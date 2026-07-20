package dev.gumi.edge.sdk.ble

import dev.gumi.edge.sdk.EndpointCandidate

enum class BleGattCharacteristicProperty {
    BROADCAST,
    READ,
    WRITE_WITHOUT_RESPONSE,
    WRITE,
    NOTIFY,
    INDICATE,
    SIGNED_WRITE,
    EXTENDED,
}

enum class BleGattAttributePermission {
    READ,
    READ_ENCRYPTED,
    READ_ENCRYPTED_MITM,
    WRITE,
    WRITE_ENCRYPTED,
    WRITE_ENCRYPTED_MITM,
    WRITE_SIGNED,
    WRITE_SIGNED_MITM,
}

data class BleGattDescriptor(
    val uuid: String,
    val permissions: Set<BleGattAttributePermission>,
) {
    init {
        requireCanonicalBleUuid(uuid)
    }
}

data class BleGattCharacteristic(
    val serviceUuid: String,
    val uuid: String,
    val properties: Set<BleGattCharacteristicProperty>,
    val permissions: Set<BleGattAttributePermission>,
    val descriptors: List<BleGattDescriptor>,
) {
    init {
        requireCanonicalBleUuid(serviceUuid)
        requireCanonicalBleUuid(uuid)
    }
}

data class BleGattService(
    val uuid: String,
    val primary: Boolean,
    val characteristics: List<BleGattCharacteristic>,
) {
    init {
        requireCanonicalBleUuid(uuid)
    }
}

data class BleGattReadTarget(
    val serviceUuid: String,
    val characteristicUuid: String,
) {
    init {
        requireCanonicalBleUuid(serviceUuid)
        requireCanonicalBleUuid(characteristicUuid)
    }
}

/**
 * A deliberately redacted wrapper for bytes read from a peripheral. Callers must explicitly copy
 * the value before decoding it; logs and debugger-friendly string interpolation never reveal it.
 */
class BleGattValue private constructor(private val bytes: ByteArray) {
    val size: Int get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is BleGattValue && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "BleGattValue([redacted], size=$size)"

    companion object {
        fun copyOf(bytes: ByteArray): BleGattValue = BleGattValue(bytes.copyOf())
    }
}

enum class BleGattReadFailureCode {
    MISSING,
    NOT_READABLE,
    PLATFORM_FAILED,
}

sealed interface BleGattReadResult {
    val target: BleGattReadTarget

    data class Success(
        override val target: BleGattReadTarget,
        val value: BleGattValue,
    ) : BleGattReadResult

    data class Failure(
        override val target: BleGattReadTarget,
        val code: BleGattReadFailureCode,
        val detail: String,
    ) : BleGattReadResult
}

enum class BlePhy {
    LE_1M,
    LE_2M,
    LE_CODED,
    UNKNOWN,
}

enum class BleBondState {
    NOT_BONDED,
    BONDING,
    BONDED,
    UNKNOWN,
}

data class BleLinkSnapshot(
    val mtu: Int?,
    val txPhy: BlePhy?,
    val rxPhy: BlePhy?,
    val bondState: BleBondState,
)

data class BleGattInspectionRequest(
    val reads: Set<BleGattReadTarget> = emptySet(),
    val connectionTimeoutMillis: Long = 15_000,
) {
    init {
        require(connectionTimeoutMillis in 1_000..60_000) {
            "BLE inspection connection timeout must be between 1 and 60 seconds"
        }
    }
}

data class BleGattInspection(
    val endpoint: EndpointCandidate,
    val services: List<BleGattService>,
    val reads: List<BleGattReadResult>,
    val link: BleLinkSnapshot,
)

enum class BleGattInspectionFailureCode {
    ENDPOINT_EXPIRED,
    PERMISSION_DENIED,
    CONNECTION_FAILED,
    BLUETOOTH_UNAVAILABLE,
}

class BleGattInspectionException(
    val code: BleGattInspectionFailureCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

interface BleGattInspector {
    /**
     * Connects once, discovers the service tree, performs only the explicitly allowlisted reads,
     * and disconnects. The port intentionally exposes no write, subscription, pairing, cache,
     * connection-priority, or firmware operation.
     */
    suspend fun inspect(
        endpoint: EndpointCandidate,
        request: BleGattInspectionRequest,
    ): BleGattInspection
}

private val canonicalBleUuid = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
)

internal fun requireCanonicalBleUuid(uuid: String) {
    require(canonicalBleUuid.matches(uuid)) { "BLE UUID must be canonical: $uuid" }
}
