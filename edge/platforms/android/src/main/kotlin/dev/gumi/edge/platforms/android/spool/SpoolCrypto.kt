package dev.gumi.edge.platforms.android.spool

import dev.gumi.edge.runtime.spool.ChunkDescriptor
import dev.gumi.edge.runtime.spool.DurablePayloadRef
import dev.gumi.edge.runtime.spool.Sha256Digest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface SpoolKeyring {
    val activeEncryptionKeyVersion: Int

    fun encryptionKey(version: Int): SecretKey?

    fun locatorKey(): SecretKey
}

internal class AeadEnvelopeCipher(
    private val keyring: SpoolKeyring,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val keyVersion = keyring.activeEncryptionKeyVersion
        require(keyVersion > 0) { "Encryption key version must be positive" }
        val key = keyring.encryptionKey(keyVersion)
            ?: throw SpoolCryptoException("ANDROID_SPOOL_ACTIVE_KEY_UNAVAILABLE")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, secureRandom)
            cipher.updateAAD(associatedData)
            val ciphertext = cipher.doFinal(plaintext)
            val nonce = cipher.iv
            require(nonce.size == NONCE_BYTES) { "Provider returned an unsupported GCM nonce" }
            encodeEnvelope(keyVersion, nonce, ciphertext)
        } catch (failure: SpoolCryptoException) {
            throw failure
        } catch (failure: GeneralSecurityException) {
            throw classifySecurityFailure(failure, "ANDROID_SPOOL_ENCRYPTION_FAILED")
        } catch (failure: IllegalArgumentException) {
            throw SpoolCryptoException("ANDROID_SPOOL_ENCRYPTION_FAILED", failure)
        }
    }

    fun decrypt(
        envelope: ByteArray,
        associatedData: ByteArray,
        maximumPlaintextBytes: Int,
    ): DecryptedEnvelope {
        require(maximumPlaintextBytes >= 0) { "Maximum plaintext size cannot be negative" }
        val parsed = decodeEnvelope(envelope, maximumPlaintextBytes)
        val key = keyring.encryptionKey(parsed.keyVersion)
            ?: throw SpoolCryptoException("ANDROID_SPOOL_KEY_VERSION_UNAVAILABLE")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, parsed.nonce),
            )
            cipher.updateAAD(associatedData)
            val plaintext = cipher.doFinal(parsed.ciphertext)
            if (plaintext.size > maximumPlaintextBytes) {
                throw SpoolCryptoException("ANDROID_SPOOL_ENVELOPE_TOO_LARGE")
            }
            DecryptedEnvelope(parsed.keyVersion, plaintext)
        } catch (failure: SpoolCryptoException) {
            throw failure
        } catch (failure: AEADBadTagException) {
            throw SpoolCryptoException("ANDROID_SPOOL_INTEGRITY_FAILED", failure)
        } catch (failure: GeneralSecurityException) {
            throw classifySecurityFailure(failure, "ANDROID_SPOOL_DECRYPTION_FAILED")
        }
    }

    private fun encodeEnvelope(
        keyVersion: Int,
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(ciphertext.size >= TAG_BYTES) { "GCM ciphertext is missing its tag" }
        return ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(ENVELOPE_MAGIC)
                output.writeInt(ENVELOPE_VERSION)
                output.writeInt(keyVersion)
                output.writeInt(nonce.size)
                output.write(nonce)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }
    }

    private fun decodeEnvelope(
        envelope: ByteArray,
        maximumPlaintextBytes: Int,
    ): ParsedEnvelope {
        val maximumEnvelopeBytes = maximumPlaintextBytes.toLong() + MAX_ENVELOPE_OVERHEAD
        if (envelope.size.toLong() > maximumEnvelopeBytes) {
            throw SpoolCryptoException("ANDROID_SPOOL_ENVELOPE_TOO_LARGE")
        }
        try {
            val bytes = ByteArrayInputStream(envelope)
            return DataInputStream(bytes).use { input ->
                val magic = ByteArray(ENVELOPE_MAGIC.size)
                input.readFully(magic)
                require(magic.contentEquals(ENVELOPE_MAGIC)) { "Invalid envelope magic" }
                require(input.readInt() == ENVELOPE_VERSION) { "Unsupported envelope version" }
                val keyVersion = input.readInt()
                require(keyVersion > 0) { "Invalid key version" }
                val nonceLength = input.readInt()
                require(nonceLength == NONCE_BYTES) { "Invalid GCM nonce length" }
                val nonce = ByteArray(nonceLength)
                input.readFully(nonce)
                val ciphertextLength = input.readInt()
                require(ciphertextLength in TAG_BYTES..maximumEnvelopeBytes.toInt()) {
                    "Invalid ciphertext length"
                }
                val ciphertext = ByteArray(ciphertextLength)
                input.readFully(ciphertext)
                require(bytes.available() == 0) { "Trailing envelope bytes" }
                ParsedEnvelope(keyVersion, nonce, ciphertext)
            }
        } catch (failure: EOFException) {
            throw SpoolCryptoException("ANDROID_SPOOL_ENVELOPE_TRUNCATED", failure)
        } catch (failure: SpoolCryptoException) {
            throw failure
        } catch (failure: Exception) {
            throw SpoolCryptoException("ANDROID_SPOOL_ENVELOPE_INVALID", failure)
        }
    }

    data class DecryptedEnvelope(
        val keyVersion: Int,
        val plaintext: ByteArray,
    )

    private data class ParsedEnvelope(
        val keyVersion: Int,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    )

    companion object {
        private val ENVELOPE_MAGIC = byteArrayOf(0x47, 0x55, 0x4d, 0x45) // GUME
        private const val ENVELOPE_VERSION = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val MAX_ENVELOPE_OVERHEAD = 4L + 4L + 4L + 4L + NONCE_BYTES + 4L + TAG_BYTES

        private fun classifySecurityFailure(
            failure: GeneralSecurityException,
            fallbackCode: String,
        ): SpoolCryptoException = when (failure) {
            is android.security.keystore.KeyPermanentlyInvalidatedException ->
                SpoolCryptoException("ANDROID_SPOOL_KEY_INVALIDATED", failure)
            is android.security.keystore.UserNotAuthenticatedException ->
                SpoolCryptoException(
                    failureCode = "ANDROID_SPOOL_KEY_AUTHENTICATION_REQUIRED",
                    cause = failure,
                    retryable = true,
                )
            else -> SpoolCryptoException(fallbackCode, failure)
        }
    }
}

internal object PayloadIdentity {
    private const val REFERENCE_PREFIX = "gsp1_"
    private val referencePattern = Regex("gsp1_[A-Za-z0-9_-]{43}")

    fun associatedData(descriptor: ChunkDescriptor): ByteArray =
        SpoolStateBinaryCodec.encodeChunkDescriptor(descriptor)

    fun reference(
        descriptor: ChunkDescriptor,
        keyring: SpoolKeyring,
    ): DurablePayloadRef {
        val digest = try {
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(keyring.locatorKey())
            hmac.doFinal(associatedData(descriptor))
        } catch (failure: GeneralSecurityException) {
            throw SpoolCryptoException("ANDROID_SPOOL_LOCATOR_FAILED", failure)
        }
        return DurablePayloadRef(
            REFERENCE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest),
        )
    }

    fun isValidReference(value: String): Boolean = referencePattern.matches(value)

    fun sha256(bytes: ByteArray): Sha256Digest {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Sha256Digest("sha256:" + digest.joinToString("") { "%02x".format(it) })
    }

    fun digestMatches(
        expected: Sha256Digest,
        bytes: ByteArray,
    ): Boolean {
        val actual = sha256(bytes).value.toByteArray(Charsets.US_ASCII)
        val expectedBytes = expected.value.toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expectedBytes, actual)
    }
}

internal object MetadataIdentity {
    private const val TOKEN_PREFIX = "gmr1_"
    private val tokenPattern = Regex("gmr1_[A-Za-z0-9_-]{43}")

    fun revisionToken(
        revision: ULong,
        keyring: SpoolKeyring,
    ): String {
        val digest = try {
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(keyring.locatorKey())
            hmac.doFinal("gumi/metadata/revision/v1/$revision".toByteArray(Charsets.US_ASCII))
        } catch (failure: GeneralSecurityException) {
            throw SpoolCryptoException("ANDROID_SPOOL_REVISION_TOKEN_FAILED", failure)
        }
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun isValidToken(value: String): Boolean = tokenPattern.matches(value)
}

internal class SpoolCryptoException(
    val failureCode: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
) : Exception(failureCode, cause)
