package dev.gumi.edge.shell.application

import dev.gumi.edge.runtime.capture.CaptureCommand
import dev.gumi.edge.runtime.capture.CaptureCommandKind
import dev.gumi.edge.runtime.capture.CaptureMode
import dev.gumi.edge.runtime.capture.CaptureProof
import dev.gumi.edge.runtime.capture.CaptureProofSource
import dev.gumi.edge.runtime.capture.CaptureState
import dev.gumi.edge.runtime.capture.CaptureTransition
import dev.gumi.edge.runtime.capture.CaptureTruth
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellControlPresentationTest {
    @Test
    fun `every semantic foreground meets text contrast on both dark surfaces`() {
        val palette = GumiDarkShellPalette
        val foregrounds = listOf(
            palette.content,
            palette.muted,
            palette.verifiedOff,
            palette.privacyActive,
            palette.privacyUncertain,
            palette.information,
        )

        foregrounds.forEach { foreground ->
            assertTrue(contrast(foreground, palette.background) >= 4.5)
            assertTrue(contrast(foreground, palette.surface) >= 4.5)
        }
    }

    @Test
    fun `verified idle exposes starts while every status keeps text and icon`() {
        val control = control(snapshot())

        assertEquals(ShellSemanticTone.VERIFIED_OFF, control.captureVisual.tone)
        assertEquals("microphone-off-verified", control.captureVisual.iconKey)
        assertTrue(control.captureVisual.label.contains("Microphone off"))
        assertTrue(control.actions.enabled(ShellControlAction.START_RECORDING))
        assertTrue(control.actions.enabled(ShellControlAction.START_VOICE_TURN))
        assertTrue(control.actions.enabled(ShellControlAction.BEGIN_PAIRING))
        assertTrue(control.actions.enabled(ShellControlAction.PREPARE_UPDATE))
        assertTrue(control.actions.enabled(ShellControlAction.REQUEST_SHUTDOWN))
        assertFalse(control.actions.enabled(ShellControlAction.STOP_CAPTURE))
    }

    @Test
    fun `active and uncertain capture always retain a local stop control`() {
        val recording = control(
            snapshot(capture = acquired(CaptureMode.RECORDING)),
        )
        assertEquals(ShellSemanticTone.PRIVACY_ACTIVE, recording.captureVisual.tone)
        assertTrue(recording.actions.enabled(ShellControlAction.STOP_CAPTURE))
        assertFalse(recording.actions.enabled(ShellControlAction.START_RECORDING))
        assertTrue(recording.actions.enabled(ShellControlAction.REQUEST_SHUTDOWN))

        val disconnected = control(
            snapshot(
                capture = acquired(CaptureMode.RECORDING),
                link = LinkState.DISCONNECTED,
            ),
        )
        assertEquals(ShellSemanticTone.PRIVACY_UNCERTAIN, disconnected.captureVisual.tone)
        assertTrue(disconnected.actions.enabled(ShellControlAction.STOP_CAPTURE))
        assertEquals(
            "CAPTURE_TRUTH_NOT_VERIFIED_OFF",
            disconnected.actions.getValue(ShellControlAction.START_RECORDING).blockedReasonCode,
        )
    }

    @Test
    fun `a pending start cannot hide the safety stop`() {
        val command = CaptureCommand(
            id = CommandId("pending-start"),
            correlationId = CorrelationId("pending-start-correlation"),
            kind = CaptureCommandKind.START_RECORDING,
        )
        val starting = acquired(CaptureMode.IDLE).copy(
            transition = CaptureTransition(command, CaptureMode.RECORDING),
        )
        val control = control(snapshot(capture = starting, pendingCommandId = command.id))

        assertEquals(CapturePresentationKind.STARTING, control.device.capture.value.kind)
        assertTrue(control.actions.enabled(ShellControlAction.STOP_CAPTURE))
        assertFalse(control.actions.enabled(ShellControlAction.START_RECORDING))
        assertEquals(
            "CAPTURE_TRUTH_NOT_VERIFIED_OFF",
            control.actions.getValue(ShellControlAction.START_RECORDING).blockedReasonCode,
        )
    }

    @Test
    fun `maintenance is explicit and physical confirmation is context bound`() {
        val updating = control(snapshot(maintenance = MaintenanceState.UPDATING))
        assertFalse(updating.actions.enabled(ShellControlAction.START_RECORDING))
        assertFalse(updating.actions.enabled(ShellControlAction.PREPARE_UPDATE))
        assertFalse(updating.actions.enabled(ShellControlAction.REQUEST_SHUTDOWN))
        assertTrue(updating.actions.enabled(ShellControlAction.REPEAT_STATUS))

        val awaiting = control(
            snapshot(maintenance = MaintenanceState.AWAITING_PHYSICAL_CONFIRMATION),
        )
        assertTrue(awaiting.actions.enabled(ShellControlAction.CONFIRM_PHYSICAL_ACTION))
        assertFalse(awaiting.actions.enabled(ShellControlAction.START_RECORDING))
    }

    @Test
    fun `update is hidden until power evidence satisfies policy`() {
        val unknownPower = control(
            snapshot(
                power = PowerStatus(
                    state = PowerState.OPERATIONAL,
                    batteryPercent = 80u,
                    level = PowerLevel.UNKNOWN,
                ),
            ),
        )

        assertFalse(unknownPower.actions.enabled(ShellControlAction.PREPARE_UPDATE))
        assertEquals(
            "POWER_POLICY_NOT_SATISFIED",
            unknownPower.actions.getValue(ShellControlAction.PREPARE_UPDATE).blockedReasonCode,
        )
    }

    @Test
    fun `voice turn overlay exposes voice stop and base capture stop`() {
        val state = acquired(CaptureMode.VOICE_TURN).copy(
            resumeAfterVoiceTurn = CaptureMode.RECORDING,
        )
        val control = control(snapshot(capture = state))

        assertEquals(CapturePresentationKind.RECORDING_WITH_VOICE_TURN, control.device.capture.value.kind)
        assertTrue(control.actions.enabled(ShellControlAction.STOP_VOICE_TURN))
        assertTrue(control.actions.enabled(ShellControlAction.STOP_CAPTURE))
    }

    @Test
    fun `fresh base recording may start one voice turn overlay but an existing turn may not`() {
        val recording = control(snapshot(capture = acquired(CaptureMode.RECORDING)))
        assertTrue(recording.actions.enabled(ShellControlAction.START_VOICE_TURN))
        assertFalse(recording.actions.enabled(ShellControlAction.START_RECORDING))

        val voiceTurn = control(snapshot(capture = acquired(CaptureMode.VOICE_TURN)))
        assertFalse(voiceTurn.actions.enabled(ShellControlAction.START_VOICE_TURN))
        assertEquals(
            "VOICE_TURN_ALREADY_ACTIVE",
            voiceTurn.actions.getValue(ShellControlAction.START_VOICE_TURN).blockedReasonCode,
        )
    }

    private fun control(snapshot: DeviceShellSnapshot): DeviceControlPresentation =
        ShellControlProjector.project(ShellProjector.project(snapshot, NOW))

    private fun snapshot(
        capture: CaptureState = acquired(CaptureMode.IDLE),
        link: LinkState = LinkState.READY,
        maintenance: MaintenanceState = MaintenanceState.NORMAL,
        power: PowerStatus = PowerStatus(
            state = PowerState.OPERATIONAL,
            batteryPercent = 80u,
            level = PowerLevel.NORMAL,
        ),
        pendingCommandId: CommandId? = null,
    ): DeviceShellSnapshot {
        fun <T> observation(value: T) = AxisObservation(
            value = value,
            authority = ProjectionAuthority.DEVICE_REPORTED,
            observedAtEpochMillis = NOW,
            freshness = ObservationFreshness.FRESH,
            connectionSessionGeneration = CONNECTION,
        )
        return DeviceShellSnapshot(
            deviceId = DeviceId("control-device"),
            displayName = "Control device",
            capture = observation(capture),
            link = observation(link),
            maintenance = observation(maintenance),
            update = observation(UpdateStatus(UpdateStage.IDLE)),
            sync = observation(SyncStatus(SyncState.CURRENT, BacklogStatus())),
            power = observation(power),
            storage = observation(StorageStatus(StorageState.HEALTHY)),
            fault = observation(FaultStatus(FaultSeverity.NONE)),
            pendingCommandId = pendingCommandId,
        )
    }

    private fun acquired(mode: CaptureMode): CaptureState = CaptureState(
        truth = CaptureTruth.Acquired(
            mode = mode,
            proof = CaptureProof(
                connectionSessionGeneration = CONNECTION,
                causalGeneration = 1u,
                source = CaptureProofSource.DEVICE_OBSERVATION,
            ),
        ),
        resumeAfterVoiceTurn = if (mode == CaptureMode.VOICE_TURN) CaptureMode.IDLE else null,
    )

    private fun Map<ShellControlAction, ShellActionAvailability>.enabled(
        action: ShellControlAction,
    ): Boolean = getValue(action).enabled

    private fun contrast(first: ShellArgb, second: ShellArgb): Double {
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun luminance(color: ShellArgb): Double {
        fun channel(shift: Int): Double {
            val encoded = ((color.value shr shift) and 0xFFu).toDouble() / 255.0
            return if (encoded <= 0.04045) {
                encoded / 12.92
            } else {
                ((encoded + 0.055) / 1.055).pow(2.4)
            }
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private companion object {
        const val NOW = 1_000L
        const val CONNECTION = 1uL
    }
}
