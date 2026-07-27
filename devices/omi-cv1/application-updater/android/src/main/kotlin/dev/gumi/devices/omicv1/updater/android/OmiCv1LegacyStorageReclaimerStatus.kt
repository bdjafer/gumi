package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate

internal fun interface OmiCv1LegacyStorageReclaimerStatusProbe {
    suspend fun inspect(endpoint: EndpointCandidate): OmiCv1LegacyStorageReclaimerStatusEvidence
}

internal enum class OmiCv1LegacyStorageReclaimerPhase(val wireValue: Int) {
    COLD(0),
    SAFE_TRANSPORT_READY(1),
    INSPECTING(2),
    RECLAIMING(3),
    RECLAIMED(4),
    ALREADY_ABSENT(5),
    REFUSED(6),
    FAILED(7),
    ;

    val terminal: Boolean
        get() = this in setOf(RECLAIMED, ALREADY_ABSENT, REFUSED, FAILED)
}

internal data class OmiCv1LegacyStorageReclaimerStatusEvidence(
    val schemaVersion: Int,
    val phase: OmiCv1LegacyStorageReclaimerPhase,
    val flags: Int,
    val lastError: Int,
    val generation: Long,
    val targetSizeBytes: Long,
    val freeBytesBefore: Long,
    val freeBytesAfter: Long,
    val transportReady: Boolean,
    val microphoneVerifiedOff: Boolean,
    val volumeMounted: Boolean,
    val targetObserved: Boolean,
    val targetExact: Boolean,
    val deleteAttempted: Boolean,
    val targetAbsent: Boolean,
    val minimumFreeProven: Boolean,
    val mutationAdmitted: Boolean,
    val recoveryTransportPresent: Boolean,
    val familyIdentityServiceEmpty: Boolean,
    val statusSurfaceExact: Boolean,
    val functionalOmiServicesAbsent: Boolean,
    val rawHex: String,
) {
    val reclaimSucceeded: Boolean
        get() = when (phase) {
            OmiCv1LegacyStorageReclaimerPhase.RECLAIMED ->
                lastError == 0 &&
                    flags == OmiCv1LegacyStorageReclaimerStatusProtocol.RECLAIMED_FLAGS &&
                    targetSizeBytes ==
                    OmiCv1LegacyStorageReclaimerStatusProtocol.EXACT_TARGET_SIZE_BYTES &&
                    freeBytesAfter >=
                    OmiCv1LegacyStorageReclaimerStatusProtocol.MINIMUM_FREE_BYTES

            OmiCv1LegacyStorageReclaimerPhase.ALREADY_ABSENT ->
                lastError == 0 &&
                    flags == OmiCv1LegacyStorageReclaimerStatusProtocol.ALREADY_ABSENT_FLAGS &&
                    targetSizeBytes == 0L &&
                    freeBytesAfter >=
                    OmiCv1LegacyStorageReclaimerStatusProtocol.MINIMUM_FREE_BYTES

            else -> false
        }

    val recoveryEligible: Boolean
        get() = phase.terminal &&
            transportReady &&
            microphoneVerifiedOff &&
            mutationAdmitted
}

/**
 * Validates the status-only evidence surface without turning a refusal into success.
 *
 * [validate] accepts exact nonterminal and terminal evidence. Callers must separately invoke
 * [requireReclaimSucceeded] before functional firmware, or [requireRecoveryEligible] before a
 * recovery-only escape.
 */
internal object OmiCv1LegacyStorageReclaimerStatusProtocol {
    const val SERVICE_UUID = "47554d49-0011-4f4d-492d-435631000001"
    const val STATUS_CHARACTERISTIC_UUID = "47554d49-0011-4f4d-492d-435631000002"
    const val FAMILY_IDENTITY_SERVICE_UUID = "19b10000-e8f2-537e-4f6c-d104768a1214"
    const val STATUS_WIRE_SIZE = 40
    const val EXACT_TARGET_SIZE_BYTES = 505_118_720L
    const val MINIMUM_FREE_BYTES = 4_194_304L

    internal const val TRANSPORT_READY = 1 shl 0
    internal const val MICROPHONE_VERIFIED_OFF = 1 shl 1
    internal const val VOLUME_MOUNTED = 1 shl 2
    internal const val TARGET_OBSERVED = 1 shl 3
    internal const val TARGET_EXACT = 1 shl 4
    internal const val DELETE_ATTEMPTED = 1 shl 5
    internal const val TARGET_ABSENT = 1 shl 6
    internal const val MINIMUM_FREE_PROVEN = 1 shl 7
    internal const val MUTATION_ADMITTED = 1 shl 8
    internal const val RECLAIMED_FLAGS =
        TRANSPORT_READY or MICROPHONE_VERIFIED_OFF or VOLUME_MOUNTED or
            TARGET_OBSERVED or TARGET_EXACT or DELETE_ATTEMPTED or TARGET_ABSENT or
            MINIMUM_FREE_PROVEN or MUTATION_ADMITTED
    internal const val ALREADY_ABSENT_FLAGS =
        TRANSPORT_READY or MICROPHONE_VERIFIED_OFF or VOLUME_MOUNTED or TARGET_ABSENT or
            MINIMUM_FREE_PROVEN or MUTATION_ADMITTED

    private const val EXPECTED_SCHEMA = 1
    private const val ALL_FLAGS = RECLAIMED_FLAGS

    private val forbiddenFunctionalServiceUuids = setOf(
        OmiCv1CaptureSelftestProtocol.SERVICE_UUID,
        OmiCv1RecordingRootProvisionerStatusProtocol.SERVICE_UUID,
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
        reclaimerCharacteristicUuids: Set<String>,
        familyIdentityServiceHasCharacteristics: Boolean,
    ): OmiCv1LegacyStorageReclaimerStatusEvidence {
        val services = discoveredServiceUuids.mapTo(mutableSetOf()) { it.lowercase() }
        val characteristics =
            reclaimerCharacteristicUuids.mapTo(mutableSetOf()) { it.lowercase() }
        rejectUnless(SERVICE_UUID in services) {
            "Legacy-storage reclaimer service is absent"
        }
        rejectUnless(OmiCv1RecoveryStatusProtocol.RECOVERY_SERVICE_UUID in services) {
            "Reclaimer recovery transport status service is absent"
        }
        rejectUnless(FAMILY_IDENTITY_SERVICE_UUID in services) {
            "The empty Omi-family identity service is absent"
        }
        rejectUnless(!familyIdentityServiceHasCharacteristics) {
            "Omi-family identity service is not empty in reclaimer mode"
        }
        rejectUnless(characteristics == setOf(STATUS_CHARACTERISTIC_UUID)) {
            "Reclaimer GATT surface is not the exact status-only contract"
        }
        val unexpectedServices = services.intersect(forbiddenFunctionalServiceUuids)
        rejectUnless(unexpectedServices.isEmpty()) {
            "A provisioner, self-test, functional, or stock service is unexpectedly present: " +
                unexpectedServices.sorted().joinToString()
        }
        rejectUnless(statusBytes.size == STATUS_WIRE_SIZE) {
            "Legacy-storage status must contain exactly $STATUS_WIRE_SIZE bytes"
        }
        rejectUnless(statusBytes.copyOfRange(36, 40).all { it == 0.toByte() }) {
            "Legacy-storage status reserved bytes are nonzero"
        }

        val schema = statusBytes.u8LegacyReclaimer(0)
        val phaseValue = statusBytes.u8LegacyReclaimer(1)
        val flags = statusBytes.u16leLegacyReclaimer(2)
        val phase = OmiCv1LegacyStorageReclaimerPhase.entries.singleOrNull {
            it.wireValue == phaseValue
        }
        rejectUnless(schema == EXPECTED_SCHEMA) {
            "Unsupported legacy-storage status schema $schema"
        }
        rejectUnless(phase != null) {
            "Unknown legacy-storage reclaimer phase $phaseValue"
        }
        rejectUnless(flags and ALL_FLAGS == flags) {
            "Legacy-storage status contains unknown flags"
        }

        val evidence = OmiCv1LegacyStorageReclaimerStatusEvidence(
            schemaVersion = schema,
            phase = requireNotNull(phase),
            flags = flags,
            lastError = statusBytes.i32leLegacyReclaimer(4),
            generation = statusBytes.u32leLegacyReclaimer(8),
            targetSizeBytes = statusBytes.u64leLegacyReclaimer(12),
            freeBytesBefore = statusBytes.u64leLegacyReclaimer(20),
            freeBytesAfter = statusBytes.u64leLegacyReclaimer(28),
            transportReady = flags and TRANSPORT_READY != 0,
            microphoneVerifiedOff = flags and MICROPHONE_VERIFIED_OFF != 0,
            volumeMounted = flags and VOLUME_MOUNTED != 0,
            targetObserved = flags and TARGET_OBSERVED != 0,
            targetExact = flags and TARGET_EXACT != 0,
            deleteAttempted = flags and DELETE_ATTEMPTED != 0,
            targetAbsent = flags and TARGET_ABSENT != 0,
            minimumFreeProven = flags and MINIMUM_FREE_PROVEN != 0,
            mutationAdmitted = flags and MUTATION_ADMITTED != 0,
            recoveryTransportPresent = true,
            familyIdentityServiceEmpty = true,
            statusSurfaceExact = true,
            functionalOmiServicesAbsent = true,
            rawHex = statusBytes.joinToString("") {
                it.toUByte().toString(16).padStart(2, '0')
            },
        )
        rejectUnless(!evidence.targetExact || evidence.targetObserved) {
            "Exact-target flag is present without a target observation"
        }
        rejectUnless(!evidence.deleteAttempted || evidence.targetExact) {
            "Delete-attempt flag is present without an exact target"
        }
        rejectUnless(!evidence.minimumFreeProven || evidence.targetAbsent) {
            "Minimum-free flag is present without proving the target absent"
        }
        rejectUnless(!evidence.mutationAdmitted || evidence.phase.terminal) {
            "Follow-up mutation was admitted before a terminal state"
        }
        rejectUnless(
            !evidence.mutationAdmitted ||
                (evidence.transportReady && evidence.microphoneVerifiedOff),
        ) {
            "Follow-up mutation was admitted without transport and microphone-off proof"
        }
        if (evidence.phase == OmiCv1LegacyStorageReclaimerPhase.RECLAIMED) {
            rejectUnless(evidence.reclaimSucceeded) {
                "Reclaimed terminal evidence does not prove the exact deletion and free-space result"
            }
        }
        if (evidence.phase == OmiCv1LegacyStorageReclaimerPhase.ALREADY_ABSENT) {
            rejectUnless(evidence.reclaimSucceeded) {
                "Already-absent terminal evidence does not prove sufficient free space"
            }
        }
        if (
            evidence.phase in setOf(
                OmiCv1LegacyStorageReclaimerPhase.REFUSED,
                OmiCv1LegacyStorageReclaimerPhase.FAILED,
            )
        ) {
            rejectUnless(evidence.lastError < 0) {
                "Non-success terminal evidence must carry a negative errno"
            }
        }
        return evidence
    }

    fun requireReclaimSucceeded(evidence: OmiCv1LegacyStorageReclaimerStatusEvidence) {
        rejectUnless(evidence.reclaimSucceeded) {
            "Legacy stock recording reclamation has not been proven successful"
        }
    }

    fun requireRecoveryEligible(evidence: OmiCv1LegacyStorageReclaimerStatusEvidence) {
        rejectUnless(evidence.recoveryEligible) {
            "Reclaimer has not safely admitted a recovery-only transition"
        }
    }

    private fun rejectUnless(condition: Boolean, message: () -> String) {
        if (!condition) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.LEGACY_STORAGE_RECLAIMER_EVIDENCE_REJECTED,
                message(),
            )
        }
    }
}

private fun ByteArray.u8LegacyReclaimer(offset: Int): Int = this[offset].toUByte().toInt()

private fun ByteArray.u16leLegacyReclaimer(offset: Int): Int =
    u8LegacyReclaimer(offset) or (u8LegacyReclaimer(offset + 1) shl 8)

private fun ByteArray.u32leLegacyReclaimer(offset: Int): Long =
    this[offset].toUByte().toLong() or
        (this[offset + 1].toUByte().toLong() shl 8) or
        (this[offset + 2].toUByte().toLong() shl 16) or
        (this[offset + 3].toUByte().toLong() shl 24)

private fun ByteArray.i32leLegacyReclaimer(offset: Int): Int =
    u32leLegacyReclaimer(offset).toInt()

private fun ByteArray.u64leLegacyReclaimer(offset: Int): Long =
    (0 until 8).fold(0L) { value, index ->
        value or (this[offset + index].toUByte().toLong() shl (index * 8))
    }
