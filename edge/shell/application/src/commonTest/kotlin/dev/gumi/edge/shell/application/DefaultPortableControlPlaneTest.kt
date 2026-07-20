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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPortableControlPlaneTest {
    @Test
    fun `selection is portable explicit and falls back without mutating runtime truth`() = runTest {
        val shell = FakeShellApplication(fleet(off("a"), off("b")))
        val controlPlane = DefaultPortableControlPlane(backgroundScope, shell)
        runCurrent()

        assertEquals(DeviceId("a"), controlPlane.presentation.value.selectedDeviceId)
        assertIs<ShellSelectionResult.NotManaged>(controlPlane.select(DeviceId("missing")))
        assertEquals(DeviceId("a"), controlPlane.presentation.value.selectedDeviceId)

        val selected = assertIs<ShellSelectionResult.Applied>(controlPlane.select(DeviceId("b")))
        assertEquals(DeviceId("b"), selected.preferredDeviceId)
        assertEquals(DeviceId("b"), selected.resolvedDeviceId)
        runCurrent()
        assertEquals(DeviceId("b"), controlPlane.presentation.value.selectedDeviceId)

        val automatic = assertIs<ShellSelectionResult.Applied>(controlPlane.select(null))
        assertNull(automatic.preferredDeviceId)
        assertEquals(DeviceId("a"), automatic.resolvedDeviceId)
    }

    @Test
    fun `blocked second capture never reaches command port and returns correlated evidence`() = runTest {
        val shell = FakeShellApplication(fleet(recording("a"), off("b")))
        val controlPlane = DefaultPortableControlPlane(backgroundScope, shell)
        val command = command("b", ShellIntent.StartRecording, "second-start")

        val result = assertIs<ShellCommandResult.Terminal>(controlPlane.submit(command))

        assertEquals(ShellTerminalOutcome.REJECTED, result.outcome)
        assertEquals("SHELL_PRODUCT_ACTION_BLOCKED", result.failure?.code?.value)
        assertEquals("FLEET_CAPTURE_NOT_QUIESCENT", result.failure?.redactedEvidence?.get("reason"))
        assertEquals("START_RECORDING", result.failure?.redactedEvidence?.get("action"))
        assertEquals(command.correlationId, result.failure?.correlationId)
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `enabled command is passed through unchanged and acquired truth remains projection-owned`() =
        runTest {
            val shell = FakeShellApplication(fleet(off("a")))
            val controlPlane = DefaultPortableControlPlane(backgroundScope, shell)
            val command = command("a", ShellIntent.StartRecording, "first-start")

            assertIs<ShellCommandResult.Accepted>(controlPlane.submit(command))

            assertEquals(listOf(command), shell.commands)
            assertEquals(
                CapturePresentationKind.VERIFIED_OFF,
                controlPlane.presentation.value.devices.single().control.device.capture.value.kind,
            )
        }

    @Test
    fun `output port changes reproject fault and admission without device-specific types`() = runTest {
        val shell = FakeShellApplication(fleet(off("a")))
        val output = MutableOutputPort()
        val controlPlane = DefaultPortableControlPlane(backgroundScope, shell, output)
        runCurrent()
        assertEquals(
            ShellPhysicalOutputKind.UNVERIFIED,
            controlPlane.presentation.value.devices.single().physicalOutput.kind,
        )

        output.mutable.value = mapOf(
            DeviceId("a") to DeviceOutputTruth(
                DeviceId("a"),
                observation(
                    DeviceVisibleOutput(
                        DeviceVisibleOutputSemantic.UNKNOWN,
                        DeviceVisibleOutputHealth.DRIVE_FAILED,
                    ),
                ),
            ),
        )
        runCurrent()

        val device = controlPlane.presentation.value.devices.single()
        assertEquals(ShellPhysicalOutputKind.FAILED, device.physicalOutput.kind)
        assertEquals(ShellFaultKind.PRIVACY_CRITICAL, device.fault.kind)
        val result = assertIs<ShellCommandResult.Terminal>(
            controlPlane.submit(command("a", ShellIntent.StartRecording, "failed-output")),
        )
        assertEquals(
            "PHYSICAL_OUTPUT_NOT_TRUSTWORTHY",
            result.failure?.redactedEvidence?.get("reason"),
        )
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `safety stop is dispatched during capture collision`() = runTest {
        val shell = FakeShellApplication(fleet(recording("a"), recording("b")))
        val controlPlane = DefaultPortableControlPlane(backgroundScope, shell)
        val stop = command("b", ShellIntent.StopRecording, "collision-stop")

        assertIs<ShellCommandResult.Accepted>(controlPlane.submit(stop))
        assertEquals(listOf(stop), shell.commands)
    }

    @Test
    fun `unmanaged command is rejected before underlying shell`() = runTest {
        val shell = FakeShellApplication(fleet(off("a")))
        val controlPlane = DefaultPortableControlPlane(backgroundScope, shell)

        val result = assertIs<ShellCommandResult.Terminal>(
            controlPlane.submit(command("missing", ShellIntent.RepeatStatus, "missing")),
        )

        assertEquals("DEVICE_NOT_MANAGED", result.failure?.redactedEvidence?.get("reason"))
        assertNull(result.failure?.redactedEvidence?.get("action"))
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `concurrent starts reserve one target while exact replay and safety stop still route`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = command("a", ShellIntent.StartRecording, "reserved")
        val shell = FakeShellApplication(fleet(off("a"), off("b"))) { command ->
            if (command == first && !entered.isCompleted) {
                entered.complete(Unit)
                release.await()
            }
            ShellCommandResult.Accepted(command.id)
        }
        val controlPlane = DefaultPortableControlPlane(backgroundScope, shell)

        val inFlight = async { controlPlane.submit(first) }
        entered.await()
        runCurrent()
        assertEquals(ShellCaptureWorkflowState.STARTING, controlPlane.presentation.value.workflow.state)

        val second = assertIs<ShellCommandResult.Terminal>(
            controlPlane.submit(command("b", ShellIntent.StartRecording, "second")),
        )
        assertEquals("CAPTURE_ADMISSION_RESERVED", second.failure?.redactedEvidence?.get("reason"))

        val stop = command("a", ShellIntent.StopRecording, "safety-stop")
        assertIs<ShellCommandResult.Accepted>(controlPlane.submit(stop))
        release.complete(Unit)
        assertIs<ShellCommandResult.Accepted>(inFlight.await())

        assertIs<ShellCommandResult.Accepted>(controlPlane.submit(first))
        assertEquals(listOf(first, stop, first), shell.commands)
    }

    @Test
    fun `refusal releases admission while failure and outcome unknown keep it`() = runTest {
        val refusedStart = command("a", ShellIntent.StartRecording, "refused")
        val refusedShell = FakeShellApplication(fleet(off("a"), off("b"))) { command ->
            if (command == refusedStart) {
                ShellCommandResult.Terminal(
                    command.id,
                    ShellTerminalOutcome.REFUSED,
                    dev.gumi.edge.sdk.ExpectedFailure(
                        dev.gumi.edge.sdk.FailureCategory.REJECTED_POLICY,
                        dev.gumi.edge.sdk.FailureCode("TEST_CAPTURE_REFUSED"),
                        retryable = false,
                    ),
                )
            } else {
                ShellCommandResult.Accepted(command.id)
            }
        }
        val refusedPlane = DefaultPortableControlPlane(backgroundScope, refusedShell)
        assertIs<ShellCommandResult.Terminal>(refusedPlane.submit(refusedStart))
        assertIs<ShellCommandResult.Accepted>(
            refusedPlane.submit(command("b", ShellIntent.StartRecording, "after-refusal")),
        )

        val unknownStart = command("a", ShellIntent.StartRecording, "unknown")
        val unknownShell = FakeShellApplication(fleet(off("a"), off("b"))) { command ->
            if (command == unknownStart) {
                ShellCommandResult.Terminal(
                    command.id,
                    ShellTerminalOutcome.OUTCOME_UNKNOWN,
                    dev.gumi.edge.sdk.ExpectedFailure(
                        dev.gumi.edge.sdk.FailureCategory.INTERNAL,
                        dev.gumi.edge.sdk.FailureCode("TEST_CAPTURE_UNKNOWN"),
                        retryable = false,
                    ),
                )
            } else {
                ShellCommandResult.Accepted(command.id)
            }
        }
        val unknownPlane = DefaultPortableControlPlane(backgroundScope, unknownShell)
        assertIs<ShellCommandResult.Terminal>(unknownPlane.submit(unknownStart))
        val blocked = assertIs<ShellCommandResult.Terminal>(
            unknownPlane.submit(command("b", ShellIntent.StartRecording, "after-unknown")),
        )
        assertEquals("CAPTURE_ADMISSION_RESERVED", blocked.failure?.redactedEvidence?.get("reason"))

        val failedStart = command("a", ShellIntent.StartRecording, "failed")
        val failedShell = FakeShellApplication(fleet(off("a"), off("b"))) { command ->
            if (command == failedStart) {
                ShellCommandResult.Terminal(
                    command.id,
                    ShellTerminalOutcome.FAILED,
                    dev.gumi.edge.sdk.ExpectedFailure(
                        dev.gumi.edge.sdk.FailureCategory.INTERNAL,
                        dev.gumi.edge.sdk.FailureCode("TEST_CAPTURE_FAILED"),
                        retryable = false,
                    ),
                )
            } else {
                ShellCommandResult.Accepted(command.id)
            }
        }
        val failedPlane = DefaultPortableControlPlane(backgroundScope, failedShell)
        assertIs<ShellCommandResult.Terminal>(failedPlane.submit(failedStart))
        val failedBlocked = assertIs<ShellCommandResult.Terminal>(
            failedPlane.submit(command("b", ShellIntent.StartRecording, "after-failed")),
        )
        assertEquals(
            "CAPTURE_ADMISSION_RESERVED",
            failedBlocked.failure?.redactedEvidence?.get("reason"),
        )
    }

    @Test
    fun `runtime evidence carries reservation through active capture and clears it after verified stop`() =
        runTest {
            val shell = FakeShellApplication(fleet(off("a")))
            val controlPlane = DefaultPortableControlPlane(backgroundScope, shell)
            assertIs<ShellCommandResult.Accepted>(
                controlPlane.submit(command("a", ShellIntent.StartRecording, "lifecycle")),
            )
            runCurrent()
            assertEquals(ShellCaptureWorkflowState.STARTING, controlPlane.presentation.value.workflow.state)

            shell.publish(fleet(recording("a")))
            runCurrent()
            assertEquals(ShellCaptureWorkflowState.ONE_ACTIVE, controlPlane.presentation.value.workflow.state)
            assertTrue(controlPlane.presentation.value.devices.single().captureAdmissionReserved)

            shell.publish(fleet(off("a")))
            runCurrent()
            assertEquals(
                ShellCaptureWorkflowState.ALL_VERIFIED_OFF,
                controlPlane.presentation.value.workflow.state,
            )
            assertNull(controlPlane.presentation.value.workflow.admissionReservation)
        }

    private class FakeShellApplication(
        initial: FleetShellProjection,
        private val handler: suspend (ShellCommand) -> ShellCommandResult = {
            ShellCommandResult.Accepted(it.id)
        },
    ) : ShellApplication {
        private val mutable = MutableStateFlow(initial)
        override val projection: StateFlow<FleetShellProjection> = mutable
        val commands = mutableListOf<ShellCommand>()

        override suspend fun submit(command: ShellCommand): ShellCommandResult {
            commands += command
            return handler(command)
        }

        fun publish(value: FleetShellProjection) {
            mutable.value = value
        }
    }

    private class MutableOutputPort : ShellDeviceOutputTruthPort {
        val mutable = MutableStateFlow<Map<DeviceId, DeviceOutputTruth>>(emptyMap())
        override val outputTruth: StateFlow<Map<DeviceId, DeviceOutputTruth>> = mutable
    }

    private fun command(
        device: String,
        intent: ShellIntent,
        suffix: String,
    ) = ShellCommand(
        id = CommandId("command-$suffix"),
        correlationId = CorrelationId("correlation-$suffix"),
        targetDeviceId = DeviceId(device),
        issuedAtEpochMillis = NOW,
        intent = intent,
    )

    private fun fleet(vararg devices: ShellProjection) = FleetShellProjector.aggregate(devices.toList())

    private fun off(id: String) = projected(id, CaptureMode.IDLE)

    private fun recording(id: String) = projected(id, CaptureMode.RECORDING)

    private fun projected(id: String, mode: CaptureMode): ShellProjection = ShellProjector.project(
        DeviceShellSnapshot(
            deviceId = DeviceId(id),
            displayName = "Device $id",
            capture = observation(
                CaptureState(
                    CaptureTruth.Acquired(
                        mode,
                        CaptureProof(SESSION, 1uL, CaptureProofSource.DEVICE_OBSERVATION),
                    ),
                ),
            ),
            link = observation(LinkState.READY),
            maintenance = observation(MaintenanceState.NORMAL),
            update = observation(UpdateStatus(UpdateStage.IDLE)),
            sync = observation(SyncStatus(SyncState.CURRENT, BacklogStatus())),
            power = observation(PowerStatus(PowerState.OPERATIONAL, level = PowerLevel.NORMAL)),
            storage = observation(StorageStatus(StorageState.HEALTHY)),
            fault = observation(FaultStatus(FaultSeverity.NONE)),
        ),
        NOW,
    )

    private fun <T> observation(value: T) = AxisObservation(
        value = value,
        authority = ProjectionAuthority.DEVICE_REPORTED,
        observedAtEpochMillis = NOW,
        freshness = ObservationFreshness.FRESH,
        connectionSessionGeneration = SESSION,
    )

    private companion object {
        const val NOW = 10_000L
        const val SESSION = 3uL
    }
}
