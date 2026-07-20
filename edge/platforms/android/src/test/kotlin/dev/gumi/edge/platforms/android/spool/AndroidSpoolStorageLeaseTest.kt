package dev.gumi.edge.platforms.android.spool

import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AndroidSpoolStorageLeaseTest {
    @Test
    fun `ownership is exclusive for the lifetime and recoverable after close`() {
        val directory = Files.createTempDirectory("gumi-spool-ownership-test-").toFile()
        try {
            val lockFile = directory.resolve("owner.lock")
            val first = AndroidSpoolStorageLease.acquire(lockFile)
            try {
                RandomAccessFile(lockFile, "rw").channel.use { competingChannel ->
                    assertFailsWith<OverlappingFileLockException> {
                        competingChannel.tryLock()
                    }
                }
                val duplicate = assertFailsWith<SpoolStorageOwnershipException> {
                    AndroidSpoolStorageLease.acquire(lockFile)
                }
                assertEquals("ANDROID_SPOOL_STORAGE_ALREADY_OPEN", duplicate.failureCode)
            } finally {
                first.close()
            }

            AndroidSpoolStorageLease.acquire(lockFile).close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `simultaneous process claims admit exactly one owner`() {
        val directory = Files.createTempDirectory("gumi-spool-ownership-race-test-").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val lockFile = directory.resolve("owner.lock")
            val start = CountDownLatch(1)
            val attempts = List(2) {
                executor.submit<Result<AndroidSpoolStorageLease>> {
                    start.await()
                    runCatching { AndroidSpoolStorageLease.acquire(lockFile) }
                }
            }
            start.countDown()
            val results = attempts.map { it.get() }
            val owners = results.mapNotNull { it.getOrNull() }
            try {
                assertEquals(1, results.count { it.isSuccess })
                val loser = results.single { it.isFailure }.exceptionOrNull()
                assertEquals(
                    "ANDROID_SPOOL_STORAGE_ALREADY_OPEN",
                    assertIs<SpoolStorageOwnershipException>(loser).failureCode,
                )
            } finally {
                owners.forEach(AndroidSpoolStorageLease::close)
            }
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }
}
