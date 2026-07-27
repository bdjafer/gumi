package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import java.security.MessageDigest

internal object McubootApplicationArtifactInspector {
    private const val IMAGE_MAGIC = 0x96f3b83dL
    private const val MIN_HEADER_SIZE = 32
    private const val TLV_INFO_SIZE = 4
    private const val TLV_ENTRY_HEADER_SIZE = 4
    private const val UNPROTECTED_TLV_MAGIC = 0x6907
    private const val KEY_HASH_TLV = 0x01
    private const val SHA256_TLV = 0x10
    private const val RSA2048_PSS_TLV = 0x20
    private const val SHA256_BYTES = 32
    private const val RSA2048_SIGNATURE_BYTES = 256

    fun inspect(
        sourceBytes: ByteArray,
        manifest: OmiCv1ApplicationArtifactManifest,
    ): OmiCv1ApplicationArtifactEvidence {
        val bytes = sourceBytes.copyOf()
        rejectUnless(bytes.size == manifest.fileSizeBytes, "file size does not match the pinned manifest")
        rejectUnless(bytes.size >= MIN_HEADER_SIZE, "image header is truncated")
        rejectUnless(bytes.u32(0) == IMAGE_MAGIC, "MCUboot image magic is invalid")

        val headerSize = bytes.u16(8)
        val protectedTlvSize = bytes.u16(10)
        val payloadSize = bytes.u32(12).toIntExact("payload size")
        val loadAddress = bytes.u32(4)
        val flags = bytes.u32(16)
        rejectUnless(headerSize >= MIN_HEADER_SIZE, "MCUboot header is too small")
        rejectUnless(headerSize == OFFICIAL_HEADER_SIZE, "unexpected MCUboot header size")
        rejectUnless(protectedTlvSize == 0, "protected TLVs are not allowed in compatibility images")
        rejectUnless(loadAddress == 0L, "unexpected application load address")
        rejectUnless(flags == 0L, "unexpected MCUboot image flags")
        requireRange(bytes, 0, headerSize, "header")

        val version = "${bytes.u8(20)}.${bytes.u8(21)}.${bytes.u16(22)}+${bytes.u32(24)}"
        val payloadEnd = checkedEnd(headerSize, payloadSize, bytes.size, "payload")
        rejectUnless(payloadSize == manifest.payloadSizeBytes, "payload size does not match the pinned manifest")

        val tlvStart = checkedEnd(payloadEnd, protectedTlvSize, bytes.size, "protected TLV area")
        requireRange(bytes, tlvStart, TLV_INFO_SIZE, "unprotected TLV header")
        rejectUnless(bytes.u16(tlvStart) == UNPROTECTED_TLV_MAGIC, "unprotected TLV magic is invalid")
        val tlvSize = bytes.u16(tlvStart + 2)
        rejectUnless(tlvSize >= TLV_INFO_SIZE, "unprotected TLV area is too small")
        val tlvEnd = checkedEnd(tlvStart, tlvSize, bytes.size, "unprotected TLV area")
        rejectUnless(tlvEnd == bytes.size, "bytes exist outside the declared MCUboot image")

        val entries = mutableListOf<TlvEntry>()
        var cursor = tlvStart + TLV_INFO_SIZE
        while (cursor < tlvEnd) {
            requireRange(bytes, cursor, TLV_ENTRY_HEADER_SIZE, "TLV entry header")
            val type = bytes.u8(cursor)
            val reserved = bytes.u8(cursor + 1)
            val length = bytes.u16(cursor + 2)
            rejectUnless(reserved == 0, "TLV entry has a non-zero reserved byte")
            val valueStart = cursor + TLV_ENTRY_HEADER_SIZE
            val valueEnd = checkedEnd(valueStart, length, tlvEnd, "TLV value")
            entries += TlvEntry(type, valueStart, valueEnd)
            cursor = valueEnd
        }
        rejectUnless(cursor == tlvEnd, "TLV entries do not end at the declared boundary")

        val digestEntry = entries.requireUnique(SHA256_TLV, SHA256_BYTES, "SHA-256")
        val keyHashEntry = entries.requireUnique(KEY_HASH_TLV, SHA256_BYTES, "key hash")
        val signatureEntry = entries.requireUnique(
            RSA2048_PSS_TLV,
            RSA2048_SIGNATURE_BYTES,
            "RSA-2048 PSS signature",
        )
        rejectUnless(entries.map(TlvEntry::type) == listOf(SHA256_TLV, KEY_HASH_TLV, RSA2048_PSS_TLV)) {
            "MCUboot TLV layout differs from the qualified application format"
        }
        rejectUnless(entries.last() == signatureEntry && signatureEntry.valueEnd == bytes.size) {
            "RSA-2048 PSS signature must be the final image value"
        }

        val computedImageDigest = bytes.sha256(0, payloadEnd + protectedTlvSize)
        val encodedImageDigest = bytes.copyOfRange(digestEntry.valueStart, digestEntry.valueEnd)
        rejectUnless(computedImageDigest.contentEquals(encodedImageDigest)) {
            "encoded MCUboot image digest does not match the image bytes"
        }

        val evidence = OmiCv1ApplicationArtifactEvidence(
            fileSizeBytes = bytes.size,
            fileSha256 = FirmwareImageHash.copyOf(bytes.sha256()),
            headerSizeBytes = headerSize,
            payloadSizeBytes = payloadSize,
            mcubootImageHash = FirmwareImageHash.copyOf(encodedImageDigest),
            compatibilityKeyHash = FirmwareImageHash.copyOf(
                bytes.copyOfRange(keyHashEntry.valueStart, keyHashEntry.valueEnd),
            ),
            mcubootVersion = version,
        )
        rejectUnless(evidence.fileSha256 == manifest.fileSha256, "file digest does not match the pinned manifest")
        rejectUnless(evidence.mcubootImageHash == manifest.mcubootImageHash) {
            "MCUboot image digest does not match the pinned manifest"
        }
        rejectUnless(evidence.compatibilityKeyHash == manifest.compatibilityKeyHash) {
            "compatibility key hash does not match the pinned manifest"
        }
        rejectUnless(evidence.mcubootVersion == manifest.mcubootVersion) {
            "MCUboot version does not match the pinned manifest"
        }
        return evidence
    }

    private data class TlvEntry(val type: Int, val valueStart: Int, val valueEnd: Int)

    private fun List<TlvEntry>.requireUnique(type: Int, size: Int, label: String): TlvEntry {
        val matches = filter { it.type == type }
        rejectUnless(matches.size == 1, "expected exactly one $label TLV")
        return matches.single().also {
            rejectUnless(it.valueEnd - it.valueStart == size, "$label TLV has an unexpected size")
        }
    }

    private fun rejectUnless(condition: Boolean, message: () -> String) = rejectUnless(condition, message())

    private fun rejectUnless(condition: Boolean, message: String) {
        if (!condition) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.ARTIFACT_REJECTED,
                message,
            )
        }
    }

    private fun requireRange(bytes: ByteArray, start: Int, size: Int, label: String) {
        checkedEnd(start, size, bytes.size, label)
    }

    private fun checkedEnd(start: Int, size: Int, limit: Int, label: String): Int {
        if (start < 0 || size < 0 || start > limit || size > limit - start) {
            rejectUnless(false, "$label extends outside the image")
        }
        return start + size
    }

    private fun Long.toIntExact(label: String): Int {
        if (this > Int.MAX_VALUE) rejectUnless(false, "$label is too large")
        return toInt()
    }

    private fun ByteArray.u8(offset: Int): Int = get(offset).toInt() and 0xff

    private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

    private fun ByteArray.u32(offset: Int): Long =
        u8(offset).toLong() or
            (u8(offset + 1).toLong() shl 8) or
            (u8(offset + 2).toLong() shl 16) or
            (u8(offset + 3).toLong() shl 24)

    private fun ByteArray.sha256(start: Int = 0, end: Int = size): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(copyOfRange(start, end))

    private const val OFFICIAL_HEADER_SIZE = 512
}

/** Generic facade used by verified application and network-core MCUboot artifacts. */
internal object McubootArtifactInspector {
    fun inspect(
        sourceBytes: ByteArray,
        manifest: OmiCv1McubootArtifactManifest,
    ): OmiCv1McubootArtifactEvidence =
        McubootApplicationArtifactInspector.inspect(sourceBytes, manifest)
}
