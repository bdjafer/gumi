package dev.gumi.edge.sdk.capability.audio

import dev.gumi.edge.sdk.OpaqueBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AudioInputTest {
    @Test
    fun `optional transport receipt time rejects negative clocks`() {
        assertFailsWith<IllegalArgumentException> {
            AudioFrame(
                sequence = 1uL,
                payload = OpaqueBytes.copyOf(byteArrayOf(0x01)),
                receivedAtMonotonicMillis = -1,
            )
        }
    }

    @Test
    fun `wire framing is explicit and codec compatible`() {
        val rawOpus = AudioFormat(
            codec = AudioCodec.OPUS,
            sampleRateHz = 16_000u,
            channels = 1u,
            payloadFraming = AudioPayloadFraming.RAW_OPUS_PACKET,
        )
        assertEquals(AudioPayloadFraming.RAW_OPUS_PACKET, rawOpus.payloadFraming)
        assertNotEquals(AudioPayloadFraming.OGG_OPUS_PAGE_FRAGMENT, rawOpus.payloadFraming)

        assertFailsWith<IllegalArgumentException> {
            AudioFormat(
                codec = AudioCodec.PCM_S16_LE,
                sampleRateHz = 16_000u,
                channels = 1u,
                payloadFraming = AudioPayloadFraming.RAW_OPUS_PACKET,
            )
        }
    }
}
