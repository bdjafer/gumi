package dev.gumi.edge.sdk

data class CapabilityDescriptor(
    val key: CapabilityKey,
    val version: SemanticVersion,
    val required: Boolean = false,
    val attributes: Map<String, String> = emptyMap(),
)

data class DeviceDescriptor(
    val driverId: DriverId,
    val manufacturer: String,
    val model: String,
    val protocolVersion: String,
    val capabilities: List<CapabilityDescriptor>,
) {
    init {
        require(manufacturer.isNotBlank())
        require(model.isNotBlank())
        require(protocolVersion.isNotBlank())
        require(capabilities.map { it.key }.distinct().size == capabilities.size) {
            "A device cannot publish the same capability key more than once"
        }
    }
}
