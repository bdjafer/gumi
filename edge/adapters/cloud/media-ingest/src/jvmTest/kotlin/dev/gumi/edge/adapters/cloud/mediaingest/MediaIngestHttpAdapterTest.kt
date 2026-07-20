package dev.gumi.edge.adapters.cloud.mediaingest

import dev.gumi.edge.runtime.ingest.MediaIngestUploadResult
import dev.gumi.edge.runtime.ingest.PreparedMediaChunkUpload
import dev.gumi.edge.runtime.spool.CloudAckDisposition
import dev.gumi.edge.runtime.spool.Sha256Digest
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.OpaqueBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MediaIngestHttpAdapterTest {
    @Test
    fun exactPutRequestAndStoredOrDuplicateAcknowledgementRoundTrip() {
        listOf(
            "stored" to CloudAckDisposition.STORED,
            "duplicate" to CloudAckDisposition.DUPLICATE,
        ).forEach { (wireDisposition, expectedDisposition) ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(successResponse(MediaIngestHttpTestFixture.ackJson(wireDisposition)))
                val authorization = CountingAuthorization()

                val result = runBlocking {
                    adapter(server, authorization).uploadChunk(
                        MediaIngestHttpTestFixture.prepared,
                        MediaIngestHttpTestFixture.opaquePayload,
                    )
                }

                val acknowledged = assertIs<MediaIngestUploadResult.DurablyAcknowledged>(result)
                assertEquals(expectedDisposition, acknowledged.ack.disposition)
                assertEquals(MediaIngestHttpTestFixture.chunkId, acknowledged.ack.acknowledgedChunkId)
                assertEquals(MediaIngestHttpTestFixture.descriptorDigest, acknowledged.ack.acknowledgedDescriptorDigest)
                assertEquals(1, authorization.calls)

                val request = server.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull(request)
                assertEquals("PUT", request.method)
                assertEquals(
                    "/v1/ingest-sessions/${MediaIngestHttpTestFixture.ingestSessionId.value}" +
                        "/streams/${MediaIngestHttpTestFixture.streamId.value}" +
                        "/chunks/${MediaIngestHttpTestFixture.chunkId.value}",
                    request.path,
                )
                assertSingleHeader(request, "Authorization", "Bearer capture-secret")
                assertSingleHeader(
                    request,
                    "X-Correlation-ID",
                    MediaIngestHttpTestFixture.correlationId.value,
                )
                assertSingleHeader(request, "Content-Type", "application/octet-stream")
                assertSingleHeader(request, "Content-Length", "168")
                assertSingleHeader(request, "Accept", "application/json, application/problem+json")
                assertSingleHeader(request, "Cache-Control", "no-store")
                assertSingleHeader(request, "Gumi-Payload-Bytes", "168")
                assertSingleHeader(request, "Gumi-Sequence-First", "0")
                assertSingleHeader(request, "Gumi-Sequence-Last", "1")
                assertSingleHeader(request, "Gumi-Payload-Format", "ogg-opus-page-fragment-v1")
                assertSingleHeader(request, "Gumi-Codec-Configuration-Id", "opus-16000-mono-20ms-v1")
                assertSingleHeader(request, "Gumi-Source-Started-At", "2026-07-19T20:00:01Z")
                assertSingleHeader(request, "Gumi-Edge-Received-At", "2026-07-19T20:00:01.125Z")
                assertSingleHeader(request, "Gumi-Source-Retransmission", "false")
                assertSingleHeader(
                    request,
                    "Content-Digest",
                    "sha-256=:2FfFR8b/sdj5ljosbq0EIFAaNRm1tvis9RQ05Hmty4M=:",
                )
                assertTrue(request.headers.values("Gumi-Discontinuity-Reason").isEmpty())
                assertTrue(request.headers.values("Gumi-Dropped-Frame-Count").isEmpty())
                assertContentEquals(MediaIngestHttpTestFixture.payload, request.body.readByteArray())
            }
        }
    }

    @Test
    fun localPreflightFailuresNeverAuthorizeOrTouchTheNetwork() {
        MockWebServer().use { server ->
            server.start()
            val authorization = CountingAuthorization()
            val adapter = adapter(server, authorization)

            val lengthMismatch = runBlocking {
                adapter.uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    OpaqueBytes.copyOf(byteArrayOf(1)),
                )
            }
            assertFailureCode(lengthMismatch, "INGEST_LOCAL_PAYLOAD_LENGTH_MISMATCH")

            val corruptPayload = MediaIngestHttpTestFixture.payload.copyOf().also {
                it[it.lastIndex] = (it.last() + 1).toByte()
            }
            val digestMismatch = runBlocking {
                adapter.uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    OpaqueBytes.copyOf(corruptPayload),
                )
            }
            assertFailureCode(digestMismatch, "INGEST_LOCAL_PAYLOAD_DIGEST_MISMATCH")

            val drifted = PreparedMediaChunkUpload(
                ingestSessionId = MediaIngestHttpTestFixture.ingestSessionId,
                descriptor = MediaIngestHttpTestFixture.descriptor,
                canonicalDescriptorDigest = Sha256Digest("sha256:${"0".repeat(64)}"),
            )
            val descriptorDrift = runBlocking {
                adapter.uploadChunk(drifted, MediaIngestHttpTestFixture.opaquePayload)
            }
            assertFailureCode(descriptorDrift, "INGEST_PREPARED_DESCRIPTOR_DRIFT")

            val requestBound = runBlocking {
                adapter(
                    server,
                    authorization,
                    maximumRequestBytes = MediaIngestHttpTestFixture.payload.size.toLong() - 1L,
                ).uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    MediaIngestHttpTestFixture.opaquePayload,
                )
            }
            assertFailureCode(requestBound, "INGEST_LOCAL_PAYLOAD_TOO_LARGE")

            assertEquals(0, authorization.calls)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun authorizationFailureOrExceptionIsNotAnExternalAttempt() {
        MockWebServer().use { server ->
            server.start()
            val unavailable = MediaIngestAuthorizationPort {
                MediaIngestAuthorizationResult.Unavailable(
                    ExpectedFailure(
                        category = FailureCategory.UNAVAILABLE,
                        code = FailureCode("CREDENTIAL_STORE_LOCKED"),
                        retryable = false,
                    ),
                )
            }
            val unavailableResult = runBlocking {
                adapter(server, unavailable).uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    MediaIngestHttpTestFixture.opaquePayload,
                )
            }
            assertFailureCode(unavailableResult, "CREDENTIAL_STORE_LOCKED")

            val throwing = MediaIngestAuthorizationPort { error("secret provider detail") }
            val exceptionResult = runBlocking {
                adapter(server, throwing).uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    MediaIngestHttpTestFixture.opaquePayload,
                )
            }
            assertFailureCode(exceptionResult, "INGEST_AUTHORIZATION_UNAVAILABLE")
            assertEquals(0, server.requestCount)
            assertTrue(exceptionResult.toString().contains("secret provider detail").not())
        }
    }

    @Test
    fun malformedMismatchedOrOversizedSuccessCanNeverAdvanceDurability() {
        val cases = listOf(
            successResponse("not-json") to "INGEST_ACK_INVALID",
            successResponse(
                MediaIngestHttpTestFixture.ackJson()
                    .replace("\"stateRevision\": \"1\"", "\"stateRevision\": 1"),
            ) to "INGEST_ACK_INVALID",
            successResponse(
                MediaIngestHttpTestFixture.ackJson()
                    .replace("\"first\": \"0\", \"last\": \"1\"", "\"first\": 0, \"last\": \"1\""),
            ) to "INGEST_ACK_INVALID",
            successResponse(
                MediaIngestHttpTestFixture.ackJson()
                    .replace("\"durableThrough\": \"1\"", "\"durableThrough\": 1"),
            ) to "INGEST_ACK_INVALID",
            successResponse(
                MediaIngestHttpTestFixture.ackJson()
                    .replace(
                        "\"committedRanges\": [{ \"first\": \"0\", \"last\": \"1\" }]",
                        "\"committedRanges\": [{ \"first\": \"0\", \"last\": \"${ULong.MAX_VALUE}\" }]",
                    )
                    .replace(
                        "\"missingRanges\": []",
                        "\"missingRanges\": [{ \"first\": \"${ULong.MAX_VALUE}\", " +
                            "\"last\": \"${ULong.MAX_VALUE}\" }]",
                    )
                    .replace(
                        "\"accountedRange\": { \"first\": \"0\", \"last\": \"1\" }",
                        "\"accountedRange\": { \"first\": \"0\", \"last\": \"${ULong.MAX_VALUE}\" }",
                    )
                    .replace("\"durableThrough\": \"1\"", "\"durableThrough\": \"${ULong.MAX_VALUE}\""),
            ) to "INGEST_ACK_INVALID",
            successResponse(
                MediaIngestHttpTestFixture.ackJson(
                    acknowledgedChunkId = "0190c6f0-7b21-7a40-8b11-000000000099",
                ),
            ) to "INGEST_ACK_INVALID",
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/plain")
                .addHeader("X-Correlation-ID", MediaIngestHttpTestFixture.correlationId.value)
                .addHeader("X-Request-ID", MediaIngestHttpTestFixture.requestId)
                .setBody(MediaIngestHttpTestFixture.ackJson()) to "INGEST_ACK_CONTENT_TYPE_INVALID",
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Correlation-ID", MediaIngestHttpTestFixture.correlationId.value)
                .addHeader("X-Request-ID", MediaIngestHttpTestFixture.requestId)
                .setBody("x".repeat(4_097)) to "INGEST_RESPONSE_TOO_LARGE",
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Correlation-ID", "0190c6f0-7b21-7a40-8b11-000000000099")
                .addHeader("X-Request-ID", MediaIngestHttpTestFixture.requestId)
                .setBody(MediaIngestHttpTestFixture.ackJson()) to "INGEST_RESPONSE_CORRELATION_MISMATCH",
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Correlation-ID", MediaIngestHttpTestFixture.correlationId.value)
                .setBody(MediaIngestHttpTestFixture.ackJson()) to "INGEST_RESPONSE_REQUEST_ID_INVALID",
        )

        cases.forEach { (response, expectedCode) ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(response)
                val result = runBlocking {
                    adapter(server, CountingAuthorization(), maximumResponseBytes = 4_096).uploadChunk(
                        MediaIngestHttpTestFixture.prepared,
                        MediaIngestHttpTestFixture.opaquePayload,
                    )
                }
                val unknown = assertIs<MediaIngestUploadResult.OutcomeUnknown>(result)
                assertEquals(expectedCode, unknown.failure.code.value)
                assertEquals(false, unknown.failure.retryable)
            }
        }
    }

    @Test
    fun malformedOrMislabeledProblemsCannotBecomeTypedPublisherFailures() {
        val validBody = """
            {"type":"/problems/chunk-digest-conflict","title":"Conflict","status":409,
             "code":"CHUNK_DIGEST_CONFLICT","detail":"Conflict",
             "traceId":"${MediaIngestHttpTestFixture.requestId}"}
        """.trimIndent()
        val cases = listOf(
            MockResponse()
                .setResponseCode(409)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Correlation-ID", MediaIngestHttpTestFixture.correlationId.value)
                .addHeader("X-Request-ID", MediaIngestHttpTestFixture.requestId)
                .setBody(validBody) to "INGEST_PROBLEM_CONTENT_TYPE_INVALID",
            problemResponse(
                409,
                validBody.replace("\"status\":409", "\"status\":\"409\""),
            ) to "INGEST_PROBLEM_INVALID",
        )

        cases.forEach { (response, expectedCode) ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(response)
                val result = runBlocking {
                    adapter(server, CountingAuthorization()).uploadChunk(
                        MediaIngestHttpTestFixture.prepared,
                        MediaIngestHttpTestFixture.opaquePayload,
                    )
                }
                assertIs<MediaIngestUploadResult.OutcomeUnknown>(result)
                assertFailureCode(result, expectedCode)
            }
        }
    }

    @Test
    fun dedicatedTransportHasNoAmbientHooksOrAutomaticReplayMechanism() {
        val client = DedicatedMediaIngestHttpTransport.build(17.seconds)

        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertSame(Proxy.NO_PROXY, client.proxy)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertEquals(null, client.cache)
        assertEquals(false, client.followRedirects)
        assertEquals(false, client.followSslRedirects)
        assertEquals(false, client.retryOnConnectionFailure)
        assertEquals(17_000L, client.callTimeoutMillis.toLong())
    }

    @Test
    fun publisherProblemsAreAllowlistedAndDefinitiveOnlyWhereSpecified() {
        val problem = """
            {"type":"/problems/chunk-digest-conflict","title":"Conflict","status":409,
             "code":"CHUNK_DIGEST_CONFLICT","detail":"provider secret detail",
             "traceId":"${MediaIngestHttpTestFixture.requestId}"}
        """.trimIndent()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(409, problem))
            server.enqueue(
                problemResponse(
                    503,
                    """
                        {"type":"/problems/durability-unavailable","title":"Unavailable",
                         "status":503,"code":"DURABILITY_UNAVAILABLE","detail":"Unavailable",
                         "traceId":"${MediaIngestHttpTestFixture.requestId}"}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                problemResponse(
                    429,
                    """
                        {"type":"/problems/rate-limited","title":"Rate limited",
                         "status":429,"code":"RATE_LIMITED","detail":"Wait",
                         "traceId":"${MediaIngestHttpTestFixture.requestId}","retryAfterSeconds":17}
                    """.trimIndent(),
                    retryAfterSeconds = 17,
                ),
            )
            server.enqueue(
                problemResponse(
                    429,
                    """
                        {"type":"/problems/rate-limited","title":"Rate limited",
                         "status":429,"code":"RATE_LIMITED","detail":"Wait",
                         "traceId":"${MediaIngestHttpTestFixture.requestId}","retryAfterSeconds":17}
                    """.trimIndent(),
                ),
            )

            val conflict = runBlocking {
                adapter(server, CountingAuthorization()).uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    MediaIngestHttpTestFixture.opaquePayload,
                )
            }
            val rejectedConflict = assertIs<MediaIngestUploadResult.Rejected>(conflict)
            assertEquals("CHUNK_DIGEST_CONFLICT", rejectedConflict.failure.code.value)
            assertEquals(false, rejectedConflict.failure.retryable)
            assertTrue(conflict.toString().contains("provider secret detail").not())

            val unavailable = runBlocking {
                adapter(server, CountingAuthorization()).uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    MediaIngestHttpTestFixture.opaquePayload,
                )
            }
            val unknownUnavailable = assertIs<MediaIngestUploadResult.OutcomeUnknown>(unavailable)
            assertEquals("DURABILITY_UNAVAILABLE", unknownUnavailable.failure.code.value)
            assertEquals(false, unknownUnavailable.failure.retryable)

            val rateLimited = runBlocking {
                adapter(server, CountingAuthorization()).uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    MediaIngestHttpTestFixture.opaquePayload,
                )
            }
            val rejectedRateLimit = assertIs<MediaIngestUploadResult.Rejected>(rateLimited)
            assertEquals("RATE_LIMITED", rejectedRateLimit.failure.code.value)
            assertEquals(true, rejectedRateLimit.failure.retryable)
            assertEquals(
                mapOf("retry_after_seconds" to "17"),
                rejectedRateLimit.failure.redactedEvidence,
            )

            val malformedRateLimit = runBlocking {
                adapter(server, CountingAuthorization()).uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    MediaIngestHttpTestFixture.opaquePayload,
                )
            }
            assertFailureCode(malformedRateLimit, "INGEST_RATE_LIMIT_CONTRACT_INVALID")
            assertIs<MediaIngestUploadResult.OutcomeUnknown>(malformedRateLimit)
        }
    }

    @Test
    fun everyPublishedPutChunk4xxPairMapsToOneTypedRejection() {
        MockWebServer().use { server ->
            server.start()
            DEFINITIVE_PUT_REJECTIONS.forEach { case ->
                val retryAfter = if (case.status == 429) 17 else null
                val challenge = if (case.status == 401) {
                    "Bearer realm=\"gumi-media-ingest-data\", charset=\"UTF-8\""
                } else {
                    null
                }
                server.enqueue(
                    problemResponse(
                        case.status,
                        problemBody(case.status, case.problemCode, retryAfter),
                        retryAfterSeconds = retryAfter,
                        wwwAuthenticate = challenge,
                    ),
                )
            }
            val adapter = adapter(server, CountingAuthorization())

            DEFINITIVE_PUT_REJECTIONS.forEach { case ->
                val result = runBlocking {
                    adapter.uploadChunk(
                        MediaIngestHttpTestFixture.prepared,
                        MediaIngestHttpTestFixture.opaquePayload,
                    )
                }

                val rejected = assertIs<MediaIngestUploadResult.Rejected>(result)
                assertEquals(case.problemCode, rejected.failure.code.value)
                assertEquals(case.status == 429, rejected.failure.retryable)
            }
        }
    }

    @Test
    fun onlyExactPutChunkProblemPairsBecomeTypedRejections() {
        val cases = listOf(
            ProblemCase(400, "CHUNK_DIGEST_CONFLICT", "INGEST_PROBLEM_OPERATION_MISMATCH"),
            ProblemCase(403, "CONTROL_SCOPE_MISMATCH", "INGEST_PROBLEM_OPERATION_MISMATCH"),
            ProblemCase(409, "FINALIZATION_RANGE_CONFLICT", "INGEST_PROBLEM_OPERATION_MISMATCH"),
            ProblemCase(413, "REQUEST_BODY_TOO_LARGE", "INGEST_PROBLEM_OPERATION_MISMATCH"),
            ProblemCase(418, "INVALID_REQUEST", "INGEST_PROBLEM_OPERATION_MISMATCH"),
            ProblemCase(503, "CHUNK_DIGEST_CONFLICT", "INGEST_PROBLEM_OPERATION_MISMATCH"),
            ProblemCase(401, "INVALID_INGEST_CREDENTIAL", "INGEST_AUTHENTICATION_CHALLENGE_INVALID"),
            ProblemCase(
                401,
                "INVALID_INGEST_CREDENTIAL",
                "INGEST_AUTHENTICATION_CHALLENGE_INVALID",
                wwwAuthenticate = "Bearer realm=\"wrong\"",
            ),
        )

        cases.forEach { case ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    problemResponse(
                        case.status,
                        problemBody(case.status, case.problemCode),
                        wwwAuthenticate = case.wwwAuthenticate,
                    ),
                )
                val result = runBlocking {
                    adapter(server, CountingAuthorization()).uploadChunk(
                        MediaIngestHttpTestFixture.prepared,
                        MediaIngestHttpTestFixture.opaquePayload,
                    )
                }

                assertIs<MediaIngestUploadResult.OutcomeUnknown>(result)
                assertFailureCode(result, case.expectedFailureCode)
            }
        }

        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                problemResponse(
                    401,
                    problemBody(401, "INVALID_INGEST_CREDENTIAL"),
                    wwwAuthenticate =
                        "Bearer realm=\"gumi-media-ingest-data\", charset=\"UTF-8\"",
                ),
            )
            val result = runBlocking {
                adapter(server, CountingAuthorization()).uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    MediaIngestHttpTestFixture.opaquePayload,
                )
            }

            val rejected = assertIs<MediaIngestUploadResult.Rejected>(result)
            assertEquals("INVALID_INGEST_CREDENTIAL", rejected.failure.code.value)
            assertEquals(false, rejected.failure.retryable)
        }
    }

    @Test
    fun retryMetadataIsAcceptedOnlyForTheExactRateLimitContract() {
        val unexpectedRetryBody = problemBody(409, "CHUNK_DIGEST_CONFLICT")
            .dropLast(1) + ",\"retryAfterSeconds\":17}"
        val cases = listOf(
            problemResponse(409, unexpectedRetryBody),
            problemResponse(
                409,
                problemBody(409, "CHUNK_DIGEST_CONFLICT"),
                retryAfterSeconds = 17,
            ),
            problemResponse(
                429,
                problemBody(429, "RATE_LIMITED", retryAfterSeconds = 18),
                retryAfterSeconds = 17,
            ),
        )

        cases.forEach { response ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(response)
                val result = runBlocking {
                    adapter(server, CountingAuthorization()).uploadChunk(
                        MediaIngestHttpTestFixture.prepared,
                        MediaIngestHttpTestFixture.opaquePayload,
                    )
                }
                assertIs<MediaIngestUploadResult.OutcomeUnknown>(result)
            }
        }
    }

    @Test
    fun transportDisconnectIsOutcomeUnknownAndCoroutineCancellationPropagates() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val disconnected = runBlocking {
                adapter(server, CountingAuthorization()).uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    MediaIngestHttpTestFixture.opaquePayload,
                )
            }
            assertFailureCode(disconnected, "INGEST_TRANSPORT_OUTCOME_UNKNOWN")
            assertIs<MediaIngestUploadResult.OutcomeUnknown>(disconnected)
        }

        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                successResponse(MediaIngestHttpTestFixture.ackJson())
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            val disconnectedBody = runBlocking {
                adapter(server, CountingAuthorization()).uploadChunk(
                    MediaIngestHttpTestFixture.prepared,
                    MediaIngestHttpTestFixture.opaquePayload,
                )
            }
            assertFailureCode(disconnectedBody, "INGEST_RESPONSE_BODY_OUTCOME_UNKNOWN")
            assertIs<MediaIngestUploadResult.OutcomeUnknown>(disconnectedBody)
        }

        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            runBlocking {
                val upload = async {
                    adapter(server, CountingAuthorization()).uploadChunk(
                        MediaIngestHttpTestFixture.prepared,
                        MediaIngestHttpTestFixture.opaquePayload,
                    )
                }
                yield()
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                upload.cancel()
                upload.join()
                assertTrue(upload.isCancelled)
            }
        }
    }

    private fun adapter(
        server: MockWebServer,
        authorization: MediaIngestAuthorizationPort,
        maximumRequestBytes: Long = MediaIngestHttpAdapter.DEFAULT_MAXIMUM_REQUEST_BYTES,
        maximumResponseBytes: Long = MediaIngestHttpAdapter.DEFAULT_MAXIMUM_RESPONSE_BYTES,
    ): MediaIngestHttpAdapter = MediaIngestHttpAdapter(
        endpoint = MediaIngestHttpEndpoint(server.url("/"), allowCleartextLoopback = true),
        authorization = authorization,
        maximumRequestBytes = maximumRequestBytes,
        maximumResponseBytes = maximumResponseBytes,
    )

    private fun successResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .addHeader("X-Correlation-ID", MediaIngestHttpTestFixture.correlationId.value)
        .addHeader("X-Request-ID", MediaIngestHttpTestFixture.requestId)
        .setBody(body)

    private fun problemResponse(
        status: Int,
        body: String,
        retryAfterSeconds: Int? = null,
        wwwAuthenticate: String? = null,
    ): MockResponse = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .addHeader("X-Correlation-ID", MediaIngestHttpTestFixture.correlationId.value)
        .addHeader("X-Request-ID", MediaIngestHttpTestFixture.requestId)
        .apply {
            retryAfterSeconds?.let { addHeader("Retry-After", it) }
            wwwAuthenticate?.let { addHeader("WWW-Authenticate", it) }
        }
        .setBody(body)

    private fun problemBody(
        status: Int,
        code: String,
        retryAfterSeconds: Int? = null,
    ): String = buildString {
        append("{\"type\":\"/problems/test\",\"title\":\"Test\",\"status\":")
        append(status)
        append(",\"code\":\"")
        append(code)
        append("\",\"detail\":\"Test\",\"traceId\":\"")
        append(MediaIngestHttpTestFixture.requestId)
        append('"')
        retryAfterSeconds?.let {
            append(",\"retryAfterSeconds\":")
            append(it)
        }
        append('}')
    }

    private fun assertSingleHeader(
        request: okhttp3.mockwebserver.RecordedRequest,
        name: String,
        expected: String,
    ) {
        assertEquals(listOf(expected), request.headers.values(name), name)
    }

    private fun assertFailureCode(result: MediaIngestUploadResult, expectedCode: String) {
        val failure = when (result) {
            is MediaIngestUploadResult.DurablyAcknowledged -> error("Unexpected durable acknowledgement")
            is MediaIngestUploadResult.NotAttempted -> result.failure
            is MediaIngestUploadResult.OutcomeUnknown -> result.failure
            is MediaIngestUploadResult.Rejected -> result.failure
        }
        assertEquals(expectedCode, failure.code.value)
    }
}

private data class ProblemCase(
    val status: Int,
    val problemCode: String,
    val expectedFailureCode: String,
    val wwwAuthenticate: String? = null,
)

private val DEFINITIVE_PUT_REJECTIONS = mapOf(
    400 to setOf("INVALID_REQUEST", "INVALID_SEQUENCE_RANGE"),
    401 to setOf(
        "AUTHENTICATION_REQUIRED",
        "INVALID_INGEST_CREDENTIAL",
        "INGEST_CREDENTIAL_EXPIRED",
    ),
    403 to setOf("SESSION_SCOPE_MISMATCH", "DEVICE_REVOKED", "CAPTURE_REVOKED"),
    404 to setOf("INGEST_SESSION_NOT_FOUND", "STREAM_NOT_FOUND"),
    409 to setOf(
        "CHUNK_DIGEST_CONFLICT",
        "CHUNK_METADATA_CONFLICT",
        "SEQUENCE_OVERLAP",
        "INGEST_SESSION_EXPIRED",
        "SESSION_ALREADY_FINALIZED",
    ),
    413 to setOf("CHUNK_TOO_LARGE"),
    422 to setOf("CONTENT_LENGTH_MISMATCH", "CONTENT_DIGEST_MISMATCH"),
    429 to setOf("RATE_LIMITED"),
).flatMap { (status, codes) ->
    codes.map { code -> ProblemCase(status, code, expectedFailureCode = code) }
}

private class CountingAuthorization : MediaIngestAuthorizationPort {
    var calls: Int = 0
        private set

    override suspend fun authorize(
        ingestSessionId: dev.gumi.edge.runtime.spool.IngestSessionId,
    ): MediaIngestAuthorizationResult {
        calls += 1
        assertEquals(MediaIngestHttpTestFixture.ingestSessionId, ingestSessionId)
        return MediaIngestAuthorizationResult.Authorized(
            AuthorizedMediaIngestRequest(
                credential = CaptureScopedIngestCredential.copyOf("capture-secret"),
                correlationId = MediaIngestHttpTestFixture.correlationId,
            ),
        )
    }
}
