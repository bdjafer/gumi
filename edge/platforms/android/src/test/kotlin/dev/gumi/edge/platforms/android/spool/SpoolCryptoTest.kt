package dev.gumi.edge.platforms.android.spool

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpoolCryptoTest {
    @Test
    fun `AES GCM envelope uses fresh provider nonce and authenticates associated data`() {
        val cipher = AeadEnvelopeCipher(TestSpoolKeyring())
        val plaintext = byteArrayOf(1, 2, 3, 4)
        val aad = "descriptor-a".encodeToByteArray()

        val first = cipher.encrypt(plaintext, aad)
        val second = cipher.encrypt(plaintext, aad)

        assertNotEquals(first.toList(), second.toList())
        assertContentEquals(plaintext, cipher.decrypt(first, aad, plaintext.size).plaintext)
        assertEquals(
            "ANDROID_SPOOL_INTEGRITY_FAILED",
            assertFailsWith<SpoolCryptoException> {
                cipher.decrypt(first, "descriptor-b".encodeToByteArray(), plaintext.size)
            }.failureCode,
        )
        val tampered = first.copyOf().apply { this[lastIndex] = this[lastIndex].inc() }
        assertEquals(
            "ANDROID_SPOOL_INTEGRITY_FAILED",
            assertFailsWith<SpoolCryptoException> {
                cipher.decrypt(tampered, aad, plaintext.size)
            }.failureCode,
        )
    }

    @Test
    fun `versioned envelope reader keeps old keys readable when test policy selects a new version`() {
        val keyring = TestSpoolKeyring(activeVersion = 1)
        val cipher = AeadEnvelopeCipher(keyring)
        val aad = byteArrayOf(5)
        val oldEnvelope = cipher.encrypt(byteArrayOf(1), aad)

        keyring.rotateTo(2)
        val newEnvelope = cipher.encrypt(byteArrayOf(2), aad)

        assertEquals(1, cipher.decrypt(oldEnvelope, aad, 1).keyVersion)
        assertEquals(2, cipher.decrypt(newEnvelope, aad, 1).keyVersion)
        keyring.remove(1)
        assertEquals(
            "ANDROID_SPOOL_KEY_VERSION_UNAVAILABLE",
            assertFailsWith<SpoolCryptoException> {
                cipher.decrypt(oldEnvelope, aad, 1)
            }.failureCode,
        )
    }

    @Test
    fun `payload locator is stable across encryption rotation and reveals no media identity`() {
        val keyring = TestSpoolKeyring()
        val descriptor = testDescriptor(byteArrayOf(7, 7, 7))
        val first = PayloadIdentity.reference(descriptor, keyring)

        keyring.rotateTo(2)
        val second = PayloadIdentity.reference(descriptor, keyring)

        assertEquals(first.value, second.value)
        assertTrue(PayloadIdentity.isValidReference(first.value))
        assertFalse(first.value.contains(descriptor.captureSessionId.value))
        assertFalse(first.value.contains(descriptor.streamId.value))
        assertFalse(first.value.contains(descriptor.chunkId.value))
        assertEquals("<redacted-durable-payload-ref>", first.toString())
    }
}
