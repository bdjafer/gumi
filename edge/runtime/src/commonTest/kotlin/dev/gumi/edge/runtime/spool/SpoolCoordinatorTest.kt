package dev.gumi.edge.runtime.spool

import dev.gumi.edge.sdk.OpaqueBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpoolCoordinatorTest {
    @Test
    fun `source advance permit exists only after chunk and checkpoint commit together`() = runTest {
        val store = store()
        val coordinator = registeredCoordinator(store)

        val persisted = assertIs<SpoolResult.Applied<ChunkPersistenceReceipt>>(
            coordinator.persist(chunk(index = 1, first = 0uL, last = 0uL)),
        )

        assertEquals(0uL, persisted.value.sourceAdvancePermit?.durableThrough)
        val committed = store.committedStates.last()
        val stream = committed.stream()
        assertEquals(0uL, stream.sourceDurableThrough)
        assertEquals(1, stream.chunks.size)
    }

    @Test
    fun `failure before metadata commit cannot advance the physical source`() = runTest {
        val store = store()
        val coordinator = registeredCoordinator(store)
        store.nextCommitFault = InMemorySpoolStore.CommitFault.BEFORE_COMMIT

        val persisted = coordinator.persist(chunk(index = 1, first = 0uL, last = 0uL))

        assertIs<SpoolResult.Unavailable>(persisted)
        val recovery = recovery(coordinator)
        assertTrue(recovery.sourceAdvancePermits.isEmpty())
        assertEquals(0uL, recovery.retainedBytes)
    }

    @Test
    fun `lost response after commit reconciles exact durable identity before permitting advance`() =
        runTest {
            val store = store()
            val coordinator = registeredCoordinator(store)
            store.nextCommitFault = InMemorySpoolStore.CommitFault.AFTER_COMMIT_RESPONSE_LOST

            val result = coordinator.persist(chunk(index = 1, first = 0uL, last = 0uL))

            val reconciled = assertIs<SpoolResult.Duplicate<ChunkPersistenceReceipt>>(result)
            assertEquals(0uL, reconciled.value.sourceAdvancePermit?.durableThrough)
            assertEquals(1, store.snapshot().stream().chunks.size)
        }

    @Test
    fun `restart recovery reissues absolute idempotent source cursor from durable state`() = runTest {
        val store = store()
        val firstProcess = registeredCoordinator(store)
        firstProcess.persist(chunk(index = 1, first = 0uL, last = 0uL))

        val restarted = SpoolCoordinator(store, payloadStore())
        val recovery = recovery(restarted)

        assertEquals(
            listOf(SourceAdvancePermit(CAPTURE_ID, STREAM_ID, 0uL)),
            recovery.sourceAdvancePermits,
        )
        assertEquals(1uL, recovery.streams.single().durableChunkCount)
        assertEquals(0uL, recovery.streams.single().sourceDurableThrough)
        assertEquals(OGG_LAYOUT, recovery.streams.single().descriptor.oggLayout)
    }

    @Test
    fun `restart preserves Ogg layout and refuses stream identity rebound to different mux facts`() =
        runTest {
            val store = store()
            val firstProcess = SpoolCoordinator(store, payloadStore())
            assertIs<SpoolResult.Applied<Unit>>(firstProcess.registerStream(stream()))

            val restarted = SpoolCoordinator(store, payloadStore())
            assertEquals(OGG_LAYOUT, recovery(restarted).streams.single().descriptor.oggLayout)
            val conflicting = assertIs<SpoolResult.Rejected>(
                restarted.registerStream(
                    stream(layout = OGG_LAYOUT.copy(serialNumber = OGG_LAYOUT.serialNumber + 1u)),
                ),
            )

            assertEquals(
                "STREAM_ID_REUSED_WITH_DIFFERENT_DESCRIPTOR",
                assertIs<SpoolRejection.IdentityConflict>(conflicting.rejection).code,
            )
            assertEquals(OGG_LAYOUT, recovery(restarted).streams.single().descriptor.oggLayout)
        }

    @Test
    fun `out of order durable chunks advance only when the complete prefix closes`() = runTest {
        val coordinator = registeredCoordinator(store())

        val later = assertIs<SpoolResult.Applied<ChunkPersistenceReceipt>>(
            coordinator.persist(chunk(index = 2, first = 1uL, last = 1uL)),
        )
        assertNull(later.value.sourceAdvancePermit)

        val prefix = assertIs<SpoolResult.Applied<ChunkPersistenceReceipt>>(
            coordinator.persist(chunk(index = 1, first = 0uL, last = 0uL)),
        )
        assertEquals(1uL, prefix.value.sourceAdvancePermit?.durableThrough)
    }

    @Test
    fun `exact duplicate is idempotent while identity conflict and overlap are rejected`() = runTest {
        val coordinator = registeredCoordinator(store())
        val original = chunk(index = 1, first = 0uL, last = 0uL)
        coordinator.persist(original)

        assertIs<SpoolResult.Duplicate<ChunkPersistenceReceipt>>(
            coordinator.persist(original),
        )

        val conflictingIdentity = original.copy(
            descriptor = original.descriptor.copy(contentDigest = digest('b')),
        )
        val identityConflict = assertIs<SpoolResult.Rejected>(
            coordinator.persist(conflictingIdentity),
        )
        assertEquals(
            "CHUNK_ID_REUSED_WITH_DIFFERENT_CONTENT_OR_METADATA",
            assertIs<SpoolRejection.IdentityConflict>(identityConflict.rejection).code,
        )

        val overlap = assertIs<SpoolResult.Rejected>(
            coordinator.persist(chunk(index = 2, first = 0uL, last = 0uL)),
        )
        assertEquals(
            CHUNK_1,
            assertIs<SpoolRejection.SequenceOverlap>(overlap.rejection).existingChunkId,
        )
    }

    @Test
    fun `finalization requires exact contiguous terminal range and every durable cloud ack`() = runTest {
        val coordinator = registeredCoordinator(store())
        val first = chunk(index = 1, first = 0uL, last = 0uL)
        val second = chunk(index = 2, first = 2uL, last = 2uL)
        coordinator.persist(first)
        coordinator.persist(second)

        val sealed = assertIs<SpoolResult.Applied<FinalizationReadiness>>(
            coordinator.declareTerminalRange(CAPTURE_ID, STREAM_ID, SequenceRange(0uL, 2uL)),
        )
        assertEquals(
            listOf(SequenceRange(1uL, 1uL)),
            assertIs<FinalizationReadiness.MissingDurableRanges>(sealed.value).missing,
        )

        val middle = chunk(index = 3, first = 1uL, last = 1uL)
        coordinator.persist(middle)
        assertIs<FinalizationReadiness.AwaitingCloudDurability>(
            recovery(coordinator).streams.single().finalizationReadiness,
        )

        applyAck(coordinator, first, revision = 1uL, descriptorDigest = digest('1'))
        applyAck(coordinator, second, revision = 2uL, descriptorDigest = digest('2'))
        val ready = applyAck(
            coordinator,
            middle,
            revision = 3uL,
            descriptorDigest = digest('3'),
        )
        assertEquals(
            listOf(CHUNK_1, CHUNK_3, CHUNK_2),
            assertIs<FinalizationReadiness.Ready>(ready).orderedChunkIds,
        )
    }

    @Test
    fun `cloud ack binds exact range content and canonical descriptor digest`() = runTest {
        val coordinator = registeredCoordinator(store())
        val local = chunk(index = 1, first = 0uL, last = 0uL)
        coordinator.persist(local)
        val expectation = expectation(local, digest('d'))

        val wrongRange = ack(local, digest('d'), revision = 1uL).copy(
            acknowledgedSequenceRange = SequenceRange(1uL, 1uL),
        )
        val rangeMismatch = assertIs<SpoolResult.Rejected>(
            coordinator.applyDurableCloudAck(expectation, wrongRange),
        )
        assertEquals(
            "acknowledgedSequenceRange",
            assertIs<SpoolRejection.CloudAckMismatch>(rangeMismatch.rejection).field,
        )

        val wrongDescriptor = ack(local, digest('e'), revision = 1uL)
        val descriptorMismatch = assertIs<SpoolResult.Rejected>(
            coordinator.applyDurableCloudAck(expectation, wrongDescriptor),
        )
        assertEquals(
            "acknowledgedDescriptorDigest",
            assertIs<SpoolRejection.CloudAckMismatch>(descriptorMismatch.rejection).field,
        )

        assertIs<SpoolResult.Applied<FinalizationReadiness>>(
            coordinator.applyDurableCloudAck(expectation, ack(local, digest('d'), revision = 1uL)),
        )
    }

    @Test
    fun `older exact chunk ack remains durable without downgrading newer capture snapshot`() = runTest {
        val store = store()
        val coordinator = registeredCoordinator(store)
        val first = chunk(index = 1, first = 0uL, last = 0uL)
        val second = chunk(index = 2, first = 1uL, last = 1uL)
        coordinator.persist(first)
        coordinator.persist(second)

        applyAck(coordinator, second, revision = 2uL, descriptorDigest = digest('2'))
        val restarted = SpoolCoordinator(store, payloadStore())
        assertIs<SpoolResult.Applied<FinalizationReadiness>>(
            restarted.applyDurableCloudAck(
                expectation(first, digest('1')),
                ack(first, digest('1'), revision = 1uL),
            ),
        )

        val durable = store.snapshot().captures.getValue(CAPTURE_ID)
        assertEquals(2uL, durable.remoteIngest?.stateRevision)
        assertEquals(
            1uL,
            durable.streams.getValue(STREAM_ID).chunks.getValue(CHUNK_1).cloudAck?.stateRevision,
        )
        assertEquals(2uL, recovery(restarted).streams.single().cloudDurableChunkCount)
    }

    @Test
    fun `unknown ack commit reconciles after a concurrent newer chunk snapshot`() = runTest {
        val store = store()
        val coordinator = registeredCoordinator(store)
        val first = chunk(index = 1, first = 0uL, last = 0uL)
        val second = chunk(index = 2, first = 1uL, last = 1uL)
        coordinator.persist(first)
        coordinator.persist(second)
        store.nextCommitFault = InMemorySpoolStore.CommitFault.AFTER_COMMIT_RESPONSE_LOST
        store.afterUnknownCommit = { committedFirst ->
            committedFirst.withCloudAck(second, descriptorDigest = digest('2'), revision = 2uL)
        }

        val reconciled = assertIs<SpoolResult.Duplicate<FinalizationReadiness>>(
            coordinator.applyDurableCloudAck(
                expectation(first, digest('1')),
                ack(first, digest('1'), revision = 1uL),
            ),
        )
        assertIs<FinalizationReadiness.TerminalRangeNotDeclared>(reconciled.value)
        val durable = store.snapshot().captures.getValue(CAPTURE_ID)
        assertEquals(2uL, durable.remoteIngest?.stateRevision)
        assertEquals(
            1uL,
            durable.streams.getValue(STREAM_ID).chunks.getValue(CHUNK_1).cloudAck?.stateRevision,
        )
        assertEquals(
            2uL,
            durable.streams.getValue(STREAM_ID).chunks.getValue(CHUNK_2).cloudAck?.stateRevision,
        )
    }

    @Test
    fun `bounded quota exposes pressure and refuses bytes without mutating state`() = runTest {
        val store = store(quota = SpoolQuota(pressureAtBytes = 6uL, maximumRetainedBytes = 8uL))
        val coordinator = registeredCoordinator(store, maxChunkBytes = 8uL)

        val pressured = assertIs<SpoolResult.Applied<ChunkPersistenceReceipt>>(
            coordinator.persist(chunk(index = 1, first = 0uL, last = 0uL, bytes = 6uL)),
        )
        assertEquals(SpoolPressure.BACKPRESSURE, pressured.value.capacity.pressure)
        assertEquals(SpoolPressure.BACKPRESSURE, recovery(coordinator).pressure)

        val refused = assertIs<SpoolResult.Rejected>(
            coordinator.persist(chunk(index = 2, first = 1uL, last = 1uL, bytes = 3uL)),
        )
        val quota = assertIs<SpoolRejection.QuotaExceeded>(refused.rejection)
        assertEquals(6uL, quota.retainedBytes)
        assertEquals(8uL, quota.maximumRetainedBytes)
        assertEquals(6uL, recovery(coordinator).retainedBytes)

        coordinator.persist(chunk(index = 3, first = 1uL, last = 1uL, bytes = 2uL))
        val exhausted = recovery(coordinator)
        assertEquals(8uL, exhausted.retainedBytes)
        assertEquals(SpoolPressure.EXHAUSTED, exhausted.pressure)
    }

    @Test
    fun `source discontinuity survives restart but does not excuse a transfer gap`() = runTest {
        val store = store()
        val coordinator = registeredCoordinator(store)
        val discontinuous = chunk(index = 2, first = 1uL, last = 1uL).let { chunk ->
            chunk.copy(
                descriptor = chunk.descriptor.copy(
                    sourceDiscontinuityBefore = SourceDiscontinuity(
                        reason = DiscontinuityReason.DEVICE_RING_OVERWRITE,
                        droppedFrameCount = 44uL,
                    ),
                ),
            )
        }
        coordinator.persist(discontinuous)
        coordinator.declareTerminalRange(CAPTURE_ID, STREAM_ID, SequenceRange(0uL, 1uL))

        val restarted = recovery(SpoolCoordinator(store, payloadStore())).streams.single()
        assertEquals(1uL, restarted.discontinuityCount)
        assertEquals(
            listOf(SequenceRange(0uL, 0uL)),
            assertIs<FinalizationReadiness.MissingDurableRanges>(
                restarted.finalizationReadiness,
            ).missing,
        )
    }

    @Test
    fun `inclusive u64 maximum sequence never overflows prefix or missing range arithmetic`() =
        runTest {
            val stream = stream(
                policy = SequencePolicy(ULong.MAX_VALUE, ULong.MAX_VALUE),
                maxChunkBytes = 1uL,
            )
            val store = store()
            val coordinator = SpoolCoordinator(store, payloadStore())
            coordinator.registerStream(stream)
            val only = chunk(
                index = 1,
                first = ULong.MAX_VALUE,
                last = ULong.MAX_VALUE,
                bytes = 1uL,
            )

            val persisted = assertIs<SpoolResult.Applied<ChunkPersistenceReceipt>>(
                coordinator.persist(only),
            )
            assertEquals(ULong.MAX_VALUE, persisted.value.sourceAdvancePermit?.durableThrough)
            coordinator.declareTerminalRange(
                CAPTURE_ID,
                STREAM_ID,
                SequenceRange(ULong.MAX_VALUE, ULong.MAX_VALUE),
            )
            val readiness = applyAck(coordinator, only, 1uL, digest('f'))
            assertIs<FinalizationReadiness.Ready>(readiness)
        }

    @Test
    fun `stream and chunk descriptors must agree exactly`() = runTest {
        val coordinator = registeredCoordinator(store(), maxChunkBytes = 10uL)
        val tooLarge = chunk(index = 1, first = 0uL, last = 0uL, bytes = 11uL)

        val rejected = assertIs<SpoolResult.Rejected>(
            coordinator.persist(tooLarge),
        )

        assertEquals(
            "payloadBytes",
            assertIs<SpoolRejection.DescriptorMismatch>(rejected.rejection).field,
        )
    }

    @Test
    fun `durable payload location is redacted and raw bytes are outside the model`() {
        val durable = chunk(index = 1, first = 0uL, last = 0uL)

        assertEquals("<redacted-durable-payload-ref>", durable.payloadRef.toString())
        assertFalse(durable.toString().contains("test-encrypted-payload-1"))
    }

    @Test
    fun `callers cannot advance source truth without coordinator-owned payload durability`() =
        runTest {
            val metadata = store()
            val payloads = payloadStore().also {
                it.nextFailure = SpoolStoreFailure("TEST_PAYLOAD_FLUSH_FAILED", retryable = true)
            }
            val coordinator = registeredCoordinator(metadata, payloads)

            assertIs<SpoolResult.Unavailable>(
                coordinator.persist(chunk(index = 1, first = 0uL, last = 0uL)),
            )
            assertTrue(recovery(coordinator).sourceAdvancePermits.isEmpty())
            assertEquals(0uL, recovery(coordinator).retainedBytes)
            assertEquals(1, payloads.writeCount)
        }

    @Test
    fun `payload length is checked before invoking the durable payload adapter`() = runTest {
        val payloads = payloadStore()
        val coordinator = registeredCoordinator(store(), payloads)
        val descriptor = chunk(index = 1, first = 0uL, last = 0uL).descriptor

        val rejected = assertIs<SpoolResult.Rejected>(
            coordinator.persistChunk(descriptor, OpaqueBytes.copyOf(ByteArray(9))),
        )

        assertEquals(
            SpoolRejection.PayloadLengthMismatch(declaredBytes = 10uL, receivedBytes = 9uL),
            rejected.rejection,
        )
        assertEquals(0, payloads.writeCount)
    }

    @Test
    fun `unknown stream is refused before invoking the durable payload adapter`() = runTest {
        val payloads = payloadStore()
        val coordinator = registeredCoordinator(store(), payloads)
        val unknown = chunk(index = 1, first = 0uL, last = 0uL).let { chunk ->
            chunk.copy(descriptor = chunk.descriptor.copy(streamId = StreamId(uuid(99))))
        }

        assertIs<SpoolResult.Rejected>(coordinator.persist(unknown))
        assertEquals(0, payloads.writeCount)
    }

    @Test
    fun `stream total byte policy is independent from global spool quota`() = runTest {
        val coordinator = registeredCoordinator(
            store(quota = SpoolQuota(pressureAtBytes = 90uL, maximumRetainedBytes = 100uL)),
            maxChunkBytes = 8uL,
            maxTotalBytes = 10uL,
        )
        coordinator.persist(chunk(index = 1, first = 0uL, last = 0uL, bytes = 6uL))

        val rejected = assertIs<SpoolResult.Rejected>(
            coordinator.persist(chunk(index = 2, first = 1uL, last = 1uL, bytes = 5uL)),
        )

        assertEquals(
            "maxTotalBytes",
            assertIs<SpoolRejection.DescriptorMismatch>(rejected.rejection).field,
        )
        assertEquals(6uL, recovery(coordinator).retainedBytes)
    }

    @Test
    fun `source timestamps reject impossible calendar and offset values`() {
        assertFails { SourceTimestamp("2026-02-30T20:00:01Z") }
        assertFails { SourceTimestamp("2026-07-19T24:00:01Z") }
        assertFails { SourceTimestamp("2026-07-19T20:00:01+24:00") }
        assertFails { SourceTimestamp("2026-07-19T20:00:01Z\"") }
        assertFails { SourceTimestamp("2026-07-19T20:00:01Z\n") }
        assertFails { SourceTimestamp("２０２６-07-19T20:00:01Z") }
        assertEquals(
            "2024-02-29T20:00:01.123456789-05:30",
            SourceTimestamp("2024-02-29T20:00:01.123456789-05:30").value,
        )
    }

    @Test
    fun `M1 layout bounds page space and pre-skip`() {
        assertFails {
            OggOpusLayoutDescriptor(
                profile = "gumi.ogg-opus.unknown.v1",
                serialNumber = 7u,
                preSkip48kSamples = 312u,
            )
        }
        assertFails {
            OggOpusLayoutDescriptor(serialNumber = 7u, preSkip48kSamples = 65_536u)
        }
        assertFails {
            stream(policy = SequencePolicy(0uL, UInt.MAX_VALUE.toULong() - 1uL))
        }
    }
}

private suspend fun registeredCoordinator(
    store: InMemorySpoolStore,
    payloadStore: InMemoryDurablePayloadStore = payloadStore(),
    maxChunkBytes: ULong = 100uL,
    maxTotalBytes: ULong = 1_000uL,
): SpoolCoordinator = SpoolCoordinator(store, payloadStore).also { coordinator ->
    assertIs<SpoolResult.Applied<Unit>>(
        coordinator.registerStream(
            stream(maxChunkBytes = maxChunkBytes, maxTotalBytes = maxTotalBytes),
        ),
    )
}

private fun store(
    quota: SpoolQuota = SpoolQuota(pressureAtBytes = 800uL, maximumRetainedBytes = 1_000uL),
): InMemorySpoolStore = InMemorySpoolStore(SpoolState.empty(quota))

private fun stream(
    policy: SequencePolicy = SequencePolicy(0uL, 999_999uL),
    maxChunkBytes: ULong = 100uL,
    maxTotalBytes: ULong = 1_000uL,
    layout: OggOpusLayoutDescriptor = OGG_LAYOUT,
): StreamDescriptor = StreamDescriptor(
    captureSessionId = CAPTURE_ID,
    streamId = STREAM_ID,
    sequencePolicy = policy,
    maxChunkBytes = maxChunkBytes,
    maxTotalBytes = maxTotalBytes,
    payloadFormat = MediaPayloadFormat.OGG_OPUS_PAGE_FRAGMENT_V1,
    codec = CODEC,
    oggLayout = layout,
)

private fun chunk(
    index: Int,
    first: ULong,
    last: ULong,
    bytes: ULong = 10uL,
): DurableChunk {
    val chunkId = ChunkId(uuid(index + 10))
    return DurableChunk(
        descriptor = ChunkDescriptor(
            captureSessionId = CAPTURE_ID,
            streamId = STREAM_ID,
            chunkId = chunkId,
            sequenceRange = SequenceRange(first, last),
            payloadBytes = bytes,
            payloadFormat = MediaPayloadFormat.OGG_OPUS_PAGE_FRAGMENT_V1,
            contentDigest = digest(('a'.code + index - 1).toChar()),
            codecConfigurationId = CODEC.configurationId,
            sourceStartedAt = SourceTimestamp("2026-07-19T20:00:01Z"),
            edgeReceivedAt = SourceTimestamp("2026-07-19T20:00:01.125Z"),
            sourceRetransmission = false,
        ),
        payloadRef = DurablePayloadRef("test-encrypted-payload-$index"),
    )
}

private fun expectation(
    chunk: DurableChunk,
    descriptorDigest: Sha256Digest,
) = CloudAckExpectation(
    ingestSessionId = INGEST_ID,
    captureSessionId = chunk.descriptor.captureSessionId,
    streamId = chunk.descriptor.streamId,
    chunkId = chunk.descriptor.chunkId,
    descriptorDigest = descriptorDigest,
)

private fun ack(
    chunk: DurableChunk,
    descriptorDigest: Sha256Digest,
    revision: ULong,
) = DurableCloudAck(
    ingestSessionId = INGEST_ID,
    streamId = chunk.descriptor.streamId,
    acknowledgedChunkId = chunk.descriptor.chunkId,
    acknowledgedContentDigest = chunk.descriptor.contentDigest,
    acknowledgedDescriptorDigest = descriptorDigest,
    acknowledgedSequenceRange = chunk.descriptor.sequenceRange,
    disposition = CloudAckDisposition.STORED,
    stateRevision = revision,
)

private suspend fun applyAck(
    coordinator: SpoolCoordinator,
    chunk: DurableChunk,
    revision: ULong,
    descriptorDigest: Sha256Digest,
): FinalizationReadiness = assertIs<SpoolResult.Applied<FinalizationReadiness>>(
    coordinator.applyDurableCloudAck(
        expectation(chunk, descriptorDigest),
        ack(chunk, descriptorDigest, revision),
    ),
).value

private suspend fun recovery(coordinator: SpoolCoordinator): SpoolRecovery =
    assertIs<SpoolResult.Applied<SpoolRecovery>>(coordinator.recover()).value

private fun SpoolState.stream(): StreamSpoolState =
    captures.getValue(CAPTURE_ID).streams.getValue(STREAM_ID)

private fun digest(character: Char): Sha256Digest = Sha256Digest("sha256:" + character.toString().repeat(64))

private fun uuid(index: Int): String =
    "0190c6f0-7b21-7a40-8b11-${index.toString().padStart(12, '0')}"

private fun payloadStore(): InMemoryDurablePayloadStore = InMemoryDurablePayloadStore()

private suspend fun SpoolCoordinator.persist(chunk: DurableChunk): SpoolResult<ChunkPersistenceReceipt> {
    val byteCount = chunk.descriptor.payloadBytes.toInt()
    return persistChunk(chunk.descriptor, OpaqueBytes.copyOf(ByteArray(byteCount)))
}

private fun SpoolState.withCloudAck(
    chunk: DurableChunk,
    descriptorDigest: Sha256Digest,
    revision: ULong,
): SpoolState {
    val capture = captures.getValue(chunk.descriptor.captureSessionId)
    val stream = capture.streams.getValue(chunk.descriptor.streamId)
    val record = stream.chunks.getValue(chunk.descriptor.chunkId)
    val nextRecord = record.copy(
        cloudAck = AppliedCloudAck(
            ingestSessionId = INGEST_ID,
            descriptorDigest = descriptorDigest,
            stateRevision = revision,
        ),
    )
    val nextStream = stream.copy(chunks = stream.chunks + (chunk.descriptor.chunkId to nextRecord))
    val remoteRevision = maxOf(capture.remoteIngest?.stateRevision ?: 0uL, revision)
    val nextCapture = capture.copy(
        streams = capture.streams + (chunk.descriptor.streamId to nextStream),
        remoteIngest = RemoteIngestState(INGEST_ID, remoteRevision),
    )
    return copy(
        storeRevision = storeRevision + 1uL,
        captures = captures + (capture.captureSessionId to nextCapture),
    )
}

private val CAPTURE_ID = CaptureSessionId(uuid(1))
private val INGEST_ID = IngestSessionId(uuid(2))
private val STREAM_ID = StreamId(uuid(6))
private val CHUNK_1 = ChunkId(uuid(11))
private val CHUNK_2 = ChunkId(uuid(12))
private val CHUNK_3 = ChunkId(uuid(13))
private val CODEC = AudioCodecDescriptor(
    configurationId = CodecConfigurationId("opus-16000-mono-20ms-v1"),
    sampleRateHz = 16_000u,
    channelCount = 1u,
    frameDurationUs = 20_000u,
)
private val OGG_LAYOUT = OggOpusLayoutDescriptor(
    serialNumber = 3_548_421_033u,
    preSkip48kSamples = 312u,
)
