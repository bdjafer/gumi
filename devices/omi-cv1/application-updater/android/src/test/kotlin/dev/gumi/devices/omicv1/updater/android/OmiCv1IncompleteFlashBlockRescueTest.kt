package dev.gumi.devices.omicv1.updater.android

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OmiCv1IncompleteFlashBlockRescueTest {
    @Test
    fun `recovery artifact is aligned with erased bytes and remains one byte incomplete`() {
        val image = ByteArray(OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.fileSizeBytes) {
            (it and 0xff).toByte()
        }

        val rescue = OmiCv1IncompleteFlashBlockRescue.create(image)

        assertEquals(107_008, rescue.stagedBytes.size)
        assertEquals(107_009, rescue.advertisedSizeBytes)
        assertContentEquals(image, rescue.stagedBytes.copyOfRange(0, image.size))
        assertTrue(
            rescue.stagedBytes.copyOfRange(image.size, rescue.stagedBytes.size).all {
                it == 0xff.toByte()
            },
        )
        assertContentEquals(
            MessageDigest.getInstance("SHA-256").digest(image).copyOf(31),
            rescue.resumeShaPrefix,
        )
    }

    @Test
    fun `already aligned image still leaves exactly one unsent byte`() {
        val image = ByteArray(1_024) { 0x5a }

        val rescue = OmiCv1IncompleteFlashBlockRescue.create(image)

        assertContentEquals(image, rescue.stagedBytes)
        assertEquals(1_025, rescue.advertisedSizeBytes)
    }

    @Test
    fun `empty rescue target is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            OmiCv1IncompleteFlashBlockRescue.create(byteArrayOf())
        }
    }
}
