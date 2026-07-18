package dev.gumi.edge.sdk

import kotlinx.coroutines.flow.Flow

enum class TransportKind {
    BLE,
    SIMULATED,
}

data class EndpointCandidate(
    val transport: TransportKind,
    val ephemeralId: String,
    val advertisedServiceUuids: Set<String> = emptySet(),
    val advertisedName: String? = null,
) {
    init {
        require(ephemeralId.isNotBlank()) { "Endpoint candidate needs an ephemeral transport ID" }
    }
}

enum class MatchConfidence {
    NONE,
    POSSIBLE,
    EXACT,
}

data class DriverMatch(
    val confidence: MatchConfidence,
    val evidence: Set<String> = emptySet(),
) {
    init {
        require(confidence != MatchConfidence.NONE || evidence.isEmpty()) {
            "A non-match cannot claim positive evidence"
        }
    }

    companion object {
        val None = DriverMatch(MatchConfidence.NONE)
    }
}

sealed interface TransportEvent {
    data object Closed : TransportEvent

    data class Fault(
        val code: String,
        val detail: String,
    ) : TransportEvent
}

interface TransportSession {
    val endpoint: EndpointCandidate
    val events: Flow<TransportEvent>

    suspend fun close()
}

sealed interface DeviceSessionEvent {
    data object Closed : DeviceSessionEvent

    data class Diagnostic(
        val code: String,
        val detail: String,
    ) : DeviceSessionEvent
}

interface DeviceSession {
    val endpoint: EndpointCandidate
    val descriptor: DeviceDescriptor
    val events: Flow<DeviceSessionEvent>

    suspend fun close()
}

interface DeviceDriverProvider {
    val id: DriverId

    fun match(candidate: EndpointCandidate): DriverMatch

    fun describe(candidate: EndpointCandidate): DeviceDescriptor

    suspend fun open(
        candidate: EndpointCandidate,
        transport: TransportSession,
    ): DeviceSession
}
