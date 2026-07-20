package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.capability.audio.AudioStreamException

/** Stock Omi firmware emits: u16 little-endian notification sequence, u8 fragment index, payload. */
internal const val OMI_LIVE_AUDIO_ENVELOPE_BYTES = 3

/** The stock Opus encoder output buffer is CODEC_PACKAGE_SAMPLES / 2 = 160 bytes. */
internal const val OMI_STOCK_OPUS_MAX_PACKET_BYTES = 160

/** ATT MTU needed for the 160-byte source maximum, its 3-byte envelope, and ATT's 3-byte overhead. */
internal const val OMI_STOCK_AUDIO_MINIMUM_UNFRAGMENTED_ATT_MTU =
    OMI_STOCK_OPUS_MAX_PACKET_BYTES + OMI_LIVE_AUDIO_ENVELOPE_BYTES + 3

internal data class DecodedOmiLiveAudioPacket(
    val sequence: ULong,
    val payload: ByteArray,
    val discontinuityBefore: Boolean,
)

/**
 * Turns the stock BLE envelope into exactly one raw Opus packet.
 *
 * M1 deliberately rejects firmware fragmentation. The connection requests ATT MTU 512 and this
 * decoder is opened only when the negotiated MTU proves the complete 160-byte source maximum fits.
 * Rejecting an unexpected fragment is safer than presenting partial bytes as a codec packet.
 */
internal class OmiLiveAudioEnvelopeDecoder {
    private var previousNotificationOrdinal: ULong? = null
    private var previousRawSequence: UInt? = null
    private var previousExpandedSequence: ULong? = null

    fun decode(notificationOrdinal: ULong, bytes: ByteArray): DecodedOmiLiveAudioPacket {
        if (bytes.size <= OMI_LIVE_AUDIO_ENVELOPE_BYTES) {
            fail("OMI_AUDIO_ENVELOPE_EMPTY")
        }
        val payloadSize = bytes.size - OMI_LIVE_AUDIO_ENVELOPE_BYTES
        if (payloadSize > OMI_STOCK_OPUS_MAX_PACKET_BYTES) {
            fail("OMI_AUDIO_PACKET_EXCEEDS_SOURCE_BOUND")
        }

        val rawSequence = bytes[0].toUByte().toUInt() or
            (bytes[1].toUByte().toUInt() shl 8)
        val fragmentIndex = bytes[2].toUByte().toUInt()
        if (fragmentIndex != 0u) {
            fail("OMI_AUDIO_FRAGMENTATION_UNSUPPORTED")
        }

        val priorRaw = previousRawSequence
        val priorExpanded = previousExpandedSequence
        val sourceDelta = if (priorRaw == null) {
            null
        } else {
            (rawSequence - priorRaw) and 0xffffu
        }
        val expanded = if (priorExpanded == null || sourceDelta == null) {
            rawSequence.toULong()
        } else {
            if (ULong.MAX_VALUE - priorExpanded < sourceDelta.toULong()) {
                fail("OMI_AUDIO_SEQUENCE_EXHAUSTED")
            }
            priorExpanded + sourceDelta.toULong()
        }

        val priorOrdinal = previousNotificationOrdinal
        val hostContinuous = priorOrdinal == null ||
            (priorOrdinal != ULong.MAX_VALUE && notificationOrdinal == priorOrdinal + 1uL)
        val sourceContinuous = sourceDelta == null || sourceDelta == 1u

        previousNotificationOrdinal = notificationOrdinal
        previousRawSequence = rawSequence
        previousExpandedSequence = expanded

        return DecodedOmiLiveAudioPacket(
            sequence = expanded,
            payload = bytes.copyOfRange(OMI_LIVE_AUDIO_ENVELOPE_BYTES, bytes.size),
            discontinuityBefore = !hostContinuous || !sourceContinuous,
        )
    }

    private fun fail(code: String): Nothing = throw AudioStreamException(
        ExpectedFailure(
            category = FailureCategory.CORRUPT,
            code = FailureCode(code),
            retryable = false,
        ),
    )
}
