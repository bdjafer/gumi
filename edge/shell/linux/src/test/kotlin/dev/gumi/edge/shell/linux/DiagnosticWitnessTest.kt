package dev.gumi.edge.shell.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticWitnessTest {
    @Test
    fun `linux composition root discovers Omi through the generic runtime registry`() {
        val projection = buildDiagnosticProjection()

        assertEquals("gumi.device.omi-cv1", projection.driverId)
        assertEquals("Omi consumer v1", projection.deviceModel)
        assertTrue("gumi.audio-input" in projection.capabilityKeys)
        assertTrue("gumi.local-media-store" in projection.capabilityKeys)
    }
}
