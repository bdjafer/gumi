package dev.gumi.edge.sdk.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BleGattInspectorTest {
    @Test
    fun gattValuesCopyInputAndNeverRenderBytes() {
        val input = byteArrayOf(0x41, 0x42, 0x43)
        val value = BleGattValue.copyOf(input)
        input[0] = 0

        assertContentEquals(byteArrayOf(0x41, 0x42, 0x43), value.copyBytes())
        assertEquals("BleGattValue([redacted], size=3)", value.toString())
        assertFalse(value.toString().contains("ABC"))
    }

    @Test
    fun gattTargetsRequireCanonicalUuids() {
        assertFailsWith<IllegalArgumentException> {
            BleGattReadTarget("180a", "2a29")
        }
    }
}
