package dev.gumi.edge.shell.android.runtime

import dev.gumi.edge.runtime.host.RuntimeHostAssociationState
import dev.gumi.edge.runtime.host.RuntimeHostCleanupRequest
import dev.gumi.edge.runtime.host.RuntimeHostCleanupResult
import dev.gumi.edge.runtime.host.RuntimeHostExecutionState
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.host.RuntimeHostPermissionState
import dev.gumi.edge.runtime.host.RuntimeHostPrerequisitePort
import dev.gumi.edge.runtime.host.RuntimeHostPrerequisiteResult
import dev.gumi.edge.runtime.host.RuntimeHostPrerequisites
import dev.gumi.edge.runtime.host.RuntimeHostPresenceState
import dev.gumi.edge.runtime.host.RuntimeHostRecoveryEvent
import dev.gumi.edge.runtime.host.RuntimeHostRehydrationResult
import dev.gumi.edge.runtime.host.RuntimeHostRequest
import dev.gumi.edge.runtime.host.RuntimeHostStartOrigin
import dev.gumi.edge.runtime.host.RuntimeHostStopOrigin
import dev.gumi.edge.runtime.host.RuntimeHostTransportState
import dev.gumi.edge.runtime.operational.OperationalBacklogScope
import dev.gumi.edge.runtime.operational.OperationalLinkState
import dev.gumi.edge.runtime.operational.OperationalPowerRefreshRequest
import dev.gumi.edge.runtime.operational.OperationalPowerRefreshResult
import dev.gumi.edge.runtime.operational.OperationalRuntimeLifecycle
import dev.gumi.edge.runtime.operational.OperationalRuntimeNode
import dev.gumi.edge.runtime.operational.OperationalRuntimeProjection
import dev.gumi.edge.runtime.operational.OperationalStorageOpenResult
import dev.gumi.edge.runtime.operational.OperationalStoragePort
import dev.gumi.edge.runtime.operational.OperationalStorageState
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.capability.power.PowerStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidOperationalProcessGraphTest {
    @Test
    fun `two device runtimes share one process host foreground bridge and storage owner`() = runTest {
        val order = mutableListOf<String>()
        val suppliedStorage = mutableListOf<OperationalStoragePort>()
        val physicalStorage = OperationalStoragePort { operation, _ ->
            OperationalStorageOpenResult.Failed(
                operation,
                androidRuntimeFailure(
                    category = dev.gumi.edge.sdk.FailureCategory.UNAVAILABLE,
                    code = "TEST_PHYSICAL_STORAGE_UNUSED",
                    retryable = false,
                    correlationId = operation.hostOperation.correlationId,
                ),
            )
        }
        val foreground = AndroidRuntimeForegroundBridge()
        val graph = createAndroidOperationalProcessGraph(
            parentScope = this,
            prerequisites = allowedPrerequisites,
            foreground = foreground,
            physicalStorage = physicalStorage,
            runtimeFactories = listOf(
                factory(DeviceId("device-b"), suppliedStorage, order),
                factory(DeviceId("device-a"), suppliedStorage, order),
            ),
        )
        val endpoint = FakeEndpoint()

        graph.owner.submit(startRequest("fleet"), 1, endpoint)
        advanceUntilIdle()

        assertEquals(RuntimeHostExecutionState.FOREGROUND, graph.owner.hostProjection.value.execution)
        assertEquals(listOf("start:device-a", "start:device-b"), order)
        assertEquals(2, suppliedStorage.size)
        assertSame(graph.storage, suppliedStorage[0])
        assertSame(graph.storage, suppliedStorage[1])
        assertEquals(2, endpoint.entries.size)
        assertEquals(0, endpoint.leaves.size)

        graph.owner.submit(stopRequest("fleet"), 2, endpoint)
        advanceUntilIdle()

        assertEquals(RuntimeHostExecutionState.STOPPED, graph.owner.hostProjection.value.execution)
        assertEquals(
            listOf(
                "start:device-a",
                "start:device-b",
                "cleanup:device-b",
                "cleanup:device-a",
            ),
            order,
        )
        assertEquals(1, endpoint.leaves.size)
        assertTrue(endpoint.settlements.any { it.first == 2 && it.second })

        graph.owner.shutdown()
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

    private fun factory(
        deviceId: DeviceId,
        suppliedStorage: MutableList<OperationalStoragePort>,
        order: MutableList<String>,
    ) = AndroidOperationalRuntimeFactory(deviceId) { storage ->
        suppliedStorage += storage
        GraphRuntimeNode(deviceId, order)
    }
}

private class GraphRuntimeNode(
    private val deviceId: DeviceId,
    private val order: MutableList<String>,
) : OperationalRuntimeNode {
    private val mutableProjection = MutableStateFlow(OperationalRuntimeProjection())
    override val projection: StateFlow<OperationalRuntimeProjection> = mutableProjection
    override val events: Flow<RuntimeHostRecoveryEvent> = emptyFlow()

    override suspend fun rehydrateAndReconcile(
        operation: RuntimeHostOperation,
    ): RuntimeHostRehydrationResult {
        order += "start:${deviceId.value}"
        mutableProjection.value = OperationalRuntimeProjection(
            lifecycle = OperationalRuntimeLifecycle.READY,
            ownerOperation = operation,
            sessionGeneration = 1uL,
            deviceId = deviceId,
            link = OperationalLinkState.CONNECTED,
            storage = OperationalStorageState.READY,
            backlogScope = OperationalBacklogScope.EDGE_HOST,
        )
        return RuntimeHostRehydrationResult.Rehydrated(operation, RuntimeHostTransportState.READY)
    }

    override suspend fun cleanup(request: RuntimeHostCleanupRequest): RuntimeHostCleanupResult {
        order += "cleanup:${deviceId.value}"
        mutableProjection.value = OperationalRuntimeProjection(
            lifecycle = OperationalRuntimeLifecycle.STOPPED,
            link = OperationalLinkState.DISCONNECTED,
        )
        return RuntimeHostCleanupResult.Cleaned(request.operation)
    }

    override suspend fun refreshPower(
        request: OperationalPowerRefreshRequest,
    ): OperationalPowerRefreshResult = OperationalPowerRefreshResult.Completed(
        request,
        PowerStatus(50u, null, null),
    )

    override suspend fun close() {
        order += "close:${deviceId.value}"
        mutableProjection.value = OperationalRuntimeProjection(
            lifecycle = OperationalRuntimeLifecycle.CLOSED,
            link = OperationalLinkState.DISCONNECTED,
        )
    }
}

private val allowedPrerequisites = object : RuntimeHostPrerequisitePort {
    override suspend fun inspect(operation: RuntimeHostOperation): RuntimeHostPrerequisiteResult =
        RuntimeHostPrerequisiteResult.Observed(
            operation,
            RuntimeHostPrerequisites(
                RuntimeHostAssociationState.ASSOCIATED,
                RuntimeHostPresenceState.PRESENT,
                RuntimeHostPermissionState.GRANTED,
            ),
        )
}

private fun startRequest(label: String): RuntimeHostRequest.Start = RuntimeHostRequest.Start(
    CommandId("android-graph-start-$label"),
    CorrelationId("android-graph-start-correlation-$label"),
    RuntimeHostStartOrigin.EXPLICIT_USER,
)

private fun stopRequest(label: String): RuntimeHostRequest.Stop = RuntimeHostRequest.Stop(
    CommandId("android-graph-stop-$label"),
    CorrelationId("android-graph-stop-correlation-$label"),
    RuntimeHostStopOrigin.EXPLICIT_USER,
)
