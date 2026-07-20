package dev.gumi.edge.shell.linux

import dev.gumi.devices.omicv1.OmiCv1DriverProvider
import dev.gumi.devices.omicv1.simulator.OmiCv1Simulator
import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.sdk.NegotiatedDeviceSession
import kotlinx.coroutines.runBlocking

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

/** Opens and negotiates the same BLE-shaped Omi session that a platform host will use. */
fun buildDiagnosticProjection(): DiagnosticProjection = runBlocking {
    val simulator = OmiCv1Simulator()
    val registry = DeviceDriverRegistry(listOf(OmiCv1DriverProvider()))
    val selection = registry.select(simulator.endpoint)
    val session = selection.provider.open(
        simulator.endpoint,
        simulator.connect(simulator.endpoint),
    ) as NegotiatedDeviceSession
    try {
        DiagnosticProjection(
            host = "linux-jvm",
            driverId = session.descriptor.driverId.value,
            deviceModel = session.descriptor.model,
            protocolVersion = session.descriptor.protocolVersion,
            capabilityKeys = session.capabilities.keys().map { it.value }.sorted(),
        )
    } finally {
        session.close()
    }
}

fun main() {
    println(buildDiagnosticProjection().render())
    println()
    println(buildPortableControlPlaneWitness().render())
}
