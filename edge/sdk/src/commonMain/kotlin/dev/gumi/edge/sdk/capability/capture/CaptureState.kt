package dev.gumi.edge.sdk.capability.capture

import dev.gumi.edge.sdk.CapabilityDescriptor
import dev.gumi.edge.sdk.CapabilityHandle
import dev.gumi.edge.sdk.CapabilityKey
import dev.gumi.edge.sdk.CapabilityType
import dev.gumi.edge.sdk.SemanticVersion
import kotlinx.coroutines.flow.Flow

/**
 * Device-reported physical microphone truth. This is deliberately independent
 * from recording retention and semantic interpretation.
 */
enum class DeviceMicrophoneTruth {
    VERIFIED_OFF,
    TRANSITIONING,
    ACQUIRED,
    UNKNOWN,
}

enum class DeviceRecordingTruth {
    INACTIVE,
    STARTING,
    ACTIVE,
    STOPPING,
    UNKNOWN,
}

enum class DeviceVoiceActionTruth {
    INACTIVE,
    STARTING,
    ACTIVE,
    ENDING,
    UNKNOWN,
}

enum class DeviceSemanticSignalTruth {
    INACTIVE,
    ACTIVE,
    UNKNOWN,
}

enum class DevicePrivacyOutputTruth {
    INACTIVE,
    ACTIVE,
    UNKNOWN,
    FAILED,
}

enum class DeviceMaintenanceTruth {
    NORMAL,
    ADMITTED,
    ACTIVE,
    UNKNOWN,
}

enum class DeviceCaptureAvailability {
    BOOTING,
    READY,
    BUSY,
    MAINTENANCE,
    DEGRADED,
    FAULTED,
}

/**
 * Orthogonal, device-neutral capture observation.
 *
 * It never implies control authority. A read-only device may publish this
 * capability without implementing CaptureControl, live media, or media export.
 */
data class DeviceCaptureState(
    val generation: ULong,
    val microphone: DeviceMicrophoneTruth,
    val recording: DeviceRecordingTruth,
    val voiceAction: DeviceVoiceActionTruth,
    val semanticSignal: DeviceSemanticSignalTruth,
    val privacyOutput: DevicePrivacyOutputTruth,
    val maintenance: DeviceMaintenanceTruth,
    val availability: DeviceCaptureAvailability,
    val activeRecordingId: ULong?,
    val freeBytes: ULong?,
    val faultCode: String?,
    /** Null when a compatibility transport cannot timestamp a synchronous read. */
    val observedAtMonotonicMillis: Long?,
) {
    init {
        require(activeRecordingId == null || activeRecordingId != 0UL)
        require(faultCode == null || faultCode.isNotBlank())
        require(observedAtMonotonicMillis == null || observedAtMonotonicMillis >= 0)
        if (recording == DeviceRecordingTruth.ACTIVE) {
            require(microphone == DeviceMicrophoneTruth.ACQUIRED)
            require(privacyOutput == DevicePrivacyOutputTruth.ACTIVE)
            require(activeRecordingId != null)
        }
        if (semanticSignal == DeviceSemanticSignalTruth.ACTIVE) {
            require(recording == DeviceRecordingTruth.ACTIVE)
        }
        if (microphone == DeviceMicrophoneTruth.VERIFIED_OFF) {
            require(recording != DeviceRecordingTruth.ACTIVE)
            require(voiceAction != DeviceVoiceActionTruth.ACTIVE)
            require(semanticSignal != DeviceSemanticSignalTruth.ACTIVE)
        }
        if (maintenance in setOf(DeviceMaintenanceTruth.ADMITTED, DeviceMaintenanceTruth.ACTIVE)) {
            require(microphone == DeviceMicrophoneTruth.VERIFIED_OFF)
            require(recording == DeviceRecordingTruth.INACTIVE)
            require(voiceAction == DeviceVoiceActionTruth.INACTIVE)
        }
    }
}

data class CaptureStateDescriptor(
    val localRecording: Boolean,
    val readOnly: Boolean,
    val liveMedia: Boolean,
    val mediaExport: Boolean,
    val semanticSignals: Boolean,
    override val required: Boolean = false,
) : CapabilityDescriptor {
    override val key: CapabilityKey = CaptureStateV1.key
    override val version: SemanticVersion = SemanticVersion(1u, 0u)
}

interface CaptureStateHandle : CapabilityHandle<CaptureStateDescriptor> {
    suspend fun read(): DeviceCaptureState
    val updates: Flow<DeviceCaptureState>
}

object CaptureStateV1 : CapabilityType<CaptureStateDescriptor, CaptureStateHandle> {
    override val key = CapabilityKey("gumi.capture-state")
    override val supportedMajor = 1u
}
