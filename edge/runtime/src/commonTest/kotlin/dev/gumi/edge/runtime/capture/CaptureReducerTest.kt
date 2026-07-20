package dev.gumi.edge.runtime.capture

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CaptureReducerTest {
    @Test
    fun `requested recording remains distinct from acquired hardware truth`() {
        val command = command("record", CaptureCommandKind.START_RECORDING)

        val requested = CaptureReducer.reduce(verifiedState(), CaptureInput.Command(command))

        assertEquals(acquired(CaptureMode.IDLE), requested.state.truth)
        assertEquals(CaptureMode.RECORDING, requested.state.transition?.targetMode)
        assertEquals(
            listOf(CaptureEffect.RequestHardwareMode(command.correlationId, CaptureMode.RECORDING)),
            requested.effects,
        )

        val acquired = CaptureReducer.reduce(
            requested.state,
            CaptureInput.HardwareAcquired(
                command.correlationId,
                CaptureMode.RECORDING,
                proof(2u, CaptureProofSource.MODE_ACQUISITION),
            ),
        )
        assertEquals(
            acquired(CaptureMode.RECORDING, 2u, CaptureProofSource.MODE_ACQUISITION),
            acquired.state.truth,
        )
        assertNull(acquired.state.transition)
        assertIs<CaptureTerminalOutcome.Completed>(
            acquired.state.terminalCommands.getValue(command.id).outcome,
        )
    }

    @Test
    fun `duplicate command is inert both in flight and after completion`() {
        val command = command("record", CaptureCommandKind.START_RECORDING)
        val requested = CaptureReducer.reduce(verifiedState(), CaptureInput.Command(command))

        val inFlightDuplicate = CaptureReducer.reduce(requested.state, CaptureInput.Command(command))
        val activeOutcome = assertIs<CaptureReductionOutcome.CommandDuplicate>(inFlightDuplicate.outcome)
        assertTrue(activeOutcome.inFlight)
        assertTrue(inFlightDuplicate.effects.isEmpty())
        assertSame(requested.state, inFlightDuplicate.state)

        val completed = CaptureReducer.reduce(
            requested.state,
            CaptureInput.HardwareAcquired(
                command.correlationId,
                CaptureMode.RECORDING,
                proof(2u, CaptureProofSource.MODE_ACQUISITION),
            ),
        )
        val terminalDuplicate = CaptureReducer.reduce(completed.state, CaptureInput.Command(command))
        val terminalOutcome = assertIs<CaptureReductionOutcome.CommandDuplicate>(terminalDuplicate.outcome)
        assertEquals(completed.state.terminalCommands[command.id], terminalOutcome.prior)
        assertTrue(terminalDuplicate.effects.isEmpty())
        assertSame(completed.state, terminalDuplicate.state)
    }

    @Test
    fun `reusing a command ID for another intent is a replay conflict`() {
        val original = command("shared", CaptureCommandKind.START_RECORDING)
        val requested = CaptureReducer.reduce(verifiedState(), CaptureInput.Command(original))
        val conflicting = original.copy(
            correlationId = CorrelationId("correlation-conflict"),
            kind = CaptureCommandKind.START_VOICE_TURN,
        )

        val result = CaptureReducer.reduce(requested.state, CaptureInput.Command(conflicting))

        val outcome = assertIs<CaptureReductionOutcome.CommandIdConflict>(result.outcome)
        assertEquals(original, outcome.original)
        assertEquals(FailureCategory.REPLAYED, outcome.failure.category)
        assertEquals("CAPTURE_COMMAND_ID_CONFLICT", outcome.failure.code.value)
        assertSame(requested.state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `stale completion cannot resolve the active transition`() {
        val command = command("record", CaptureCommandKind.START_RECORDING)
        val requested = CaptureReducer.reduce(verifiedState(), CaptureInput.Command(command))

        val stale = CaptureReducer.reduce(
            requested.state,
            CaptureInput.HardwareAcquired(
                CorrelationId("other-correlation"),
                CaptureMode.RECORDING,
                proof(2u, CaptureProofSource.MODE_ACQUISITION),
            ),
        )

        assertIs<CaptureReductionOutcome.StaleHardwareCompletion>(stale.outcome)
        assertSame(requested.state, stale.state)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun `VoiceTurn entered from Recording returns to Recording`() {
        var state = transition(
            verifiedState(),
            command("record", CaptureCommandKind.START_RECORDING),
            CaptureMode.RECORDING,
        )
        state = transition(
            state,
            command("voice", CaptureCommandKind.START_VOICE_TURN),
            CaptureMode.VOICE_TURN,
        )
        assertEquals(CaptureMode.RECORDING, state.resumeAfterVoiceTurn)

        state = transition(
            state,
            command("release", CaptureCommandKind.STOP_VOICE_TURN),
            CaptureMode.RECORDING,
        )

        assertEquals(CaptureMode.RECORDING, (state.truth as CaptureTruth.Acquired).mode)
        assertNull(state.resumeAfterVoiceTurn)
    }

    @Test
    fun `VoiceTurn entered from Idle returns to Idle`() {
        var state = transition(
            verifiedState(),
            command("voice", CaptureCommandKind.START_VOICE_TURN),
            CaptureMode.VOICE_TURN,
        )
        state = transition(
            state,
            command("release", CaptureCommandKind.STOP_VOICE_TURN),
            CaptureMode.IDLE,
        )

        assertEquals(CaptureMode.IDLE, (state.truth as CaptureTruth.Acquired).mode)
        assertNull(state.resumeAfterVoiceTurn)
    }

    @Test
    fun `fatal fault enters Unknown and only correlated release may establish Idle`() {
        val command = command("record", CaptureCommandKind.START_RECORDING)
        val requested = CaptureReducer.reduce(verifiedState(), CaptureInput.Command(command))
        val recovery = CorrelationId("emergency-release")
        val fault = failure("CAPTURE_DEVICE_FAULT", command.correlationId)

        val failed = CaptureReducer.reduce(
            requested.state,
            CaptureInput.FatalFault(
                fault,
                recovery,
                releaseMustFollowCausalGeneration = 2u,
                connectionSessionGeneration = SESSION,
            ),
        )

        assertEquals(
            CaptureTruth.Unknown(fault, recovery, 2u, SESSION),
            failed.state.truth,
        )
        assertNull(failed.state.transition)
        assertEquals(
            listOf(CaptureEffect.RequestEmergencyRelease(recovery)),
            failed.effects,
        )
        assertIs<CaptureTerminalOutcome.Failed>(
            failed.state.terminalCommands.getValue(command.id).outcome,
        )

        val stale = CaptureReducer.reduce(
            failed.state,
            CaptureInput.HardwareReleaseConfirmed(
                CorrelationId("stale-release"),
                proof(3u, CaptureProofSource.EMERGENCY_RELEASE),
            ),
        )
        assertSame(failed.state, stale.state)

        val recovered = CaptureReducer.reduce(
            failed.state,
            CaptureInput.HardwareReleaseConfirmed(
                recovery,
                proof(3u, CaptureProofSource.EMERGENCY_RELEASE),
            ),
        )
        assertEquals(
            acquired(CaptureMode.IDLE, 3u, CaptureProofSource.EMERGENCY_RELEASE),
            recovered.state.truth,
        )
    }

    @Test
    fun `contradictory acquired mode is a fail-safe fault rather than a stale or Idle state`() {
        val command = command("record", CaptureCommandKind.START_RECORDING)
        val requested = CaptureReducer.reduce(verifiedState(), CaptureInput.Command(command))

        val mismatch = CaptureReducer.reduce(
            requested.state,
            CaptureInput.HardwareAcquired(
                command.correlationId,
                CaptureMode.VOICE_TURN,
                proof(2u, CaptureProofSource.MODE_ACQUISITION),
            ),
        )

        assertIs<CaptureTruth.Unknown>(mismatch.state.truth)
        assertIs<CaptureReductionOutcome.EnteredFailSafe>(mismatch.outcome)
        assertEquals(
            listOf(CaptureEffect.RequestEmergencyRelease(command.correlationId)),
            mismatch.effects,
        )
    }

    @Test
    fun `hardware refusal preserves prior acquired truth and terminates the command`() {
        val command = command("record", CaptureCommandKind.START_RECORDING)
        val requested = CaptureReducer.reduce(verifiedState(), CaptureInput.Command(command))
        val refusal = failure("CAPTURE_HARDWARE_REFUSED", command.correlationId)

        val refused = CaptureReducer.reduce(
            requested.state,
            CaptureInput.HardwareRefused(command.correlationId, refusal),
        )

        assertEquals(acquired(CaptureMode.IDLE), refused.state.truth)
        assertNull(refused.state.transition)
        assertEquals(
            CaptureTerminalOutcome.Refused(refusal),
            refused.state.terminalCommands.getValue(command.id).outcome,
        )
    }

    @Test
    fun `a second command cannot displace an in-flight transition`() {
        val first = command("record", CaptureCommandKind.START_RECORDING)
        val requested = CaptureReducer.reduce(verifiedState(), CaptureInput.Command(first))
        val second = command("voice", CaptureCommandKind.START_VOICE_TURN)

        val rejected = CaptureReducer.reduce(requested.state, CaptureInput.Command(second))

        assertEquals(requested.state.transition, rejected.state.transition)
        val terminal = assertIs<CaptureTerminalOutcome.Rejected>(
            rejected.state.terminalCommands.getValue(second.id).outcome,
        )
        assertEquals("CAPTURE_TRANSITION_IN_PROGRESS", terminal.failure.code.value)
        assertTrue(rejected.effects.isEmpty())
    }

    @Test
    fun `commands are rejected while capture truth is Unknown`() {
        val recovery = CorrelationId("recovery")
        val fault = failure("CAPTURE_DEVICE_FAULT", recovery)
        val unknown = CaptureState(
            truth = CaptureTruth.Unknown(fault, recovery, 1u, SESSION),
        )
        val command = command("record", CaptureCommandKind.START_RECORDING)

        val rejected = CaptureReducer.reduce(unknown, CaptureInput.Command(command))

        val terminal = assertIs<CaptureTerminalOutcome.Rejected>(
            rejected.state.terminalCommands.getValue(command.id).outcome,
        )
        assertEquals("CAPTURE_TRUTH_UNKNOWN", terminal.failure.code.value)
        assertTrue(rejected.effects.isEmpty())
    }

    @Test
    fun `terminal replay ledger evicts the oldest record at its explicit bound`() {
        var state = verifiedState().copy(
            terminalCommandLedgerPolicy = TerminalCommandLedgerPolicy(maxEntries = 2),
        )
        val first = command("ledger-first", CaptureCommandKind.STOP_RECORDING)
        val second = command("ledger-second", CaptureCommandKind.STOP_RECORDING)
        val third = command("ledger-third", CaptureCommandKind.STOP_RECORDING)

        state = CaptureReducer.reduce(state, CaptureInput.Command(first)).state
        state = CaptureReducer.reduce(state, CaptureInput.Command(second)).state
        state = CaptureReducer.reduce(state, CaptureInput.Command(third)).state

        assertEquals(setOf(second.id, third.id), state.terminalCommands.keys)
        assertEquals(1uL, state.evictedTerminalCommandCount)
        val retainedReplay = CaptureReducer.reduce(state, CaptureInput.Command(third))
        assertIs<CaptureReductionOutcome.CommandDuplicate>(retainedReplay.outcome)
        assertSame(state, retainedReplay.state)

        val explicitlyOutsideWindow = CaptureReducer.reduce(state, CaptureInput.Command(first))
        assertIs<CaptureReductionOutcome.CommandTerminal>(explicitlyOutsideWindow.outcome)
        assertEquals(2, explicitlyOutsideWindow.state.terminalCommands.size)
        assertEquals(2uL, explicitlyOutsideWindow.state.evictedTerminalCommandCount)
    }

    private fun transition(
        initial: CaptureState,
        command: CaptureCommand,
        acquired: CaptureMode,
    ): CaptureState {
        val requested = CaptureReducer.reduce(initial, CaptureInput.Command(command))
        assertIs<CaptureReductionOutcome.CommandAccepted>(requested.outcome)
        return CaptureReducer.reduce(
            requested.state,
            CaptureInput.HardwareAcquired(
                command.correlationId,
                acquired,
                proof(
                    ((requested.state.truth as CaptureTruth.Acquired).proof.causalGeneration + 1u),
                    CaptureProofSource.MODE_ACQUISITION,
                ),
            ),
        ).state
    }

    private fun verifiedState(mode: CaptureMode = CaptureMode.IDLE) = CaptureState(
        truth = acquired(mode),
        resumeAfterVoiceTurn = CaptureMode.IDLE.takeIf { mode == CaptureMode.VOICE_TURN },
    )

    private fun acquired(
        mode: CaptureMode,
        causalGeneration: ULong = 1u,
        source: CaptureProofSource = CaptureProofSource.DEVICE_OBSERVATION,
    ) = CaptureTruth.Acquired(mode, proof(causalGeneration, source))

    private fun proof(
        causalGeneration: ULong,
        source: CaptureProofSource,
    ) = CaptureProof(SESSION, causalGeneration, source)

    private fun command(id: String, kind: CaptureCommandKind) = CaptureCommand(
        id = CommandId("command-$id"),
        correlationId = CorrelationId("correlation-$id"),
        kind = kind,
    )

    private fun failure(code: String, correlationId: CorrelationId) = ExpectedFailure(
        category = FailureCategory.INTERNAL,
        code = FailureCode(code),
        retryable = false,
        correlationId = correlationId,
    )
}

private const val SESSION: ULong = 7u
