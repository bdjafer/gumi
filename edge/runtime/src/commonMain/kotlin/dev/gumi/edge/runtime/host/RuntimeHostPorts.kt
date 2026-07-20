package dev.gumi.edge.runtime.host

import dev.gumi.edge.sdk.ExpectedFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class RuntimeHostPrerequisites(
    val association: RuntimeHostAssociationState,
    val presence: RuntimeHostPresenceState,
    val permission: RuntimeHostPermissionState,
)

sealed interface RuntimeHostPrerequisiteResult {
    val operation: RuntimeHostOperation

    data class Observed(
        override val operation: RuntimeHostOperation,
        val prerequisites: RuntimeHostPrerequisites,
    ) : RuntimeHostPrerequisiteResult

    data class Failed(
        override val operation: RuntimeHostOperation,
        val failure: ExpectedFailure,
    ) : RuntimeHostPrerequisiteResult
}

/** Reads association/presence/permission facts without starting transport or foreground execution. */
interface RuntimeHostPrerequisitePort {
    suspend fun inspect(operation: RuntimeHostOperation): RuntimeHostPrerequisiteResult
}

sealed interface RuntimeHostForegroundStartResult {
    val operation: RuntimeHostOperation

    data class Entered(override val operation: RuntimeHostOperation) : RuntimeHostForegroundStartResult

    /** Definitive refusal: the adapter proves no foreground execution lease was acquired. */
    data class Denied(
        override val operation: RuntimeHostOperation,
        val failure: ExpectedFailure,
    ) : RuntimeHostForegroundStartResult

    /** The adapter cannot prove whether foreground execution was acquired. */
    data class OutcomeUnknown(
        override val operation: RuntimeHostOperation,
        val failure: ExpectedFailure,
    ) : RuntimeHostForegroundStartResult
}

sealed interface RuntimeHostForegroundStopResult {
    val operation: RuntimeHostOperation

    data class Released(override val operation: RuntimeHostOperation) : RuntimeHostForegroundStopResult

    data class Failed(
        override val operation: RuntimeHostOperation,
        val failure: ExpectedFailure,
    ) : RuntimeHostForegroundStopResult

    data class OutcomeUnknown(
        override val operation: RuntimeHostOperation,
        val failure: ExpectedFailure,
    ) : RuntimeHostForegroundStopResult
}

/**
 * Host execution boundary. Enter must return promptly enough for the platform's foreground deadline.
 * If enter is cancelled, it must settle before cancellation returns; the coordinator then calls leave
 * because acquisition may have crossed an outcome-unknown boundary.
 */
interface RuntimeHostExecutionPort {
    suspend fun enterForeground(operation: RuntimeHostOperation): RuntimeHostForegroundStartResult

    suspend fun leaveForeground(operation: RuntimeHostOperation): RuntimeHostForegroundStopResult
}

sealed interface RuntimeHostRehydrationResult {
    val operation: RuntimeHostOperation

    data class Rehydrated(
        override val operation: RuntimeHostOperation,
        val transport: RuntimeHostTransportState,
    ) : RuntimeHostRehydrationResult {
        init {
            require(transport in setOf(RuntimeHostTransportState.READY, RuntimeHostTransportState.DEGRADED))
        }
    }

    data class ReconciliationRequired(
        override val operation: RuntimeHostOperation,
        val failure: ExpectedFailure,
    ) : RuntimeHostRehydrationResult

    data class Failed(
        override val operation: RuntimeHostOperation,
        val failure: ExpectedFailure,
    ) : RuntimeHostRehydrationResult

    data class OutcomeUnknown(
        override val operation: RuntimeHostOperation,
        val failure: ExpectedFailure,
    ) : RuntimeHostRehydrationResult
}

enum class RuntimeHostCleanupReason {
    START_FAILED,
    START_CANCELLED,
    STOP_REQUESTED,
}

data class RuntimeHostCleanupRequest(
    val operation: RuntimeHostOperation,
    val reason: RuntimeHostCleanupReason,
)

sealed interface RuntimeHostCleanupResult {
    val operation: RuntimeHostOperation

    data class Cleaned(override val operation: RuntimeHostOperation) : RuntimeHostCleanupResult

    data class Failed(
        override val operation: RuntimeHostOperation,
        val failure: ExpectedFailure,
    ) : RuntimeHostCleanupResult

    data class OutcomeUnknown(
        override val operation: RuntimeHostOperation,
        val failure: ExpectedFailure,
    ) : RuntimeHostCleanupResult
}

/**
 * Ongoing truth from the recovery owner after initial rehydration has returned.
 *
 * Every event carries the exact start operation that owns the recovered resources. An adapter must
 * never relabel a late event with the currently active generation.
 */
sealed interface RuntimeHostRecoveryEvent {
    val operation: RuntimeHostOperation
    val failure: ExpectedFailure

    data class TransportDisconnected(
        override val operation: RuntimeHostOperation,
        override val failure: ExpectedFailure,
    ) : RuntimeHostRecoveryEvent

    data class Faulted(
        override val operation: RuntimeHostOperation,
        override val failure: ExpectedFailure,
    ) : RuntimeHostRecoveryEvent
}

/**
 * Rehydrates and reconciles durable runtime state only after foreground execution is established.
 * Cancellation must settle detached work before returning. Cleanup is idempotent by operation and may
 * be called conservatively after an outcome-unknown or cancelled rehydration.
 */
interface RuntimeHostRecoveryPort {
    /** Bounded single-consumer stream. Failures must be values; this flow must not throw. */
    val events: Flow<RuntimeHostRecoveryEvent> get() = emptyFlow()

    suspend fun rehydrateAndReconcile(operation: RuntimeHostOperation): RuntimeHostRehydrationResult

    suspend fun cleanup(request: RuntimeHostCleanupRequest): RuntimeHostCleanupResult
}
