package dev.gumi.edge.sdk.firmware

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirmwareImageStateInspectorTest {
    @Test
    fun `hashes are canonical lowercase copies`() {
        val input = byteArrayOf(0x00, 0x7f, 0xa5.toByte(), 0xff.toByte())
        val hash = FirmwareImageHash.copyOf(input)
        input[0] = 0x44

        assertEquals("007fa5ff", hash.hex)
        assertFailsWith<IllegalArgumentException> { FirmwareImageHash("AABB") }
        assertFailsWith<IllegalArgumentException> { FirmwareImageHash("") }
    }

    @Test
    fun `semantic read disclosure makes BLE writes explicit`() {
        val disclosure = FirmwareImageStateReadDisclosure(
            protocol = "mcumgr-smp",
            requestedAttMtu = 23,
            writesRequestCharacteristic = true,
            writesNotificationDescriptor = true,
            protocolReads = listOf(
                FirmwareProtocolReadRequest(0, 6, "MCU Manager parameters"),
                FirmwareProtocolReadRequest(1, 0, "MCUboot image state"),
            ),
            persistentDeviceMutationExpected = false,
        )

        assertTrue(disclosure.writesRequestCharacteristic)
        assertTrue(disclosure.writesNotificationDescriptor)
        assertFalse(disclosure.persistentDeviceMutationExpected)
        assertEquals(listOf(0 to 6, 1 to 0), disclosure.protocolReads.map { it.groupId to it.commandId })
    }

    @Test
    fun `image state rejects duplicate coordinates`() {
        val slot = FirmwareImageSlot(
            imageNumber = 0,
            slotNumber = 0,
            version = "0.0.0+0",
            hash = FirmwareImageHash("aabb"),
            bootable = true,
            pending = false,
            confirmed = true,
            active = true,
            permanent = true,
            compressed = false,
        )

        assertFailsWith<IllegalArgumentException> {
            FirmwareImageStateInspection(
                endpoint = EndpointCandidate(TransportKind.BLE, "ephemeral"),
                protocol = "mcumgr-smp",
                slots = listOf(slot, slot.copy()),
                splitStatus = 0,
            )
        }
    }
}
