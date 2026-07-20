package dev.gumi.edge.shell.linux

import dev.gumi.devices.omicv1.OmiCv1DriverProvider
import dev.gumi.devices.omicv1.simulator.OmiCv1Simulator
import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.runtime.capture.CaptureState
import dev.gumi.edge.shell.application.AxisObservation
import dev.gumi.edge.shell.application.BacklogStatus
import dev.gumi.edge.shell.application.DefaultShellApplication
import dev.gumi.edge.shell.application.DeviceShellSnapshot
import dev.gumi.edge.shell.application.DeviceShellUpdate
import dev.gumi.edge.shell.application.FaultSeverity
import dev.gumi.edge.shell.application.FaultStatus
import dev.gumi.edge.shell.application.FleetCaptureState
import dev.gumi.edge.shell.application.LinkState
import dev.gumi.edge.shell.application.MaintenanceState
import dev.gumi.edge.shell.application.ObservationFreshness
import dev.gumi.edge.shell.application.PowerState
import dev.gumi.edge.shell.application.PowerStatus
import dev.gumi.edge.shell.application.ProjectionAuthority
import dev.gumi.edge.shell.application.RoutedShellCommand
import dev.gumi.edge.shell.application.ShellClock
import dev.gumi.edge.shell.application.ShellCommand
import dev.gumi.edge.shell.application.ShellCommandPort
import dev.gumi.edge.shell.application.ShellCommandResult
import dev.gumi.edge.shell.application.ShellIntent
import dev.gumi.edge.shell.application.ShellTerminalOutcome
import dev.gumi.edge.shell.application.StorageState
import dev.gumi.edge.shell.application.StorageStatus
import dev.gumi.edge.shell.application.SyncState
import dev.gumi.edge.shell.application.SyncStatus
import dev.gumi.edge.shell.application.UpdateStage
import dev.gumi.edge.shell.application.UpdateStatus
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.NegotiatedDeviceSession
import kotlinx.coroutines.runBlocking

data class PortableControlPlaneWitness(
    val host: String,
    val managedDeviceCount: Int,
    val fleetCaptureState: FleetCaptureState,
    val captureLabel: String,
    val routedOwnerGeneration: ULong,
    val commandOutcome: ShellTerminalOutcome,
) {
    fun render(): String = buildString {
        appendLine("Gumi portable control plane: $host")
        appendLine("managedDevices=$managedDeviceCount")
        appendLine("fleetCapture=$fleetCaptureState")
        appendLine("capture=$captureLabel")
        appendLine("routedOwnerGeneration=$routedOwnerGeneration")
        append("commandOutcome=$commandOutcome")
    }
}

/**
 * JVM/Linux composition witness for the same portable shell used by Android.
 *
 * Negotiating a BLE-shaped session does not establish microphone-off truth, so the projection remains
 * deliberately uncertain until a device-owned capture observation arrives.
 */
fun buildPortableControlPlaneWitness(): PortableControlPlaneWitness = runBlocking {
    val simulator = OmiCv1Simulator()
    val registry = DeviceDriverRegistry(listOf(OmiCv1DriverProvider()))
    val selection = registry.select(simulator.endpoint)
    val session = selection.provider.open(
        simulator.endpoint,
        simulator.connect(simulator.endpoint),
    ) as NegotiatedDeviceSession
    try {
        var routed: RoutedShellCommand? = null
        val application = DefaultShellApplication(
            commandPort = ShellCommandPort { command ->
                routed = command
                ShellCommandResult.Terminal(
                    commandId = command.command.id,
                    outcome = ShellTerminalOutcome.NO_OP,
                )
            },
            clock = ShellClock { WITNESS_TIME },
        )
        application.publish(
            DeviceShellUpdate(
                ownerGeneration = OWNER_GENERATION,
                sequence = 0,
                snapshot = initialSnapshot(session.descriptor.model),
            ),
        )
        val result = application.submit(
            ShellCommand(
                id = CommandId("linux-repeat-status"),
                correlationId = CorrelationId("linux-repeat-status-correlation"),
                targetDeviceId = DEVICE_ID,
                issuedAtEpochMillis = WITNESS_TIME,
                intent = ShellIntent.RepeatStatus,
            ),
        ) as ShellCommandResult.Terminal
        val projection = application.projection.value
        PortableControlPlaneWitness(
            host = "linux-jvm",
            managedDeviceCount = projection.devices.size,
            fleetCaptureState = projection.capture.state,
            captureLabel = projection.devices.single().capture.value.label,
            routedOwnerGeneration = requireNotNull(routed).expectedOwnerGeneration,
            commandOutcome = result.outcome,
        )
    } finally {
        session.close()
    }
}

private fun initialSnapshot(model: String): DeviceShellSnapshot {
    fun <T> observation(
        value: T,
        authority: ProjectionAuthority = ProjectionAuthority.EDGE_INFERRED,
    ) = AxisObservation(
        value = value,
        authority = authority,
        observedAtEpochMillis = WITNESS_TIME,
        freshness = ObservationFreshness.FRESH,
        connectionSessionGeneration = CONNECTION_GENERATION,
    )
    return DeviceShellSnapshot(
        deviceId = DEVICE_ID,
        displayName = model,
        capture = observation(CaptureState()),
        link = observation(LinkState.READY, ProjectionAuthority.DEVICE_REPORTED),
        maintenance = observation(MaintenanceState.NORMAL),
        update = observation(UpdateStatus(UpdateStage.IDLE)),
        sync = observation(SyncStatus(SyncState.CURRENT, BacklogStatus())),
        power = observation(PowerStatus(PowerState.OPERATIONAL)),
        storage = observation(StorageStatus(StorageState.UNKNOWN)),
        fault = observation(FaultStatus(FaultSeverity.NONE)),
    )
}

private val DEVICE_ID = DeviceId("linux-simulated-provisioned-omi")
private const val OWNER_GENERATION = 1uL
private const val CONNECTION_GENERATION = 1uL
private const val WITNESS_TIME = 1_000L
