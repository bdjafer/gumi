package dev.gumi.devices.omicv1

data class RingStatus(
    val usedBytes: Long,
    val unreadPackets: Long,
    val freeBytes: Long,
    val rtcValid: Long,
)

data class RingInfo(
    val readSequence: ULong,
    val writeSequence: ULong,
    val capacityPackets: Long,
    val droppedPackets: ULong,
    val packetSizeBytes: Int,
) {
    val unreadPackets: ULong
        get() = writeSequence - readSequence
}

data class DoneNotification(
    val status: Int,
    val nextSequence: ULong,
) {
    val isOk: Boolean
        get() = status == 0
}

data class ReadBeginNotification(
    val transferStartSequence: ULong,
    val packetCount: Long,
)

data class RingRecord(
    val timestamp: Long,
    val frames: List<ByteArray>,
)

object RingProtocol {
    const val RECORD_SIZE = 444
    const val TIMESTAMP_SIZE = 4
    const val AUDIO_PAYLOAD_SIZE = RECORD_SIZE - TIMESTAMP_SIZE

    const val NOTIFY_ACK = 0x01
    const val NOTIFY_INFO = 0x02
    const val NOTIFY_DATA = 0x03
    const val NOTIFY_DONE = 0x04
    const val NOTIFY_READ_BEGIN = 0x05

    const val COMMAND_STOP = 0x03
    const val COMMAND_INFO = 0x10
    const val COMMAND_READ = 0x11
    const val COMMAND_ADVANCE = 0x12
    const val COMMAND_CLEAR = 0x13

    fun decodeStatus(bytes: ByteArray): RingStatus? {
        if (bytes.size < 16) return null
        return RingStatus(
            usedBytes = bytes.readU32LittleEndian(0),
            unreadPackets = bytes.readU32LittleEndian(4),
            freeBytes = bytes.readU32LittleEndian(8),
            rtcValid = bytes.readU32LittleEndian(12),
        )
    }

    fun decodeInfoNotification(bytes: ByteArray): RingInfo? {
        if (bytes.size < 31 || bytes[0].toUnsignedInt() != NOTIFY_INFO) return null
        return RingInfo(
            readSequence = bytes.readU64BigEndian(1),
            writeSequence = bytes.readU64BigEndian(9),
            capacityPackets = bytes.readU32BigEndian(17),
            droppedPackets = bytes.readU64BigEndian(21),
            packetSizeBytes = bytes.readU16BigEndian(29),
        )
    }

    fun decodeDoneNotification(bytes: ByteArray): DoneNotification? {
        if (bytes.size < 10 || bytes[0].toUnsignedInt() != NOTIFY_DONE) return null
        return DoneNotification(
            status = bytes[1].toUnsignedInt(),
            nextSequence = bytes.readU64BigEndian(2),
        )
    }

    fun decodeReadBeginNotification(bytes: ByteArray): ReadBeginNotification? {
        if (bytes.size < 13 || bytes[0].toUnsignedInt() != NOTIFY_READ_BEGIN) return null
        return ReadBeginNotification(
            transferStartSequence = bytes.readU64BigEndian(1),
            packetCount = bytes.readU32BigEndian(9),
        )
    }

    fun encodeInfoCommand(): ByteArray = byteArrayOf(COMMAND_INFO.toByte())

    fun encodeReadCommand(
        startSequence: ULong,
        packetCount: Long? = null,
    ): ByteArray {
        require(packetCount == null || packetCount in 0..0xffff_ffffL)
        val hasCount = packetCount != null && packetCount > 0
        return ByteArray(if (hasCount) 13 else 9).also { output ->
            output[0] = COMMAND_READ.toByte()
            output.writeU64BigEndian(1, startSequence)
            if (hasCount) output.writeU32BigEndian(9, packetCount)
        }
    }

    fun encodeAdvanceCommand(newReadSequence: ULong): ByteArray = ByteArray(9).also { output ->
        output[0] = COMMAND_ADVANCE.toByte()
        output.writeU64BigEndian(1, newReadSequence)
    }

    fun decodeRecord(record: ByteArray): RingRecord {
        require(record.size == RECORD_SIZE) { "Ring record must be exactly $RECORD_SIZE bytes" }
        return RingRecord(
            timestamp = record.readU32BigEndian(0),
            frames = decodeAudioPayload(record.copyOfRange(TIMESTAMP_SIZE, RECORD_SIZE)),
        )
    }

    fun decodeAudioPayload(audio: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < audio.size - 1) {
            val size = audio[offset].toUnsignedInt()
            if (size == 0) {
                offset += 1
                continue
            }
            if (offset + 1 + size >= audio.size) break
            frames += audio.copyOfRange(offset + 1, offset + 1 + size)
            offset += size + 1
        }
        return frames
    }
}

class RingRecordReassembler {
    private var buffer = ByteArray(RingProtocol.RECORD_SIZE * 2)
    private var readOffset = 0
    private var writeOffset = 0

    val pendingBytes: Int
        get() = writeOffset - readOffset

    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        ensureWritableBytes(bytes.size)
        bytes.copyInto(buffer, destinationOffset = writeOffset)
        writeOffset += bytes.size
    }

    fun drainRecords(): List<ByteArray> = buildList {
        while (pendingBytes >= RingProtocol.RECORD_SIZE) {
            add(buffer.copyOfRange(readOffset, readOffset + RingProtocol.RECORD_SIZE))
            readOffset += RingProtocol.RECORD_SIZE
        }
        if (readOffset == writeOffset) {
            readOffset = 0
            writeOffset = 0
        }
    }

    private fun ensureWritableBytes(count: Int) {
        if (buffer.size - writeOffset >= count) return

        if (readOffset > 0 && buffer.size - pendingBytes >= count) {
            buffer.copyInto(
                destination = buffer,
                destinationOffset = 0,
                startIndex = readOffset,
                endIndex = writeOffset,
            )
            writeOffset = pendingBytes
            readOffset = 0
            return
        }

        val requiredCapacity = pendingBytes + count
        val expanded = ByteArray(maxOf(buffer.size * 2, requiredCapacity))
        buffer.copyInto(
            destination = expanded,
            destinationOffset = 0,
            startIndex = readOffset,
            endIndex = writeOffset,
        )
        writeOffset = pendingBytes
        readOffset = 0
        buffer = expanded
    }
}

private fun Byte.toUnsignedInt(): Int = toInt() and 0xff

private fun ByteArray.readU16BigEndian(offset: Int): Int =
    (this[offset].toUnsignedInt() shl 8) or this[offset + 1].toUnsignedInt()

private fun ByteArray.readU32LittleEndian(offset: Int): Long =
    (0 until 4).fold(0L) { value, byteIndex ->
        value or (this[offset + byteIndex].toUnsignedInt().toLong() shl (byteIndex * 8))
    }

private fun ByteArray.readU32BigEndian(offset: Int): Long =
    (0 until 4).fold(0L) { value, byteIndex ->
        (value shl 8) or this[offset + byteIndex].toUnsignedInt().toLong()
    }

private fun ByteArray.readU64BigEndian(offset: Int): ULong =
    (0 until 8).fold(0uL) { value, byteIndex ->
        (value shl 8) or this[offset + byteIndex].toUnsignedInt().toULong()
    }

private fun ByteArray.writeU32BigEndian(offset: Int, value: Long) {
    for (byteIndex in 0 until 4) {
        this[offset + byteIndex] = (value shr ((3 - byteIndex) * 8)).toByte()
    }
}

private fun ByteArray.writeU64BigEndian(offset: Int, value: ULong) {
    for (byteIndex in 0 until 8) {
        this[offset + byteIndex] = (value shr ((7 - byteIndex) * 8)).toByte()
    }
}
