package dev.gumi.edge.runtime.ingest

import dev.gumi.edge.runtime.media.ogg.DeterministicOggOpusMuxer
import dev.gumi.edge.runtime.media.ogg.OggOpusMuxResult
import dev.gumi.edge.runtime.media.ogg.OggOpusStreamConfig
import dev.gumi.edge.runtime.media.ogg.SequencedRawOpusPacket
import dev.gumi.edge.runtime.spool.AudioCodecDescriptor
import dev.gumi.edge.runtime.spool.CaptureSessionId
import dev.gumi.edge.runtime.spool.ChunkDescriptor
import dev.gumi.edge.runtime.spool.ChunkId
import dev.gumi.edge.runtime.spool.CloudAckDisposition
import dev.gumi.edge.runtime.spool.CodecConfigurationId
import dev.gumi.edge.runtime.spool.DurableCloudAck
import dev.gumi.edge.runtime.spool.FinalizationReadiness
import dev.gumi.edge.runtime.spool.InMemoryDurablePayloadStore
import dev.gumi.edge.runtime.spool.InMemorySpoolStore
import dev.gumi.edge.runtime.spool.IngestSessionId
import dev.gumi.edge.runtime.spool.MediaPayloadFormat
import dev.gumi.edge.runtime.spool.OggOpusLayoutDescriptor
import dev.gumi.edge.runtime.spool.SequencePolicy
import dev.gumi.edge.runtime.spool.SequenceRange
import dev.gumi.edge.runtime.spool.Sha256Digest
import dev.gumi.edge.runtime.spool.SourceTimestamp
import dev.gumi.edge.runtime.spool.SpoolCoordinator
import dev.gumi.edge.runtime.spool.SpoolQuota
import dev.gumi.edge.runtime.spool.SpoolResult
import dev.gumi.edge.runtime.spool.SpoolState
import dev.gumi.edge.runtime.spool.StreamDescriptor
import dev.gumi.edge.runtime.spool.StreamId
import dev.gumi.edge.runtime.spool.UploadAttemptDisposition
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.OpaqueBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaUploadCoordinatorTest {
    @Test
    fun `selection is deterministic across streams and out-of-order local durability`() = runTest {
        val fixture = fixture(listOf(STREAM_B, STREAM_A))
        fixture.persist(descriptor(STREAM_B, index = 3, first = 0uL), byteArrayOf(3))
        fixture.persist(descriptor(STREAM_A, index = 2, first = 1uL), byteArrayOf(2))
        fixture.persist(descriptor(STREAM_A, index = 1, first = 0uL), byteArrayOf(1))

        repeat(3) {
            assertIs<MediaUploadStepResult.Acknowledged>(fixture.uploader.uploadNext(CAPTURE, INGEST))
        }

        assertEquals(
            listOf(
                STREAM_A to ChunkId(uuid(11)),
                STREAM_A to ChunkId(uuid(12)),
                STREAM_B to ChunkId(uuid(13)),
            ),
            fixture.port.uploads.map { it.prepared.descriptor.streamId to it.prepared.descriptor.chunkId },
        )
        assertIs<MediaUploadStepResult.NothingPending>(
            fixture.uploader.uploadNext(CAPTURE, INGEST),
        )
    }

    @Test
    fun `outcome unknown survives restart and cannot blind retry without exact authorization`() =
        runTest {
            val fixture = fixture(listOf(STREAM_A))
            fixture.persist(descriptor(STREAM_A, index = 1, first = 0uL), byteArrayOf(1))
            fixture.port.responder = { _, _, call ->
                if (call == 1) MediaIngestUploadResult.OutcomeUnknown(failure("TEST_TIMEOUT"))
                else null
            }

            assertIs<MediaUploadStepResult.OutcomeUnknown>(
                fixture.uploader.uploadNext(CAPTURE, INGEST),
            )
            val restarted = MediaUploadCoordinator(fixture.spool, fixture.payloads, fixture.port)
            val blocked = assertIs<MediaUploadStepResult.Blocked>(
                restarted.uploadNext(CAPTURE, INGEST),
            )

            assertEquals(1, fixture.port.uploads.size)
            assertEquals(UploadAttemptDisposition.OUTCOME_UNKNOWN, blocked.chunks.single().attempt.disposition)
            assertIs<SpoolResult.Applied<Unit>>(
                restarted.authorizeRetry(blocked.chunks.single().expectation),
            )
            assertIs<MediaUploadStepResult.Acknowledged>(restarted.uploadNext(CAPTURE, INGEST))
            assertEquals(2, fixture.port.uploads.size)
        }

    @Test
    fun `authorized retry retains the original canonical descriptor identity`() = runTest {
        val fixture = fixture(listOf(STREAM_A))
        fixture.persist(descriptor(STREAM_A, index = 1, first = 0uL), byteArrayOf(1))
        fixture.port.responder = { _, _, _ ->
            MediaIngestUploadResult.OutcomeUnknown(failure("TEST_TIMEOUT"))
        }
        assertIs<MediaUploadStepResult.OutcomeUnknown>(fixture.uploader.uploadNext(CAPTURE, INGEST))
        val blocked = assertIs<MediaUploadStepResult.Blocked>(
            fixture.uploader.uploadNext(CAPTURE, INGEST),
        ).chunks.single()
        assertIs<SpoolResult.Applied<Unit>>(fixture.uploader.authorizeRetry(blocked.expectation))

        fixture.port.descriptorDigest = digest('e')
        val changed = assertIs<MediaUploadStepResult.SpoolRejected>(
            fixture.uploader.uploadNext(CAPTURE, INGEST),
        )
        assertEquals(
            "descriptorDigest",
            assertIs<dev.gumi.edge.runtime.spool.SpoolRejection.CloudAckMismatch>(
                changed.rejection,
            ).field,
        )
        assertEquals(1, fixture.port.uploads.size)

        fixture.port.descriptorDigest = digest('d')
        fixture.port.responder = { _, _, _ -> null }
        assertIs<MediaUploadStepResult.Acknowledged>(fixture.uploader.uploadNext(CAPTURE, INGEST))
        assertEquals(2, fixture.port.uploads.size)
    }

    @Test
    fun `lost response while fencing an attempt never permits an unfenced external call`() = runTest {
        val fixture = fixture(listOf(STREAM_A))
        fixture.persist(descriptor(STREAM_A, index = 1, first = 0uL), byteArrayOf(1))
        assertIs<SpoolResult.Applied<Unit>>(fixture.spool.bindIngestSession(CAPTURE, INGEST))
        fixture.metadata.nextCommitFault =
            InMemorySpoolStore.CommitFault.AFTER_COMMIT_RESPONSE_LOST

        assertIs<MediaUploadStepResult.SelectionChanged>(
            fixture.uploader.uploadNext(CAPTURE, INGEST),
        )
        val blocked = assertIs<MediaUploadStepResult.Blocked>(
            MediaUploadCoordinator(fixture.spool, fixture.payloads, fixture.port)
                .uploadNext(CAPTURE, INGEST),
        )

        assertTrue(fixture.port.uploads.isEmpty())
        assertEquals(UploadAttemptDisposition.OUTCOME_UNKNOWN, blocked.chunks.single().attempt.disposition)
    }

    @Test
    fun `definitive rejection is durable and remains non-retryable by the scheduler`() = runTest {
        val fixture = fixture(listOf(STREAM_A))
        fixture.persist(descriptor(STREAM_A, index = 1, first = 0uL), byteArrayOf(1))
        fixture.port.responder = { _, _, _ ->
            MediaIngestUploadResult.Rejected(failure("TEST_POLICY_REJECTED", retryable = false))
        }

        assertIs<MediaUploadStepResult.Rejected>(fixture.uploader.uploadNext(CAPTURE, INGEST))
        val blocked = assertIs<MediaUploadStepResult.Blocked>(
            fixture.uploader.uploadNext(CAPTURE, INGEST),
        )

        assertEquals(1, fixture.port.uploads.size)
        assertEquals(UploadAttemptDisposition.REJECTED, blocked.chunks.single().attempt.disposition)
    }

    @Test
    fun `not attempted response safely releases fence for a later scheduler pass`() = runTest {
        val fixture = fixture(listOf(STREAM_A))
        fixture.persist(descriptor(STREAM_A, index = 1, first = 0uL), byteArrayOf(1))
        fixture.port.responder = { _, _, call ->
            if (call == 1) MediaIngestUploadResult.NotAttempted(failure("TEST_OFFLINE"))
            else null
        }

        assertIs<MediaUploadStepResult.NotAttempted>(fixture.uploader.uploadNext(CAPTURE, INGEST))
        assertIs<MediaUploadStepResult.Acknowledged>(fixture.uploader.uploadNext(CAPTURE, INGEST))
        assertEquals(2, fixture.port.uploads.size)
    }

    @Test
    fun `payload integrity read must succeed before an external attempt is fenced or sent`() = runTest {
        val fixture = fixture(listOf(STREAM_A))
        fixture.persist(descriptor(STREAM_A, index = 1, first = 0uL), byteArrayOf(1))
        fixture.payloads.nextReadFailure = dev.gumi.edge.runtime.spool.SpoolStoreFailure(
            "TEST_PAYLOAD_CORRUPT",
            retryable = false,
        )

        assertIs<MediaUploadStepResult.PayloadUnavailable>(
            fixture.uploader.uploadNext(CAPTURE, INGEST),
        )
        assertTrue(fixture.port.uploads.isEmpty())
        assertIs<MediaUploadStepResult.Acknowledged>(
            fixture.uploader.uploadNext(CAPTURE, INGEST),
        )
    }

    @Test
    fun `ACK must repeat the exact prepared canonical descriptor digest`() = runTest {
        val fixture = fixture(listOf(STREAM_A))
        fixture.persist(descriptor(STREAM_A, index = 1, first = 0uL), byteArrayOf(1))
        fixture.port.responder = { prepared, _, call ->
            fixture.port.ack(prepared, call).let {
                MediaIngestUploadResult.DurablyAcknowledged(
                    it.copy(acknowledgedDescriptorDigest = digest('e')),
                )
            }
        }

        val mismatch = assertIs<MediaUploadStepResult.SpoolRejected>(
            fixture.uploader.uploadNext(CAPTURE, INGEST),
        )
        assertEquals("acknowledgedDescriptorDigest", mismatch.rejection.let {
            assertIs<dev.gumi.edge.runtime.spool.SpoolRejection.CloudAckMismatch>(it).field
        })
        assertIs<MediaUploadStepResult.Blocked>(fixture.uploader.uploadNext(CAPTURE, INGEST))
        assertEquals(1, fixture.port.uploads.size)
    }

    @Test
    fun `concurrent scheduler calls never overlap external upload attempts`() = runTest {
        val fixture = fixture(listOf(STREAM_A))
        fixture.persist(descriptor(STREAM_A, index = 1, first = 0uL), byteArrayOf(1))
        fixture.persist(descriptor(STREAM_A, index = 2, first = 1uL), byteArrayOf(2))
        fixture.port.delayMillis = 10

        val first = async { fixture.uploader.uploadNext(CAPTURE, INGEST) }
        val second = async { fixture.uploader.uploadNext(CAPTURE, INGEST) }

        assertIs<MediaUploadStepResult.Acknowledged>(first.await())
        assertIs<MediaUploadStepResult.Acknowledged>(second.await())
        assertEquals(1, fixture.port.maximumActiveUploads)
        assertEquals(2, fixture.port.uploads.size)
    }

    @Test
    fun `deterministic mux bytes cross spool and fake ingest to finalization readiness`() = runTest {
        val muxer = DeterministicOggOpusMuxer(
            OggOpusStreamConfig(
                configurationId = CODEC.configurationId.value,
                serialNumber = OGG_LAYOUT.serialNumber,
                firstAudioSequence = 10uL,
                channelCount = CODEC.channelCount,
                inputSampleRateHz = CODEC.sampleRateHz,
                preSkip48kSamples = OGG_LAYOUT.preSkip48kSamples,
                expectedFrameDurationUs = CODEC.frameDurationUs,
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
        val fragment = accepted(muxer.finish())
        val bytes = fragment.bytes.copyBytes()
        val fixture = fixture(
            listOf(STREAM_A),
            policy = SequencePolicy(10uL, 10uL),
            maxChunkBytes = bytes.size.toULong(),
        )
        fixture.port.expectedPayload = bytes
        fixture.persist(
            descriptor(
                streamId = STREAM_A,
                index = 1,
                first = fragment.audioSequenceRange.first,
                last = fragment.audioSequenceRange.last,
                payloadBytes = bytes.size.toULong(),
                contentDigest = Sha256Digest(
                    "sha256:9746a9c6ca5f4badd049b2faaaf43bdf760dc80dcd8228d829ff56e8d97e15ed",
                ),
            ),
            bytes,
        )
        fixture.spool.declareTerminalRange(CAPTURE, STREAM_A, SequenceRange(10uL, 10uL))

        val uploaded = assertIs<MediaUploadStepResult.Acknowledged>(
            fixture.uploader.uploadNext(CAPTURE, INGEST),
        )

        assertIs<FinalizationReadiness.Ready>(uploaded.finalizationReadiness)
        assertContentEquals(bytes, fixture.port.uploads.single().payload.copyBytes())
        assertTrue(fragment.beginsLogicalStream)
        assertTrue(fragment.endsLogicalStream)
    }
}

private data class UploadCall(
    val prepared: PreparedMediaChunkUpload,
    val payload: OpaqueBytes,
)

private class FakeMediaIngestPort : MediaIngestPort {
    var responder: suspend (
        PreparedMediaChunkUpload,
        OpaqueBytes,
        Int,
    ) -> MediaIngestUploadResult? = { _, _, _ -> null }
    var delayMillis: Long = 0
    var expectedPayload: ByteArray? = null
    var descriptorDigest: Sha256Digest = digest('d')
    val uploads = mutableListOf<UploadCall>()
    var maximumActiveUploads: Int = 0
        private set
    private var activeUploads: Int = 0

    override fun prepareChunk(
        ingestSessionId: IngestSessionId,
        descriptor: ChunkDescriptor,
    ): MediaIngestPreparationResult = MediaIngestPreparationResult.Prepared(
        PreparedMediaChunkUpload(ingestSessionId, descriptor, descriptorDigest),
    )

    override suspend fun uploadChunk(
        prepared: PreparedMediaChunkUpload,
        payload: OpaqueBytes,
    ): MediaIngestUploadResult {
        activeUploads += 1
        maximumActiveUploads = maxOf(maximumActiveUploads, activeUploads)
        try {
            if (delayMillis > 0) delay(delayMillis)
            expectedPayload?.let { assertContentEquals(it, payload.copyBytes()) }
            uploads += UploadCall(prepared, payload)
            val call = uploads.size
            return responder(prepared, payload, call)
                ?: MediaIngestUploadResult.DurablyAcknowledged(ack(prepared, call))
        } finally {
            activeUploads -= 1
        }
    }

    fun ack(prepared: PreparedMediaChunkUpload, call: Int): DurableCloudAck = DurableCloudAck(
        ingestSessionId = prepared.ingestSessionId,
        streamId = prepared.descriptor.streamId,
        acknowledgedChunkId = prepared.descriptor.chunkId,
        acknowledgedContentDigest = prepared.descriptor.contentDigest,
        acknowledgedDescriptorDigest = prepared.canonicalDescriptorDigest,
        acknowledgedSequenceRange = prepared.descriptor.sequenceRange,
        disposition = CloudAckDisposition.STORED,
        stateRevision = call.toULong(),
    )
}

private data class UploadFixture(
    val metadata: InMemorySpoolStore,
    val payloads: InMemoryDurablePayloadStore,
    val spool: SpoolCoordinator,
    val port: FakeMediaIngestPort,
    val uploader: MediaUploadCoordinator,
) {
    suspend fun persist(descriptor: ChunkDescriptor, bytes: ByteArray) {
        assertIs<SpoolResult.Applied<*>>(
            spool.persistChunk(descriptor, OpaqueBytes.copyOf(bytes)),
        )
    }
}

private suspend fun fixture(
    streamIds: List<StreamId>,
    policy: SequencePolicy = SequencePolicy(0uL, 100uL),
    maxChunkBytes: ULong = 100uL,
): UploadFixture {
    val metadata = InMemorySpoolStore(
        SpoolState.empty(SpoolQuota(pressureAtBytes = 800uL, maximumRetainedBytes = 1_000uL)),
    )
    val payloads = InMemoryDurablePayloadStore()
    val spool = SpoolCoordinator(metadata, payloads)
    for (streamId in streamIds) {
        assertIs<SpoolResult.Applied<Unit>>(
            spool.registerStream(
                StreamDescriptor(
                    captureSessionId = CAPTURE,
                    streamId = streamId,
                    sequencePolicy = policy,
                    maxChunkBytes = maxChunkBytes,
                    maxTotalBytes = 1_000uL,
                    payloadFormat = MediaPayloadFormat.OGG_OPUS_PAGE_FRAGMENT_V1,
                    codec = CODEC,
                    oggLayout = OGG_LAYOUT,
                ),
            ),
        )
    }
    val port = FakeMediaIngestPort()
    return UploadFixture(
        metadata,
        payloads,
        spool,
        port,
        MediaUploadCoordinator(spool, payloads, port),
    )
}

private fun descriptor(
    streamId: StreamId,
    index: Int,
    first: ULong,
    last: ULong = first,
    payloadBytes: ULong = 1uL,
    contentDigest: Sha256Digest = digest(('a'.code + index - 1).toChar()),
): ChunkDescriptor = ChunkDescriptor(
    captureSessionId = CAPTURE,
    streamId = streamId,
    chunkId = ChunkId(uuid(index + 10)),
    sequenceRange = SequenceRange(first, last),
    payloadBytes = payloadBytes,
    payloadFormat = MediaPayloadFormat.OGG_OPUS_PAGE_FRAGMENT_V1,
    contentDigest = contentDigest,
    codecConfigurationId = CODEC.configurationId,
    sourceStartedAt = SourceTimestamp("2026-07-19T20:00:01Z"),
    edgeReceivedAt = SourceTimestamp("2026-07-19T20:00:01.125Z"),
    sourceRetransmission = false,
)

private fun failure(code: String, retryable: Boolean = true): ExpectedFailure = ExpectedFailure(
    category = FailureCategory.UNAVAILABLE,
    code = FailureCode(code),
    retryable = retryable,
)

private fun digest(character: Char): Sha256Digest =
    Sha256Digest("sha256:" + character.toString().repeat(64))

private fun uuid(index: Int): String =
    "0190c6f0-7b21-7a40-8b11-${index.toString().padStart(12, '0')}"

private fun <T> accepted(result: OggOpusMuxResult<T>): T = when (result) {
    is OggOpusMuxResult.Accepted -> result.value
    is OggOpusMuxResult.Rejected -> error("Expected accepted result, got ${result.failure}")
}

private val CAPTURE = CaptureSessionId(uuid(1))
private val INGEST = IngestSessionId(uuid(2))
private val STREAM_A = StreamId(uuid(3))
private val STREAM_B = StreamId(uuid(4))
private val CODEC = AudioCodecDescriptor(
    configurationId = CodecConfigurationId("opus-16000-mono-20ms-v1"),
    sampleRateHz = 16_000u,
    channelCount = 1u,
    frameDurationUs = 20_000u,
)
private val OGG_LAYOUT = OggOpusLayoutDescriptor(
    serialNumber = 0x0102_0304u,
    preSkip48kSamples = 312u,
)
