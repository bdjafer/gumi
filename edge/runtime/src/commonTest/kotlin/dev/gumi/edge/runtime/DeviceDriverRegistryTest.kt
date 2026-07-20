package dev.gumi.edge.runtime

import dev.gumi.edge.sdk.DeviceDescriptor
import dev.gumi.edge.sdk.DeviceDriverProvider
import dev.gumi.edge.sdk.DeviceSession
import dev.gumi.edge.sdk.DriverId
import dev.gumi.edge.sdk.DriverMatch
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.TransportSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceDriverRegistryTest {
    private val candidate = EndpointCandidate(TransportKind.BLE, "fixture-1")

    @Test
    fun `exact match wins over possible match`() {
        val possible = StubProvider("possible", MatchConfidence.POSSIBLE)
        val exact = StubProvider("exact", MatchConfidence.EXACT)

        val selection = DeviceDriverRegistry(listOf(possible, exact)).select(candidate)

        assertEquals(exact.id, selection.provider.id)
        assertEquals(MatchConfidence.EXACT, selection.match.confidence)
    }

    @Test
    fun `same-confidence matches are rejected instead of guessed`() {
        val registry = DeviceDriverRegistry(
            listOf(
                StubProvider("first", MatchConfidence.EXACT),
                StubProvider("second", MatchConfidence.EXACT),
            ),
        )

        assertFailsWith<DriverResolutionException.Ambiguous> { registry.select(candidate) }
    }

    @Test
    fun `unknown endpoint fails closed`() {
        val registry = DeviceDriverRegistry(listOf(StubProvider("none", MatchConfidence.NONE)))

        assertFailsWith<DriverResolutionException.NoDriver> { registry.select(candidate) }
    }

    private class StubProvider(
        id: String,
        private val confidence: MatchConfidence,
    ) : DeviceDriverProvider {
        override val id = DriverId("gumi.test.$id")

        override fun match(candidate: EndpointCandidate) = DriverMatch(confidence)

        override fun describe(candidate: EndpointCandidate): DeviceDescriptor = error("Not needed")

        override suspend fun open(
            candidate: EndpointCandidate,
            transport: TransportSession,
        ): DeviceSession = error("Not needed")
    }
}
