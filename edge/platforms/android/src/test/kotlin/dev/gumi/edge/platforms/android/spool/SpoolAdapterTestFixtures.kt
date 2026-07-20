package dev.gumi.edge.platforms.android.spool

import dev.gumi.edge.runtime.spool.AppliedCloudAck
import dev.gumi.edge.runtime.spool.AudioCodecDescriptor
import dev.gumi.edge.runtime.spool.CaptureSessionId
import dev.gumi.edge.runtime.spool.CaptureSpoolState
import dev.gumi.edge.runtime.spool.ChunkDescriptor
import dev.gumi.edge.runtime.spool.ChunkId
import dev.gumi.edge.runtime.spool.CodecConfigurationId
import dev.gumi.edge.runtime.spool.DiscontinuityReason
import dev.gumi.edge.runtime.spool.DurableChunk
import dev.gumi.edge.runtime.spool.DurableChunkRecord
import dev.gumi.edge.runtime.spool.DurablePayloadRef
import dev.gumi.edge.runtime.spool.IngestSessionId
import dev.gumi.edge.runtime.spool.MediaPayloadFormat
import dev.gumi.edge.runtime.spool.OggOpusLayoutDescriptor
import dev.gumi.edge.runtime.spool.RemoteIngestState
import dev.gumi.edge.runtime.spool.SequencePolicy
import dev.gumi.edge.runtime.spool.SequenceRange
import dev.gumi.edge.runtime.spool.Sha256Digest
import dev.gumi.edge.runtime.spool.SourceDiscontinuity
import dev.gumi.edge.runtime.spool.SourceTimestamp
import dev.gumi.edge.runtime.spool.SpoolQuota
import dev.gumi.edge.runtime.spool.SpoolState
import dev.gumi.edge.runtime.spool.StreamDescriptor
import dev.gumi.edge.runtime.spool.StreamId
import dev.gumi.edge.runtime.spool.StreamSpoolState
import dev.gumi.edge.runtime.spool.UploadAttemptDisposition
import dev.gumi.edge.runtime.spool.UploadAttemptFence
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

internal val TEST_QUOTA = SpoolQuota(pressureAtBytes = 8_000uL, maximumRetainedBytes = 10_000uL)
internal val TEST_CAPTURE = CaptureSessionId(testUuid(1))
internal val TEST_STREAM = StreamId(testUuid(2))
internal val TEST_INGEST = IngestSessionId(testUuid(3))
internal val TEST_CODEC = AudioCodecDescriptor(
    configurationId = CodecConfigurationId("opus-16000-mono-20ms-v1"),
    sampleRateHz = 16_000u,
    channelCount = 1u,
    frameDurationUs = 20_000u,
)

internal fun testDescriptor(
    payload: ByteArray,
    index: Int = 1,
    sequence: ULong = index.toULong(),
): ChunkDescriptor = ChunkDescriptor(
    captureSessionId = TEST_CAPTURE,
    streamId = TEST_STREAM,
    chunkId = ChunkId(testUuid(10 + index)),
    sequenceRange = SequenceRange(sequence, sequence),
    payloadBytes = payload.size.toULong(),
    payloadFormat = MediaPayloadFormat.OGG_OPUS_PAGE_FRAGMENT_V1,
    contentDigest = PayloadIdentity.sha256(payload),
    codecConfigurationId = TEST_CODEC.configurationId,
    sourceStartedAt = SourceTimestamp("2026-07-19T20:00:01Z"),
    edgeReceivedAt = SourceTimestamp("2026-07-19T20:00:01.125Z"),
    sourceRetransmission = index % 2 == 0,
    sourceDiscontinuityBefore = if (index == 2) {
        SourceDiscontinuity(DiscontinuityReason.SOURCE_REPORTED_GAP, 3uL)
    } else {
        null
    },
)

internal fun richState(revision: ULong = 9uL): SpoolState {
    val firstDescriptor = testDescriptor(byteArrayOf(1, 2, 3), index = 1, sequence = 1uL)
    val secondDescriptor = testDescriptor(byteArrayOf(4, 5), index = 2, sequence = 2uL)
    val first = DurableChunkRecord(
        chunk = DurableChunk(firstDescriptor, DurablePayloadRef("gsp1_" + "A".repeat(43))),
        cloudAck = AppliedCloudAck(
            ingestSessionId = TEST_INGEST,
            descriptorDigest = Sha256Digest("sha256:" + "a".repeat(64)),
            stateRevision = 4uL,
        ),
    )
    val second = DurableChunkRecord(
        chunk = DurableChunk(secondDescriptor, DurablePayloadRef("gsp1_" + "B".repeat(43))),
        uploadAttempt = UploadAttemptFence(
            ingestSessionId = TEST_INGEST,
            descriptorDigest = Sha256Digest("sha256:" + "b".repeat(64)),
            disposition = UploadAttemptDisposition.OUTCOME_UNKNOWN,
        ),
    )
    val streamDescriptor = StreamDescriptor(
        captureSessionId = TEST_CAPTURE,
        streamId = TEST_STREAM,
        sequencePolicy = SequencePolicy(1uL, 100uL),
        maxChunkBytes = 1_000uL,
        maxTotalBytes = 5_000uL,
        payloadFormat = MediaPayloadFormat.OGG_OPUS_PAGE_FRAGMENT_V1,
        codec = TEST_CODEC,
        oggLayout = OggOpusLayoutDescriptor(
            serialNumber = UInt.MAX_VALUE,
            preSkip48kSamples = 312u,
        ),
    )
    val stream = StreamSpoolState(
        descriptor = streamDescriptor,
        chunks = linkedMapOf(
            secondDescriptor.chunkId to second,
            firstDescriptor.chunkId to first,
        ),
        sourceDurableThrough = 2uL,
        terminalRange = SequenceRange(1uL, 2uL),
    )
    return SpoolState(
        storeRevision = revision,
        quota = TEST_QUOTA,
        captures = mapOf(
            TEST_CAPTURE to CaptureSpoolState(
                captureSessionId = TEST_CAPTURE,
                streams = mapOf(TEST_STREAM to stream),
                remoteIngest = RemoteIngestState(TEST_INGEST, 4uL),
            ),
        ),
    )
}

internal class TestSpoolKeyring(
    activeVersion: Int = 1,
    private val encryptionSalt: Int = 0,
) : SpoolKeyring {
    private val encryptionKeys = linkedMapOf<Int, SecretKey>()
    private val locator = SecretKeySpec(ByteArray(32) { (it + 31).toByte() }, "HmacSHA256")

    override var activeEncryptionKeyVersion: Int = activeVersion
        private set

    init {
        add(activeVersion)
    }

    override fun encryptionKey(version: Int): SecretKey? = encryptionKeys[version]

    override fun locatorKey(): SecretKey = locator

    fun rotateTo(version: Int) {
        add(version)
        activeEncryptionKeyVersion = version
    }

    fun remove(version: Int) {
        encryptionKeys.remove(version)
    }

    private fun add(version: Int) {
        encryptionKeys.putIfAbsent(
            version,
            SecretKeySpec(
                ByteArray(32) { (it + version * 17 + encryptionSalt).toByte() },
                "AES",
            ),
        )
    }
}

internal fun testUuid(index: Int): String =
    "0190c6f0-7b21-7a40-8b11-${index.toString().padStart(12, '0')}"
