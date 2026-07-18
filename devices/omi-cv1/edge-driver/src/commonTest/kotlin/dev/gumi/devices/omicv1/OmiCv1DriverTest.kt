package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.TransportKind
import kotlin.test.Test
import kotlin.test.assertEquals

class OmiCv1DriverTest {
    private val driver = OmiCv1DriverProvider()

    @Test
    fun `storage service UUID is an exact match`() {
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = "ble:fixture",
            advertisedServiceUuids = setOf(OmiCv1Protocol.OFFLINE_STORAGE_SERVICE_UUID.uppercase()),
        )

        assertEquals(MatchConfidence.EXACT, driver.match(endpoint).confidence)
        assertEquals(8, driver.describe(endpoint).capabilities.size)
    }

    @Test
    fun `advertised audio service UUID is an exact match`() {
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = "ble:fixture",
            advertisedServiceUuids = setOf(OmiCv1Protocol.AUDIO_SERVICE_UUID),
        )

        assertEquals(MatchConfidence.EXACT, driver.match(endpoint).confidence)
    }

    @Test
    fun `name alone remains possible rather than exact`() {
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = "ble:fixture",
            advertisedName = "Omi",
        )

        assertEquals(MatchConfidence.POSSIBLE, driver.match(endpoint).confidence)
    }
}
