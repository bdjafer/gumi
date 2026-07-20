package dev.gumi.edge.platforms.android.spool

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

internal class TestDurableFileOps(
    var reportedUsableBytes: Long = Long.MAX_VALUE,
) : DurableFileOps {
    var deleteFailure: IOException? = null

    var directorySyncCount: Int = 0
        private set

    override fun ensureDirectory(directory: File) {
        Files.createDirectories(directory.toPath())
    }

    override fun installImmutable(
        directory: File,
        target: File,
        body: ByteArray,
    ): ImmutableInstallResult {
        ensureDirectory(directory)
        val temporary = Files.createTempFile(
            directory.toPath(),
            EncryptedPayloadFileStore.TEMP_PREFIX,
            EncryptedPayloadFileStore.TEMP_SUFFIX,
        ).toFile()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(body)
                output.fd.sync()
            }
            return try {
                Files.createLink(target.toPath(), temporary.toPath())
                syncDirectory(directory)
                ImmutableInstallResult.INSTALLED
            } catch (_: FileAlreadyExistsException) {
                ImmutableInstallResult.ALREADY_EXISTS
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    override fun readBounded(
        file: File,
        maximumBytes: Int,
    ): ByteArray {
        require(file.length() <= maximumBytes)
        return FileInputStream(file).use { it.readBytes() }.also {
            require(it.size <= maximumBytes)
        }
    }

    override fun usableBytes(directory: File): Long = reportedUsableBytes

    override fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        directorySyncCount += 1
    }

    override fun deleteDurably(
        directory: File,
        file: File,
    ): Boolean {
        deleteFailure?.let { throw it }
        val deleted = Files.deleteIfExists(file.toPath())
        if (deleted) syncDirectory(directory)
        return deleted
    }
}
