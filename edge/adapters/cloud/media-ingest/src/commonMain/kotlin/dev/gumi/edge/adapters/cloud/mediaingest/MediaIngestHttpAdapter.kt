package dev.gumi.edge.adapters.cloud.mediaingest

import dev.gumi.edge.runtime.ingest.MediaIngestPort
import dev.gumi.edge.runtime.ingest.MediaIngestPreparationResult
import dev.gumi.edge.runtime.ingest.MediaIngestUploadResult
import dev.gumi.edge.runtime.ingest.PreparedMediaChunkUpload
import dev.gumi.edge.runtime.spool.CloudAckDisposition
import dev.gumi.edge.runtime.spool.DurableCloudAck
import dev.gumi.edge.runtime.spool.SequenceRange
import dev.gumi.edge.runtime.spool.Sha256Digest
import dev.gumi.edge.runtime.spool.SourceTimestamp
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.OpaqueBytes
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.net.Proxy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MediaIngestHttpAdapter(
    private val endpoint: MediaIngestHttpEndpoint,
    private val authorization: MediaIngestAuthorizationPort,
    private val maximumRequestBytes: Long = DEFAULT_MAXIMUM_REQUEST_BYTES,
    private val maximumResponseBytes: Long = DEFAULT_MAXIMUM_RESPONSE_BYTES,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
) : MediaIngestPort {
    init {
        require(maximumRequestBytes in 1..PUBLISHER_HARD_MAXIMUM_REQUEST_BYTES) {
            "Media-ingest request bound is outside the publisher hard cap"
        }
        require(maximumResponseBytes in 1..MAXIMUM_CONFIGURABLE_RESPONSE_BYTES) {
            "Media-ingest response bound is outside the adapter policy"
        }
        require(requestTimeout > Duration.ZERO && requestTimeout <= MAXIMUM_REQUEST_TIMEOUT) {
            "Media-ingest request timeout is outside the adapter policy"
        }
    }

    // Capture credentials and media never pass through an application-global client. In particular,
    // this dedicated client cannot inherit interceptors, authenticators, cookies, event listeners,
    // redirects, or retry policy from another subsystem.
    private val client = DedicatedMediaIngestHttpTransport.build(requestTimeout)

    override fun prepareChunk(
        ingestSessionId: dev.gumi.edge.runtime.spool.IngestSessionId,
        descriptor: dev.gumi.edge.runtime.spool.ChunkDescriptor,
    ): MediaIngestPreparationResult = try {
        MediaIngestPreparationResult.Prepared(
            MediaIngestV1DescriptorMapper.prepared(ingestSessionId, descriptor),
        )
    } catch (_: Exception) {
        MediaIngestPreparationResult.Rejected(
            failure(
                category = FailureCategory.INCOMPATIBLE,
                code = "INGEST_DESCRIPTOR_MAPPING_REJECTED",
                retryable = false,
            ),
        )
    }

    override suspend fun uploadChunk(
        prepared: PreparedMediaChunkUpload,
        payload: OpaqueBytes,
    ): MediaIngestUploadResult {
        val sizeFailure = validateLocalRequestSize(prepared, payload.size)
        if (sizeFailure != null) return MediaIngestUploadResult.NotAttempted(sizeFailure)
        val payloadBytes = payload.copyBytes()
        val localFailure = validateLocalRequest(prepared, payloadBytes)
        if (localFailure != null) return MediaIngestUploadResult.NotAttempted(localFailure)

        val authorized = try {
            when (val result = authorization.authorize(prepared.ingestSessionId)) {
                is MediaIngestAuthorizationResult.Authorized -> result.request
                is MediaIngestAuthorizationResult.Unavailable -> {
                    return MediaIngestUploadResult.NotAttempted(result.failure)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MediaIngestUploadResult.NotAttempted(
                failure(
                    category = FailureCategory.UNAVAILABLE,
                    code = "INGEST_AUTHORIZATION_UNAVAILABLE",
                    retryable = false,
                ),
            )
        }
        if (!UUID_V7.matches(authorized.correlationId.value)) {
            return MediaIngestUploadResult.NotAttempted(
                failure(
                    category = FailureCategory.INCOMPATIBLE,
                    code = "INGEST_CORRELATION_ID_INVALID",
                    retryable = false,
                ),
            )
        }

        val request = buildRequest(prepared, payloadBytes, authorized)
        val response = try {
            client.newCall(request).executeAsync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MediaIngestUploadResult.OutcomeUnknown(
                failure(
                    category = FailureCategory.UNAVAILABLE,
                    code = "INGEST_TRANSPORT_OUTCOME_UNKNOWN",
                    retryable = false,
                    correlationId = authorized.correlationId,
                ),
            )
        }
        return try {
            response.use {
                mapResponse(it, prepared, authorized)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Receiving headers does not prove that the bounded response body was received. A
            // disconnect or decoder I/O failure while OkHttp lazily reads that body leaves the
            // publisher outcome ambiguous in exactly the same way as an earlier transport loss.
            MediaIngestUploadResult.OutcomeUnknown(
                failure(
                    category = FailureCategory.UNAVAILABLE,
                    code = "INGEST_RESPONSE_BODY_OUTCOME_UNKNOWN",
                    retryable = false,
                    correlationId = authorized.correlationId,
                ),
            )
        }
    }

    private fun validateLocalRequestSize(
        prepared: PreparedMediaChunkUpload,
        payloadSize: Int,
    ): ExpectedFailure? {
        val declared = prepared.descriptor.payloadBytes
        if (declared > maximumRequestBytes.toULong() || payloadSize.toLong() > maximumRequestBytes) {
            return failure(
                category = FailureCategory.RESOURCE_EXHAUSTED,
                code = "INGEST_LOCAL_PAYLOAD_TOO_LARGE",
                retryable = false,
            )
        }
        if (payloadSize.toULong() != declared) {
            return failure(
                category = FailureCategory.CORRUPT,
                code = "INGEST_LOCAL_PAYLOAD_LENGTH_MISMATCH",
                retryable = false,
            )
        }
        return null
    }

    private fun validateLocalRequest(
        prepared: PreparedMediaChunkUpload,
        payload: ByteArray,
    ): ExpectedFailure? {
        val descriptor = prepared.descriptor
        val actualPayloadDigest = Sha256Digest(
            "sha256:${payload.toByteString().sha256().hex()}",
        )
        if (actualPayloadDigest != descriptor.contentDigest) {
            return failure(
                category = FailureCategory.CORRUPT,
                code = "INGEST_LOCAL_PAYLOAD_DIGEST_MISMATCH",
                retryable = false,
            )
        }
        val remapped = try {
            MediaIngestV1DescriptorMapper.canonical(prepared.ingestSessionId, descriptor)
        } catch (_: Exception) {
            return failure(
                category = FailureCategory.INCOMPATIBLE,
                code = "INGEST_DESCRIPTOR_MAPPING_REJECTED",
                retryable = false,
            )
        }
        if (remapped.digest != prepared.canonicalDescriptorDigest) {
            return failure(
                category = FailureCategory.CORRUPT,
                code = "INGEST_PREPARED_DESCRIPTOR_DRIFT",
                retryable = false,
            )
        }
        return null
    }

    private fun buildRequest(
        prepared: PreparedMediaChunkUpload,
        payload: ByteArray,
        authorized: AuthorizedMediaIngestRequest,
    ): Request {
        val descriptor = prepared.descriptor
        val url = endpoint.baseUrl.newBuilder()
            .addPathSegment("v1")
            .addPathSegment("ingest-sessions")
            .addPathSegment(prepared.ingestSessionId.value)
            .addPathSegment("streams")
            .addPathSegment(descriptor.streamId.value)
            .addPathSegment("chunks")
            .addPathSegment(descriptor.chunkId.value)
            .build()
        val request = Request.Builder()
            .url(url)
            .put(payload.toRequestBody(OCTET_STREAM))
            .header("Authorization", authorized.credential.authorizationHeader())
            .header("X-Correlation-ID", authorized.correlationId.value)
            .header("Accept", "application/json, application/problem+json")
            .header("Cache-Control", "no-store")
            .header("Gumi-Sequence-First", descriptor.sequenceRange.first.toString())
            .header("Gumi-Sequence-Last", descriptor.sequenceRange.last.toString())
            .header("Gumi-Payload-Bytes", descriptor.payloadBytes.toString())
            .header("Gumi-Payload-Format", descriptor.payloadFormat.wireValue)
            .header("Content-Digest", descriptor.contentDigest.toContentDigestHeader())
            .header("Gumi-Codec-Configuration-Id", descriptor.codecConfigurationId.value)
            .header("Gumi-Source-Started-At", descriptor.sourceStartedAt.value)
            .header("Gumi-Source-Retransmission", descriptor.sourceRetransmission.toString())
        descriptor.edgeReceivedAt?.let {
            request.header("Gumi-Edge-Received-At", it.value)
        }
        descriptor.sourceDiscontinuityBefore?.let {
            request.header("Gumi-Discontinuity-Reason", it.reason.wireValue)
            request.header("Gumi-Dropped-Frame-Count", it.droppedFrameCount.toString())
        }
        return request.build()
    }

    private fun mapResponse(
        response: Response,
        prepared: PreparedMediaChunkUpload,
        authorized: AuthorizedMediaIngestRequest,
    ): MediaIngestUploadResult {
        if (response.headers("X-Correlation-ID") != listOf(authorized.correlationId.value)) {
            return MediaIngestUploadResult.OutcomeUnknown(
                failure(
                    category = FailureCategory.INCOMPATIBLE,
                    code = "INGEST_RESPONSE_CORRELATION_MISMATCH",
                    retryable = false,
                    correlationId = authorized.correlationId,
                ),
            )
        }
        val requestId = response.headers("X-Request-ID").singleOrNull()
        if (requestId == null || !UUID_V7.matches(requestId)) {
            return responseContractFailure(
                authorized,
                "INGEST_RESPONSE_REQUEST_ID_INVALID",
            )
        }
        val responseBytes = readBoundedBody(response)
            ?: return MediaIngestUploadResult.OutcomeUnknown(
                failure(
                    category = FailureCategory.INCOMPATIBLE,
                    code = "INGEST_RESPONSE_TOO_LARGE",
                    retryable = false,
                    correlationId = authorized.correlationId,
                ),
            )
        if (response.code == 200) {
            if (!response.hasSingleContentType("application", "json")) {
                return malformedSuccess(authorized, "INGEST_ACK_CONTENT_TYPE_INVALID")
            }
            val ack = try {
                parseDurableAck(responseBytes.decodeToString(), prepared)
            } catch (_: Exception) {
                return malformedSuccess(authorized, "INGEST_ACK_INVALID")
            }
            return MediaIngestUploadResult.DurablyAcknowledged(ack)
        }

        if (!response.hasSingleContentType("application", "problem+json")) {
            return responseContractFailure(authorized, "INGEST_PROBLEM_CONTENT_TYPE_INVALID")
        }
        val problem = parseProblem(responseBytes, response.code, requestId)
            ?: return responseContractFailure(authorized, "INGEST_PROBLEM_INVALID")
        if (response.code == 429) {
            val retryAfter = response.headers("Retry-After")
                .singleOrNull()
                ?.takeIf(CANONICAL_RETRY_AFTER_SECONDS::matches)
                ?.toIntOrNull()
                ?.takeIf { it in 0..MAXIMUM_RETRY_AFTER_SECONDS }
            if (problem.code != "RATE_LIMITED" ||
                retryAfter == null ||
                problem.retryAfterSeconds != retryAfter
            ) {
                return responseContractFailure(
                    authorized,
                    "INGEST_RATE_LIMIT_CONTRACT_INVALID",
                )
            }
        } else if (problem.retryAfterSeconds != null || response.headers("Retry-After").isNotEmpty()) {
            return responseContractFailure(
                authorized,
                "INGEST_PROBLEM_OPERATION_MISMATCH",
            )
        }
        if (response.code == 401 &&
            response.headers("WWW-Authenticate") != listOf(CANONICAL_DATA_BEARER_CHALLENGE)
        ) {
            return responseContractFailure(
                authorized,
                "INGEST_AUTHENTICATION_CHALLENGE_INVALID",
            )
        }
        if (problem.code !in UPLOAD_PROBLEM_CODES_BY_STATUS[response.code].orEmpty()) {
            return responseContractFailure(
                authorized,
                "INGEST_PROBLEM_OPERATION_MISMATCH",
            )
        }
        val mapped = failure(
            category = categoryForStatus(response.code),
            code = problem.code,
            retryable = response.code == 429,
            correlationId = authorized.correlationId,
            redactedEvidence = problem.retryAfterSeconds?.let {
                mapOf("retry_after_seconds" to it.toString())
            }.orEmpty(),
        )
        return if (response.code == 503) {
            MediaIngestUploadResult.OutcomeUnknown(mapped.copy(retryable = false))
        } else {
            MediaIngestUploadResult.Rejected(mapped)
        }
    }

    private fun malformedSuccess(
        authorized: AuthorizedMediaIngestRequest,
        code: String,
    ): MediaIngestUploadResult = MediaIngestUploadResult.OutcomeUnknown(
        failure(
            category = FailureCategory.INCOMPATIBLE,
            code = code,
            retryable = false,
            correlationId = authorized.correlationId,
        ),
    )

    private fun responseContractFailure(
        authorized: AuthorizedMediaIngestRequest,
        code: String,
    ): MediaIngestUploadResult = MediaIngestUploadResult.OutcomeUnknown(
        failure(
            category = FailureCategory.INCOMPATIBLE,
            code = code,
            retryable = false,
            correlationId = authorized.correlationId,
        ),
    )

    private fun readBoundedBody(response: Response): ByteArray? {
        val source = response.body.source()
        val buffer = Buffer()
        var total = 0L
        while (total <= maximumResponseBytes) {
            val remaining = maximumResponseBytes + 1L - total
            val read = source.read(buffer, minOf(8_192L, remaining))
            if (read == -1L) return buffer.readByteArray()
            total += read
        }
        return null
    }

    companion object {
        const val DEFAULT_MAXIMUM_REQUEST_BYTES: Long = 1L * 1_024L * 1_024L
        const val PUBLISHER_HARD_MAXIMUM_REQUEST_BYTES: Long = 1L * 1_024L * 1_024L
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES: Long = 64L * 1_024L
        const val MAXIMUM_CONFIGURABLE_RESPONSE_BYTES: Long = 1L * 1_024L * 1_024L
        val DEFAULT_REQUEST_TIMEOUT: Duration = 30.seconds
        val MAXIMUM_REQUEST_TIMEOUT: Duration = 5.minutes
    }
}

internal object DedicatedMediaIngestHttpTransport {
    fun build(requestTimeout: Duration): OkHttpClient = OkHttpClient.Builder()
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(requestTimeout)
        .build()
}

private fun Sha256Digest.toContentDigestHeader(): String =
    "sha-256=:${value.removePrefix("sha256:").decodeHex().base64()}:"

private fun parseDurableAck(
    text: String,
    prepared: PreparedMediaChunkUpload,
): DurableCloudAck {
    val root = STRICT_JSON.parseToJsonElement(text).jsonObject
    root.requireExactKeys(ACK_KEYS)
    require(root.requiredString("schemaVersion") == "gumi.media-ingest.ack.v1")
    val ingestSessionId = dev.gumi.edge.runtime.spool.IngestSessionId(
        root.requiredString("ingestSessionId"),
    )
    val streamId = dev.gumi.edge.runtime.spool.StreamId(root.requiredString("streamId"))
    val chunkId = dev.gumi.edge.runtime.spool.ChunkId(root.requiredString("acknowledgedChunkId"))
    val contentDigest = Sha256Digest(root.requiredString("acknowledgedContentDigest"))
    val descriptorDigest = Sha256Digest(root.requiredString("acknowledgedDescriptorDigest"))
    val range = root.requiredRange("acknowledgedSequenceRange")
    val disposition = when (root.requiredString("disposition")) {
        "stored" -> CloudAckDisposition.STORED
        "duplicate" -> CloudAckDisposition.DUPLICATE
        else -> error("Unsupported durable acknowledgement disposition")
    }
    val committed = root.requiredRanges("committedRanges", requireNonEmpty = true)
    val missing = root.requiredRanges("missingRanges", requireNonEmpty = false)
    val accounted = root.requiredRange("accountedRange")
    val durableThrough = root.optionalString("durableThrough")?.let(::canonicalUlong)
    validateRangeSnapshot(committed, missing, accounted, durableThrough)
    val stateRevision = canonicalUlong(root.requiredString("stateRevision"))
    require(root.requiredString("sessionState") in SESSION_STATES)
    SourceTimestamp(root.requiredString("acknowledgedAt"))

    val expectation = prepared.ackExpectation
    require(ingestSessionId == expectation.ingestSessionId)
    require(streamId == expectation.streamId)
    require(chunkId == expectation.chunkId)
    require(contentDigest == prepared.descriptor.contentDigest)
    require(descriptorDigest == expectation.descriptorDigest)
    require(range == prepared.descriptor.sequenceRange)
    require(committed.any { it.contains(range) })
    return DurableCloudAck(
        ingestSessionId = ingestSessionId,
        streamId = streamId,
        acknowledgedChunkId = chunkId,
        acknowledgedContentDigest = contentDigest,
        acknowledgedDescriptorDigest = descriptorDigest,
        acknowledgedSequenceRange = range,
        disposition = disposition,
        stateRevision = stateRevision,
    )
}

private data class ParsedProblem(
    val code: String,
    val retryAfterSeconds: Int?,
)

private fun parseProblem(
    bytes: ByteArray,
    expectedStatus: Int,
    expectedRequestId: String,
): ParsedProblem? = try {
    val root = STRICT_JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
    require(root.keys.containsAll(PROBLEM_REQUIRED_KEYS))
    require(PROBLEM_ALLOWED_KEYS.containsAll(root.keys))
    val type = root.requiredString("type")
    val title = root.requiredString("title")
    val detail = root.requiredString("detail")
    val code = root.requiredString("code")
    val statusPrimitive = requireNotNull(root["status"]).jsonPrimitive
    require(!statusPrimitive.isString)
    val status = requireNotNull(statusPrimitive.intOrNull)
    val traceId = root.requiredString("traceId")
    val retryAfter = root["retryAfterSeconds"]?.jsonPrimitive?.let { primitive ->
        require(!primitive.isString)
        requireNotNull(primitive.intOrNull)
    }
    root["details"]?.jsonObject
    code
        .takeIf {
            it in PUBLISHED_PROBLEM_CODES &&
                status == expectedStatus &&
                status in 400..599 &&
                traceId == expectedRequestId &&
                type.isNotEmpty() &&
                title.length in 1..200 &&
                detail.length in 1..2_000 &&
                (retryAfter == null || retryAfter in 0..MAXIMUM_RETRY_AFTER_SECONDS)
        }
        ?.let { ParsedProblem(it, retryAfter) }
} catch (_: Exception) {
    null
}

private fun JsonObject.requiredString(name: String): String {
    val primitive = requireNotNull(this[name]).jsonPrimitive
    require(primitive.isString) { "$name must be a JSON string" }
    return primitive.content
}

private fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val primitive = value.jsonPrimitive
    require(primitive.isString) { "$name must be a JSON string or null" }
    return primitive.content
}

private fun JsonObject.requiredRange(name: String): SequenceRange {
    val value = requireNotNull(this[name]).jsonObject
    value.requireExactKeys(setOf("first", "last"))
    return SequenceRange(
        canonicalUlong(value.requiredString("first")),
        canonicalUlong(value.requiredString("last")),
    )
}

private fun JsonObject.requiredRanges(name: String, requireNonEmpty: Boolean): List<SequenceRange> {
    val value = requireNotNull(this[name]).jsonArray
    if (requireNonEmpty) require(value.isNotEmpty())
    return value.map { element ->
        val range = element.jsonObject
        range.requireExactKeys(setOf("first", "last"))
        SequenceRange(
            canonicalUlong(range.requiredString("first")),
            canonicalUlong(range.requiredString("last")),
        )
    }.also(::requireCanonicalRanges)
}

private fun JsonObject.requireExactKeys(expected: Set<String>) {
    require(keys == expected) { "JSON object does not match its exact contract shape" }
}

private fun Response.hasSingleContentType(type: String, subtype: String): Boolean {
    val values = headers("Content-Type")
    if (values.size != 1) return false
    val contentType = values.single().toMediaTypeOrNull() ?: return false
    return contentType.type == type && contentType.subtype == subtype
}

private fun canonicalUlong(value: String): ULong {
    require(CANONICAL_U64.matches(value))
    return requireNotNull(value.toULongOrNull())
}

private fun requireCanonicalRanges(ranges: List<SequenceRange>) {
    ranges.zipWithNext().forEach { (left, right) ->
        require(left.first < right.first)
        require(left.last != ULong.MAX_VALUE && left.last + 1uL < right.first) {
            "Ranges must be sorted, disjoint, and canonically merged"
        }
    }
}

private fun validateRangeSnapshot(
    committed: List<SequenceRange>,
    missing: List<SequenceRange>,
    accounted: SequenceRange,
    durableThrough: ULong?,
) {
    val partition = (committed.map { it to true } + missing.map { it to false })
        .sortedBy { it.first.first }
    require(partition.isNotEmpty())
    var expectedFirst = accounted.first
    partition.forEachIndexed { index, (range, _) ->
        require(range.first == expectedFirst)
        if (range.last == ULong.MAX_VALUE) {
            require(range.last == accounted.last)
            require(index == partition.lastIndex) {
                "No range may follow a range ending at the unsigned-64 maximum"
            }
            expectedFirst = ULong.MAX_VALUE
        } else {
            expectedFirst = range.last + 1uL
        }
    }
    require(partition.last().first.last == accounted.last)
    val first = partition.first()
    val expectedDurableThrough = if (first.second && first.first.first == accounted.first) {
        first.first.last
    } else {
        null
    }
    require(durableThrough == expectedDurableThrough)
}

private fun categoryForStatus(status: Int): FailureCategory = when (status) {
    400 -> FailureCategory.INCOMPATIBLE
    401, 403 -> FailureCategory.UNAUTHORIZED
    404 -> FailureCategory.UNAVAILABLE
    409 -> FailureCategory.REJECTED_POLICY
    413, 429 -> FailureCategory.RESOURCE_EXHAUSTED
    422 -> FailureCategory.CORRUPT
    503 -> FailureCategory.UNAVAILABLE
    else -> FailureCategory.INTERNAL
}

private fun failure(
    category: FailureCategory,
    code: String,
    retryable: Boolean,
    correlationId: dev.gumi.edge.sdk.CorrelationId? = null,
    redactedEvidence: Map<String, String> = emptyMap(),
): ExpectedFailure = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
    correlationId = correlationId,
    redactedEvidence = redactedEvidence,
)

private val STRICT_JSON = Json {
    isLenient = false
    ignoreUnknownKeys = false
    allowSpecialFloatingPointValues = false
}
private val OCTET_STREAM = "application/octet-stream".toMediaType()
private val UUID_V7 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val CANONICAL_U64 = Regex("(?:0|[1-9][0-9]{0,19})")
private val CANONICAL_RETRY_AFTER_SECONDS = Regex("(?:0|[1-9][0-9]{0,4})")
private const val MAXIMUM_RETRY_AFTER_SECONDS = 86_400
private const val CANONICAL_DATA_BEARER_CHALLENGE =
    "Bearer realm=\"gumi-media-ingest-data\", charset=\"UTF-8\""
private val ACK_KEYS = setOf(
    "schemaVersion",
    "ingestSessionId",
    "streamId",
    "acknowledgedChunkId",
    "acknowledgedContentDigest",
    "acknowledgedDescriptorDigest",
    "acknowledgedSequenceRange",
    "disposition",
    "committedRanges",
    "missingRanges",
    "accountedRange",
    "durableThrough",
    "stateRevision",
    "sessionState",
    "acknowledgedAt",
)
private val PROBLEM_REQUIRED_KEYS = setOf(
    "type",
    "title",
    "status",
    "code",
    "detail",
    "traceId",
)
private val PROBLEM_ALLOWED_KEYS = PROBLEM_REQUIRED_KEYS + setOf(
    "retryAfterSeconds",
    "details",
)
private val SESSION_STATES = setOf("open", "finalizing", "finalized")
private val UPLOAD_PROBLEM_CODES_BY_STATUS = mapOf(
    400 to setOf(
        "INVALID_REQUEST",
        "INVALID_SEQUENCE_RANGE",
    ),
    401 to setOf(
        "AUTHENTICATION_REQUIRED",
        "INVALID_INGEST_CREDENTIAL",
        "INGEST_CREDENTIAL_EXPIRED",
    ),
    403 to setOf(
        "SESSION_SCOPE_MISMATCH",
        "DEVICE_REVOKED",
        "CAPTURE_REVOKED",
    ),
    404 to setOf(
        "INGEST_SESSION_NOT_FOUND",
        "STREAM_NOT_FOUND",
    ),
    409 to setOf(
        "CHUNK_DIGEST_CONFLICT",
        "CHUNK_METADATA_CONFLICT",
        "SEQUENCE_OVERLAP",
        "INGEST_SESSION_EXPIRED",
        "SESSION_ALREADY_FINALIZED",
    ),
    413 to setOf("CHUNK_TOO_LARGE"),
    422 to setOf(
        "CONTENT_LENGTH_MISMATCH",
        "CONTENT_DIGEST_MISMATCH",
    ),
    429 to setOf("RATE_LIMITED"),
    503 to setOf("DURABILITY_UNAVAILABLE"),
)
private val PUBLISHED_PROBLEM_CODES = setOf(
    "INVALID_REQUEST",
    "REQUEST_BODY_TOO_LARGE",
    "INVALID_SEQUENCE_RANGE",
    "AUTHENTICATION_REQUIRED",
    "INVALID_CONTROL_CREDENTIAL",
    "INVALID_INGEST_CREDENTIAL",
    "INGEST_CREDENTIAL_EXPIRED",
    "CONTROL_SCOPE_MISMATCH",
    "SESSION_SCOPE_MISMATCH",
    "DEVICE_REVOKED",
    "CAPTURE_REVOKED",
    "INGEST_SESSION_NOT_FOUND",
    "STREAM_NOT_FOUND",
    "CHUNK_DIGEST_CONFLICT",
    "CHUNK_METADATA_CONFLICT",
    "SEQUENCE_OVERLAP",
    "SEQUENCE_GAP",
    "FINALIZATION_STREAM_SET_MISMATCH",
    "FINALIZATION_RANGE_CONFLICT",
    "REQUEST_ID_CONFLICT",
    "INGEST_SESSION_EXPIRED",
    "SESSION_ALREADY_FINALIZED",
    "MANIFEST_DIGEST_CONFLICT",
    "CHUNK_TOO_LARGE",
    "CONTENT_LENGTH_MISMATCH",
    "CONTENT_DIGEST_MISMATCH",
    "FINAL_DIGEST_MISMATCH",
    "RATE_LIMITED",
    "DURABILITY_UNAVAILABLE",
)
