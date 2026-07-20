package dev.gumi.edge.runtime.spool

import dev.gumi.edge.runtime.media.ogg.M1_SINGLE_PACKET_PAGE_PROFILE

/** Stable semantic identity for one capture lifecycle. */
@JvmInline
value class CaptureSessionId(val value: String) {
    init {
        requireStableMediaId("Capture session ID", value)
    }

    override fun toString(): String = value
}

/** Stable identity for one encoded media stream within a capture. */
@JvmInline
value class StreamId(val value: String) {
    init {
        requireStableMediaId("Stream ID", value)
    }

    override fun toString(): String = value
}

/** Stable idempotency identity for one durable chunk. */
@JvmInline
value class ChunkId(val value: String) {
    init {
        requireStableMediaId("Chunk ID", value)
    }

    override fun toString(): String = value
}

/** Opaque media-ingest session identity. It is not a credential or storage address. */
@JvmInline
value class IngestSessionId(val value: String) {
    init {
        requireStableMediaId("Ingest session ID", value)
    }

    override fun toString(): String = value
}

/** SHA-256 encoded exactly as the media-ingest contract requires. */
@JvmInline
value class Sha256Digest(val value: String) {
    init {
        require(value.matches(Regex("sha256:[0-9a-f]{64}"))) {
            "SHA-256 digest must be lowercase hexadecimal with a sha256: prefix"
        }
    }

    override fun toString(): String = value
}

/**
 * A durable adapter's opaque reference to encrypted bytes.
 *
 * Runtime state may compare and persist this value, but diagnostics must never render it because a
 * concrete adapter may encode storage topology in the reference.
 */
@JvmInline
value class DurablePayloadRef(val value: String) {
    init {
        require(value.isNotBlank()) { "Durable payload reference cannot be blank" }
        require(value == value.trim()) {
            "Durable payload reference cannot have leading or trailing whitespace"
        }
        require(value.length <= 512) { "Durable payload reference cannot exceed 512 characters" }
        require(value.none(Char::isISOControl)) {
            "Durable payload reference cannot contain control characters"
        }
    }

    override fun toString(): String = "<redacted-durable-payload-ref>"
}

/** Inclusive unsigned 64-bit sequence range. */
data class SequenceRange(
    val first: ULong,
    val last: ULong,
) {
    init {
        require(first <= last) { "Sequence range must be non-empty and ordered" }
    }

    fun contains(other: SequenceRange): Boolean =
        first <= other.first && other.last <= last

    fun overlaps(other: SequenceRange): Boolean =
        first <= other.last && other.first <= last
}

data class SequencePolicy(
    val first: ULong,
    val maximumLast: ULong,
) {
    init {
        require(first <= maximumLast) { "Sequence policy must be non-empty and ordered" }
    }

    val allowedRange: SequenceRange = SequenceRange(first, maximumLast)
}

@JvmInline
value class CodecConfigurationId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) {
            "Codec configuration ID must be a stable lowercase identifier"
        }
    }

    override fun toString(): String = value
}

data class AudioCodecDescriptor(
    val name: String = "opus",
    val configurationId: CodecConfigurationId,
    val sampleRateHz: UInt,
    val channelCount: UInt,
    val frameDurationUs: UInt,
) {
    init {
        require(name == "opus") { "M1 spool supports the versioned Opus profile only" }
        require(sampleRateHz in 8_000u..48_000u) { "Opus sample rate is outside the M1 policy" }
        require(channelCount in 1u..2u) { "Opus channel count is outside the M1 policy" }
        require(frameDurationUs in OPUS_FRAME_DURATIONS_US) {
            "Opus frame duration is outside the M1 policy"
        }
    }
}

/** Durable session facts required to reproduce and independently validate the M1 Ogg layout. */
data class OggOpusLayoutDescriptor(
    val profile: String = M1_SINGLE_PACKET_PAGE_PROFILE,
    val serialNumber: UInt,
    val preSkip48kSamples: UInt,
) {
    init {
        require(profile == M1_SINGLE_PACKET_PAGE_PROFILE) {
            "M1 spool supports the single-packet-page Ogg layout only"
        }
        require(preSkip48kSamples <= UShort.MAX_VALUE.toUInt()) {
            "Opus pre-skip must fit the identification header"
        }
    }
}

enum class MediaPayloadFormat(val wireValue: String) {
    OGG_OPUS_PAGE_FRAGMENT_V1("ogg-opus-page-fragment-v1"),
}

/** RFC 3339 source evidence. Ordering and identity never depend on this wall-clock value. */
@JvmInline
value class SourceTimestamp(val value: String) {
    init {
        require(value.length in 20..64 && value.contains('T') && hasExplicitOffset(value)) {
            "Source timestamp must be a bounded RFC 3339 value with an explicit offset"
        }
    }

    override fun toString(): String = value
}

enum class DiscontinuityReason(val wireValue: String) {
    DEVICE_RING_OVERWRITE("device-ring-overwrite"),
    SOURCE_RESTART("source-restart"),
    SOURCE_REPORTED_GAP("source-reported-gap"),
    EDGE_SPOOL_LOSS("edge-spool-loss"),
    UNKNOWN("unknown"),
}

/** Physical-source loss before a transferred chunk. It never excuses a transfer sequence gap. */
data class SourceDiscontinuity(
    val reason: DiscontinuityReason,
    val droppedFrameCount: ULong,
)

data class StreamDescriptor(
    val captureSessionId: CaptureSessionId,
    val streamId: StreamId,
    val sequencePolicy: SequencePolicy,
    val maxChunkBytes: ULong,
    val maxTotalBytes: ULong,
    val payloadFormat: MediaPayloadFormat,
    val codec: AudioCodecDescriptor,
    val oggLayout: OggOpusLayoutDescriptor,
) {
    init {
        require(maxChunkBytes > 0uL) { "Maximum chunk size must be positive" }
        require(maxTotalBytes > 0uL) { "Maximum stream size must be positive" }
        require(maxChunkBytes <= maxTotalBytes) {
            "Maximum chunk size cannot exceed maximum stream size"
        }
        require(sequencePolicy.maximumLast - sequencePolicy.first <= UInt.MAX_VALUE.toULong() - 2uL) {
            "Sequence policy cannot map to the deterministic Ogg page-sequence space"
        }
    }
}

/**
 * Immutable metadata for one body. It intentionally contains no media bytes, credential, URL, or
 * provider storage key.
 */
data class ChunkDescriptor(
    val captureSessionId: CaptureSessionId,
    val streamId: StreamId,
    val chunkId: ChunkId,
    val sequenceRange: SequenceRange,
    val payloadBytes: ULong,
    val payloadFormat: MediaPayloadFormat,
    val contentDigest: Sha256Digest,
    val codecConfigurationId: CodecConfigurationId,
    val sourceStartedAt: SourceTimestamp,
    val edgeReceivedAt: SourceTimestamp? = null,
    val sourceRetransmission: Boolean,
    val sourceDiscontinuityBefore: SourceDiscontinuity? = null,
) {
    init {
        require(payloadBytes > 0uL) { "Chunk payload length must be positive" }
    }
}

/** Proof-shaped value issued only after an encrypted payload was written and durably flushed. */
data class DurableChunk(
    val descriptor: ChunkDescriptor,
    val payloadRef: DurablePayloadRef,
)

data class SpoolQuota(
    val pressureAtBytes: ULong,
    val maximumRetainedBytes: ULong,
) {
    init {
        require(maximumRetainedBytes > 0uL) { "Spool quota must be positive" }
        require(pressureAtBytes in 1uL..maximumRetainedBytes) {
            "Pressure threshold must be positive and no greater than the spool quota"
        }
    }
}

enum class SpoolPressure {
    ACCEPTING,
    BACKPRESSURE,
    EXHAUSTED,
}

data class SourceAdvancePermit(
    val captureSessionId: CaptureSessionId,
    val streamId: StreamId,
    /** Absolute inclusive cursor. Applying the same permit repeatedly must be harmless. */
    val durableThrough: ULong,
)

data class SpoolCapacity(
    val retainedBytes: ULong,
    val maximumRetainedBytes: ULong,
    val pressure: SpoolPressure,
) {
    val availableBytes: ULong = maximumRetainedBytes - retainedBytes
}

data class ChunkPersistenceReceipt(
    val sourceAdvancePermit: SourceAdvancePermit?,
    val capacity: SpoolCapacity,
)

enum class CloudAckDisposition {
    STORED,
    DUPLICATE,
}

/** Exact upload-attempt binding computed from the canonical provider descriptor. */
data class CloudAckExpectation(
    val ingestSessionId: IngestSessionId,
    val captureSessionId: CaptureSessionId,
    val streamId: StreamId,
    val chunkId: ChunkId,
    val descriptorDigest: Sha256Digest,
)

/** Provider-independent normalized durable acknowledgement. */
data class DurableCloudAck(
    val ingestSessionId: IngestSessionId,
    val streamId: StreamId,
    val acknowledgedChunkId: ChunkId,
    val acknowledgedContentDigest: Sha256Digest,
    val acknowledgedDescriptorDigest: Sha256Digest,
    val acknowledgedSequenceRange: SequenceRange,
    val disposition: CloudAckDisposition,
    val stateRevision: ULong,
)

data class AppliedCloudAck(
    val ingestSessionId: IngestSessionId,
    val descriptorDigest: Sha256Digest,
    val stateRevision: ULong,
)

/**
 * Durable retry fence written before the publisher may observe an upload attempt.
 *
 * [OUTCOME_UNKNOWN] is deliberately written before crossing the external boundary: process death
 * can never turn an in-flight request into an automatic retry. [REJECTED] records a definitive
 * publisher refusal. Both require an explicit retry authorization before this chunk is selectable
 * again. [RETRY_AUTHORIZED] preserves that exact identity while permitting one new external attempt;
 * it is never equivalent to forgetting the previous fence.
 */
enum class UploadAttemptDisposition {
    OUTCOME_UNKNOWN,
    REJECTED,
    RETRY_AUTHORIZED,
}

data class UploadAttemptFence(
    val ingestSessionId: IngestSessionId,
    val descriptorDigest: Sha256Digest,
    val disposition: UploadAttemptDisposition,
)

data class DurableChunkRecord(
    val chunk: DurableChunk,
    val cloudAck: AppliedCloudAck? = null,
    val uploadAttempt: UploadAttemptFence? = null,
) {
    init {
        require(cloudAck == null || uploadAttempt == null) {
            "A cloud-durable chunk cannot retain an unresolved upload attempt"
        }
    }
}

data class PendingChunkUpload(
    val ingestSessionId: IngestSessionId,
    val chunk: DurableChunk,
)

data class BlockedChunkUpload(
    val captureSessionId: CaptureSessionId,
    val streamId: StreamId,
    val chunkId: ChunkId,
    val attempt: UploadAttemptFence,
) {
    val expectation: CloudAckExpectation = CloudAckExpectation(
        ingestSessionId = attempt.ingestSessionId,
        captureSessionId = captureSessionId,
        streamId = streamId,
        chunkId = chunkId,
        descriptorDigest = attempt.descriptorDigest,
    )
}

sealed interface PendingUploadSelection {
    data class Ready(val upload: PendingChunkUpload) : PendingUploadSelection

    /** Every locally durable, unacknowledged chunk is fenced pending reconciliation or intent. */
    data class Blocked(val chunks: List<BlockedChunkUpload>) : PendingUploadSelection

    data object Empty : PendingUploadSelection
}

data class RemoteIngestState(
    val ingestSessionId: IngestSessionId,
    val stateRevision: ULong,
)

data class StreamSpoolState(
    val descriptor: StreamDescriptor,
    val chunks: Map<ChunkId, DurableChunkRecord> = emptyMap(),
    val sourceDurableThrough: ULong? = null,
    val terminalRange: SequenceRange? = null,
) {
    init {
        require(chunks.all { (chunkId, record) ->
            chunkId == record.chunk.descriptor.chunkId &&
                record.chunk.descriptor.captureSessionId == descriptor.captureSessionId &&
                record.chunk.descriptor.streamId == descriptor.streamId &&
                descriptor.sequencePolicy.allowedRange.contains(record.chunk.descriptor.sequenceRange) &&
                record.chunk.descriptor.payloadBytes <= descriptor.maxChunkBytes &&
                record.chunk.descriptor.payloadFormat == descriptor.payloadFormat &&
                record.chunk.descriptor.codecConfigurationId == descriptor.codec.configurationId
        }) { "Persisted chunk metadata must agree with its stream descriptor" }
        require(chunks.values.fold(0uL) { total, record ->
            checkedAdd(total, record.chunk.descriptor.payloadBytes)
        } <= descriptor.maxTotalBytes) {
            "Persisted chunks exceed the stream's authorized total-byte limit"
        }
        require(noOverlappingChunkRanges(chunks.values)) {
            "Distinct durable chunks cannot overlap sequence ranges"
        }
        require(sourceDurableThrough == contiguousDurableThrough(descriptor, chunks.values)) {
            "Persisted source checkpoint must equal the complete local durable prefix"
        }
        terminalRange?.let { terminal ->
            require(terminal.first == descriptor.sequencePolicy.first) {
                "Terminal range must start at the stream policy's first sequence"
            }
            require(descriptor.sequencePolicy.allowedRange.contains(terminal)) {
                "Terminal range must remain inside stream policy"
            }
            require(chunks.values.all { terminal.contains(it.chunk.descriptor.sequenceRange) }) {
                "A sealed stream cannot contain durable chunks beyond its terminal range"
            }
        }
    }
}

data class CaptureSpoolState(
    val captureSessionId: CaptureSessionId,
    val streams: Map<StreamId, StreamSpoolState> = emptyMap(),
    val remoteIngest: RemoteIngestState? = null,
) {
    init {
        require(streams.all { (streamId, state) ->
            streamId == state.descriptor.streamId &&
                state.descriptor.captureSessionId == captureSessionId
        }) { "Persisted stream identity must agree with its capture" }
        val appliedAcks = streams.values
            .flatMap { it.chunks.values }
            .mapNotNull { it.cloudAck }
        val uploadAttempts = streams.values
            .flatMap { it.chunks.values }
            .mapNotNull { it.uploadAttempt }
        require(appliedAcks.isEmpty() && uploadAttempts.isEmpty() || remoteIngest != null) {
            "Cloud acknowledgements and upload attempts require a capture-level ingest binding"
        }
        remoteIngest?.let { remote ->
            require(appliedAcks.all {
                it.ingestSessionId == remote.ingestSessionId && it.stateRevision <= remote.stateRevision
            }) {
                "Cloud acknowledgements must agree with the capture's remote ingest snapshot"
            }
            require(uploadAttempts.all { it.ingestSessionId == remote.ingestSessionId }) {
                "Upload attempts must agree with the capture's remote ingest binding"
            }
        }
    }
}

/** Entire transactionally persisted metadata snapshot. Media payloads remain behind opaque refs. */
data class SpoolState(
    val storeRevision: ULong,
    val quota: SpoolQuota,
    val captures: Map<CaptureSessionId, CaptureSpoolState> = emptyMap(),
) {
    init {
        require(captures.all { (captureId, state) -> captureId == state.captureSessionId }) {
            "Persisted capture identity must agree with its map key"
        }
        require(retainedBytes <= quota.maximumRetainedBytes) {
            "Persisted media metadata exceeds configured spool quota"
        }
    }

    val retainedBytes: ULong
        get() = captures.values.asSequence()
            .flatMap { it.streams.values.asSequence() }
            .flatMap { it.chunks.values.asSequence() }
            .fold(0uL) { total, record ->
                checkedAdd(total, record.chunk.descriptor.payloadBytes)
            }

    val pressure: SpoolPressure
        get() = when {
            retainedBytes == quota.maximumRetainedBytes -> SpoolPressure.EXHAUSTED
            retainedBytes >= quota.pressureAtBytes -> SpoolPressure.BACKPRESSURE
            else -> SpoolPressure.ACCEPTING
        }

    val capacity: SpoolCapacity
        get() = SpoolCapacity(
            retainedBytes = retainedBytes,
            maximumRetainedBytes = quota.maximumRetainedBytes,
            pressure = pressure,
        )

    companion object {
        fun empty(quota: SpoolQuota): SpoolState = SpoolState(
            storeRevision = 0uL,
            quota = quota,
        )
    }
}

sealed interface FinalizationReadiness {
    data object TerminalRangeNotDeclared : FinalizationReadiness

    data class MissingDurableRanges(
        val terminalRange: SequenceRange,
        val missing: List<SequenceRange>,
    ) : FinalizationReadiness

    data class AwaitingCloudDurability(
        val terminalRange: SequenceRange,
        val chunkIds: List<ChunkId>,
    ) : FinalizationReadiness

    data class Ready(
        val terminalRange: SequenceRange,
        val orderedChunkIds: List<ChunkId>,
    ) : FinalizationReadiness
}

data class RecoveredStream(
    val descriptor: StreamDescriptor,
    val durableChunkCount: ULong,
    val cloudDurableChunkCount: ULong,
    val blockedUploadCount: ULong,
    val sourceDurableThrough: ULong?,
    val terminalRange: SequenceRange?,
    val discontinuityCount: ULong,
    val finalizationReadiness: FinalizationReadiness,
)

data class SpoolRecovery(
    val storeRevision: ULong,
    val retainedBytes: ULong,
    val pressure: SpoolPressure,
    val streams: List<RecoveredStream>,
    val sourceAdvancePermits: List<SourceAdvancePermit>,
)

internal fun finalizationReadiness(stream: StreamSpoolState): FinalizationReadiness {
    val terminal = stream.terminalRange ?: return FinalizationReadiness.TerminalRangeNotDeclared
    val ordered = stream.chunks.values.sortedWith(
        compareBy<DurableChunkRecord> { it.chunk.descriptor.sequenceRange.first }
            .thenBy { it.chunk.descriptor.sequenceRange.last }
            .thenBy { it.chunk.descriptor.chunkId.value },
    )
    val missing = missingRanges(terminal, ordered.map { it.chunk.descriptor.sequenceRange })
    if (missing.isNotEmpty()) {
        return FinalizationReadiness.MissingDurableRanges(terminal, missing)
    }
    val unacknowledged = ordered
        .filter { it.cloudAck == null }
        .map { it.chunk.descriptor.chunkId }
    if (unacknowledged.isNotEmpty()) {
        return FinalizationReadiness.AwaitingCloudDurability(terminal, unacknowledged)
    }
    return FinalizationReadiness.Ready(
        terminalRange = terminal,
        orderedChunkIds = ordered.map { it.chunk.descriptor.chunkId },
    )
}

internal fun contiguousDurableThrough(
    descriptor: StreamDescriptor,
    chunks: Collection<DurableChunkRecord>,
): ULong? {
    var expected = descriptor.sequencePolicy.first
    var durableThrough: ULong? = null
    val ordered = chunks.sortedBy { it.chunk.descriptor.sequenceRange.first }
    for (record in ordered) {
        val range = record.chunk.descriptor.sequenceRange
        if (range.first != expected) break
        durableThrough = range.last
        if (range.last == ULong.MAX_VALUE) break
        expected = range.last + 1uL
    }
    return durableThrough
}

private fun missingRanges(
    terminal: SequenceRange,
    committed: List<SequenceRange>,
): List<SequenceRange> {
    val missing = mutableListOf<SequenceRange>()
    var next = terminal.first
    var complete = false
    for (range in committed.sortedBy(SequenceRange::first)) {
        if (range.last < terminal.first || range.first > terminal.last) continue
        if (range.first > next) {
            missing += SequenceRange(next, range.first - 1uL)
        }
        if (range.last == ULong.MAX_VALUE) {
            complete = true
            break
        }
        next = range.last + 1uL
        if (next > terminal.last) {
            complete = true
            break
        }
    }
    if (!complete && next <= terminal.last) missing += SequenceRange(next, terminal.last)
    return missing
}

private fun noOverlappingChunkRanges(chunks: Collection<DurableChunkRecord>): Boolean {
    val ordered = chunks.sortedBy { it.chunk.descriptor.sequenceRange.first }
    return ordered.zipWithNext().all { (left, right) ->
        !left.chunk.descriptor.sequenceRange.overlaps(right.chunk.descriptor.sequenceRange)
    }
}

private fun checkedAdd(left: ULong, right: ULong): ULong {
    require(right <= ULong.MAX_VALUE - left) { "Unsigned 64-bit byte total overflow" }
    return left + right
}

private fun requireStableMediaId(label: String, value: String) {
    require(value.matches(UUID_V7)) { "$label must be a lowercase RFC 9562 UUIDv7" }
}

private fun hasExplicitOffset(value: String): Boolean {
    val match = RFC_3339.matchEntire(value) ?: return false
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    val hour = match.groupValues[4].toInt()
    val minute = match.groupValues[5].toInt()
    val second = match.groupValues[6].toInt()
    val offsetHour = match.groupValues[9]
        .takeIf(String::isNotEmpty)
        ?.removePrefix("+")
        ?.removePrefix("-")
        ?.toInt()
        ?: 0
    val offsetMinute = match.groupValues[10].takeIf(String::isNotEmpty)?.toInt() ?: 0
    if (month !in 1..12 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return false
    if (offsetHour !in 0..23 || offsetMinute !in 0..59) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day in 1..days
}

private val UUID_V7 = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
)

private val RFC_3339 = Regex(
    "^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(\\.[0-9]{1,9})?(Z|([+-][0-9]{2}):([0-9]{2}))$",
)

private val OPUS_FRAME_DURATIONS_US = setOf(2_500u, 5_000u, 10_000u, 20_000u, 40_000u, 60_000u)
