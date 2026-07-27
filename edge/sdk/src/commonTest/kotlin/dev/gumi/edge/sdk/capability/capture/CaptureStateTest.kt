package dev.gumi.edge.sdk.capability.capture

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CaptureStateTest {
    @Test
    fun `active recording requires acquired microphone privacy and identity`() {
        assertFailsWith<IllegalArgumentException> {
            base().copy(recording = DeviceRecordingTruth.ACTIVE)
        }
        assertFailsWith<IllegalArgumentException> {
            base().copy(
                microphone = DeviceMicrophoneTruth.ACQUIRED,
                recording = DeviceRecordingTruth.ACTIVE,
                activeRecordingId = 1UL,
            )
        }
        base().copy(
            microphone = DeviceMicrophoneTruth.ACQUIRED,
            recording = DeviceRecordingTruth.ACTIVE,
            privacyOutput = DevicePrivacyOutputTruth.ACTIVE,
            activeRecordingId = 1UL,
        )
    }

    @Test
    fun `semantic signal overlays a recording instead of redefining microphone mode`() {
        assertFailsWith<IllegalArgumentException> {
            base().copy(semanticSignal = DeviceSemanticSignalTruth.ACTIVE)
        }
        base().copy(
            microphone = DeviceMicrophoneTruth.ACQUIRED,
            recording = DeviceRecordingTruth.ACTIVE,
            semanticSignal = DeviceSemanticSignalTruth.ACTIVE,
            privacyOutput = DevicePrivacyOutputTruth.ACTIVE,
            activeRecordingId = 4UL,
        )
    }

    @Test
    fun `maintenance admission requires verified microphone off`() {
        assertFailsWith<IllegalArgumentException> {
            base().copy(
                microphone = DeviceMicrophoneTruth.UNKNOWN,
                maintenance = DeviceMaintenanceTruth.ADMITTED,
            )
        }
        base().copy(maintenance = DeviceMaintenanceTruth.ADMITTED)
    }

    private fun base() = DeviceCaptureState(
        generation = 1UL,
        microphone = DeviceMicrophoneTruth.VERIFIED_OFF,
        recording = DeviceRecordingTruth.INACTIVE,
        voiceAction = DeviceVoiceActionTruth.INACTIVE,
        semanticSignal = DeviceSemanticSignalTruth.INACTIVE,
        privacyOutput = DevicePrivacyOutputTruth.INACTIVE,
        maintenance = DeviceMaintenanceTruth.NORMAL,
        availability = DeviceCaptureAvailability.READY,
        activeRecordingId = null,
        freeBytes = 8UL * 1024UL * 1024UL,
        faultCode = null,
        observedAtMonotonicMillis = 10L,
    )
}
