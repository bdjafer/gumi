package dev.gumi.edge.sdk

@JvmInline
value class DriverId(val value: String) {
    init {
        require(value.isNotBlank()) { "Driver ID cannot be blank" }
    }

    override fun toString(): String = value
}

/** Stable, provisioned identity for one physical device. Never derive this from a transport address. */
@JvmInline
value class DeviceId(val value: String) {
    init {
        requireOpaqueIdentifier("Device ID", value)
    }

    override fun toString(): String = value
}

/** Process-local reference to a discovered transport endpoint. It is not a semantic device identity. */
@JvmInline
value class EndpointId(val value: String) {
    init {
        requireOpaqueIdentifier("Endpoint ID", value)
    }

    override fun toString(): String = value
}

/** Stable idempotency identity supplied with an application command. */
@JvmInline
value class CommandId(val value: String) {
    init {
        requireOpaqueIdentifier("Command ID", value)
    }

    override fun toString(): String = value
}

/** Identity joining a requested effect to the one hardware completion allowed to resolve it. */
@JvmInline
value class CorrelationId(val value: String) {
    init {
        requireOpaqueIdentifier("Correlation ID", value)
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

private fun requireOpaqueIdentifier(label: String, value: String) {
    require(value.isNotBlank()) { "$label cannot be blank" }
    require(value == value.trim()) { "$label cannot have leading or trailing whitespace" }
    require(value.length <= 200) { "$label cannot exceed 200 characters" }
    require(value.none(Char::isISOControl)) { "$label cannot contain control characters" }
}
