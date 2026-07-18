package dev.gumi.devices.omicv1

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RingFixtureConformanceTest {
    private val fixture: JsonObject by lazy {
        val text = assertNotNull(javaClass.getResource("/fixtures.json"))
            .readText()
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `portable ring codec satisfies every device-owned fixture`() {
        assertEquals("gumi.byte-fixtures/v1", fixture.string("fixture_schema"))
        assertEquals("omi.ring/v1", fixture.string("wire_protocol"))

        fixture.array("cases").forEach { element ->
            val case = element.jsonObject
            when (case.string("operation")) {
                "decode_status" -> verifyStatus(case)
                "decode_notification" -> verifyNotification(case)
                "encode_command" -> verifyCommand(case)
                "decode_audio_payload" -> verifyAudio(case)
                "reassemble_and_decode_record" -> verifyDecodedRecord(case)
                "reassemble_records" -> verifyReassembly(case)
                else -> error("Unknown fixture operation in ${case.string("id")}")
            }
        }
    }

    private fun verifyStatus(case: JsonObject) {
        val input = case.obj("input").bytes()
        val expected = case.obj("expect")
        val actual = RingProtocol.decodeStatus(input)

        if (expected.containsKey("error")) {
            assertNull(actual, case.string("id"))
            return
        }

        assertNotNull(actual, case.string("id"))
        assertEquals(expected.long("used_bytes"), actual.usedBytes)
        assertEquals(expected.long("unread_packets"), actual.unreadPackets)
        assertEquals(expected.long("free_bytes"), actual.freeBytes)
        assertEquals(expected.long("rtc_valid"), actual.rtcValid)
    }

    private fun verifyNotification(case: JsonObject) {
        val input = case.obj("input").bytes()
        val expected = case.obj("expect")

        when (expected.string("type")) {
            "info" -> {
                val actual = assertNotNull(RingProtocol.decodeInfoNotification(input), case.string("id"))
                assertEquals(expected.ulong("read_sequence"), actual.readSequence)
                assertEquals(expected.ulong("write_sequence"), actual.writeSequence)
                assertEquals(expected.long("capacity_packets"), actual.capacityPackets)
                assertEquals(expected.ulong("dropped_packets"), actual.droppedPackets)
                assertEquals(expected.int("packet_size_bytes"), actual.packetSizeBytes)
                assertEquals(expected.ulong("unread_packets"), actual.unreadPackets)
            }

            "done" -> {
                val actual = assertNotNull(RingProtocol.decodeDoneNotification(input), case.string("id"))
                assertEquals(expected.int("status"), actual.status)
                assertEquals(expected.ulong("next_sequence"), actual.nextSequence)
            }

            "read_begin" -> {
                val actual = assertNotNull(RingProtocol.decodeReadBeginNotification(input), case.string("id"))
                assertEquals(expected.ulong("transfer_start_sequence"), actual.transferStartSequence)
                assertEquals(expected.long("packet_count"), actual.packetCount)
            }

            else -> error("Unknown notification fixture ${case.string("id")}")
        }
    }

    private fun verifyCommand(case: JsonObject) {
        val input = case.obj("input")
        val actual = when (input.string("command")) {
            "info" -> RingProtocol.encodeInfoCommand()
            "read" -> RingProtocol.encodeReadCommand(
                startSequence = input.optionalULong("start_sequence", "start_sequence_hex"),
                packetCount = input["packet_count"]?.jsonPrimitive?.content?.toLong(),
            )
            "advance" -> RingProtocol.encodeAdvanceCommand(
                input.optionalULong("new_read_sequence", "new_read_sequence_hex"),
            )
            else -> error("Unknown command fixture ${case.string("id")}")
        }

        assertEquals(case.obj("expect").string("hex"), actual.toHex(), case.string("id"))
    }

    private fun verifyAudio(case: JsonObject) {
        val actual = RingProtocol.decodeAudioPayload(case.obj("input").bytes())
        val expectedFrames = case.obj("expect").array("frames_hex").map { it.jsonPrimitive.content }

        assertEquals(expectedFrames, actual.map(ByteArray::toHex), case.string("id"))
    }

    private fun verifyDecodedRecord(case: JsonObject) {
        val input = case.obj("input")
        val record = input.obj("record").bytes()
        val splitOffsets = input.array("split_at_offsets").map { it.jsonPrimitive.content.toInt() }
        val reassembler = RingRecordReassembler()

        var priorOffset = 0
        (splitOffsets + record.size).forEach { offset ->
            reassembler.append(record.copyOfRange(priorOffset, offset))
            priorOffset = offset
        }

        val records = reassembler.drainRecords()
        val expected = case.obj("expect")
        assertEquals(expected.int("records"), records.size, case.string("id"))
        assertEquals(expected.int("pending_bytes"), reassembler.pendingBytes, case.string("id"))

        val decoded = RingProtocol.decodeRecord(records.single())
        assertEquals(expected.long("timestamp"), decoded.timestamp, case.string("id"))
        assertEquals(expected.array("frames_hex").map { it.jsonPrimitive.content }, decoded.frames.map(ByteArray::toHex))
    }

    private fun verifyReassembly(case: JsonObject) {
        val reassembler = RingRecordReassembler()
        case.obj("input").array("chunks").forEach { reassembler.append(it.jsonObject.bytes()) }

        val records = reassembler.drainRecords()
        val expected = case.obj("expect")
        assertEquals(expected.int("records"), records.size, case.string("id"))
        assertEquals(expected.int("record_size_bytes"), records.single().size, case.string("id"))
        assertEquals(expected.int("pending_bytes"), reassembler.pendingBytes, case.string("id"))

        expected.array("segments").forEach { element ->
            val segment = element.jsonObject
            val expectedByte = segment.string("hex").hexToBytes().single()
            val actual = records.single().copyOfRange(segment.int("start"), segment.int("end_exclusive"))
            assertContentEquals(ByteArray(actual.size) { expectedByte }, actual, case.string("id"))
        }
    }
}

private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.int(key: String): Int = string(key).toInt()

private fun JsonObject.long(key: String): Long = string(key).toLong()

private fun JsonObject.ulong(key: String): ULong = string(key).toULong()

private fun JsonObject.optionalULong(decimalKey: String, hexKey: String): ULong =
    get(decimalKey)?.jsonPrimitive?.content?.toULong()
        ?: getValue(hexKey).jsonPrimitive.content.toULong(radix = 16)

private fun JsonObject.bytes(): ByteArray = when {
    containsKey("hex") -> string("hex").hexToBytes()
    containsKey("parts") -> array("parts").flatMap { it.jsonObject.bytes().asIterable() }.toByteArray()
    containsKey("u8") -> byteArrayOf(int("u8").toByte())
    containsKey("repeat") -> {
        val repeat = obj("repeat")
        val repeated = repeat.string("hex").hexToBytes()
        buildList {
            repeat(repeat.int("count")) { addAll(repeated.asIterable()) }
        }.toByteArray()
    }
    else -> error("Unknown byte recipe: $this")
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex must have an even number of characters" }
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
