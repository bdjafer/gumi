package dev.gumi.devices.omicv1.updater.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.gumi.devices.omicv1.OmiCv1DriverProvider
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.platforms.android.ble.AndroidBleEndpointDirectory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal data class OmiCv1FlashLabCandidate(
    internal val endpoint: EndpointCandidate,
    val advertisedName: String,
    val rssi: Int,
    val connectable: Boolean,
)

internal sealed interface OmiCv1FlashLabScanEvent {
    data class Candidate(val value: OmiCv1FlashLabCandidate) : OmiCv1FlashLabScanEvent
    data class Failure(val detail: String) : OmiCv1FlashLabScanEvent
}

internal fun interface OmiCv1FlashLabScanner {
    fun scan(): Flow<OmiCv1FlashLabScanEvent>
}

internal class AndroidOmiCv1FlashLabScanner(
    context: Context,
    private val directory: AndroidBleEndpointDirectory,
    private val driver: OmiCv1DriverProvider = OmiCv1DriverProvider(),
) : OmiCv1FlashLabScanner {
    private val applicationContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun scan(): Flow<OmiCv1FlashLabScanEvent> = callbackFlow {
        if (!applicationContext.hasFlashLabBlePermissions()) {
            trySend(OmiCv1FlashLabScanEvent.Failure("Nearby Devices permission is required"))
            close()
            return@callbackFlow
        }

        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
        val platformScanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (platformScanner == null) {
            trySend(OmiCv1FlashLabScanEvent.Failure("Bluetooth is disabled or unavailable"))
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.toExactOmiCandidate()?.let { candidate ->
                    trySend(OmiCv1FlashLabScanEvent.Candidate(candidate))
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(
                    OmiCv1FlashLabScanEvent.Failure(
                        "Android BLE scan failed with code $errorCode",
                    ),
                )
                close()
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

        try {
            platformScanner.startScan(emptyList(), settings, callback)
        } catch (_: SecurityException) {
            trySend(OmiCv1FlashLabScanEvent.Failure("Android rejected BLE scan permission"))
            close()
            return@callbackFlow
        } catch (error: IllegalStateException) {
            trySend(
                OmiCv1FlashLabScanEvent.Failure(
                    error.message ?: "Android BLE scanner is not ready",
                ),
            )
            close()
            return@callbackFlow
        }

        awaitClose {
            if (applicationContext.hasFlashLabBlePermissions()) {
                runCatching { platformScanner.stopScan(callback) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toExactOmiCandidate(): OmiCv1FlashLabCandidate? {
        if (!isConnectable) return null
        val advertisedName = scanRecord?.deviceName
        val services = scanRecord?.serviceUuids
            .orEmpty()
            .map { it.uuid.toString().lowercase() }
            .toSet()
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = directory.observe(device),
            advertisedServiceUuids = services,
            advertisedName = advertisedName,
        )
        if (driver.match(endpoint).confidence != MatchConfidence.EXACT) return null
        return OmiCv1FlashLabCandidate(
            endpoint = endpoint,
            advertisedName = advertisedName ?: "Omi CV1",
            rssi = rssi,
            connectable = true,
        )
    }
}

internal fun Context.hasFlashLabBlePermissions(): Boolean =
    requiredFlashLabBlePermissions().all { permission ->
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

internal fun requiredFlashLabBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
