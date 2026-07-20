package dev.gumi.edge.sdk.capability.haptic

import dev.gumi.edge.sdk.CapabilityDescriptor
import dev.gumi.edge.sdk.CapabilityHandle
import dev.gumi.edge.sdk.CapabilityKey
import dev.gumi.edge.sdk.CapabilityType
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.OperationResult
import dev.gumi.edge.sdk.SemanticVersion

@JvmInline
value class HapticPatternId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9-]{0,63}")))
    }

    override fun toString(): String = value
}

data class HapticDescriptor(
    val patterns: Set<HapticPatternId>,
    override val required: Boolean = false,
) : CapabilityDescriptor {
    override val key: CapabilityKey = HapticV1.key
    override val version: SemanticVersion = SemanticVersion(1u, 0u)

    init {
        require(patterns.isNotEmpty())
    }
}

interface HapticHandle : CapabilityHandle<HapticDescriptor> {
    suspend fun play(
        commandId: CommandId,
        pattern: HapticPatternId,
    ): OperationResult<Unit>
}

object HapticV1 : CapabilityType<HapticDescriptor, HapticHandle> {
    override val key = CapabilityKey("gumi.haptic")
    override val supportedMajor = 1u
}
