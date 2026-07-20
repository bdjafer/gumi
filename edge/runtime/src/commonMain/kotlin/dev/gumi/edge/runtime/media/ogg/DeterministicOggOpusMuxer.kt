package dev.gumi.edge.runtime.media.ogg

import dev.gumi.edge.sdk.OpaqueBytes

private const val OGG_HEADER_BYTES = 27
const val RFC6716_MAX_SINGLE_FRAME_PACKET_BYTES = 1_276
const val M1_SINGLE_PACKET_PAGE_PROFILE = "gumi.ogg-opus.single-packet-page.v1"
const val OGG_OPUS_MUX_SNAPSHOT_VERSION = "gumi.ogg-opus.mux-snapshot.v1"

private const val M1_OPUS_TAGS_VENDOR = "gumi"
private const val OGG_FLAG_BOS = 0x02
private const val OGG_FLAG_EOS = 0x04

private val OPUS_FRAME_DURATIONS_US = setOf(2_500u, 5_000u, 10_000u, 20_000u, 40_000u, 60_000u)

enum class OggOpusLayout(val wireValue: String) {
    /** Header pages 0/1, then exactly one audio packet per page from page sequence 2. */
    M1_SINGLE_PACKET_PAGE_V1(M1_SINGLE_PACKET_PAGE_PROFILE),
}

data class OpusPacketInfo(
    val packetBytes: Int,
    val configuration: UInt,
    val encodedStereo: Boolean,
    val frameCount: UInt,
    val frameCountCode: UInt,
    val frameDurationUs: UInt,
    val decodedSamples48k: UInt,
)

sealed interface OpusPacketInspectionFailure {
    data object EmptyPacket : OpusPacketInspectionFailure

    data class PacketTooLarge(
        val receivedBytes: Int,
        val maximumBytes: Int,
    ) : OpusPacketInspectionFailure

    data class FrameCountNotOne(val frameCountCode: UInt) : OpusPacketInspectionFailure

    data class FrameDurationMismatch(
        val expectedUs: UInt,
        val receivedUs: UInt,
    ) : OpusPacketInspectionFailure

}

sealed interface OpusPacketInspectionResult {
    data class Valid(val info: OpusPacketInfo) : OpusPacketInspectionResult
    data class Invalid(val failure: OpusPacketInspectionFailure) : OpusPacketInspectionResult
}

/** Observation-safe RFC 6716 TOC inspection shared by probes and the muxer. */
object Rfc6716OpusPacketInspector {
    fun inspect(
        payload: OpaqueBytes,
        expectedFrameDurationUs: UInt? = null,
    ): OpusPacketInspectionResult = inspectBytes(payload.copyBytes(), expectedFrameDurationUs)

    internal fun inspectBytes(
        bytes: ByteArray,
        expectedFrameDurationUs: UInt? = null,
    ): OpusPacketInspectionResult {
        if (bytes.isEmpty()) {
            return OpusPacketInspectionResult.Invalid(OpusPacketInspectionFailure.EmptyPacket)
        }
        if (bytes.size > RFC6716_MAX_SINGLE_FRAME_PACKET_BYTES) {
            return OpusPacketInspectionResult.Invalid(
                OpusPacketInspectionFailure.PacketTooLarge(
                    receivedBytes = bytes.size,
                    maximumBytes = RFC6716_MAX_SINGLE_FRAME_PACKET_BYTES,
                ),
            )
        }
        val toc = bytes[0].toUInt() and 0xffu
        val frameCountCode = toc and 0x03u
        if (frameCountCode != 0u) {
            return OpusPacketInspectionResult.Invalid(
                OpusPacketInspectionFailure.FrameCountNotOne(frameCountCode),
            )
        }
        val durationUs = opusFrameDurationUs(toc)
        if (expectedFrameDurationUs != null && durationUs != expectedFrameDurationUs) {
            return OpusPacketInspectionResult.Invalid(
                OpusPacketInspectionFailure.FrameDurationMismatch(
                    expectedUs = expectedFrameDurationUs,
                    receivedUs = durationUs,
                ),
            )
        }
        return OpusPacketInspectionResult.Valid(
            OpusPacketInfo(
                packetBytes = bytes.size,
                configuration = toc shr 3,
                encodedStereo = (toc and 0x04u) != 0u,
                frameCount = 1u,
                frameCountCode = frameCountCode,
                frameDurationUs = durationUs,
                decodedSamples48k = durationUs * 48u / 1_000u,
            ),
        )
    }
}

/**
 * Every stream-varying value is supplied by the caller. The M1 layout fixes OpusTags to vendor
 * `gumi` and zero comments; arbitrary caller metadata is deliberately not representable.
 */
data class OggOpusStreamConfig(
    val configurationId: String,
    val layout: OggOpusLayout = OggOpusLayout.M1_SINGLE_PACKET_PAGE_V1,
    val serialNumber: UInt,
    val firstAudioSequence: ULong,
    val channelCount: UInt,
    val inputSampleRateHz: UInt,
    val preSkip48kSamples: UInt,
    val expectedFrameDurationUs: UInt,
) {
    init {
        require(configurationId.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) {
            "Configuration identity must be a stable lowercase identifier"
        }
        require(channelCount in 1u..2u) { "Ogg Opus mapping family zero supports mono or stereo" }
        require(inputSampleRateHz in 8_000u..48_000u) {
            "Input sample rate is outside the media-ingest candidate profile"
        }
        require(preSkip48kSamples <= UShort.MAX_VALUE.toUInt()) {
            "Opus pre-skip must fit the identification header"
        }
        require(expectedFrameDurationUs in OPUS_FRAME_DURATIONS_US) {
            "Expected Opus frame duration is not defined by RFC 6716"
        }
    }
}

/** One already-delimited Opus packet. This type never claims that an arbitrary BLE value is a packet. */
data class OpusPacketReplayBinding(val sha256Hex: String) {
    init {
        require(sha256Hex.isCanonicalSha256Hex()) {
            "Replay binding must be a canonical lowercase SHA-256 supplied by a trusted digest port"
        }
    }
}

data class SequencedRawOpusPacket(
    val sequence: ULong,
    val payload: OpaqueBytes,
    val discontinuityBefore: Boolean = false,
    /** Computed over payload by a trusted platform/runtime digest port, never by this muxer. */
    val replayBinding: OpusPacketReplayBinding? = null,
)

data class PendingOpusPacketSnapshot(
    val sequence: ULong,
    val packetBytes: Int,
    /** A fixed-size replay binding, never the pending microphone payload itself. */
    val replaySha256Hex: String,
)

/**
 * Complete bounded state required to resume the deterministic stream after process death. The
 * caller must commit this snapshot atomically with any fragment emitted by the same accept call.
 */
data class OggOpusMuxSnapshot(
    val schemaVersion: String = OGG_OPUS_MUX_SNAPSHOT_VERSION,
    val configurationId: String,
    val config: OggOpusStreamConfig,
    /** Sequence the durable source must replay next; it is the pending sequence when one exists. */
    val nextSourceSequence: ULong,
    val nextPageSequence: UInt,
    val decodedGranule48k: ULong,
    val headersEmitted: Boolean,
    val pendingPacket: PendingOpusPacketSnapshot?,
)

enum class OggOpusResumeMode {
    /** The source can replay the pending sequence itself without advancing its durable cursor. */
    REPLAYABLE_SEQUENCE_SOURCE,

    /** Live bytes may have passed during process death, so this logical stream must not resume. */
    NON_REPLAYABLE_LIVE_SOURCE,
}

data class AudioPacketRange(
    val first: ULong,
    val last: ULong,
) {
    init {
        require(first <= last)
    }
}

/**
 * Upload-ready `ogg-opus-page-fragment-v1` bytes. This candidate emits one audio packet per audio
 * page and therefore never carries a continued packet across a fragment boundary.
 */
data class OggOpusPageFragment(
    val audioSequenceRange: AudioPacketRange,
    val firstPageSequence: UInt,
    val lastPageSequence: UInt,
    val beginsLogicalStream: Boolean,
    val endsLogicalStream: Boolean,
    val terminalGranulePosition: ULong,
    val bytes: OpaqueBytes,
)

sealed interface OggOpusFragmentCompositionFailure {
    data object EmptyBatch : OggOpusFragmentCompositionFailure

    data class InvalidBound(val field: String) : OggOpusFragmentCompositionFailure

    data class FragmentLimitExceeded(
        val received: Int,
        val maximum: Int,
    ) : OggOpusFragmentCompositionFailure

    data class ByteLimitExceeded(
        val received: Long,
        val maximum: Int,
    ) : OggOpusFragmentCompositionFailure

    data class EmptyFragmentBytes(val index: Int) : OggOpusFragmentCompositionFailure

    data class NonContiguousAudioSequence(
        val expected: ULong,
        val received: ULong,
    ) : OggOpusFragmentCompositionFailure

    data class NonContiguousPageSequence(
        val expected: UInt,
        val received: UInt,
    ) : OggOpusFragmentCompositionFailure

    data class UnexpectedLogicalStreamStart(val sequence: ULong) :
        OggOpusFragmentCompositionFailure

    data class LogicalStreamEndedBeforeFinalFragment(val sequence: ULong) :
        OggOpusFragmentCompositionFailure

    data class GranulePositionRegressed(
        val prior: ULong,
        val received: ULong,
    ) : OggOpusFragmentCompositionFailure

    data object CompositionSequenceExhausted : OggOpusFragmentCompositionFailure
}

sealed interface OggOpusFragmentCompositionResult {
    data class Composed(val fragment: OggOpusPageFragment) : OggOpusFragmentCompositionResult

    data class Rejected(val failure: OggOpusFragmentCompositionFailure) :
        OggOpusFragmentCompositionResult
}

/**
 * Bounded composition for spool/HTTP chunks. The muxer emits one audio page at a time, while this
 * helper safely concatenates adjacent outputs into N-page chunks without inventing new headers.
 */
object OggOpusFragmentComposer {
    fun compose(
        fragments: List<OggOpusPageFragment>,
        maximumFragments: Int,
        maximumBytes: Int,
    ): OggOpusFragmentCompositionResult {
        if (maximumFragments <= 0) {
            return OggOpusFragmentCompositionResult.Rejected(
                OggOpusFragmentCompositionFailure.InvalidBound("maximumFragments"),
            )
        }
        if (maximumBytes <= 0) {
            return OggOpusFragmentCompositionResult.Rejected(
                OggOpusFragmentCompositionFailure.InvalidBound("maximumBytes"),
            )
        }
        if (fragments.isEmpty()) {
            return OggOpusFragmentCompositionResult.Rejected(
                OggOpusFragmentCompositionFailure.EmptyBatch,
            )
        }
        if (fragments.size > maximumFragments) {
            return OggOpusFragmentCompositionResult.Rejected(
                OggOpusFragmentCompositionFailure.FragmentLimitExceeded(
                    received = fragments.size,
                    maximum = maximumFragments,
                ),
            )
        }

        var totalBytes = 0L
        var prior: OggOpusPageFragment? = null
        fragments.forEachIndexed { index, fragment ->
            if (fragment.bytes.size == 0) {
                return OggOpusFragmentCompositionResult.Rejected(
                    OggOpusFragmentCompositionFailure.EmptyFragmentBytes(index),
                )
            }
            totalBytes += fragment.bytes.size.toLong()
            if (totalBytes > maximumBytes.toLong()) {
                return OggOpusFragmentCompositionResult.Rejected(
                    OggOpusFragmentCompositionFailure.ByteLimitExceeded(
                        received = totalBytes,
                        maximum = maximumBytes,
                    ),
                )
            }
            if (index > 0 && fragment.beginsLogicalStream) {
                return OggOpusFragmentCompositionResult.Rejected(
                    OggOpusFragmentCompositionFailure.UnexpectedLogicalStreamStart(
                        fragment.audioSequenceRange.first,
                    ),
                )
            }
            val previous = prior
            if (previous != null) {
                if (previous.endsLogicalStream) {
                    return OggOpusFragmentCompositionResult.Rejected(
                        OggOpusFragmentCompositionFailure.LogicalStreamEndedBeforeFinalFragment(
                            previous.audioSequenceRange.last,
                        ),
                    )
                }
                if (previous.audioSequenceRange.last == ULong.MAX_VALUE) {
                    return OggOpusFragmentCompositionResult.Rejected(
                        OggOpusFragmentCompositionFailure.CompositionSequenceExhausted,
                    )
                }
                val expectedAudio = previous.audioSequenceRange.last + 1uL
                if (fragment.audioSequenceRange.first != expectedAudio) {
                    return OggOpusFragmentCompositionResult.Rejected(
                        OggOpusFragmentCompositionFailure.NonContiguousAudioSequence(
                            expectedAudio,
                            fragment.audioSequenceRange.first,
                        ),
                    )
                }
                if (previous.lastPageSequence == UInt.MAX_VALUE) {
                    return OggOpusFragmentCompositionResult.Rejected(
                        OggOpusFragmentCompositionFailure.CompositionSequenceExhausted,
                    )
                }
                val expectedPage = previous.lastPageSequence + 1u
                if (fragment.firstPageSequence != expectedPage) {
                    return OggOpusFragmentCompositionResult.Rejected(
                        OggOpusFragmentCompositionFailure.NonContiguousPageSequence(
                            expectedPage,
                            fragment.firstPageSequence,
                        ),
                    )
                }
                if (fragment.terminalGranulePosition < previous.terminalGranulePosition) {
                    return OggOpusFragmentCompositionResult.Rejected(
                        OggOpusFragmentCompositionFailure.GranulePositionRegressed(
                            previous.terminalGranulePosition,
                            fragment.terminalGranulePosition,
                        ),
                    )
                }
            }
            prior = fragment
        }

        val first = fragments.first()
        val last = fragments.last()
        val combined = ByteArray(totalBytes.toInt())
        var offset = 0
        for (fragment in fragments) {
            val bytes = fragment.bytes.copyBytes()
            bytes.copyInto(combined, destinationOffset = offset)
            offset += bytes.size
        }
        return OggOpusFragmentCompositionResult.Composed(
            OggOpusPageFragment(
                audioSequenceRange = AudioPacketRange(
                    first.audioSequenceRange.first,
                    last.audioSequenceRange.last,
                ),
                firstPageSequence = first.firstPageSequence,
                lastPageSequence = last.lastPageSequence,
                beginsLogicalStream = first.beginsLogicalStream,
                endsLogicalStream = last.endsLogicalStream,
                terminalGranulePosition = last.terminalGranulePosition,
                bytes = OpaqueBytes.copyOf(combined),
            ),
        )
    }
}

sealed interface OggOpusMuxFailure {
    data class SourceDiscontinuity(val sequence: ULong) : OggOpusMuxFailure

    data class NonContiguousAudioSequence(
        val expected: ULong,
        val received: ULong,
    ) : OggOpusMuxFailure

    data class AudioSequenceExhausted(val received: ULong) : OggOpusMuxFailure

    data class InvalidOpusPacket(
        val sequence: ULong,
        val failure: OpusPacketInspectionFailure,
    ) : OggOpusMuxFailure

    data class SnapshotVersionUnsupported(val received: String) : OggOpusMuxFailure

    data class SnapshotConfigMismatch(val field: String) : OggOpusMuxFailure

    data class SnapshotStateMismatch(val field: String) : OggOpusMuxFailure

    data class SnapshotPendingIdentityMalformed(val code: String) : OggOpusMuxFailure

    data class SnapshotPendingPacketRequired(val sequence: ULong) : OggOpusMuxFailure

    data class SnapshotPendingPacketUnexpected(val sequence: ULong) : OggOpusMuxFailure

    data class SnapshotPendingPacketMismatch(val field: String) : OggOpusMuxFailure

    data class PendingPacketReplayBindingRequired(val sequence: ULong) : OggOpusMuxFailure

    data object NonReplayableSourceCannotResume : OggOpusMuxFailure

    data class SnapshotSequencePrecedesStream(
        val first: ULong,
        val received: ULong,
    ) : OggOpusMuxFailure

    data object PageSequenceExhausted : OggOpusMuxFailure

    data object GranulePositionExhausted : OggOpusMuxFailure

    data object NoAudioPackets : OggOpusMuxFailure

    data class TerminalTrimExceedsFinalPacket(
        val requestedSamples: UInt,
        val finalPacketSamples: UInt,
    ) : OggOpusMuxFailure

    data class TerminalGranulePrecedesPreSkip(
        val terminalGranule: ULong,
        val preSkip: UInt,
    ) : OggOpusMuxFailure

    data object StreamAlreadyFinished : OggOpusMuxFailure
}

sealed interface OggOpusMuxResult<out T> {
    data class Accepted<T>(val value: T) : OggOpusMuxResult<T>

    data class Rejected(val failure: OggOpusMuxFailure) : OggOpusMuxResult<Nothing>
}

/**
 * Incremental, pure-Kotlin Ogg Opus muxer for an already-delimited raw-packet source.
 *
 * The M1 layout is self-derivable from the audio sequence: page sequence is `2 + packetOffset` and
 * untrimmed granule is `(packetOffset + 1) * fixedFrameSamples48k`. At most one audio packet (1,276
 * bytes) is retained so the final packet can carry EOS and optional end trim. OpusTags are fixed by
 * the profile. A source gap latches a typed failure: this logical stream can never silently resume
 * across lost physical audio.
 */
class DeterministicOggOpusMuxer(config: OggOpusStreamConfig) {
    private val config = config
    private val headerPrefix: ByteArray
    private var pending: PendingPacket? = null
    private var finished = false
    private var latchedFailure: OggOpusMuxFailure? = null

    /** Observable bound for qualification tests and future spool orchestration. */
    val bufferedAudioBytes: Int
        get() = pending?.bytes?.size ?: 0

    val layoutProfile: String
        get() = config.layout.wireValue

    init {
        val head = opusHead(this.config)
        val tags = opusTags()
        headerPrefix = concatenate(
            oggPage(
                packet = head,
                serialNumber = this.config.serialNumber,
                pageSequence = 0u,
                granulePosition = 0uL,
                headerType = OGG_FLAG_BOS,
            ),
            oggPage(
                packet = tags,
                serialNumber = this.config.serialNumber,
                pageSequence = 1u,
                granulePosition = 0uL,
                headerType = 0,
            ),
        )
    }

    /** Snapshot contains bounded state and a digest binding, never pending microphone bytes. */
    fun snapshot(): OggOpusMuxResult<OggOpusMuxSnapshot> {
        latchedFailure?.let { return OggOpusMuxResult.Rejected(it) }
        if (finished) return OggOpusMuxResult.Rejected(OggOpusMuxFailure.StreamAlreadyFinished)
        return OggOpusMuxResult.Accepted(
            OggOpusMuxSnapshot(
                configurationId = config.configurationId,
                config = config,
                nextSourceSequence = pending?.sequence ?: config.firstAudioSequence,
                nextPageSequence = pending?.pageSequence ?: 2u,
                decodedGranule48k = pending?.endGranule ?: 0uL,
                headersEmitted = pending?.sequence?.let { it > config.firstAudioSequence } ?: false,
                pendingPacket = pending?.let {
                    val binding = it.replayBinding ?: return OggOpusMuxResult.Rejected(
                        OggOpusMuxFailure.PendingPacketReplayBindingRequired(it.sequence),
                    )
                    PendingOpusPacketSnapshot(
                        sequence = it.sequence,
                        packetBytes = it.bytes.size,
                        replaySha256Hex = binding.sha256Hex,
                    )
                },
            ),
        )
    }

    /**
     * Accepts exactly the next physical packet. The first call buffers; each later call emits the
     * preceding packet as a complete non-terminal page fragment.
     */
    fun accept(packet: SequencedRawOpusPacket): OggOpusMuxResult<OggOpusPageFragment?> {
        latchedFailure?.let { return OggOpusMuxResult.Rejected(it) }
        if (finished) return OggOpusMuxResult.Rejected(OggOpusMuxFailure.StreamAlreadyFinished)
        if (packet.discontinuityBefore) {
            return rejectPermanently(OggOpusMuxFailure.SourceDiscontinuity(packet.sequence))
        }
        val expected = nextSourceSequence()
        if (expected == null) {
            return rejectPermanently(OggOpusMuxFailure.AudioSequenceExhausted(packet.sequence))
        }
        if (packet.sequence != expected) {
            return rejectPermanently(
                OggOpusMuxFailure.NonContiguousAudioSequence(expected, packet.sequence),
            )
        }
        val bytes = packet.payload.copyBytes()
        val packetInfo = when (
            val inspected = Rfc6716OpusPacketInspector.inspectBytes(
                bytes,
                config.expectedFrameDurationUs,
            )
        ) {
            is OpusPacketInspectionResult.Valid -> inspected.info
            is OpusPacketInspectionResult.Invalid -> return rejectPermanently(
                OggOpusMuxFailure.InvalidOpusPacket(packet.sequence, inspected.failure),
            )
        }
        val identity = when (val derived = derivePageIdentity(packet.sequence, packetInfo.decodedSamples48k)) {
            is OggOpusMuxResult.Accepted -> derived.value
            is OggOpusMuxResult.Rejected -> return rejectPermanently(derived.failure)
        }

        val prior = pending
        pending = PendingPacket(
            sequence = packet.sequence,
            bytes = bytes,
            replayBinding = packet.replayBinding,
            samples48k = packetInfo.decodedSamples48k,
            pageSequence = identity.pageSequence,
            endGranule = identity.endGranule,
        )

        if (prior == null) return OggOpusMuxResult.Accepted(null)
        return OggOpusMuxResult.Accepted(emit(prior, eos = false, terminalGranule = prior.endGranule))
    }

    /** Emits the retained final packet with EOS. End trim is expressed at the Opus 48 kHz clock. */
    fun finish(endTrim48kSamples: UInt = 0u): OggOpusMuxResult<OggOpusPageFragment> {
        latchedFailure?.let { return OggOpusMuxResult.Rejected(it) }
        if (finished) return OggOpusMuxResult.Rejected(OggOpusMuxFailure.StreamAlreadyFinished)
        val finalPacket = pending
            ?: return rejectPermanently(OggOpusMuxFailure.NoAudioPackets)
        if (endTrim48kSamples > finalPacket.samples48k) {
            return rejectPermanently(
                OggOpusMuxFailure.TerminalTrimExceedsFinalPacket(
                    requestedSamples = endTrim48kSamples,
                    finalPacketSamples = finalPacket.samples48k,
                ),
            )
        }
        val terminalGranule = finalPacket.endGranule - endTrim48kSamples.toULong()
        if (terminalGranule < config.preSkip48kSamples.toULong()) {
            return rejectPermanently(
                OggOpusMuxFailure.TerminalGranulePrecedesPreSkip(
                    terminalGranule = terminalGranule,
                    preSkip = config.preSkip48kSamples,
                ),
            )
        }
        val fragment = emit(finalPacket, eos = true, terminalGranule = terminalGranule)
        pending = null
        finished = true
        return OggOpusMuxResult.Accepted(fragment)
    }

    private fun derivePageIdentity(
        sequence: ULong,
        frameSamples48k: UInt,
    ): OggOpusMuxResult<PageIdentity> {
        if (sequence < config.firstAudioSequence) {
            return OggOpusMuxResult.Rejected(
                OggOpusMuxFailure.SnapshotSequencePrecedesStream(
                    first = config.firstAudioSequence,
                    received = sequence,
                ),
            )
        }
        val packetOffset = sequence - config.firstAudioSequence
        if (packetOffset == ULong.MAX_VALUE) {
            return OggOpusMuxResult.Rejected(OggOpusMuxFailure.GranulePositionExhausted)
        }
        val packetCount = packetOffset + 1uL
        if (packetCount > ULong.MAX_VALUE / frameSamples48k.toULong()) {
            return OggOpusMuxResult.Rejected(OggOpusMuxFailure.GranulePositionExhausted)
        }
        if (packetOffset > UInt.MAX_VALUE.toULong() - 2uL) {
            return OggOpusMuxResult.Rejected(OggOpusMuxFailure.PageSequenceExhausted)
        }
        return OggOpusMuxResult.Accepted(
            PageIdentity(
                pageSequence = packetOffset.toUInt() + 2u,
                endGranule = packetCount * frameSamples48k.toULong(),
            ),
        )
    }

    private fun emit(
        packet: PendingPacket,
        eos: Boolean,
        terminalGranule: ULong,
    ): OggOpusPageFragment {
        val audioPage = oggPage(
            packet = packet.bytes,
            serialNumber = config.serialNumber,
            pageSequence = packet.pageSequence,
            granulePosition = terminalGranule,
            headerType = if (eos) OGG_FLAG_EOS else 0,
        )
        val first = packet.sequence == config.firstAudioSequence
        val body = if (first) concatenate(headerPrefix, audioPage) else audioPage
        return OggOpusPageFragment(
            audioSequenceRange = AudioPacketRange(packet.sequence, packet.sequence),
            firstPageSequence = if (first) 0u else packet.pageSequence,
            lastPageSequence = packet.pageSequence,
            beginsLogicalStream = first,
            endsLogicalStream = eos,
            terminalGranulePosition = terminalGranule,
            bytes = OpaqueBytes.copyOf(body),
        )
    }

    private fun <T> rejectPermanently(failure: OggOpusMuxFailure): OggOpusMuxResult<T> {
        latchedFailure = failure
        return OggOpusMuxResult.Rejected(failure)
    }

    private fun nextSourceSequence(): ULong? = pending?.sequence?.let { prior ->
        if (prior == ULong.MAX_VALUE) null else prior + 1uL
    } ?: if (pending == null) config.firstAudioSequence else null

    private data class PendingPacket(
        val sequence: ULong,
        val bytes: ByteArray,
        val replayBinding: OpusPacketReplayBinding?,
        val samples48k: UInt,
        val pageSequence: UInt,
        val endGranule: ULong,
    )

    private data class PageIdentity(
        val pageSequence: UInt,
        val endGranule: ULong,
    )

    companion object {
        fun restore(
            snapshot: OggOpusMuxSnapshot,
            expectedConfig: OggOpusStreamConfig,
            resumeMode: OggOpusResumeMode,
            replayedPendingPacket: SequencedRawOpusPacket?,
        ): OggOpusMuxResult<DeterministicOggOpusMuxer> {
            if (snapshot.schemaVersion != OGG_OPUS_MUX_SNAPSHOT_VERSION) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotVersionUnsupported(snapshot.schemaVersion),
                )
            }
            if (snapshot.configurationId != snapshot.config.configurationId) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotConfigMismatch("configurationId"),
                )
            }
            if (snapshot.config != expectedConfig) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotConfigMismatch("config"),
                )
            }
            if (resumeMode == OggOpusResumeMode.NON_REPLAYABLE_LIVE_SOURCE) {
                return OggOpusMuxResult.Rejected(OggOpusMuxFailure.NonReplayableSourceCannotResume)
            }
            val muxer = DeterministicOggOpusMuxer(expectedConfig)
            val saved = snapshot.pendingPacket
            if (saved == null) {
                if (replayedPendingPacket != null) {
                    return OggOpusMuxResult.Rejected(
                        OggOpusMuxFailure.SnapshotPendingPacketUnexpected(
                            replayedPendingPacket.sequence,
                        ),
                    )
                }
                return if (
                    snapshot.nextSourceSequence == expectedConfig.firstAudioSequence &&
                    snapshot.nextPageSequence == 2u &&
                    snapshot.decodedGranule48k == 0uL &&
                    !snapshot.headersEmitted
                ) {
                    OggOpusMuxResult.Accepted(muxer)
                } else {
                    OggOpusMuxResult.Rejected(OggOpusMuxFailure.SnapshotStateMismatch("empty"))
                }
            }
            if (saved.packetBytes !in 1..RFC6716_MAX_SINGLE_FRAME_PACKET_BYTES) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotPendingIdentityMalformed("PACKET_LENGTH"),
                )
            }
            if (!saved.replaySha256Hex.isCanonicalSha256Hex()) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotPendingIdentityMalformed("SHA256"),
                )
            }
            val replayed = replayedPendingPacket ?: return OggOpusMuxResult.Rejected(
                OggOpusMuxFailure.SnapshotPendingPacketRequired(saved.sequence),
            )
            if (replayed.discontinuityBefore) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SourceDiscontinuity(replayed.sequence),
                )
            }
            if (replayed.sequence != saved.sequence) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotPendingPacketMismatch("sequence"),
                )
            }
            val bytes = replayed.payload.copyBytes()
            if (bytes.size != saved.packetBytes) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotPendingPacketMismatch("packetBytes"),
                )
            }
            val replayBinding = replayed.replayBinding ?: return OggOpusMuxResult.Rejected(
                OggOpusMuxFailure.PendingPacketReplayBindingRequired(replayed.sequence),
            )
            if (replayBinding.sha256Hex != saved.replaySha256Hex) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotPendingPacketMismatch("replaySha256Hex"),
                )
            }
            val packetInfo = when (
                val inspected = Rfc6716OpusPacketInspector.inspectBytes(
                    bytes,
                    snapshot.config.expectedFrameDurationUs,
                )
            ) {
                is OpusPacketInspectionResult.Valid -> inspected.info
                is OpusPacketInspectionResult.Invalid -> return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.InvalidOpusPacket(saved.sequence, inspected.failure),
                )
            }
            val identity = when (
                val derived = muxer.derivePageIdentity(saved.sequence, packetInfo.decodedSamples48k)
            ) {
                is OggOpusMuxResult.Accepted -> derived.value
                is OggOpusMuxResult.Rejected -> return derived
            }
            if (snapshot.nextSourceSequence != saved.sequence) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotStateMismatch("nextSourceSequence"),
                )
            }
            if (snapshot.nextPageSequence != identity.pageSequence) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotStateMismatch("nextPageSequence"),
                )
            }
            if (snapshot.decodedGranule48k != identity.endGranule) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotStateMismatch("decodedGranule48k"),
                )
            }
            if (snapshot.headersEmitted != (saved.sequence > expectedConfig.firstAudioSequence)) {
                return OggOpusMuxResult.Rejected(
                    OggOpusMuxFailure.SnapshotStateMismatch("headersEmitted"),
                )
            }
            muxer.pending = PendingPacket(
                sequence = saved.sequence,
                bytes = bytes,
                replayBinding = replayBinding,
                samples48k = packetInfo.decodedSamples48k,
                pageSequence = identity.pageSequence,
                endGranule = identity.endGranule,
            )
            return OggOpusMuxResult.Accepted(muxer)
        }
    }
}

private fun opusFrameDurationUs(toc: UInt): UInt {
    val configuration = toc shr 3
    return when {
        configuration < 12u -> listOf(10_000u, 20_000u, 40_000u, 60_000u)[(configuration % 4u).toInt()]
        configuration < 16u -> listOf(10_000u, 20_000u)[(configuration % 2u).toInt()]
        else -> listOf(2_500u, 5_000u, 10_000u, 20_000u)[(configuration % 4u).toInt()]
    }
}

private fun opusHead(config: OggOpusStreamConfig): ByteArray = ByteArray(19).also { packet ->
    "OpusHead".encodeToByteArray().copyInto(packet)
    packet[8] = 1
    packet[9] = config.channelCount.toByte()
    packet.putUShortLe(10, config.preSkip48kSamples.toUShort())
    packet.putUIntLe(12, config.inputSampleRateHz)
    packet.putUShortLe(16, 0u.toUShort())
    packet[18] = 0
}

private fun opusTags(): ByteArray {
    val vendor = M1_OPUS_TAGS_VENDOR.encodeToByteArray()
    return ByteArray(8 + 4 + vendor.size + 4).also { packet ->
        var offset = 0
        "OpusTags".encodeToByteArray().copyInto(packet, destinationOffset = offset)
        offset += 8
        packet.putUIntLe(offset, vendor.size.toUInt())
        offset += 4
        vendor.copyInto(packet, destinationOffset = offset)
        offset += vendor.size
        packet.putUIntLe(offset, 0u)
    }
}

private fun oggPage(
    packet: ByteArray,
    serialNumber: UInt,
    pageSequence: UInt,
    granulePosition: ULong,
    headerType: Int,
): ByteArray {
    val fullSegments = packet.size / 255
    val segmentCount = fullSegments + 1
    require(segmentCount <= 255) { "Packet cannot fit in a single bounded Ogg page" }
    val page = ByteArray(OGG_HEADER_BYTES + segmentCount + packet.size)
    "OggS".encodeToByteArray().copyInto(page)
    page[4] = 0
    page[5] = headerType.toByte()
    page.putULongLe(6, granulePosition)
    page.putUIntLe(14, serialNumber)
    page.putUIntLe(18, pageSequence)
    page.putUIntLe(22, 0u)
    page[26] = segmentCount.toByte()
    repeat(fullSegments) { page[OGG_HEADER_BYTES + it] = 255.toByte() }
    page[OGG_HEADER_BYTES + fullSegments] = (packet.size % 255).toByte()
    packet.copyInto(page, destinationOffset = OGG_HEADER_BYTES + segmentCount)
    page.putUIntLe(22, oggCrc(page))
    return page
}

private val OGG_CRC_TABLE: Array<UInt> = Array(256) { index ->
    var value = index.toUInt() shl 24
    repeat(8) {
        value = if ((value and 0x8000_0000u) != 0u) {
            (value shl 1) xor 0x04c1_1db7u
        } else {
            value shl 1
        }
    }
    value
}

private fun oggCrc(page: ByteArray): UInt {
    var crc = 0u
    for (byte in page) {
        val tableIndex = (((crc shr 24) xor (byte.toUInt() and 0xffu)) and 0xffu).toInt()
        crc = (crc shl 8) xor OGG_CRC_TABLE[tableIndex]
    }
    return crc
}

private fun ByteArray.putUShortLe(offset: Int, value: UShort) {
    this[offset] = value.toByte()
    this[offset + 1] = (value.toUInt() shr 8).toByte()
}

private fun ByteArray.putUIntLe(offset: Int, value: UInt) {
    repeat(4) { byte -> this[offset + byte] = (value shr (byte * 8)).toByte() }
}

private fun ByteArray.putULongLe(offset: Int, value: ULong) {
    repeat(8) { byte -> this[offset + byte] = (value shr (byte * 8)).toByte() }
}

private fun concatenate(vararg parts: ByteArray): ByteArray {
    val total = parts.fold(0) { size, part ->
        require(part.size <= Int.MAX_VALUE - size) { "Combined Ogg fragment exceeds memory bounds" }
        size + part.size
    }
    return ByteArray(total).also { combined ->
        var offset = 0
        for (part in parts) {
            part.copyInto(combined, destinationOffset = offset)
            offset += part.size
        }
    }
}

private fun String.isCanonicalSha256Hex(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
