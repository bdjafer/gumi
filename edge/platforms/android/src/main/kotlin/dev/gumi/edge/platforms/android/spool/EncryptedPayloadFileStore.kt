package dev.gumi.edge.platforms.android.spool

import dev.gumi.edge.runtime.spool.ChunkDescriptor
import dev.gumi.edge.runtime.spool.DurableChunk
import dev.gumi.edge.runtime.spool.DurablePayloadReadResult
import dev.gumi.edge.runtime.spool.DurablePayloadStore
import dev.gumi.edge.runtime.spool.DurablePayloadWriteResult
import dev.gumi.edge.runtime.spool.SpoolStoreFailure
import dev.gumi.edge.sdk.OpaqueBytes
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ImmutableInstallResult {
    INSTALLED,
    ALREADY_EXISTS,
}

/** Host filesystem primitives whose Android implementation fsyncs the file and containing directory. */
internal interface DurableFileOps {
    fun ensureDirectory(directory: File)

    fun installImmutable(
        directory: File,
        target: File,
        body: ByteArray,
    ): ImmutableInstallResult

    fun readBounded(
        file: File,
        maximumBytes: Int,
    ): ByteArray

    fun usableBytes(directory: File): Long

    fun syncDirectory(directory: File)

    fun deleteDurably(
        directory: File,
        file: File,
    ): Boolean
}

/**
 * Encrypted immutable-file implementation of the runtime payload port.
 *
 * File names are keyed HMAC locators, never media IDs. Each body is AES-GCM authenticated against
 * the entire chunk descriptor. The final file is published by atomic rename while the storage-wide
 * lease and this store's mutex provide single-writer/no-overwrite semantics; the directory entry is
 * flushed before success is returned.
 */
internal class EncryptedPayloadFileStore(
    private val directory: File,
    private val keyring: SpoolKeyring,
    private val fileOps: DurableFileOps,
    private val minimumFreeBytes: Long,
    private val maximumPayloadBytes: Int,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DurablePayloadStore {
    private val mutex = Mutex()
    private val cipher = AeadEnvelopeCipher(keyring)
    private val closed = AtomicBoolean(false)

    init {
        require(minimumFreeBytes >= 0L) { "Minimum free bytes cannot be negative" }
        require(maximumPayloadBytes in 1..MAXIMUM_SUPPORTED_PAYLOAD_BYTES) {
            "Maximum payload bytes are outside the bounded Android envelope policy"
        }
    }

    override suspend fun writeAndFlush(
        descriptor: ChunkDescriptor,
        payload: OpaqueBytes,
    ): DurablePayloadWriteResult = withContext(ioDispatcher) {
        mutex.withLock {
            writeLocked(descriptor, payload)
        }
    }

    override suspend fun readAndVerify(chunk: DurableChunk): DurablePayloadReadResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                readLocked(chunk)
            }
        }

    private fun writeLocked(
        descriptor: ChunkDescriptor,
        payload: OpaqueBytes,
    ): DurablePayloadWriteResult {
        if (closed.get()) {
            return unavailable("ANDROID_SPOOL_PAYLOAD_STORE_CLOSED", retryable = false)
        }
        val plaintext = payload.copyBytes()
        try {
            if (descriptor.payloadBytes > Int.MAX_VALUE.toULong() ||
                descriptor.payloadBytes.toInt() != plaintext.size ||
                plaintext.size > maximumPayloadBytes
            ) {
                return unavailable("ANDROID_SPOOL_PAYLOAD_LENGTH_MISMATCH", retryable = false)
            }
            if (!PayloadIdentity.digestMatches(descriptor.contentDigest, plaintext)) {
                return unavailable("ANDROID_SPOOL_PAYLOAD_DIGEST_MISMATCH", retryable = false)
            }

            val payloadRef = PayloadIdentity.reference(descriptor, keyring)
            val target = payloadFile(payloadRef.value)
            fileOps.ensureDirectory(directory)

            if (target.exists()) {
                fileOps.syncDirectory(directory)
                return when (val existing = readLocked(DurableChunk(descriptor, payloadRef))) {
                    is DurablePayloadReadResult.Verified -> {
                        if (existing.payload == payload) {
                            DurablePayloadWriteResult.Stored(payloadRef)
                        } else {
                            unavailable("ANDROID_SPOOL_PAYLOAD_IDENTITY_CONFLICT", retryable = false)
                        }
                    }
                    is DurablePayloadReadResult.Unavailable ->
                        DurablePayloadWriteResult.Unavailable(existing.failure)
                }
            }

            val estimatedBodyBytes = plaintext.size.toLong() + ENVELOPE_ALLOWANCE_BYTES
            val usable = fileOps.usableBytes(directory)
            if (usable < minimumFreeBytes || estimatedBodyBytes > usable - minimumFreeBytes) {
                return unavailable("ANDROID_SPOOL_STORAGE_EXHAUSTED", retryable = true)
            }

            val envelope = cipher.encrypt(plaintext, PayloadIdentity.associatedData(descriptor))
            return when (fileOps.installImmutable(directory, target, envelope)) {
                ImmutableInstallResult.INSTALLED -> DurablePayloadWriteResult.Stored(payloadRef)
                ImmutableInstallResult.ALREADY_EXISTS -> {
                    // Another process may have won the immutable install. Accept only exact content.
                    fileOps.syncDirectory(directory)
                    when (val existing = readLocked(DurableChunk(descriptor, payloadRef))) {
                        is DurablePayloadReadResult.Verified -> {
                            if (existing.payload == payload) {
                                DurablePayloadWriteResult.Stored(payloadRef)
                            } else {
                                unavailable(
                                    "ANDROID_SPOOL_PAYLOAD_IDENTITY_CONFLICT",
                                    retryable = false,
                                )
                            }
                        }
                        is DurablePayloadReadResult.Unavailable ->
                            DurablePayloadWriteResult.Unavailable(existing.failure)
                    }
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            return DurablePayloadWriteResult.Unavailable(
                AndroidSpoolFailureMapper.map(failure, "ANDROID_SPOOL_PAYLOAD_WRITE_FAILED"),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun readLocked(chunk: DurableChunk): DurablePayloadReadResult {
        if (closed.get()) {
            return readUnavailable("ANDROID_SPOOL_PAYLOAD_STORE_CLOSED", retryable = false)
        }
        var plaintext: ByteArray? = null
        try {
            val descriptor = chunk.descriptor
            if (descriptor.payloadBytes > maximumPayloadBytes.toULong() ||
                !PayloadIdentity.isValidReference(chunk.payloadRef.value)
            ) {
                return readUnavailable("ANDROID_SPOOL_PAYLOAD_REFERENCE_INVALID", retryable = false)
            }
            val expectedRef = PayloadIdentity.reference(descriptor, keyring)
            if (expectedRef.value != chunk.payloadRef.value) {
                return readUnavailable("ANDROID_SPOOL_PAYLOAD_BINDING_MISMATCH", retryable = false)
            }
            val target = payloadFile(chunk.payloadRef.value)
            if (!target.isFile) {
                return readUnavailable("ANDROID_SPOOL_PAYLOAD_NOT_FOUND", retryable = false)
            }
            val maximumEnvelopeBytes = descriptor.payloadBytes.toInt() + ENVELOPE_ALLOWANCE_BYTES.toInt()
            val envelope = fileOps.readBounded(target, maximumEnvelopeBytes)
            plaintext = cipher.decrypt(
                envelope = envelope,
                associatedData = PayloadIdentity.associatedData(descriptor),
                maximumPlaintextBytes = descriptor.payloadBytes.toInt(),
            ).plaintext
            if (plaintext.size.toULong() != descriptor.payloadBytes ||
                !PayloadIdentity.digestMatches(descriptor.contentDigest, plaintext)
            ) {
                return readUnavailable("ANDROID_SPOOL_PAYLOAD_INTEGRITY_MISMATCH", retryable = false)
            }
            return DurablePayloadReadResult.Verified(OpaqueBytes.copyOf(plaintext))
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            return DurablePayloadReadResult.Unavailable(
                AndroidSpoolFailureMapper.map(failure, "ANDROID_SPOOL_PAYLOAD_READ_FAILED"),
            )
        } finally {
            plaintext?.fill(0)
        }
    }

    internal fun payloadFile(reference: String): File {
        require(PayloadIdentity.isValidReference(reference)) { "Invalid payload reference" }
        return File(directory, "$reference$PAYLOAD_SUFFIX")
    }

    internal fun directory(): File = directory

    internal fun close() {
        closed.set(true)
    }

    private fun unavailable(
        code: String,
        retryable: Boolean,
    ) = DurablePayloadWriteResult.Unavailable(SpoolStoreFailure(code, retryable))

    private fun readUnavailable(
        code: String,
        retryable: Boolean,
    ) = DurablePayloadReadResult.Unavailable(SpoolStoreFailure(code, retryable))

    companion object {
        internal const val PAYLOAD_SUFFIX = ".gsp"
        internal const val TEMP_PREFIX = ".pending-"
        internal const val TEMP_SUFFIX = ".tmp"
        internal const val MAXIMUM_SUPPORTED_PAYLOAD_BYTES = 64 * 1024 * 1024
        private const val ENVELOPE_ALLOWANCE_BYTES = 128L
    }
}

internal object AndroidSpoolFailureMapper {
    fun map(
        failure: Throwable,
        fallbackCode: String,
    ): SpoolStoreFailure = when (failure) {
        is SpoolStorageOwnershipException ->
            SpoolStoreFailure(failure.failureCode, retryable = false)
        is AndroidSpoolCloseException ->
            SpoolStoreFailure(failure.failureCode, retryable = false)
        is SpoolCryptoException -> SpoolStoreFailure(failure.failureCode, failure.retryable)
        is SpoolCodecException -> SpoolStoreFailure(failure.failureCode, retryable = false)
        is android.security.keystore.KeyPermanentlyInvalidatedException ->
            SpoolStoreFailure("ANDROID_SPOOL_KEY_INVALIDATED", retryable = false)
        is java.security.UnrecoverableKeyException ->
            SpoolStoreFailure("ANDROID_SPOOL_KEY_UNAVAILABLE", retryable = false)
        is android.system.ErrnoException -> if (failure.errno == android.system.OsConstants.ENOSPC) {
            SpoolStoreFailure("ANDROID_SPOOL_STORAGE_EXHAUSTED", retryable = true)
        } else {
            SpoolStoreFailure("ANDROID_SPOOL_STORAGE_IO_FAILED", retryable = true)
        }
        is android.database.sqlite.SQLiteFullException ->
            SpoolStoreFailure("ANDROID_SPOOL_STORAGE_EXHAUSTED", retryable = true)
        is android.database.sqlite.SQLiteDatabaseCorruptException ->
            SpoolStoreFailure("ANDROID_SPOOL_DATABASE_CORRUPT", retryable = false)
        is android.database.sqlite.SQLiteCantOpenDatabaseException ->
            SpoolStoreFailure("ANDROID_SPOOL_DATABASE_UNAVAILABLE", retryable = true)
        is android.database.sqlite.SQLiteDatabaseLockedException,
        is android.database.sqlite.SQLiteTableLockedException,
        -> SpoolStoreFailure("ANDROID_SPOOL_DATABASE_BUSY", retryable = true)
        is android.database.sqlite.SQLiteDiskIOException,
        is IOException,
        -> SpoolStoreFailure("ANDROID_SPOOL_STORAGE_IO_FAILED", retryable = true)
        is SecurityException -> SpoolStoreFailure("ANDROID_SPOOL_ACCESS_DENIED", retryable = false)
        else -> SpoolStoreFailure(fallbackCode, retryable = false)
    }
}
