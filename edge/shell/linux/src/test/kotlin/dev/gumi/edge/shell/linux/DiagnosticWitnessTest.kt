package dev.gumi.edge.shell.linux

import dev.gumi.edge.shell.application.FleetCaptureState
import dev.gumi.edge.shell.application.ShellTerminalOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticWitnessTest {
    @Test
    fun `linux composition root discovers Omi through the generic runtime registry`() {
        val projection = buildDiagnosticProjection()

        assertEquals("gumi.device.omi-cv1", projection.driverId)
        assertEquals("Omi CV 1", projection.deviceModel)
        assertEquals("omi-stock/3.0.12", projection.protocolVersion)
        assertTrue("gumi.audio-input" in projection.capabilityKeys)
        assertTrue("gumi.power-status" in projection.capabilityKeys)
        assertTrue("gumi.local-media-store" !in projection.capabilityKeys)
    }

    @Test
    fun `linux composes the same multi-device shell without laundering negotiated link as mic truth`() {
        val projection = buildPortableControlPlaneWitness()

        assertEquals("linux-jvm", projection.host)
        assertEquals(1, projection.managedDeviceCount)
        assertEquals(FleetCaptureState.MAY_BE_ACTIVE, projection.fleetCaptureState)
        assertEquals("Microphone state unknown — check the device privacy light", projection.captureLabel)
        assertEquals(1u, projection.routedOwnerGeneration)
        assertEquals(ShellTerminalOutcome.NO_OP, projection.commandOutcome)
    }
}
