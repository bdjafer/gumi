package dev.gumi.edge.runtime.media.ogg

import dev.gumi.edge.sdk.OpaqueBytes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class MediaIngestConformanceFixtureTest {
    @Test
    fun `edge muxer remains byte-identical to the media-ingest conformance fixture`() {
        val muxer = DeterministicOggOpusMuxer(
            OggOpusStreamConfig(
                configurationId = "opus-16000-mono-20ms-v1",
                serialNumber = 0x0102_0304u,
                firstAudioSequence = 10uL,
                channelCount = 1u,
                inputSampleRateHz = 16_000u,
                preSkip48kSamples = 312u,
                expectedFrameDurationUs = 20_000u,
            ),
        )
        assertNull(
            accepted(
                muxer.accept(
                    SequencedRawOpusPacket(
                        sequence = 10uL,
                        payload = OpaqueBytes.copyOf(byteArrayOf(0x48, 0x11, 0x22)),
                    ),
                ),
            ),
        )
        val actual = accepted(muxer.finish()).bytes.copyBytes()
        val root = requireNotNull(System.getProperty("gumi.repositoryRoot"))
        val fixture = File(
            root,
            "cloud/apps/media-ingest/fixtures/v1/conformance/edge-muxer-single-packet.hex",
        ).readText().trim().hexBytes()

        assertContentEquals(fixture, actual)
    }
}

private fun <T> accepted(result: OggOpusMuxResult<T>): T = when (result) {
    is OggOpusMuxResult.Accepted -> result.value
    is OggOpusMuxResult.Rejected -> error("Expected accepted result, got ${result.failure}")
}

private fun String.hexBytes(): ByteArray {
    require(isNotEmpty() && length % 2 == 0 && all { it.isDigit() || it in 'a'..'f' })
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
