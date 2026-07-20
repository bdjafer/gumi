package dev.gumi.edge.shell.android.diagnostics

import dev.gumi.edge.runtime.media.ogg.OpusPacketInspectionFailure
import dev.gumi.edge.runtime.media.ogg.OpusPacketInspectionResult
import dev.gumi.edge.runtime.media.ogg.Rfc6716OpusPacketInspector
import dev.gumi.edge.sdk.OpaqueBytes
import dev.gumi.edge.sdk.capability.audio.AudioCodec
import dev.gumi.edge.sdk.capability.audio.AudioFormat
import dev.gumi.edge.sdk.capability.audio.AudioPayloadFraming

/** Thin adapter over the shared common RFC 6716 inspector; no parser logic is duplicated here. */
object SharedOpusPacketMetadataInspector : AudioPacketMetadataInspector {
    override fun inspect(
        format: AudioFormat,
        payload: OpaqueBytes,
    ): AudioPacketMetadataInspection {
        if (
            format.codec != AudioCodec.OPUS ||
            format.payloadFraming != AudioPayloadFraming.RAW_OPUS_PACKET
        ) {
            return AudioPacketMetadataInspection.NotApplicable
        }
        val expectedDurationUs = format.frameDurationMillis?.times(1_000u)
        return when (
            val inspected = Rfc6716OpusPacketInspector.inspect(payload, expectedDurationUs)
        ) {
            is OpusPacketInspectionResult.Valid -> AudioPacketMetadataInspection.Valid(
                frameCount = inspected.info.frameCount,
                frameDurationUs = inspected.info.frameDurationUs,
                decodedSamples48k = inspected.info.decodedSamples48k,
                tocConfiguration = inspected.info.configuration,
                encodedStereo = inspected.info.encodedStereo,
            )
            is OpusPacketInspectionResult.Invalid -> AudioPacketMetadataInspection.Invalid(
                code = inspected.failure.stableCode(),
            )
        }
    }
}

private fun OpusPacketInspectionFailure.stableCode(): String = when (this) {
    OpusPacketInspectionFailure.EmptyPacket -> "OPUS_EMPTY_PACKET"
    is OpusPacketInspectionFailure.PacketTooLarge -> "OPUS_PACKET_TOO_LARGE"
    is OpusPacketInspectionFailure.FrameCountNotOne -> "OPUS_FRAME_COUNT_NOT_ONE"
    is OpusPacketInspectionFailure.FrameDurationMismatch -> "OPUS_FRAME_DURATION_MISMATCH"
}
