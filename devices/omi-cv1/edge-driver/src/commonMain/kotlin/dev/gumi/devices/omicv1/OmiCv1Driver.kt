package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.CapabilityDescriptor
import dev.gumi.edge.sdk.CapabilityKey
import dev.gumi.edge.sdk.DeviceDescriptor
import dev.gumi.edge.sdk.DeviceDriverProvider
import dev.gumi.edge.sdk.DeviceSession
import dev.gumi.edge.sdk.DeviceSessionEvent
import dev.gumi.edge.sdk.DriverId
import dev.gumi.edge.sdk.DriverMatch
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.SemanticVersion
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.TransportSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

object OmiCv1Protocol {
    const val DRIVER_ID = "gumi.device.omi-cv1"
    const val AUDIO_SERVICE_UUID = "19b10000-e8f2-537e-4f6c-d104768a1214"
    const val OFFLINE_STORAGE_SERVICE_UUID = "30295780-4301-eabd-2904-2849adfeae43"
}

class OmiCv1DriverProvider : DeviceDriverProvider {
    override val id = DriverId(OmiCv1Protocol.DRIVER_ID)

    override fun match(candidate: EndpointCandidate): DriverMatch {
        if (candidate.transport != TransportKind.BLE && candidate.transport != TransportKind.SIMULATED) {
            return DriverMatch.None
        }

        val normalizedServices = candidate.advertisedServiceUuids.map { it.lowercase() }.toSet()
        val matchedService = when {
            OmiCv1Protocol.AUDIO_SERVICE_UUID in normalizedServices -> OmiCv1Protocol.AUDIO_SERVICE_UUID
            OmiCv1Protocol.OFFLINE_STORAGE_SERVICE_UUID in normalizedServices ->
                OmiCv1Protocol.OFFLINE_STORAGE_SERVICE_UUID
            else -> null
        }
        if (matchedService != null) {
            return DriverMatch(
                confidence = MatchConfidence.EXACT,
                evidence = setOf("advertised Omi service UUID $matchedService"),
            )
        }

        if (candidate.advertisedName?.lowercase()?.startsWith("omi") == true) {
            return DriverMatch(
                confidence = MatchConfidence.POSSIBLE,
                evidence = setOf("advertised name starts with omi"),
            )
        }

        return DriverMatch.None
    }

    override fun describe(candidate: EndpointCandidate): DeviceDescriptor {
        require(match(candidate).confidence != MatchConfidence.NONE) {
            "Cannot describe an endpoint that does not match Omi CV1"
        }

        return DeviceDescriptor(
            driverId = id,
            manufacturer = "Based Hardware",
            model = "Omi consumer v1",
            protocolVersion = "stock-ring-v1 (3.0.20+)",
            capabilities = listOf(
                capability("gumi.audio-input", "codec" to "opus"),
                capability("gumi.capture-control"),
                capability("gumi.button-gesture"),
                capability("gumi.local-media-store", "record-size-bytes" to RingProtocol.RECORD_SIZE.toString()),
                capability("gumi.visual-indicator"),
                capability("gumi.haptic"),
                capability("gumi.power-status"),
                capability("gumi.firmware-update"),
            ),
        )
    }

    override suspend fun open(
        candidate: EndpointCandidate,
        transport: TransportSession,
    ): DeviceSession {
        require(transport.endpoint == candidate) { "Transport belongs to a different endpoint" }
        val descriptor = describe(candidate)
        return OmiCv1Session(candidate, descriptor, transport)
    }

    private fun capability(
        key: String,
        vararg attributes: Pair<String, String>,
    ) = CapabilityDescriptor(
        key = CapabilityKey(key),
        version = SemanticVersion(1u, 0u),
        attributes = attributes.toMap(),
    )
}

private class OmiCv1Session(
    override val endpoint: EndpointCandidate,
    override val descriptor: DeviceDescriptor,
    private val transport: TransportSession,
) : DeviceSession {
    override val events: Flow<DeviceSessionEvent> = emptyFlow()

    override suspend fun close() {
        transport.close()
    }
}
