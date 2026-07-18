package dev.gumi.edge.sdk.ble

import dev.gumi.edge.sdk.EndpointCandidate
import kotlinx.coroutines.flow.Flow

enum class BleScanMode {
    LOW_POWER,
    BALANCED,
    LOW_LATENCY,
}

data class BleScanRequest(
    val serviceUuids: Set<String> = emptySet(),
    val advertisedNamePrefix: String? = null,
    val mode: BleScanMode = BleScanMode.BALANCED,
) {
    init {
        require(advertisedNamePrefix == null || advertisedNamePrefix.isNotBlank()) {
            "An advertised-name prefix cannot be blank"
        }
    }
}

data class BleAdvertisement(
    val endpoint: EndpointCandidate,
    val rssi: Int,
    val connectable: Boolean?,
    val txPower: Int?,
    val serviceDataLengths: Map<String, Int>,
    val manufacturerDataLengths: Map<Int, Int>,
    val observedAtMonotonicNanos: Long,
)

enum class BleScanFailureCode {
    PERMISSION_DENIED,
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_DISABLED,
    PLATFORM_SCAN_FAILED,
}

sealed interface BleScanEvent {
    data class Advertisement(val value: BleAdvertisement) : BleScanEvent

    data class Failure(
        val code: BleScanFailureCode,
        val detail: String,
        val platformCode: Int? = null,
    ) : BleScanEvent
}

interface BleScanner {
    /**
     * Returns a cold, cancellable scan. Collection owns the platform scan lease; cancellation must
     * release it. The flow never emits a stable semantic device identity or raw advertisement bytes.
     */
    fun scan(request: BleScanRequest): Flow<BleScanEvent>
}
