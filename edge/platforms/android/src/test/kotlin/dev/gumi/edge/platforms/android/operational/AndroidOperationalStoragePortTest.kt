package dev.gumi.edge.platforms.android.operational

import dev.gumi.edge.platforms.android.spool.AndroidSpoolCloseException
import dev.gumi.edge.platforms.android.spool.richState
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.operational.OperationalBacklog
import dev.gumi.edge.runtime.operational.OperationalBacklogScope
import dev.gumi.edge.runtime.operational.OperationalLeaseResult
import dev.gumi.edge.runtime.operational.OperationalRuntimeOperation
import dev.gumi.edge.runtime.operational.OperationalStorageOpenResult
import dev.gumi.edge.runtime.operational.ProvisionedDeviceBinding
import dev.gumi.edge.runtime.spool.ChunkDescriptor
import dev.gumi.edge.runtime.spool.DurableChunk
import dev.gumi.edge.runtime.spool.DurablePayloadReadResult
import dev.gumi.edge.runtime.spool.DurablePayloadStore
import dev.gumi.edge.runtime.spool.DurablePayloadWriteResult
import dev.gumi.edge.runtime.spool.SpoolState
import dev.gumi.edge.runtime.spool.SpoolStore
import dev.gumi.edge.runtime.spool.SpoolStoreCommitResult
import dev.gumi.edge.runtime.spool.SpoolStoreFailure
import dev.gumi.edge.runtime.spool.SpoolStoreLoadResult
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.OpaqueBytes
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidOperationalStoragePortTest {
    @Test
    fun `ready is emitted only after portable recovery and reports exact retained backlog`() = runTest {
        val firstStorage = FakeOperationalSpool(SpoolStoreLoadResult.Loaded(richState()))
        val secondStorage = FakeOperationalSpool(SpoolStoreLoadResult.Loaded(richState(10uL)))
        val opens = ArrayDeque(listOf(firstStorage, secondStorage))
        val port = port { AndroidOperationalSpoolOpenResult.Ready(opens.removeFirst()) }
        val firstOperation = operation("first", hostGeneration = 1uL, sessionGeneration = 1uL)

        val first = assertIs<OperationalStorageOpenResult.Ready>(
            port.openAndReconcile(firstOperation, binding),
        )

        assertEquals(firstOperation, first.operation)
        assertEquals(OperationalBacklog(pendingChunkCount = 1uL, retainedPayloadBytes = 5uL), first.backlog)
        assertEquals(OperationalBacklogScope.EDGE_HOST, first.backlogScope)
        assertEquals(1, firstStorage.loadCount)
        assertEquals(0, firstStorage.closeCount)

        val closeBeforeQuiesce = assertIs<OperationalLeaseResult.Failed>(
            first.lease.close(operation("stop", 2uL, 1uL)),
        )
        assertEquals(
            "ANDROID_OPERATIONAL_STORAGE_CLOSE_BEFORE_QUIESCE",
            closeBeforeQuiesce.failure.code.value,
        )
        assertEquals(0, firstStorage.closeCount)

        val stop = operation("stop", hostGeneration = 2uL, sessionGeneration = 1uL)
        assertIs<OperationalLeaseResult.Completed>(first.lease.quiesce(stop))
        assertIs<OperationalLeaseResult.Completed>(first.lease.quiesce(stop))
        assertIs<OperationalLeaseResult.Completed>(first.lease.close(stop))
        assertIs<OperationalLeaseResult.Completed>(first.lease.close(stop))
        assertEquals(1, firstStorage.closeCount)

        val reopened = assertIs<OperationalStorageOpenResult.Ready>(
            port.openAndReconcile(
                operation("reopen", hostGeneration = 3uL, sessionGeneration = 2uL),
                binding,
            ),
        )
        assertEquals(1, secondStorage.loadCount)
        val reopenStop = operation("reopen-stop", 4uL, 2uL)
        reopened.lease.quiesce(reopenStop)
        reopened.lease.close(reopenStop)
        assertEquals(1, secondStorage.closeCount)
    }

    @Test
    fun `platform reconciliation failure exposes no lease`() = runTest {
        val port = port { AndroidOperationalSpoolOpenResult.Degraded }
        val operation = operation("degraded", 1uL, 1uL)

        val failed = assertIs<OperationalStorageOpenResult.Failed>(
            port.openAndReconcile(operation, binding),
        )

        assertEquals("ANDROID_OPERATIONAL_STORAGE_RECONCILIATION_FAILED", failed.failure.code.value)
        assertEquals(operation.hostOperation.correlationId, failed.failure.correlationId)
    }

    @Test
    fun `platform open uncertainty exposes only a cleanup lease and replays close uncertainty`() =
        runTest {
            val storage = CloseOnlyOperationalSpool(
                AndroidSpoolCloseException("ANDROID_SPOOL_OWNERSHIP_RELEASE_FAILED"),
            )
            val port = port {
                AndroidOperationalSpoolOpenResult.OutcomeUnknown(
                    storage,
                    "ANDROID_SPOOL_OWNERSHIP_RELEASE_FAILED",
                )
            }
            val open = operation("partial-open-unknown", 1uL, 4uL)

            val unknown = assertIs<OperationalStorageOpenResult.OutcomeUnknown>(
                port.openAndReconcile(open, binding),
            )

            assertEquals("ANDROID_SPOOL_OWNERSHIP_RELEASE_FAILED", unknown.failure.code.value)
            val cleanup = operation("partial-open-cleanup", 2uL, 4uL)
            assertIs<OperationalLeaseResult.Completed>(unknown.lease.quiesce(cleanup))
            assertEquals(
                "ANDROID_SPOOL_OWNERSHIP_RELEASE_FAILED",
                assertIs<OperationalLeaseResult.OutcomeUnknown>(
                    unknown.lease.close(cleanup),
                ).failure.code.value,
            )
            assertIs<OperationalLeaseResult.OutcomeUnknown>(unknown.lease.close(cleanup))
            assertEquals(1, storage.closeCount)
        }

    @Test
    fun `portable recovery failure closes ownership and exposes no usable lease`() = runTest {
        val storage = FakeOperationalSpool(
            SpoolStoreLoadResult.Unavailable(
                SpoolStoreFailure("ANDROID_SPOOL_DATABASE_UNAVAILABLE", retryable = true),
            ),
        )
        val port = port { AndroidOperationalSpoolOpenResult.Ready(storage) }
        val operation = operation("recover-failed", 1uL, 1uL)

        val failed = assertIs<OperationalStorageOpenResult.Failed>(
            port.openAndReconcile(operation, binding),
        )

        assertEquals("ANDROID_SPOOL_DATABASE_UNAVAILABLE", failed.failure.code.value)
        assertTrue(failed.failure.retryable)
        assertEquals(1, storage.loadCount)
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun `recovery failure with uncertain close retains a fenced lease and never replays success`() =
        runTest {
            val storage = FakeOperationalSpool(
                loadResult = SpoolStoreLoadResult.Unavailable(
                    SpoolStoreFailure("ANDROID_SPOOL_METADATA_LOAD_FAILED", retryable = false),
                ),
                closeFailure = AndroidSpoolCloseException(
                    "ANDROID_SPOOL_OWNERSHIP_RELEASE_FAILED",
                ),
            )
            val port = port { AndroidOperationalSpoolOpenResult.Ready(storage) }
            val open = operation("unknown-open", 1uL, 1uL)

            val unknown = assertIs<OperationalStorageOpenResult.OutcomeUnknown>(
                port.openAndReconcile(open, binding),
            )

            assertEquals("ANDROID_SPOOL_OWNERSHIP_RELEASE_FAILED", unknown.failure.code.value)
            assertEquals(1, storage.closeCount)
            val cleanup = operation("unknown-cleanup", 2uL, 1uL)
            assertIs<OperationalLeaseResult.Completed>(unknown.lease.quiesce(cleanup))
            val replay = assertIs<OperationalLeaseResult.OutcomeUnknown>(
                unknown.lease.close(cleanup),
            )
            assertEquals("ANDROID_SPOOL_OWNERSHIP_RELEASE_FAILED", replay.failure.code.value)
            assertEquals(cleanup.hostOperation.correlationId, replay.failure.correlationId)
            assertEquals(1, storage.closeCount)
        }

    @Test
    fun `recovery cancellation with uncertain close returns the cleanup lease before cancellation escapes`() =
        runTest {
            val storage = FakeOperationalSpool(
                loadResult = SpoolStoreLoadResult.Loaded(SpoolState.empty(richState().quota)),
                loadFailure = CancellationException("synthetic recovery cancellation"),
                closeFailure = AndroidSpoolCloseException(
                    "ANDROID_SPOOL_OWNERSHIP_RELEASE_FAILED",
                ),
            )
            val port = port { AndroidOperationalSpoolOpenResult.Ready(storage) }

            val unknown = assertIs<OperationalStorageOpenResult.OutcomeUnknown>(
                port.openAndReconcile(operation("cancelled-recovery", 1uL, 9uL), binding),
            )

            assertEquals("ANDROID_SPOOL_OWNERSHIP_RELEASE_FAILED", unknown.failure.code.value)
            assertEquals(1, storage.loadCount)
            assertEquals(1, storage.closeCount)
            val cleanup = operation("cancelled-recovery-cleanup", 2uL, 9uL)
            assertIs<OperationalLeaseResult.Completed>(unknown.lease.quiesce(cleanup))
            assertIs<OperationalLeaseResult.OutcomeUnknown>(unknown.lease.close(cleanup))
            assertEquals(1, storage.closeCount)
        }

    @Test
    fun `a stale session cannot quiesce or close the live storage owner`() = runTest {
        val storage = FakeOperationalSpool(SpoolStoreLoadResult.Loaded(SpoolState.empty(richState().quota)))
        val port = port { AndroidOperationalSpoolOpenResult.Ready(storage) }
        val ready = assertIs<OperationalStorageOpenResult.Ready>(
            port.openAndReconcile(operation("owner", 1uL, 7uL), binding),
        )
        val stale = operation("stale", hostGeneration = 2uL, sessionGeneration = 6uL)

        assertEquals(
            "ANDROID_OPERATIONAL_STORAGE_STALE_LEASE_OPERATION",
            assertIs<OperationalLeaseResult.Failed>(ready.lease.quiesce(stale)).failure.code.value,
        )
        assertEquals(
            "ANDROID_OPERATIONAL_STORAGE_STALE_LEASE_OPERATION",
            assertIs<OperationalLeaseResult.Failed>(ready.lease.close(stale)).failure.code.value,
        )
        assertEquals(0, storage.closeCount)

        val current = operation("current", hostGeneration = 3uL, sessionGeneration = 7uL)
        ready.lease.quiesce(current)
        ready.lease.close(current)
        assertEquals(1, storage.closeCount)
    }

    private fun TestScope.port(
        opener: suspend () -> AndroidOperationalSpoolOpenResult,
    ): AndroidOperationalStoragePort = AndroidOperationalStoragePort(
        opener = opener,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    private fun operation(
        label: String,
        hostGeneration: ULong,
        sessionGeneration: ULong,
    ): OperationalRuntimeOperation = OperationalRuntimeOperation(
        hostOperation = RuntimeHostOperation(
            commandId = CommandId("android-storage-$label"),
            correlationId = CorrelationId("android-storage-$label-correlation"),
            generation = hostGeneration,
        ),
        sessionGeneration = sessionGeneration,
    )

    companion object {
        private val binding = ProvisionedDeviceBinding(DeviceId("provisioned-device-1"))
    }
}

private class FakeOperationalSpool(
    private val loadResult: SpoolStoreLoadResult,
    private val loadFailure: Throwable? = null,
    private val closeFailure: Exception? = null,
) : AndroidOperationalReadySpool {
    var loadCount = 0
    var closeCount = 0

    override val metadataStore: SpoolStore = object : SpoolStore {
        override suspend fun load(): SpoolStoreLoadResult {
            loadCount += 1
            loadFailure?.let { throw it }
            return loadResult
        }

        override suspend fun commit(
            expectedRevision: ULong,
            next: SpoolState,
        ): SpoolStoreCommitResult = SpoolStoreCommitResult.Unavailable(
            SpoolStoreFailure("TEST_COMMIT_NOT_AVAILABLE", retryable = false),
        )
    }

    override val payloadStore: DurablePayloadStore = object : DurablePayloadStore {
        override suspend fun writeAndFlush(
            descriptor: ChunkDescriptor,
            payload: OpaqueBytes,
        ): DurablePayloadWriteResult = DurablePayloadWriteResult.Unavailable(
            SpoolStoreFailure("TEST_PAYLOAD_WRITE_NOT_AVAILABLE", retryable = false),
        )

        override suspend fun readAndVerify(chunk: DurableChunk): DurablePayloadReadResult =
            DurablePayloadReadResult.Unavailable(
                SpoolStoreFailure("TEST_PAYLOAD_READ_NOT_AVAILABLE", retryable = false),
            )
    }

    override fun close() {
        closeCount += 1
        closeFailure?.let { throw it }
    }
}

private class CloseOnlyOperationalSpool(
    private val closeFailure: Exception,
) : AndroidOperationalSpool {
    var closeCount = 0

    override fun close() {
        closeCount += 1
        throw closeFailure
    }
}
