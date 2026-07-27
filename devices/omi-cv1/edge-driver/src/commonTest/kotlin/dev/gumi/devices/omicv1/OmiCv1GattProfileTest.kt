package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.ble.BleBondState
import dev.gumi.edge.sdk.ble.BleGattInspection
import dev.gumi.edge.sdk.ble.BleGattReadResult
import dev.gumi.edge.sdk.ble.BleGattReadTarget
import dev.gumi.edge.sdk.ble.BleGattValue
import dev.gumi.edge.sdk.ble.BleLinkSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class OmiCv1GattProfileTest {
    @Test
    fun decodesTheAllowlistedStockEvidence() {
        val reads = listOf(
            success(
                OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
                OmiCv1GattProfile.MANUFACTURER_NAME,
                "Based Hardware".encodeToByteArray(),
            ),
            success(
                OmiCv1GattProfile.BATTERY_SERVICE,
                OmiCv1GattProfile.BATTERY_LEVEL,
                byteArrayOf(73),
            ),
            success(
                OmiCv1GattProfile.STORAGE_SERVICE,
                OmiCv1GattProfile.STORAGE_STATUS,
                byteArrayOf(
                    0x44, 0x33, 0x22, 0x11,
                    0x04, 0x00, 0x00, 0x00,
                    0x08, 0x07, 0x06, 0x05,
                    0x01, 0x00, 0x00, 0x00,
                ),
            ),
            success(
                OmiCv1GattProfile.RECOVERY_SERVICE,
                OmiCv1GattProfile.RECOVERY_STATUS,
                byteArrayOf(0x01, 0x07, 0x01, 0x23),
            ),
        )
        val evidence = OmiCv1GattProfile.decode(inspection(reads))

        assertEquals("Based Hardware", evidence.manufacturer)
        assertEquals(73, evidence.batteryPercent)
        val storage = evidence.storage as OmiCv1StorageEvidence.V3020Status
        assertEquals(0x11223344u, storage.usedBytes)
        assertEquals(4u, storage.unreadPackets)
        assertEquals(0x05060708u, storage.freeBytes)
        assertEquals(true, storage.rtcValid)
        assertEquals("44332211040000000807060501000000", evidence.storageRawHex)
        assertEquals("01070123", evidence.recoveryStatusRawHex)
    }

    @Test
    fun decodesTheEightByteV3012StorageShape() {
        val evidence = OmiCv1GattProfile.decode(
            inspection(
                listOf(
                    success(
                        OmiCv1GattProfile.STORAGE_SERVICE,
                        OmiCv1GattProfile.STORAGE_STATUS,
                        byteArrayOf(0x34, 0x12, 0x00, 0x00, 0x78, 0x56, 0x00, 0x00),
                    ),
                ),
            ),
        )

        val storage = evidence.storage as OmiCv1StorageEvidence.LegacyV3012FileSizes
        assertEquals(0x1234u, storage.firstFileBytes)
        assertEquals(0x5678u, storage.secondFileBytes)
        assertEquals(8, storage.payloadSize)
    }

    private fun success(
        service: String,
        characteristic: String,
        bytes: ByteArray,
    ) = BleGattReadResult.Success(
        BleGattReadTarget(service, characteristic),
        BleGattValue.copyOf(bytes),
    )

    private fun inspection(reads: List<BleGattReadResult>) = BleGattInspection(
        endpoint = EndpointCandidate(TransportKind.BLE, "ble:test"),
        services = emptyList(),
        reads = reads,
        link = BleLinkSnapshot(
            mtu = 23,
            txPhy = null,
            rxPhy = null,
            bondState = BleBondState.NOT_BONDED,
        ),
    )
}
