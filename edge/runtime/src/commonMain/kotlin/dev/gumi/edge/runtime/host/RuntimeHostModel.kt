package dev.gumi.edge.runtime.host

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure

enum class RuntimeHostAssociationState {
    UNKNOWN,
    MISSING,
    ASSOCIATED,
}

enum class RuntimeHostPresenceState {
    UNKNOWN,
    ABSENT,
    PRESENT,
}

/** Aggregate truth for every platform permission required by this host operation. */
enum class RuntimeHostPermissionState {
    UNKNOWN,
    DENIED,
    GRANTED,
}

/** Physical/process execution truth; user restart intent is a separate orthogonal fact. */
enum class RuntimeHostExecutionState {
    STOPPED,
    START_REQUESTED,
    FOREGROUND,
    START_DENIED,
    STOP_REQUESTED,
    OUTCOME_UNKNOWN,
}

enum class RuntimeHostTransportState {
    DISCONNECTED,
    CONNECTING,
    READY,
    DEGRADED,
}

enum class RuntimeHostRecoveryState {
    CLEAN,
    REHYDRATING,
    RECONCILIATION_REQUIRED,
    FAULTED,
}

enum class RuntimeHostRestartPolicy {
    AUTOMATIC_ALLOWED,
    USER_STOPPED,
}

enum class RuntimeHostStartOrigin {
    EXPLICIT_USER,
    AUTOMATIC_PRESENCE,
    AUTOMATIC_RECOVERY,
}

enum class RuntimeHostStopOrigin {
    EXPLICIT_USER,
    PREREQUISITE_LOST,
    OWNER_SHUTDOWN,
}

sealed interface RuntimeHostRequest {
    val id: CommandId
    val correlationId: CorrelationId

    data class Start(
        override val id: CommandId,
        override val correlationId: CorrelationId,
        val origin: RuntimeHostStartOrigin,
    ) : RuntimeHostRequest

    data class Stop(
        override val id: CommandId,
        override val correlationId: CorrelationId,
        val origin: RuntimeHostStopOrigin,
    ) : RuntimeHostRequest
}

/** Process-local generation plus stable caller identities carried through every effect completion. */
data class RuntimeHostOperation(
    val commandId: CommandId,
    val correlationId: CorrelationId,
    val generation: ULong,
)

enum class RuntimeHostOperationPhase {
    CHECKING_PREREQUISITES,
    ACQUIRING_FOREGROUND,
    REHYDRATING,
    CLEANING_RECOVERY,
    RELEASING_FOREGROUND,
}

data class RuntimeHostActiveOperation(
    val request: RuntimeHostRequest,
    val operation: RuntimeHostOperation,
    val phase: RuntimeHostOperationPhase,
)

sealed interface RuntimeHostCommandOutcome {
    data class Started(val recovery: RuntimeHostRecoveryState) : RuntimeHostCommandOutcome

    data object Stopped : RuntimeHostCommandOutcome

    data class NoOp(val code: String) : RuntimeHostCommandOutcome {
        init {
            require(code.matches(Regex("[A-Z][A-Z0-9_]{1,63}")))
        }
    }

    data class Suppressed(val failure: ExpectedFailure) : RuntimeHostCommandOutcome

    data class Rejected(val failure: ExpectedFailure) : RuntimeHostCommandOutcome

    data class Cancelled(val failure: ExpectedFailure) : RuntimeHostCommandOutcome

    data class Failed(
        val failure: ExpectedFailure,
        val cleanupFailures: List<ExpectedFailure> = emptyList(),
    ) : RuntimeHostCommandOutcome
}

data class RuntimeHostCommandRecord(
    val request: RuntimeHostRequest,
    val outcome: RuntimeHostCommandOutcome,
)

data class RuntimeHostCommandResult(
    val record: RuntimeHostCommandRecord,
    val replayed: Boolean,
)

/** Immutable host-neutral projection; no axis is derived from another axis. */
data class RuntimeHostProjection(
    val association: RuntimeHostAssociationState = RuntimeHostAssociationState.UNKNOWN,
    val presence: RuntimeHostPresenceState = RuntimeHostPresenceState.UNKNOWN,
    val permission: RuntimeHostPermissionState = RuntimeHostPermissionState.UNKNOWN,
    val execution: RuntimeHostExecutionState = RuntimeHostExecutionState.STOPPED,
    val transport: RuntimeHostTransportState = RuntimeHostTransportState.DISCONNECTED,
    val recovery: RuntimeHostRecoveryState = RuntimeHostRecoveryState.CLEAN,
    val restartPolicy: RuntimeHostRestartPolicy = RuntimeHostRestartPolicy.AUTOMATIC_ALLOWED,
    val activeOperation: RuntimeHostActiveOperation? = null,
    val terminalCommands: Map<CommandId, RuntimeHostCommandRecord> = emptyMap(),
    val terminalCommandLimit: Int = DEFAULT_TERMINAL_COMMAND_LIMIT,
    val evictedTerminalCommandCount: ULong = 0uL,
    val staleCompletionCount: ULong = 0uL,
    val sequence: Long = 0L,
    val lastCommand: RuntimeHostCommandRecord? = null,
    val lastFailure: ExpectedFailure? = null,
) {
    init {
        require(terminalCommandLimit > 0) { "Runtime host terminal command limit must be positive" }
        require(terminalCommands.size <= terminalCommandLimit) {
            "Runtime host terminal command ledger exceeds its configured limit"
        }
        require(activeOperation?.request?.id !in terminalCommands) {
            "An active runtime host request cannot already be terminal"
        }
        require(sequence >= 0) { "Runtime host projection sequence cannot be negative" }
    }

    companion object {
        const val DEFAULT_TERMINAL_COMMAND_LIMIT: Int = 256
    }
}
