package dev.gumi.edge.runtime.capture

import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode

/** Pure capture state transition function. Hardware and persistence effects are returned as values. */
object CaptureReducer {
    fun reduce(state: CaptureState, input: CaptureInput): CaptureReduction = when (input) {
        is CaptureInput.Command -> command(state, input.command)
        is CaptureInput.HardwareAcquired -> acquired(state, input)
        is CaptureInput.HardwareStateObserved -> observed(state, input)
        is CaptureInput.HardwareRefused -> refused(state, input)
        is CaptureInput.FatalFault -> fatal(
            state = state,
            failure = input.failure,
            recoveryCorrelationId = input.recoveryCorrelationId,
            releaseMustFollowCausalGeneration = input.releaseMustFollowCausalGeneration,
            connectionSessionGeneration = input.connectionSessionGeneration,
        )
        is CaptureInput.HardwareReleaseConfirmed -> releaseConfirmed(state, input)
    }

    private fun command(state: CaptureState, command: CaptureCommand): CaptureReduction {
        val active = state.transition?.command
        if (active?.id == command.id) {
            if (active != command) return commandIdConflict(state, command, active)
            return CaptureReduction(
                state = state,
                outcome = CaptureReductionOutcome.CommandDuplicate(
                    command = command,
                    prior = null,
                    inFlight = true,
                ),
            )
        }
        state.terminalCommands[command.id]?.let { prior ->
            if (prior.command != command) return commandIdConflict(state, command, prior.command)
            return CaptureReduction(
                state = state,
                outcome = CaptureReductionOutcome.CommandDuplicate(
                    command = command,
                    prior = prior,
                    inFlight = false,
                ),
            )
        }

        if (state.truth is CaptureTruth.Unknown || state.truth is CaptureTruth.Unverified) {
            val code = if (state.truth is CaptureTruth.Unknown) {
                "CAPTURE_TRUTH_UNKNOWN"
            } else {
                "CAPTURE_TRUTH_UNVERIFIED"
            }
            return terminal(
                state = state,
                command = command,
                outcome = CaptureTerminalOutcome.Rejected(
                    captureFailure(
                        category = FailureCategory.REJECTED_POLICY,
                        code = code,
                        retryable = true,
                        correlationId = command.correlationId,
                    ),
                ),
            )
        }
        if (active != null) {
            return terminal(
                state = state,
                command = command,
                outcome = CaptureTerminalOutcome.Rejected(
                    captureFailure(
                        category = FailureCategory.REJECTED_POLICY,
                        code = "CAPTURE_TRANSITION_IN_PROGRESS",
                        retryable = true,
                        correlationId = command.correlationId,
                    ),
                ),
            )
        }

        val acquired = (state.truth as CaptureTruth.Acquired).mode
        val target = targetMode(acquired, state.resumeAfterVoiceTurn, command.kind)
        if (target == null) {
            return terminal(
                state = state,
                command = command,
                outcome = CaptureTerminalOutcome.Rejected(
                    captureFailure(
                        category = FailureCategory.REJECTED_POLICY,
                        code = "CAPTURE_COMMAND_NOT_APPLICABLE",
                        retryable = false,
                        correlationId = command.correlationId,
                    ),
                ),
            )
        }
        if (target == acquired) {
            return terminal(
                state = state,
                command = command,
                outcome = CaptureTerminalOutcome.NoOp(acquired),
            )
        }

        val resume = when {
            target == CaptureMode.VOICE_TURN -> acquired
            acquired == CaptureMode.VOICE_TURN -> state.resumeAfterVoiceTurn
            else -> null
        }
        val next = state.copy(
            transition = CaptureTransition(command, target),
            resumeAfterVoiceTurn = resume,
        )
        return CaptureReduction(
            state = next,
            effects = listOf(
                CaptureEffect.RequestHardwareMode(
                    correlationId = command.correlationId,
                    targetMode = target,
                ),
            ),
            outcome = CaptureReductionOutcome.CommandAccepted(command, target),
        )
    }

    private fun acquired(
        state: CaptureState,
        input: CaptureInput.HardwareAcquired,
    ): CaptureReduction {
        val transition = state.transition
        if (transition == null || transition.command.correlationId != input.correlationId) {
            return stale(state, input.correlationId)
        }
        val prior = state.truth as? CaptureTruth.Acquired
            ?: return staleProof(state, input.proof)
        if (
            input.proof.source != CaptureProofSource.MODE_ACQUISITION ||
            input.proof.connectionSessionGeneration != prior.proof.connectionSessionGeneration ||
            input.proof.causalGeneration <= prior.proof.causalGeneration
        ) {
            return staleProof(state, input.proof)
        }
        if (transition.targetMode != input.mode) {
            return fatal(
                state = state,
                failure = captureFailure(
                    category = FailureCategory.CORRUPT,
                    code = "CAPTURE_HARDWARE_MODE_MISMATCH",
                    retryable = false,
                    correlationId = input.correlationId,
                ),
                recoveryCorrelationId = input.correlationId,
                releaseMustFollowCausalGeneration = input.proof.causalGeneration,
                connectionSessionGeneration = input.proof.connectionSessionGeneration,
            )
        }

        val record = CaptureCommandRecord(
            command = transition.command,
            outcome = CaptureTerminalOutcome.Completed(input.mode),
        )
        val ledger = state.terminalLedgerAfter(record)
        val next = state.copy(
            truth = CaptureTruth.Acquired(input.mode, input.proof),
            transition = null,
            resumeAfterVoiceTurn = state.resumeAfterVoiceTurn.takeIf {
                input.mode == CaptureMode.VOICE_TURN
            },
            terminalCommands = ledger.records,
            evictedTerminalCommandCount = ledger.evictedCount,
        )
        return CaptureReduction(
            state = next,
            outcome = CaptureReductionOutcome.TransitionCompleted(record),
        )
    }

    private fun observed(
        state: CaptureState,
        input: CaptureInput.HardwareStateObserved,
    ): CaptureReduction {
        if (input.proof.source != CaptureProofSource.DEVICE_OBSERVATION || state.transition != null) {
            return staleProof(state, input.proof)
        }
        val lastProof = when (val truth = state.truth) {
            is CaptureTruth.Acquired -> truth.proof
            is CaptureTruth.Unverified -> truth.lastProof
            is CaptureTruth.Unknown -> return staleProof(state, input.proof)
        }
        if (
            lastProof != null &&
            (
                input.proof.connectionSessionGeneration < lastProof.connectionSessionGeneration ||
                    input.proof.connectionSessionGeneration == lastProof.connectionSessionGeneration &&
                    input.proof.causalGeneration <= lastProof.causalGeneration
                )
        ) {
            return staleProof(state, input.proof)
        }

        val next = state.copy(
            truth = CaptureTruth.Acquired(input.mode, input.proof),
            resumeAfterVoiceTurn = if (input.mode == CaptureMode.VOICE_TURN) {
                (state.truth as? CaptureTruth.Unverified)?.lastReportedMode
                    ?.takeIf { it in setOf(CaptureMode.IDLE, CaptureMode.RECORDING) }
                    ?: CaptureMode.IDLE
            } else {
                null
            },
        )
        return CaptureReduction(
            state = next,
            outcome = CaptureReductionOutcome.HardwareTruthObserved(input.mode, input.proof),
        )
    }

    private fun refused(
        state: CaptureState,
        input: CaptureInput.HardwareRefused,
    ): CaptureReduction {
        val transition = state.transition
        if (transition == null || transition.command.correlationId != input.correlationId) {
            return stale(state, input.correlationId)
        }

        val record = CaptureCommandRecord(
            command = transition.command,
            outcome = CaptureTerminalOutcome.Refused(input.failure),
        )
        val stillInVoiceTurn = (state.truth as? CaptureTruth.Acquired)?.mode == CaptureMode.VOICE_TURN
        val ledger = state.terminalLedgerAfter(record)
        val next = state.copy(
            transition = null,
            resumeAfterVoiceTurn = state.resumeAfterVoiceTurn.takeIf { stillInVoiceTurn },
            terminalCommands = ledger.records,
            evictedTerminalCommandCount = ledger.evictedCount,
        )
        return CaptureReduction(
            state = next,
            outcome = CaptureReductionOutcome.TransitionRefused(record),
        )
    }

    private fun fatal(
        state: CaptureState,
        failure: ExpectedFailure,
        recoveryCorrelationId: CorrelationId,
        releaseMustFollowCausalGeneration: ULong,
        connectionSessionGeneration: ULong,
    ): CaptureReduction {
        val active = state.transition?.command
        val withTerminal = if (active == null) {
            state
        } else {
            val record = CaptureCommandRecord(active, CaptureTerminalOutcome.Failed(failure))
            val ledger = state.terminalLedgerAfter(record)
            state.copy(
                transition = null,
                resumeAfterVoiceTurn = null,
                terminalCommands = ledger.records,
                evictedTerminalCommandCount = ledger.evictedCount,
            )
        }
        val next = withTerminal.copy(
            truth = CaptureTruth.Unknown(
                failure = failure,
                recoveryCorrelationId = recoveryCorrelationId,
                releaseMustFollowCausalGeneration = releaseMustFollowCausalGeneration,
                connectionSessionGeneration = connectionSessionGeneration,
            ),
            transition = null,
            resumeAfterVoiceTurn = null,
        )
        return CaptureReduction(
            state = next,
            effects = listOf(CaptureEffect.RequestEmergencyRelease(recoveryCorrelationId)),
            outcome = CaptureReductionOutcome.EnteredFailSafe(failure, recoveryCorrelationId),
        )
    }

    private fun releaseConfirmed(
        state: CaptureState,
        input: CaptureInput.HardwareReleaseConfirmed,
    ): CaptureReduction {
        val unknown = state.truth as? CaptureTruth.Unknown
        if (unknown == null || unknown.recoveryCorrelationId != input.recoveryCorrelationId) {
            return stale(state, input.recoveryCorrelationId)
        }
        if (
            input.proof.source != CaptureProofSource.EMERGENCY_RELEASE ||
            input.proof.connectionSessionGeneration != unknown.connectionSessionGeneration ||
            input.proof.causalGeneration <= unknown.releaseMustFollowCausalGeneration
        ) {
            return staleProof(state, input.proof)
        }
        return CaptureReduction(
            state = state.copy(truth = CaptureTruth.Acquired(CaptureMode.IDLE, input.proof)),
            outcome = CaptureReductionOutcome.FailSafeReleaseConfirmed(input.recoveryCorrelationId),
        )
    }

    private fun terminal(
        state: CaptureState,
        command: CaptureCommand,
        outcome: CaptureTerminalOutcome,
    ): CaptureReduction {
        val record = CaptureCommandRecord(command, outcome)
        val ledger = state.terminalLedgerAfter(record)
        return CaptureReduction(
            state = state.copy(
                terminalCommands = ledger.records,
                evictedTerminalCommandCount = ledger.evictedCount,
            ),
            outcome = CaptureReductionOutcome.CommandTerminal(record),
        )
    }

    private fun stale(state: CaptureState, correlationId: CorrelationId) = CaptureReduction(
        state = state,
        outcome = CaptureReductionOutcome.StaleHardwareCompletion(correlationId),
    )

    private fun staleProof(state: CaptureState, proof: CaptureProof) = CaptureReduction(
        state = state,
        outcome = CaptureReductionOutcome.StaleCaptureProof(proof),
    )

    private fun commandIdConflict(
        state: CaptureState,
        command: CaptureCommand,
        original: CaptureCommand,
    ): CaptureReduction {
        val failure = captureFailure(
            category = FailureCategory.REPLAYED,
            code = "CAPTURE_COMMAND_ID_CONFLICT",
            retryable = false,
            correlationId = command.correlationId,
        )
        return CaptureReduction(
            state = state,
            outcome = CaptureReductionOutcome.CommandIdConflict(
                command = command,
                original = original,
                failure = failure,
            ),
        )
    }

    private fun targetMode(
        acquired: CaptureMode,
        resumeAfterVoiceTurn: CaptureMode?,
        kind: CaptureCommandKind,
    ): CaptureMode? = when (kind) {
        CaptureCommandKind.START_RECORDING -> when (acquired) {
            CaptureMode.IDLE -> CaptureMode.RECORDING
            CaptureMode.RECORDING -> CaptureMode.RECORDING
            CaptureMode.VOICE_TURN -> null
        }

        CaptureCommandKind.STOP_RECORDING -> when (acquired) {
            CaptureMode.IDLE -> CaptureMode.IDLE
            CaptureMode.RECORDING -> CaptureMode.IDLE
            CaptureMode.VOICE_TURN -> null
        }

        CaptureCommandKind.START_VOICE_TURN -> CaptureMode.VOICE_TURN
        CaptureCommandKind.STOP_VOICE_TURN -> when (acquired) {
            CaptureMode.VOICE_TURN -> checkNotNull(resumeAfterVoiceTurn)
            CaptureMode.IDLE -> CaptureMode.IDLE
            CaptureMode.RECORDING -> CaptureMode.RECORDING
        }
    }
}

private data class TerminalLedgerUpdate(
    val records: Map<CommandId, CaptureCommandRecord>,
    val evictedCount: ULong,
)

private fun CaptureState.terminalLedgerAfter(record: CaptureCommandRecord): TerminalLedgerUpdate {
    val ordered = linkedMapOf<CommandId, CaptureCommandRecord>()
    terminalCommands.forEach { (id, existing) ->
        if (id != record.command.id) ordered[id] = existing
    }
    ordered[record.command.id] = record

    var evicted = 0uL
    while (ordered.size > terminalCommandLedgerPolicy.maxEntries) {
        val oldest = ordered.keys.first()
        ordered.remove(oldest)
        evicted += 1u
    }
    val nextEvictedCount = if (ULong.MAX_VALUE - evictedTerminalCommandCount < evicted) {
        ULong.MAX_VALUE
    } else {
        evictedTerminalCommandCount + evicted
    }
    return TerminalLedgerUpdate(ordered, nextEvictedCount)
}

private fun captureFailure(
    category: FailureCategory,
    code: String,
    retryable: Boolean,
    correlationId: CorrelationId,
) = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
    correlationId = correlationId,
)
