package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate

internal fun interface OmiCv1RecordingRootProvisionerStatusProbe {
    suspend fun inspect(endpoint: EndpointCandidate): OmiCv1RecordingRootProvisionerStatusEvidence
}

internal enum class OmiCv1RecordingRootProvisionerPhase(val wireValue: Int) {
    COLD(0),
    SAFE_TRANSPORT_READY(1),
    PROVISIONING(2),
    PROVISIONED(3),
    ALREADY_PRESENT(4),
    FAILED(5),
}

internal data class OmiCv1RecordingRootProvisionerStatusEvidence(
    val schemaVersion: Int,
    val phase: OmiCv1RecordingRootProvisionerPhase,
    val flags: Int,
    val lastError: Int,
    val generation: Long,
    val transportReady: Boolean,
    val microphoneVerifiedOff: Boolean,
    val writeAttempted: Boolean,
    val mextPresent: Boolean,
    val derivationVerified: Boolean,
    val mutationAdmitted: Boolean,
    val recoveryTransportPresent: Boolean,
    val familyIdentityServiceEmpty: Boolean,
    val statusSurfaceExact: Boolean,
    val functionalOmiServicesAbsent: Boolean,
    val rawHex: String,
) {
    val provisioningComplete: Boolean
        get() = phase in setOf(
            OmiCv1RecordingRootProvisionerPhase.PROVISIONED,
            OmiCv1RecordingRootProvisionerPhase.ALREADY_PRESENT,
        ) &&
            lastError == 0 &&
            transportReady &&
            microphoneVerifiedOff &&
            mextPresent &&
            derivationVerified &&
            mutationAdmitted &&
            (phase != OmiCv1RecordingRootProvisionerPhase.PROVISIONED || writeAttempted)
}

/**
 * Validates only non-secret provisioning evidence.
 *
 * The service exposes one fixed 12-byte status characteristic. No root, derivative, digest, or
 * arbitrary key-slot read is part of this contract.
 */
internal object OmiCv1RecordingRootProvisionerStatusProtocol {
    const val SERVICE_UUID = "47554d49-0010-4f4d-492d-435631000001"
    const val STATUS_CHARACTERISTIC_UUID = "47554d49-0010-4f4d-492d-435631000002"
    const val FAMILY_IDENTITY_SERVICE_UUID = "19b10000-e8f2-537e-4f6c-d104768a1214"
    const val STATUS_WIRE_SIZE = 12

    private val forbiddenFunctionalServiceUuids = setOf(
        OmiCv1CaptureSelftestProtocol.SERVICE_UUID,
        OmiCv1FunctionalStatusProtocol.SERVICE_UUID,
        "0000180f-0000-1000-8000-00805f9b34fb", // Stock battery.
        "23ba7924-0000-1000-7450-346eac492e92", // Stock audio.
        "cab1ab95-2ea5-4f4d-bb56-874b72cfc984", // Stock storage.
        "19b10010-e8f2-537e-4f6c-d104768a1214", // Stock settings.
        "19b10020-e8f2-537e-4f6c-d104768a1214", // Stock features.
        "30295780-4301-eabd-2904-2849adfeae43", // Stock offline storage.
    )

    fun validate(
        statusBytes: ByteArray,
        discoveredServiceUuids: Set<String>,
        provisionerCharacteristicUuids: Set<String>,
        familyIdentityServiceHasCharacteristics: Boolean,
    ): OmiCv1RecordingRootProvisionerStatusEvidence {
        val services = discoveredServiceUuids.mapTo(mutableSetOf()) { it.lowercase() }
        val characteristics =
            provisionerCharacteristicUuids.mapTo(mutableSetOf()) { it.lowercase() }
        rejectUnless(SERVICE_UUID in services) {
            "Recording-root provisioner service is absent"
        }
        rejectUnless(OmiCv1RecoveryStatusProtocol.RECOVERY_SERVICE_UUID in services) {
            "Provisioner recovery transport status service is absent"
        }
        rejectUnless(FAMILY_IDENTITY_SERVICE_UUID in services) {
            "The empty Omi-family identity service is absent"
        }
        rejectUnless(!familyIdentityServiceHasCharacteristics) {
            "Omi-family identity service is not empty in provisioning mode"
        }
        rejectUnless(characteristics == setOf(STATUS_CHARACTERISTIC_UUID)) {
            "Provisioner GATT surface is not the exact status-only contract"
        }
        val unexpectedServices = services.intersect(forbiddenFunctionalServiceUuids)
        rejectUnless(unexpectedServices.isEmpty()) {
            "A self-test, functional, or stock service is unexpectedly present: " +
                unexpectedServices.sorted().joinToString()
        }
        rejectUnless(statusBytes.size == STATUS_WIRE_SIZE) {
            "Recording-root status must contain exactly $STATUS_WIRE_SIZE bytes"
        }
        rejectUnless(statusBytes[3] == 0.toByte()) {
            "Recording-root status reserved byte is nonzero"
        }

        val schema = statusBytes.u8RecordingRoot(0)
        val phaseValue = statusBytes.u8RecordingRoot(1)
        val flags = statusBytes.u8RecordingRoot(2)
        val phase = OmiCv1RecordingRootProvisionerPhase.entries.singleOrNull {
            it.wireValue == phaseValue
        }
        rejectUnless(schema == EXPECTED_SCHEMA) {
            "Unsupported recording-root status schema $schema"
        }
        rejectUnless(phase != null) {
            "Unknown recording-root provisioner phase $phaseValue"
        }
        rejectUnless(flags and ALL_FLAGS == flags) {
            "Recording-root status contains unknown flags"
        }

        val evidence = OmiCv1RecordingRootProvisionerStatusEvidence(
            schemaVersion = schema,
            phase = requireNotNull(phase),
            flags = flags,
            lastError = statusBytes.i32leRecordingRoot(4),
            generation = statusBytes.u32leRecordingRoot(8),
            transportReady = flags and TRANSPORT_READY != 0,
            microphoneVerifiedOff = flags and MICROPHONE_VERIFIED_OFF != 0,
            writeAttempted = flags and WRITE_ATTEMPTED != 0,
            mextPresent = flags and MEXT_PRESENT != 0,
            derivationVerified = flags and DERIVATION_VERIFIED != 0,
            mutationAdmitted = flags and MUTATION_ADMITTED != 0,
            recoveryTransportPresent = true,
            familyIdentityServiceEmpty = true,
            statusSurfaceExact = true,
            functionalOmiServicesAbsent = true,
            rawHex = statusBytes.joinToString("") {
                it.toUByte().toString(16).padStart(2, '0')
            },
        )
        rejectUnless(evidence.provisioningComplete) {
            "Recording-root provisioner has not proven a usable MEXT root"
        }
        rejectUnless(
            evidence.phase != OmiCv1RecordingRootProvisionerPhase.ALREADY_PRESENT ||
                !evidence.writeAttempted,
        ) {
            "Already-present result unexpectedly claims a write attempt"
        }
        return evidence
    }

    private fun rejectUnless(condition: Boolean, message: () -> String) {
        if (!condition) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.RECORDING_ROOT_PROVISIONER_EVIDENCE_REJECTED,
                message(),
            )
        }
    }

    private const val EXPECTED_SCHEMA = 1
    private const val TRANSPORT_READY = 1 shl 0
    private const val MICROPHONE_VERIFIED_OFF = 1 shl 1
    private const val WRITE_ATTEMPTED = 1 shl 2
    private const val MEXT_PRESENT = 1 shl 3
    private const val DERIVATION_VERIFIED = 1 shl 4
    private const val MUTATION_ADMITTED = 1 shl 5
    private const val ALL_FLAGS =
        TRANSPORT_READY or MICROPHONE_VERIFIED_OFF or WRITE_ATTEMPTED or
            MEXT_PRESENT or DERIVATION_VERIFIED or MUTATION_ADMITTED
}

private fun ByteArray.u8RecordingRoot(offset: Int): Int = this[offset].toUByte().toInt()

private fun ByteArray.u32leRecordingRoot(offset: Int): Long =
    this[offset].toUByte().toLong() or
        (this[offset + 1].toUByte().toLong() shl 8) or
        (this[offset + 2].toUByte().toLong() shl 16) or
        (this[offset + 3].toUByte().toLong() shl 24)

private fun ByteArray.i32leRecordingRoot(offset: Int): Int =
    u32leRecordingRoot(offset).toInt()
