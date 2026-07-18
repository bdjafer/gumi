package dev.gumi.edge.shell.linux

import dev.gumi.devices.omicv1.OmiCv1DriverProvider
import dev.gumi.devices.omicv1.OmiCv1Protocol
import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind

data class DiagnosticProjection(
    val host: String,
    val driverId: String,
    val deviceModel: String,
    val protocolVersion: String,
    val capabilityKeys: List<String>,
) {
    fun render(): String = buildString {
        appendLine("Gumi edge witness: $host")
        appendLine("driver=$driverId")
        appendLine("device=$deviceModel")
        appendLine("protocol=$protocolVersion")
        append("capabilities=${capabilityKeys.joinToString()}")
    }
}

fun buildDiagnosticProjection(): DiagnosticProjection {
    val endpoint = EndpointCandidate(
        transport = TransportKind.SIMULATED,
        ephemeralId = "simulated:omi-cv1",
        advertisedServiceUuids = setOf(OmiCv1Protocol.OFFLINE_STORAGE_SERVICE_UUID),
    )
    val registry = DeviceDriverRegistry(listOf(OmiCv1DriverProvider()))
    val selection = registry.select(endpoint)
    val descriptor = selection.provider.describe(endpoint)

    return DiagnosticProjection(
        host = "linux-jvm",
        driverId = descriptor.driverId.value,
        deviceModel = descriptor.model,
        protocolVersion = descriptor.protocolVersion,
        capabilityKeys = descriptor.capabilities.map { it.key.value },
    )
}

fun main() {
    println(buildDiagnosticProjection().render())
}
