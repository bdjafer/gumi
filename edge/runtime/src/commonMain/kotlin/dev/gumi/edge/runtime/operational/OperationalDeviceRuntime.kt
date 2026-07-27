package dev.gumi.edge.runtime.operational

import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.runtime.DriverResolutionException
import dev.gumi.edge.runtime.host.RuntimeHostCleanupRequest
import dev.gumi.edge.runtime.host.RuntimeHostCleanupResult
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.host.RuntimeHostRecoveryEvent
import dev.gumi.edge.runtime.host.RuntimeHostRehydrationResult
import dev.gumi.edge.runtime.host.RuntimeHostTransportState
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.DeviceOpenException
import dev.gumi.edge.sdk.DeviceSession
import dev.gumi.edge.sdk.DeviceSessionEvent
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.NegotiatedDeviceSession
import dev.gumi.edge.sdk.TransportSession
import dev.gumi.edge.sdk.ble.BleConnectionOptions
import dev.gumi.edge.sdk.ble.BleSessionException
import dev.gumi.edge.sdk.capability.capture.CaptureStateHandle
import dev.gumi.edge.sdk.capability.capture.CaptureStateV1
import dev.gumi.edge.sdk.capability.capture.DeviceCaptureState
import dev.gumi.edge.sdk.capability.power.PowerStatus
import dev.gumi.edge.sdk.capability.power.PowerStatusHandle
import dev.gumi.edge.sdk.capability.power.PowerStatusV1
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Portable operational composition for one provisioned device.
 *
 * Acquisition is deliberately strict: durable binding, reconciled storage, transport ownership,
 * ephemeral endpoint resolution, BLE connection, driver negotiation, initial observational capability
 * reads, then lifetime collectors. No capture command, media read, ring-buffer operation, or device
 * write occurs.
 */
class OperationalDeviceRuntime(
    parentScope: CoroutineScope,
    private val bindings: ProvisionedDeviceBindingPort,
    private val storage: OperationalStoragePort,
    private val transportLeases: DeviceTransportLeasePort,
    private val endpoints: OperationalEndpointResolutionPort,
    private val drivers: DeviceDriverRegistry,
    private val connectionOptions: BleConnectionOptions = BleConnectionOptions(),
    eventCapacity: Int = DEFAULT_EVENT_CAPACITY,
) : OperationalRuntimeNode {
    init {
        require(eventCapacity > 0) { "Operational runtime event capacity must be positive" }
    }

    private val operationMutex = Mutex()
    private val stateMutex = Mutex()
    private val supervisorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisorJob)
    private val eventChannel = Channel<RuntimeHostRecoveryEvent>(eventCapacity)
    private var active: OwnedResources? = null
    private var nextSessionGeneration = 0uL
    private var closed = false
    private var unresolvedCleanup: CleanupFailure? = null
    private val terminalCleanups = LinkedHashMap<RuntimeHostOperation, RuntimeHostCleanupResult>()
    private val terminalPowerRefreshes =
        LinkedHashMap<CommandId, OperationalPowerRefreshRecord>()

    private val mutableProjection = MutableStateFlow(OperationalRuntimeProjection())
    override val projection: StateFlow<OperationalRuntimeProjection> = mutableProjection.asStateFlow()

    override val events: Flow<RuntimeHostRecoveryEvent> = eventChannel.receiveAsFlow()

    override suspend fun rehydrateAndReconcile(
        operation: RuntimeHostOperation,
    ): RuntimeHostRehydrationResult = operationMutex.withLock {
        if (closed) {
            return@withLock RuntimeHostRehydrationResult.Failed(
                operation,
                failure(operation, FailureCategory.CANCELLED, "OPERATIONAL_RUNTIME_CLOSED", false),
            )
        }
        unresolvedCleanup?.let { unresolved ->
            return@withLock RuntimeHostRehydrationResult.Failed(
                operation,
                unresolved.failure.copy(correlationId = operation.correlationId),
            )
        }

        val alreadyOwned = stateMutex.withLock { active }
        if (alreadyOwned != null) {
            return@withLock if (
                alreadyOwned.ownerOperation == operation &&
                mutableProjection.value.lifecycle == OperationalRuntimeLifecycle.READY
            ) {
                RuntimeHostRehydrationResult.Rehydrated(operation, RuntimeHostTransportState.READY)
            } else {
                RuntimeHostRehydrationResult.Failed(
                    operation,
                    failure(
                        operation,
                        FailureCategory.REJECTED_POLICY,
                        "OPERATIONAL_RUNTIME_ALREADY_OWNED",
                        true,
                    ),
                )
            }
        }
        if (nextSessionGeneration == ULong.MAX_VALUE) {
            return@withLock RuntimeHostRehydrationResult.Failed(
                operation,
                failure(
                    operation,
                    FailureCategory.RESOURCE_EXHAUSTED,
                    "OPERATIONAL_SESSION_GENERATION_EXHAUSTED",
                    false,
                ),
            )
        }

        nextSessionGeneration += 1uL
        val runtimeOperation = OperationalRuntimeOperation(operation, nextSessionGeneration)
        val owned = OwnedResources(operation, runtimeOperation)
        stateMutex.withLock {
            active = owned
            updateProjectionLocked {
                it.copy(
                    lifecycle = OperationalRuntimeLifecycle.STARTING,
                    ownerOperation = operation,
                    sessionGeneration = runtimeOperation.sessionGeneration,
                    deviceId = null,
                    link = OperationalLinkState.UNKNOWN,
                    capture = OperationalCaptureTruth.UNVERIFIED,
                    captureState = null,
                    captureObservationRevision = 0uL,
                    power = null,
                    powerObservationRevision = 0uL,
                    storage = OperationalStorageState.CLOSED,
                    backlog = OperationalBacklog.Empty,
                    backlogScope = OperationalBacklogScope.UNAVAILABLE,
                    lastFailure = null,
                )
            }
        }

        try {
            val bindingResult = bindings.load(runtimeOperation)
            if (bindingResult.operation != runtimeOperation) {
                return@withLock staleRehydration(
                    operation,
                    "OPERATIONAL_STALE_BINDING_COMPLETION",
                )
            }
            val binding = when (bindingResult) {
                is ProvisionedDeviceBindingResult.Bound -> bindingResult.binding
                is ProvisionedDeviceBindingResult.Failed -> {
                    return@withLock failedRehydration(operation, bindingResult.failure)
                }
            }
            owned.binding = binding
            stateMutex.withLock {
                updateProjectionLocked { it.copy(deviceId = binding.deviceId) }
            }

            stateMutex.withLock {
                updateProjectionLocked { it.copy(storage = OperationalStorageState.OPENING) }
            }
            val storageResult = storage.openAndReconcile(runtimeOperation, binding)
            if (storageResult is OperationalStorageOpenResult.Ready) {
                // A returned lease belongs to this invocation even when the adapter echoed a stale
                // completion identity; retain it so the mandated cleanup can release it.
                owned.storage = storageResult.lease
            } else if (storageResult is OperationalStorageOpenResult.OutcomeUnknown) {
                // The adapter still owns a possibly-live resource. Retain its lease even for a stale
                // completion so cleanup can quiesce/close it instead of making ownership unreachable.
                owned.storage = storageResult.lease
            }
            if (storageResult.operation != runtimeOperation) {
                return@withLock staleRehydration(
                    operation,
                    "OPERATIONAL_STALE_STORAGE_COMPLETION",
                )
            }
            when (storageResult) {
                is OperationalStorageOpenResult.Failed -> {
                    stateMutex.withLock {
                        updateProjectionLocked { it.copy(storage = OperationalStorageState.DEGRADED) }
                    }
                    return@withLock failedRehydration(operation, storageResult.failure)
                }

                is OperationalStorageOpenResult.OutcomeUnknown -> {
                    stateMutex.withLock {
                        updateProjectionLocked { it.copy(storage = OperationalStorageState.DEGRADED) }
                    }
                    return@withLock outcomeUnknownRehydration(operation, storageResult.failure)
                }

                is OperationalStorageOpenResult.Ready -> stateMutex.withLock {
                    updateProjectionLocked {
                        it.copy(
                            storage = OperationalStorageState.READY,
                            backlog = storageResult.backlog,
                            backlogScope = storageResult.backlogScope,
                        )
                    }
                }
            }

            val transportResult = transportLeases.acquire(runtimeOperation, binding)
            if (transportResult is DeviceTransportLeaseResult.Acquired) {
                owned.transportLease = transportResult.lease
            }
            if (transportResult.operation != runtimeOperation) {
                return@withLock staleRehydration(
                    operation,
                    "OPERATIONAL_STALE_TRANSPORT_LEASE_COMPLETION",
                )
            }
            val transportLease = when (transportResult) {
                is DeviceTransportLeaseResult.Acquired -> transportResult.lease
                is DeviceTransportLeaseResult.Failed -> {
                    return@withLock failedRehydration(operation, transportResult.failure)
                }
            }

            val endpointResult = endpoints.resolve(runtimeOperation, binding)
            if (endpointResult.operation != runtimeOperation) {
                return@withLock staleRehydration(
                    operation,
                    "OPERATIONAL_STALE_ENDPOINT_COMPLETION",
                )
            }
            val endpoint = when (endpointResult) {
                is OperationalEndpointResolutionResult.Resolved -> endpointResult.endpoint
                is OperationalEndpointResolutionResult.Failed -> {
                    return@withLock failedRehydration(operation, endpointResult.failure)
                }
            }
            stateMutex.withLock {
                updateProjectionLocked { it.copy(link = OperationalLinkState.CONNECTING) }
            }

            val transport = transportLease.bleCentral.connect(endpoint, connectionOptions)
            owned.transportSession = transport
            val opened = drivers.open(endpoint, transport)
            owned.deviceSession = opened
            // Ownership of transport cleanup transfers to a successfully opened device session.
            owned.transportSession = null
            val session = opened as? NegotiatedDeviceSession ?: return@withLock failedRehydration(
                operation,
                failure(
                    operation,
                    FailureCategory.INCOMPATIBLE,
                    "OPERATIONAL_SESSION_NOT_NEGOTIATED",
                    false,
                ),
            )
            if (session.deviceId != null && session.deviceId != binding.deviceId) {
                return@withLock failedRehydration(
                    operation,
                    failure(
                        operation,
                        FailureCategory.UNAUTHORIZED,
                        "OPERATIONAL_SESSION_DEVICE_ID_MISMATCH",
                        false,
                    ),
                )
            }

            val powerHandle = session.capabilities.handle(PowerStatusV1)
            val captureHandle = session.capabilities.handle(CaptureStateV1)
            owned.powerHandle = powerHandle
            owned.captureHandle = captureHandle
            val initialPower = powerHandle?.read()
            val initialCapture = captureHandle?.read()
            stateMutex.withLock {
                updateProjectionLocked {
                    it.copy(
                        link = OperationalLinkState.CONNECTED,
                        capture = if (initialCapture == null) {
                            OperationalCaptureTruth.UNVERIFIED
                        } else {
                            OperationalCaptureTruth.DEVICE_REPORTED
                        },
                        captureState = initialCapture,
                        captureObservationRevision = if (initialCapture == null) 0uL else 1uL,
                        power = initialPower,
                        powerObservationRevision = if (initialPower == null) 0uL else 1uL,
                    )
                }
            }
            owned.collectors += startSessionCollector(owned, session)
            if (powerHandle != null) owned.collectors += startPowerCollector(owned, powerHandle)
            if (captureHandle != null) {
                owned.collectors += startCaptureCollector(owned, captureHandle)
            }

            val startupFault = stateMutex.withLock {
                if (active !== owned) {
                    failure(
                        operation,
                        FailureCategory.REPLAYED,
                        "OPERATIONAL_START_FENCED_BEFORE_READY",
                        false,
                    )
                } else if (mutableProjection.value.lifecycle == OperationalRuntimeLifecycle.DEGRADED) {
                    mutableProjection.value.lastFailure
                } else {
                    updateProjectionLocked { it.copy(lifecycle = OperationalRuntimeLifecycle.READY) }
                    null
                }
            }
            if (startupFault != null) {
                return@withLock RuntimeHostRehydrationResult.ReconciliationRequired(
                    operation,
                    startupFault,
                )
            }
            RuntimeHostRehydrationResult.Rehydrated(operation, RuntimeHostTransportState.READY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failedRehydration(operation, error.toOperationalFailure(operation))
        }
    }

    /**
     * Performs one observational device power read for the exact currently-owned session.
     *
     * The operation mutex prevents teardown/replacement from crossing the read. Session events may
     * still disconnect the device while the read is suspended, so completion is checked again under
     * the state mutex before any status is published.
     */
    override suspend fun refreshPower(
        request: OperationalPowerRefreshRequest,
    ): OperationalPowerRefreshResult = operationMutex.withLock {
        terminalPowerRefreshes[request.commandId]?.let { prior ->
            return@withLock if (prior.request == request) {
                prior.result.asReplayed()
            } else {
                OperationalPowerRefreshResult.Failed(
                    request,
                    refreshFailure(
                        request,
                        FailureCategory.CORRUPT,
                        "OPERATIONAL_POWER_REFRESH_ID_CONFLICT",
                        false,
                    ),
                )
            }
        }
        if (closed) {
            return@withLock recordPowerRefresh(
                OperationalPowerRefreshResult.Failed(
                    request,
                    refreshFailure(
                        request,
                        FailureCategory.CANCELLED,
                        "OPERATIONAL_RUNTIME_CLOSED",
                        false,
                    ),
                ),
            )
        }

        val owned = stateMutex.withLock { active }
            ?: return@withLock recordPowerRefresh(
                OperationalPowerRefreshResult.Failed(
                    request,
                    refreshFailure(
                        request,
                        FailureCategory.REJECTED_POLICY,
                        "OPERATIONAL_POWER_REFRESH_NOT_READY",
                        false,
                    ),
                ),
            )
        if (request.expectedOwner != owned.runtimeOperation) {
            markStale()
            return@withLock recordPowerRefresh(
                OperationalPowerRefreshResult.Failed(
                    request,
                    refreshFailure(
                        request,
                        FailureCategory.REPLAYED,
                        "OPERATIONAL_STALE_POWER_REFRESH_REQUEST",
                        false,
                    ),
                ),
            )
        }

        val ready = stateMutex.withLock {
            val current = mutableProjection.value
            active === owned &&
                current.lifecycle == OperationalRuntimeLifecycle.READY &&
                current.link == OperationalLinkState.CONNECTED
        }
        if (!ready) {
            return@withLock recordPowerRefresh(
                OperationalPowerRefreshResult.Failed(
                    request,
                    refreshFailure(
                        request,
                        FailureCategory.REJECTED_POLICY,
                        "OPERATIONAL_POWER_REFRESH_NOT_READY",
                        true,
                    ),
                ),
            )
        }
        val power = owned.powerHandle
            ?: return@withLock recordPowerRefresh(
                OperationalPowerRefreshResult.Failed(
                    request,
                    refreshFailure(
                        request,
                        FailureCategory.INCOMPATIBLE,
                        "OPERATIONAL_POWER_STATUS_UNAVAILABLE",
                        false,
                    ),
                ),
            )
        val powerRevisionExhausted = stateMutex.withLock {
            mutableProjection.value.powerObservationRevision == ULong.MAX_VALUE
        }
        if (powerRevisionExhausted) {
            return@withLock recordPowerRefresh(
                OperationalPowerRefreshResult.Failed(
                    request,
                    refreshFailure(
                        request,
                        FailureCategory.RESOURCE_EXHAUSTED,
                        "OPERATIONAL_POWER_REVISION_EXHAUSTED",
                        false,
                    ),
                ),
            )
        }

        val status = try {
            power.read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withLock recordPowerRefresh(
                OperationalPowerRefreshResult.OutcomeUnknown(
                    request,
                    error.toPowerRefreshFailure(request),
                ),
            )
        }

        val result = stateMutex.withLock {
            val current = mutableProjection.value
            if (
                active !== owned ||
                current.ownerOperation != owned.ownerOperation ||
                current.sessionGeneration != owned.runtimeOperation.sessionGeneration ||
                current.lifecycle != OperationalRuntimeLifecycle.READY ||
                current.link != OperationalLinkState.CONNECTED
            ) {
                markStaleLocked()
                OperationalPowerRefreshResult.OutcomeUnknown(
                    request,
                    refreshFailure(
                        request,
                        FailureCategory.DISCONNECTED,
                        "OPERATIONAL_POWER_REFRESH_COMPLETION_FENCED",
                        true,
                    ),
                )
            } else if (current.powerObservationRevision == ULong.MAX_VALUE) {
                OperationalPowerRefreshResult.OutcomeUnknown(
                    request,
                    refreshFailure(
                        request,
                        FailureCategory.RESOURCE_EXHAUSTED,
                        "OPERATIONAL_POWER_REVISION_EXHAUSTED",
                        false,
                    ),
                )
            } else {
                updateProjectionLocked {
                    it.copy(
                        power = status,
                        powerObservationRevision = it.powerObservationRevision + 1uL,
                    )
                }
                OperationalPowerRefreshResult.Completed(request, status)
            }
        }
        recordPowerRefresh(result)
    }

    override suspend fun cleanup(
        request: RuntimeHostCleanupRequest,
    ): RuntimeHostCleanupResult = withContext(NonCancellable) {
        operationMutex.withLock {
            terminalCleanups[request.operation]?.let { return@withLock it }

            val owned = stateMutex.withLock {
                val current = active
                if (current == null) return@withLock null
                if (!request.operation.canFence(current.ownerOperation)) {
                    markStaleLocked()
                    return@withLock current.copy(staleCleanupRejected = true)
                }
                active = null
                updateProjectionLocked {
                    it.copy(
                        lifecycle = OperationalRuntimeLifecycle.STOPPING,
                        ownerOperation = null,
                        sessionGeneration = null,
                        lastFailure = null,
                    )
                }
                current
            }

            if (owned?.staleCleanupRejected == true) {
                val result = RuntimeHostCleanupResult.Failed(
                    request.operation,
                    failure(
                        request.operation,
                        FailureCategory.REPLAYED,
                        "OPERATIONAL_STALE_CLEANUP_REQUEST",
                        false,
                    ),
                )
                return@withLock recordCleanup(request.operation, result)
            }
            if (owned == null) {
                unresolvedCleanup?.let { unresolved ->
                    val failure = unresolved.failure.copy(
                        correlationId = request.operation.correlationId,
                    )
                    val result = if (unresolved.outcomeUnknown) {
                        RuntimeHostCleanupResult.OutcomeUnknown(request.operation, failure)
                    } else {
                        RuntimeHostCleanupResult.Failed(request.operation, failure)
                    }
                    return@withLock recordCleanup(request.operation, result)
                }
                return@withLock recordCleanup(
                    request.operation,
                    RuntimeHostCleanupResult.Cleaned(request.operation),
                )
            }

            val cleanupOperation = OperationalRuntimeOperation(
                request.operation,
                owned.runtimeOperation.sessionGeneration,
            )
            val failures = mutableListOf<CleanupFailure>()

            owned.collectors.forEach(Job::cancel)
            owned.collectors.forEach { collector -> collector.join() }

            val session = owned.deviceSession
            val rawTransport = owned.transportSession
            try {
                if (session != null) session.close() else rawTransport?.close()
            } catch (error: Throwable) {
                failures += CleanupFailure(
                    error.toCleanupFailure(request.operation, "OPERATIONAL_SESSION_CLOSE_FAILED"),
                    outcomeUnknown = true,
                    sessionClose = true,
                )
            }

            owned.transportLease?.let { lease ->
                try {
                    failures += lease.release(cleanupOperation).cleanupFailures(
                        expected = cleanupOperation,
                        staleCode = "OPERATIONAL_STALE_TRANSPORT_RELEASE_COMPLETION",
                        stale = ::markStale,
                    )
                } catch (error: Throwable) {
                    failures += CleanupFailure(
                        error.toCleanupFailure(
                            request.operation,
                            "OPERATIONAL_TRANSPORT_RELEASE_FAILED",
                        ),
                        outcomeUnknown = true,
                    )
                }
            }
            owned.storage?.let { lease ->
                try {
                    failures += lease.quiesce(cleanupOperation).cleanupFailures(
                        expected = cleanupOperation,
                        staleCode = "OPERATIONAL_STALE_STORAGE_QUIESCE_COMPLETION",
                        stale = ::markStale,
                    )
                } catch (error: Throwable) {
                    failures += CleanupFailure(
                        error.toCleanupFailure(
                            request.operation,
                            "OPERATIONAL_STORAGE_QUIESCE_FAILED",
                        ),
                        outcomeUnknown = true,
                    )
                }
                try {
                    failures += lease.close(cleanupOperation).cleanupFailures(
                        expected = cleanupOperation,
                        staleCode = "OPERATIONAL_STALE_STORAGE_CLOSE_COMPLETION",
                        stale = ::markStale,
                    )
                } catch (error: Throwable) {
                    failures += CleanupFailure(
                        error.toCleanupFailure(
                            request.operation,
                            "OPERATIONAL_STORAGE_CLOSE_FAILED",
                        ),
                        outcomeUnknown = true,
                        storageClose = true,
                    )
                }
            }

            val firstFailure = failures.firstOrNull()
            val unresolvedFailure = failures.firstOrNull(CleanupFailure::outcomeUnknown)
                ?: firstFailure
            unresolvedCleanup = unresolvedFailure
            stateMutex.withLock {
                updateProjectionLocked {
                    it.copy(
                        lifecycle = if (unresolvedFailure == null) {
                            OperationalRuntimeLifecycle.STOPPED
                        } else {
                            OperationalRuntimeLifecycle.DEGRADED
                        },
                        link = if (failures.any { failure -> failure.sessionClose }) {
                            OperationalLinkState.UNKNOWN
                        } else {
                            OperationalLinkState.DISCONNECTED
                        },
                        capture = OperationalCaptureTruth.UNVERIFIED,
                        storage = if (failures.any { failure -> failure.storageClose }) {
                            OperationalStorageState.DEGRADED
                        } else {
                            OperationalStorageState.CLOSED
                        },
                        backlog = OperationalBacklog.Empty,
                        backlogScope = OperationalBacklogScope.UNAVAILABLE,
                        lastFailure = unresolvedFailure?.failure,
                    )
                }
            }
            val result = when {
                unresolvedFailure == null -> RuntimeHostCleanupResult.Cleaned(request.operation)
                failures.any(CleanupFailure::outcomeUnknown) -> RuntimeHostCleanupResult.OutcomeUnknown(
                    request.operation,
                    unresolvedFailure.failure,
                )

                else -> RuntimeHostCleanupResult.Failed(request.operation, unresolvedFailure.failure)
            }
            recordCleanup(request.operation, result)
        }
    }

    /** Permanently closes the process-local owner after its resources have been cleaned. */
    override suspend fun close(): Unit = withContext(NonCancellable) {
        operationMutex.withLock {
            if (closed) return@withLock
            val canClose = stateMutex.withLock {
                active == null && mutableProjection.value.storage == OperationalStorageState.CLOSED
            }
            check(canClose) { "Operational runtime cleanup must complete before close" }
            closed = true
            supervisorJob.cancelAndJoin()
            eventChannel.close()
            stateMutex.withLock {
                updateProjectionLocked {
                    it.copy(
                        lifecycle = OperationalRuntimeLifecycle.CLOSED,
                        ownerOperation = null,
                        sessionGeneration = null,
                    )
                }
            }
        }
    }

    private fun startSessionCollector(
        owned: OwnedResources,
        session: DeviceSession,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var terminalPublished = false
        try {
            session.events.collect { event ->
                if (event == DeviceSessionEvent.Closed && !terminalPublished) {
                    terminalPublished = true
                    publishDisconnected(owned)
                }
                // Diagnostics have no generic fatality contract and therefore cannot change truth.
            }
            if (!terminalPublished) publishDisconnected(owned)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            publishFault(owned, "OPERATIONAL_SESSION_EVENTS_FAILED")
        }
    }

    private fun startPowerCollector(
        owned: OwnedResources,
        power: PowerStatusHandle,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            power.updates.collect { observation -> publishPower(owned, observation) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            publishFault(owned, "OPERATIONAL_POWER_UPDATES_FAILED")
        }
    }

    private fun startCaptureCollector(
        owned: OwnedResources,
        capture: CaptureStateHandle,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            capture.updates.collect { observation -> publishCapture(owned, observation) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            publishCaptureFault(owned)
        }
    }

    private suspend fun publishPower(owned: OwnedResources, power: PowerStatus) {
        stateMutex.withLock {
            if (active !== owned) {
                markStaleLocked()
                return
            }
            if (mutableProjection.value.powerObservationRevision == ULong.MAX_VALUE) {
                markStaleLocked()
                return
            }
            updateProjectionLocked {
                it.copy(
                    power = power,
                    powerObservationRevision = it.powerObservationRevision + 1uL,
                )
            }
        }
    }

    private suspend fun publishCapture(
        owned: OwnedResources,
        capture: DeviceCaptureState,
    ) {
        stateMutex.withLock {
            if (active !== owned) {
                markStaleLocked()
                return
            }
            if (mutableProjection.value.captureObservationRevision == ULong.MAX_VALUE) {
                markStaleLocked()
                return
            }
            updateProjectionLocked {
                it.copy(
                    capture = OperationalCaptureTruth.DEVICE_REPORTED,
                    captureState = capture,
                    captureObservationRevision = it.captureObservationRevision + 1uL,
                )
            }
        }
    }

    private suspend fun publishCaptureFault(owned: OwnedResources) {
        val failure = failure(
            owned.ownerOperation,
            FailureCategory.CORRUPT,
            "OPERATIONAL_CAPTURE_UPDATES_FAILED",
            true,
        )
        val accepted = stateMutex.withLock {
            if (active !== owned) {
                markStaleLocked()
                false
            } else {
                updateProjectionLocked {
                    it.copy(
                        lifecycle = OperationalRuntimeLifecycle.DEGRADED,
                        capture = OperationalCaptureTruth.UNVERIFIED,
                        lastFailure = failure,
                    )
                }
                true
            }
        }
        if (accepted) eventChannel.send(RuntimeHostRecoveryEvent.Faulted(owned.ownerOperation, failure))
    }

    private suspend fun publishDisconnected(owned: OwnedResources) {
        val failure = failure(
            owned.ownerOperation,
            FailureCategory.DISCONNECTED,
            "OPERATIONAL_DEVICE_DISCONNECTED",
            true,
        )
        val accepted = stateMutex.withLock {
            if (active !== owned) {
                markStaleLocked()
                false
            } else {
                updateProjectionLocked {
                    it.copy(
                        lifecycle = OperationalRuntimeLifecycle.DEGRADED,
                        link = OperationalLinkState.DISCONNECTED,
                        capture = OperationalCaptureTruth.UNVERIFIED,
                        lastFailure = failure,
                    )
                }
                true
            }
        }
        if (accepted) {
            eventChannel.send(
                RuntimeHostRecoveryEvent.TransportDisconnected(owned.ownerOperation, failure),
            )
        }
    }

    private suspend fun publishFault(owned: OwnedResources, code: String) {
        val failure = failure(
            owned.ownerOperation,
            FailureCategory.INTERNAL,
            code,
            true,
        )
        val accepted = stateMutex.withLock {
            if (active !== owned) {
                markStaleLocked()
                false
            } else {
                updateProjectionLocked {
                    it.copy(
                        lifecycle = OperationalRuntimeLifecycle.DEGRADED,
                        lastFailure = failure,
                    )
                }
                true
            }
        }
        if (accepted) eventChannel.send(RuntimeHostRecoveryEvent.Faulted(owned.ownerOperation, failure))
    }

    private suspend fun failedRehydration(
        operation: RuntimeHostOperation,
        sourceFailure: ExpectedFailure,
    ): RuntimeHostRehydrationResult.Failed {
        val normalized = sourceFailure.withCorrelation(operation)
        stateMutex.withLock {
            updateProjectionLocked {
                it.copy(
                    lifecycle = OperationalRuntimeLifecycle.DEGRADED,
                    lastFailure = normalized,
                )
            }
        }
        return RuntimeHostRehydrationResult.Failed(operation, normalized)
    }

    private suspend fun outcomeUnknownRehydration(
        operation: RuntimeHostOperation,
        sourceFailure: ExpectedFailure,
    ): RuntimeHostRehydrationResult.OutcomeUnknown {
        val normalized = sourceFailure.withCorrelation(operation)
        stateMutex.withLock {
            updateProjectionLocked {
                it.copy(
                    lifecycle = OperationalRuntimeLifecycle.DEGRADED,
                    lastFailure = normalized,
                )
            }
        }
        return RuntimeHostRehydrationResult.OutcomeUnknown(operation, normalized)
    }

    private suspend fun staleRehydration(
        operation: RuntimeHostOperation,
        code: String,
    ): RuntimeHostRehydrationResult.Failed {
        markStale()
        return failedRehydration(
            operation,
            failure(operation, FailureCategory.REPLAYED, code, false),
        )
    }

    private suspend fun markStale() = stateMutex.withLock { markStaleLocked() }

    private fun markStaleLocked() {
        updateProjectionLocked {
            it.copy(
                staleEventCount = if (it.staleEventCount == ULong.MAX_VALUE) {
                    ULong.MAX_VALUE
                } else {
                    it.staleEventCount + 1uL
                },
            )
        }
    }

    private fun updateProjectionLocked(
        transform: (OperationalRuntimeProjection) -> OperationalRuntimeProjection,
    ) {
        val prior = mutableProjection.value
        mutableProjection.value = transform(prior).copy(sequence = prior.sequence + 1L)
    }

    private fun recordCleanup(
        operation: RuntimeHostOperation,
        result: RuntimeHostCleanupResult,
    ): RuntimeHostCleanupResult {
        terminalCleanups[operation] = result
        while (terminalCleanups.size > TERMINAL_CLEANUP_LIMIT) {
            terminalCleanups.remove(terminalCleanups.keys.first())
        }
        return result
    }

    private fun recordPowerRefresh(
        result: OperationalPowerRefreshResult,
    ): OperationalPowerRefreshResult {
        terminalPowerRefreshes[result.request.commandId] = OperationalPowerRefreshRecord(
            result.request,
            result,
        )
        while (terminalPowerRefreshes.size > TERMINAL_POWER_REFRESH_LIMIT) {
            terminalPowerRefreshes.remove(terminalPowerRefreshes.keys.first())
        }
        return result
    }

    companion object {
        const val DEFAULT_EVENT_CAPACITY: Int = 64
        private const val TERMINAL_CLEANUP_LIMIT: Int = 64
        private const val TERMINAL_POWER_REFRESH_LIMIT: Int = 64
    }
}

private data class OwnedResources(
    val ownerOperation: RuntimeHostOperation,
    val runtimeOperation: OperationalRuntimeOperation,
    var binding: ProvisionedDeviceBinding? = null,
    var storage: OperationalStorageLease? = null,
    var transportLease: DeviceTransportLease? = null,
    var transportSession: TransportSession? = null,
    var deviceSession: DeviceSession? = null,
    var powerHandle: PowerStatusHandle? = null,
    var captureHandle: CaptureStateHandle? = null,
    val collectors: MutableList<Job> = mutableListOf(),
    val staleCleanupRejected: Boolean = false,
)

private data class CleanupFailure(
    val failure: ExpectedFailure,
    val outcomeUnknown: Boolean,
    val sessionClose: Boolean = false,
    val storageClose: Boolean = false,
)

private data class OperationalPowerRefreshRecord(
    val request: OperationalPowerRefreshRequest,
    val result: OperationalPowerRefreshResult,
)

private fun OperationalPowerRefreshResult.asReplayed(): OperationalPowerRefreshResult = when (this) {
    is OperationalPowerRefreshResult.Completed -> copy(replayed = true)
    is OperationalPowerRefreshResult.Failed -> copy(replayed = true)
    is OperationalPowerRefreshResult.OutcomeUnknown -> copy(replayed = true)
}

private fun RuntimeHostOperation.canFence(owner: RuntimeHostOperation): Boolean = when {
    generation > owner.generation -> true
    generation < owner.generation -> false
    else -> this == owner
}

private suspend fun OperationalLeaseResult.cleanupFailures(
    expected: OperationalRuntimeOperation,
    staleCode: String,
    stale: suspend () -> Unit,
): List<CleanupFailure> {
    if (operation != expected) {
        stale()
        return listOf(
            CleanupFailure(
                failure(
                    expected.hostOperation,
                    FailureCategory.REPLAYED,
                    staleCode,
                    false,
                ),
                outcomeUnknown = true,
                storageClose = staleCode.contains("STORAGE_CLOSE"),
            ),
        )
    }
    return when (this) {
        is OperationalLeaseResult.Completed -> emptyList()
        is OperationalLeaseResult.Failed -> listOf(
            CleanupFailure(
                failure.withCorrelation(expected.hostOperation),
                outcomeUnknown = false,
                storageClose = staleCode.contains("STORAGE_CLOSE"),
            ),
        )

        is OperationalLeaseResult.OutcomeUnknown -> listOf(
            CleanupFailure(
                failure.withCorrelation(expected.hostOperation),
                outcomeUnknown = true,
                storageClose = staleCode.contains("STORAGE_CLOSE"),
            ),
        )
    }
}

private fun Throwable.toOperationalFailure(operation: RuntimeHostOperation): ExpectedFailure = when (this) {
    is DeviceOpenException -> failure.withCorrelation(operation)
    is BleSessionException -> failure(
        operation,
        when (code.name) {
            "PERMISSION_DENIED" -> FailureCategory.PERMISSION
            "TIMEOUT" -> FailureCategory.TIMEOUT
            "DISCONNECTED", "CLOSED" -> FailureCategory.DISCONNECTED
            "ATTRIBUTE_MISSING", "OPERATION_NOT_SUPPORTED" -> FailureCategory.INCOMPATIBLE
            else -> FailureCategory.UNAVAILABLE
        },
        "OPERATIONAL_BLE_SESSION_FAILED",
        code.name in setOf("TIMEOUT", "DISCONNECTED", "CONNECTION_FAILED"),
    )

    is DriverResolutionException -> failure(
        operation,
        FailureCategory.INCOMPATIBLE,
        "OPERATIONAL_DRIVER_RESOLUTION_FAILED",
        false,
    )

    is SecurityException -> failure(
        operation,
        FailureCategory.PERMISSION,
        "OPERATIONAL_PERMISSION_DENIED",
        false,
    )

    else -> failure(
        operation,
        FailureCategory.INTERNAL,
        "OPERATIONAL_RUNTIME_EFFECT_FAILED",
        true,
    )
}

private fun Throwable.toCleanupFailure(
    operation: RuntimeHostOperation,
    code: String,
): ExpectedFailure = failure(operation, FailureCategory.INTERNAL, code, false)

private fun Throwable.toPowerRefreshFailure(
    request: OperationalPowerRefreshRequest,
): ExpectedFailure {
    val source = toOperationalFailure(request.expectedOwner.hostOperation)
    return source.copy(
        code = FailureCode("OPERATIONAL_POWER_REFRESH_FAILED"),
        correlationId = request.correlationId,
    )
}

private fun refreshFailure(
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

private fun ExpectedFailure.withCorrelation(operation: RuntimeHostOperation): ExpectedFailure =
    if (correlationId != null) this else copy(correlationId = operation.correlationId)

private fun failure(
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
