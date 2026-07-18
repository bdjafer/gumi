package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.ble.BleGattInspection
import dev.gumi.edge.sdk.ble.BleGattInspectionRequest
import dev.gumi.edge.sdk.ble.BleGattReadResult
import dev.gumi.edge.sdk.ble.BleGattReadTarget

object OmiCv1GattProfile {
    const val DEVICE_INFORMATION_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb"
    const val MANUFACTURER_NAME = "00002a29-0000-1000-8000-00805f9b34fb"
    const val MODEL_NUMBER = "00002a24-0000-1000-8000-00805f9b34fb"
    const val FIRMWARE_REVISION = "00002a26-0000-1000-8000-00805f9b34fb"
    const val HARDWARE_REVISION = "00002a27-0000-1000-8000-00805f9b34fb"
    const val SOFTWARE_REVISION = "00002a28-0000-1000-8000-00805f9b34fb"
    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"
    const val STORAGE_SERVICE = OmiCv1Protocol.OFFLINE_STORAGE_SERVICE_UUID
    const val STORAGE_STATUS = "30295782-4301-eabd-2904-2849adfeae43"

    val readOnlyInspectionRequest = BleGattInspectionRequest(
        reads = setOf(
            target(DEVICE_INFORMATION_SERVICE, MANUFACTURER_NAME),
            target(DEVICE_INFORMATION_SERVICE, MODEL_NUMBER),
            target(DEVICE_INFORMATION_SERVICE, FIRMWARE_REVISION),
            target(DEVICE_INFORMATION_SERVICE, HARDWARE_REVISION),
            target(DEVICE_INFORMATION_SERVICE, SOFTWARE_REVISION),
            target(BATTERY_SERVICE, BATTERY_LEVEL),
            target(STORAGE_SERVICE, STORAGE_STATUS),
        ),
    )

    fun decode(inspection: BleGattInspection): OmiCv1GattEvidence {
        val reads = inspection.reads.associateBy(BleGattReadResult::target)
        val storageBytes = reads.bytes(STORAGE_SERVICE, STORAGE_STATUS)
        return OmiCv1GattEvidence(
            manufacturer = reads.text(DEVICE_INFORMATION_SERVICE, MANUFACTURER_NAME),
            modelNumber = reads.text(DEVICE_INFORMATION_SERVICE, MODEL_NUMBER),
            firmwareRevision = reads.text(DEVICE_INFORMATION_SERVICE, FIRMWARE_REVISION),
            hardwareRevision = reads.text(DEVICE_INFORMATION_SERVICE, HARDWARE_REVISION),
            softwareRevision = reads.text(DEVICE_INFORMATION_SERVICE, SOFTWARE_REVISION),
            batteryPercent = reads.bytes(BATTERY_SERVICE, BATTERY_LEVEL)
                ?.firstOrNull()
                ?.toUByte()
                ?.toInt(),
            storage = storageBytes?.decodeStorageEvidence(),
            storageRawHex = storageBytes?.toHex(),
            successfulReadLengths = inspection.reads
                .filterIsInstance<BleGattReadResult.Success>()
                .associate { it.target to it.value.size },
            readFailures = inspection.reads.filterIsInstance<BleGattReadResult.Failure>(),
        )
    }

    private fun target(service: String, characteristic: String) =
        BleGattReadTarget(service, characteristic)
}

data class OmiCv1GattEvidence(
    val manufacturer: String?,
    val modelNumber: String?,
    val firmwareRevision: String?,
    val hardwareRevision: String?,
    val softwareRevision: String?,
    val batteryPercent: Int?,
    val storage: OmiCv1StorageEvidence?,
    val storageRawHex: String?,
    val successfulReadLengths: Map<BleGattReadTarget, Int>,
    val readFailures: List<BleGattReadResult.Failure>,
)

sealed interface OmiCv1StorageEvidence {
    val payloadSize: Int

    data class LegacyV3012FileSizes(
        val firstFileBytes: UInt,
        val secondFileBytes: UInt,
    ) : OmiCv1StorageEvidence {
        override val payloadSize: Int = LEGACY_STORAGE_STATUS_SIZE
    }

    data class V3020Status(
        val usedBytes: UInt,
        val unreadPackets: UInt,
        val freeBytes: UInt,
        val rtcValid: Boolean,
    ) : OmiCv1StorageEvidence {
        override val payloadSize: Int = CURRENT_STORAGE_STATUS_SIZE
    }

    data class Unknown(override val payloadSize: Int) : OmiCv1StorageEvidence
}

private fun Map<BleGattReadTarget, BleGattReadResult>.bytes(
    service: String,
    characteristic: String,
): ByteArray? = (get(BleGattReadTarget(service, characteristic)) as? BleGattReadResult.Success)
    ?.value
    ?.copyBytes()

private fun Map<BleGattReadTarget, BleGattReadResult>.text(
    service: String,
    characteristic: String,
): String? = bytes(service, characteristic)
    ?.decodeToString()
    ?.trimEnd('\u0000')
    ?.takeIf(String::isNotBlank)

private fun ByteArray.decodeStorageEvidence(): OmiCv1StorageEvidence = when (size) {
    LEGACY_STORAGE_STATUS_SIZE -> OmiCv1StorageEvidence.LegacyV3012FileSizes(
        firstFileBytes = littleEndianUInt(0),
        secondFileBytes = littleEndianUInt(4),
    )

    CURRENT_STORAGE_STATUS_SIZE -> OmiCv1StorageEvidence.V3020Status(
        usedBytes = littleEndianUInt(0),
        unreadPackets = littleEndianUInt(4),
        freeBytes = littleEndianUInt(8),
        rtcValid = littleEndianUInt(12) != 0u,
    )

    else -> OmiCv1StorageEvidence.Unknown(size)
}

private fun ByteArray.littleEndianUInt(offset: Int): UInt =
    this[offset].toUByte().toUInt() or
        (this[offset + 1].toUByte().toUInt() shl 8) or
        (this[offset + 2].toUByte().toUInt() shl 16) or
        (this[offset + 3].toUByte().toUInt() shl 24)

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    byte.toUByte().toString(radix = 16).padStart(2, '0')
}

private const val LEGACY_STORAGE_STATUS_SIZE = 8
private const val CURRENT_STORAGE_STATUS_SIZE = 16
