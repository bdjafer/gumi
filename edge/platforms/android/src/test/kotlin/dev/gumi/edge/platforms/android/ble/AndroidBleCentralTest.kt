package dev.gumi.edge.platforms.android.ble

import dev.gumi.edge.sdk.ble.BleCharacteristicTarget
import dev.gumi.edge.sdk.ble.BleSessionException
import dev.gumi.edge.sdk.ble.BleSessionFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import no.nordicsemi.android.ble.callback.FailCallback

class AndroidBleCentralTest {
    @Test
    fun `session close is idempotent and takes precedence over disconnect`() {
        val lifecycle = BleSessionLifecycle()

        assertTrue(lifecycle.close())
        assertFalse(lifecycle.close())
        assertFalse(lifecycle.disconnect())

        val failure = assertFailsWith<BleSessionException> { lifecycle.ensureOperational() }
        assertEquals(BleSessionFailureCode.CLOSED, failure.code)
    }

    @Test
    fun `unexpected disconnect permanently rejects operations`() {
        val lifecycle = BleSessionLifecycle()

        assertTrue(lifecycle.disconnect())
        assertFalse(lifecycle.disconnect())

        val failure = assertFailsWith<BleSessionException> { lifecycle.ensureOperational() }
        assertEquals(BleSessionFailureCode.DISCONNECTED, failure.code)
    }

    @Test
    fun `event overflow is terminal until explicit close takes precedence`() {
        val lifecycle = BleSessionLifecycle()

        assertTrue(lifecycle.failEventOverflow())
        assertFalse(lifecycle.failEventOverflow())
        assertFalse(lifecycle.disconnect())
        assertEquals(
            BleSessionFailureCode.EVENT_OVERFLOW,
            assertFailsWith<BleSessionException> { lifecycle.ensureOperational() }.code,
        )

        assertTrue(lifecycle.close())
        assertEquals(
            BleSessionFailureCode.CLOSED,
            assertFailsWith<BleSessionException> { lifecycle.ensureOperational() }.code,
        )
    }

    @Test
    fun `Nordic timeout disconnect and unsupported statuses have stable mappings`() {
        assertEquals(
            BleSessionFailureCode.TIMEOUT,
            mapNordicFailureStatus(FailCallback.REASON_TIMEOUT),
        )
        assertEquals(
            BleSessionFailureCode.DISCONNECTED,
            mapNordicFailureStatus(FailCallback.REASON_DEVICE_DISCONNECTED),
        )
        assertEquals(
            BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
            mapNordicFailureStatus(FailCallback.REASON_UNSUPPORTED_CONFIGURATION),
        )
        assertEquals(
            BleSessionFailureCode.OPERATION_FAILED,
            mapNordicFailureStatus(133),
        )
    }

    @Test
    fun `notification sink is bounded redacted and preserves receive ordinal`() = runBlocking {
        val target = BleCharacteristicTarget(
            serviceUuid = "19b10000-e8f2-537e-4f6c-d104768a1214",
            characteristicUuid = "19b10001-e8f2-537e-4f6c-d104768a1214",
        )
        val sink = BoundedNotificationSink(target, capacity = 1, monotonicMillis = { 42L })

        assertEquals(
            NotificationOfferResult.ACCEPTED,
            sink.offer(byteArrayOf(0x01, 0x02)),
        )
        assertEquals(NotificationOfferResult.BUFFER_FULL, sink.offer(byteArrayOf(0x03)))

        val notification = sink.flow.first()
        assertEquals(1uL, notification.ordinal)
        assertEquals(42L, notification.receivedAtMonotonicMillis)
        assertEquals("OpaqueBytes([redacted], size=2)", notification.value.toString())
        assertTrue(notification.value.copyBytes().contentEquals(byteArrayOf(0x01, 0x02)))
        sink.close()
    }

    @Test
    fun `notification arriving during intentional shutdown is not reported as buffer full`() {
        val target = BleCharacteristicTarget(
            serviceUuid = "19b10000-e8f2-537e-4f6c-d104768a1214",
            characteristicUuid = "19b10001-e8f2-537e-4f6c-d104768a1214",
        )
        val sink = BoundedNotificationSink(target, capacity = 1, monotonicMillis = { 42L })

        sink.stopAccepting()

        assertEquals(NotificationOfferResult.CLOSED, sink.offer(byteArrayOf(0x01)))
        sink.close()
    }
}
