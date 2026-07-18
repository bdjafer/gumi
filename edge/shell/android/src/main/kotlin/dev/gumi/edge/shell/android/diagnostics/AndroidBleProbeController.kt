package dev.gumi.edge.shell.android.diagnostics

import android.util.Log
import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.ble.BleScanEvent
import dev.gumi.edge.sdk.ble.BleScanMode
import dev.gumi.edge.sdk.ble.BleScanRequest
import dev.gumi.edge.sdk.ble.BleScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BleProbeDevice(
    internal val endpoint: EndpointCandidate,
    val advertisedName: String,
    val rssi: Int,
    val connectable: Boolean?,
    val serviceUuids: Set<String>,
    val serviceDataLengths: Map<String, Int>,
    val manufacturerDataLengths: Map<Int, Int>,
    val matchedDriver: String?,
    val matchConfidence: MatchConfidence?,
)

private val BleProbeDevice.endpointId: String get() = endpoint.ephemeralId

data class BleProbeState(
    val scanning: Boolean = false,
    val nearbyDeviceCount: Int = 0,
    val devices: List<BleProbeDevice> = emptyList(),
    val error: String? = null,
)

class AndroidBleProbeController(
    private val scanner: BleScanner,
    private val driverRegistry: DeviceDriverRegistry,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(BleProbeState())
    private val firstObservations = mutableSetOf<String>()
    private val observedEndpoints = mutableSetOf<String>()
    private var scanJob: Job? = null

    val state: StateFlow<BleProbeState> = mutableState.asStateFlow()

    fun start() {
        if (scanJob?.isActive == true) return

        observedEndpoints.clear()
        mutableState.update { it.copy(scanning = true, nearbyDeviceCount = 0, error = null) }
        scanJob = scope.launch {
            try {
                scanner.scan(
                    BleScanRequest(
                        mode = BleScanMode.LOW_LATENCY,
                    ),
                ).catch { error ->
                    mutableState.update {
                        it.copy(error = error.message ?: "Android BLE scan failed")
                    }
                }.collect { event ->
                    when (event) {
                        is BleScanEvent.Advertisement -> record(event)
                        is BleScanEvent.Failure -> mutableState.update {
                            it.copy(
                                scanning = false,
                                error = "${event.code}: ${event.detail}",
                            )
                        }
                    }
                }
            } finally {
                mutableState.update { it.copy(scanning = false) }
            }
        }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
        mutableState.update { it.copy(scanning = false) }
    }

    override fun close() {
        scope.cancel()
    }

    private fun record(event: BleScanEvent.Advertisement) {
        val advertisement = event.value
        if (observedEndpoints.add(advertisement.endpoint.ephemeralId)) {
            mutableState.update { current ->
                current.copy(nearbyDeviceCount = observedEndpoints.size)
            }
        }
        val selection = runCatching { driverRegistry.select(advertisement.endpoint) }.getOrNull()
            ?: return
        val device = BleProbeDevice(
            endpoint = advertisement.endpoint,
            advertisedName = advertisement.endpoint.advertisedName ?: "Unnamed Omi",
            rssi = advertisement.rssi,
            connectable = advertisement.connectable,
            serviceUuids = advertisement.endpoint.advertisedServiceUuids,
            serviceDataLengths = advertisement.serviceDataLengths,
            manufacturerDataLengths = advertisement.manufacturerDataLengths,
            matchedDriver = selection.provider.id.value,
            matchConfidence = selection.match.confidence,
        )

        mutableState.update { current ->
            val devicesById = current.devices.associateBy(BleProbeDevice::endpointId).toMutableMap()
            devicesById[device.endpointId] = device
            current.copy(
                devices = devicesById.values.sortedByDescending(BleProbeDevice::rssi),
                error = null,
            )
        }

        if (firstObservations.add(device.endpointId)) {
            Log.i(
                LOG_TAG,
                "Omi advertisement observed: " +
                    "name=${device.advertisedName}, " +
                    "rssi=${device.rssi}, " +
                    "connectable=${device.connectable}, " +
                    "services=${device.serviceUuids.sorted()}, " +
                    "serviceDataLengths=${device.serviceDataLengths}, " +
                    "manufacturerDataLengths=${device.manufacturerDataLengths}, " +
                    "driver=${device.matchedDriver}, " +
                    "confidence=${device.matchConfidence}",
            )
        }
    }

    private companion object {
        const val LOG_TAG = "GumiBleProbe"
    }
}
