package dev.gumi.edge.runtime.operational

import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessGlobalOperationalStorageOwnerTest {
    @Test
    fun `concurrent device claims share one physical open and only process close releases it`() =
        runTest {
            val physical = FakePhysicalStorageLease()
            val delegate = FakeStoragePort { operation, _ ->
                OperationalStorageOpenResult.Ready(
                    operation,
                    physical,
                    OperationalBacklog(4uL, 8_192uL),
                    OperationalBacklogScope.EDGE_HOST,
                )
            }
            val owner = ProcessGlobalOperationalStorageOwner(delegate)
            val operationA = storageOperation("device-a", 1uL, 1uL)
            val operationB = storageOperation("device-b", 1uL, 1uL)

            val openedA = async {
                owner.openAndReconcile(operationA, binding("device-a"))
            }
            val openedB = async {
                owner.openAndReconcile(operationB, binding("device-b"))
            }
            val readyA = assertIs<OperationalStorageOpenResult.Ready>(openedA.await())
            val readyB = assertIs<OperationalStorageOpenResult.Ready>(openedB.await())

            assertEquals(1, delegate.openCount)
            assertEquals(OperationalBacklogScope.EDGE_HOST, readyA.backlogScope)
            assertEquals(OperationalBacklogScope.EDGE_HOST, readyB.backlogScope)
            assertEquals(OperationalBacklog(4uL, 8_192uL), owner.projection.value.backlog)
            assertEquals(
                setOf(DeviceId("device-a"), DeviceId("device-b")),
                owner.projection.value.claimantDeviceIds,
            )

            val cleanupA = storageOperation("cleanup-a", 2uL, 1uL)
            val cleanupB = storageOperation("cleanup-b", 2uL, 1uL)
            assertIs<OperationalLeaseResult.Completed>(readyA.lease.quiesce(cleanupA))
            assertIs<OperationalLeaseResult.Completed>(readyA.lease.close(cleanupA))
            assertIs<OperationalLeaseResult.Completed>(readyB.lease.quiesce(cleanupB))
            assertIs<OperationalLeaseResult.Completed>(readyB.lease.close(cleanupB))
            assertEquals(0, physical.closeCount)
            assertEquals(emptySet(), owner.projection.value.claimantDeviceIds)

            assertIs<ProcessGlobalStorageCloseResult.Closed>(owner.close())
            val replay = assertIs<ProcessGlobalStorageCloseResult.Closed>(owner.close())
            assertTrue(replay.replayed)
            assertEquals(1, physical.quiesceCount)
            assertEquals(1, physical.closeCount)
            assertEquals(ProcessGlobalStorageLifecycle.CLOSED, owner.projection.value.lifecycle)
        }

    @Test
    fun `same device replays an exact open lease and rejects a competing session`() = runTest {
        val physical = FakePhysicalStorageLease()
        val delegate = FakeStoragePort { operation, _ ->
            OperationalStorageOpenResult.Ready(
                operation,
                physical,
                OperationalBacklog.Empty,
                OperationalBacklogScope.EDGE_HOST,
            )
        }
        val owner = ProcessGlobalOperationalStorageOwner(delegate)
        val operation = storageOperation("exact", 1uL, 7uL)
        val first = assertIs<OperationalStorageOpenResult.Ready>(
            owner.openAndReconcile(operation, binding("same-device")),
        )
        val replay = assertIs<OperationalStorageOpenResult.Ready>(
            owner.openAndReconcile(operation, binding("same-device")),
        )
        assertSame(first.lease, replay.lease)
        assertEquals(1, delegate.openCount)

        val competing = assertIs<OperationalStorageOpenResult.Failed>(
            owner.openAndReconcile(
                storageOperation("competing", 2uL, 8uL),
                binding("same-device"),
            ),
        )
        assertEquals("PROCESS_GLOBAL_STORAGE_DEVICE_ALREADY_CLAIMED", competing.failure.code.value)

        val stale = assertIs<OperationalLeaseResult.Failed>(
            first.lease.quiesce(storageOperation("stale", 3uL, 6uL)),
        )
        assertEquals("PROCESS_GLOBAL_STORAGE_STALE_DEVICE_LEASE", stale.failure.code.value)
        assertFalse(owner.projection.value.claimantDeviceIds.isEmpty())

        val cleanup = storageOperation("cleanup", 3uL, 7uL)
        first.lease.quiesce(cleanup)
        first.lease.close(cleanup)
        owner.close()
    }

    @Test
    fun `process close refuses live device claims but closes after logical cleanup`() = runTest {
        val physical = FakePhysicalStorageLease()
        val owner = ProcessGlobalOperationalStorageOwner(
            FakeStoragePort { operation, _ ->
                OperationalStorageOpenResult.Ready(
                    operation,
                    physical,
                    OperationalBacklog.Empty,
                    OperationalBacklogScope.EDGE_HOST,
                )
            },
        )
        val operation = storageOperation("active", 1uL, 1uL)
        val ready = assertIs<OperationalStorageOpenResult.Ready>(
            owner.openAndReconcile(operation, binding("active-device")),
        )

        val refused = assertIs<ProcessGlobalStorageCloseResult.Failed>(owner.close())
        assertEquals("PROCESS_GLOBAL_STORAGE_ACTIVE_DEVICE_CLAIMS", refused.failure.code.value)
        assertEquals(0, physical.closeCount)
        val lateOpen = assertIs<OperationalStorageOpenResult.Failed>(
            owner.openAndReconcile(
                storageOperation("late", 2uL, 2uL),
                binding("late-device"),
            ),
        )
        assertEquals("PROCESS_GLOBAL_STORAGE_CLOSED", lateOpen.failure.code.value)

        val cleanup = storageOperation("active-cleanup", 2uL, 1uL)
        ready.lease.quiesce(cleanup)
        ready.lease.close(cleanup)
        assertIs<ProcessGlobalStorageCloseResult.Closed>(owner.close())
        assertEquals(1, physical.closeCount)
    }

    @Test
    fun `uncertain physical open is settled by logical cleanup and never becomes false clean`() =
        runTest {
            val closeFailure = ExpectedFailure(
                FailureCategory.UNAVAILABLE,
                FailureCode("TEST_PHYSICAL_CLOSE_OUTCOME_UNKNOWN"),
                retryable = false,
            )
            val physical = FakePhysicalStorageLease(closeFailure = closeFailure)
            val openFailure = ExpectedFailure(
                FailureCategory.UNAVAILABLE,
                FailureCode("TEST_PHYSICAL_OPEN_OUTCOME_UNKNOWN"),
                retryable = false,
            )
            val owner = ProcessGlobalOperationalStorageOwner(
                FakeStoragePort { operation, _ ->
                    OperationalStorageOpenResult.OutcomeUnknown(
                        operation,
                        physical,
                        openFailure.copy(correlationId = operation.hostOperation.correlationId),
                    )
                },
            )
            val operation = storageOperation("uncertain", 1uL, 5uL)
            val uncertain = assertIs<OperationalStorageOpenResult.OutcomeUnknown>(
                owner.openAndReconcile(operation, binding("uncertain-device")),
            )
            val cleanup = storageOperation("uncertain-cleanup", 2uL, 5uL)

            assertIs<OperationalLeaseResult.Completed>(uncertain.lease.quiesce(cleanup))
            val unresolved = assertIs<OperationalLeaseResult.OutcomeUnknown>(
                uncertain.lease.close(cleanup),
            )
            assertEquals("TEST_PHYSICAL_CLOSE_OUTCOME_UNKNOWN", unresolved.failure.code.value)
            assertEquals(1, physical.quiesceCount)
            assertEquals(1, physical.closeCount)
            assertEquals(ProcessGlobalStorageLifecycle.OUTCOME_UNKNOWN, owner.projection.value.lifecycle)

            val processClose = assertIs<ProcessGlobalStorageCloseResult.OutcomeUnknown>(owner.close())
            val replay = assertIs<ProcessGlobalStorageCloseResult.OutcomeUnknown>(owner.close())
            assertEquals("TEST_PHYSICAL_CLOSE_OUTCOME_UNKNOWN", processClose.failure.code.value)
            assertTrue(replay.replayed)
            assertEquals(1, physical.closeCount)
        }
}

private class FakeStoragePort(
    private val handler: suspend (
        OperationalRuntimeOperation,
        ProvisionedDeviceBinding,
    ) -> OperationalStorageOpenResult,
) : OperationalStoragePort {
    var openCount = 0

    override suspend fun openAndReconcile(
        operation: OperationalRuntimeOperation,
        binding: ProvisionedDeviceBinding,
    ): OperationalStorageOpenResult {
        openCount += 1
        return handler(operation, binding)
    }
}

private class FakePhysicalStorageLease(
    private val closeFailure: ExpectedFailure? = null,
) : OperationalStorageLease {
    var quiesceCount = 0
    var closeCount = 0

    override suspend fun quiesce(
        operation: OperationalRuntimeOperation,
    ): OperationalLeaseResult {
        quiesceCount += 1
        return OperationalLeaseResult.Completed(operation)
    }

    override suspend fun close(
        operation: OperationalRuntimeOperation,
    ): OperationalLeaseResult {
        closeCount += 1
        return closeFailure?.let {
            OperationalLeaseResult.OutcomeUnknown(
                operation,
                it.copy(correlationId = operation.hostOperation.correlationId),
            )
        } ?: OperationalLeaseResult.Completed(operation)
    }
}

private fun storageOperation(
    label: String,
    hostGeneration: ULong,
    sessionGeneration: ULong,
): OperationalRuntimeOperation = OperationalRuntimeOperation(
    RuntimeHostOperation(
        CommandId("process-storage-$label"),
        CorrelationId("process-storage-correlation-$label"),
        hostGeneration,
    ),
    sessionGeneration,
)

private fun binding(label: String): ProvisionedDeviceBinding =
    ProvisionedDeviceBinding(DeviceId(label))
