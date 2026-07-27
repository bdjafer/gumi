package dev.gumi.edge.shell.application

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PortableControlPlaneActionAdapterTest {
    @Test
    fun `ordinary product actions map to semantic command envelopes`() = runTest {
        val plane = RecordingControlPlane()
        val identities = CountingIdentities()
        val adapter = PortableControlPlaneActionAdapter(plane, ShellClock { 1000 }, identities)
        val device = DeviceId("omi-1")
        val expected = mapOf(
            ShellControlAction.REPEAT_STATUS to ShellIntent.RepeatStatus,
            ShellControlAction.START_RECORDING to ShellIntent.StartRecording,
            ShellControlAction.STOP_CAPTURE to ShellIntent.StopRecording,
            ShellControlAction.STOP_VOICE_TURN to ShellIntent.StopVoiceTurn,
            ShellControlAction.BEGIN_PAIRING to ShellIntent.BeginPairing,
            ShellControlAction.REQUEST_SHUTDOWN to ShellIntent.RequestShutdown,
        )

        expected.forEach { (action, intent) ->
            val dispatched = assertIs<ShellProductActionResult.Dispatched>(
                adapter.dispatch(device, action),
            )
            assertEquals(intent, dispatched.command.intent)
            assertEquals(device, dispatched.command.targetDeviceId)
            assertEquals(1000, dispatched.command.issuedAtEpochMillis)
        }

        assertEquals(expected.size, plane.commands.size)
        assertEquals(expected.size, identities.count)
    }

    @Test
    fun `sensitive actions require typed qualification before identities or effects`() = runTest {
        val plane = RecordingControlPlane()
        val identities = CountingIdentities()
        val adapter = PortableControlPlaneActionAdapter(plane, ShellClock { 1000 }, identities)
        val device = DeviceId("omi-1")
        val expected = mapOf(
            ShellControlAction.START_VOICE_TURN to
                ShellActionQualificationRequirement.VOICE_TURN_ADMISSION,
            ShellControlAction.PREPARE_UPDATE to
                ShellActionQualificationRequirement.REVIEWED_FIRMWARE_ARTIFACT,
            ShellControlAction.CONFIRM_PHYSICAL_ACTION to
                ShellActionQualificationRequirement.PHYSICAL_CONFIRMATION_LEASE,
        )

        expected.forEach { (action, requirement) ->
            val result = assertIs<ShellProductActionResult.QualificationRequired>(
                adapter.dispatch(device, action),
            )
            assertEquals(requirement, result.requirement)
        }

        assertEquals(0, identities.count)
        assertTrue(plane.commands.isEmpty())
    }

    @Test
    fun `typed qualifications build only their matching intents`() = runTest {
        val plane = RecordingControlPlane()
        val adapter = PortableControlPlaneActionAdapter(
            plane,
            ShellClock { 1000 },
            CountingIdentities(),
        )
        val device = DeviceId("omi-1")

        val voice = assertIs<ShellProductActionResult.Dispatched>(
            adapter.dispatch(
                device,
                ShellControlAction.START_VOICE_TURN,
                ShellActionQualification.VoiceTurn(VoiceTurnAdmission("voice-lease", 2000)),
            ),
        )
        val update = assertIs<ShellProductActionResult.Dispatched>(
            adapter.dispatch(
                device,
                ShellControlAction.PREPARE_UPDATE,
                ShellActionQualification.FirmwareUpdate("artifact-reviewed-1"),
            ),
        )
        val confirmation = assertIs<ShellProductActionResult.Dispatched>(
            adapter.dispatch(
                device,
                ShellControlAction.CONFIRM_PHYSICAL_ACTION,
                ShellActionQualification.PhysicalConfirmation("physical-lease"),
            ),
        )

        assertEquals(
            VoiceTurnAdmission("voice-lease", 2000),
            assertIs<ShellIntent.StartVoiceTurn>(voice.command.intent).admission,
        )
        assertEquals(
            "artifact-reviewed-1",
            assertIs<ShellIntent.PrepareUpdate>(update.command.intent).artifactId,
        )
        assertEquals(
            "physical-lease",
            assertIs<ShellIntent.ConfirmPhysicalAction>(
                confirmation.command.intent,
            ).confirmationLeaseId,
        )
    }

    @Test
    fun `expired or mismatched qualification is rejected without an effect`() = runTest {
        val plane = RecordingControlPlane()
        val identities = CountingIdentities()
        val adapter = PortableControlPlaneActionAdapter(plane, ShellClock { 1000 }, identities)
        val device = DeviceId("omi-1")

        val expired = assertIs<ShellProductActionResult.Invalid>(
            adapter.dispatch(
                device,
                ShellControlAction.START_VOICE_TURN,
                ShellActionQualification.VoiceTurn(VoiceTurnAdmission("expired", 1000)),
            ),
        )
        val mismatch = assertIs<ShellProductActionResult.Invalid>(
            adapter.dispatch(
                device,
                ShellControlAction.PREPARE_UPDATE,
                ShellActionQualification.PhysicalConfirmation("wrong-kind"),
            ),
        )
        val unexpected = assertIs<ShellProductActionResult.Invalid>(
            adapter.dispatch(
                device,
                ShellControlAction.START_RECORDING,
                ShellActionQualification.FirmwareUpdate("unexpected"),
            ),
        )

        assertEquals("VOICE_TURN_ADMISSION_EXPIRED", expired.reasonCode)
        assertEquals("ACTION_QUALIFICATION_MISMATCH", mismatch.reasonCode)
        assertEquals("ACTION_QUALIFICATION_NOT_EXPECTED", unexpected.reasonCode)
        assertEquals(0, identities.count)
        assertTrue(plane.commands.isEmpty())
    }

    @Test
    fun `outcome unknown retry reuses the exact envelope`() = runTest {
        val plane = RecordingControlPlane()
        val adapter = PortableControlPlaneActionAdapter(
            plane,
            ShellClock { 1000 },
            CountingIdentities(),
        )
        val first = assertIs<ShellProductActionResult.Dispatched>(
            adapter.dispatch(DeviceId("omi-1"), ShellControlAction.START_RECORDING),
        )

        val retried = adapter.retry(first.command)

        assertEquals(first.command, retried.command)
        assertEquals(listOf(first.command, first.command), plane.commands)
    }

    private class CountingIdentities : ShellCommandIdentitySource {
        var count = 0

        override fun next(): ShellCommandIdentity {
            count += 1
            return ShellCommandIdentity(
                CommandId("command-$count"),
                CorrelationId("correlation-$count"),
            )
        }
    }

    private class RecordingControlPlane : PortableControlPlane {
        private val mutablePresentation = MutableStateFlow(
            PortableControlPlaneProjector.project(FleetShellProjector.aggregate(emptyList())),
        )
        override val presentation: StateFlow<PortableControlPlanePresentation> = mutablePresentation
        val commands = mutableListOf<ShellCommand>()

        override suspend fun select(deviceId: DeviceId?): ShellSelectionResult =
            ShellSelectionResult.Applied(deviceId, deviceId)

        override suspend fun submit(command: ShellCommand): ShellCommandResult {
            commands += command
            return ShellCommandResult.Accepted(command.id)
        }
    }
}
