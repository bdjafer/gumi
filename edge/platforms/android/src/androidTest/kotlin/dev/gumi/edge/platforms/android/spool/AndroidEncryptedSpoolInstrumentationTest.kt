package dev.gumi.edge.platforms.android.spool

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gumi.edge.platforms.android.operational.AndroidOperationalStoragePort
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.operational.OperationalBacklog
import dev.gumi.edge.runtime.operational.OperationalLeaseResult
import dev.gumi.edge.runtime.operational.OperationalRuntimeOperation
import dev.gumi.edge.runtime.operational.OperationalStorageOpenResult
import dev.gumi.edge.runtime.operational.ProvisionedDeviceBinding
import dev.gumi.edge.runtime.spool.AudioCodecDescriptor
import dev.gumi.edge.runtime.spool.CaptureSessionId
import dev.gumi.edge.runtime.spool.CaptureSpoolState
import dev.gumi.edge.runtime.spool.ChunkDescriptor
import dev.gumi.edge.runtime.spool.ChunkId
import dev.gumi.edge.runtime.spool.CodecConfigurationId
import dev.gumi.edge.runtime.spool.DurableChunk
import dev.gumi.edge.runtime.spool.DurableChunkRecord
import dev.gumi.edge.runtime.spool.DurablePayloadReadResult
import dev.gumi.edge.runtime.spool.DurablePayloadWriteResult
import dev.gumi.edge.runtime.spool.MediaPayloadFormat
import dev.gumi.edge.runtime.spool.OggOpusLayoutDescriptor
import dev.gumi.edge.runtime.spool.SequencePolicy
import dev.gumi.edge.runtime.spool.SequenceRange
import dev.gumi.edge.runtime.spool.SourceTimestamp
import dev.gumi.edge.runtime.spool.SpoolQuota
import dev.gumi.edge.runtime.spool.SpoolState
import dev.gumi.edge.runtime.spool.SpoolStoreCommitResult
import dev.gumi.edge.runtime.spool.SpoolStoreLoadResult
import dev.gumi.edge.runtime.spool.StreamDescriptor
import dev.gumi.edge.runtime.spool.StreamId
import dev.gumi.edge.runtime.spool.StreamSpoolState
import dev.gumi.edge.sdk.OpaqueBytes
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import java.io.File
import java.security.KeyStore
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device witness for APIs that local JVM tests deliberately cannot claim to prove. */
@RunWith(AndroidJUnit4::class)
class AndroidEncryptedSpoolInstrumentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openedStorages = mutableListOf<AndroidEncryptedSpoolStorage>()
    private val root: File get() = File(context.noBackupFilesDir, "gumi-spool-v1")
    private val ownershipFile: File
        get() = File(context.noBackupFilesDir, AndroidEncryptedSpoolStorage.OWNERSHIP_LOCK_FILE)

    @Before
    fun cleanBefore() = cleanup()

    @After
    fun cleanAfter() = cleanup()

    @Test
    fun keystoreSQLiteFsyncRestartExclusiveOwnershipAndKeyLossWitness() = runBlocking {
        assertTrue(root.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        val configuration = configuration()
        val firstOpen = assertIs<AndroidSpoolOpenResult.Ready>(
            AndroidEncryptedSpoolStorage.openAndReconcile(context, configuration),
        )
        assertEquals(AndroidSpoolReconciliationStatus.COMPLETE, firstOpen.reconciliation.status)
        val firstStorage = track(firstOpen.storage)
        assertEquals(
            SpoolState.empty(QUOTA),
            assertIs<SpoolStoreLoadResult.Loaded>(firstStorage.metadataStore.load()).state,
        )

        val duplicateOpen = try {
            AndroidEncryptedSpoolStorage.openAndReconcile(context, configuration)
            error("The lifetime ownership lease must reject a second open")
        } catch (failure: AndroidSpoolOpenException) {
            failure
        }
        assertEquals("ANDROID_SPOOL_STORAGE_ALREADY_OPEN", duplicateOpen.failureCode)

        val payload = "physical-secret-marker-one".encodeToByteArray()
        val descriptor = descriptor(payload, index = 1)
        val write = requireStored(
            firstStorage.payloadStore.writeAndFlush(descriptor, OpaqueBytes.copyOf(payload)),
        )
        val revisionOne = state(
            revision = 1uL,
            chunks = listOf(DurableChunk(descriptor, write.payloadRef)),
        )
        assertIs<SpoolStoreCommitResult.Committed>(
            firstStorage.metadataStore.commit(0uL, revisionOne),
        )
        firstStorage.close()

        val restart = assertIs<AndroidSpoolOpenResult.Ready>(
            AndroidEncryptedSpoolStorage.openAndReconcile(context, configuration),
        )
        assertEquals(AndroidSpoolReconciliationStatus.COMPLETE, restart.reconciliation.status)
        assertEquals(1, restart.reconciliation.verifiedPayloadCount)
        val restartedStorage = track(restart.storage)
        assertEquals(
            revisionOne,
            assertIs<SpoolStoreLoadResult.Loaded>(restartedStorage.metadataStore.load()).state,
        )
        assertContentEquals(
            payload,
            assertIs<DurablePayloadReadResult.Verified>(
                restartedStorage.payloadStore.readAndVerify(DurableChunk(descriptor, write.payloadRef)),
            ).payload.copyBytes(),
        )

        val diskBytes = root.walkTopDown()
            .filter(File::isFile)
            .flatMap { it.readBytes().asSequence() }
            .toList()
            .toByteArray()
        assertFalse(diskBytes.containsSubsequence(payload))
        assertFalse(diskBytes.containsSubsequence(CAPTURE.value.encodeToByteArray()))
        assertFalse(diskBytes.containsSubsequence(STREAM.value.encodeToByteArray()))
        restartedStorage.close()

        deleteKeystoreAlias("$KEY_PREFIX.enc.v1")
        val activeKeyLoss = try {
            AndroidEncryptedSpoolStorage.openAndReconcile(context, configuration)
            error("Existing storage must not recreate a lost encryption key")
        } catch (failure: AndroidSpoolOpenException) {
            failure
        }
        assertEquals("ANDROID_SPOOL_ACTIVE_KEY_UNAVAILABLE", activeKeyLoss.failureCode)
        assertFalse(keystoreContains("$KEY_PREFIX.enc.v1"))
    }

    @Test
    fun missingReferencedPayloadReturnsDegradedWithoutOperationalPorts() = runBlocking {
        val ready = assertIs<AndroidSpoolOpenResult.Ready>(
            AndroidEncryptedSpoolStorage.openAndReconcile(context, configuration()),
        )
        val storage = track(ready.storage)
        val payload = byteArrayOf(1, 2, 3, 4)
        val descriptor = descriptor(payload, index = 1)
        val write = requireStored(
            storage.payloadStore.writeAndFlush(descriptor, OpaqueBytes.copyOf(payload)),
        )
        assertIs<SpoolStoreCommitResult.Committed>(
            storage.metadataStore.commit(
                0uL,
                state(1uL, listOf(DurableChunk(descriptor, write.payloadRef))),
            ),
        )
        storage.close()
        val payloadFile = root.resolve("payloads").listFiles().orEmpty().single { it.name.endsWith(".gsp") }
        assertTrue(payloadFile.delete())

        val degraded = assertIs<AndroidSpoolOpenResult.Degraded>(
            AndroidEncryptedSpoolStorage.openAndReconcile(context, configuration()),
        )

        assertEquals(AndroidSpoolReconciliationStatus.COMPLETED_WITH_FAILURES, degraded.reconciliation.status)
        assertEquals(1, degraded.reconciliation.missingOrInvalidReferencedPayloadCount)
        assertEquals(setOf("ANDROID_SPOOL_PAYLOAD_NOT_FOUND"), degraded.reconciliation.failureCodes)
    }

    @Test
    fun existingDatabaseWithMissingSingletonRowNeverBootstrapsEmpty() = runBlocking {
        val ready = assertIs<AndroidSpoolOpenResult.Ready>(
            AndroidEncryptedSpoolStorage.openAndReconcile(context, configuration()),
        )
        track(ready.storage).close()
        SQLiteDatabase.openDatabase(
            root.resolve("metadata.sqlite").absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.delete("spool_snapshot", "singleton = 1", emptyArray())
        }

        val degraded = assertIs<AndroidSpoolOpenResult.Degraded>(
            AndroidEncryptedSpoolStorage.openAndReconcile(context, configuration()),
        )

        assertEquals(
            AndroidSpoolReconciliationStatus.SKIPPED_METADATA_UNAVAILABLE,
            degraded.reconciliation.status,
        )
        assertEquals(
            setOf("ANDROID_SPOOL_METADATA_MISSING_FOR_EXISTING_STORE"),
            degraded.reconciliation.failureCodes,
        )
    }

    @Test
    fun cancellationImmediatelyAfterOwnershipSettlesBeforeTheLockCanBeReopened() = runBlocking {
        val cancellation = try {
            AndroidEncryptedSpoolStorage.openAndReconcileForTesting(
                context = context,
                configuration = configuration(),
                fileOps = AndroidDurableFileOps(context),
                clockMillis = System::currentTimeMillis,
                ioDispatcher = Dispatchers.IO,
                afterOwnershipAcquired = { throw CancellationException("synthetic open cancellation") },
            )
            error("Opening should preserve cancellation after definitive partial cleanup")
        } catch (failure: CancellationException) {
            failure
        }
        assertEquals("synthetic open cancellation", cancellation.message)

        val reopened = assertIs<AndroidSpoolOpenResult.Ready>(
            AndroidEncryptedSpoolStorage.openAndReconcile(context, configuration()),
        )
        track(reopened.storage).close()
    }

    @Test
    fun operationalAdapterOwnsExclusivelyAndReopensOnlyAfterQuiescedStop() {
        runBlocking {
            val port = AndroidOperationalStoragePort(context, configuration())
            val binding = ProvisionedDeviceBinding(DeviceId("instrumented-provisioned-device"))
            val firstOperation = operationalOperation("first", hostGeneration = 1uL, sessionGeneration = 1uL)
            val first = assertIs<OperationalStorageOpenResult.Ready>(
                port.openAndReconcile(firstOperation, binding),
            )
            try {
                assertEquals(OperationalBacklog.Empty, first.backlog)
                val duplicate = assertIs<OperationalStorageOpenResult.Failed>(
                    port.openAndReconcile(
                        operationalOperation("duplicate", 2uL, 2uL),
                        ProvisionedDeviceBinding(DeviceId("other-instrumented-device")),
                    ),
                )
                assertEquals("ANDROID_SPOOL_STORAGE_ALREADY_OPEN", duplicate.failure.code.value)
            } finally {
                val stop = operationalOperation("first-stop", 3uL, 1uL)
                assertIs<OperationalLeaseResult.Completed>(first.lease.quiesce(stop))
                assertIs<OperationalLeaseResult.Completed>(first.lease.close(stop))
            }

            val reopened = assertIs<OperationalStorageOpenResult.Ready>(
                port.openAndReconcile(
                    operationalOperation("reopened", 4uL, 3uL),
                    binding,
                ),
            )
            val reopenStop = operationalOperation("reopened-stop", 5uL, 3uL)
            assertIs<OperationalLeaseResult.Completed>(reopened.lease.quiesce(reopenStop))
            assertIs<OperationalLeaseResult.Completed>(reopened.lease.close(reopenStop))
        }
    }

    @Test
    fun androidPrimitiveBootstrapStagesRemainExecutable() = runBlocking {
        val configuration = configuration()
        val ownership = AndroidSpoolStorageLease.acquire(ownershipFile)
        var metadata: EncryptedSnapshotSpoolStore? = null
        try {
            assertFalse(root.exists())
            val fileOps = AndroidDurableFileOps(context)
            val keyring = AndroidKeystoreSpoolKeyring(
                aliasPrefix = configuration.keystoreAliasPrefix,
                initialActiveEncryptionKeyVersion = 1,
                allowInitialKeyCreation = true,
            )
            val payloads = EncryptedPayloadFileStore(
                directory = root.resolve("payloads"),
                keyring = keyring,
                fileOps = fileOps,
                minimumFreeBytes = configuration.minimumFilesystemFreeBytes,
                maximumPayloadBytes = configuration.maximumPayloadBytes,
            )
            metadata = EncryptedSnapshotSpoolStore(
                database = AndroidSqliteSnapshotDatabase(root.resolve("metadata.sqlite"), fileOps),
                keyring = keyring,
                initialQuota = configuration.quota,
                allowEmptyBootstrap = true,
            )
            assertIs<SpoolStoreLoadResult.Loaded>(metadata.load())
            val report = SpoolRestartReconciler(
                payloadStore = payloads,
                concretePayloadStore = payloads,
                fileOps = fileOps,
                orphanGracePeriodMillis = configuration.orphanGracePeriodMillis,
                clockMillis = System::currentTimeMillis,
            ).reconcile(metadata)
            assertEquals(AndroidSpoolReconciliationStatus.COMPLETE, report.status)
        } finally {
            metadata?.close()
            ownership.close()
        }
    }

    @Test
    fun androidDurableFileStagesRemainExecutable() {
        val fileOps = AndroidDurableFileOps(context)
        val directory = root.resolve("payloads")
        fileOps.ensureDirectory(directory)
        assertTrue(fileOps.usableBytes(directory) > 0L)
        val target = directory.resolve("instrumentation.gsp")
        assertEquals(
            ImmutableInstallResult.INSTALLED,
            fileOps.installImmutable(directory, target, byteArrayOf(1, 2, 3, 4)),
        )
        assertEquals(
            ImmutableInstallResult.ALREADY_EXISTS,
            fileOps.installImmutable(directory, target, byteArrayOf(9, 9, 9, 9)),
        )
        assertContentEquals(byteArrayOf(1, 2, 3, 4), fileOps.readBounded(target, 4))
        assertTrue(fileOps.deleteDurably(directory, target))
    }

    private fun configuration() = AndroidSpoolStorageConfiguration(
        quota = QUOTA,
        minimumFilesystemFreeBytes = 0L,
        maximumPayloadBytes = 1_024,
        orphanGracePeriodMillis = 60_000L,
        keystoreAliasPrefix = KEY_PREFIX,
    )

    private fun track(storage: AndroidEncryptedSpoolStorage): AndroidEncryptedSpoolStorage =
        storage.also(openedStorages::add)

    private fun operationalOperation(
        label: String,
        hostGeneration: ULong,
        sessionGeneration: ULong,
    ) = OperationalRuntimeOperation(
        RuntimeHostOperation(
            commandId = CommandId("instrumented-operational-storage-$label"),
            correlationId = CorrelationId("instrumented-operational-storage-$label-correlation"),
            generation = hostGeneration,
        ),
        sessionGeneration,
    )

    private fun requireStored(result: DurablePayloadWriteResult): DurablePayloadWriteResult.Stored =
        when (result) {
            is DurablePayloadWriteResult.Stored -> result
            is DurablePayloadWriteResult.Unavailable -> error(
                "Payload write failed with ${result.failure.code}",
            )
        }

    private fun state(
        revision: ULong,
        chunks: List<DurableChunk>,
    ): SpoolState {
        val streamDescriptor = StreamDescriptor(
            captureSessionId = CAPTURE,
            streamId = STREAM,
            sequencePolicy = SequencePolicy(1uL, 100uL),
            maxChunkBytes = 1_024uL,
            maxTotalBytes = 10_000uL,
            payloadFormat = MediaPayloadFormat.OGG_OPUS_PAGE_FRAGMENT_V1,
            codec = CODEC,
            oggLayout = OggOpusLayoutDescriptor(serialNumber = 42u, preSkip48kSamples = 312u),
        )
        val records = chunks.associate { chunk ->
            chunk.descriptor.chunkId to DurableChunkRecord(chunk)
        }
        val stream = StreamSpoolState(
            descriptor = streamDescriptor,
            chunks = records,
            sourceDurableThrough = chunks.maxOf { it.descriptor.sequenceRange.last },
        )
        return SpoolState(
            storeRevision = revision,
            quota = QUOTA,
            captures = mapOf(
                CAPTURE to CaptureSpoolState(
                    captureSessionId = CAPTURE,
                    streams = mapOf(STREAM to stream),
                ),
            ),
        )
    }

    private fun descriptor(
        bytes: ByteArray,
        index: Int,
    ) = ChunkDescriptor(
        captureSessionId = CAPTURE,
        streamId = STREAM,
        chunkId = ChunkId(uuid(10 + index)),
        sequenceRange = SequenceRange(index.toULong(), index.toULong()),
        payloadBytes = bytes.size.toULong(),
        payloadFormat = MediaPayloadFormat.OGG_OPUS_PAGE_FRAGMENT_V1,
        contentDigest = PayloadIdentity.sha256(bytes),
        codecConfigurationId = CODEC.configurationId,
        sourceStartedAt = SourceTimestamp("2026-07-19T20:00:01Z"),
        sourceRetransmission = false,
    )

    private fun cleanup() {
        openedStorages.asReversed().forEach { storage -> runCatching(storage::close) }
        openedStorages.clear()
        root.deleteRecursively()
        ownershipFile.delete()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.aliases().toList().filter { it.startsWith(KEY_PREFIX) }.forEach(keyStore::deleteEntry)
    }

    private fun deleteKeystoreAlias(alias: String) {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }

    private fun keystoreContains(alias: String): Boolean =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                this[start + offset] == needle[offset]
            }
        }
    }

    companion object {
        private const val KEY_PREFIX = "dev.gumi.edge.spool.instrumentation"
        private val QUOTA = SpoolQuota(8_000uL, 10_000uL)
        private val CAPTURE = CaptureSessionId(uuid(1))
        private val STREAM = StreamId(uuid(2))
        private val CODEC = AudioCodecDescriptor(
            configurationId = CodecConfigurationId("opus-16000-mono-20ms-v1"),
            sampleRateHz = 16_000u,
            channelCount = 1u,
            frameDurationUs = 20_000u,
        )

        private fun uuid(index: Int): String =
            "0190c6f0-7b21-7a40-8b11-${index.toString().padStart(12, '0')}"
    }
}
