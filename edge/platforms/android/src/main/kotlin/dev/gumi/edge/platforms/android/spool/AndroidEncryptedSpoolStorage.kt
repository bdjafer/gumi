package dev.gumi.edge.platforms.android.spool

import android.content.Context
import dev.gumi.edge.runtime.spool.DurablePayloadStore
import dev.gumi.edge.runtime.spool.SpoolQuota
import dev.gumi.edge.runtime.spool.SpoolStore
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AndroidSpoolStorageConfiguration(
    val quota: SpoolQuota,
    val minimumFilesystemFreeBytes: Long = 64L * 1024L * 1024L,
    val maximumPayloadBytes: Int = 8 * 1024 * 1024,
    val orphanGracePeriodMillis: Long = 24L * 60L * 60L * 1_000L,
    val keystoreAliasPrefix: String = "dev.gumi.edge.spool",
) {
    init {
        require(minimumFilesystemFreeBytes >= 0L) { "Minimum free bytes cannot be negative" }
        require(maximumPayloadBytes in 1..EncryptedPayloadFileStore.MAXIMUM_SUPPORTED_PAYLOAD_BYTES) {
            "Maximum payload bytes are outside the bounded Android envelope policy"
        }
        require(orphanGracePeriodMillis >= 0L) { "Orphan grace period cannot be negative" }
    }
}

enum class AndroidSpoolReconciliationStatus {
    COMPLETE,
    SKIPPED_METADATA_UNAVAILABLE,
    COMPLETED_WITH_FAILURES,
}

/** Counts and stable codes only: reports never expose media, identifiers, references, or paths. */
data class AndroidSpoolReconciliationReport(
    val status: AndroidSpoolReconciliationStatus,
    val referencedPayloadCount: Int,
    val verifiedPayloadCount: Int,
    val missingOrInvalidReferencedPayloadCount: Int,
    val retainedYoungOrphanCount: Int,
    val deletedOrphanCount: Int,
    val untouchedUnknownFileCount: Int,
    val failureCodes: Set<String>,
) {
    /** Cleanup warnings do not hide ports; unavailable metadata or referenced bytes do. */
    val allReferencedPayloadsVerified: Boolean
        get() = status != AndroidSpoolReconciliationStatus.SKIPPED_METADATA_UNAVAILABLE &&
            missingOrInvalidReferencedPayloadCount == 0 &&
            verifiedPayloadCount == referencedPayloadCount
}

sealed interface AndroidSpoolOpenResult {
    data class Ready(
        val storage: AndroidEncryptedSpoolStorage,
        val reconciliation: AndroidSpoolReconciliationReport,
    ) : AndroidSpoolOpenResult

    /** No operational storage ports are exposed when durable truth cannot be verified. */
    data class Degraded(
        val reconciliation: AndroidSpoolReconciliationReport,
    ) : AndroidSpoolOpenResult

    /** Cleanup crossed a close boundary whose resource-release outcome cannot be proven. */
    data class OutcomeUnknown(
        val cleanup: AndroidSpoolCleanupHandle,
        val failureCode: String,
        val reconciliation: AndroidSpoolReconciliationReport? = null,
    ) : AndroidSpoolOpenResult
}

/** Close-only ownership retained when storage ports cannot safely escape an open attempt. */
interface AndroidSpoolCleanupHandle : Closeable

class AndroidSpoolOpenException internal constructor(
    val failureCode: String,
) : Exception(failureCode)

/** Carries partial ownership across cancellation when cleanup could not prove release. */
class AndroidSpoolOpenOutcomeUnknownException internal constructor(
    val cleanup: AndroidSpoolCleanupHandle,
    val failureCode: String,
    cause: Throwable,
) : Exception(failureCode, cause)

class AndroidSpoolCloseException internal constructor(
    val failureCode: String,
) : Exception(failureCode)

/**
 * Production-shaped Android composition for the portable spool ports.
 *
 * One process-wide plus OS-backed lease is held for this object's complete lifetime. Reconciliation
 * runs exactly once, under that lease, before operational ports can escape [openAndReconcile]. Callers
 * must still quiesce coordinators before [close].
 */
class AndroidEncryptedSpoolStorage private constructor(
    val metadataStore: SpoolStore,
    val payloadStore: DurablePayloadStore,
    private val resources: AndroidSpoolResourceOwner,
) : AndroidSpoolCleanupHandle {
    override fun close() = resources.close()

    companion object {
        suspend fun openAndReconcile(
            context: Context,
            configuration: AndroidSpoolStorageConfiguration,
        ): AndroidSpoolOpenResult = openAndReconcileForTesting(
            context = context.applicationContext,
            configuration = configuration,
            fileOps = AndroidDurableFileOps(context.applicationContext),
            clockMillis = System::currentTimeMillis,
            ioDispatcher = Dispatchers.IO,
        )

        internal suspend fun openAndReconcileForTesting(
            context: Context,
            configuration: AndroidSpoolStorageConfiguration,
            fileOps: DurableFileOps,
            clockMillis: () -> Long,
            ioDispatcher: CoroutineDispatcher,
            afterOwnershipAcquired: suspend () -> Unit = {},
        ): AndroidSpoolOpenResult = withContext(ioDispatcher) {
            var cleanupToClose: AndroidSpoolResourceOwner? = null
            try {
                // The lease lives outside the data root, so creating it cannot make an old store look
                // new or a new store look old. No artifact/key/bootstrap inspection precedes it.
                val ownership = AndroidSpoolStorageLease.acquire(
                    File(context.noBackupFilesDir, OWNERSHIP_LOCK_FILE),
                )
                val resources = AndroidSpoolResourceOwner(ownership::close)
                cleanupToClose = resources
                afterOwnershipAcquired()

                val root = File(context.noBackupFilesDir, STORAGE_DIRECTORY)
                val payloadDirectory = File(root, PAYLOAD_DIRECTORY)
                val storageAlreadyExists = root.exists()
                val keyring = AndroidKeystoreSpoolKeyring(
                    aliasPrefix = configuration.keystoreAliasPrefix,
                    initialActiveEncryptionKeyVersion = FIXED_ENCRYPTION_KEY_VERSION,
                    allowInitialKeyCreation = !storageAlreadyExists,
                )
                val payloads = EncryptedPayloadFileStore(
                    directory = payloadDirectory,
                    keyring = keyring,
                    fileOps = fileOps,
                    minimumFreeBytes = configuration.minimumFilesystemFreeBytes,
                    maximumPayloadBytes = configuration.maximumPayloadBytes,
                    ioDispatcher = ioDispatcher,
                )
                resources.attachPayload(payloads::close)
                val sqlite = AndroidSqliteSnapshotDatabase(File(root, DATABASE_FILE), fileOps)
                resources.attachMetadata(sqlite::close)
                val metadata = EncryptedSnapshotSpoolStore(
                    database = sqlite,
                    keyring = keyring,
                    initialQuota = configuration.quota,
                    allowEmptyBootstrap = !storageAlreadyExists,
                    ioDispatcher = ioDispatcher,
                )
                resources.attachMetadata(metadata::close)
                val reconciler = SpoolRestartReconciler(
                    payloadStore = payloads,
                    concretePayloadStore = payloads,
                    fileOps = fileOps,
                    orphanGracePeriodMillis = configuration.orphanGracePeriodMillis,
                    clockMillis = clockMillis,
                    ioDispatcher = ioDispatcher,
                )
                val storage = AndroidEncryptedSpoolStorage(
                    metadataStore = metadata,
                    payloadStore = payloads,
                    resources = resources,
                )
                val reconciliation = reconciler.reconcile(metadata)
                val result = if (reconciliation.allReferencedPayloadsVerified) {
                    cleanupToClose = null
                    AndroidSpoolOpenResult.Ready(storage, reconciliation)
                } else {
                    val closeFailure = storage.closeFailureCode()
                    cleanupToClose = null
                    if (closeFailure == null) {
                        AndroidSpoolOpenResult.Degraded(reconciliation)
                    } else {
                        AndroidSpoolOpenResult.OutcomeUnknown(
                            cleanup = storage,
                            failureCode = closeFailure,
                            reconciliation = reconciliation,
                        )
                    }
                }
                result
            } catch (failure: CancellationException) {
                cleanupToClose?.let { cleanup ->
                    cleanup.closeFailureCode()?.let { closeFailure ->
                        cleanupToClose = null
                        throw AndroidSpoolOpenOutcomeUnknownException(
                            cleanup = cleanup,
                            failureCode = closeFailure,
                            cause = failure,
                        )
                    }
                }
                throw failure
            } catch (failure: Exception) {
                cleanupToClose?.let { cleanup ->
                    cleanup.closeFailureCode()?.let { closeFailure ->
                        cleanupToClose = null
                        throw AndroidSpoolOpenOutcomeUnknownException(
                            cleanup = cleanup,
                            failureCode = closeFailure,
                            cause = failure,
                        )
                    }
                }
                throw AndroidSpoolOpenException(
                    AndroidSpoolFailureMapper.map(failure, "ANDROID_SPOOL_OPEN_FAILED").code,
                )
            }
        }

        private const val STORAGE_DIRECTORY = "gumi-spool-v1"
        private const val PAYLOAD_DIRECTORY = "payloads"
        private const val DATABASE_FILE = "metadata.sqlite"
        private const val FIXED_ENCRYPTION_KEY_VERSION = 1
        internal const val OWNERSHIP_LOCK_FILE = ".gumi-spool-v1.owner.lock"
    }
}

private enum class AndroidSpoolResourceCloseState {
    OPEN,
    CLOSED,
    OUTCOME_UNKNOWN,
}

/**
 * Owns every close boundary acquired during open, including incomplete construction. A failed close
 * is sticky: later calls replay the same uncertainty and can never turn it into a successful no-op.
 */
internal class AndroidSpoolResourceOwner(
    private val ownershipClose: () -> Unit,
) : AndroidSpoolCleanupHandle {
    private val monitor = Any()
    private var payloadClose: (() -> Unit)? = null
    private var metadataClose: (() -> Unit)? = null
    private var state = AndroidSpoolResourceCloseState.OPEN
    private var closeFailureCode: String? = null

    fun attachPayload(close: () -> Unit) = synchronized(monitor) {
        check(state == AndroidSpoolResourceCloseState.OPEN)
        payloadClose = close
    }

    fun attachMetadata(close: () -> Unit) = synchronized(monitor) {
        check(state == AndroidSpoolResourceCloseState.OPEN)
        metadataClose = close
    }

    override fun close(): Unit = synchronized(monitor) {
        when (state) {
            AndroidSpoolResourceCloseState.CLOSED -> return
            AndroidSpoolResourceCloseState.OUTCOME_UNKNOWN -> {
                throw AndroidSpoolCloseException(requireNotNull(closeFailureCode))
            }
            AndroidSpoolResourceCloseState.OPEN -> Unit
        }

        var failureCode: String? = null
        fun closeBoundary(action: (() -> Unit)?, fallbackCode: String) {
            try {
                action?.invoke()
            } catch (failure: Throwable) {
                if (failureCode == null) {
                    failureCode = AndroidSpoolFailureMapper.map(failure, fallbackCode).code
                }
            }
        }

        closeBoundary(payloadClose, "ANDROID_SPOOL_PAYLOAD_CLOSE_FAILED")
        closeBoundary(metadataClose, "ANDROID_SPOOL_DATABASE_CLOSE_FAILED")
        closeBoundary(ownershipClose, "ANDROID_SPOOL_OWNERSHIP_RELEASE_FAILED")
        if (failureCode == null) {
            state = AndroidSpoolResourceCloseState.CLOSED
        } else {
            closeFailureCode = failureCode
            state = AndroidSpoolResourceCloseState.OUTCOME_UNKNOWN
            throw AndroidSpoolCloseException(failureCode)
        }
    }
}

private fun AndroidSpoolCleanupHandle.closeFailureCode(): String? = try {
    close()
    null
} catch (failure: AndroidSpoolCloseException) {
    failure.failureCode
} catch (failure: Throwable) {
    AndroidSpoolFailureMapper.map(failure, "ANDROID_SPOOL_CLOSE_FAILED").code
}
