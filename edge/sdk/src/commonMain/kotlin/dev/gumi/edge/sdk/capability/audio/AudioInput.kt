package dev.gumi.edge.sdk.capability.audio

import dev.gumi.edge.sdk.CapabilityDescriptor
import dev.gumi.edge.sdk.CapabilityHandle
import dev.gumi.edge.sdk.CapabilityKey
import dev.gumi.edge.sdk.CapabilityType
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.OpaqueBytes
import dev.gumi.edge.sdk.SemanticVersion
import kotlinx.coroutines.flow.Flow

enum class AudioCodec {
    PCM_S16_LE,
    OPUS,
}

enum class AudioPayloadFraming {
    PCM_S16_LE_FRAME,

    /** One codec packet with no Ogg page/header/CRC; never upload-ready as an Ogg page fragment. */
    RAW_OPUS_PACKET,

    /** One or more complete Ogg pages, with no packet continued across the enclosing chunk. */
    OGG_OPUS_PAGE_FRAGMENT,
}

data class AudioFormat(
    val codec: AudioCodec,
    val sampleRateHz: UInt,
    val channels: UInt,
    /**
     * Wire framing delivered by [AudioStream.frames]. RAW_OPUS_PACKET requires an explicit,
     * sequence-aware Ogg muxer before it can become an upload-ready OGG_OPUS_PAGE_FRAGMENT.
     */
    val payloadFraming: AudioPayloadFraming,
    val frameDurationMillis: UInt? = null,
) {
    init {
        require(sampleRateHz in 8_000u..192_000u)
        require(channels in 1u..8u)
        require(frameDurationMillis == null || frameDurationMillis in 1u..1_000u)
        require(
            when (codec) {
                AudioCodec.PCM_S16_LE -> payloadFraming == AudioPayloadFraming.PCM_S16_LE_FRAME
                AudioCodec.OPUS -> payloadFraming in setOf(
                    AudioPayloadFraming.RAW_OPUS_PACKET,
                    AudioPayloadFraming.OGG_OPUS_PAGE_FRAGMENT,
                )
            },
        ) { "Audio payload framing is incompatible with its codec" }
    }
}

data class AudioInputDescriptor(
    val formats: Set<AudioFormat>,
    val live: Boolean,
    val stored: Boolean,
    override val required: Boolean = false,
) : CapabilityDescriptor {
    override val key: CapabilityKey = AudioInputV1.key
    override val version: SemanticVersion = SemanticVersion(1u, 0u)

    init {
        require(formats.isNotEmpty())
        require(live || stored)
    }
}

data class AudioFrame(
    /** Null when a compatibility protocol provides no trustworthy sequence identity. */
    val sequence: ULong?,
    val payload: OpaqueBytes,
    val discontinuityBefore: Boolean = false,
    /** Adapter receipt time on a monotonic clock; null only when the transport cannot expose it. */
    val receivedAtMonotonicMillis: Long? = null,
) {
    init {
        require(receivedAtMonotonicMillis == null || receivedAtMonotonicMillis >= 0) {
            "Audio frame receipt time must be non-negative"
        }
    }
}

/**
 * A typed, redacted failure raised while opening or collecting an audio stream.
 *
 * Device drivers use this boundary when a transport envelope cannot be proved to contain complete
 * codec frames. Consumers may project [failure] but must never substitute exception text for a
 * stable code or attach media bytes as evidence.
 */
class AudioStreamException(
    val failure: ExpectedFailure,
    cause: Throwable? = null,
) : Exception(failure.code.value, cause)

interface AudioStream {
    val format: AudioFormat
    val frames: Flow<AudioFrame>

    suspend fun close()
}

interface AudioInputHandle : CapabilityHandle<AudioInputDescriptor> {
    suspend fun open(format: AudioFormat): AudioStream
}

object AudioInputV1 : CapabilityType<AudioInputDescriptor, AudioInputHandle> {
    override val key = CapabilityKey("gumi.audio-input")
    override val supportedMajor = 1u
}
