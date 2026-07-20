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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Stable, strict metadata encoding encrypted before it reaches SQLite. */
internal object SpoolStateBinaryCodec {
    private val magic = byteArrayOf(0x47, 0x55, 0x4d, 0x53) // GUMS
    private const val FORMAT_VERSION = 1
    private const val MAX_ENCODED_BYTES = 64 * 1024 * 1024
    private const val MAX_COLLECTION_ENTRIES = 1_000_000
    private const val MAX_STRING_BYTES = 4 * 1024

    fun encode(state: SpoolState): ByteArray = encodeDocument(magic, FORMAT_VERSION) {
        writeSpoolState(state)
    }

    fun decode(encoded: ByteArray): SpoolState = decodeDocument(
        encoded = encoded,
        expectedMagic = magic,
        expectedVersion = FORMAT_VERSION,
    ) {
        readSpoolState()
    }

    private fun DataOutputStream.writeSpoolState(state: SpoolState) {
        writeULong(state.storeRevision)
        writeULong(state.quota.pressureAtBytes)
        writeULong(state.quota.maximumRetainedBytes)
        writeCount(state.captures.size)
        state.captures.entries.sortedBy { it.key.value }.forEach { (_, capture) ->
            writeCapture(capture)
        }
    }

    private fun DataInputStream.readSpoolState(): SpoolState {
        val revision = readULong()
        val quota = SpoolQuota(
            pressureAtBytes = readULong(),
            maximumRetainedBytes = readULong(),
        )
        val captures = LinkedHashMap<CaptureSessionId, CaptureSpoolState>()
        repeat(readCount()) {
            val capture = readCapture()
            require(captures.put(capture.captureSessionId, capture) == null) {
                "Duplicate capture identity"
            }
        }
        return SpoolState(revision, quota, captures)
    }

    private fun DataOutputStream.writeCapture(capture: CaptureSpoolState) {
        writeString(capture.captureSessionId.value)
        writeNullable(capture.remoteIngest) { remote ->
            writeString(remote.ingestSessionId.value)
            writeULong(remote.stateRevision)
        }
        writeCount(capture.streams.size)
        capture.streams.entries.sortedBy { it.key.value }.forEach { (_, stream) ->
            writeStream(stream)
        }
    }

    private fun DataInputStream.readCapture(): CaptureSpoolState {
        val captureId = CaptureSessionId(readString())
        val remote = readNullable {
            RemoteIngestState(
                ingestSessionId = IngestSessionId(readString()),
                stateRevision = readULong(),
            )
        }
        val streams = LinkedHashMap<StreamId, StreamSpoolState>()
        repeat(readCount()) {
            val stream = readStream()
            require(stream.descriptor.captureSessionId == captureId) {
                "Stream belongs to another capture"
            }
            require(streams.put(stream.descriptor.streamId, stream) == null) {
                "Duplicate stream identity"
            }
        }
        return CaptureSpoolState(captureId, streams, remote)
    }

    private fun DataOutputStream.writeStream(stream: StreamSpoolState) {
        writeStreamDescriptor(stream.descriptor)
        writeNullableULong(stream.sourceDurableThrough)
        writeNullable(stream.terminalRange) { writeRange(it) }
        writeCount(stream.chunks.size)
        stream.chunks.entries.sortedBy { it.key.value }.forEach { (_, record) ->
            writeChunkRecord(record)
        }
    }

    private fun DataInputStream.readStream(): StreamSpoolState {
        val descriptor = readStreamDescriptor()
        val sourceDurableThrough = readNullableULong()
        val terminal = readNullable { readRange() }
        val chunks = LinkedHashMap<ChunkId, DurableChunkRecord>()
        repeat(readCount()) {
            val record = readChunkRecord()
            val chunkId = record.chunk.descriptor.chunkId
            require(chunks.put(chunkId, record) == null) { "Duplicate chunk identity" }
        }
        return StreamSpoolState(descriptor, chunks, sourceDurableThrough, terminal)
    }

    private fun DataOutputStream.writeStreamDescriptor(descriptor: StreamDescriptor) {
        writeString(descriptor.captureSessionId.value)
        writeString(descriptor.streamId.value)
        writeULong(descriptor.sequencePolicy.first)
        writeULong(descriptor.sequencePolicy.maximumLast)
        writeULong(descriptor.maxChunkBytes)
        writeULong(descriptor.maxTotalBytes)
        writeEnum(descriptor.payloadFormat)
        writeString(descriptor.codec.name)
        writeString(descriptor.codec.configurationId.value)
        writeUInt(descriptor.codec.sampleRateHz)
        writeUInt(descriptor.codec.channelCount)
        writeUInt(descriptor.codec.frameDurationUs)
        writeString(descriptor.oggLayout.profile)
        writeUInt(descriptor.oggLayout.serialNumber)
        writeUInt(descriptor.oggLayout.preSkip48kSamples)
    }

    private fun DataInputStream.readStreamDescriptor() = StreamDescriptor(
        captureSessionId = CaptureSessionId(readString()),
        streamId = StreamId(readString()),
        sequencePolicy = SequencePolicy(readULong(), readULong()),
        maxChunkBytes = readULong(),
        maxTotalBytes = readULong(),
        payloadFormat = readEnum<MediaPayloadFormat>(),
        codec = AudioCodecDescriptor(
            name = readString(),
            configurationId = CodecConfigurationId(readString()),
            sampleRateHz = readUInt(),
            channelCount = readUInt(),
            frameDurationUs = readUInt(),
        ),
        oggLayout = OggOpusLayoutDescriptor(
            profile = readString(),
            serialNumber = readUInt(),
            preSkip48kSamples = readUInt(),
        ),
    )

    private fun DataOutputStream.writeChunkRecord(record: DurableChunkRecord) {
        writeChunkDescriptor(record.chunk.descriptor)
        writeString(record.chunk.payloadRef.value)
        writeNullable(record.cloudAck) { ack ->
            writeString(ack.ingestSessionId.value)
            writeString(ack.descriptorDigest.value)
            writeULong(ack.stateRevision)
        }
        writeNullable(record.uploadAttempt) { attempt ->
            writeString(attempt.ingestSessionId.value)
            writeString(attempt.descriptorDigest.value)
            writeEnum(attempt.disposition)
        }
    }

    private fun DataInputStream.readChunkRecord(): DurableChunkRecord {
        val chunk = DurableChunk(
            descriptor = readChunkDescriptor(),
            payloadRef = DurablePayloadRef(readString()),
        )
        val ack = readNullable {
            AppliedCloudAck(
                ingestSessionId = IngestSessionId(readString()),
                descriptorDigest = Sha256Digest(readString()),
                stateRevision = readULong(),
            )
        }
        val attempt = readNullable {
            UploadAttemptFence(
                ingestSessionId = IngestSessionId(readString()),
                descriptorDigest = Sha256Digest(readString()),
                disposition = readEnum<UploadAttemptDisposition>(),
            )
        }
        return DurableChunkRecord(chunk, ack, attempt)
    }

    internal fun encodeChunkDescriptor(descriptor: ChunkDescriptor): ByteArray = encodeDocument(
        magic = byteArrayOf(0x47, 0x55, 0x4d, 0x44), // GUMD
        version = 1,
    ) {
        writeChunkDescriptor(descriptor)
    }

    private fun DataOutputStream.writeChunkDescriptor(descriptor: ChunkDescriptor) {
        writeString(descriptor.captureSessionId.value)
        writeString(descriptor.streamId.value)
        writeString(descriptor.chunkId.value)
        writeRange(descriptor.sequenceRange)
        writeULong(descriptor.payloadBytes)
        writeEnum(descriptor.payloadFormat)
        writeString(descriptor.contentDigest.value)
        writeString(descriptor.codecConfigurationId.value)
        writeString(descriptor.sourceStartedAt.value)
        writeNullable(descriptor.edgeReceivedAt) { writeString(it.value) }
        writeStrictBoolean(descriptor.sourceRetransmission)
        writeNullable(descriptor.sourceDiscontinuityBefore) { discontinuity ->
            writeEnum(discontinuity.reason)
            writeULong(discontinuity.droppedFrameCount)
        }
    }

    private fun DataInputStream.readChunkDescriptor(): ChunkDescriptor = ChunkDescriptor(
        captureSessionId = CaptureSessionId(readString()),
        streamId = StreamId(readString()),
        chunkId = ChunkId(readString()),
        sequenceRange = readRange(),
        payloadBytes = readULong(),
        payloadFormat = readEnum<MediaPayloadFormat>(),
        contentDigest = Sha256Digest(readString()),
        codecConfigurationId = CodecConfigurationId(readString()),
        sourceStartedAt = SourceTimestamp(readString()),
        edgeReceivedAt = readNullable { SourceTimestamp(readString()) },
        sourceRetransmission = readStrictBoolean(),
        sourceDiscontinuityBefore = readNullable {
            SourceDiscontinuity(readEnum<DiscontinuityReason>(), readULong())
        },
    )

    private fun DataOutputStream.writeRange(range: SequenceRange) {
        writeULong(range.first)
        writeULong(range.last)
    }

    private fun DataInputStream.readRange() = SequenceRange(readULong(), readULong())

    private inline fun <T> DataOutputStream.writeNullable(value: T?, write: (T) -> Unit) {
        writeStrictBoolean(value != null)
        if (value != null) write(value)
    }

    private inline fun <T> DataInputStream.readNullable(read: () -> T): T? =
        if (readStrictBoolean()) read() else null

    private fun DataOutputStream.writeNullableULong(value: ULong?) {
        writeStrictBoolean(value != null)
        if (value != null) writeULong(value)
    }

    private fun DataInputStream.readNullableULong(): ULong? =
        if (readStrictBoolean()) readULong() else null

    private fun DataOutputStream.writeULong(value: ULong) = writeLong(value.toLong())
    private fun DataInputStream.readULong(): ULong = readLong().toULong()
    private fun DataOutputStream.writeUInt(value: UInt) = writeInt(value.toInt())
    private fun DataInputStream.readUInt(): UInt = readInt().toUInt()

    private fun DataOutputStream.writeCount(value: Int) {
        require(value in 0..MAX_COLLECTION_ENTRIES) { "Collection is too large" }
        writeInt(value)
    }

    private fun DataInputStream.readCount(): Int = readInt().also {
        require(it in 0..MAX_COLLECTION_ENTRIES) { "Invalid collection count" }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "String is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid string length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun DataOutputStream.writeStrictBoolean(value: Boolean) = writeByte(if (value) 1 else 0)

    private fun DataInputStream.readStrictBoolean(): Boolean = when (val value = readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> error("Invalid boolean value $value")
    }

    private fun DataOutputStream.writeEnum(value: Enum<*>) = writeString(value.name)

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val value = readString()
        return enumValues<T>().singleOrNull { it.name == value }
            ?: error("Unsupported ${T::class.simpleName} value")
    }

    private fun encodeDocument(
        magic: ByteArray,
        version: Int,
        writeBody: DataOutputStream.() -> Unit,
    ): ByteArray = try {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(magic)
            output.writeInt(version)
            output.writeBody()
        }
        bytes.toByteArray().also {
            require(it.size <= MAX_ENCODED_BYTES) { "Encoded metadata is too large" }
        }
    } catch (failure: SpoolCodecException) {
        throw failure
    } catch (failure: Exception) {
        throw SpoolCodecException("ANDROID_SPOOL_METADATA_ENCODE_FAILED", failure)
    }

    private fun <T> decodeDocument(
        encoded: ByteArray,
        expectedMagic: ByteArray,
        expectedVersion: Int,
        readBody: DataInputStream.() -> T,
    ): T {
        if (encoded.size > MAX_ENCODED_BYTES) {
            throw SpoolCodecException("ANDROID_SPOOL_METADATA_TOO_LARGE")
        }
        return try {
            val bytes = ByteArrayInputStream(encoded)
            DataInputStream(bytes).use { input ->
                val actualMagic = ByteArray(expectedMagic.size)
                input.readFully(actualMagic)
                require(actualMagic.contentEquals(expectedMagic)) { "Invalid metadata magic" }
                val version = input.readInt()
                require(version == expectedVersion) { "Unsupported metadata format" }
                val decoded = input.readBody()
                require(bytes.available() == 0) { "Trailing metadata bytes" }
                decoded
            }
        } catch (failure: SpoolCodecException) {
            throw failure
        } catch (failure: EOFException) {
            throw SpoolCodecException("ANDROID_SPOOL_METADATA_TRUNCATED", failure)
        } catch (failure: Exception) {
            throw SpoolCodecException("ANDROID_SPOOL_METADATA_INVALID", failure)
        }
    }
}

internal class SpoolCodecException(
    val failureCode: String,
    cause: Throwable? = null,
) : Exception(failureCode, cause)
