package dev.gumi.edge.sdk

import kotlinx.coroutines.flow.Flow

enum class TransportKind {
    BLE,
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

    /** Bounded, single-consumer transport stream owned by the device runtime. */
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

/**
 * Session contract for drivers that have completed protocol/capability negotiation. Legacy probe
 * sessions may continue to implement [DeviceSession] while they are migrated.
 */
interface NegotiatedDeviceSession : DeviceSession {
    /** Null until provisioning has bound this transport session to a stable project device. */
    val deviceId: DeviceId?
    val capabilities: CapabilitySet
}

interface DeviceDriverProvider {
    val id: DriverId

    fun match(candidate: EndpointCandidate): DriverMatch

    /**
     * Match-time metadata only. It must not advertise negotiated capabilities or a guessed protocol
     * version; those exist exclusively on the DeviceSession returned by [open].
     */
    fun describe(candidate: EndpointCandidate): DeviceDescriptor

    suspend fun open(
        candidate: EndpointCandidate,
        transport: TransportSession,
    ): DeviceSession
}

class DeviceOpenException(
    val failure: ExpectedFailure,
    message: String = failure.code.value,
    cause: Throwable? = null,
) : Exception(message, cause)
