package dev.gumi.edge.runtime.capture

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure

enum class CaptureMode {
    IDLE,
    RECORDING,
    VOICE_TURN,
}

enum class CaptureProofSource {
    DEVICE_OBSERVATION,
    MODE_ACQUISITION,
    EMERGENCY_RELEASE,
}

/**
 * Causal evidence for an acquired physical mode.
 *
 * A connection generation prevents evidence from one physical link session being reused after a
 * reconnect. A causal generation is allocated by the single device supervisor and orders every
 * observation/acquire/release that can establish capture truth in that session.
 */
data class CaptureProof(
    val connectionSessionGeneration: ULong,
    val causalGeneration: ULong,
    val source: CaptureProofSource,
)

sealed interface CaptureTruth {
    /** No physical evidence has established the current microphone state. */
    data class Unverified(
        val lastReportedMode: CaptureMode? = null,
        val lastProof: CaptureProof? = null,
    ) : CaptureTruth

    data class Acquired(
        val mode: CaptureMode,
        val proof: CaptureProof,
    ) : CaptureTruth

    /**
     * Hardware capture state is not trustworthy. The runtime must use fail-safe physical/UI feedback
     * until the correlated emergency release is confirmed.
     */
    data class Unknown(
        val failure: ExpectedFailure,
        val recoveryCorrelationId: CorrelationId,
        val releaseMustFollowCausalGeneration: ULong,
        val connectionSessionGeneration: ULong,
    ) : CaptureTruth
}

enum class CaptureCommandKind {
    START_RECORDING,
    STOP_RECORDING,
    START_VOICE_TURN,
    STOP_VOICE_TURN,
}

data class CaptureCommand(
    val id: CommandId,
    val correlationId: CorrelationId,
    val kind: CaptureCommandKind,
)

data class CaptureTransition(
    val command: CaptureCommand,
    val targetMode: CaptureMode,
)

sealed interface CaptureTerminalOutcome {
    data class Completed(val acquiredMode: CaptureMode) : CaptureTerminalOutcome

    data class NoOp(val acquiredMode: CaptureMode) : CaptureTerminalOutcome

    data class Refused(val failure: ExpectedFailure) : CaptureTerminalOutcome

    data class Rejected(val failure: ExpectedFailure) : CaptureTerminalOutcome

    data class Failed(val failure: ExpectedFailure) : CaptureTerminalOutcome
}

data class CaptureCommandRecord(
    val command: CaptureCommand,
    val outcome: CaptureTerminalOutcome,
)

enum class TerminalCommandEvictionPolicy {
    OLDEST_TERMINAL_FIRST,
}

/**
 * Process-local replay window for terminal capture commands.
 *
 * This ledger is not durable. Hosts that promise idempotency across process death must persist
 * terminal command results outside this reducer. Within one state owner the oldest terminal record
 * is evicted first once [maxEntries] is reached.
 */
data class TerminalCommandLedgerPolicy(
    val maxEntries: Int = DEFAULT_MAX_TERMINAL_COMMANDS,
    val eviction: TerminalCommandEvictionPolicy = TerminalCommandEvictionPolicy.OLDEST_TERMINAL_FIRST,
) {
    init {
        require(maxEntries > 0) { "Terminal command ledger capacity must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_TERMINAL_COMMANDS: Int = 256
    }
}

data class CaptureState(
    val truth: CaptureTruth = CaptureTruth.Unverified(),
    val transition: CaptureTransition? = null,
    val resumeAfterVoiceTurn: CaptureMode? = null,
    val terminalCommands: Map<CommandId, CaptureCommandRecord> = emptyMap(),
    val terminalCommandLedgerPolicy: TerminalCommandLedgerPolicy = TerminalCommandLedgerPolicy(),
    val evictedTerminalCommandCount: ULong = 0u,
) {
    init {
        require(terminalCommands.size <= terminalCommandLedgerPolicy.maxEntries) {
            "Terminal command ledger exceeds its configured capacity"
        }
        require(transition?.command?.id !in terminalCommands) {
            "An in-flight capture command cannot already be terminal"
        }
        when (truth) {
            is CaptureTruth.Unverified -> {
                require(transition == null) { "Unverified capture truth cannot carry a transition" }
                require(resumeAfterVoiceTurn == null) {
                    "Unverified capture truth cannot promise a VoiceTurn resume mode"
                }
            }

            is CaptureTruth.Unknown -> {
                require(transition == null) { "Unknown capture truth cannot carry a normal transition" }
                require(resumeAfterVoiceTurn == null) {
                    "Unknown capture truth cannot promise a VoiceTurn resume mode"
                }
            }

            is CaptureTruth.Acquired -> when (truth.mode) {
                CaptureMode.VOICE_TURN -> require(resumeAfterVoiceTurn in VOICE_TURN_RESUME_MODES) {
                    "An acquired VoiceTurn must remember whether to resume Idle or Recording"
                }

                CaptureMode.IDLE,
                CaptureMode.RECORDING,
                -> {
                    val enteringVoiceTurn = transition?.targetMode == CaptureMode.VOICE_TURN
                    require(
                        resumeAfterVoiceTurn == null ||
                            enteringVoiceTurn && resumeAfterVoiceTurn == truth.mode,
                    ) {
                        "A non-VoiceTurn state may remember a resume mode only while entering VoiceTurn"
                    }
                }
            }
        }
    }
}

sealed interface CaptureInput {
    data class Command(val command: CaptureCommand) : CaptureInput

    data class HardwareAcquired(
        val correlationId: CorrelationId,
        val mode: CaptureMode,
        val proof: CaptureProof,
    ) : CaptureInput

    data class HardwareStateObserved(
        val mode: CaptureMode,
        val proof: CaptureProof,
    ) : CaptureInput

    data class HardwareRefused(
        val correlationId: CorrelationId,
        val failure: ExpectedFailure,
    ) : CaptureInput

    data class FatalFault(
        val failure: ExpectedFailure,
        val recoveryCorrelationId: CorrelationId,
        val releaseMustFollowCausalGeneration: ULong,
        val connectionSessionGeneration: ULong,
    ) : CaptureInput

    data class HardwareReleaseConfirmed(
        val recoveryCorrelationId: CorrelationId,
        val proof: CaptureProof,
    ) : CaptureInput
}

sealed interface CaptureEffect {
    data class RequestHardwareMode(
        val correlationId: CorrelationId,
        val targetMode: CaptureMode,
    ) : CaptureEffect

    data class RequestEmergencyRelease(
        val recoveryCorrelationId: CorrelationId,
    ) : CaptureEffect
}

sealed interface CaptureReductionOutcome {
    data class CommandAccepted(
        val command: CaptureCommand,
        val targetMode: CaptureMode,
    ) : CaptureReductionOutcome

    data class CommandDuplicate(
        val command: CaptureCommand,
        val prior: CaptureCommandRecord?,
        val inFlight: Boolean,
    ) : CaptureReductionOutcome

    data class CommandIdConflict(
        val command: CaptureCommand,
        val original: CaptureCommand,
        val failure: ExpectedFailure,
    ) : CaptureReductionOutcome

    data class CommandTerminal(
        val record: CaptureCommandRecord,
    ) : CaptureReductionOutcome

    data class TransitionCompleted(
        val record: CaptureCommandRecord,
    ) : CaptureReductionOutcome

    data class TransitionRefused(
        val record: CaptureCommandRecord,
    ) : CaptureReductionOutcome

    data class StaleHardwareCompletion(
        val correlationId: CorrelationId,
    ) : CaptureReductionOutcome

    data class StaleCaptureProof(
        val proof: CaptureProof,
    ) : CaptureReductionOutcome

    data class HardwareTruthObserved(
        val mode: CaptureMode,
        val proof: CaptureProof,
    ) : CaptureReductionOutcome

    data class EnteredFailSafe(
        val failure: ExpectedFailure,
        val recoveryCorrelationId: CorrelationId,
    ) : CaptureReductionOutcome

    data class FailSafeReleaseConfirmed(
        val recoveryCorrelationId: CorrelationId,
    ) : CaptureReductionOutcome
}

data class CaptureReduction(
    val state: CaptureState,
    val effects: List<CaptureEffect> = emptyList(),
    val outcome: CaptureReductionOutcome,
)

private val VOICE_TURN_RESUME_MODES = setOf(CaptureMode.IDLE, CaptureMode.RECORDING)
