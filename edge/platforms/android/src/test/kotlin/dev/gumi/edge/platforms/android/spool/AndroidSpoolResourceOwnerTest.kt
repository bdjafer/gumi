package dev.gumi.edge.platforms.android.spool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidSpoolResourceOwnerTest {
    @Test
    fun `successful partial ownership cleanup closes in reverse resource order exactly once`() {
        val order = mutableListOf<String>()
        val owner = AndroidSpoolResourceOwner { order += "ownership" }
        owner.attachPayload { order += "payload" }
        owner.attachMetadata { order += "metadata" }

        owner.close()
        owner.close()

        assertEquals(listOf("payload", "metadata", "ownership"), order)
    }

    @Test
    fun `close failure is sticky while every later cleanup boundary still runs`() {
        val order = mutableListOf<String>()
        val owner = AndroidSpoolResourceOwner {
            order += "ownership"
            error("synthetic ownership release failure")
        }
        owner.attachPayload { order += "payload" }
        owner.attachMetadata {
            order += "metadata"
            error("synthetic metadata close failure")
        }

        val first = assertFailsWith<AndroidSpoolCloseException> { owner.close() }
        val replay = assertFailsWith<AndroidSpoolCloseException> { owner.close() }

        assertEquals("ANDROID_SPOOL_DATABASE_CLOSE_FAILED", first.failureCode)
        assertEquals(first.failureCode, replay.failureCode)
        assertEquals(listOf("payload", "metadata", "ownership"), order)
    }

    @Test
    fun `ownership acquired before later construction is still represented by a close handle`() {
        var ownershipCloseCount = 0
        val owner = AndroidSpoolResourceOwner { ownershipCloseCount += 1 }

        owner.close()
        owner.close()

        assertEquals(1, ownershipCloseCount)
    }
}
