package dev.gumi.edge.runtime.operational

import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.runtime.host.RuntimeHostCleanupReason
import dev.gumi.edge.runtime.host.RuntimeHostCleanupRequest
import dev.gumi.edge.runtime.host.RuntimeHostCleanupResult
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.host.RuntimeHostRecoveryEvent
import dev.gumi.edge.runtime.host.RuntimeHostRehydrationResult
import dev.gumi.edge.sdk.CapabilityBinding
import dev.gumi.edge.sdk.CapabilitySet
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceDescriptor
import dev.gumi.edge.sdk.DeviceDriverProvider
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.DeviceSession
import dev.gumi.edge.sdk.DeviceSessionEvent
import dev.gumi.edge.sdk.DriverId
import dev.gumi.edge.sdk.DriverMatch
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.NegotiatedDeviceSession
import dev.gumi.edge.sdk.OperationResult
import dev.gumi.edge.sdk.TransportEvent
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.TransportSession
import dev.gumi.edge.sdk.ble.BleCentral
import dev.gumi.edge.sdk.ble.BleConnectionOptions
import dev.gumi.edge.sdk.ble.BleLinkSnapshot
import dev.gumi.edge.sdk.ble.BleTransportSession
import dev.gumi.edge.sdk.capability.power.PowerStatus
import dev.gumi.edge.sdk.capability.power.PowerStatusDescriptor
import dev.gumi.edge.sdk.capability.power.PowerStatusHandle
import dev.gumi.edge.sdk.capability.power.PowerStatusV1
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OperationalDeviceRuntimeTest {
    @Test
    fun `acquisition follows durable-first order and cleanup reverses owned lifetimes once`() =
        runTest {
            val fixture = fixture()
            val owner = operation("ordered-owner", 1uL)

            val started = fixture.runtime.rehydrateAndReconcile(owner)

            assertIs<RuntimeHostRehydrationResult.Rehydrated>(started)
            assertEquals(
                listOf(
                    "binding",
                    "storage-open",
                    "transport-acquire",
                    "endpoint-resolve",
                    "ble-connect",
                    "driver-open",
                    "power-read",
                    "session-collector-start",
                    "power-collector-start",
                ),
                fixture.order,
            )
            with(fixture.runtime.projection.value) {
                assertEquals(OperationalRuntimeLifecycle.READY, lifecycle)
                assertEquals(owner, ownerOperation)
                assertEquals(DeviceId("device-provisioned-1"), deviceId)
                assertEquals(OperationalLinkState.CONNECTED, link)
                assertEquals(OperationalCaptureTruth.UNVERIFIED, capture)
                assertEquals(47u, power?.batteryPercent)
                assertEquals(OperationalStorageState.READY, storage)
                assertEquals(OperationalBacklog(3uL, 4096uL), backlog)
            }

            val stop = cleanup("ordered-stop", 2uL)
            assertIs<RuntimeHostCleanupResult.Cleaned>(fixture.runtime.cleanup(stop))
            val firstCleanupOrder = fixture.order.toList()
            assertBefore(firstCleanupOrder, "session-collector-stop", "session-close")
            assertBefore(firstCleanupOrder, "power-collector-stop", "session-close")
            assertBefore(firstCleanupOrder, "session-close", "transport-release")
            assertBefore(firstCleanupOrder, "transport-release", "storage-quiesce")
            assertBefore(firstCleanupOrder, "storage-quiesce", "storage-close")
            assertEquals(1, fixture.session.closeCount)
            assertEquals(1, fixture.transportLease.releaseCount)
            assertEquals(1, fixture.storageLease.quiesceCount)
            assertEquals(1, fixture.storageLease.closeCount)

            assertIs<RuntimeHostCleanupResult.Cleaned>(fixture.runtime.cleanup(stop))
            assertEquals(firstCleanupOrder, fixture.order)
            with(fixture.runtime.projection.value) {
                assertEquals(OperationalRuntimeLifecycle.STOPPED, lifecycle)
                assertEquals(OperationalLinkState.DISCONNECTED, link)
                assertEquals(OperationalStorageState.CLOSED, storage)
                assertNull(ownerOperation)
                assertNull(sessionGeneration)
            }
            fixture.runtime.close()
        }

    @Test
    fun `unready durable storage prevents all transport and endpoint effects`() = runTest {
        val order = mutableListOf<String>()
        val storageFailure = expectedFailure("TEST_STORAGE_NOT_READY", FailureCategory.CORRUPT)
        val fixture = fixture(
            order = order,
            storageHandler = { operation, _ ->
                order += "storage-open"
                OperationalStorageOpenResult.Failed(operation, storageFailure)
            },
        )
        val owner = operation("storage-failure", 1uL)

        val result = fixture.runtime.rehydrateAndReconcile(owner)

        assertEquals(
            storageFailure.copy(correlationId = owner.correlationId),
            assertIs<RuntimeHostRehydrationResult.Failed>(result).failure,
        )
        assertEquals(listOf("binding", "storage-open"), order)
        assertEquals(0, fixture.central.connectCount)
        assertEquals(0, fixture.transportLease.acquireCount)
        with(fixture.runtime.projection.value) {
            assertEquals(OperationalStorageState.DEGRADED, storage)
            assertEquals(OperationalLinkState.UNKNOWN, link)
            assertEquals(OperationalCaptureTruth.UNVERIFIED, capture)
        }
        assertIs<RuntimeHostCleanupResult.Cleaned>(
            fixture.runtime.cleanup(cleanup("storage-failure", 2uL)),
        )
        fixture.runtime.close()
    }

    @Test
    fun `storage open uncertainty retains its lease for cleanup and blocks every later startup`() =
        runTest {
            val order = mutableListOf<String>()
            val openFailure = expectedFailure(
                "TEST_STORAGE_OPEN_OUTCOME_UNKNOWN",
                FailureCategory.UNAVAILABLE,
            )
            val closeFailure = expectedFailure(
                "TEST_STORAGE_CLOSE_OUTCOME_UNKNOWN",
                FailureCategory.UNAVAILABLE,
            )
            val uncertainLease = OutcomeUnknownStorageLease(order, closeFailure)
            var storageOpenCount = 0
            val fixture = fixture(
                order = order,
                parentScope = backgroundScope,
                storageHandler = { operation, _ ->
                    order += "storage-open"
                    storageOpenCount += 1
                    OperationalStorageOpenResult.OutcomeUnknown(
                        operation,
                        uncertainLease,
                        openFailure,
                    )
                },
            )
            val owner = operation("uncertain-storage-owner", 1uL)

            val started = fixture.runtime.rehydrateAndReconcile(owner)

            assertEquals(
                openFailure.copy(correlationId = owner.correlationId),
                assertIs<RuntimeHostRehydrationResult.OutcomeUnknown>(started).failure,
            )
            assertEquals(0, fixture.transportLease.acquireCount)
            assertEquals(0, fixture.central.connectCount)
            assertEquals(OperationalStorageState.DEGRADED, fixture.runtime.projection.value.storage)

            val stop = cleanup("uncertain-storage-stop", 2uL)
            val cleaned = assertIs<RuntimeHostCleanupResult.OutcomeUnknown>(
                fixture.runtime.cleanup(stop),
            )
            assertEquals("TEST_STORAGE_CLOSE_OUTCOME_UNKNOWN", cleaned.failure.code.value)
            assertEquals(1, uncertainLease.quiesceCount)
            assertEquals(1, uncertainLease.closeCount)
            assertBefore(order, "storage-quiesce-unknown", "storage-close-unknown")
            assertEquals(OperationalStorageState.DEGRADED, fixture.runtime.projection.value.storage)

            val effectsAfterCleanup = order.toList()
            val blocked = fixture.runtime.rehydrateAndReconcile(
                operation("blocked-after-storage-unknown", 3uL),
            )
            assertEquals(
                "TEST_STORAGE_CLOSE_OUTCOME_UNKNOWN",
                assertIs<RuntimeHostRehydrationResult.Failed>(blocked).failure.code.value,
            )
            assertEquals(1, storageOpenCount)
            assertEquals(effectsAfterCleanup, order)
        }

    @Test
    fun `a later uncertain cleanup boundary cannot be downgraded by an earlier definitive failure`() =
        runTest {
            val order = mutableListOf<String>()
            val closeFailure = expectedFailure(
                "TEST_STORAGE_CLOSE_LATER_OUTCOME_UNKNOWN",
                FailureCategory.UNAVAILABLE,
            )
            val storageLease = OutcomeUnknownStorageLease(order, closeFailure)
            val fixture = fixture(
                order = order,
                parentScope = backgroundScope,
                storageHandler = { operation, _ ->
                    order += "storage-open"
                    OperationalStorageOpenResult.Ready(
                        operation,
                        storageLease,
                        OperationalBacklog.Empty,
                    )
                },
            )
            fixture.transportLease.releaseFailure = expectedFailure(
                "TEST_TRANSPORT_RELEASE_DEFINITIVE_FAILURE",
                FailureCategory.UNAVAILABLE,
            )
            assertIs<RuntimeHostRehydrationResult.Rehydrated>(
                fixture.runtime.rehydrateAndReconcile(operation("mixed-cleanup-owner", 1uL)),
            )

            val result = assertIs<RuntimeHostCleanupResult.OutcomeUnknown>(
                fixture.runtime.cleanup(cleanup("mixed-cleanup-stop", 2uL)),
            )

            assertEquals("TEST_STORAGE_CLOSE_LATER_OUTCOME_UNKNOWN", result.failure.code.value)
            assertEquals(
                "TEST_STORAGE_CLOSE_LATER_OUTCOME_UNKNOWN",
                fixture.runtime.projection.value.lastFailure?.code?.value,
            )
            assertEquals(
                "TEST_STORAGE_CLOSE_LATER_OUTCOME_UNKNOWN",
                assertIs<RuntimeHostRehydrationResult.Failed>(
                    fixture.runtime.rehydrateAndReconcile(operation("mixed-cleanup-blocked", 3uL)),
                ).failure.code.value,
            )
        }

    @Test
    fun `duplicate owner rehydration does not repeat physical acquisition`() = runTest {
        val fixture = fixture()
        val owner = operation("duplicate-owner", 1uL)

        assertIs<RuntimeHostRehydrationResult.Rehydrated>(
            fixture.runtime.rehydrateAndReconcile(owner),
        )
        val effects = fixture.order.toList()
        assertIs<RuntimeHostRehydrationResult.Rehydrated>(
            fixture.runtime.rehydrateAndReconcile(owner),
        )

        assertEquals(effects, fixture.order)
        assertEquals(1, fixture.central.connectCount)
        assertEquals(1, fixture.provider.openCount)
        fixture.runtime.cleanup(cleanup("duplicate-owner", 2uL))
        fixture.runtime.close()
    }

    @Test
    fun `late cleanup generation cannot release a newer session`() = runTest {
        val fixture = fixture()
        fixture.runtime.rehydrateAndReconcile(operation("first-owner", 1uL))
        fixture.runtime.cleanup(cleanup("first-stop", 2uL))
        fixture.runtime.rehydrateAndReconcile(operation("second-owner", 3uL))
        val releasesBefore = fixture.transportLease.releaseCount
        val projectionBefore = fixture.runtime.projection.value

        val stale = fixture.runtime.cleanup(cleanup("late-old-stop", 2uL))

        assertEquals(
            "OPERATIONAL_STALE_CLEANUP_REQUEST",
            assertIs<RuntimeHostCleanupResult.Failed>(stale).failure.code.value,
        )
        assertEquals(releasesBefore, fixture.transportLease.releaseCount)
        with(fixture.runtime.projection.value) {
            assertEquals(projectionBefore.ownerOperation, ownerOperation)
            assertEquals(OperationalRuntimeLifecycle.READY, lifecycle)
            assertEquals(projectionBefore.power, power)
            assertEquals(projectionBefore.staleEventCount + 1uL, staleEventCount)
        }
        fixture.runtime.cleanup(cleanup("second-stop", 4uL))
        fixture.runtime.close()
    }

    @Test
    fun `session termination publishes operation-fenced disconnect truth`() = runTest {
        val fixture = fixture()
        val owner = operation("disconnect-owner", 1uL)
        fixture.runtime.rehydrateAndReconcile(owner)
        val event = async { fixture.runtime.events.first() }
        runCurrent()

        fixture.session.eventSource.emit(DeviceSessionEvent.Closed)
        runCurrent()

        val disconnected = assertIs<RuntimeHostRecoveryEvent.TransportDisconnected>(event.await())
        assertEquals(owner, disconnected.operation)
        with(fixture.runtime.projection.value) {
            assertEquals(OperationalRuntimeLifecycle.DEGRADED, lifecycle)
            assertEquals(OperationalLinkState.DISCONNECTED, link)
            assertEquals("OPERATIONAL_DEVICE_DISCONNECTED", lastFailure?.code?.value)
            assertEquals(OperationalCaptureTruth.UNVERIFIED, capture)
        }
        fixture.runtime.cleanup(cleanup("disconnect-stop", 2uL))
        fixture.runtime.close()
    }

    @Test
    fun `power refresh is owner fenced versioned and idempotent by exact command request`() = runTest {
        val fixture = fixture()
        val owner = operation("power-refresh-owner", 1uL)
        fixture.runtime.rehydrateAndReconcile(owner)
        val initial = fixture.runtime.projection.value
        assertEquals(1uL, initial.powerObservationRevision)
        assertEquals(1, fixture.power.readCount)
        fixture.power.nextRead = PowerStatus(47u, null, null)
        val request = powerRefreshRequest(
            suffix = "exact",
            expectedOwner = OperationalRuntimeOperation(
                owner,
                assertNotNull(initial.sessionGeneration),
            ),
        )

        val completed = assertIs<OperationalPowerRefreshResult.Completed>(
            fixture.runtime.refreshPower(request),
        )
        assertFalse(completed.replayed)
        assertEquals(2, fixture.power.readCount)
        val refreshed = fixture.runtime.projection.value
        assertEquals(initial.power, refreshed.power)
        assertEquals(2uL, refreshed.powerObservationRevision)
        assertEquals(initial.sequence + 1L, refreshed.sequence)

        val replay = assertIs<OperationalPowerRefreshResult.Completed>(
            fixture.runtime.refreshPower(request),
        )
        assertTrue(replay.replayed)
        assertEquals(2, fixture.power.readCount)
        assertEquals(refreshed, fixture.runtime.projection.value)

        val conflict = assertIs<OperationalPowerRefreshResult.Failed>(
            fixture.runtime.refreshPower(
                request.copy(correlationId = CorrelationId("correlation-power-conflict")),
            ),
        )
        assertEquals("OPERATIONAL_POWER_REFRESH_ID_CONFLICT", conflict.failure.code.value)
        assertFalse(conflict.replayed)
        assertEquals(2, fixture.power.readCount)

        val staleBefore = fixture.runtime.projection.value.staleEventCount
        val stale = assertIs<OperationalPowerRefreshResult.Failed>(
            fixture.runtime.refreshPower(
                powerRefreshRequest(
                    suffix = "stale-owner",
                    expectedOwner = request.expectedOwner.copy(
                        sessionGeneration = request.expectedOwner.sessionGeneration + 1uL,
                    ),
                ),
            ),
        )
        assertEquals("OPERATIONAL_STALE_POWER_REFRESH_REQUEST", stale.failure.code.value)
        assertEquals(staleBefore + 1uL, fixture.runtime.projection.value.staleEventCount)
        assertEquals(2, fixture.power.readCount)
        fixture.runtime.cleanup(cleanup("power-refresh-stop", 2uL))
        fixture.runtime.close()
    }

    @Test
    fun `thrown power read is durable outcome unknown and does not publish a new observation`() =
        runTest {
            val fixture = fixture()
            val owner = operation("power-failure-owner", 1uL)
            fixture.runtime.rehydrateAndReconcile(owner)
            val before = fixture.runtime.projection.value
            fixture.power.readFailure = IllegalStateException("sensitive transport detail")
            val request = powerRefreshRequest(
                suffix = "read-failure",
                expectedOwner = OperationalRuntimeOperation(
                    owner,
                    assertNotNull(before.sessionGeneration),
                ),
            )

            val result = assertIs<OperationalPowerRefreshResult.OutcomeUnknown>(
                fixture.runtime.refreshPower(request),
            )

            assertEquals("OPERATIONAL_POWER_REFRESH_FAILED", result.failure.code.value)
            assertEquals(request.correlationId, result.failure.correlationId)
            assertTrue(result.failure.redactedEvidence.isEmpty())
            assertEquals(before, fixture.runtime.projection.value)
            assertEquals(2, fixture.power.readCount)
            val replay = assertIs<OperationalPowerRefreshResult.OutcomeUnknown>(
                fixture.runtime.refreshPower(request),
            )
            assertTrue(replay.replayed)
            assertEquals(2, fixture.power.readCount)
            fixture.runtime.cleanup(cleanup("power-failure-stop", 2uL))
            fixture.runtime.close()
        }

    @Test
    fun `collector completion racing cleanup is fenced to stale count only`() = runTest {
        val stalePower = PowerStatus(99u, true, 50L)
        val releaseLateUpdate = CompletableDeferred<Unit>()
        val fixture = fixture(
            powerUpdates = flow {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        releaseLateUpdate.await()
                        emit(stalePower)
                    }
                }
            },
        )
        fixture.runtime.rehydrateAndReconcile(operation("stale-power-owner", 1uL))
        val before = fixture.runtime.projection.value

        val cleanup = async { fixture.runtime.cleanup(cleanup("stale-power-stop", 2uL)) }
        runCurrent()
        releaseLateUpdate.complete(Unit)
        assertIs<RuntimeHostCleanupResult.Cleaned>(cleanup.await())

        with(fixture.runtime.projection.value) {
            assertEquals(before.power, power)
            assertEquals(before.staleEventCount + 1uL, staleEventCount)
            assertEquals(OperationalRuntimeLifecycle.STOPPED, lifecycle)
            assertNull(lastFailure)
        }
        fixture.runtime.close()
    }

    @Test
    fun `wrong endpoint completion generation is rejected and retained leases still clean`() =
        runTest {
            val fixture = fixture(
                endpointHandler = { operation, _ ->
                    OperationalEndpointResolutionResult.Resolved(
                        operation.copy(sessionGeneration = operation.sessionGeneration + 1uL),
                        testEndpoint,
                    )
                },
            )
            val owner = operation("stale-endpoint", 1uL)

            val result = fixture.runtime.rehydrateAndReconcile(owner)

            assertEquals(
                "OPERATIONAL_STALE_ENDPOINT_COMPLETION",
                assertIs<RuntimeHostRehydrationResult.Failed>(result).failure.code.value,
            )
            assertEquals(1uL, fixture.runtime.projection.value.staleEventCount)
            assertEquals(0, fixture.central.connectCount)
            assertIs<RuntimeHostCleanupResult.Cleaned>(
                fixture.runtime.cleanup(cleanup("stale-endpoint-stop", 2uL)),
            )
            assertEquals(1, fixture.transportLease.releaseCount)
            assertEquals(1, fixture.storageLease.closeCount)
            fixture.runtime.close()
        }

    @Test
    fun `thrown cleanup effects cannot skip later reverse cleanup boundaries`() = runTest {
        val fixture = fixture()
        fixture.runtime.rehydrateAndReconcile(operation("cleanup-throws-owner", 1uL))
        fixture.transportLease.throwOnRelease = true
        fixture.storageLease.throwOnQuiesce = true

        val result = fixture.runtime.cleanup(cleanup("cleanup-throws-stop", 2uL))

        assertEquals(
            "OPERATIONAL_TRANSPORT_RELEASE_FAILED",
            assertIs<RuntimeHostCleanupResult.OutcomeUnknown>(result).failure.code.value,
        )
        assertEquals(1, fixture.session.closeCount)
        assertEquals(1, fixture.transportLease.releaseCount)
        assertEquals(1, fixture.storageLease.quiesceCount)
        assertEquals(1, fixture.storageLease.closeCount)
        assertBefore(fixture.order, "transport-release", "storage-quiesce")
        assertBefore(fixture.order, "storage-quiesce", "storage-close")
        with(fixture.runtime.projection.value) {
            assertEquals(OperationalRuntimeLifecycle.DEGRADED, lifecycle)
            assertEquals(OperationalStorageState.CLOSED, storage)
        }
        val cleanupEffects = fixture.order.toList()
        val retry = fixture.runtime.cleanup(cleanup("cleanup-throws-retry", 3uL))
        assertEquals(
            "OPERATIONAL_TRANSPORT_RELEASE_FAILED",
            assertIs<RuntimeHostCleanupResult.OutcomeUnknown>(retry).failure.code.value,
        )
        assertEquals(cleanupEffects, fixture.order)
        assertEquals(
            "OPERATIONAL_TRANSPORT_RELEASE_FAILED",
            assertIs<RuntimeHostRehydrationResult.Failed>(
                fixture.runtime.rehydrateAndReconcile(operation("blocked-after-cleanup", 4uL)),
            ).failure.code.value,
        )
        assertEquals(cleanupEffects, fixture.order)
        fixture.runtime.close()
    }

}

private data class OperationalFixture(
    val order: MutableList<String>,
    val runtime: OperationalDeviceRuntime,
    val storageLease: FakeStorageLease,
    val transportLease: FakeTransportLease,
    val central: FakeCentral,
    val provider: FakeDriverProvider,
    val session: FakeNegotiatedSession,
    val power: FakePowerHandle,
)

private fun TestScope.fixture(
    order: MutableList<String> = mutableListOf(),
    parentScope: CoroutineScope = this,
    storageHandler: suspend (
        OperationalRuntimeOperation,
        ProvisionedDeviceBinding,
    ) -> OperationalStorageOpenResult = DEFAULT_STORAGE_HANDLER,
    endpointHandler: suspend (
        OperationalRuntimeOperation,
        ProvisionedDeviceBinding,
    ) -> OperationalEndpointResolutionResult = { operation, _ ->
        OperationalEndpointResolutionResult.Resolved(operation, testEndpoint)
    },
    powerUpdates: Flow<PowerStatus>? = null,
): OperationalFixture {
    val storageLease = FakeStorageLease(order)
    val transport = FakeBleTransportSession(testEndpoint, order)
    val central = FakeCentral(order, transport)
    val transportLease = FakeTransportLease(order, central)
    val power = FakePowerHandle(
        order = order,
        initial = PowerStatus(47u, null, null),
        updatesOverride = powerUpdates,
    )
    val session = FakeNegotiatedSession(testEndpoint, order, transport, power)
    val provider = FakeDriverProvider(order, session)
    val effectiveStorageHandler = if (
        storageHandler === DEFAULT_STORAGE_HANDLER
    ) {
        { operation: OperationalRuntimeOperation, _: ProvisionedDeviceBinding ->
            order += "storage-open"
            OperationalStorageOpenResult.Ready(
                operation,
                storageLease,
                OperationalBacklog(3uL, 4096uL),
            )
        }
    } else {
        storageHandler
    }
    val runtime = OperationalDeviceRuntime(
        parentScope = parentScope,
        bindings = bindingPort(order),
        storage = OperationalStoragePort(effectiveStorageHandler),
        transportLeases = DeviceTransportLeasePort { operation, _ ->
            order += "transport-acquire"
            transportLease.acquireCount += 1
            DeviceTransportLeaseResult.Acquired(operation, transportLease)
        },
        endpoints = OperationalEndpointResolutionPort { operation, binding ->
            order += "endpoint-resolve"
            endpointHandler(operation, binding)
        },
        drivers = DeviceDriverRegistry(listOf(provider)),
    )
    return OperationalFixture(
        order,
        runtime,
        storageLease,
        transportLease,
        central,
        provider,
        session,
        power,
    )
}

private val DEFAULT_STORAGE_HANDLER: suspend (
    OperationalRuntimeOperation,
    ProvisionedDeviceBinding,
) -> OperationalStorageOpenResult = { _, _ -> error("sentinel") }

private fun bindingPort(order: MutableList<String>): ProvisionedDeviceBindingPort =
    ProvisionedDeviceBindingPort { operation ->
        order += "binding"
        ProvisionedDeviceBindingResult.Bound(
            operation,
            ProvisionedDeviceBinding(DeviceId("device-provisioned-1")),
        )
    }

private class FakeStorageLease(
    private val order: MutableList<String>,
) : OperationalStorageLease {
    var quiesceCount = 0
    var closeCount = 0
    var throwOnQuiesce = false

    override suspend fun quiesce(operation: OperationalRuntimeOperation): OperationalLeaseResult {
        order += "storage-quiesce"
        quiesceCount += 1
        if (throwOnQuiesce) error("synthetic quiesce failure")
        return OperationalLeaseResult.Completed(operation)
    }

    override suspend fun close(operation: OperationalRuntimeOperation): OperationalLeaseResult {
        order += "storage-close"
        closeCount += 1
        return OperationalLeaseResult.Completed(operation)
    }
}

private class OutcomeUnknownStorageLease(
    private val order: MutableList<String>,
    private val closeFailure: ExpectedFailure,
) : OperationalStorageLease {
    var quiesceCount = 0
    var closeCount = 0

    override suspend fun quiesce(operation: OperationalRuntimeOperation): OperationalLeaseResult {
        order += "storage-quiesce-unknown"
        quiesceCount += 1
        return OperationalLeaseResult.Completed(operation)
    }

    override suspend fun close(operation: OperationalRuntimeOperation): OperationalLeaseResult {
        order += "storage-close-unknown"
        closeCount += 1
        return OperationalLeaseResult.OutcomeUnknown(operation, closeFailure)
    }
}

private class FakeTransportLease(
    private val order: MutableList<String>,
    override val bleCentral: BleCentral,
) : DeviceTransportLease {
    var acquireCount = 0
    var releaseCount = 0
    var throwOnRelease = false
    var releaseFailure: ExpectedFailure? = null

    override suspend fun release(operation: OperationalRuntimeOperation): OperationalLeaseResult {
        order += "transport-release"
        releaseCount += 1
        if (throwOnRelease) error("synthetic transport release failure")
        releaseFailure?.let { return OperationalLeaseResult.Failed(operation, it) }
        return OperationalLeaseResult.Completed(operation)
    }
}

private class FakeCentral(
    private val order: MutableList<String>,
    private val transport: BleTransportSession,
) : BleCentral {
    var connectCount = 0

    override suspend fun connect(
        endpoint: EndpointCandidate,
        options: BleConnectionOptions,
    ): BleTransportSession {
        order += "ble-connect"
        connectCount += 1
        assertEquals(testEndpoint, endpoint)
        return transport
    }
}

private class FakeDriverProvider(
    private val order: MutableList<String>,
    private val session: DeviceSession,
) : DeviceDriverProvider {
    override val id = DriverId("test.operational-driver")
    var openCount = 0

    override fun match(candidate: EndpointCandidate): DriverMatch =
        DriverMatch(MatchConfidence.EXACT, setOf("test endpoint"))

    override fun describe(candidate: EndpointCandidate): DeviceDescriptor = session.descriptor

    override suspend fun open(
        candidate: EndpointCandidate,
        transport: TransportSession,
    ): DeviceSession {
        order += "driver-open"
        openCount += 1
        return session
    }
}

private class FakeNegotiatedSession(
    override val endpoint: EndpointCandidate,
    private val order: MutableList<String>,
    private val transport: TransportSession,
    power: PowerStatusHandle,
) : NegotiatedDeviceSession {
    val eventSource = MutableSharedFlow<DeviceSessionEvent>(extraBufferCapacity = 8)
    private val powerDescriptor = power.descriptor
    override val deviceId: DeviceId? = null
    override val descriptor = DeviceDescriptor(
        driverId = DriverId("test.operational-driver"),
        manufacturer = "Gumi test",
        model = "portable",
        protocolVersion = "1",
        capabilities = listOf(powerDescriptor),
    )
    override val capabilities: CapabilitySet = assertIs<OperationResult.Success<CapabilitySet>>(
        CapabilitySet.negotiate(
            advertised = listOf(powerDescriptor),
            bindings = listOf(CapabilityBinding(PowerStatusV1, powerDescriptor, power)),
        ),
    ).value
    override val events: Flow<DeviceSessionEvent> = eventSource
        .onStart { order += "session-collector-start" }
        .onCompletion { order += "session-collector-stop" }
    var closeCount = 0

    override suspend fun close() {
        order += "session-close"
        closeCount += 1
        transport.close()
    }
}

private class FakePowerHandle(
    private val order: MutableList<String>,
    initial: PowerStatus,
    updatesOverride: Flow<PowerStatus>?,
) : PowerStatusHandle {
    override val descriptor = PowerStatusDescriptor(true, true)
    private val updatesSource = updatesOverride ?: MutableSharedFlow(extraBufferCapacity = 8)
    override val updates: Flow<PowerStatus> = updatesSource
        .onStart { order += "power-collector-start" }
        .onCompletion { order += "power-collector-stop" }
    var nextRead: PowerStatus = initial
    var readFailure: Throwable? = null
    var readCount: Int = 0

    override suspend fun read(): PowerStatus {
        order += "power-read"
        readCount += 1
        readFailure?.let { throw it }
        return nextRead
    }
}

private class FakeBleTransportSession(
    override val endpoint: EndpointCandidate,
    private val order: MutableList<String>,
) : BleTransportSession {
    override val link: BleLinkSnapshot
        get() = error("The operational coordinator does not inspect BLE link parameters")
    override val bleEvents = emptyFlow<dev.gumi.edge.sdk.ble.BleSessionEvent>()
    override val events = emptyFlow<TransportEvent>()

    override suspend fun discoverServices() = error("Fake driver does not inspect GATT")
    override suspend fun read(target: dev.gumi.edge.sdk.ble.BleCharacteristicTarget) =
        error("Fake driver does not read GATT")
    override suspend fun write(
        target: dev.gumi.edge.sdk.ble.BleCharacteristicTarget,
        value: dev.gumi.edge.sdk.OpaqueBytes,
        kind: dev.gumi.edge.sdk.ble.BleWriteKind,
    ) = error("Operational runtime must not write")

    override suspend fun subscribe(target: dev.gumi.edge.sdk.ble.BleCharacteristicTarget) =
        error("Fake driver does not subscribe")

    override suspend fun close() {
        order += "ble-close"
    }
}

private fun operation(suffix: String, generation: ULong): RuntimeHostOperation = RuntimeHostOperation(
    commandId = CommandId("command-$suffix"),
    correlationId = CorrelationId("correlation-$suffix"),
    generation = generation,
)

private fun powerRefreshRequest(
    suffix: String,
    expectedOwner: OperationalRuntimeOperation,
): OperationalPowerRefreshRequest = OperationalPowerRefreshRequest(
    commandId = CommandId("power-command-$suffix"),
    correlationId = CorrelationId("power-correlation-$suffix"),
    expectedOwner = expectedOwner,
)

private fun cleanup(suffix: String, generation: ULong): RuntimeHostCleanupRequest =
    RuntimeHostCleanupRequest(
        operation = operation(suffix, generation),
        reason = RuntimeHostCleanupReason.STOP_REQUESTED,
    )

private fun expectedFailure(
    code: String,
    category: FailureCategory = FailureCategory.INTERNAL,
): ExpectedFailure = ExpectedFailure(category, FailureCode(code), retryable = false)

private fun assertBefore(order: List<String>, earlier: String, later: String) {
    assertTrue(order.indexOf(earlier) >= 0, "$earlier was not observed: $order")
    assertTrue(order.indexOf(later) >= 0, "$later was not observed: $order")
    assertTrue(order.indexOf(earlier) < order.indexOf(later), "$earlier must precede $later: $order")
}

private val testEndpoint = EndpointCandidate(
    transport = TransportKind.BLE,
    ephemeralId = "ble:test-operational-endpoint",
    advertisedName = "test",
)
