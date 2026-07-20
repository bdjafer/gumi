package dev.gumi.edge.platforms.android.spool

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.atomic.AtomicBoolean

internal class SpoolStorageOwnershipException(
    val failureCode: String,
    cause: Throwable? = null,
) : Exception(failureCode, cause)

/** Same-process claim plus an OS advisory lock held for the complete storage lifetime. */
internal class AndroidSpoolStorageLease private constructor(
    private val identity: String,
    private val channel: FileChannel,
    private val fileLock: FileLock,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var releaseFailure: Throwable? = null
        try {
            fileLock.release()
        } catch (failure: Throwable) {
            releaseFailure = failure
        } finally {
            try {
                channel.close()
            } catch (failure: Throwable) {
                if (releaseFailure == null) {
                    releaseFailure = failure
                } else {
                    releaseFailure.addSuppressed(failure)
                }
            } finally {
                synchronized(processLock) {
                    processClaims.remove(identity)
                }
            }
        }
        releaseFailure?.let {
            throw SpoolStorageOwnershipException(
                failureCode = "ANDROID_SPOOL_OWNERSHIP_RELEASE_FAILED",
                cause = it,
            )
        }
    }

    companion object {
        private val processLock = Any()
        private val processClaims = mutableSetOf<String>()

        fun acquire(lockFile: File): AndroidSpoolStorageLease {
            val parent = lockFile.parentFile
                ?: throw SpoolStorageOwnershipException("ANDROID_SPOOL_OWNERSHIP_PATH_INVALID")
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                throw SpoolStorageOwnershipException("ANDROID_SPOOL_OWNERSHIP_DIRECTORY_FAILED")
            }
            val identity = try {
                lockFile.canonicalPath
            } catch (failure: Exception) {
                throw SpoolStorageOwnershipException(
                    "ANDROID_SPOOL_OWNERSHIP_PATH_INVALID",
                    failure,
                )
            }
            synchronized(processLock) {
                if (!processClaims.add(identity)) {
                    throw SpoolStorageOwnershipException("ANDROID_SPOOL_STORAGE_ALREADY_OPEN")
                }
            }

            var channel: FileChannel? = null
            try {
                channel = RandomAccessFile(lockFile, "rw").channel
                val acquired = try {
                    channel.tryLock()
                } catch (failure: OverlappingFileLockException) {
                    throw SpoolStorageOwnershipException(
                        "ANDROID_SPOOL_STORAGE_ALREADY_OPEN",
                        failure,
                    )
                } ?: throw SpoolStorageOwnershipException("ANDROID_SPOOL_STORAGE_ALREADY_OPEN")
                return AndroidSpoolStorageLease(identity, channel, acquired)
            } catch (failure: Throwable) {
                runCatching { channel?.close() }
                synchronized(processLock) {
                    processClaims.remove(identity)
                }
                throw failure
            }
        }
    }
}
