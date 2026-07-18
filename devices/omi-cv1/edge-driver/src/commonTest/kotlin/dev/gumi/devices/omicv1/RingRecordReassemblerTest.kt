package dev.gumi.devices.omicv1

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RingRecordReassemblerTest {
    @Test
    fun `partial record survives drain and buffer compaction`() {
        val reassembler = RingRecordReassembler()
        reassembler.append(ByteArray(RingProtocol.RECORD_SIZE) { 0x11 })
        reassembler.append(ByteArray(10) { 0x22 })

        assertContentEquals(
            ByteArray(RingProtocol.RECORD_SIZE) { 0x11 },
            reassembler.drainRecords().single(),
        )
        assertEquals(10, reassembler.pendingBytes)

        reassembler.append(ByteArray(RingProtocol.RECORD_SIZE - 10) { 0x22 })

        assertContentEquals(
            ByteArray(RingProtocol.RECORD_SIZE) { 0x22 },
            reassembler.drainRecords().single(),
        )
        assertEquals(0, reassembler.pendingBytes)
    }

    @Test
    fun `one large notification expands safely and yields every complete record`() {
        val reassembler = RingRecordReassembler()
        reassembler.append(ByteArray(RingProtocol.RECORD_SIZE * 3 + 7) { index -> index.toByte() })

        val records = reassembler.drainRecords()

        assertEquals(3, records.size)
        assertEquals(7, reassembler.pendingBytes)
    }
}
