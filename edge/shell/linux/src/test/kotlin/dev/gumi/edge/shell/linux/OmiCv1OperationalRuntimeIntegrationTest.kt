package dev.gumi.edge.shell.linux

import dev.gumi.devices.omicv1.OmiCv1DriverProvider
import dev.gumi.devices.omicv1.simulator.OmiCv1Simulator
import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.runtime.host.RuntimeHostCleanupReason
import dev.gumi.edge.runtime.host.RuntimeHostCleanupRequest
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.host.RuntimeHostRehydrationResult
import dev.gumi.edge.runtime.operational.DeviceTransportLease
import dev.gumi.edge.runtime.operational.DeviceTransportLeasePort
import dev.gumi.edge.runtime.operational.DeviceTransportLeaseResult
import dev.gumi.edge.runtime.operational.OperationalBacklog
import dev.gumi.edge.runtime.operational.OperationalCaptureTruth
import dev.gumi.edge.runtime.operational.OperationalDeviceRuntime
import dev.gumi.edge.runtime.operational.OperationalEndpointResolutionPort
import dev.gumi.edge.runtime.operational.OperationalEndpointResolutionResult
import dev.gumi.edge.runtime.operational.OperationalLeaseResult
import dev.gumi.edge.runtime.operational.OperationalRuntimeOperation
import dev.gumi.edge.runtime.operational.OperationalStorageLease
import dev.gumi.edge.runtime.operational.OperationalStorageOpenResult
import dev.gumi.edge.runtime.operational.OperationalStoragePort
import dev.gumi.edge.runtime.operational.ProvisionedDeviceBinding
import dev.gumi.edge.runtime.operational.ProvisionedDeviceBindingPort
import dev.gumi.edge.runtime.operational.ProvisionedDeviceBindingResult
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ble.BleCentral
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Composition-root proof: the shell selects both generic runtime and concrete Omi plugin. */
class OmiCv1OperationalRuntimeIntegrationTest {
    @Test
    fun `operational runtime negotiates Omi power without a device write`() = runBlocking {
        val simulator = OmiCv1Simulator()
        val storageLease = NoOpStorageLease()
        val transportLease = SimulatorTransportLease(simulator)
        val runtime = OperationalDeviceRuntime(
            parentScope = this,
            bindings = ProvisionedDeviceBindingPort { operation ->
                ProvisionedDeviceBindingResult.Bound(
                    operation,
                    ProvisionedDeviceBinding(DeviceId("device-provisioned-1")),
                )
            },
            storage = OperationalStoragePort { operation, _ ->
                OperationalStorageOpenResult.Ready(
                    operation,
                    storageLease,
                    OperationalBacklog.Empty,
                )
            },
            transportLeases = DeviceTransportLeasePort { operation, _ ->
                DeviceTransportLeaseResult.Acquired(operation, transportLease)
            },
            endpoints = OperationalEndpointResolutionPort { operation, _ ->
                OperationalEndpointResolutionResult.Resolved(operation, simulator.endpoint)
            },
            drivers = DeviceDriverRegistry(listOf(OmiCv1DriverProvider())),
        )
        val owner = operation("omi-simulator", 1uL)

        assertIs<RuntimeHostRehydrationResult.Rehydrated>(runtime.rehydrateAndReconcile(owner))
        assertEquals(47u, assertNotNull(runtime.projection.value.power).batteryPercent)
        assertTrue(simulator.operations.isNotEmpty())
        assertTrue(simulator.writes.isEmpty())
        assertEquals(OperationalCaptureTruth.UNVERIFIED, runtime.projection.value.capture)

        runtime.cleanup(
            RuntimeHostCleanupRequest(
                operation("omi-simulator-stop", 2uL),
                RuntimeHostCleanupReason.STOP_REQUESTED,
            ),
        )
        runtime.close()
    }
}

private class NoOpStorageLease : OperationalStorageLease {
    override suspend fun quiesce(
        operation: OperationalRuntimeOperation,
    ): OperationalLeaseResult = OperationalLeaseResult.Completed(operation)

    override suspend fun close(
        operation: OperationalRuntimeOperation,
    ): OperationalLeaseResult = OperationalLeaseResult.Completed(operation)
}

private class SimulatorTransportLease(
    override val bleCentral: BleCentral,
) : DeviceTransportLease {
    override suspend fun release(
        operation: OperationalRuntimeOperation,
    ): OperationalLeaseResult = OperationalLeaseResult.Completed(operation)
}

private fun operation(suffix: String, generation: ULong): RuntimeHostOperation =
    RuntimeHostOperation(
        commandId = CommandId("command-$suffix"),
        correlationId = CorrelationId("correlation-$suffix"),
        generation = generation,
    )
