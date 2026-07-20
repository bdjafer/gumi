package dev.gumi.edge.platforms.android.spool

import dev.gumi.edge.runtime.spool.ChunkId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SpoolStateBinaryCodecTest {
    @Test
    fun `rich spool state round trips without losing recovery or upload fences`() {
        val state = richState()

        val decoded = SpoolStateBinaryCodec.decode(SpoolStateBinaryCodec.encode(state))

        assertEquals(state, decoded)
        assertEquals(2uL, decoded.captures.getValue(TEST_CAPTURE)
            .streams.getValue(TEST_STREAM).sourceDurableThrough)
    }

    @Test
    fun `encoding is canonical across map insertion order`() {
        val state = richState()
        val capture = state.captures.getValue(TEST_CAPTURE)
        val stream = capture.streams.getValue(TEST_STREAM)
        val reversedChunks = stream.chunks.entries.reversed().associate { it.toPair() }
        val reordered = state.copy(
            captures = mapOf(
                TEST_CAPTURE to capture.copy(
                    streams = mapOf(TEST_STREAM to stream.copy(chunks = reversedChunks)),
                ),
            ),
        )

        assertContentEquals(
            SpoolStateBinaryCodec.encode(state),
            SpoolStateBinaryCodec.encode(reordered),
        )
    }

    @Test
    fun `decoder rejects truncation trailing bytes and version drift`() {
        val encoded = SpoolStateBinaryCodec.encode(richState())

        assertEquals(
            "ANDROID_SPOOL_METADATA_TRUNCATED",
            assertFailsWith<SpoolCodecException> {
                SpoolStateBinaryCodec.decode(encoded.copyOf(encoded.size - 1))
            }.failureCode,
        )
        assertEquals(
            "ANDROID_SPOOL_METADATA_INVALID",
            assertFailsWith<SpoolCodecException> {
                SpoolStateBinaryCodec.decode(encoded + 0x01)
            }.failureCode,
        )
        val futureVersion = encoded.copyOf().apply { this[7] = 2 }
        assertEquals(
            "ANDROID_SPOOL_METADATA_INVALID",
            assertFailsWith<SpoolCodecException> {
                SpoolStateBinaryCodec.decode(futureVersion)
            }.failureCode,
        )
    }

    @Test
    fun `chunk associated data binds every descriptor identity`() {
        val payload = byteArrayOf(9, 8, 7)
        val descriptor = testDescriptor(payload)
        val canonical = SpoolStateBinaryCodec.encodeChunkDescriptor(descriptor)

        val anotherChunk = descriptor.copy(chunkId = ChunkId(testUuid(99)))

        assertNotEquals(
            canonical.toList(),
            SpoolStateBinaryCodec.encodeChunkDescriptor(anotherChunk).toList(),
        )
    }
}
