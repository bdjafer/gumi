package dev.gumi.edge.sdk.capability.power

import dev.gumi.edge.sdk.CapabilityDescriptor
import dev.gumi.edge.sdk.CapabilityHandle
import dev.gumi.edge.sdk.CapabilityKey
import dev.gumi.edge.sdk.CapabilityType
import dev.gumi.edge.sdk.SemanticVersion
import kotlinx.coroutines.flow.Flow

data class PowerStatusDescriptor(
    val reportsBatteryPercent: Boolean,
    val reportsCharging: Boolean,
    override val required: Boolean = false,
) : CapabilityDescriptor {
    override val key: CapabilityKey = PowerStatusV1.key
    override val version: SemanticVersion = SemanticVersion(1u, 0u)
}

data class PowerStatus(
    val batteryPercent: UInt?,
    val charging: Boolean?,
    /** Null when a compatibility transport cannot attach a trustworthy host timestamp to a read. */
    val observedAtMonotonicMillis: Long?,
) {
    init {
        require(batteryPercent == null || batteryPercent <= 100u)
        require(observedAtMonotonicMillis == null || observedAtMonotonicMillis >= 0)
    }
}

interface PowerStatusHandle : CapabilityHandle<PowerStatusDescriptor> {
    suspend fun read(): PowerStatus
    val updates: Flow<PowerStatus>
}

object PowerStatusV1 : CapabilityType<PowerStatusDescriptor, PowerStatusHandle> {
    override val key = CapabilityKey("gumi.power-status")
    override val supportedMajor = 1u
}
