package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McubootApplicationArtifactInspectorTest {
    @Test
    fun `accepts a pinned structurally valid compatibility application`() {
        val fixture = mcubootFixture()

        val evidence = McubootApplicationArtifactInspector.inspect(fixture.bytes, fixture.manifest)

        assertEquals(fixture.manifest.fileSha256, evidence.fileSha256)
        assertEquals(fixture.manifest.mcubootImageHash, evidence.mcubootImageHash)
        assertEquals(fixture.manifest.compatibilityKeyHash, evidence.compatibilityKeyHash)
        assertEquals("0.0.0+0", evidence.mcubootVersion)
    }

    @Test
    fun `rejects payload mutation even when the pinned file size still matches`() {
        val fixture = mcubootFixture()
        val mutated = fixture.bytes.copyOf().also { it[HEADER_SIZE + 3] = (it[HEADER_SIZE + 3] + 1).toByte() }

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            McubootApplicationArtifactInspector.inspect(mutated, fixture.manifest)
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.ARTIFACT_REJECTED, error.code)
    }

    @Test
    fun `rejects a manifest with a different compatibility key`() {
        val fixture = mcubootFixture()
        val wrongManifest = fixture.manifest.copy(
            compatibilityKeyHash = FirmwareImageHash("00".repeat(32)),
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            McubootApplicationArtifactInspector.inspect(fixture.bytes, wrongManifest)
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.ARTIFACT_REJECTED, error.code)
    }

    @Test
    fun `rejects non-SHA-256 artifact digests at manifest construction`() {
        val fixture = mcubootFixture()

        assertFailsWith<IllegalArgumentException> {
            fixture.manifest.copy(fileSha256 = FirmwareImageHash("aa"))
        }
    }

    @Test
    fun `rejects an extra security-counter TLV even when the exact file manifest is updated`() {
        val fixture = mcubootFixture()
        val tlvStart = HEADER_SIZE + PAYLOAD_SIZE
        val signatureTlvStart = tlvStart + 4 + (4 + 32) + (4 + 32)
        val mutated = ByteArray(fixture.bytes.size + 8)
        fixture.bytes.copyInto(mutated, endIndex = signatureTlvStart)
        mutated[signatureTlvStart] = 0x50.toByte()
        mutated.writeU16(signatureTlvStart + 2, 4)
        fixture.bytes.copyInto(
            destination = mutated,
            destinationOffset = signatureTlvStart + 8,
            startIndex = signatureTlvStart,
        )
        mutated.writeU16(tlvStart + 2, mutated.size - tlvStart)
        val matchingFileManifest = fixture.manifest.copy(
            fileSizeBytes = mutated.size,
            fileSha256 = FirmwareImageHash.copyOf(mutated.sha256()),
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            McubootApplicationArtifactInspector.inspect(mutated, matchingFileManifest)
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.ARTIFACT_REJECTED, error.code)
    }

    @Test
    fun `planner owns an immutable copy of accepted image bytes`() {
        val fixture = mcubootFixture()
        val sourceBytes = fixture.bytes.copyOf()
        val endpoint = endpoint()
        val release = release(fixture.manifest)
        val prepared = OmiCv1ApplicationUpdatePlanner.prepare(
            endpoint,
            stableInspection(endpoint, release.source),
            release,
            sourceBytes,
        )

        sourceBytes.fill(0)

        assertContentEquals(fixture.bytes, prepared.copyImageBytes())
    }

    companion object {
        internal const val HEADER_SIZE = 512
        private const val PAYLOAD_SIZE = 32

        internal data class Fixture(
            val bytes: ByteArray,
            val manifest: OmiCv1ApplicationArtifactManifest,
        )

        internal fun mcubootFixture(payloadSeed: Int = 17): Fixture {
            val payload = ByteArray(PAYLOAD_SIZE) { (payloadSeed + it).toByte() }
            val keyHash = ByteArray(32) { 0x5a.toByte() }
            val signature = ByteArray(256) { (it xor 0xa5).toByte() }
            val tlvSize = 4 + (4 + 32) + (4 + 32) + (4 + 256)
            val bytes = ByteArray(HEADER_SIZE + PAYLOAD_SIZE + tlvSize)
            bytes.writeU32(0, 0x96f3b83dL)
            bytes.writeU16(8, HEADER_SIZE)
            bytes.writeU16(10, 0)
            bytes.writeU32(12, PAYLOAD_SIZE.toLong())
            bytes.writeU32(16, 0)
            payload.copyInto(bytes, HEADER_SIZE)
            val payloadDigest = bytes.copyOfRange(0, HEADER_SIZE + PAYLOAD_SIZE).sha256()
            var cursor = HEADER_SIZE + PAYLOAD_SIZE
            bytes.writeU16(cursor, 0x6907)
            bytes.writeU16(cursor + 2, tlvSize)
            cursor += 4
            cursor = bytes.writeTlv(cursor, 0x10, payloadDigest)
            cursor = bytes.writeTlv(cursor, 0x01, keyHash)
            cursor = bytes.writeTlv(cursor, 0x20, signature)
            check(cursor == bytes.size)
            return Fixture(
                bytes = bytes,
                manifest = OmiCv1ApplicationArtifactManifest(
                    identity = "test/application",
                    fileSizeBytes = bytes.size,
                    fileSha256 = FirmwareImageHash.copyOf(bytes.sha256()),
                    payloadSizeBytes = PAYLOAD_SIZE,
                    mcubootImageHash = FirmwareImageHash.copyOf(payloadDigest),
                    compatibilityKeyHash = FirmwareImageHash.copyOf(keyHash),
                    mcubootVersion = "0.0.0+0",
                    signatureVerifiedOffline = true,
                ),
            )
        }

        private fun ByteArray.writeTlv(offset: Int, type: Int, value: ByteArray): Int {
            this[offset] = type.toByte()
            this[offset + 1] = 0
            writeU16(offset + 2, value.size)
            value.copyInto(this, offset + 4)
            return offset + 4 + value.size
        }

        private fun ByteArray.writeU16(offset: Int, value: Int) {
            this[offset] = value.toByte()
            this[offset + 1] = (value ushr 8).toByte()
        }

        private fun ByteArray.writeU32(offset: Int, value: Long) {
            repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
        }

        internal fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
    }
}
