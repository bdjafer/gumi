package dev.gumi.edge.platforms.android.ble

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import io.runtime.mcumgr.McuMgrCallback
import io.runtime.mcumgr.response.img.McuMgrImageStateResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AndroidMcuMgrImageStateInspectorTest {
    @Test
    fun `maps an offline Nordic response without losing image identity or state`() {
        val response = McuMgrImageStateResponse().apply {
            splitStatus = McuMgrImageStateResponse.SPLIT_STATUS_MATCHING
            images = arrayOf(
                McuMgrImageStateResponse.ImageSlot().apply {
                    image = 1
                    slot = 0
                    version = "0.0.0+0"
                    hash = byteArrayOf(0x00, 0x7f, 0xa5.toByte(), 0xff.toByte())
                    bootable = true
                    pending = false
                    confirmed = true
                    active = true
                    permanent = true
                    compressed = false
                },
            )
        }

        val inspection = response.toInspection(
            EndpointCandidate(TransportKind.BLE, "ephemeral-test-endpoint"),
        )
        val slot = inspection.slots.single()

        assertEquals("mcumgr-smp", inspection.protocol)
        assertEquals(McuMgrImageStateResponse.SPLIT_STATUS_MATCHING, inspection.splitStatus)
        assertEquals(1, slot.imageNumber)
        assertEquals(0, slot.slotNumber)
        assertEquals("0.0.0+0", slot.version)
        assertEquals("007fa5ff", slot.hash?.hex)
        assertTrue(slot.bootable)
        assertFalse(slot.pending)
        assertTrue(slot.confirmed)
        assertTrue(slot.active)
        assertTrue(slot.permanent)
        assertFalse(slot.compressed)
    }

    @Test
    fun `cancellation releases a pending transport exactly once`() = runBlocking {
        var callback: McuMgrCallback<McuMgrImageStateResponse>? = null
        val releaseCount = AtomicInteger(0)
        val releaseTransport = RunOnce { releaseCount.incrementAndGet() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitMcuMgrImageState(
                start = { callback = it },
                onCancellation = releaseTransport::run,
            )
        }

        assertNotNull(callback)
        assertFalse(job.isCompleted)
        job.cancelAndJoin()
        releaseTransport.run() // Mirrors the adapter's finally block after cancellation.

        assertEquals(1, releaseCount.get())
    }
}
