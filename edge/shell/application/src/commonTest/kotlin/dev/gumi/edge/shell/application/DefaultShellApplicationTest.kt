package dev.gumi.edge.shell.application

import dev.gumi.edge.runtime.capture.CaptureMode
import dev.gumi.edge.runtime.capture.CaptureProof
import dev.gumi.edge.runtime.capture.CaptureProofSource
import dev.gumi.edge.runtime.capture.CaptureState
import dev.gumi.edge.runtime.capture.CaptureTruth
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultShellApplicationTest {
    @Test
    fun `versioned publications reject stale and conflicting runtime truth`() = runTest {
        val clock = MutableShellClock(1_000)
        val application = application(clock)
        val original = update(deviceId = DEVICE_A, owner = 4u, sequence = 2)

        assertIs<ShellUpdateResult.Applied>(application.publish(original))
        assertIs<ShellUpdateResult.Duplicate>(application.publish(original))
        assertIs<ShellUpdateResult.Stale>(
            application.publish(update(DEVICE_A, owner = 3u, sequence = 99)),
        )
        assertIs<ShellUpdateResult.Stale>(
            application.publish(update(DEVICE_A, owner = 4u, sequence = 1)),
        )
        assertIs<ShellUpdateResult.Conflict>(
            application.publish(
                original.copy(snapshot = original.snapshot.copy(displayName = "Changed identity label")),
            ),
        )
        assertEquals("Pendant A", application.projection.value.devices.single().displayName)

        assertIs<ShellUpdateResult.Applied>(
            application.publish(update(DEVICE_A, owner = 5u, sequence = 0, name = "Replacement owner")),
        )
        assertEquals("Replacement owner", application.projection.value.devices.single().displayName)
    }

    @Test
    fun `slow command effect never holds the fleet projection lock`() = runTest {
        val entered = CompletableDeferred<RoutedShellCommand>()
        val release = CompletableDeferred<Unit>()
        val application = DefaultShellApplication(
            commandPort = ShellCommandPort { routed ->
                entered.complete(routed)
                release.await()
                ShellCommandResult.Accepted(routed.command.id)
            },
            clock = MutableShellClock(1_000),
        )
        application.publish(update(DEVICE_A, owner = 7u, sequence = 0))

        val pending = async { application.submit(command(DEVICE_A, "slow")) }
        assertEquals(7u, entered.await().expectedOwnerGeneration)

        assertIs<ShellUpdateResult.Applied>(
            application.publish(update(DEVICE_B, owner = 1u, sequence = 0, name = "Pendant B")),
        )
        assertEquals(listOf(DEVICE_A, DEVICE_B), application.projection.value.devices.map { it.deviceId })

        release.complete(Unit)
        assertIs<ShellCommandResult.Accepted>(pending.await())
    }

    @Test
    fun `owner replacement after acceptance is explicitly outcome unknown`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val application = DefaultShellApplication(
            commandPort = ShellCommandPort { routed ->
                entered.complete(Unit)
                release.await()
                ShellCommandResult.Accepted(routed.command.id)
            },
            clock = MutableShellClock(1_000),
        )
        application.publish(update(DEVICE_A, owner = 1u, sequence = 0))
        val request = command(DEVICE_A, "owner-race")

        val pending = async { application.submit(request) }
        entered.await()
        application.publish(update(DEVICE_A, owner = 2u, sequence = 0))
        release.complete(Unit)

        val result = assertIs<ShellCommandResult.Terminal>(pending.await())
        assertEquals(ShellTerminalOutcome.OUTCOME_UNKNOWN, result.outcome)
        assertEquals("SHELL_OWNER_CHANGED_AFTER_ACCEPTANCE", result.failure?.code?.value)
        assertEquals(request.correlationId, result.failure?.correlationId)
    }

    @Test
    fun `unknown device is rejected before the command port`() = runTest {
        var portCalls = 0
        val application = DefaultShellApplication(
            commandPort = ShellCommandPort {
                portCalls += 1
                ShellCommandResult.Accepted(it.command.id)
            },
            clock = MutableShellClock(1_000),
        )

        val result = assertIs<ShellCommandResult.Terminal>(
            application.submit(command(DEVICE_A, "unmanaged")),
        )

        assertEquals(ShellTerminalOutcome.REJECTED, result.outcome)
        assertEquals("SHELL_DEVICE_NOT_MANAGED", result.failure?.code?.value)
        assertEquals(0, portCalls)
    }

    @Test
    fun `mismatched command receipt cannot be laundered as caller evidence`() = runTest {
        val application = DefaultShellApplication(
            commandPort = ShellCommandPort {
                ShellCommandResult.Accepted(CommandId("different-command"))
            },
            clock = MutableShellClock(1_000),
        )
        application.publish(update(DEVICE_A, owner = 1u, sequence = 0))

        val result = assertIs<ShellCommandResult.Terminal>(
            application.submit(command(DEVICE_A, "receipt-mismatch")),
        )

        assertEquals(ShellTerminalOutcome.OUTCOME_UNKNOWN, result.outcome)
        assertEquals("SHELL_COMMAND_RESULT_ID_MISMATCH", result.failure?.code?.value)
    }

    @Test
    fun `fresh idle expires and a wall clock rollback cannot restore verified off`() = runTest {
        val clock = MutableShellClock(1_000)
        val application = application(clock)
        application.publish(update(DEVICE_A, owner = 1u, sequence = 0))
        assertEquals(FleetCaptureState.ALL_VERIFIED_OFF, application.projection.value.capture.state)

        clock.now = 31_001
        application.refresh()
        assertEquals(FleetCaptureState.MAY_BE_ACTIVE, application.projection.value.capture.state)

        clock.now = 500
        application.refresh()
        assertEquals(FleetCaptureState.MAY_BE_ACTIVE, application.projection.value.capture.state)
    }

    @Test
    fun `only the current owner can explicitly deprovision a managed device`() = runTest {
        val application = application(MutableShellClock(1_000))
        application.publish(update(DEVICE_A, owner = 8u, sequence = 0))

        assertIs<ShellForgetResult.StaleOwner>(application.forget(DEVICE_A, 7u))
        assertIs<ShellForgetResult.OwnerMismatch>(application.forget(DEVICE_A, 9u))
        assertEquals(1, application.projection.value.devices.size)

        assertIs<ShellForgetResult.Removed>(application.forget(DEVICE_A, 8u))
        assertEquals(FleetCaptureState.NO_MANAGED_DEVICES, application.projection.value.capture.state)
        assertIs<ShellForgetResult.NotManaged>(application.forget(DEVICE_A, 8u))
    }

    @Test
    fun `unexpected adapter exception is outcome unknown without sensitive evidence`() = runTest {
        val application = DefaultShellApplication(
            commandPort = ShellCommandPort { error("secret transport detail") },
            clock = MutableShellClock(1_000),
        )
        application.publish(update(DEVICE_A, owner = 1u, sequence = 0))

        val result = assertIs<ShellCommandResult.Terminal>(
            application.submit(command(DEVICE_A, "adapter-throw")),
        )

        assertEquals(ShellTerminalOutcome.OUTCOME_UNKNOWN, result.outcome)
        assertEquals("SHELL_COMMAND_PORT_OUTCOME_UNKNOWN", result.failure?.code?.value)
        assertTrue(result.failure?.redactedEvidence?.isEmpty() == true)
    }

    private fun application(clock: MutableShellClock) = DefaultShellApplication(
        commandPort = ShellCommandPort { ShellCommandResult.Accepted(it.command.id) },
        clock = clock,
    )

    private fun command(deviceId: DeviceId, suffix: String) = ShellCommand(
        id = CommandId("command-$suffix"),
        correlationId = CorrelationId("correlation-$suffix"),
        targetDeviceId = deviceId,
        issuedAtEpochMillis = 1_000,
        intent = ShellIntent.StartRecording,
    )

    private fun update(
        deviceId: DeviceId,
        owner: ULong,
        sequence: Long,
        name: String = "Pendant A",
    ) = DeviceShellUpdate(
        ownerGeneration = owner,
        sequence = sequence,
        snapshot = snapshot(deviceId, name),
    )

    private fun snapshot(deviceId: DeviceId, name: String): DeviceShellSnapshot {
        val proof = CaptureProof(
            connectionSessionGeneration = 1u,
            causalGeneration = 1u,
            source = CaptureProofSource.DEVICE_OBSERVATION,
        )
        fun <T> observation(value: T) = AxisObservation(
            value = value,
            authority = ProjectionAuthority.DEVICE_REPORTED,
            observedAtEpochMillis = 1_000,
            freshness = ObservationFreshness.FRESH,
            connectionSessionGeneration = 1u,
        )
        return DeviceShellSnapshot(
            deviceId = deviceId,
            displayName = name,
            capture = observation(CaptureState(truth = CaptureTruth.Acquired(CaptureMode.IDLE, proof))),
            link = observation(LinkState.READY),
            maintenance = observation(MaintenanceState.NORMAL),
            update = observation(UpdateStatus(UpdateStage.IDLE)),
            sync = observation(SyncStatus(SyncState.CURRENT, BacklogStatus())),
            power = observation(PowerStatus(PowerState.OPERATIONAL)),
            storage = observation(StorageStatus(StorageState.HEALTHY)),
            fault = observation(FaultStatus(FaultSeverity.NONE)),
        )
    }

    private class MutableShellClock(var now: Long) : ShellClock {
        override fun nowEpochMillis(): Long = now
    }

    private companion object {
        val DEVICE_A = DeviceId("device-a")
        val DEVICE_B = DeviceId("device-b")
    }
}
