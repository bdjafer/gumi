package dev.gumi.devices.omicv1.simulator.humanio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OmiCv1HumanIoIntegrationReferenceTest {
    @Test
    fun `double tap becomes durable recording only after the final required effect`() {
        val subject = OmiCv1HumanIoIntegrationReference(
            context = OmiCv1GestureContext.NORMAL_IDLE,
            initialState = OmiCv1HumanIoState(),
        )

        subject.acceptEdge(0, OmiCv1ButtonLevel.PRESSED)
        subject.acceptEdge(100, OmiCv1ButtonLevel.RELEASED)
        subject.acceptEdge(300, OmiCv1ButtonLevel.PRESSED)
        subject.acceptEdge(400, OmiCv1ButtonLevel.RELEASED)
        subject.applyEffect(effect(401, OmiCv1HumanIoInputType.PRIVACY_GUARD_ASSERTED))
        subject.applyEffect(effect(402, OmiCv1HumanIoInputType.LOCAL_DURABILITY_READY))

        assertNull(subject.firstAudioPermittedAtMillis)
        assertEquals(OmiCv1MicTruth.ACQUIRING, subject.state.micTruth)
        assertEquals(OmiCv1IndicatorPattern.PRIVACY_RECORDING, subject.currentIndicator)

        subject.applyEffect(effect(405, OmiCv1HumanIoInputType.MICROPHONE_ACQUIRED))

        assertEquals(405L, subject.firstAudioPermittedAtMillis)
        assertEquals(OmiCv1BaseRecording.ACTIVE, subject.state.baseRecording)
        assertEquals(
            listOf("double_tap", "start_base_recording_requested", "base_recording_started"),
            subject.events.map { it.type },
        )
        assertEquals(OmiCv1HapticPattern.RECORDING_STARTED, subject.steps.last().haptics.single())
    }

    @Test
    fun `voice turn waits for guard microphone durability and realtime in any completion order`() {
        val subject = OmiCv1HumanIoIntegrationReference(
            context = OmiCv1GestureContext.NORMAL_IDLE,
            initialState = OmiCv1HumanIoState(),
        )

        subject.acceptEdge(0, OmiCv1ButtonLevel.PRESSED)
        subject.advanceTo(500)
        subject.applyEffect(effect(501, OmiCv1HumanIoInputType.PRIVACY_GUARD_ASSERTED))
        subject.applyEffect(effect(502, OmiCv1HumanIoInputType.REALTIME_ROUTE_READY))
        subject.applyEffect(effect(503, OmiCv1HumanIoInputType.LOCAL_DURABILITY_READY))

        assertNull(subject.firstAudioPermittedAtMillis)
        assertEquals(OmiCv1IndicatorPattern.PRIVACY_VOICE_TURN, subject.currentIndicator)

        subject.applyEffect(effect(504, OmiCv1HumanIoInputType.MICROPHONE_ACQUIRED))

        assertEquals(504L, subject.firstAudioPermittedAtMillis)
        assertEquals(OmiCv1VoiceTurn.ACTIVE, subject.state.voiceTurn)
        assertEquals(OmiCv1HapticPattern.VOICE_READY, subject.steps.last().haptics.single())
    }

    @Test
    fun `hold release before acquisition cancels the pending voice turn without capture`() {
        val subject = OmiCv1HumanIoIntegrationReference(
            context = OmiCv1GestureContext.NORMAL_IDLE,
            initialState = OmiCv1HumanIoState(),
        )

        subject.acceptEdge(0, OmiCv1ButtonLevel.PRESSED)
        subject.advanceTo(500)
        subject.acceptEdge(700, OmiCv1ButtonLevel.RELEASED)

        assertNull(subject.firstAudioPermittedAtMillis)
        assertEquals(OmiCv1MicTruth.VERIFIED_OFF, subject.state.micTruth)
        assertEquals(OmiCv1VoiceTurn.INACTIVE, subject.state.voiceTurn)
        assertNull(subject.currentIndicator)
    }

    @Test
    fun `voice refusal over recording preserves base truth and suppresses the idle status pulse`() {
        val subject = OmiCv1HumanIoIntegrationReference(
            context = OmiCv1GestureContext.NORMAL_RECORDING,
            initialState = OmiCv1HumanIoState(
                micTruth = OmiCv1MicTruth.ACQUIRED,
                baseRecording = OmiCv1BaseRecording.ACTIVE,
                baseRecordingId = "recording-a",
            ),
            realtimeAdmissionAvailable = false,
        )

        subject.acceptEdge(0, OmiCv1ButtonLevel.PRESSED)
        subject.advanceTo(500)

        assertEquals(OmiCv1MicTruth.ACQUIRED, subject.state.micTruth)
        assertEquals(OmiCv1BaseRecording.ACTIVE, subject.state.baseRecording)
        assertEquals("recording-a", subject.state.baseRecordingId)
        assertEquals(OmiCv1IndicatorPattern.PRIVACY_RECORDING, subject.currentIndicator)
        assertEquals(OmiCv1HapticPattern.REFUSED, subject.steps.last().haptics.single())
        assertEquals(
            setOf(OmiCv1IndicatorPattern.DISCONNECTED_STATUS),
            subject.steps.last().outputDecision.suppressed,
        )
    }

    private fun effect(atMillis: Long, type: OmiCv1HumanIoInputType) =
        OmiCv1HumanIoInput(atMillis, type)
}
