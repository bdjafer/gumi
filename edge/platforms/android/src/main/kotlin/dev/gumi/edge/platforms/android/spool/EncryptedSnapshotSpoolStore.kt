package dev.gumi.edge.platforms.android.spool

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.gumi.edge.runtime.spool.SpoolQuota
import dev.gumi.edge.runtime.spool.SpoolState
import dev.gumi.edge.runtime.spool.SpoolStore
import dev.gumi.edge.runtime.spool.SpoolStoreCommitResult
import dev.gumi.edge.runtime.spool.SpoolStoreFailure
import dev.gumi.edge.runtime.spool.SpoolStoreLoadResult
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class EncryptedSnapshotRow(
    val revisionToken: String,
    val encryptedState: ByteArray,
)

internal interface SnapshotTransaction {
    fun read(): EncryptedSnapshotRow?

    fun insertIfAbsent(row: EncryptedSnapshotRow): Boolean

    fun replaceIfRevision(
        expectedRevision: String,
        next: EncryptedSnapshotRow,
    ): Boolean
}

internal interface SnapshotDatabase : Closeable {
    /** Commits all changes atomically or throws [SnapshotCommitOutcomeUnknownException]. */
    fun <T> transaction(block: SnapshotTransaction.() -> T): T
}

internal class SnapshotCommitOutcomeUnknownException(cause: Throwable? = null) :
    Exception("ANDROID_SPOOL_COMMIT_OUTCOME_UNKNOWN", cause)

/** Encrypted single-snapshot CAS engine; production storage is [AndroidSqliteSnapshotDatabase]. */
internal class EncryptedSnapshotSpoolStore(
    private val database: SnapshotDatabase,
    private val keyring: SpoolKeyring,
    private val initialQuota: SpoolQuota,
    allowEmptyBootstrap: Boolean = true,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SpoolStore, Closeable {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val emptyBootstrapPermission = AtomicBoolean(allowEmptyBootstrap)
    private val cipher = AeadEnvelopeCipher(keyring)

    override suspend fun load(): SpoolStoreLoadResult = withContext(ioDispatcher) {
        mutex.withLock {
            if (closed.get()) return@withLock unavailableLoad("ANDROID_SPOOL_DATABASE_CLOSED", false)
            try {
                val state = database.transaction {
                    val row = read() ?: checkedBootstrapRow().also { initial ->
                        if (!insertIfAbsent(initial)) {
                            return@also
                        }
                    }.let { read() ?: it }
                    decodeAndValidate(row)
                }
                SpoolStoreLoadResult.Loaded(state)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                SpoolStoreLoadResult.Unavailable(
                    AndroidSpoolFailureMapper.map(failure, "ANDROID_SPOOL_METADATA_LOAD_FAILED"),
                )
            }
        }
    }

    override suspend fun commit(
        expectedRevision: ULong,
        next: SpoolState,
    ): SpoolStoreCommitResult = withContext(ioDispatcher) {
        mutex.withLock {
            if (closed.get()) return@withLock unavailableCommit("ANDROID_SPOOL_DATABASE_CLOSED", false)
            if (expectedRevision == ULong.MAX_VALUE || next.storeRevision != expectedRevision + 1uL) {
                return@withLock unavailableCommit("ANDROID_SPOOL_REVISION_TRANSITION_INVALID", false)
            }
            try {
                database.transaction {
                    var current = read()
                    if (current == null) {
                        val initial = checkedBootstrapRow()
                        insertIfAbsent(initial)
                        current = read() ?: initial
                    }
                    val currentState = decodeAndValidate(current)
                    if (currentState.storeRevision != expectedRevision) {
                        return@transaction SpoolStoreCommitResult.RevisionMismatch(
                            currentState.storeRevision,
                        )
                    }
                    val nextRow = encode(next)
                    if (!replaceIfRevision(current.revisionToken, nextRow)) {
                        val winner = read()?.let(::decodeAndValidate)
                            ?: throw SpoolCodecException("ANDROID_SPOOL_METADATA_ROW_MISSING")
                        return@transaction SpoolStoreCommitResult.RevisionMismatch(
                            winner.storeRevision,
                        )
                    }
                    SpoolStoreCommitResult.Committed
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: SnapshotCommitOutcomeUnknownException) {
                SpoolStoreCommitResult.OutcomeUnknown
            } catch (failure: Exception) {
                SpoolStoreCommitResult.Unavailable(
                    AndroidSpoolFailureMapper.map(failure, "ANDROID_SPOOL_METADATA_COMMIT_FAILED"),
                )
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) database.close()
    }

    private fun bootstrapRow(): EncryptedSnapshotRow = encode(SpoolState.empty(initialQuota))

    private fun checkedBootstrapRow(): EncryptedSnapshotRow {
        // Even a provably-new store gets one attempt. Row loss later in the same process must not be
        // laundered into a second empty ledger.
        if (!emptyBootstrapPermission.compareAndSet(true, false)) {
            throw SpoolCodecException("ANDROID_SPOOL_METADATA_MISSING_FOR_EXISTING_STORE")
        }
        return bootstrapRow()
    }

    private fun encode(state: SpoolState): EncryptedSnapshotRow {
        val revisionToken = MetadataIdentity.revisionToken(state.storeRevision, keyring)
        val plaintext = SpoolStateBinaryCodec.encode(state)
        return try {
            EncryptedSnapshotRow(
                revisionToken = revisionToken,
                encryptedState = cipher.encrypt(plaintext, metadataAssociatedData(revisionToken)),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decodeAndValidate(row: EncryptedSnapshotRow): SpoolState {
        if (!MetadataIdentity.isValidToken(row.revisionToken)) {
            throw SpoolCodecException("ANDROID_SPOOL_METADATA_REVISION_TOKEN_INVALID")
        }
        val decrypted = cipher.decrypt(
            envelope = row.encryptedState,
            associatedData = metadataAssociatedData(row.revisionToken),
            maximumPlaintextBytes = MAX_METADATA_PLAINTEXT_BYTES,
        ).plaintext
        return try {
            SpoolStateBinaryCodec.decode(decrypted).also { state ->
                if (MetadataIdentity.revisionToken(state.storeRevision, keyring) != row.revisionToken) {
                    throw SpoolCodecException("ANDROID_SPOOL_METADATA_REVISION_MISMATCH")
                }
            }
        } finally {
            decrypted.fill(0)
        }
    }

    private fun metadataAssociatedData(revisionToken: String): ByteArray =
        "$METADATA_AAD_PREFIX$revisionToken".toByteArray(StandardCharsets.US_ASCII)

    private fun unavailableLoad(
        code: String,
        retryable: Boolean,
    ) = SpoolStoreLoadResult.Unavailable(SpoolStoreFailure(code, retryable))

    private fun unavailableCommit(
        code: String,
        retryable: Boolean,
    ) = SpoolStoreCommitResult.Unavailable(SpoolStoreFailure(code, retryable))

    companion object {
        private const val MAX_METADATA_PLAINTEXT_BYTES = 64 * 1024 * 1024
        private const val METADATA_AAD_PREFIX = "gumi/android-spool-metadata/v1/revision-token/"
    }
}

/** Actual Android SQLite transaction ledger. Its sole payload column is an encrypted snapshot. */
internal class AndroidSqliteSnapshotDatabase(
    databaseFile: File,
    fileOps: DurableFileOps,
) : SnapshotDatabase {
    private val database: SQLiteDatabase

    init {
        val parent = databaseFile.parentFile
            ?: throw IllegalArgumentException("Database must have a parent directory")
        fileOps.ensureDirectory(parent)
        var opened: SQLiteDatabase? = null
        try {
            val openParams = SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY)
                .addOpenFlags(SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                .setJournalMode("WAL")
                .setSynchronousMode("FULL")
                .build()
            val candidate = SQLiteDatabase.openDatabase(databaseFile, openParams)
            opened = candidate
            if (!candidate.isWriteAheadLoggingEnabled) {
                throw SpoolCodecException("ANDROID_SPOOL_WAL_UNAVAILABLE")
            }
            candidate.setMaximumSize(MAX_DATABASE_BYTES)
            candidate.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) != 1) {
                    throw SpoolCodecException("ANDROID_SPOOL_SECURE_DELETE_UNAVAILABLE")
                }
            }
            when (candidate.version) {
                0 -> {
                    candidate.beginTransaction()
                    try {
                        candidate.execSQL(
                            """
                            CREATE TABLE spool_snapshot (
                                singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
                                revision_token TEXT NOT NULL,
                                encrypted_state BLOB NOT NULL
                            )
                            """.trimIndent(),
                        )
                        candidate.version = SCHEMA_VERSION
                        candidate.setTransactionSuccessful()
                    } finally {
                        candidate.endTransaction()
                    }
                    fileOps.syncDirectory(parent)
                }
                SCHEMA_VERSION -> Unit
                else -> throw SpoolCodecException("ANDROID_SPOOL_SCHEMA_VERSION_UNSUPPORTED")
            }
            database = candidate
            opened = null
        } finally {
            opened?.let { runCatching(it::close) }
        }
    }

    override fun <T> transaction(block: SnapshotTransaction.() -> T): T {
        database.beginTransactionNonExclusive()
        var markedSuccessful = false
        var primaryFailure: Throwable? = null
        try {
            val result = SqliteSnapshotTransaction(database).block()
            database.setTransactionSuccessful()
            markedSuccessful = true
            return result
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                database.endTransaction()
            } catch (endFailure: Throwable) {
                primaryFailure?.addSuppressed(endFailure)
                if (primaryFailure == null) {
                    if (markedSuccessful) {
                        throw SnapshotCommitOutcomeUnknownException(endFailure)
                    }
                    throw endFailure
                }
            }
        }
    }

    override fun close() = database.close()

    private class SqliteSnapshotTransaction(
        private val database: SQLiteDatabase,
    ) : SnapshotTransaction {
        override fun read(): EncryptedSnapshotRow? = database.rawQuery(
            "SELECT revision_token, encrypted_state FROM spool_snapshot WHERE singleton = 1",
            emptyArray(),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            EncryptedSnapshotRow(
                revisionToken = cursor.getString(0),
                encryptedState = cursor.getBlob(1),
            )
        }

        override fun insertIfAbsent(row: EncryptedSnapshotRow): Boolean {
            val values = ContentValues().apply {
                put("singleton", 1)
                put("revision_token", row.revisionToken)
                put("encrypted_state", row.encryptedState)
            }
            return database.insertWithOnConflict(
                "spool_snapshot",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
        }

        override fun replaceIfRevision(
            expectedRevision: String,
            next: EncryptedSnapshotRow,
        ): Boolean {
            val values = ContentValues().apply {
                put("revision_token", next.revisionToken)
                put("encrypted_state", next.encryptedState)
            }
            return database.update(
                "spool_snapshot",
                values,
                "singleton = 1 AND revision_token = ?",
                arrayOf(expectedRevision),
            ) == 1
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_DATABASE_BYTES = 128L * 1024L * 1024L
    }
}
