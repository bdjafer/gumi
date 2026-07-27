package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate

internal fun interface OmiCv1RecoveryStatusProbe {
    suspend fun inspect(endpoint: EndpointCandidate): OmiCv1RecoveryStatusEvidence
}

internal data class OmiCv1RecoveryStatusEvidence(
    val schemaVersion: Int,
    val phase: Int,
    val reason: Int,
    val flags: Int,
    val rawHex: String,
    val recoveryTransportReady: Boolean,
    val microphoneVerifiedOff: Boolean,
    val capturePermitted: Boolean,
    val overwriteOnlyBootPolicyObserved: Boolean,
    val stockIdentityServiceEmpty: Boolean,
    val functionalOmiServicesAbsent: Boolean,
)

/** Pure validator for the recovery status wire value and the discovered GATT topology. */
internal object OmiCv1RecoveryStatusProtocol {
    const val RECOVERY_SERVICE_UUID = "796e0485-8f9d-4063-af3b-f5596fced74a"
    const val STATUS_CHARACTERISTIC_UUID = "32fcb4a7-660b-4c26-a887-3baf0166246c"
    const val STOCK_IDENTITY_SERVICE_UUID = "19b10000-e8f2-537e-4f6c-d104768a1214"

    private val forbiddenFunctionalServiceUuids = setOf(
        "0000180f-0000-1000-8000-00805f9b34fb", // Battery
        "23ba7924-0000-1000-7450-346eac492e92",
        "cab1ab95-2ea5-4f4d-bb56-874b72cfc984",
        "19b10010-e8f2-537e-4f6c-d104768a1214", // Settings
        "19b10020-e8f2-537e-4f6c-d104768a1214", // Features
        "30295780-4301-eabd-2904-2849adfeae43", // Offline storage
    )

    fun validate(
        statusBytes: ByteArray,
        discoveredServiceUuids: Set<String>,
        stockIdentityServiceHasCharacteristics: Boolean,
    ): OmiCv1RecoveryStatusEvidence {
        val services = discoveredServiceUuids.mapTo(mutableSetOf()) { it.lowercase() }
        rejectUnless(RECOVERY_SERVICE_UUID in services) {
            "Recovery status service is absent"
        }
        rejectUnless(STOCK_IDENTITY_SERVICE_UUID in services) {
            "The empty stock-family identity service is absent"
        }
        rejectUnless(!stockIdentityServiceHasCharacteristics) {
            "Stock-family identity service is not empty in recovery mode"
        }
        val unexpectedServices = services.intersect(forbiddenFunctionalServiceUuids)
        rejectUnless(unexpectedServices.isEmpty()) {
            "Functional Omi services are unexpectedly present: ${unexpectedServices.sorted().joinToString()}"
        }
        rejectUnless(statusBytes.size == STATUS_WIRE_SIZE) {
            "Recovery status must contain exactly $STATUS_WIRE_SIZE bytes"
        }

        val values = statusBytes.map(Byte::toUByte).map(UByte::toInt)
        val schema = values[0]
        val phase = values[1]
        val reason = values[2]
        val flags = values[3]
        rejectUnless(schema == EXPECTED_SCHEMA) { "Unsupported recovery status schema $schema" }
        rejectUnless(phase == SAFE_MODE_PHASE) { "Recovery supervisor is not in safe mode (phase $phase)" }
        rejectUnless(reason == EXPLICIT_SAFE_MODE_REASON) {
            "Recovery supervisor did not enter explicit safe mode (reason $reason)"
        }
        rejectUnless(flags == EXPECTED_SAFE_MODE_FLAGS) {
            "Recovery status flags are not the exact fail-closed value 0x${flags.toString(16).padStart(2, '0')}"
        }

        return OmiCv1RecoveryStatusEvidence(
            schemaVersion = schema,
            phase = phase,
            reason = reason,
            flags = flags,
            rawHex = statusBytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') },
            recoveryTransportReady = flags and TRANSPORT_READY_FLAG != 0,
            microphoneVerifiedOff = flags and MICROPHONE_OFF_FLAG != 0,
            capturePermitted = flags and CAPTURE_PERMITTED_FLAG != 0,
            overwriteOnlyBootPolicyObserved = flags and OVERWRITE_ONLY_FLAG != 0,
            stockIdentityServiceEmpty = true,
            functionalOmiServicesAbsent = true,
        )
    }

    private fun rejectUnless(condition: Boolean, message: () -> String) {
        if (!condition) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.RECOVERY_EVIDENCE_REJECTED,
                message(),
            )
        }
    }

    private const val STATUS_WIRE_SIZE = 4
    private const val EXPECTED_SCHEMA = 1
    private const val SAFE_MODE_PHASE = 7
    private const val EXPLICIT_SAFE_MODE_REASON = 1
    private const val TRANSPORT_READY_FLAG = 0x01
    private const val MICROPHONE_OFF_FLAG = 0x02
    private const val CAPTURE_PERMITTED_FLAG = 0x10
    private const val OVERWRITE_ONLY_FLAG = 0x20
    private const val EXPECTED_SAFE_MODE_FLAGS =
        TRANSPORT_READY_FLAG or MICROPHONE_OFF_FLAG or OVERWRITE_ONLY_FLAG
}
