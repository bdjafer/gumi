package dev.gumi.edge.sdk

@JvmInline
value class DriverId(val value: String) {
    init {
        require(value.isNotBlank()) { "Driver ID cannot be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class CapabilityKey(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9-]*(\\.[a-z0-9-]+)+"))) {
            "Capability key must be a lowercase namespaced key: $value"
        }
    }

    override fun toString(): String = value
}

data class SemanticVersion(
    val major: UInt,
    val minor: UInt,
) {
    override fun toString(): String = "$major.$minor"
}
