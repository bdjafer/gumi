package dev.gumi.edge.adapters.cloud.mediaingest

import dev.gumi.edge.runtime.ingest.PreparedMediaChunkUpload
import dev.gumi.edge.runtime.spool.CaptureSessionId
import dev.gumi.edge.runtime.spool.ChunkDescriptor
import dev.gumi.edge.runtime.spool.ChunkId
import dev.gumi.edge.runtime.spool.CodecConfigurationId
import dev.gumi.edge.runtime.spool.IngestSessionId
import dev.gumi.edge.runtime.spool.MediaPayloadFormat
import dev.gumi.edge.runtime.spool.SequenceRange
import dev.gumi.edge.runtime.spool.Sha256Digest
import dev.gumi.edge.runtime.spool.SourceTimestamp
import dev.gumi.edge.runtime.spool.StreamId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.OpaqueBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaIngestHttpModelTest {
    @Test
    fun canonicalDescriptorMatchesThePublisherOwnedGoldenFixture() {
        val mapped = MediaIngestV1DescriptorMapper.canonical(
            MediaIngestHttpTestFixture.ingestSessionId,
            MediaIngestHttpTestFixture.descriptor,
        )

        assertEquals(MediaIngestHttpTestFixture.canonicalDescriptorJson, mapped.json)
        assertEquals(MediaIngestHttpTestFixture.descriptorDigest, mapped.digest)
    }

    @Test
    fun endpointPolicyRejectsAmbientStateAndNonLoopbackCleartext() {
        assertFailsWith<IllegalArgumentException> {
            MediaIngestHttpEndpoint.parse("http://ingest.example.test/")
        }
        assertFailsWith<IllegalArgumentException> {
            MediaIngestHttpEndpoint.parse(
                "http://ingest.example.test/",
                allowCleartextLoopback = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MediaIngestHttpEndpoint.parse("https://user:secret@ingest.example.test/")
        }
        assertFailsWith<IllegalArgumentException> {
            MediaIngestHttpEndpoint.parse("https://ingest.example.test/?tenant=ambient")
        }
        assertFailsWith<IllegalArgumentException> {
            MediaIngestHttpEndpoint.parse("https://ingest.example.test/#fragment")
        }
        assertFailsWith<IllegalArgumentException> {
            MediaIngestHttpEndpoint.parse("https://ingest.example.test/base/")
        }

        assertEquals(
            "127.0.0.1",
            MediaIngestHttpEndpoint.parse(
                "http://127.0.0.1:8080/",
                allowCleartextLoopback = true,
            ).baseUrl.host,
        )
        assertEquals(
            "https",
            MediaIngestHttpEndpoint.parse("https://ingest.example.test/").baseUrl.scheme,
        )
    }

    @Test
    fun credentialIsBoundedAndNeverRendersItsSecret() {
        val secret = "gumi-ingest-secret"
        val credential = CaptureScopedIngestCredential.copyOf(secret)

        assertEquals("CaptureScopedIngestCredential([redacted])", credential.toString())
        assertFailsWith<IllegalArgumentException> {
            CaptureScopedIngestCredential.copyOf(" $secret")
        }
        assertFailsWith<IllegalArgumentException> {
            CaptureScopedIngestCredential.copyOf("secret\nsecond-header")
        }
        assertFailsWith<IllegalArgumentException> {
            CaptureScopedIngestCredential.copyOf("secret with space")
        }
        assertFailsWith<IllegalArgumentException> {
            CaptureScopedIngestCredential.copyOf("secret,second")
        }
        assertFailsWith<IllegalArgumentException> {
            CaptureScopedIngestCredential.copyOf("sëcret")
        }
    }
}

internal object MediaIngestHttpTestFixture {
    val ingestSessionId = IngestSessionId("0190c6f0-7b21-7a40-8b11-000000000002")
    val streamId = StreamId("0190c6f0-7b21-7a40-8b11-000000000006")
    val chunkId = ChunkId("0190c6f0-7b21-7a40-8b11-000000000007")
    val correlationId = CorrelationId("0190c6f0-7b21-7a40-8b11-00000000000b")
    const val requestId: String = "0190c6f0-7b21-7a40-8b11-00000000000c"
    val contentDigest = Sha256Digest(
        "sha256:d857c547c6ffb1d8f9963a2c6ead0420501a3519b5b6f8acf51434e479adcb83",
    )
    val descriptorDigest = Sha256Digest(
        "sha256:2e50c90ba004d1238eea28832e39478a2fff6112d7b8ef9c101ff888349ba56f",
    )
    val descriptor = ChunkDescriptor(
        captureSessionId = CaptureSessionId("0190c6f0-7b21-7a40-8b11-000000000001"),
        streamId = streamId,
        chunkId = chunkId,
        sequenceRange = SequenceRange(0uL, 1uL),
        payloadBytes = 168uL,
        payloadFormat = MediaPayloadFormat.OGG_OPUS_PAGE_FRAGMENT_V1,
        contentDigest = contentDigest,
        codecConfigurationId = CodecConfigurationId("opus-16000-mono-20ms-v1"),
        sourceStartedAt = SourceTimestamp("2026-07-19T20:00:01Z"),
        edgeReceivedAt = SourceTimestamp("2026-07-19T20:00:01.125Z"),
        sourceRetransmission = false,
    )
    val payload: ByteArray = (
        "4f67675300020000000000000000a99b80d300000000a880e7ad01134f707573486561640101380180" +
            "3e00000000004f67675300000000000000000000a99b80d301000000f82c605d01144f707573546167" +
            "730400000067756d69000000004f6767530000c003000000000000a99b80d3020000006b7ae25f0108" +
            "480be4c136ecc5804f67675300008007000000000000a99b80d3030000008ad8fb2501094807c97227" +
            "e144ea50"
        ).decodeHex()
    val opaquePayload: OpaqueBytes get() = OpaqueBytes.copyOf(payload)
    val prepared: PreparedMediaChunkUpload get() =
        MediaIngestV1DescriptorMapper.prepared(ingestSessionId, descriptor)

    const val canonicalDescriptorJson: String =
        "{\"chunkId\":\"0190c6f0-7b21-7a40-8b11-000000000007\"," +
            "\"codecConfigurationId\":\"opus-16000-mono-20ms-v1\"," +
            "\"contentDigest\":\"sha256:d857c547c6ffb1d8f9963a2c6ead0420501a3519b5b6f8acf51434e479adcb83\"," +
            "\"edgeReceivedAt\":\"2026-07-19T20:00:01.125Z\"," +
            "\"ingestSessionId\":\"0190c6f0-7b21-7a40-8b11-000000000002\"," +
            "\"payloadBytes\":\"168\"," +
            "\"payloadFormat\":\"ogg-opus-page-fragment-v1\"," +
            "\"schemaVersion\":\"gumi.media-ingest.chunk.v1\"," +
            "\"sequenceRange\":{\"first\":\"0\",\"last\":\"1\"}," +
            "\"sourceRetransmission\":false," +
            "\"sourceStartedAt\":\"2026-07-19T20:00:01Z\"," +
            "\"streamId\":\"0190c6f0-7b21-7a40-8b11-000000000006\"}"

    fun ackJson(
        disposition: String = "stored",
        acknowledgedChunkId: String = chunkId.value,
        descriptorDigest: String = this.descriptorDigest.value,
    ): String = """
        {
          "schemaVersion": "gumi.media-ingest.ack.v1",
          "ingestSessionId": "${ingestSessionId.value}",
          "streamId": "${streamId.value}",
          "acknowledgedChunkId": "$acknowledgedChunkId",
          "acknowledgedContentDigest": "${contentDigest.value}",
          "acknowledgedDescriptorDigest": "$descriptorDigest",
          "acknowledgedSequenceRange": { "first": "0", "last": "1" },
          "disposition": "$disposition",
          "committedRanges": [{ "first": "0", "last": "1" }],
          "missingRanges": [],
          "accountedRange": { "first": "0", "last": "1" },
          "durableThrough": "1",
          "stateRevision": "1",
          "sessionState": "open",
          "acknowledgedAt": "2026-07-19T20:00:02Z"
        }
    """.trimIndent()
}

private fun String.decodeHex(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
