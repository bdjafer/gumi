package dev.gumi.edge.platforms.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import java.util.UUID

/** Equality-only result for two observations made through one directory instance. */
enum class AndroidBleObservationComparison {
    SAME,
    CHANGED,
    INCONCLUSIVE,
}

/**
 * Process-local bridge between a redacted SDK endpoint and Android's BluetoothDevice. Stable BLE
 * addresses never cross the Android adapter boundary and disappear with this directory instance.
 */
class AndroidBleEndpointDirectory {
    private data class Entry(
        val ephemeralId: String,
        val device: BluetoothDevice,
    )

    private val entriesByAddress = mutableMapOf<String, Entry>()
    private val devicesByEndpoint = mutableMapOf<String, BluetoothDevice>()

    /**
     * Registers a platform observation without exposing the stable Android BLE address.
     *
     * This is public so device-specific Android adapters can share one process-local directory
     * with the generic scanner, GATT inspector, and transport implementations.
     */
    @SuppressLint("MissingPermission")
    fun observe(device: BluetoothDevice): String = synchronized(this) {
        entriesByAddress.getOrPut(device.address) {
            val endpointId = "ble:${UUID.randomUUID()}"
            devicesByEndpoint[endpointId] = device
            Entry(endpointId, device)
        }.ephemeralId
    }

    internal fun resolve(ephemeralId: String): BluetoothDevice? = synchronized(this) {
        devicesByEndpoint[ephemeralId]
    }

    /**
     * Resolves only a live endpoint minted by this directory.
     *
     * The raw device remains inside the Android adapter layer and is never rendered, logged, or
     * persisted. Non-BLE endpoints and observations from another directory fail closed.
     */
    fun resolve(endpoint: EndpointCandidate): BluetoothDevice? = synchronized(this) {
        if (endpoint.transport != TransportKind.BLE) return@synchronized null
        devicesByEndpoint[endpoint.ephemeralId]
    }

    /**
     * Compares only the Android scan addresses behind two process-local observations. Neither raw
     * address is returned, rendered, logged, or persisted. The result is transport evidence only:
     * it must never be treated as semantic device identity, ownership, bonding, or authority.
     *
     * [INCONCLUSIVE][AndroidBleObservationComparison.INCONCLUSIVE] is returned when either
     * observation was not created by this live directory. A new Activity creates a new directory,
     * so callers cannot carry this comparison across Activity or process replacement.
     */
    @SuppressLint("MissingPermission")
    fun compareProcessLocalObservations(
        baseline: EndpointCandidate,
        current: EndpointCandidate,
    ): AndroidBleObservationComparison = synchronized(this) {
        val baselineDevice = devicesByEndpoint[baseline.ephemeralId]
            ?: return@synchronized AndroidBleObservationComparison.INCONCLUSIVE
        val currentDevice = devicesByEndpoint[current.ephemeralId]
            ?: return@synchronized AndroidBleObservationComparison.INCONCLUSIVE

        try {
            compareProcessLocalBleObservationTokens(
                baselineDevice.address,
                currentDevice.address,
            )
        } catch (_: SecurityException) {
            AndroidBleObservationComparison.INCONCLUSIVE
        }
    }
}

/** Pure policy seam; production tokens are read and retained only inside this Android adapter. */
internal fun compareProcessLocalBleObservationTokens(
    baselineToken: String?,
    currentToken: String?,
): AndroidBleObservationComparison = when {
    baselineToken == null || currentToken == null -> AndroidBleObservationComparison.INCONCLUSIVE
    baselineToken == currentToken -> AndroidBleObservationComparison.SAME
    else -> AndroidBleObservationComparison.CHANGED
}
