package dev.gumi.edge.platforms.android.spool

import android.content.Context
import android.os.storage.StorageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Android Keystore keyring with explicit encryption versions and a stable, separate locator key. */
internal class AndroidKeystoreSpoolKeyring(
    private val aliasPrefix: String,
    private val initialActiveEncryptionKeyVersion: Int,
    private val allowInitialKeyCreation: Boolean,
) : SpoolKeyring {
    private val lock = Any()
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        require(aliasPrefix.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}"))) {
            "Keystore alias prefix must be a stable lowercase identifier"
        }
        require(initialActiveEncryptionKeyVersion > 0) {
            "Active encryption key version must be positive"
        }
        synchronized(lock) {
            ensureEncryptionKeyLocked(
                initialActiveEncryptionKeyVersion,
                allowCreation = allowInitialKeyCreation,
            )
            ensureLocatorKeyLocked(allowCreation = allowInitialKeyCreation)
        }
    }

    override val activeEncryptionKeyVersion: Int
        get() = initialActiveEncryptionKeyVersion

    override fun encryptionKey(version: Int): SecretKey? = synchronized(lock) {
        if (version <= 0) return@synchronized null
        keyStore.getKey(encryptionAlias(version), null) as? SecretKey
    }

    override fun locatorKey(): SecretKey = synchronized(lock) {
        ensureLocatorKeyLocked(allowCreation = false)
        keyStore.getKey(locatorAlias(), null) as? SecretKey
            ?: throw SpoolCryptoException("ANDROID_SPOOL_LOCATOR_KEY_UNAVAILABLE")
    }

    private fun ensureEncryptionKeyLocked(
        version: Int,
        allowCreation: Boolean,
    ) {
        val alias = encryptionAlias(version)
        if (keyStore.containsAlias(alias)) return
        if (!allowCreation) {
            throw SpoolCryptoException("ANDROID_SPOOL_ACTIVE_KEY_UNAVAILABLE")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private fun ensureLocatorKeyLocked(allowCreation: Boolean) {
        val alias = locatorAlias()
        if (keyStore.containsAlias(alias)) return
        if (!allowCreation) {
            throw SpoolCryptoException("ANDROID_SPOOL_LOCATOR_KEY_UNAVAILABLE")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    private fun encryptionAlias(version: Int) = "$aliasPrefix.enc.v$version"
    private fun locatorAlias() = "$aliasPrefix.locator.v1"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** Linux-kernel-backed durability primitives used only by the Android production adapter. */
internal class AndroidDurableFileOps(context: Context) : DurableFileOps {
    private val storageManager = context.getSystemService(StorageManager::class.java)
        ?: throw IllegalStateException("ANDROID_SPOOL_STORAGE_MANAGER_UNAVAILABLE")

    override fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("ANDROID_SPOOL_DIRECTORY_CREATE_FAILED")
        }
        directory.parentFile?.takeIf { it.isDirectory }?.let(::syncDirectory)
    }

    override fun installImmutable(
        directory: File,
        target: File,
        body: ByteArray,
    ): ImmutableInstallResult {
        require(target.parentFile?.canonicalFile == directory.canonicalFile) {
            "Payload target escaped its storage directory"
        }
        ensureDirectory(directory)
        val temporary = File.createTempFile(
            EncryptedPayloadFileStore.TEMP_PREFIX,
            EncryptedPayloadFileStore.TEMP_SUFFIX,
            directory,
        )
        var installed = false
        try {
            FileOutputStream(temporary).use { output ->
                output.write(body)
                output.flush()
                output.fd.sync()
            }
            // Android SELinux denies hard links in ordinary app data. The storage-wide process/OS
            // lease and this payload store's mutex are therefore part of the install invariant:
            // under that single-writer ownership, an existence check followed by rename cannot race
            // another conforming writer. rename is the crash-atomic publication boundary.
            if (target.exists()) return ImmutableInstallResult.ALREADY_EXISTS
            Os.rename(temporary.absolutePath, target.absolutePath)
            installed = true
            syncDirectory(directory)
            return ImmutableInstallResult.INSTALLED
        } finally {
            if (temporary.exists()) {
                // Before rename this is an uncommitted orphan; after rename it normally no longer
                // has the temporary name. A failed cleanup remains startup-reconciliation input.
                temporary.delete()
                if (installed) runCatching { syncDirectory(directory) }
            }
        }
    }

    override fun readBounded(
        file: File,
        maximumBytes: Int,
    ): ByteArray {
        require(maximumBytes >= 0) { "Maximum read size cannot be negative" }
        val declaredLength = file.length()
        if (declaredLength < 0L || declaredLength > maximumBytes.toLong()) {
            throw SpoolCryptoException("ANDROID_SPOOL_ENVELOPE_TOO_LARGE")
        }
        return FileInputStream(file).use { input ->
            val body = ByteArray(declaredLength.toInt())
            var offset = 0
            while (offset < body.size) {
                val read = input.read(body, offset, body.size - offset)
                if (read < 0) throw IOException("ANDROID_SPOOL_UNEXPECTED_EOF")
                offset += read
            }
            if (input.read() != -1) {
                throw SpoolCryptoException("ANDROID_SPOOL_ENVELOPE_TOO_LARGE")
            }
            body
        }
    }

    override fun usableBytes(directory: File): Long {
        val filesystemUsable = directory.usableSpace.coerceAtLeast(0L)
        val allocatable = storageManager.getAllocatableBytes(storageManager.getUuidForPath(directory))
            .coerceAtLeast(0L)
        return minOf(filesystemUsable, allocatable)
    }

    override fun syncDirectory(directory: File) {
        val descriptor = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    override fun deleteDurably(
        directory: File,
        file: File,
    ): Boolean {
        require(file.parentFile?.canonicalFile == directory.canonicalFile) {
            "Delete target escaped its storage directory"
        }
        if (!file.exists()) return false
        if (!file.delete()) throw IOException("ANDROID_SPOOL_DELETE_FAILED")
        syncDirectory(directory)
        return true
    }
}
