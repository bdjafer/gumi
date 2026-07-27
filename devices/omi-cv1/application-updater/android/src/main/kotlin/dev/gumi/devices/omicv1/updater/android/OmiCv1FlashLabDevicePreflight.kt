package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1GattEvidence
import dev.gumi.devices.omicv1.OmiCv1GattProfile
import dev.gumi.devices.omicv1.OmiCv1StockV3007FirmwareIdentity
import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.ble.BleGattInspectionException
import dev.gumi.edge.sdk.ble.BleGattInspectionFailureCode
import dev.gumi.edge.sdk.ble.BleGattInspector

internal data class OmiCv1FlashLabDevicePreflightEvidence(
    val endpoint: EndpointCandidate,
    val identity: OmiCv1GattEvidence,
    val serviceCount: Int,
    val characteristicCount: Int,
)

internal fun interface OmiCv1FlashLabDevicePreflightProbe {
    suspend fun inspect(endpoint: EndpointCandidate): OmiCv1FlashLabDevicePreflightEvidence
}

/**
 * Read-only device-side safety gate.
 *
 * Human checklist answers remain useful owner attestations, but they are never accepted as proof
 * of the Omi's observable battery or identity.
 */
internal object OmiCv1FlashLabDevicePreflightPolicy {
    fun requireSafe(
        evidence: OmiCv1FlashLabDevicePreflightEvidence,
        expectedEndpoint: EndpointCandidate,
        expectedManufacturer: String,
    ) {
        requireIdentity(evidence, expectedEndpoint, expectedManufacturer)
    }

    fun requireIdentity(
        evidence: OmiCv1FlashLabDevicePreflightEvidence,
        expectedEndpoint: EndpointCandidate,
        expectedManufacturer: String,
    ) {
        rejectUnless(
            evidence.endpoint == expectedEndpoint,
            OmiCv1ApplicationUpdateFailureCode.ENDPOINT_MISMATCH,
            "Device preflight evidence belongs to a different process-local endpoint",
        )
        rejectUnless(
            evidence.identity.manufacturer == expectedManufacturer,
            message = "Device manufacturer does not match the qualified Omi CV1 identity",
        )
        rejectUnless(
            evidence.identity.modelNumber == EXPECTED_MODEL,
            message = "Device model does not match the qualified Omi CV1 identity",
        )
        val firmwareRevision = evidence.identity.firmwareRevision
        rejectUnless(
            !firmwareRevision.isNullOrBlank(),
            message = "Device firmware revision is unavailable",
        )
        val expectedHardware = EXPECTED_HARDWARE_BY_FIRMWARE[firmwareRevision]
        rejectUnless(
            expectedHardware != null &&
                evidence.identity.hardwareRevision == expectedHardware,
            message = "Device hardware revision does not match the qualified Omi CV1 identity " +
                "for firmware $firmwareRevision",
        )
    }

    private fun rejectUnless(
        condition: Boolean,
        code: OmiCv1ApplicationUpdateFailureCode =
            OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
        message: String,
    ) {
        if (!condition) throw OmiCv1ApplicationUpdateException(code, message)
    }

    private const val EXPECTED_MODEL = "Omi CV 1"
    private val EXPECTED_HARDWARE_BY_FIRMWARE = mapOf(
        OmiCv1StockV3007FirmwareIdentity.DEVICE_INFORMATION_REVISION to
            OmiCv1StockV3007FirmwareIdentity.DEVICE_INFORMATION_HARDWARE_REVISION,
        OmiCv1StockV3012FirmwareOracle.DEVICE_INFORMATION_REVISION to
            OmiCv1StockV3012FirmwareOracle.DEVICE_INFORMATION_HARDWARE_REVISION,
    )
    const val LOW_OMI_BATTERY_WARNING_PERCENT = 20
}

internal class AndroidOmiCv1FlashLabDevicePreflightProbe(
    private val inspector: BleGattInspector,
) : OmiCv1FlashLabDevicePreflightProbe {
    override suspend fun inspect(
        endpoint: EndpointCandidate,
    ): OmiCv1FlashLabDevicePreflightEvidence = try {
        val inspection = inspector.inspect(endpoint, OmiCv1GattProfile.readOnlyInspectionRequest)
        OmiCv1FlashLabDevicePreflightEvidence(
            endpoint = inspection.endpoint,
            identity = OmiCv1GattProfile.decode(inspection),
            serviceCount = inspection.services.size,
            characteristicCount = inspection.services.sumOf { it.characteristics.size },
        )
    } catch (error: BleGattInspectionException) {
        throw OmiCv1ApplicationUpdateException(
            code = when (error.code) {
                BleGattInspectionFailureCode.PERMISSION_DENIED ->
                    OmiCv1ApplicationUpdateFailureCode.PERMISSION_DENIED

                BleGattInspectionFailureCode.BLUETOOTH_UNAVAILABLE ->
                    OmiCv1ApplicationUpdateFailureCode.BLUETOOTH_UNAVAILABLE

                BleGattInspectionFailureCode.ENDPOINT_EXPIRED ->
                    OmiCv1ApplicationUpdateFailureCode.ENDPOINT_EXPIRED

                BleGattInspectionFailureCode.CONNECTION_FAILED ->
                    OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED
            },
            message = error.message ?: "Read-only device preflight failed",
            cause = error,
        )
    }
}
