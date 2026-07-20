package dev.gumi.edge.platforms.android.spool

import dev.gumi.edge.runtime.spool.SpoolState
import dev.gumi.edge.runtime.spool.SpoolStoreCommitResult
import dev.gumi.edge.runtime.spool.SpoolStoreLoadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class EncryptedSnapshotSpoolStoreTest {
    @Test
    fun `first load bootstraps encrypted revision zero and restart recovers exact state`() = runBlocking {
        val database = InMemorySnapshotDatabase()
        val keyring = TestSpoolKeyring()
        val firstProcess = store(database, keyring)

        assertEquals(
            SpoolState.empty(TEST_QUOTA),
            assertIs<SpoolStoreLoadResult.Loaded>(firstProcess.load()).state,
        )
        val row = requireNotNull(database.snapshot())
        val ciphertextText = row.encryptedState.toString(Charsets.ISO_8859_1)
        assertFalse(ciphertextText.contains(TEST_CAPTURE.value))
        assertFalse(ciphertextText.contains(TEST_STREAM.value))

        val next = richState(revision = 1uL)
        assertIs<SpoolStoreCommitResult.Committed>(firstProcess.commit(0uL, next))

        val restarted = store(database, keyring)
        assertEquals(next, assertIs<SpoolStoreLoadResult.Loaded>(restarted.load()).state)
    }

    @Test
    fun `compare and set admits one writer and reports winning revision to the loser`() = runBlocking {
        val database = InMemorySnapshotDatabase()
        val keyring = TestSpoolKeyring()
        val left = store(database, keyring)
        val right = store(database, keyring)
        assertIs<SpoolStoreLoadResult.Loaded>(left.load())

        val first = async { left.commit(0uL, richState(revision = 1uL)) }
        val second = async {
            right.commit(0uL, SpoolState.empty(TEST_QUOTA).copy(storeRevision = 1uL))
        }
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it is SpoolStoreCommitResult.Committed })
        val mismatch = assertIs<SpoolStoreCommitResult.RevisionMismatch>(
            results.single { it is SpoolStoreCommitResult.RevisionMismatch },
        )
        assertEquals(1uL, mismatch.actualRevision)
    }

    @Test
    fun `lost commit response returns outcome unknown and reload reconciles committed state`() =
        runBlocking {
            val database = InMemorySnapshotDatabase()
            val keyring = TestSpoolKeyring()
            val store = store(database, keyring)
            assertIs<SpoolStoreLoadResult.Loaded>(store.load())
            database.loseNextCommitResponse = true
            val next = richState(revision = 1uL)

            assertIs<SpoolStoreCommitResult.OutcomeUnknown>(store.commit(0uL, next))
            assertEquals(next, assertIs<SpoolStoreLoadResult.Loaded>(store.load()).state)
        }

    @Test
    fun `revision token substitution and ciphertext corruption fail closed with redacted codes`() =
        runBlocking {
            val database = InMemorySnapshotDatabase()
            val keyring = TestSpoolKeyring()
            val store = store(database, keyring)
            assertIs<SpoolStoreLoadResult.Loaded>(store.load())
            val valid = requireNotNull(database.snapshot())

            database.replaceForTest(
                valid.copy(revisionToken = MetadataIdentity.revisionToken(1uL, keyring)),
            )
            val replay = assertIs<SpoolStoreLoadResult.Unavailable>(store.load())
            assertEquals("ANDROID_SPOOL_INTEGRITY_FAILED", replay.failure.code)
            assertFalse(replay.failure.retryable)

            val corrupted = valid.encryptedState.copyOf().apply {
                this[lastIndex] = this[lastIndex].inc()
            }
            database.replaceForTest(valid.copy(encryptedState = corrupted))
            val corruption = assertIs<SpoolStoreLoadResult.Unavailable>(store.load())
            assertEquals("ANDROID_SPOOL_INTEGRITY_FAILED", corruption.failure.code)
            assertFalse(corruption.toString().contains(TEST_CAPTURE.value))
        }

    @Test
    fun `AEAD does not claim freshness against replay of a complete older authenticated row`() =
        runBlocking {
            val database = InMemorySnapshotDatabase()
            val keyring = TestSpoolKeyring()
            val store = store(database, keyring)
            assertIs<SpoolStoreLoadResult.Loaded>(store.load())
            val authenticatedRevisionZero = requireNotNull(database.snapshot())
            assertIs<SpoolStoreCommitResult.Committed>(store.commit(0uL, richState(revision = 1uL)))

            database.replaceForTest(authenticatedRevisionZero)

            val rolledBack = assertIs<SpoolStoreLoadResult.Loaded>(store.load()).state
            assertEquals(0uL, rolledBack.storeRevision)
            assertEquals(SpoolState.empty(TEST_QUOTA), rolledBack)
        }

    @Test
    fun `metadata reader accepts old envelope before test policy selects a new key version`() =
        runBlocking {
            val database = InMemorySnapshotDatabase()
            val keyring = TestSpoolKeyring()
            val store = store(database, keyring)
            assertIs<SpoolStoreLoadResult.Loaded>(store.load())
            val revisionOne = richState(revision = 1uL)
            assertIs<SpoolStoreCommitResult.Committed>(store.commit(0uL, revisionOne))

            keyring.rotateTo(2)
            assertEquals(revisionOne, assertIs<SpoolStoreLoadResult.Loaded>(store.load()).state)
            val revisionTwo = revisionOne.copy(storeRevision = 2uL)
            assertIs<SpoolStoreCommitResult.Committed>(store.commit(1uL, revisionTwo))

            keyring.remove(1)
            assertEquals(revisionTwo, assertIs<SpoolStoreLoadResult.Loaded>(store.load()).state)
        }

    @Test
    fun `invalid revision transitions and closed lifecycle are rejected before database mutation`() =
        runBlocking {
            val database = InMemorySnapshotDatabase()
            val store = store(database, TestSpoolKeyring())
            assertIs<SpoolStoreLoadResult.Loaded>(store.load())
            val initialToken = database.snapshot()?.revisionToken

            val invalid = assertIs<SpoolStoreCommitResult.Unavailable>(
                store.commit(0uL, richState(revision = 2uL)),
            )
            assertEquals("ANDROID_SPOOL_REVISION_TRANSITION_INVALID", invalid.failure.code)
            assertEquals(initialToken, database.snapshot()?.revisionToken)

            store.close()
            val closed = assertIs<SpoolStoreLoadResult.Unavailable>(store.load())
            assertEquals("ANDROID_SPOOL_DATABASE_CLOSED", closed.failure.code)
            assertTrue(database.snapshot() != null)
        }

    @Test
    fun `existing storage cannot bootstrap an empty ledger even with no surviving payload`() =
        runBlocking {
            val database = InMemorySnapshotDatabase()
            val store = EncryptedSnapshotSpoolStore(
                database = database,
                keyring = TestSpoolKeyring(),
                initialQuota = TEST_QUOTA,
                allowEmptyBootstrap = false,
            )

            val unavailable = assertIs<SpoolStoreLoadResult.Unavailable>(store.load())

            assertEquals(
                "ANDROID_SPOOL_METADATA_MISSING_FOR_EXISTING_STORE",
                unavailable.failure.code,
            )
            assertEquals(null, database.snapshot())
        }

    @Test
    fun `new-store bootstrap permission is consumed and cannot launder later row loss`() = runBlocking {
        val database = InMemorySnapshotDatabase()
        val store = store(database, TestSpoolKeyring())
        assertIs<SpoolStoreLoadResult.Loaded>(store.load())
        database.deleteForTest()

        val unavailable = assertIs<SpoolStoreLoadResult.Unavailable>(store.load())

        assertEquals(
            "ANDROID_SPOOL_METADATA_MISSING_FOR_EXISTING_STORE",
            unavailable.failure.code,
        )
        assertEquals(null, database.snapshot())
    }

    private fun store(
        database: InMemorySnapshotDatabase,
        keyring: TestSpoolKeyring,
    ) = EncryptedSnapshotSpoolStore(
        database = database,
        keyring = keyring,
        initialQuota = TEST_QUOTA,
    )
}
