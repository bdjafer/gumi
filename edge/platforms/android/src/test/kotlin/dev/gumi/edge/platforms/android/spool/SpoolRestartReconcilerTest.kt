package dev.gumi.edge.platforms.android.spool

import dev.gumi.edge.runtime.spool.CaptureSpoolState
import dev.gumi.edge.runtime.spool.DurableChunk
import dev.gumi.edge.runtime.spool.DurableChunkRecord
import dev.gumi.edge.runtime.spool.DurablePayloadWriteResult
import dev.gumi.edge.runtime.spool.MediaPayloadFormat
import dev.gumi.edge.runtime.spool.OggOpusLayoutDescriptor
import dev.gumi.edge.runtime.spool.SequencePolicy
import dev.gumi.edge.runtime.spool.SpoolState
import dev.gumi.edge.runtime.spool.SpoolStore
import dev.gumi.edge.runtime.spool.SpoolStoreCommitResult
import dev.gumi.edge.runtime.spool.SpoolStoreFailure
import dev.gumi.edge.runtime.spool.SpoolStoreLoadResult
import dev.gumi.edge.runtime.spool.StreamDescriptor
import dev.gumi.edge.runtime.spool.StreamSpoolState
import dev.gumi.edge.sdk.OpaqueBytes
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SpoolRestartReconcilerTest {
    @Test
    fun `trusted restart verifies references and collects only recognized old orphans`() = runBlocking {
        withPayloadStore { store, fileOps ->
            val now = 2_000_000L
            val referencedBytes = byteArrayOf(1, 2, 3)
            val referencedDescriptor = testDescriptor(referencedBytes, index = 1, sequence = 1uL)
            val referenced = assertIs<DurablePayloadWriteResult.Stored>(
                store.writeAndFlush(referencedDescriptor, OpaqueBytes.copyOf(referencedBytes)),
            )

            val orphanBytes = byteArrayOf(4, 5)
            val orphanDescriptor = testDescriptor(orphanBytes, index = 2, sequence = 2uL)
            val orphan = assertIs<DurablePayloadWriteResult.Stored>(
                store.writeAndFlush(orphanDescriptor, OpaqueBytes.copyOf(orphanBytes)),
            )
            val orphanFile = store.payloadFile(orphan.payloadRef.value)
            assertTrue(orphanFile.setLastModified(now - 10_000L))

            val youngBytes = byteArrayOf(6, 7)
            val youngDescriptor = testDescriptor(youngBytes, index = 3, sequence = 3uL)
            val young = assertIs<DurablePayloadWriteResult.Stored>(
                store.writeAndFlush(youngDescriptor, OpaqueBytes.copyOf(youngBytes)),
            )
            val youngFile = store.payloadFile(young.payloadRef.value)
            assertTrue(youngFile.setLastModified(now - 100L))

            val oldTemp = store.directory().resolve(".pending-restart.tmp").apply {
                writeBytes(byteArrayOf(9))
                setLastModified(now - 10_000L)
            }
            val unknown = store.directory().resolve("operator-note").apply {
                writeText("not spool data")
                setLastModified(now - 10_000L)
            }
            val state = stateWith(DurableChunk(referencedDescriptor, referenced.payloadRef))
            val report = reconciler(store, fileOps, now).reconcile(LoadedStore(state))

            assertEquals(AndroidSpoolReconciliationStatus.COMPLETE, report.status)
            assertEquals(1, report.referencedPayloadCount)
            assertEquals(1, report.verifiedPayloadCount)
            assertEquals(0, report.missingOrInvalidReferencedPayloadCount)
            assertEquals(1, report.retainedYoungOrphanCount)
            assertEquals(2, report.deletedOrphanCount)
            assertEquals(1, report.untouchedUnknownFileCount)
            assertTrue(report.failureCodes.isEmpty())
            assertFalse(orphanFile.exists())
            assertFalse(oldTemp.exists())
            assertTrue(youngFile.exists())
            assertTrue(unknown.exists())
            assertTrue(store.payloadFile(referenced.payloadRef.value).exists())
        }
    }

    @Test
    fun `metadata failure suppresses all deletion and exposes only stable code`() = runBlocking {
        withPayloadStore { store, fileOps ->
            val now = 2_000_000L
            val orphan = store.directory().resolve(".pending-unknown.tmp").apply {
                writeBytes(byteArrayOf(1, 2, 3))
                setLastModified(now - 100_000L)
            }
            val unavailable = object : SpoolStore {
                override suspend fun load() = SpoolStoreLoadResult.Unavailable(
                    SpoolStoreFailure("TEST_METADATA_UNAVAILABLE", retryable = true),
                )

                override suspend fun commit(expectedRevision: ULong, next: SpoolState) =
                    SpoolStoreCommitResult.Unavailable(
                        SpoolStoreFailure("TEST_METADATA_UNAVAILABLE", retryable = true),
                    )
            }

            val report = reconciler(store, fileOps, now).reconcile(unavailable)

            assertEquals(AndroidSpoolReconciliationStatus.SKIPPED_METADATA_UNAVAILABLE, report.status)
            assertEquals(setOf("TEST_METADATA_UNAVAILABLE"), report.failureCodes)
            assertFalse(report.allReferencedPayloadsVerified)
            assertTrue(orphan.exists())
        }
    }

    @Test
    fun `missing referenced payload is reported and never laundered as successful recovery`() =
        runBlocking {
            withPayloadStore { store, fileOps ->
                val payload = byteArrayOf(8, 8)
                val descriptor = testDescriptor(payload)
                val expectedRef = PayloadIdentity.reference(descriptor, TestSpoolKeyring())
                val state = stateWith(DurableChunk(descriptor, expectedRef))

                val report = reconciler(store, fileOps, 2_000_000L).reconcile(LoadedStore(state))

                assertEquals(AndroidSpoolReconciliationStatus.COMPLETED_WITH_FAILURES, report.status)
                assertEquals(1, report.missingOrInvalidReferencedPayloadCount)
                assertEquals(setOf("ANDROID_SPOOL_PAYLOAD_NOT_FOUND"), report.failureCodes)
                assertFalse(report.allReferencedPayloadsVerified)
            }
        }

    @Test
    fun `cleanup failure is a warning when every referenced payload remains verified`() = runBlocking {
        withPayloadStore { store, fileOps ->
            val now = 2_000_000L
            val orphanBytes = byteArrayOf(4, 5)
            val orphanDescriptor = testDescriptor(orphanBytes, index = 2, sequence = 2uL)
            val orphan = assertIs<DurablePayloadWriteResult.Stored>(
                store.writeAndFlush(orphanDescriptor, OpaqueBytes.copyOf(orphanBytes)),
            )
            assertTrue(store.payloadFile(orphan.payloadRef.value).setLastModified(now - 10_000L))
            fileOps.deleteFailure = IOException("test-only delete failure")

            val report = reconciler(store, fileOps, now).reconcile(
                LoadedStore(SpoolState.empty(TEST_QUOTA)),
            )

            assertEquals(AndroidSpoolReconciliationStatus.COMPLETED_WITH_FAILURES, report.status)
            assertEquals(setOf("ANDROID_SPOOL_STORAGE_IO_FAILED"), report.failureCodes)
            assertTrue(report.allReferencedPayloadsVerified)
        }
    }

    private fun stateWith(chunk: DurableChunk): SpoolState {
        val streamDescriptor = StreamDescriptor(
            captureSessionId = TEST_CAPTURE,
            streamId = TEST_STREAM,
            sequencePolicy = SequencePolicy(chunk.descriptor.sequenceRange.first, 100uL),
            maxChunkBytes = 1_000uL,
            maxTotalBytes = 5_000uL,
            payloadFormat = MediaPayloadFormat.OGG_OPUS_PAGE_FRAGMENT_V1,
            codec = TEST_CODEC,
            oggLayout = OggOpusLayoutDescriptor(serialNumber = 42u, preSkip48kSamples = 312u),
        )
        val stream = StreamSpoolState(
            descriptor = streamDescriptor,
            chunks = mapOf(chunk.descriptor.chunkId to DurableChunkRecord(chunk)),
            sourceDurableThrough = chunk.descriptor.sequenceRange.last,
        )
        return SpoolState(
            storeRevision = 1uL,
            quota = TEST_QUOTA,
            captures = mapOf(
                TEST_CAPTURE to CaptureSpoolState(
                    captureSessionId = TEST_CAPTURE,
                    streams = mapOf(TEST_STREAM to stream),
                ),
            ),
        )
    }

    private fun reconciler(
        store: EncryptedPayloadFileStore,
        fileOps: TestDurableFileOps,
        now: Long,
    ) = SpoolRestartReconciler(
        payloadStore = store,
        concretePayloadStore = store,
        fileOps = fileOps,
        orphanGracePeriodMillis = 1_000L,
        clockMillis = { now },
    )

    private suspend fun withPayloadStore(
        block: suspend (EncryptedPayloadFileStore, TestDurableFileOps) -> Unit,
    ) {
        val root = Files.createTempDirectory("gumi-spool-reconcile-test-").toFile()
        try {
            val fileOps = TestDurableFileOps()
            val store = EncryptedPayloadFileStore(
                directory = root,
                keyring = TestSpoolKeyring(),
                fileOps = fileOps,
                minimumFreeBytes = 0L,
                maximumPayloadBytes = 1_024,
            )
            block(store, fileOps)
        } finally {
            root.deleteRecursively()
        }
    }

    private class LoadedStore(private val state: SpoolState) : SpoolStore {
        override suspend fun load() = SpoolStoreLoadResult.Loaded(state)

        override suspend fun commit(expectedRevision: ULong, next: SpoolState) =
            error("Reconciliation never writes metadata")
    }
}
