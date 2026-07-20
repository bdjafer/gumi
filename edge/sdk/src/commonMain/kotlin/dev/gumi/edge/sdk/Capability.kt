package dev.gumi.edge.sdk

/**
 * Negotiated description of one capability.
 *
 * Capability packages implement this interface with strongly typed limits and feature fields. The
 * companion factory retains the original string-attribute envelope for compatibility probes and
 * capabilities whose typed contract has not landed yet.
 */
interface CapabilityDescriptor {
    val key: CapabilityKey
    val version: SemanticVersion
    val required: Boolean
    val attributes: Map<String, String> get() = emptyMap()

    companion object {
        operator fun invoke(
            key: CapabilityKey,
            version: SemanticVersion,
            required: Boolean = false,
            attributes: Map<String, String> = emptyMap(),
        ): BasicCapabilityDescriptor = BasicCapabilityDescriptor(
            key = key,
            version = version,
            required = required,
            attributes = attributes,
        )
    }
}

data class BasicCapabilityDescriptor(
    override val key: CapabilityKey,
    override val version: SemanticVersion,
    override val required: Boolean = false,
    override val attributes: Map<String, String> = emptyMap(),
) : CapabilityDescriptor

/** A typed operational handle. Its descriptor is the exact contract implemented by the handle. */
interface CapabilityHandle<out D : CapabilityDescriptor> {
    val descriptor: D
}

/**
 * Stable lookup token and compatibility policy for a capability contract.
 *
 * The singleton object implementing a type is also the runtime-safe lookup token. Two independently
 * created type objects with the same key are not interchangeable.
 */
interface CapabilityType<D : CapabilityDescriptor, H : CapabilityHandle<D>> {
    val key: CapabilityKey
    val supportedMajor: UInt

    fun supports(version: SemanticVersion): Boolean = version.major == supportedMajor
}

data class CapabilityBinding<D : CapabilityDescriptor, H : CapabilityHandle<D>>(
    val type: CapabilityType<D, H>,
    val descriptor: D,
    val handle: H,
) {
    init {
        require(descriptor.key == type.key) {
            "Capability descriptor key ${descriptor.key} does not match type ${type.key}"
        }
        require(type.supports(descriptor.version)) {
            "Capability ${descriptor.key} version ${descriptor.version} is unsupported by its binding"
        }
        require(handle.descriptor == descriptor) {
            "Capability handle and binding must expose the same descriptor"
        }
    }
}

/**
 * Immutable negotiated capabilities for an open device session.
 *
 * Recognized capabilities are retrievable only through their exact typed token. Unknown optional or
 * unsupported optional descriptors remain available for diagnostics. Required incompatibilities fail
 * negotiation explicitly instead of being guessed or silently discarded.
 */
class CapabilitySet private constructor(
    val descriptors: List<CapabilityDescriptor>,
    private val bindingsByKey: Map<CapabilityKey, CapabilityBinding<*, *>>,
    val unrecognizedDescriptors: List<CapabilityDescriptor>,
) {
    val size: Int get() = descriptors.size

    fun keys(): Set<CapabilityKey> = descriptors.mapTo(linkedSetOf(), CapabilityDescriptor::key)

    fun <D : CapabilityDescriptor, H : CapabilityHandle<D>> binding(
        type: CapabilityType<D, H>,
    ): CapabilityBinding<D, H>? {
        val binding = bindingsByKey[type.key] ?: return null
        if (binding.type !== type) return null
        @Suppress("UNCHECKED_CAST")
        return binding as CapabilityBinding<D, H>
    }

    fun <D : CapabilityDescriptor, H : CapabilityHandle<D>> descriptor(
        type: CapabilityType<D, H>,
    ): D? = binding(type)?.descriptor

    fun <D : CapabilityDescriptor, H : CapabilityHandle<D>> handle(
        type: CapabilityType<D, H>,
    ): H? = binding(type)?.handle

    companion object {
        fun negotiate(
            advertised: List<CapabilityDescriptor>,
            bindings: List<CapabilityBinding<*, *>>,
            supportedTypes: Set<CapabilityType<*, *>> = bindings.mapTo(linkedSetOf()) { it.type },
        ): OperationResult<CapabilitySet> {
            val effectiveTypes = supportedTypes + bindings.map { it.type }
            duplicateKey(advertised.map(CapabilityDescriptor::key))?.let { key ->
                return failure("DUPLICATE_CAPABILITY_DESCRIPTOR", "capability" to key.value)
            }
            duplicateKey(bindings.map { it.type.key })?.let { key ->
                return failure("DUPLICATE_CAPABILITY_BINDING", "capability" to key.value)
            }
            duplicateKey(effectiveTypes.map { it.key })?.let { key ->
                return failure("DUPLICATE_CAPABILITY_TYPE", "capability" to key.value)
            }

            val advertisedByKey = advertised.associateBy(CapabilityDescriptor::key)
            val bindingsByKey = bindings.associateBy { it.type.key }
            val typesByKey = effectiveTypes.associateBy { it.key }

            bindings.firstOrNull { binding ->
                val descriptor = advertisedByKey[binding.type.key]
                descriptor == null || descriptor != binding.descriptor
            }?.let { binding ->
                val code = if (binding.type.key in advertisedByKey) {
                    "CAPABILITY_BINDING_DESCRIPTOR_MISMATCH"
                } else {
                    "UNADVERTISED_CAPABILITY_BINDING"
                }
                return failure(code, "capability" to binding.type.key.value)
            }

            val unrecognized = mutableListOf<CapabilityDescriptor>()
            advertised.forEach { descriptor ->
                val type = typesByKey[descriptor.key]
                val binding = bindingsByKey[descriptor.key]

                if (type == null) {
                    if (descriptor.required) {
                        return failure(
                            "UNKNOWN_REQUIRED_CAPABILITY",
                            "capability" to descriptor.key.value,
                            "version" to descriptor.version.toString(),
                        )
                    }
                    unrecognized += descriptor
                } else if (!type.supports(descriptor.version)) {
                    if (descriptor.required) {
                        return failure(
                            "UNSUPPORTED_REQUIRED_CAPABILITY_VERSION",
                            "capability" to descriptor.key.value,
                            "version" to descriptor.version.toString(),
                        )
                    }
                    unrecognized += descriptor
                } else if (binding == null) {
                    return failure(
                        "MISSING_CAPABILITY_HANDLE",
                        "capability" to descriptor.key.value,
                        "version" to descriptor.version.toString(),
                    )
                }
            }

            return OperationResult.Success(
                CapabilitySet(
                    descriptors = advertised.toList(),
                    bindingsByKey = bindingsByKey,
                    unrecognizedDescriptors = unrecognized,
                ),
            )
        }

        private fun duplicateKey(keys: List<CapabilityKey>): CapabilityKey? = keys
            .groupingBy { it }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key

        private fun failure(
            code: String,
            vararg evidence: Pair<String, String>,
        ): OperationResult.Failure = OperationResult.Failure(
            ExpectedFailure(
                category = FailureCategory.INCOMPATIBLE,
                code = FailureCode(code),
                retryable = false,
                redactedEvidence = evidence.toMap(),
            ),
        )
    }
}

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
