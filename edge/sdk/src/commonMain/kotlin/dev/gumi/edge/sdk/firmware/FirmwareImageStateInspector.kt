package dev.gumi.edge.sdk.firmware

import dev.gumi.edge.sdk.EndpointCandidate

data class FirmwareProtocolReadRequest(
    val groupId: Int,
    val commandId: Int,
    val label: String,
) {
    init {
        require(groupId >= 0) { "Firmware protocol group ID must be non-negative" }
        require(commandId >= 0) { "Firmware protocol command ID must be non-negative" }
        require(label.isNotBlank()) { "Firmware protocol read label must not be blank" }
    }
}

/**
 * The on-air effects required by a semantic firmware-state read.
 *
 * BLE request/response protocols commonly require a request characteristic write and a temporary
 * notification subscription even when the device operation is read-only. Callers must disclose
 * these effects before executing the operation instead of describing it as an ATT/GATT-only read.
 */
data class FirmwareImageStateReadDisclosure(
    val protocol: String,
    val requestedAttMtu: Int?,
    val writesRequestCharacteristic: Boolean,
    val writesNotificationDescriptor: Boolean,
    val protocolReads: List<FirmwareProtocolReadRequest>,
    val persistentDeviceMutationExpected: Boolean,
) {
    init {
        require(protocol.isNotBlank()) { "Firmware management protocol must not be blank" }
        require(requestedAttMtu == null || requestedAttMtu in 23..517) {
            "Requested ATT MTU must be between 23 and 517"
        }
        require(protocolReads.isNotEmpty()) { "At least one semantic protocol read is required" }
    }
}

@JvmInline
value class FirmwareImageHash(val hex: String) {
    init {
        require(hex.isNotEmpty() && hex.length % 2 == 0 && LOWER_HEX.matches(hex)) {
            "Firmware image hash must be non-empty, even-length lowercase hex"
        }
    }

    companion object {
        private val LOWER_HEX = Regex("^[0-9a-f]+$")

        fun copyOf(bytes: ByteArray): FirmwareImageHash {
            require(bytes.isNotEmpty()) { "Firmware image hash bytes must not be empty" }
            return FirmwareImageHash(
                bytes.joinToString(separator = "") { byte ->
                    byte.toUByte().toString(radix = 16).padStart(2, '0')
                },
            )
        }
    }
}

data class FirmwareImageSlot(
    val imageNumber: Int,
    val slotNumber: Int,
    val version: String?,
    val hash: FirmwareImageHash?,
    val bootable: Boolean,
    val pending: Boolean,
    val confirmed: Boolean,
    val active: Boolean,
    val permanent: Boolean,
    val compressed: Boolean,
) {
    init {
        require(imageNumber >= 0) { "Firmware image number must be non-negative" }
        require(slotNumber >= 0) { "Firmware slot number must be non-negative" }
        require(version == null || version.isNotBlank()) { "Firmware version must not be blank" }
    }
}

data class FirmwareImageStateInspection(
    val endpoint: EndpointCandidate,
    val protocol: String,
    val slots: List<FirmwareImageSlot>,
    val splitStatus: Int?,
) {
    init {
        require(protocol.isNotBlank()) { "Firmware management protocol must not be blank" }
        require(slots.distinctBy { it.imageNumber to it.slotNumber }.size == slots.size) {
            "Firmware image state contains duplicate image/slot coordinates"
        }
    }
}

enum class FirmwareImageStateInspectionFailureCode {
    ENDPOINT_EXPIRED,
    PERMISSION_DENIED,
    BLUETOOTH_UNAVAILABLE,
    TRANSPORT_FAILED,
    MALFORMED_RESPONSE,
}

class FirmwareImageStateInspectionException(
    val code: FirmwareImageStateInspectionFailureCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

interface FirmwareImageStateInspector {
    /** Exact transport effects a caller must present before invoking [inspect]. */
    val disclosure: FirmwareImageStateReadDisclosure

    /**
     * Reads only the current firmware image/slot state and releases the transport afterward.
     * The narrow port intentionally exposes no upload, test, confirm, erase, reset, or file API.
     */
    suspend fun inspect(endpoint: EndpointCandidate): FirmwareImageStateInspection
}
