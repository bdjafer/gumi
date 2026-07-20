package dev.gumi.edge.runtime.operational

import dev.gumi.edge.runtime.host.RuntimeHostCleanupReason
import dev.gumi.edge.runtime.host.RuntimeHostCleanupRequest
import dev.gumi.edge.runtime.host.RuntimeHostCleanupResult
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.host.RuntimeHostRecoveryEvent
import dev.gumi.edge.runtime.host.RuntimeHostRehydrationResult
import dev.gumi.edge.runtime.host.RuntimeHostTransportState
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.capability.power.PowerStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OperationalRuntimeRegistryTest {
    @Test
    fun `one host starts devices by stable identity and cleans and closes in reverse order`() =
        runTest {
            val order = mutableListOf<String>()
            val deviceA = DeviceId("device-a")
            val deviceB = DeviceId("device-b")
            val runtimeA = FakeRuntimeNode(deviceA, order)
            val runtimeB = FakeRuntimeNode(deviceB, order)
            val registry = OperationalRuntimeRegistry(
                parentScope = backgroundScope,
                registrations = listOf(
                    OperationalRuntimeRegistration(deviceB, runtimeB),
                    OperationalRuntimeRegistration(deviceA, runtimeA),
                ),
            )
            val owner = hostOperation("owner", 1uL)

            assertIs<RuntimeHostRehydrationResult.Rehydrated>(
                registry.rehydrateAndReconcile(owner),
            )
            assertEquals(listOf("start:device-a", "start:device-b"), order)
            assertEquals(setOf(deviceA, deviceB), registry.deviceIds)
            assertEquals(runtimeA.projection, registry.projection(deviceA))

            assertIs<RuntimeHostRehydrationResult.Rehydrated>(
                registry.rehydrateAndReconcile(owner),
            )
            assertEquals(listOf("start:device-a", "start:device-b"), order)

            val stop = cleanup("stop", 2uL)
            assertIs<RuntimeHostCleanupResult.Cleaned>(registry.cleanup(stop))
            assertIs<RuntimeHostCleanupResult.Cleaned>(registry.cleanup(stop))
            assertEquals(
                listOf(
                    "start:device-a",
                    "start:device-b",
                    "cleanup:device-b",
                    "cleanup:device-a",
                ),
                order,
            )

            registry.close()
            assertEquals(
                listOf(
                    "start:device-a",
                    "start:device-b",
                    "cleanup:device-b",
                    "cleanup:device-a",
                    "close:device-b",
                    "close:device-a",
                ),
                order,
            )
        }

    @Test
    fun `identity mismatch fails closed before another device starts and cleanup covers attempted node`() =
        runTest {
            val order = mutableListOf<String>()
            val registered = DeviceId("device-a")
            val mismatched = FakeRuntimeNode(
                projectedDeviceId = DeviceId("foreign-device"),
                order = order,
                label = registered.value,
            )
            val neverStarted = FakeRuntimeNode(DeviceId("device-b"), order)
            val registry = OperationalRuntimeRegistry(
                backgroundScope,
                listOf(
                    OperationalRuntimeRegistration(registered, mismatched),
                    OperationalRuntimeRegistration(DeviceId("device-b"), neverStarted),
                ),
            )

            val failed = assertIs<RuntimeHostRehydrationResult.Failed>(
                registry.rehydrateAndReconcile(hostOperation("identity", 1uL)),
            )
            assertEquals("OPERATIONAL_REGISTRY_DEVICE_IDENTITY_MISMATCH", failed.failure.code.value)
            assertEquals(listOf("start:device-a"), order)

            assertIs<RuntimeHostCleanupResult.Cleaned>(registry.cleanup(cleanup("identity", 2uL)))
            assertEquals(listOf("start:device-a", "cleanup:device-a"), order)
            registry.close()
        }

    @Test
    fun `device scoped command ports route concurrently without a fleet wide command lock`() = runTest {
        val enteredA = CompletableDeferred<Unit>()
        val enteredB = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtimeA = FakeRuntimeNode(DeviceId("device-a"), mutableListOf()).apply {
            powerHandler = { request ->
                enteredA.complete(Unit)
                release.await()
                OperationalPowerRefreshResult.Completed(request, PowerStatus(31u, false, null))
            }
        }
        val runtimeB = FakeRuntimeNode(DeviceId("device-b"), mutableListOf()).apply {
            powerHandler = { request ->
                enteredB.complete(Unit)
                release.await()
                OperationalPowerRefreshResult.Completed(request, PowerStatus(72u, true, null))
            }
        }
        val registry = OperationalRuntimeRegistry(
            backgroundScope,
            listOf(
                OperationalRuntimeRegistration(DeviceId("device-a"), runtimeA),
                OperationalRuntimeRegistration(DeviceId("device-b"), runtimeB),
            ),
        )
        val owner = hostOperation("commands", 1uL)
        registry.rehydrateAndReconcile(owner)

        val resultA = async {
            registry.powerRefreshPort(DeviceId("device-a")).refreshPower(
                powerRequest("a", owner),
            )
        }
        val resultB = async {
            registry.powerRefreshPort(DeviceId("device-b")).refreshPower(
                powerRequest("b", owner),
            )
        }
        runCurrent()
        assertTrue(enteredA.isCompleted)
        assertTrue(enteredB.isCompleted)
        assertFalse(resultA.isCompleted)
        assertFalse(resultB.isCompleted)
        release.complete(Unit)

        assertEquals(31u, assertIs<OperationalPowerRefreshResult.Completed>(resultA.await()).status.batteryPercent)
        assertEquals(72u, assertIs<OperationalPowerRefreshResult.Completed>(resultB.await()).status.batteryPercent)
        assertEquals(1, runtimeA.powerRequestCount)
        assertEquals(1, runtimeB.powerRequestCount)

        val missingRequest = powerRequest("missing", owner)
        val missing = assertIs<OperationalPowerRefreshResult.Failed>(
            registry.routePowerRefresh(DeviceId("device-missing"), missingRequest),
        )
        assertEquals("OPERATIONAL_REGISTRY_DEVICE_NOT_REGISTERED", missing.failure.code.value)

        registry.cleanup(cleanup("commands", 2uL))
        registry.close()
    }

    @Test
    fun `current runtime events are forwarded with their exact host operation`() = runTest {
        val device = DeviceId("event-device")
        val runtime = FakeRuntimeNode(device, mutableListOf())
        val registry = OperationalRuntimeRegistry(
            backgroundScope,
            listOf(OperationalRuntimeRegistration(device, runtime)),
        )
        val owner = hostOperation("event-owner", 1uL)
        registry.rehydrateAndReconcile(owner)
        runCurrent()
        val received = async { registry.events.first() }
        runCurrent()
        val failure = expectedFailure(owner, "TEST_DEVICE_DISCONNECTED")

        runtime.eventSource.emit(RuntimeHostRecoveryEvent.TransportDisconnected(owner, failure))
        runCurrent()

        val event = assertIs<RuntimeHostRecoveryEvent.TransportDisconnected>(received.await())
        assertEquals(owner, event.operation)
        assertEquals("TEST_DEVICE_DISCONNECTED", event.failure.code.value)
        registry.cleanup(cleanup("event-owner", 2uL))
        registry.close()
    }

    @Test
    fun `stale cleanup cannot release a newer fleet owner`() = runTest {
        val order = mutableListOf<String>()
        val device = DeviceId("fenced-device")
        val runtime = FakeRuntimeNode(device, order)
        val registry = OperationalRuntimeRegistry(
            backgroundScope,
            listOf(OperationalRuntimeRegistration(device, runtime)),
        )
        registry.rehydrateAndReconcile(hostOperation("newer-owner", 4uL))

        val stale = assertIs<RuntimeHostCleanupResult.Failed>(
            registry.cleanup(cleanup("older-stop", 3uL)),
        )

        assertEquals("OPERATIONAL_REGISTRY_STALE_CLEANUP_REQUEST", stale.failure.code.value)
        assertEquals(listOf("start:fenced-device"), order)
        registry.cleanup(cleanup("current-stop", 5uL))
        registry.close()
    }

    @Test
    fun `one uncertain device cleanup keeps the process registry owned and blocks close`() = runTest {
        val order = mutableListOf<String>()
        val deviceA = DeviceId("cleanup-device-a")
        val deviceB = DeviceId("cleanup-device-b")
        val runtimeA = FakeRuntimeNode(deviceA, order)
        val runtimeB = FakeRuntimeNode(deviceB, order).apply {
            cleanupHandler = { request ->
                RuntimeHostCleanupResult.OutcomeUnknown(
                    request.operation,
                    expectedFailure(request.operation, "TEST_DEVICE_CLEANUP_OUTCOME_UNKNOWN"),
                )
            }
        }
        val registry = OperationalRuntimeRegistry(
            backgroundScope,
            listOf(
                OperationalRuntimeRegistration(deviceA, runtimeA),
                OperationalRuntimeRegistration(deviceB, runtimeB),
            ),
        )
        registry.rehydrateAndReconcile(hostOperation("cleanup-owner", 1uL))

        val result = assertIs<RuntimeHostCleanupResult.OutcomeUnknown>(
            registry.cleanup(cleanup("cleanup-stop", 2uL)),
        )

        assertEquals("TEST_DEVICE_CLEANUP_OUTCOME_UNKNOWN", result.failure.code.value)
        assertEquals(
            listOf(
                "start:cleanup-device-a",
                "start:cleanup-device-b",
                "cleanup:cleanup-device-b",
                "cleanup:cleanup-device-a",
            ),
            order,
        )
        assertFailsWith<IllegalStateException> { registry.close() }
    }
}

private class FakeRuntimeNode(
    projectedDeviceId: DeviceId,
    private val order: MutableList<String>,
    private val label: String = projectedDeviceId.value,
) : OperationalRuntimeNode {
    private val mutableProjection = MutableStateFlow(OperationalRuntimeProjection())
    override val projection: StateFlow<OperationalRuntimeProjection> = mutableProjection
    val eventSource = MutableSharedFlow<RuntimeHostRecoveryEvent>(extraBufferCapacity = 8)
    override val events: Flow<RuntimeHostRecoveryEvent> = eventSource
    var powerRequestCount = 0
    var powerHandler: suspend (OperationalPowerRefreshRequest) -> OperationalPowerRefreshResult = {
        OperationalPowerRefreshResult.Completed(it, PowerStatus(50u, null, null))
    }
    var cleanupHandler: suspend (RuntimeHostCleanupRequest) -> RuntimeHostCleanupResult = {
        RuntimeHostCleanupResult.Cleaned(it.operation)
    }
    private val identity = projectedDeviceId

    override suspend fun rehydrateAndReconcile(
        operation: RuntimeHostOperation,
    ): RuntimeHostRehydrationResult {
        order += "start:$label"
        mutableProjection.value = OperationalRuntimeProjection(
            lifecycle = OperationalRuntimeLifecycle.READY,
            ownerOperation = operation,
            sessionGeneration = 1uL,
            deviceId = identity,
            link = OperationalLinkState.CONNECTED,
            storage = OperationalStorageState.READY,
            backlogScope = OperationalBacklogScope.DEVICE,
        )
        return RuntimeHostRehydrationResult.Rehydrated(operation, RuntimeHostTransportState.READY)
    }

    override suspend fun cleanup(request: RuntimeHostCleanupRequest): RuntimeHostCleanupResult {
        order += "cleanup:$label"
        mutableProjection.value = OperationalRuntimeProjection(
            lifecycle = OperationalRuntimeLifecycle.STOPPED,
            link = OperationalLinkState.DISCONNECTED,
        )
        return cleanupHandler(request)
    }

    override suspend fun refreshPower(
        request: OperationalPowerRefreshRequest,
    ): OperationalPowerRefreshResult {
        powerRequestCount += 1
        return powerHandler(request)
    }

    override suspend fun close() {
        order += "close:$label"
        mutableProjection.value = OperationalRuntimeProjection(
            lifecycle = OperationalRuntimeLifecycle.CLOSED,
            link = OperationalLinkState.DISCONNECTED,
        )
    }
}

private fun hostOperation(label: String, generation: ULong): RuntimeHostOperation =
    RuntimeHostOperation(
        CommandId("registry-command-$label"),
        CorrelationId("registry-correlation-$label"),
        generation,
    )

private fun cleanup(label: String, generation: ULong): RuntimeHostCleanupRequest =
    RuntimeHostCleanupRequest(
        hostOperation(label, generation),
        RuntimeHostCleanupReason.STOP_REQUESTED,
    )

private fun powerRequest(
    label: String,
    owner: RuntimeHostOperation,
): OperationalPowerRefreshRequest = OperationalPowerRefreshRequest(
    CommandId("registry-power-$label"),
    CorrelationId("registry-power-correlation-$label"),
    OperationalRuntimeOperation(owner, 1uL),
)

private fun expectedFailure(operation: RuntimeHostOperation, code: String): ExpectedFailure =
    ExpectedFailure(
        FailureCategory.DISCONNECTED,
        FailureCode(code),
        retryable = true,
        correlationId = operation.correlationId,
    )
