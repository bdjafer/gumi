package dev.gumi.edge.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CapabilityTest {
    @Test
    fun `capability keys are namespaced and stable`() {
        assertEquals("gumi.audio-input", CapabilityKey("gumi.audio-input").value)
        assertFailsWith<IllegalArgumentException> { CapabilityKey("audio") }
        assertFailsWith<IllegalArgumentException> { CapabilityKey("Gumi.Audio") }
    }

    @Test
    fun `device descriptor rejects duplicate capability keys`() {
        val audio = CapabilityDescriptor(CapabilityKey("gumi.audio-input"), SemanticVersion(1u, 0u))

        assertFailsWith<IllegalArgumentException> {
            DeviceDescriptor(
                driverId = DriverId("gumi.test"),
                manufacturer = "Gumi",
                model = "Fixture",
                protocolVersion = "1",
                capabilities = listOf(audio, audio.copy()),
            )
        }
    }
}
