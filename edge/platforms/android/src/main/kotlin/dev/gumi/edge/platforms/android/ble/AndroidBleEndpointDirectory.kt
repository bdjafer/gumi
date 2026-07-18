package dev.gumi.edge.platforms.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import java.util.UUID

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

    @SuppressLint("MissingPermission")
    internal fun observe(device: BluetoothDevice): String = synchronized(this) {
        entriesByAddress.getOrPut(device.address) {
            val endpointId = "ble:${UUID.randomUUID()}"
            devicesByEndpoint[endpointId] = device
            Entry(endpointId, device)
        }.ephemeralId
    }

    internal fun resolve(ephemeralId: String): BluetoothDevice? = synchronized(this) {
        devicesByEndpoint[ephemeralId]
    }
}
