package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1FunctionalGattV1
import dev.gumi.devices.omicv1.OmiCv1FunctionalProtocolException
import dev.gumi.edge.sdk.EndpointCandidate

internal fun interface OmiCv1FunctionalStatusProbe {
    suspend fun inspect(endpoint: EndpointCandidate): OmiCv1FunctionalStatusEvidence
}

internal typealias OmiCv1FunctionalCapturePhase =
    dev.gumi.devices.omicv1.OmiCv1FunctionalCapturePhase
internal typealias OmiCv1FunctionalMicrophoneTruth =
    dev.gumi.devices.omicv1.OmiCv1FunctionalMicrophoneTruth
internal typealias OmiCv1FunctionalStorageState =
    dev.gumi.devices.omicv1.OmiCv1FunctionalStorageState
internal typealias OmiCv1FunctionalKeyTruth =
    dev.gumi.devices.omicv1.OmiCv1FunctionalKeyTruth
internal typealias OmiCv1FunctionalRecordingStorageTruth =
    dev.gumi.devices.omicv1.OmiCv1FunctionalRecordingStorageTruth
internal typealias OmiCv1FunctionalCodecTruth =
    dev.gumi.devices.omicv1.OmiCv1FunctionalCodecTruth
internal typealias OmiCv1FunctionalStatusEvidence =
    dev.gumi.devices.omicv1.OmiCv1FunctionalStatusEvidence

/**
 * Flash Lab translates the shared driver decoder's typed rejection into its
 * own closed update failure vocabulary. Wire parsing has exactly one owner.
 */
internal object OmiCv1FunctionalStatusProtocol {
    const val SERVICE_UUID = OmiCv1FunctionalGattV1.SERVICE_UUID
    const val STATUS_CHARACTERISTIC_UUID = OmiCv1FunctionalGattV1.STATUS_CHARACTERISTIC_UUID
    const val CAPABILITIES_CHARACTERISTIC_UUID =
        OmiCv1FunctionalGattV1.CAPABILITIES_CHARACTERISTIC_UUID
    const val FAMILY_IDENTITY_SERVICE_UUID = OmiCv1FunctionalGattV1.FAMILY_IDENTITY_SERVICE_UUID
    const val STATUS_WIRE_SIZE = OmiCv1FunctionalGattV1.STATUS_WIRE_SIZE
    const val CAPABILITIES_WIRE_SIZE = OmiCv1FunctionalGattV1.CAPABILITIES_WIRE_SIZE

    val EXPECTED_CAPABILITIES: ByteArray
        get() = OmiCv1FunctionalGattV1.expectedCapabilities

    fun validate(
        statusBytes: ByteArray,
        capabilitiesBytes: ByteArray,
        discoveredServiceUuids: Set<String>,
        familyIdentityServiceHasCharacteristics: Boolean,
    ): OmiCv1FunctionalStatusEvidence = translate {
        OmiCv1FunctionalGattV1.validate(
            statusBytes = statusBytes,
            capabilitiesBytes = capabilitiesBytes,
            discoveredServiceUuids = discoveredServiceUuids,
            familyIdentityServiceHasCharacteristics = familyIdentityServiceHasCharacteristics,
        )
    }

    fun requireRecoveryMaintenance(evidence: OmiCv1FunctionalStatusEvidence) {
        translate { OmiCv1FunctionalGattV1.requireRecoveryMaintenance(evidence) }
    }

    private inline fun <T> translate(block: () -> T): T = try {
        block()
    } catch (error: OmiCv1FunctionalProtocolException) {
        throw OmiCv1ApplicationUpdateException(
            OmiCv1ApplicationUpdateFailureCode.FUNCTIONAL_EVIDENCE_REJECTED,
            error.message ?: "Functional firmware evidence was rejected",
            error,
        )
    }
}
