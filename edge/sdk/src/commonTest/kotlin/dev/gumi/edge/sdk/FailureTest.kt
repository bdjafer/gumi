package dev.gumi.edge.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FailureTest {
    @Test
    fun `strong operational identities reject ambiguous values`() {
        assertEquals("device-1", DeviceId("device-1").value)
        assertEquals("command-1", CommandId("command-1").value)
        assertEquals("correlation-1", CorrelationId("correlation-1").value)
        assertFailsWith<IllegalArgumentException> { DeviceId(" device-1") }
        assertFailsWith<IllegalArgumentException> { CommandId("\n") }
    }

    @Test
    fun `failure codes are stable machine identifiers`() {
        assertEquals("BLE_TIMEOUT", FailureCode("BLE_TIMEOUT").value)
        assertFailsWith<IllegalArgumentException> { FailureCode("ble timeout") }
    }
}
