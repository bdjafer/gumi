package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.capability.capture.DeviceCaptureAvailability
import dev.gumi.edge.sdk.capability.capture.DeviceCaptureState
import dev.gumi.edge.sdk.capability.capture.DeviceMaintenanceTruth
import dev.gumi.edge.sdk.capability.capture.DeviceMicrophoneTruth
import dev.gumi.edge.sdk.capability.capture.DevicePrivacyOutputTruth
import dev.gumi.edge.sdk.capability.capture.DeviceRecordingTruth
import dev.gumi.edge.sdk.capability.capture.DeviceSemanticSignalTruth
import dev.gumi.edge.sdk.capability.capture.DeviceVoiceActionTruth

enum class OmiCv1FunctionalCapturePhase(val wireValue: Int) {
    BOOTING(0),
    IDLE(1),
    STARTING_BASE(2),
    BASE_ACTIVE(3),
    STARTING_VOICE_IDLE(4),
    VOICE_IDLE_ACTIVE(5),
    STARTING_VOICE_OVERLAY(6),
    VOICE_OVERLAY_ACTIVE(7),
    STOPPING(8),
    FATAL_IDLE(9),
}

enum class OmiCv1FunctionalMicrophoneTruth(val wireValue: Int) {
    VERIFIED_OFF(0),
    ACQUIRING(1),
    ACQUIRED(2),
    RELEASING(3),
    UNKNOWN(4),
}

enum class OmiCv1FunctionalStorageState(val wireValue: Int) {
    HEALTHY(0),
    LOW(1),
    FULL(2),
    CORRUPT(3),
}

enum class OmiCv1FunctionalKeyTruth(val wireValue: Int) {
    UNINITIALIZED(0),
    ROOT_MISSING(1),
    READY(2),
    FAULTED(3),
}

enum class OmiCv1FunctionalRecordingStorageTruth(val wireValue: Int) {
    UNINITIALIZED(0),
    READY(1),
    ACTIVE(2),
    FINALIZING(3),
    COMMITTED(4),
    INTERRUPTED(5),
    FAULTED(6),
    UNKNOWN(7),
}

enum class OmiCv1FunctionalCodecTruth(val wireValue: Int) {
    UNINITIALIZED(0),
    CLOSED(1),
    OPENING(2),
    ACTIVE(3),
    CLOSING(4),
    FAULTED(5),
    UNKNOWN(6),
}

data class OmiCv1FunctionalStatusEvidence(
    val schemaVersion: Int,
    val phase: OmiCv1FunctionalCapturePhase,
    val microphone: OmiCv1FunctionalMicrophoneTruth,
    val storageState: OmiCv1FunctionalStorageState,
    val key: OmiCv1FunctionalKeyTruth,
    val recordingStorage: OmiCv1FunctionalRecordingStorageTruth,
    val codec: OmiCv1FunctionalCodecTruth,
    val flags: Int,
    val activeRecordingId: ULong,
    val freeBytes: ULong,
    val lastError: Int,
    val generation: Long,
    val operational: Boolean,
    val baseAudioPermitted: Boolean,
    val voiceAudioPermitted: Boolean,
    val privacyAsserted: Boolean,
    val updateAdmitted: Boolean,
    val keyReady: Boolean,
    val storageReady: Boolean,
    val faulted: Boolean,
    val familyIdentityServiceEmpty: Boolean,
    val capabilitiesExact: Boolean,
    val rawHex: String,
) {
    val capturingLocally: Boolean
        get() = operational &&
            phase == OmiCv1FunctionalCapturePhase.BASE_ACTIVE &&
            microphone == OmiCv1FunctionalMicrophoneTruth.ACQUIRED &&
            key == OmiCv1FunctionalKeyTruth.READY &&
            recordingStorage == OmiCv1FunctionalRecordingStorageTruth.ACTIVE &&
            codec == OmiCv1FunctionalCodecTruth.ACTIVE &&
            baseAudioPermitted && !voiceAudioPermitted && privacyAsserted &&
            !updateAdmitted && keyReady && !storageReady && !faulted &&
            lastError == 0 && activeRecordingId != 0UL

    val recordingReady: Boolean
        get() = operational &&
            phase == OmiCv1FunctionalCapturePhase.IDLE &&
            microphone == OmiCv1FunctionalMicrophoneTruth.VERIFIED_OFF &&
            storageState == OmiCv1FunctionalStorageState.HEALTHY &&
            key == OmiCv1FunctionalKeyTruth.READY &&
            recordingStorage in READY_RECORDING_STORAGE &&
            codec == OmiCv1FunctionalCodecTruth.CLOSED &&
            !baseAudioPermitted && !voiceAudioPermitted && !privacyAsserted &&
            !updateAdmitted && keyReady && storageReady && !faulted && lastError == 0 &&
            activeRecordingId == 0UL

    fun toDeviceCaptureState(observedAtMonotonicMillis: Long?): DeviceCaptureState {
        val recording = when (phase) {
            OmiCv1FunctionalCapturePhase.STARTING_BASE ->
                DeviceRecordingTruth.STARTING
            OmiCv1FunctionalCapturePhase.BASE_ACTIVE,
            OmiCv1FunctionalCapturePhase.STARTING_VOICE_OVERLAY,
            OmiCv1FunctionalCapturePhase.VOICE_OVERLAY_ACTIVE,
            -> if (baseAudioPermitted) DeviceRecordingTruth.ACTIVE else DeviceRecordingTruth.STARTING
            OmiCv1FunctionalCapturePhase.STOPPING ->
                DeviceRecordingTruth.STOPPING
            OmiCv1FunctionalCapturePhase.BOOTING,
            OmiCv1FunctionalCapturePhase.IDLE,
            OmiCv1FunctionalCapturePhase.STARTING_VOICE_IDLE,
            OmiCv1FunctionalCapturePhase.VOICE_IDLE_ACTIVE,
            OmiCv1FunctionalCapturePhase.FATAL_IDLE,
            -> DeviceRecordingTruth.INACTIVE
        }
        val voiceAction = when (phase) {
            OmiCv1FunctionalCapturePhase.STARTING_VOICE_IDLE,
            OmiCv1FunctionalCapturePhase.STARTING_VOICE_OVERLAY,
            -> DeviceVoiceActionTruth.STARTING
            OmiCv1FunctionalCapturePhase.VOICE_IDLE_ACTIVE,
            OmiCv1FunctionalCapturePhase.VOICE_OVERLAY_ACTIVE,
            -> DeviceVoiceActionTruth.ACTIVE
            OmiCv1FunctionalCapturePhase.STOPPING ->
                if (voiceAudioPermitted) DeviceVoiceActionTruth.ENDING
                else DeviceVoiceActionTruth.INACTIVE
            else -> DeviceVoiceActionTruth.INACTIVE
        }
        val availability = when {
            faulted || phase == OmiCv1FunctionalCapturePhase.FATAL_IDLE ->
                DeviceCaptureAvailability.FAULTED
            updateAdmitted -> DeviceCaptureAvailability.MAINTENANCE
            phase == OmiCv1FunctionalCapturePhase.BOOTING ->
                DeviceCaptureAvailability.BOOTING
            recordingReady -> DeviceCaptureAvailability.READY
            phase != OmiCv1FunctionalCapturePhase.IDLE ->
                DeviceCaptureAvailability.BUSY
            else -> DeviceCaptureAvailability.DEGRADED
        }
        return DeviceCaptureState(
            generation = generation.toULong(),
            microphone = when (microphone) {
                OmiCv1FunctionalMicrophoneTruth.VERIFIED_OFF ->
                    DeviceMicrophoneTruth.VERIFIED_OFF
                OmiCv1FunctionalMicrophoneTruth.ACQUIRED ->
                    DeviceMicrophoneTruth.ACQUIRED
                OmiCv1FunctionalMicrophoneTruth.ACQUIRING,
                OmiCv1FunctionalMicrophoneTruth.RELEASING,
                -> DeviceMicrophoneTruth.TRANSITIONING
                OmiCv1FunctionalMicrophoneTruth.UNKNOWN ->
                    DeviceMicrophoneTruth.UNKNOWN
            },
            recording = recording,
            voiceAction = voiceAction,
            semanticSignal = DeviceSemanticSignalTruth.INACTIVE,
            privacyOutput = when {
                privacyAsserted -> DevicePrivacyOutputTruth.ACTIVE
                microphone == OmiCv1FunctionalMicrophoneTruth.VERIFIED_OFF ->
                    DevicePrivacyOutputTruth.INACTIVE
                else -> DevicePrivacyOutputTruth.UNKNOWN
            },
            maintenance = if (updateAdmitted) {
                DeviceMaintenanceTruth.ADMITTED
            } else {
                DeviceMaintenanceTruth.NORMAL
            },
            availability = availability,
            activeRecordingId = activeRecordingId.takeUnless { it == 0UL },
            freeBytes = freeBytes,
            faultCode = lastError.takeUnless { it == 0 }?.let { "OMI_FUNCTIONAL_ERROR_$it" },
            observedAtMonotonicMillis = observedAtMonotonicMillis,
        )
    }
}

class OmiCv1FunctionalProtocolException(message: String) : IllegalArgumentException(message)

/** Exact decoder and topology gate for gumi.functional-gatt/v1. */
object OmiCv1FunctionalGattV1 {
    const val SERVICE_UUID = "47554d49-0001-4f4d-492d-435631000001"
    const val STATUS_CHARACTERISTIC_UUID = "47554d49-0002-4f4d-492d-435631000001"
    const val CAPABILITIES_CHARACTERISTIC_UUID = "47554d49-0003-4f4d-492d-435631000001"
    const val FAMILY_IDENTITY_SERVICE_UUID = "19b10000-e8f2-537e-4f6c-d104768a1214"
    const val STATUS_WIRE_SIZE = 40
    const val CAPABILITIES_WIRE_SIZE = 16

    val expectedCapabilities: ByteArray
        get() = byteArrayOf(
            1, 1, 1, 1, 1, 1, 1, 1,
            3, 0, 0, 0, 0, 0, 0, 0,
        )

    fun validate(
        statusBytes: ByteArray,
        capabilitiesBytes: ByteArray,
        discoveredServiceUuids: Set<String>,
        familyIdentityServiceHasCharacteristics: Boolean,
    ): OmiCv1FunctionalStatusEvidence {
        val services = discoveredServiceUuids.mapTo(mutableSetOf()) { it.lowercase() }
        rejectUnless(SERVICE_UUID in services) { "Functional Gumi service is absent" }
        rejectUnless(FAMILY_IDENTITY_SERVICE_UUID in services) {
            "The empty Omi-family identity service is absent"
        }
        rejectUnless(!familyIdentityServiceHasCharacteristics) {
            "Omi-family identity service is not empty in functional mode"
        }
        val unexpectedServices = services.intersect(FORBIDDEN_MODE_SERVICE_UUIDS)
        rejectUnless(unexpectedServices.isEmpty()) {
            "A recovery, self-test, or stock functional service is unexpectedly present: " +
                unexpectedServices.sorted().joinToString()
        }
        rejectUnless(statusBytes.size == STATUS_WIRE_SIZE) {
            "Functional status must contain exactly $STATUS_WIRE_SIZE bytes"
        }
        rejectUnless(capabilitiesBytes.size == CAPABILITIES_WIRE_SIZE) {
            "Functional capabilities must contain exactly $CAPABILITIES_WIRE_SIZE bytes"
        }
        rejectUnless(capabilitiesBytes.contentEquals(expectedCapabilities)) {
            "Functional capabilities are not the exact local-recording/read-only-state v1 descriptor"
        }
        rejectUnless(statusBytes.sliceArray(32 until STATUS_WIRE_SIZE).all { it == 0.toByte() }) {
            "Functional status reserved bytes are nonzero"
        }

        val schema = statusBytes.u8(0)
        val phase = enumValue<OmiCv1FunctionalCapturePhase>(statusBytes.u8(1), "capture phase")
        val microphone =
            enumValue<OmiCv1FunctionalMicrophoneTruth>(statusBytes.u8(2), "microphone truth")
        val storageState =
            enumValue<OmiCv1FunctionalStorageState>(statusBytes.u8(3), "storage state")
        val key = enumValue<OmiCv1FunctionalKeyTruth>(statusBytes.u8(4), "recording-key truth")
        val recordingStorage = enumValue<OmiCv1FunctionalRecordingStorageTruth>(
            statusBytes.u8(5),
            "recording-storage truth",
        )
        val codec = enumValue<OmiCv1FunctionalCodecTruth>(statusBytes.u8(6), "codec truth")
        val flags = statusBytes.u8(7)
        rejectUnless(schema == EXPECTED_SCHEMA) { "Unsupported functional status schema $schema" }

        val evidence = OmiCv1FunctionalStatusEvidence(
            schemaVersion = schema,
            phase = phase,
            microphone = microphone,
            storageState = storageState,
            key = key,
            recordingStorage = recordingStorage,
            codec = codec,
            flags = flags,
            activeRecordingId = statusBytes.u64le(8),
            freeBytes = statusBytes.u64le(16),
            lastError = statusBytes.i32le(24),
            generation = statusBytes.u32le(28),
            operational = flags and OPERATIONAL != 0,
            baseAudioPermitted = flags and BASE_AUDIO_PERMITTED != 0,
            voiceAudioPermitted = flags and VOICE_AUDIO_PERMITTED != 0,
            privacyAsserted = flags and PRIVACY_ASSERTED != 0,
            updateAdmitted = flags and UPDATE_ADMITTED != 0,
            keyReady = flags and KEY_READY != 0,
            storageReady = flags and STORAGE_READY != 0,
            faulted = flags and FAULTED != 0,
            familyIdentityServiceEmpty = true,
            capabilitiesExact = true,
            rawHex = statusBytes.joinToString("") {
                it.toUByte().toString(16).padStart(2, '0')
            },
        )
        validateInvariants(evidence)
        return evidence
    }

    fun requireRecoveryMaintenance(evidence: OmiCv1FunctionalStatusEvidence) {
        rejectUnless(evidence.updateAdmitted) {
            "Functional image-0 recovery is not physically admitted; hold the Omi button " +
                "continuously for five seconds while capture is idle"
        }
        rejectUnless(
            evidence.phase in MAINTENANCE_IDLE_PHASES,
        ) {
            "Functional recovery maintenance is not in an idle capture phase"
        }
        rejectUnless(
            evidence.microphone == OmiCv1FunctionalMicrophoneTruth.VERIFIED_OFF &&
                !evidence.baseAudioPermitted &&
                !evidence.voiceAudioPermitted &&
                !evidence.privacyAsserted &&
                evidence.activeRecordingId == 0UL,
        ) {
            "Functional recovery maintenance lacks exact microphone-off and capture-idle evidence"
        }
    }

    private fun validateInvariants(evidence: OmiCv1FunctionalStatusEvidence) {
        rejectUnless(evidence.keyReady == (evidence.key == OmiCv1FunctionalKeyTruth.READY)) {
            "Functional key-ready flag disagrees with recording-key truth"
        }
        rejectUnless(
            evidence.storageReady == (evidence.recordingStorage in READY_RECORDING_STORAGE),
        ) {
            "Functional storage-ready flag disagrees with recording-storage truth"
        }
        rejectUnless(!evidence.operational || evidence.phase != OmiCv1FunctionalCapturePhase.BOOTING) {
            "Functional status claims operational while the capture supervisor is booting"
        }
        rejectUnless(
            !evidence.baseAudioPermitted ||
                evidence.phase in setOf(
                    OmiCv1FunctionalCapturePhase.BASE_ACTIVE,
                    OmiCv1FunctionalCapturePhase.STARTING_VOICE_OVERLAY,
                    OmiCv1FunctionalCapturePhase.VOICE_OVERLAY_ACTIVE,
                ),
        ) {
            "Base-audio permission disagrees with capture phase"
        }
        rejectUnless(
            !evidence.voiceAudioPermitted ||
                evidence.phase in setOf(
                    OmiCv1FunctionalCapturePhase.VOICE_IDLE_ACTIVE,
                    OmiCv1FunctionalCapturePhase.VOICE_OVERLAY_ACTIVE,
                ),
        ) {
            "Voice-audio permission disagrees with capture phase"
        }
        rejectUnless(
            !(evidence.baseAudioPermitted || evidence.voiceAudioPermitted) ||
                evidence.microphone == OmiCv1FunctionalMicrophoneTruth.ACQUIRED,
        ) {
            "Functional status permits audio without an acquired microphone"
        }
        rejectUnless(
            !(evidence.baseAudioPermitted || evidence.voiceAudioPermitted) ||
                evidence.privacyAsserted,
        ) {
            "Functional status permits audio without asserted privacy output"
        }
        rejectUnless(
            !evidence.baseAudioPermitted ||
                (
                    evidence.recordingStorage == OmiCv1FunctionalRecordingStorageTruth.ACTIVE &&
                        evidence.codec == OmiCv1FunctionalCodecTruth.ACTIVE &&
                        evidence.activeRecordingId != 0UL
                    ),
        ) {
            "Functional base-audio permission lacks an active durable recording pipeline"
        }
        rejectUnless(
            !evidence.updateAdmitted ||
                (
                    evidence.phase in MAINTENANCE_IDLE_PHASES &&
                        evidence.microphone ==
                        OmiCv1FunctionalMicrophoneTruth.VERIFIED_OFF &&
                        !evidence.baseAudioPermitted &&
                        !evidence.voiceAudioPermitted &&
                        !evidence.privacyAsserted &&
                        evidence.activeRecordingId == 0UL
                    ),
        ) {
            "Functional update admission is incompatible with live or uncertain capture"
        }
        rejectUnless(evidence.lastError == 0 || evidence.faulted) {
            "Functional status reports an error without the fault flag"
        }
        rejectUnless(!evidence.recordingReady || evidence.freeBytes >= MINIMUM_RECORDING_FREE_BYTES) {
            "Functional status claims recording-ready below the firmware free-space floor"
        }
    }

    private inline fun <reified T> enumValue(wireValue: Int, label: String): T where T : Enum<T> {
        val value = enumValues<T>().singleOrNull {
            when (it) {
                is OmiCv1FunctionalCapturePhase -> it.wireValue
                is OmiCv1FunctionalMicrophoneTruth -> it.wireValue
                is OmiCv1FunctionalStorageState -> it.wireValue
                is OmiCv1FunctionalKeyTruth -> it.wireValue
                is OmiCv1FunctionalRecordingStorageTruth -> it.wireValue
                is OmiCv1FunctionalCodecTruth -> it.wireValue
                else -> -1
            } == wireValue
        }
        rejectUnless(value != null) { "Unknown functional $label $wireValue" }
        return requireNotNull(value)
    }

    private fun rejectUnless(condition: Boolean, message: () -> String) {
        if (!condition) throw OmiCv1FunctionalProtocolException(message())
    }

    private const val EXPECTED_SCHEMA = 1
    private const val OPERATIONAL = 1 shl 0
    private const val BASE_AUDIO_PERMITTED = 1 shl 1
    private const val VOICE_AUDIO_PERMITTED = 1 shl 2
    private const val PRIVACY_ASSERTED = 1 shl 3
    private const val UPDATE_ADMITTED = 1 shl 4
    private const val KEY_READY = 1 shl 5
    private const val STORAGE_READY = 1 shl 6
    private const val FAULTED = 1 shl 7
    private const val MINIMUM_RECORDING_FREE_BYTES = 4_194_304UL
    private val MAINTENANCE_IDLE_PHASES = setOf(
        OmiCv1FunctionalCapturePhase.BOOTING,
        OmiCv1FunctionalCapturePhase.IDLE,
        OmiCv1FunctionalCapturePhase.FATAL_IDLE,
    )

    private val FORBIDDEN_MODE_SERVICE_UUIDS = setOf(
        OmiCv1GattProfile.RECOVERY_SERVICE,
        "f80a6e60-3b3f-4e8a-93e4-5f5e2c527001",
        OmiCv1GattProfile.BATTERY_SERVICE,
        "23ba7924-0000-1000-7450-346eac492e92",
        "cab1ab95-2ea5-4f4d-bb56-874b72cfc984",
        "19b10010-e8f2-537e-4f6c-d104768a1214",
        "19b10020-e8f2-537e-4f6c-d104768a1214",
        OmiCv1Protocol.OFFLINE_STORAGE_SERVICE_UUID,
    )
}

private val READY_RECORDING_STORAGE = setOf(
    OmiCv1FunctionalRecordingStorageTruth.READY,
    OmiCv1FunctionalRecordingStorageTruth.COMMITTED,
    OmiCv1FunctionalRecordingStorageTruth.INTERRUPTED,
)

private fun ByteArray.u8(offset: Int): Int = this[offset].toUByte().toInt()

private fun ByteArray.u64le(offset: Int): ULong {
    var value = 0UL
    for (index in 0 until 8) {
        value = value or (this[offset + index].toUByte().toULong() shl (index * 8))
    }
    return value
}

private fun ByteArray.u32le(offset: Int): Long =
    this[offset].toUByte().toLong() or
        (this[offset + 1].toUByte().toLong() shl 8) or
        (this[offset + 2].toUByte().toLong() shl 16) or
        (this[offset + 3].toUByte().toLong() shl 24)

private fun ByteArray.i32le(offset: Int): Int = u32le(offset).toInt()
