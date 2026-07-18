package dev.gumi.edge.runtime

import dev.gumi.edge.sdk.DeviceDriverProvider
import dev.gumi.edge.sdk.DeviceSession
import dev.gumi.edge.sdk.DriverId
import dev.gumi.edge.sdk.DriverMatch
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.TransportSession

data class DriverSelection(
    val provider: DeviceDriverProvider,
    val match: DriverMatch,
)

sealed class DriverResolutionException(message: String) : IllegalStateException(message) {
    class NoDriver(candidate: EndpointCandidate) :
        DriverResolutionException("No device driver matched endpoint ${candidate.ephemeralId}")

    class Ambiguous(
        candidate: EndpointCandidate,
        driverIds: List<DriverId>,
    ) : DriverResolutionException(
        "Endpoint ${candidate.ephemeralId} matched multiple drivers at the same confidence: " +
            driverIds.joinToString(),
    )
}

class DeviceDriverRegistry(providers: Iterable<DeviceDriverProvider>) {
    private val providerList = providers.toList()
    private val providersById = providerList.associateBy { it.id }

    init {
        require(providersById.size == providerList.size) { "Device driver IDs must be unique" }
    }

    fun registeredDriverIds(): Set<DriverId> = providersById.keys

    fun select(candidate: EndpointCandidate): DriverSelection {
        val matches = providersById.values
            .map { provider -> DriverSelection(provider, provider.match(candidate)) }
            .filter { it.match.confidence != MatchConfidence.NONE }

        if (matches.isEmpty()) throw DriverResolutionException.NoDriver(candidate)

        val bestConfidence = matches.maxOf { it.match.confidence }
        val bestMatches = matches.filter { it.match.confidence == bestConfidence }
        if (bestMatches.size != 1) {
            throw DriverResolutionException.Ambiguous(candidate, bestMatches.map { it.provider.id })
        }

        return bestMatches.single()
    }

    suspend fun open(
        candidate: EndpointCandidate,
        transport: TransportSession,
    ): DeviceSession {
        require(transport.endpoint == candidate) {
            "The selected endpoint and opened transport endpoint must agree"
        }
        return select(candidate).provider.open(candidate, transport)
    }
}
