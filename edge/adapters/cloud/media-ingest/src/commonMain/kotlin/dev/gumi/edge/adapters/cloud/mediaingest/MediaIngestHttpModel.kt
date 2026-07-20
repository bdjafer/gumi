package dev.gumi.edge.adapters.cloud.mediaingest

import dev.gumi.edge.runtime.ingest.PreparedMediaChunkUpload
import dev.gumi.edge.runtime.spool.ChunkDescriptor
import dev.gumi.edge.runtime.spool.IngestSessionId
import dev.gumi.edge.runtime.spool.Sha256Digest
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.encodeUtf8

class CaptureScopedIngestCredential private constructor(
    private val value: String,
) {
    init {
        require(value.matches(Regex("[\\x21-\\x7e]{1,8192}")) &&
            value.none { it == '\"' || it == ',' || it == ';' }
        ) { "Ingest bearer is outside the media-ingest visible-ASCII contract" }
    }

    internal fun authorizationHeader(): String = "Bearer $value"

    override fun toString(): String = "CaptureScopedIngestCredential([redacted])"

    companion object {
        /** Call only at a trusted credential-store boundary. */
        fun copyOf(value: String): CaptureScopedIngestCredential =
            CaptureScopedIngestCredential(value)
    }
}

data class AuthorizedMediaIngestRequest(
    val credential: CaptureScopedIngestCredential,
    /** UUIDv7 propagated as X-Correlation-ID; never generated from wall-clock time by this adapter. */
    val correlationId: CorrelationId,
)

sealed interface MediaIngestAuthorizationResult {
    data class Authorized(val request: AuthorizedMediaIngestRequest) :
        MediaIngestAuthorizationResult

    data class Unavailable(val failure: ExpectedFailure) : MediaIngestAuthorizationResult
}

fun interface MediaIngestAuthorizationPort {
    suspend fun authorize(
        ingestSessionId: IngestSessionId,
    ): MediaIngestAuthorizationResult
}

data class MediaIngestHttpEndpoint(
    val baseUrl: HttpUrl,
    /** Narrow escape hatch for an in-process loopback test server only. */
    val allowCleartextLoopback: Boolean = false,
) {
    init {
        require(baseUrl.username.isEmpty() && baseUrl.password.isEmpty()) {
            "Media-ingest endpoint URL cannot contain credentials"
        }
        require(baseUrl.query == null && baseUrl.fragment == null) {
            "Media-ingest endpoint URL cannot contain query or fragment state"
        }
        require(baseUrl.encodedPath == "/") {
            "Media-ingest endpoint URL must name the publisher root"
        }
        require(
            baseUrl.isHttps || allowCleartextLoopback && baseUrl.host in LOOPBACK_HOSTS,
        ) { "Media-ingest requires HTTPS except for an explicitly enabled loopback test endpoint" }
    }

    companion object {
        fun parse(
            value: String,
            allowCleartextLoopback: Boolean = false,
        ): MediaIngestHttpEndpoint = MediaIngestHttpEndpoint(
            baseUrl = value.toHttpUrl(),
            allowCleartextLoopback = allowCleartextLoopback,
        )
    }
}

internal data class CanonicalChunkDescriptor(
    val json: String,
    val digest: Sha256Digest,
)

/** Independent consumer mapping for the publisher-owned gumi.media-ingest.chunk.v1 shape. */
internal object MediaIngestV1DescriptorMapper {
    fun canonical(
        ingestSessionId: IngestSessionId,
        descriptor: ChunkDescriptor,
    ): CanonicalChunkDescriptor {
        val discontinuity = descriptor.sourceDiscontinuityBefore?.let {
            ",\"sourceDiscontinuityBefore\":{" +
                "\"droppedFrameCount\":\"${it.droppedFrameCount}\"," +
                "\"reason\":\"${it.reason.wireValue}\"}"
        }.orEmpty()
        val edgeReceivedAt = descriptor.edgeReceivedAt?.let {
            ",\"edgeReceivedAt\":\"${it.value}\""
        }.orEmpty()
        // Keys are emitted in RFC 8785 lexicographic order. Every interpolated string is already
        // constrained to the publisher's ASCII UUID/digest/codec/timestamp vocabularies.
        val json = "{" +
            "\"chunkId\":\"${descriptor.chunkId.value}\"," +
            "\"codecConfigurationId\":\"${descriptor.codecConfigurationId.value}\"," +
            "\"contentDigest\":\"${descriptor.contentDigest.value}\"" +
            edgeReceivedAt +
            ",\"ingestSessionId\":\"${ingestSessionId.value}\"," +
            "\"payloadBytes\":\"${descriptor.payloadBytes}\"," +
            "\"payloadFormat\":\"${descriptor.payloadFormat.wireValue}\"," +
            "\"schemaVersion\":\"gumi.media-ingest.chunk.v1\"," +
            "\"sequenceRange\":{" +
            "\"first\":\"${descriptor.sequenceRange.first}\"," +
            "\"last\":\"${descriptor.sequenceRange.last}\"}" +
            discontinuity +
            ",\"sourceRetransmission\":${descriptor.sourceRetransmission}," +
            "\"sourceStartedAt\":\"${descriptor.sourceStartedAt.value}\"," +
            "\"streamId\":\"${descriptor.streamId.value}\"}"
        return CanonicalChunkDescriptor(
            json = json,
            digest = Sha256Digest("sha256:${json.encodeUtf8().sha256().hex()}"),
        )
    }

    fun prepared(
        ingestSessionId: IngestSessionId,
        descriptor: ChunkDescriptor,
    ): PreparedMediaChunkUpload {
        val canonical = canonical(ingestSessionId, descriptor)
        return PreparedMediaChunkUpload(
            ingestSessionId = ingestSessionId,
            descriptor = descriptor,
            canonicalDescriptorDigest = canonical.digest,
        )
    }
}

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
