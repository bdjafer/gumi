package dev.gumi.edge.adapters.cloud.mediaingest

import dev.gumi.edge.runtime.ingest.MediaIngestUploadResult
import dev.gumi.edge.runtime.spool.CloudAckDisposition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MediaIngestPublisherContractTest {
    @Test
    fun canonicalDescriptorMatchesTheCheckedInPublisherFixture() {
        val fixture = publisherJson("fixtures/v1/success/chunk-descriptor.json")
        val mapped = MediaIngestV1DescriptorMapper.canonical(
            MediaIngestHttpTestFixture.ingestSessionId,
            MediaIngestHttpTestFixture.descriptor,
        )

        assertEquals(fixture, JSON.parseToJsonElement(mapped.json))
        assertEquals(MediaIngestHttpTestFixture.descriptorDigest, mapped.digest)
    }

    @Test
    fun checkedInPublisherAcknowledgementsRemainConsumable() {
        listOf(
            "fixtures/v1/success/chunk-stored-ack.json" to CloudAckDisposition.STORED,
            "fixtures/v1/success/chunk-duplicate-ack.json" to CloudAckDisposition.DUPLICATE,
        ).forEach { (path, expectedDisposition) ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-Correlation-ID", MediaIngestHttpTestFixture.correlationId.value)
                        .addHeader("X-Request-ID", MediaIngestHttpTestFixture.requestId)
                        .setBody(publisherFile(path).readText()),
                )
                val adapter = MediaIngestHttpAdapter(
                    endpoint = MediaIngestHttpEndpoint(
                        server.url("/"),
                        allowCleartextLoopback = true,
                    ),
                    authorization = MediaIngestAuthorizationPort {
                        MediaIngestAuthorizationResult.Authorized(
                            AuthorizedMediaIngestRequest(
                                credential = CaptureScopedIngestCredential.copyOf("fixture-credential"),
                                correlationId = MediaIngestHttpTestFixture.correlationId,
                            ),
                        )
                    },
                )

                val result = runBlocking {
                    adapter.uploadChunk(
                        MediaIngestHttpTestFixture.prepared,
                        MediaIngestHttpTestFixture.opaquePayload,
                    )
                }

                val acknowledged = assertIs<MediaIngestUploadResult.DurablyAcknowledged>(result)
                assertEquals(expectedDisposition, acknowledged.ack.disposition)
            }
        }
    }

    @Test
    fun publisherSchemasAndPutOperationRemainCompatibleWithThisConsumer() {
        val openApi = publisherJson("api/v1/openapi.json").jsonObject
        val put = openApi.requiredObject("paths")
            .requiredObject(PUT_CHUNK_PATH)
            .requiredObject("put")
        assertEquals("putMediaChunk", put.requiredString("operationId"))
        assertEquals(
            EXPECTED_PARAMETER_REFS,
            put.requiredArray("parameters").map { it.jsonObject.requiredString("\$ref") },
        )
        assertEquals(
            setOf("application/octet-stream"),
            put.requiredObject("requestBody").requiredObject("content").keys,
        )

        val responses = put.requiredObject("responses")
        assertEquals(EXPECTED_RESPONSE_STATUSES, responses.keys)
        assertEquals(
            "./schemas/chunk.schema.json#/\$defs/DurableAck",
            responses.requiredObject("200")
                .requiredObject("content")
                .requiredObject("application/json")
                .requiredObject("schema")
                .requiredString("\$ref"),
        )
        EXPECTED_PROBLEM_RESPONSE_REFS.forEach { (status, expectedRef) ->
            assertEquals(expectedRef, responses.requiredObject(status).requiredString("\$ref"), status)
        }

        val chunkDefinitions = publisherJson("api/v1/schemas/chunk.schema.json")
            .jsonObject
            .requiredObject("\$defs")
        val descriptor = chunkDefinitions.requiredObject("ChunkDescriptor")
        assertEquals(false, descriptor.requiredBoolean("additionalProperties"))
        assertEquals(DESCRIPTOR_REQUIRED_KEYS, descriptor.requiredStringSet("required"))
        assertEquals(
            "gumi.media-ingest.chunk.v1",
            descriptor.requiredObject("properties")
                .requiredObject("schemaVersion")
                .requiredString("const"),
        )
        val ack = chunkDefinitions.requiredObject("DurableAck")
        assertEquals(false, ack.requiredBoolean("additionalProperties"))
        assertEquals(ACK_REQUIRED_KEYS, ack.requiredStringSet("required"))
        assertEquals(
            "gumi.media-ingest.ack.v1",
            ack.requiredObject("properties")
                .requiredObject("schemaVersion")
                .requiredString("const"),
        )

        val problem = publisherJson("api/v1/schemas/problem.schema.json")
            .jsonObject
            .requiredObject("\$defs")
            .requiredObject("Problem")
        assertEquals(false, problem.requiredBoolean("additionalProperties"))
        assertEquals(PROBLEM_REQUIRED_KEYS, problem.requiredStringSet("required"))
        val publishedProblemCodes = problem.requiredObject("properties")
            .requiredObject("code")
            .requiredStringSet("enum")
        assertTrue(
            publishedProblemCodes.containsAll(CONSUMED_PUT_PROBLEM_CODES),
            "The publisher problem schema no longer contains every PUT response consumed by edge",
        )
    }
}

private fun publisherFile(relativePath: String): File {
    val repositoryRoot = requireNotNull(System.getProperty("gumi.repositoryRoot")) {
        "gumi.repositoryRoot must point to the repository under test"
    }
    return File(repositoryRoot, "cloud/apps/media-ingest/$relativePath")
        .also { require(it.isFile) { "Publisher contract file is absent: ${it.path}" } }
}

private fun publisherJson(relativePath: String) =
    JSON.parseToJsonElement(publisherFile(relativePath).readText())

private fun kotlinx.serialization.json.JsonObject.requiredObject(name: String) =
    requireNotNull(this[name]) { "Missing object $name" }.jsonObject

private fun kotlinx.serialization.json.JsonObject.requiredArray(name: String) =
    requireNotNull(this[name]) { "Missing array $name" }.jsonArray

private fun kotlinx.serialization.json.JsonObject.requiredString(name: String): String {
    val primitive = requireNotNull(this[name]) { "Missing string $name" }.jsonPrimitive
    require(primitive.isString) { "$name is not a JSON string" }
    return primitive.content
}

private fun kotlinx.serialization.json.JsonObject.requiredBoolean(name: String): Boolean =
    requireNotNull(this[name]) { "Missing boolean $name" }.jsonPrimitive.boolean

private fun kotlinx.serialization.json.JsonObject.requiredStringSet(name: String): Set<String> =
    requiredArray(name).map { element ->
        val primitive = element.jsonPrimitive
        require(primitive.isString) { "$name contains a non-string value" }
        primitive.content
    }.toSet()

private val JSON = Json {
    isLenient = false
    ignoreUnknownKeys = false
}

private const val PUT_CHUNK_PATH =
    "/v1/ingest-sessions/{ingestSessionId}/streams/{streamId}/chunks/{chunkId}"
private val EXPECTED_PARAMETER_REFS = listOf(
    "#/components/parameters/IngestSessionId",
    "#/components/parameters/StreamId",
    "#/components/parameters/ChunkId",
    "#/components/parameters/SequenceFirst",
    "#/components/parameters/SequenceLast",
    "#/components/parameters/PayloadBytes",
    "#/components/parameters/PayloadFormat",
    "#/components/parameters/ContentDigest",
    "#/components/parameters/CodecConfigurationId",
    "#/components/parameters/SourceStartedAt",
    "#/components/parameters/EdgeReceivedAt",
    "#/components/parameters/SourceRetransmission",
    "#/components/parameters/DiscontinuityReason",
    "#/components/parameters/DroppedFrameCount",
    "#/components/parameters/CorrelationId",
)
private val EXPECTED_RESPONSE_STATUSES = setOf(
    "200",
    "400",
    "401",
    "403",
    "404",
    "409",
    "413",
    "422",
    "429",
    "503",
)
private val EXPECTED_PROBLEM_RESPONSE_REFS = mapOf(
    "400" to "#/components/responses/Problem",
    "401" to "#/components/responses/UnauthorizedProblem",
    "403" to "#/components/responses/Problem",
    "404" to "#/components/responses/Problem",
    "409" to "#/components/responses/Problem",
    "413" to "#/components/responses/Problem",
    "422" to "#/components/responses/Problem",
    "429" to "#/components/responses/RateLimitedProblem",
    "503" to "#/components/responses/Problem",
)
private val DESCRIPTOR_REQUIRED_KEYS = setOf(
    "schemaVersion",
    "ingestSessionId",
    "streamId",
    "chunkId",
    "sequenceRange",
    "payloadBytes",
    "payloadFormat",
    "contentDigest",
    "codecConfigurationId",
    "sourceStartedAt",
    "sourceRetransmission",
)
private val ACK_REQUIRED_KEYS = setOf(
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
private val CONSUMED_PUT_PROBLEM_CODES = setOf(
    "INVALID_REQUEST",
    "INVALID_SEQUENCE_RANGE",
    "AUTHENTICATION_REQUIRED",
    "INVALID_INGEST_CREDENTIAL",
    "INGEST_CREDENTIAL_EXPIRED",
    "SESSION_SCOPE_MISMATCH",
    "DEVICE_REVOKED",
    "CAPTURE_REVOKED",
    "INGEST_SESSION_NOT_FOUND",
    "STREAM_NOT_FOUND",
    "CHUNK_DIGEST_CONFLICT",
    "CHUNK_METADATA_CONFLICT",
    "SEQUENCE_OVERLAP",
    "INGEST_SESSION_EXPIRED",
    "SESSION_ALREADY_FINALIZED",
    "CHUNK_TOO_LARGE",
    "CONTENT_LENGTH_MISMATCH",
    "CONTENT_DIGEST_MISMATCH",
    "RATE_LIMITED",
    "DURABILITY_UNAVAILABLE",
)
