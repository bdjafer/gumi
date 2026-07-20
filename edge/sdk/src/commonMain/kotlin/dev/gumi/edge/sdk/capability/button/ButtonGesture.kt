package dev.gumi.edge.sdk.capability.button

import dev.gumi.edge.sdk.CapabilityDescriptor
import dev.gumi.edge.sdk.CapabilityHandle
import dev.gumi.edge.sdk.CapabilityKey
import dev.gumi.edge.sdk.CapabilityType
import dev.gumi.edge.sdk.SemanticVersion
import kotlinx.coroutines.flow.Flow

enum class ButtonGestureKind {
    SINGLE_TAP,
    DOUBLE_TAP,
    HOLD,
    PRESS,
    RELEASE,
    UNKNOWN,
}

data class ButtonGestureDescriptor(
    val gestures: Set<ButtonGestureKind>,
    val configurable: Boolean,
    override val required: Boolean = false,
) : CapabilityDescriptor {
    override val key: CapabilityKey = ButtonGestureV1.key
    override val version: SemanticVersion = SemanticVersion(1u, 0u)

    init {
        require(gestures.isNotEmpty())
    }
}

data class ButtonGestureEvent(
    val ordinal: ULong,
    val gesture: ButtonGestureKind,
    val deviceTimeMillis: ULong?,
)

interface ButtonGestureHandle : CapabilityHandle<ButtonGestureDescriptor> {
    val events: Flow<ButtonGestureEvent>
}

object ButtonGestureV1 : CapabilityType<ButtonGestureDescriptor, ButtonGestureHandle> {
    override val key = CapabilityKey("gumi.button-gesture")
    override val supportedMajor = 1u
}
