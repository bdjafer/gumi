package dev.gumi.edge.runtime.operational

import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ProcessGlobalStorageLifecycle {
    NEW,
    OPENING,
    READY,
    DEGRADED,
    CLOSING,
    CLOSED,
    OUTCOME_UNKNOWN,
}

/** Process-level truth. Its backlog must never be projected as one device's attributed backlog. */
data class ProcessGlobalStorageProjection(
    val lifecycle: ProcessGlobalStorageLifecycle = ProcessGlobalStorageLifecycle.NEW,
    val backlog: OperationalBacklog? = null,
    val claimantDeviceIds: Set<DeviceId> = emptySet(),
    val sequence: Long = 0L,
    val lastFailure: ExpectedFailure? = null,
) {
    init {
        require(sequence >= 0L) { "Process-global storage sequence cannot be negative" }
        require(lifecycle == ProcessGlobalStorageLifecycle.READY || backlog == null) {
            "Only ready process-global storage can publish backlog truth"
        }
    }
}

sealed interface ProcessGlobalStorageCloseResult {
    val replayed: Boolean

    data class Closed(override val replayed: Boolean = false) : ProcessGlobalStorageCloseResult

    data class Failed(
        val failure: ExpectedFailure,
        override val replayed: Boolean = false,
    ) : ProcessGlobalStorageCloseResult

    data class OutcomeUnknown(
        val failure: ExpectedFailure,
        override val replayed: Boolean = false,
    ) : ProcessGlobalStorageCloseResult
}

/**
 * Opens one host-global operational store and lends device runtimes logical storage lifetimes.
 *
 * A logical device lease never closes a healthy physical store. The physical lease remains owned by
 * this process object until [close] after every device runtime has cleaned up. An uncertain physical
 * open is different: its only logical cleanup attempts the real physical release immediately so the
 * enclosing RuntimeHost cannot report a false clean boundary.
 *
 * The delegate must describe a host-global store. Its recovered backlog is retained only in
 * [projection]; logical results are marked [OperationalBacklogScope.EDGE_HOST] so downstream shells can
 * refuse to attribute those counts to a device.
 */
class ProcessGlobalOperationalStorageOwner(
    private val delegate: OperationalStoragePort,
) : OperationalStoragePort {
    private val mutex = Mutex()
    private var accepting = true
    private var state: PhysicalState = PhysicalState.New
    private val claims = linkedMapOf<DeviceId, SharedStorageLease>()
    private var terminalClose: ProcessGlobalStorageCloseResult? = null
    private val mutableProjection = MutableStateFlow(ProcessGlobalStorageProjection())

    val projection: StateFlow<ProcessGlobalStorageProjection> = mutableProjection.asStateFlow()

    override suspend fun openAndReconcile(
        operation: OperationalRuntimeOperation,
        binding: ProvisionedDeviceBinding,
    ): OperationalStorageOpenResult = mutex.withLock {
        if (!accepting) {
            return@withLock OperationalStorageOpenResult.Failed(
                operation,
                storageFailure(
                    operation,
                    FailureCategory.CANCELLED,
                    "PROCESS_GLOBAL_STORAGE_CLOSED",
                    retryable = false,
                ),
            )
        }
        claims[binding.deviceId]?.let { existing ->
            return@withLock if (existing.ownerOperation == operation) {
                existing.openResult(operation)
            } else {
                OperationalStorageOpenResult.Failed(
                    operation,
                    storageFailure(
                        operation,
                        FailureCategory.REJECTED_POLICY,
                        "PROCESS_GLOBAL_STORAGE_DEVICE_ALREADY_CLAIMED",
                        retryable = true,
                    ),
                )
            }
        }

        when (val current = state) {
            is PhysicalState.Ready -> createReadyClaim(operation, binding.deviceId, current)
            is PhysicalState.Uncertain -> OperationalStorageOpenResult.Failed(
                operation,
                current.failure.copy(correlationId = operation.hostOperation.correlationId),
            )

            is PhysicalState.Blocked -> OperationalStorageOpenResult.Failed(
                operation,
                current.failure.copy(correlationId = operation.hostOperation.correlationId),
            )

            PhysicalState.Closed -> OperationalStorageOpenResult.Failed(
                operation,
                storageFailure(
                    operation,
                    FailureCategory.CANCELLED,
                    "PROCESS_GLOBAL_STORAGE_CLOSED",
                    retryable = false,
                ),
            )

            PhysicalState.New -> openPhysical(operation, binding)
        }
    }

    /**
     * Permanently closes admission and the one physical store. Call only after device cleanup.
     * Repeating the call replays the exact terminal ownership verdict without repeating close effects.
     */
    suspend fun close(): ProcessGlobalStorageCloseResult = withContext(NonCancellable) {
        mutex.withLock {
            terminalClose?.let { return@withLock it.asReplayed() }
            accepting = false
            if (claims.isNotEmpty()) {
                return@withLock ProcessGlobalStorageCloseResult.Failed(
                    processCloseFailure(
                        FailureCategory.REJECTED_POLICY,
                        "PROCESS_GLOBAL_STORAGE_ACTIVE_DEVICE_CLAIMS",
                        retryable = true,
                    ),
                )
            }
            val result = when (val current = state) {
                PhysicalState.New,
                PhysicalState.Closed,
                -> ProcessGlobalStorageCloseResult.Closed()

                is PhysicalState.Ready -> settlePhysical(current.physical)
                is PhysicalState.Uncertain -> settlePhysical(current.physical)
                is PhysicalState.Blocked -> if (current.outcomeUnknown) {
                    ProcessGlobalStorageCloseResult.OutcomeUnknown(current.failure)
                } else {
                    ProcessGlobalStorageCloseResult.Failed(current.failure)
                }
            }
            if (result is ProcessGlobalStorageCloseResult.Closed) {
                state = PhysicalState.Closed
                updateProjection {
                    ProcessGlobalStorageProjection(
                        lifecycle = ProcessGlobalStorageLifecycle.CLOSED,
                        sequence = it.sequence,
                    )
                }
            }
            terminalClose = result
            result
        }
    }

    private suspend fun openPhysical(
        operation: OperationalRuntimeOperation,
        binding: ProvisionedDeviceBinding,
    ): OperationalStorageOpenResult {
        updateProjection {
            it.copy(
                lifecycle = ProcessGlobalStorageLifecycle.OPENING,
                backlog = null,
                lastFailure = null,
            )
        }
        val opened = try {
            delegate.openAndReconcile(operation, binding)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        return when (opened) {
            is OperationalStorageOpenResult.Failed -> {
                val failure = if (opened.operation == operation) {
                    opened.failure
                } else {
                    storageFailure(
                        operation,
                        FailureCategory.REPLAYED,
                        "PROCESS_GLOBAL_STORAGE_STALE_OPEN_COMPLETION",
                        retryable = false,
                    )
                }
                state = PhysicalState.New
                updateProjection {
                    it.copy(
                        lifecycle = ProcessGlobalStorageLifecycle.DEGRADED,
                        backlog = null,
                        lastFailure = failure,
                    )
                }
                OperationalStorageOpenResult.Failed(operation, failure)
            }

            is OperationalStorageOpenResult.Ready -> {
                if (opened.operation != operation) {
                    createUncertainClaim(
                        operation,
                        binding.deviceId,
                        PhysicalLease(opened.operation, opened.lease),
                        storageFailure(
                            operation,
                            FailureCategory.REPLAYED,
                            "PROCESS_GLOBAL_STORAGE_STALE_OPEN_COMPLETION",
                            retryable = false,
                        ),
                    )
                } else {
                    val ready = PhysicalState.Ready(
                        PhysicalLease(operation, opened.lease),
                        opened.backlog,
                    )
                    state = ready
                    createReadyClaim(operation, binding.deviceId, ready)
                }
            }

            is OperationalStorageOpenResult.OutcomeUnknown -> createUncertainClaim(
                operation,
                binding.deviceId,
                PhysicalLease(opened.operation, opened.lease),
                if (opened.operation == operation) {
                    opened.failure
                } else {
                    storageFailure(
                        operation,
                        FailureCategory.REPLAYED,
                        "PROCESS_GLOBAL_STORAGE_STALE_OPEN_COMPLETION",
                        retryable = false,
                    )
                },
            )
        }
    }

    private fun createReadyClaim(
        operation: OperationalRuntimeOperation,
        deviceId: DeviceId,
        ready: PhysicalState.Ready,
    ): OperationalStorageOpenResult.Ready {
        val lease = SharedStorageLease(
            owner = this,
            deviceId = deviceId,
            ownerOperation = operation,
            uncertainPhysicalOpen = false,
            backlog = ready.backlog,
        )
        claims[deviceId] = lease
        updateProjection {
            it.copy(
                lifecycle = ProcessGlobalStorageLifecycle.READY,
                backlog = ready.backlog,
                claimantDeviceIds = claims.keys.toSet(),
                lastFailure = null,
            )
        }
        return lease.openResult(operation) as OperationalStorageOpenResult.Ready
    }

    private fun createUncertainClaim(
        operation: OperationalRuntimeOperation,
        deviceId: DeviceId,
        physical: PhysicalLease,
        failure: ExpectedFailure,
    ): OperationalStorageOpenResult.OutcomeUnknown {
        val rebound = failure.copy(correlationId = operation.hostOperation.correlationId)
        state = PhysicalState.Uncertain(physical, rebound)
        val lease = SharedStorageLease(
            owner = this,
            deviceId = deviceId,
            ownerOperation = operation,
            uncertainPhysicalOpen = true,
            backlog = OperationalBacklog.Empty,
            openFailure = rebound,
        )
        claims[deviceId] = lease
        updateProjection {
            it.copy(
                lifecycle = ProcessGlobalStorageLifecycle.OUTCOME_UNKNOWN,
                backlog = null,
                claimantDeviceIds = claims.keys.toSet(),
                lastFailure = rebound,
            )
        }
        return lease.openResult(operation) as OperationalStorageOpenResult.OutcomeUnknown
    }

    private suspend fun releaseClaim(
        lease: SharedStorageLease,
        operation: OperationalRuntimeOperation,
    ): OperationalLeaseResult {
        return mutex.withLock {
            if (claims[lease.deviceId] !== lease) {
                return@withLock OperationalLeaseResult.Completed(operation)
            }
            claims.remove(lease.deviceId)
            updateProjection { it.copy(claimantDeviceIds = claims.keys.toSet()) }

            val current = state
            if (!lease.uncertainPhysicalOpen || current !is PhysicalState.Uncertain) {
                return@withLock OperationalLeaseResult.Completed(operation)
            }
            if (claims.isNotEmpty()) {
                return@withLock OperationalLeaseResult.OutcomeUnknown(
                    operation,
                    current.failure.copy(correlationId = operation.hostOperation.correlationId),
                )
            }
            when (val settled = settlePhysical(current.physical)) {
                is ProcessGlobalStorageCloseResult.Closed -> OperationalLeaseResult.Completed(operation)
                is ProcessGlobalStorageCloseResult.Failed -> OperationalLeaseResult.Failed(
                    operation,
                    settled.failure.copy(correlationId = operation.hostOperation.correlationId),
                )

                is ProcessGlobalStorageCloseResult.OutcomeUnknown -> OperationalLeaseResult
                    .OutcomeUnknown(
                        operation,
                        settled.failure.copy(correlationId = operation.hostOperation.correlationId),
                    )
            }
        }
    }

    private suspend fun settlePhysical(
        physical: PhysicalLease,
    ): ProcessGlobalStorageCloseResult {
        updateProjection {
            it.copy(
                lifecycle = ProcessGlobalStorageLifecycle.CLOSING,
                backlog = null,
            )
        }
        val quiesced = try {
            physical.lease.quiesce(physical.operation)
        } catch (_: Throwable) {
            OperationalLeaseResult.OutcomeUnknown(
                physical.operation,
                storageFailure(
                    physical.operation,
                    FailureCategory.INTERNAL,
                    "PROCESS_GLOBAL_STORAGE_QUIESCE_OUTCOME_UNKNOWN",
                    retryable = false,
                ),
            )
        }
        val quiesceFailure = quiesced.toPhysicalFailure(
            physical.operation,
            "PROCESS_GLOBAL_STORAGE_STALE_QUIESCE_COMPLETION",
        )
        if (quiesceFailure != null) return rememberPhysicalFailure(quiesceFailure)

        val closed = try {
            physical.lease.close(physical.operation)
        } catch (_: Throwable) {
            OperationalLeaseResult.OutcomeUnknown(
                physical.operation,
                storageFailure(
                    physical.operation,
                    FailureCategory.INTERNAL,
                    "PROCESS_GLOBAL_STORAGE_CLOSE_OUTCOME_UNKNOWN",
                    retryable = false,
                ),
            )
        }
        val closeFailure = closed.toPhysicalFailure(
            physical.operation,
            "PROCESS_GLOBAL_STORAGE_STALE_CLOSE_COMPLETION",
        )
        if (closeFailure != null) return rememberPhysicalFailure(closeFailure)

        state = PhysicalState.Closed
        updateProjection {
            ProcessGlobalStorageProjection(
                lifecycle = ProcessGlobalStorageLifecycle.CLOSED,
                sequence = it.sequence,
            )
        }
        return ProcessGlobalStorageCloseResult.Closed()
    }

    private fun rememberPhysicalFailure(
        failure: PhysicalFailure,
    ): ProcessGlobalStorageCloseResult {
        state = PhysicalState.Blocked(failure.failure, failure.outcomeUnknown)
        updateProjection {
            it.copy(
                lifecycle = if (failure.outcomeUnknown) {
                    ProcessGlobalStorageLifecycle.OUTCOME_UNKNOWN
                } else {
                    ProcessGlobalStorageLifecycle.DEGRADED
                },
                backlog = null,
                lastFailure = failure.failure,
            )
        }
        return if (failure.outcomeUnknown) {
            ProcessGlobalStorageCloseResult.OutcomeUnknown(failure.failure)
        } else {
            ProcessGlobalStorageCloseResult.Failed(failure.failure)
        }
    }

    private fun updateProjection(
        transform: (ProcessGlobalStorageProjection) -> ProcessGlobalStorageProjection,
    ) {
        val current = mutableProjection.value
        mutableProjection.value = transform(current).copy(sequence = current.sequence + 1L)
    }

    private sealed interface PhysicalState {
        data object New : PhysicalState

        data class Ready(
            val physical: PhysicalLease,
            val backlog: OperationalBacklog,
        ) : PhysicalState

        data class Uncertain(
            val physical: PhysicalLease,
            val failure: ExpectedFailure,
        ) : PhysicalState

        data class Blocked(
            val failure: ExpectedFailure,
            val outcomeUnknown: Boolean,
        ) : PhysicalState

        data object Closed : PhysicalState
    }

    private data class PhysicalLease(
        val operation: OperationalRuntimeOperation,
        val lease: OperationalStorageLease,
    )

    private class SharedStorageLease(
        private val owner: ProcessGlobalOperationalStorageOwner,
        val deviceId: DeviceId,
        val ownerOperation: OperationalRuntimeOperation,
        val uncertainPhysicalOpen: Boolean,
        private val backlog: OperationalBacklog,
        private val openFailure: ExpectedFailure? = null,
    ) : OperationalStorageLease {
        private val mutex = Mutex()
        private var state = SharedLeaseState.ACTIVE
        private var terminal: LeaseTerminal? = null

        fun openResult(operation: OperationalRuntimeOperation): OperationalStorageOpenResult =
            openFailure?.let {
                OperationalStorageOpenResult.OutcomeUnknown(
                    operation,
                    this,
                    it.copy(correlationId = operation.hostOperation.correlationId),
                )
            } ?: OperationalStorageOpenResult.Ready(
                operation,
                this,
                backlog,
                backlogScope = OperationalBacklogScope.EDGE_HOST,
            )

        override suspend fun quiesce(
            operation: OperationalRuntimeOperation,
        ): OperationalLeaseResult = withContext(NonCancellable) {
            mutex.withLock {
                stale(operation)?.let { return@withLock it }
                if (state == SharedLeaseState.ACTIVE) state = SharedLeaseState.QUIESCED
                OperationalLeaseResult.Completed(operation)
            }
        }

        override suspend fun close(
            operation: OperationalRuntimeOperation,
        ): OperationalLeaseResult = withContext(NonCancellable) {
            mutex.withLock {
                stale(operation)?.let { return@withLock it }
                terminal?.let { return@withLock it.toResult(operation) }
                if (state == SharedLeaseState.ACTIVE) {
                    return@withLock OperationalLeaseResult.Failed(
                        operation,
                        storageFailure(
                            operation,
                            FailureCategory.REJECTED_POLICY,
                            "PROCESS_GLOBAL_STORAGE_CLOSE_BEFORE_QUIESCE",
                            retryable = false,
                        ),
                    )
                }
                val result = owner.releaseClaim(this@SharedStorageLease, operation)
                terminal = result.toTerminal()
                state = SharedLeaseState.CLOSED
                result
            }
        }

        private fun stale(
            operation: OperationalRuntimeOperation,
        ): OperationalLeaseResult.Failed? = if (
            operation.sessionGeneration == ownerOperation.sessionGeneration
        ) {
            null
        } else {
            OperationalLeaseResult.Failed(
                operation,
                storageFailure(
                    operation,
                    FailureCategory.REPLAYED,
                    "PROCESS_GLOBAL_STORAGE_STALE_DEVICE_LEASE",
                    retryable = false,
                ),
            )
        }
    }

    private enum class SharedLeaseState {
        ACTIVE,
        QUIESCED,
        CLOSED,
    }

    internal sealed interface LeaseTerminal {
        fun toResult(operation: OperationalRuntimeOperation): OperationalLeaseResult

        data object Completed : LeaseTerminal {
            override fun toResult(operation: OperationalRuntimeOperation) =
                OperationalLeaseResult.Completed(operation)
        }

        data class Failed(val failure: ExpectedFailure) : LeaseTerminal {
            override fun toResult(operation: OperationalRuntimeOperation) =
                OperationalLeaseResult.Failed(
                    operation,
                    failure.copy(correlationId = operation.hostOperation.correlationId),
                )
        }

        data class OutcomeUnknown(val failure: ExpectedFailure) : LeaseTerminal {
            override fun toResult(operation: OperationalRuntimeOperation) =
                OperationalLeaseResult.OutcomeUnknown(
                    operation,
                    failure.copy(correlationId = operation.hostOperation.correlationId),
                )
        }
    }

    internal data class PhysicalFailure(
        val failure: ExpectedFailure,
        val outcomeUnknown: Boolean,
    )
}

private fun OperationalLeaseResult.toTerminal(): ProcessGlobalOperationalStorageOwner.LeaseTerminal =
    when (this) {
        is OperationalLeaseResult.Completed ->
            ProcessGlobalOperationalStorageOwner.LeaseTerminal.Completed

        is OperationalLeaseResult.Failed ->
            ProcessGlobalOperationalStorageOwner.LeaseTerminal.Failed(failure)

        is OperationalLeaseResult.OutcomeUnknown ->
            ProcessGlobalOperationalStorageOwner.LeaseTerminal.OutcomeUnknown(failure)
    }

private fun OperationalLeaseResult.toPhysicalFailure(
    expected: OperationalRuntimeOperation,
    staleCode: String,
): ProcessGlobalOperationalStorageOwner.PhysicalFailure? = when {
    operation != expected -> ProcessGlobalOperationalStorageOwner.PhysicalFailure(
        storageFailure(
            expected,
            FailureCategory.REPLAYED,
            staleCode,
            retryable = false,
        ),
        outcomeUnknown = true,
    )

    this is OperationalLeaseResult.Completed -> null
    this is OperationalLeaseResult.Failed ->
        ProcessGlobalOperationalStorageOwner.PhysicalFailure(failure, outcomeUnknown = false)

    this is OperationalLeaseResult.OutcomeUnknown ->
        ProcessGlobalOperationalStorageOwner.PhysicalFailure(failure, outcomeUnknown = true)

    else -> error("Unreachable operational lease result")
}

private fun ProcessGlobalStorageCloseResult.asReplayed(): ProcessGlobalStorageCloseResult = when (this) {
    is ProcessGlobalStorageCloseResult.Closed -> copy(replayed = true)
    is ProcessGlobalStorageCloseResult.Failed -> copy(replayed = true)
    is ProcessGlobalStorageCloseResult.OutcomeUnknown -> copy(replayed = true)
}

private fun storageFailure(
    operation: OperationalRuntimeOperation,
    category: FailureCategory,
    code: String,
    retryable: Boolean,
): ExpectedFailure = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
    correlationId = operation.hostOperation.correlationId,
)

private fun processCloseFailure(
    category: FailureCategory,
    code: String,
    retryable: Boolean,
): ExpectedFailure = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
)
