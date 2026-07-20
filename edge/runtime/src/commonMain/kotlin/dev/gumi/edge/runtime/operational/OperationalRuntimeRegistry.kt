package dev.gumi.edge.runtime.operational

import dev.gumi.edge.runtime.host.RuntimeHostCleanupRequest
import dev.gumi.edge.runtime.host.RuntimeHostCleanupResult
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.host.RuntimeHostRecoveryEvent
import dev.gumi.edge.runtime.host.RuntimeHostRecoveryPort
import dev.gumi.edge.runtime.host.RuntimeHostRehydrationResult
import dev.gumi.edge.runtime.host.RuntimeHostTransportState
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One exact process-owned runtime registration. Device identity is never inferred from transport. */
data class OperationalRuntimeRegistration(
    val deviceId: DeviceId,
    val runtime: OperationalRuntimeNode,
)

/**
 * Process-level recovery owner and command directory for provisioned device runtimes.
 *
 * A single [dev.gumi.edge.runtime.host.RuntimeHost] owns the foreground execution lease above this
 * registry. Every registered device retains an independent serialized operational runtime below it.
 * Recovery starts registrations in stable [DeviceId] order and cleanup runs in reverse attempted order.
 * A failure never causes an unattempted device to open, and a successful runtime must prove that its
 * projection is bound to the exact registration identity.
 *
 * The current aggregate host policy is deliberately strict: a current-device disconnect or fault is
 * forwarded to the process host, which reconciles every registered runtime under one cleanup barrier.
 * This is safe for the one-device M1 composition and explicit for later fleet-policy replacement.
 */
class OperationalRuntimeRegistry(
    parentScope: CoroutineScope,
    registrations: Collection<OperationalRuntimeRegistration>,
    eventCapacity: Int = DEFAULT_EVENT_CAPACITY,
    private val terminalCleanupLimit: Int = DEFAULT_TERMINAL_CLEANUP_LIMIT,
) : RuntimeHostRecoveryPort {
    init {
        require(registrations.isNotEmpty()) { "Operational runtime registry cannot be empty" }
        require(eventCapacity > 0) { "Operational registry event capacity must be positive" }
        require(terminalCleanupLimit > 0) {
            "Operational registry terminal cleanup limit must be positive"
        }
        require(registrations.map { it.deviceId }.toSet().size == registrations.size) {
            "Operational runtime registrations require unique device identities"
        }
        require(registrations.indices.all { left ->
            registrations.indices.none { right ->
                left != right && registrations.elementAt(left).runtime ===
                    registrations.elementAt(right).runtime
            }
        }) {
            "One operational runtime instance cannot own multiple device identities"
        }
    }

    private val ordered = registrations
        .sortedBy { it.deviceId.value }
        .associateByTo(linkedMapOf()) { it.deviceId }
    private val operationMutex = Mutex()
    private val stateMutex = Mutex()
    private val supervisorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisorJob)
    private val eventChannel = Channel<RuntimeHostRecoveryEvent>(eventCapacity)
    private val eventJobs = ordered.map { (deviceId, registration) ->
        scope.launch {
            registration.runtime.events.collect { event -> forwardCurrentEvent(deviceId, event) }
        }
    }
    private val terminalCleanups = LinkedHashMap<RuntimeHostOperation, RuntimeHostCleanupResult>()
    private var active: ActiveRecovery? = null
    private var closed = false

    override val events: Flow<RuntimeHostRecoveryEvent> = eventChannel.receiveAsFlow()

    val deviceIds: Set<DeviceId> = ordered.keys.toSet()

    fun projection(deviceId: DeviceId): StateFlow<OperationalRuntimeProjection>? =
        ordered[deviceId]?.runtime?.projection

    /** Returns a device-scoped port. The returned object cannot route a request to another device. */
    fun powerRefreshPort(deviceId: DeviceId): OperationalPowerRefreshPort =
        OperationalPowerRefreshPort { request -> routePowerRefresh(deviceId, request) }

    suspend fun routePowerRefresh(
        deviceId: DeviceId,
        request: OperationalPowerRefreshRequest,
    ): OperationalPowerRefreshResult {
        val lookup = stateMutex.withLock {
            RuntimeLookup(closed = closed, runtime = ordered[deviceId]?.runtime)
        }
        val runtime = lookup.runtime.takeUnless { lookup.closed }
            ?: return OperationalPowerRefreshResult.Failed(
                request,
                registryPowerFailure(
                    request,
                    if (lookup.closed) {
                        FailureCategory.CANCELLED
                    } else {
                        FailureCategory.REJECTED_POLICY
                    },
                    if (lookup.closed) {
                        "OPERATIONAL_REGISTRY_CLOSED"
                    } else {
                        "OPERATIONAL_REGISTRY_DEVICE_NOT_REGISTERED"
                    },
                    retryable = false,
                ),
            )
        return try {
            runtime.refreshPower(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            OperationalPowerRefreshResult.OutcomeUnknown(
                request,
                registryPowerFailure(
                    request,
                    FailureCategory.INTERNAL,
                    "OPERATIONAL_REGISTRY_COMMAND_OUTCOME_UNKNOWN",
                    retryable = false,
                ),
            )
        }
    }

    override suspend fun rehydrateAndReconcile(
        operation: RuntimeHostOperation,
    ): RuntimeHostRehydrationResult = operationMutex.withLock {
        stateMutex.withLock {
            if (closed) {
                return@withLock RuntimeHostRehydrationResult.Failed(
                    operation,
                    registryFailure(
                        operation,
                        FailureCategory.CANCELLED,
                        "OPERATIONAL_REGISTRY_CLOSED",
                        retryable = false,
                    ),
                )
            }
            active?.let { current ->
                return@withLock if (current.owner == operation && current.terminal != null) {
                    current.terminal
                } else {
                    RuntimeHostRehydrationResult.Failed(
                        operation,
                        registryFailure(
                            operation,
                            FailureCategory.REJECTED_POLICY,
                            "OPERATIONAL_REGISTRY_ALREADY_OWNED",
                            retryable = true,
                        ),
                    )
                }
            }
            active = ActiveRecovery(operation)
            null
        }?.let { return@withLock it }

        var reconciliationFailure: ExpectedFailure? = null
        var degradedTransport = false
        for ((deviceId, registration) in ordered) {
            stateMutex.withLock { requireNotNull(active).attempted += deviceId }
            val result = try {
                registration.runtime.rehydrateAndReconcile(operation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                RuntimeHostRehydrationResult.OutcomeUnknown(
                    operation,
                    registryFailure(
                        operation,
                        FailureCategory.INTERNAL,
                        "OPERATIONAL_REGISTRY_RUNTIME_OUTCOME_UNKNOWN",
                        retryable = false,
                    ),
                )
            }
            if (result.operation != operation) {
                return@withLock rememberTerminal(
                    RuntimeHostRehydrationResult.OutcomeUnknown(
                        operation,
                        registryFailure(
                            operation,
                            FailureCategory.REPLAYED,
                            "OPERATIONAL_REGISTRY_STALE_RUNTIME_COMPLETION",
                            retryable = false,
                        ),
                    ),
                )
            }
            when (result) {
                is RuntimeHostRehydrationResult.Rehydrated -> {
                    identityMismatch(operation, deviceId, registration.runtime)?.let {
                        return@withLock rememberTerminal(it)
                    }
                    degradedTransport = degradedTransport ||
                        result.transport == RuntimeHostTransportState.DEGRADED
                }

                is RuntimeHostRehydrationResult.ReconciliationRequired -> {
                    identityMismatch(operation, deviceId, registration.runtime)?.let {
                        return@withLock rememberTerminal(it)
                    }
                    reconciliationFailure = reconciliationFailure ?: result.failure
                    degradedTransport = true
                }

                is RuntimeHostRehydrationResult.Failed -> return@withLock rememberTerminal(result)
                is RuntimeHostRehydrationResult.OutcomeUnknown ->
                    return@withLock rememberTerminal(result)
            }
        }

        rememberTerminal(
            reconciliationFailure?.let {
                RuntimeHostRehydrationResult.ReconciliationRequired(operation, it)
            } ?: RuntimeHostRehydrationResult.Rehydrated(
                operation,
                if (degradedTransport) {
                    RuntimeHostTransportState.DEGRADED
                } else {
                    RuntimeHostTransportState.READY
                },
            ),
        )
    }

    override suspend fun cleanup(
        request: RuntimeHostCleanupRequest,
    ): RuntimeHostCleanupResult = withContext(NonCancellable) {
        operationMutex.withLock {
            terminalCleanups[request.operation]?.let { return@withLock it }
            val current = stateMutex.withLock { active }
                ?: return@withLock recordCleanup(
                    request.operation,
                    RuntimeHostCleanupResult.Cleaned(request.operation),
                )
            if (!request.operation.canFence(current.owner)) {
                return@withLock recordCleanup(
                    request.operation,
                    RuntimeHostCleanupResult.Failed(
                        request.operation,
                        registryFailure(
                            request.operation,
                            FailureCategory.REPLAYED,
                            "OPERATIONAL_REGISTRY_STALE_CLEANUP_REQUEST",
                            retryable = false,
                        ),
                    ),
                )
            }

            val failures = mutableListOf<RegistryCleanupFailure>()
            current.attempted.asReversed().forEach { deviceId ->
                val result = try {
                    requireNotNull(ordered[deviceId]).runtime.cleanup(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    RuntimeHostCleanupResult.OutcomeUnknown(
                        request.operation,
                        registryFailure(
                            request.operation,
                            FailureCategory.INTERNAL,
                            "OPERATIONAL_REGISTRY_CLEANUP_OUTCOME_UNKNOWN",
                            retryable = false,
                        ),
                    )
                }
                when {
                    result.operation != request.operation -> failures += RegistryCleanupFailure(
                        registryFailure(
                            request.operation,
                            FailureCategory.REPLAYED,
                            "OPERATIONAL_REGISTRY_STALE_CLEANUP_COMPLETION",
                            retryable = false,
                        ),
                        outcomeUnknown = true,
                    )

                    result is RuntimeHostCleanupResult.Failed -> failures +=
                        RegistryCleanupFailure(result.failure, outcomeUnknown = false)

                    result is RuntimeHostCleanupResult.OutcomeUnknown -> failures +=
                        RegistryCleanupFailure(result.failure, outcomeUnknown = true)

                    result is RuntimeHostCleanupResult.Cleaned -> Unit
                }
            }

            val result = when {
                failures.isEmpty() -> RuntimeHostCleanupResult.Cleaned(request.operation)
                failures.any(RegistryCleanupFailure::outcomeUnknown) -> {
                    RuntimeHostCleanupResult.OutcomeUnknown(
                        request.operation,
                        failures.first(RegistryCleanupFailure::outcomeUnknown).failure,
                    )
                }

                else -> RuntimeHostCleanupResult.Failed(
                    request.operation,
                    failures.first().failure,
                )
            }
            if (result is RuntimeHostCleanupResult.Cleaned) {
                stateMutex.withLock { if (active === current) active = null }
            }
            recordCleanup(request.operation, result)
        }
    }

    /** Closes every node only after the process host has cleaned the active recovery owner. */
    suspend fun close(): Unit = withContext(NonCancellable) {
        operationMutex.withLock {
            val shouldClose = stateMutex.withLock {
                if (closed) return@withLock false
                check(active == null) { "Operational registry cleanup must complete before close" }
                closed = true
                true
            }
            if (!shouldClose) return@withLock
            eventJobs.forEach(Job::cancel)
            eventJobs.forEach { it.join() }
            ordered.values.toList().asReversed().forEach { it.runtime.close() }
            eventChannel.close()
            supervisorJob.cancelAndJoin()
        }
    }

    private suspend fun rememberTerminal(
        result: RuntimeHostRehydrationResult,
    ): RuntimeHostRehydrationResult {
        stateMutex.withLock { requireNotNull(active).terminal = result }
        return result
    }

    private fun identityMismatch(
        operation: RuntimeHostOperation,
        registeredDeviceId: DeviceId,
        runtime: OperationalRuntimeNode,
    ): RuntimeHostRehydrationResult.Failed? =
        if (runtime.projection.value.deviceId == registeredDeviceId) {
            null
        } else {
            RuntimeHostRehydrationResult.Failed(
                operation,
                registryFailure(
                    operation,
                    FailureCategory.UNAUTHORIZED,
                    "OPERATIONAL_REGISTRY_DEVICE_IDENTITY_MISMATCH",
                    retryable = false,
                ),
            )
        }

    private suspend fun forwardCurrentEvent(
        deviceId: DeviceId,
        event: RuntimeHostRecoveryEvent,
    ) {
        val current = stateMutex.withLock {
            active?.takeIf { event.operation == it.owner && deviceId in it.attempted }
        } ?: return
        if (event.operation == current.owner) eventChannel.send(event)
    }

    private fun recordCleanup(
        operation: RuntimeHostOperation,
        result: RuntimeHostCleanupResult,
    ): RuntimeHostCleanupResult {
        terminalCleanups[operation] = result
        while (terminalCleanups.size > terminalCleanupLimit) {
            terminalCleanups.remove(terminalCleanups.keys.first())
        }
        return result
    }

    private data class ActiveRecovery(
        val owner: RuntimeHostOperation,
        val attempted: MutableList<DeviceId> = mutableListOf(),
        var terminal: RuntimeHostRehydrationResult? = null,
    )

    private data class RegistryCleanupFailure(
        val failure: ExpectedFailure,
        val outcomeUnknown: Boolean,
    )

    private data class RuntimeLookup(
        val closed: Boolean,
        val runtime: OperationalRuntimeNode?,
    )

    companion object {
        const val DEFAULT_EVENT_CAPACITY: Int = 64
        const val DEFAULT_TERMINAL_CLEANUP_LIMIT: Int = 256
    }
}

private fun RuntimeHostOperation.canFence(owner: RuntimeHostOperation): Boolean = when {
    generation > owner.generation -> true
    generation < owner.generation -> false
    else -> this == owner
}

private fun registryFailure(
    operation: RuntimeHostOperation,
    category: FailureCategory,
    code: String,
    retryable: Boolean,
): ExpectedFailure = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
    correlationId = operation.correlationId,
)

private fun registryPowerFailure(
    request: OperationalPowerRefreshRequest,
    category: FailureCategory,
    code: String,
    retryable: Boolean,
): ExpectedFailure = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
    correlationId = request.correlationId,
)
