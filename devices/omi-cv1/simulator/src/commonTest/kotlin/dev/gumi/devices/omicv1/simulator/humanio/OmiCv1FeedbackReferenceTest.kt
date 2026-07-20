package dev.gumi.devices.omicv1.simulator.humanio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmiCv1FeedbackReferenceTest {
    @Test
    fun `every named indicator and haptic has one exact logical definition`() {
        assertEquals(OmiCv1IndicatorPattern.entries.toSet(), OmiCv1IndicatorCatalog.definitions.keys)
        assertEquals(OmiCv1HapticPattern.entries.toSet(), OmiCv1HapticCatalog.definitions.keys)

        val voice = OmiCv1IndicatorCatalog.definitions.getValue(
            OmiCv1IndicatorPattern.PRIVACY_VOICE_TURN,
        )
        assertEquals(OmiCv1IndicatorShape.RED_BASE_BLUE_MODULATION, voice.shape)
        assertEquals(OmiCv1LogicalColor.RED, voice.baseColor)
        assertEquals(OmiCv1IndicatorLevel.PRIVACY_FLOOR_OR_HIGHER, voice.baseLevel)
        assertEquals(2u, voice.blueModulationHz)
        assertEquals(false, voice.redBaseMayTurnOff)

        assertEquals(
            listOf(
                OmiCv1MotorSegment(true, 80),
                OmiCv1MotorSegment(false, 70),
                OmiCv1MotorSegment(true, 80),
            ),
            OmiCv1HapticCatalog.definitions.getValue(OmiCv1HapticPattern.RECORDING_STARTED),
        )
    }

    @Test
    fun `privacy output suppresses fault charging and requested status without blending`() {
        val decision = OmiCv1FeedbackArbiter.decide(
            OmiCv1HumanIoState(
                micTruth = OmiCv1MicTruth.ACQUIRED,
                baseRecording = OmiCv1BaseRecording.ACTIVE,
                fault = OmiCv1Fault.RECOVERABLE,
                charging = true,
            ),
            OmiCv1IndicatorPattern.READY_LINK_STATUS,
        )

        assertEquals(OmiCv1IndicatorPattern.PRIVACY_RECORDING, decision.selected)
        assertEquals(OmiCv1IndicatorDecisionStatus.SELECTED, decision.status)
        assertEquals(
            setOf(
                OmiCv1IndicatorPattern.RECOVERABLE_FAULT,
                OmiCv1IndicatorPattern.CHARGING,
                OmiCv1IndicatorPattern.READY_LINK_STATUS,
            ),
            decision.suppressed,
        )
    }

    @Test
    fun `voice turn modulation never removes its red privacy base`() {
        val decision = OmiCv1FeedbackArbiter.decide(
            OmiCv1HumanIoState(
                micTruth = OmiCv1MicTruth.ACQUIRED,
                voiceTurn = OmiCv1VoiceTurn.ACTIVE,
            ),
        )

        assertEquals(OmiCv1IndicatorPattern.PRIVACY_VOICE_TURN, decision.selected)
        assertEquals(
            false,
            OmiCv1IndicatorCatalog.definitions
                .getValue(requireNotNull(decision.selected))
                .redBaseMayTurnOff,
        )
    }

    @Test
    fun `undefined low-power versus charging tie remains explicit`() {
        val decision = OmiCv1FeedbackArbiter.decide(
            OmiCv1HumanIoState(
                charging = true,
                powerLevel = OmiCv1PowerLevel.LOW,
            ),
        )

        assertNull(decision.selected)
        assertEquals(OmiCv1IndicatorDecisionStatus.UNRESOLVED_SAME_PRIORITY, decision.status)
        assertEquals(
            setOf(OmiCv1IndicatorPattern.LOW_POWER, OmiCv1IndicatorPattern.CHARGING),
            decision.unresolvedSamePriority,
        )
    }

    @Test
    fun `fatal privacy with verified microphone off never invents a trustworthy light`() {
        val decision = OmiCv1FeedbackArbiter.decide(
            OmiCv1HumanIoState(
                micTruth = OmiCv1MicTruth.VERIFIED_OFF,
                fault = OmiCv1Fault.FATAL_PRIVACY,
                charging = true,
            ),
        )

        assertNull(decision.selected)
        assertEquals(
            OmiCv1IndicatorDecisionStatus.FATAL_PRIVACY_OUTPUT_UNAVAILABLE,
            decision.status,
        )
        assertEquals(setOf(OmiCv1IndicatorPattern.CHARGING), decision.suppressed)
    }

    @Test
    fun `storage full retains privacy until durable release then exposes fault`() {
        val subject = OmiCv1LifecycleReference(recordingState())

        val full = subject.apply(input(100, OmiCv1HumanIoInputType.STORAGE_STATE_CHANGED, "full"))
        assertEquals(OmiCv1IndicatorPattern.PRIVACY_RECORDING, full.persistentIndicator)
        assertEquals(
            listOf(
                OmiCv1LifecycleEvent(
                    100,
                    OmiCv1LifecycleEventType.SAFE_CAPTURE_STOP_REQUESTED,
                    reason = "local_durability_unavailable",
                ),
            ),
            full.semanticEvents,
        )

        subject.apply(input(110, OmiCv1HumanIoInputType.LAST_DURABLE_FRAME_COMMITTED))
        val released = subject.apply(input(120, OmiCv1HumanIoInputType.MICROPHONE_RELEASED))

        assertEquals(OmiCv1IndicatorPattern.RECOVERABLE_FAULT, released.persistentIndicator)
        assertEquals(
            OmiCv1IndicatorTransition(
                120,
                OmiCv1IndicatorPattern.PRIVACY_RECORDING,
                OmiCv1IndicatorPattern.RECOVERABLE_FAULT,
            ),
            released.indicatorTransition,
        )
        assertEquals(listOf(OmiCv1HapticPattern.FAULT), released.haptics)
        assertEquals(OmiCv1MicTruth.VERIFIED_OFF, released.state.micTruth)
        assertEquals(OmiCv1BaseRecording.INACTIVE, released.state.baseRecording)
    }

    @Test
    fun `storage release cannot precede its durable boundary`() {
        val subject = OmiCv1LifecycleReference(recordingState())
        subject.apply(input(100, OmiCv1HumanIoInputType.STORAGE_STATE_CHANGED, "full"))

        assertFailsWith<IllegalArgumentException> {
            subject.apply(input(110, OmiCv1HumanIoInputType.MICROPHONE_RELEASED))
        }
        assertEquals(OmiCv1IndicatorPattern.PRIVACY_RECORDING, subject.currentIndicator())
    }

    @Test
    fun `disconnect changes shell assurance without changing device capture or privacy`() {
        val subject = OmiCv1LifecycleReference(recordingState())
        val step = subject.apply(input(100, OmiCv1HumanIoInputType.LINK_DISCONNECTED))

        assertEquals(OmiCv1MicTruth.ACQUIRED, step.state.micTruth)
        assertEquals(OmiCv1BaseRecording.ACTIVE, step.state.baseRecording)
        assertEquals(OmiCv1IndicatorPattern.PRIVACY_RECORDING, step.persistentIndicator)
        assertEquals(OmiCv1ShellCapture.MAY_STILL_BE_RECORDING, step.shell.capture)
        assertEquals(
            "Device disconnected; recording may continue locally",
            step.shell.label,
        )
    }

    @Test
    fun `verified idle immediately becomes unknown when the link disappears`() {
        val subject = OmiCv1LifecycleReference(OmiCv1HumanIoState())
        val step = subject.apply(input(100, OmiCv1HumanIoInputType.LINK_DISCONNECTED))

        assertEquals(OmiCv1ShellCapture.UNKNOWN, step.shell.capture)
        assertEquals(
            "Microphone state unknown — check the device privacy light",
            step.shell.label,
        )
    }

    @Test
    fun `privacy guard failure refuses acquisition and locks lower indicator requests`() {
        val subject = OmiCv1LifecycleReference(OmiCv1HumanIoState())
        subject.apply(input(0, OmiCv1HumanIoInputType.START_BASE_RECORDING_REQUESTED))
        val step = subject.apply(input(1, OmiCv1HumanIoInputType.PRIVACY_GUARD_FAILED))

        assertEquals(OmiCv1MicTruth.VERIFIED_OFF, step.state.micTruth)
        assertEquals(OmiCv1BaseRecording.INACTIVE, step.state.baseRecording)
        assertEquals(OmiCv1Fault.FATAL_PRIVACY, step.state.fault)
        assertEquals(OmiCv1ShellCapture.VERIFIED_IDLE, step.shell.capture)
        assertEquals(listOf(OmiCv1HapticPattern.FAULT), step.haptics)
        assertEquals(
            OmiCv1IndicatorDecisionStatus.FATAL_PRIVACY_OUTPUT_UNAVAILABLE,
            step.outputDecision.status,
        )
        assertEquals(
            listOf(
                OmiCv1LifecycleEvent(
                    1,
                    OmiCv1LifecycleEventType.BASE_RECORDING_REFUSED,
                    reason = "privacy_output_unavailable",
                ),
            ),
            step.semanticEvents,
        )
    }

    @Test
    fun `watchdog boot closes interrupted identity and never resumes capture`() {
        val subject = OmiCv1LifecycleReference(
            recordingState(recordingId = "recording-before-reset"),
        )
        subject.apply(input(100, OmiCv1HumanIoInputType.WATCHDOG_RESET))
        val boot = subject.apply(input(101, OmiCv1HumanIoInputType.BOOT_STARTED))
        val ready = subject.apply(input(500, OmiCv1HumanIoInputType.REQUIRED_SELF_TESTS_PASSED))

        assertEquals(
            listOf(
                OmiCv1LifecycleEvent(
                    101,
                    OmiCv1LifecycleEventType.CAPTURE_DISCONTINUITY,
                    recordingId = "recording-before-reset",
                ),
            ),
            boot.semanticEvents,
        )
        assertEquals(OmiCv1IndicatorPattern.BOOTING, boot.persistentIndicator)
        assertEquals(OmiCv1MicTruth.VERIFIED_OFF, ready.state.micTruth)
        assertEquals(OmiCv1BaseRecording.INACTIVE, ready.state.baseRecording)
        assertEquals(OmiCv1Power.OPERATIONAL, ready.state.power)
        assertNull(ready.persistentIndicator)
        assertEquals(listOf(OmiCv1HapticPattern.READY), ready.haptics)
    }

    @Test
    fun `armed acoustic detector remains separate from a retained audio stream`() {
        val subject = OmiCv1LifecycleReference(
            OmiCv1HumanIoState(acousticDetector = OmiCv1AcousticDetector.ARMED),
        )

        assertEquals(OmiCv1ShellCapture.VERIFIED_IDLE, subject.shellProjection().capture)
        assertEquals(
            "Microphone stream off; acoustic detector armed",
            subject.shellProjection().secondaryLabel,
        )
        assertNull(subject.currentIndicator())
    }

    private fun recordingState(recordingId: String? = "recording-a") = OmiCv1HumanIoState(
        micTruth = OmiCv1MicTruth.ACQUIRED,
        baseRecording = OmiCv1BaseRecording.ACTIVE,
        baseRecordingId = recordingId,
    )

    private fun input(
        atMillis: Long,
        type: OmiCv1HumanIoInputType,
        value: String? = null,
        reason: String? = null,
    ) = OmiCv1HumanIoInput(atMillis, type, value, reason)
}
