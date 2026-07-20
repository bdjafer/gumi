package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.capability.audio.AudioStreamException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OmiLiveAudioEnvelopeTest {
    @Test
    fun `strips the stock envelope and expands the device sequence`() {
        val decoder = OmiLiveAudioEnvelopeDecoder()

        val first = decoder.decode(1uL, envelope(0x1234u, 0u, 0x78, 0x11))
        val second = decoder.decode(2uL, envelope(0x1235u, 0u, 0x78, 0x22))

        assertEquals(0x1234uL, first.sequence)
        assertContentEquals(byteArrayOf(0x78, 0x11), first.payload)
        assertFalse(first.discontinuityBefore)
        assertEquals(0x1235uL, second.sequence)
        assertContentEquals(byteArrayOf(0x78, 0x22), second.payload)
        assertFalse(second.discontinuityBefore)
    }

    @Test
    fun `u16 source sequence rollover stays monotonic`() {
        val decoder = OmiLiveAudioEnvelopeDecoder()

        assertEquals(65_535uL, decoder.decode(9uL, envelope(0xffffu, 0u, 0x78)).sequence)
        val wrapped = decoder.decode(10uL, envelope(0u, 0u, 0x78))

        assertEquals(65_536uL, wrapped.sequence)
        assertFalse(wrapped.discontinuityBefore)
    }

    @Test
    fun `device or host gap marks a discontinuity`() {
        val sourceGap = OmiLiveAudioEnvelopeDecoder().run {
            decode(1uL, envelope(7u, 0u, 0x78))
            decode(2uL, envelope(9u, 0u, 0x78))
        }
        val hostGap = OmiLiveAudioEnvelopeDecoder().run {
            decode(1uL, envelope(7u, 0u, 0x78))
            decode(3uL, envelope(8u, 0u, 0x78))
        }

        assertTrue(sourceGap.discontinuityBefore)
        assertTrue(hostGap.discontinuityBefore)
    }

    @Test
    fun `fragmented empty and oversized envelopes fail with stable redacted codes`() {
        assertFailure("OMI_AUDIO_FRAGMENTATION_UNSUPPORTED") {
            OmiLiveAudioEnvelopeDecoder().decode(1uL, envelope(1u, 1u, 0x78))
        }
        assertFailure("OMI_AUDIO_ENVELOPE_EMPTY") {
            OmiLiveAudioEnvelopeDecoder().decode(1uL, byteArrayOf(1, 0, 0))
        }
        assertFailure("OMI_AUDIO_PACKET_EXCEEDS_SOURCE_BOUND") {
            OmiLiveAudioEnvelopeDecoder().decode(
                1uL,
                envelope(1u, 0u, *ByteArray(OMI_STOCK_OPUS_MAX_PACKET_BYTES + 1)),
            )
        }
        assertEquals(166, OMI_STOCK_AUDIO_MINIMUM_UNFRAGMENTED_ATT_MTU)
    }

    private fun assertFailure(code: String, block: () -> Unit) {
        val error = assertFailsWith<AudioStreamException>(block = block)
        assertEquals(code, error.failure.code.value)
        assertTrue(error.failure.redactedEvidence.isEmpty())
    }
}

private fun envelope(
    sequence: UInt,
    fragmentIndex: UInt,
    vararg payload: Byte,
): ByteArray = byteArrayOf(
    (sequence and 0xffu).toByte(),
    ((sequence shr 8) and 0xffu).toByte(),
    fragmentIndex.toByte(),
    *payload,
)
