package dev.gumi.edge.platforms.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.ble.BleAdvertisement
import dev.gumi.edge.sdk.ble.BleScanEvent
import dev.gumi.edge.sdk.ble.BleScanFailureCode
import dev.gumi.edge.sdk.ble.BleScanMode
import dev.gumi.edge.sdk.ble.BleScanRequest
import dev.gumi.edge.sdk.ble.BleScanner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AndroidBleScanner(
    context: Context,
    private val endpointDirectory: AndroidBleEndpointDirectory = AndroidBleEndpointDirectory(),
) : BleScanner {
    private val applicationContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun scan(request: BleScanRequest): Flow<BleScanEvent> = callbackFlow {
        if (!applicationContext.hasBleScanPermissions()) {
            trySend(
                BleScanEvent.Failure(
                    code = BleScanFailureCode.PERMISSION_DENIED,
                    detail = "Android Nearby Devices permission is required",
                ),
            )
            close()
            return@callbackFlow
        }

        val manager = applicationContext.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null) {
            trySend(
                BleScanEvent.Failure(
                    code = BleScanFailureCode.BLUETOOTH_UNAVAILABLE,
                    detail = "This Android host has no Bluetooth adapter",
                ),
            )
            close()
            return@callbackFlow
        }
        if (!adapter.isEnabled) {
            trySend(
                BleScanEvent.Failure(
                    code = BleScanFailureCode.BLUETOOTH_DISABLED,
                    detail = "Bluetooth is disabled",
                ),
            )
            close()
            return@callbackFlow
        }

        val platformScanner = adapter.bluetoothLeScanner
        if (platformScanner == null) {
            trySend(
                BleScanEvent.Failure(
                    code = BleScanFailureCode.BLUETOOTH_UNAVAILABLE,
                    detail = "Android did not provide a BLE scanner",
                ),
            )
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.toAdvertisement(request)?.let { advertisement ->
                    trySend(BleScanEvent.Advertisement(advertisement))
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result -> onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result) }
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(
                    BleScanEvent.Failure(
                        code = BleScanFailureCode.PLATFORM_SCAN_FAILED,
                        detail = scanFailureDetail(errorCode),
                        platformCode = errorCode,
                    ),
                )
                close()
            }
        }

        val filters = request.serviceUuids.map { uuid ->
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(uuid))
                .build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(request.mode.toPlatformScanMode())
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

        try {
            platformScanner.startScan(filters, settings, callback)
        } catch (error: SecurityException) {
            trySend(
                BleScanEvent.Failure(
                    code = BleScanFailureCode.PERMISSION_DENIED,
                    detail = "Android rejected BLE scan permission",
                ),
            )
            close()
            return@callbackFlow
        } catch (error: IllegalStateException) {
            trySend(
                BleScanEvent.Failure(
                    code = BleScanFailureCode.BLUETOOTH_UNAVAILABLE,
                    detail = error.message ?: "BLE scanner is not ready",
                ),
            )
            close()
            return@callbackFlow
        }

        awaitClose {
            if (applicationContext.hasBleScanPermissions()) {
                runCatching { platformScanner.stopScan(callback) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toAdvertisement(request: BleScanRequest): BleAdvertisement? {
        val record = scanRecord
        val advertisedName = record?.deviceName
        val namePrefix = request.advertisedNamePrefix
        if (namePrefix != null &&
            advertisedName?.startsWith(namePrefix, ignoreCase = true) != true
        ) {
            return null
        }

        val endpointId = endpointDirectory.observe(device)
        val serviceUuids = record?.serviceUuids
            .orEmpty()
            .map { it.uuid.toString().lowercase() }
            .toSet()

        return BleAdvertisement(
            endpoint = EndpointCandidate(
                transport = TransportKind.BLE,
                ephemeralId = endpointId,
                advertisedServiceUuids = serviceUuids,
                advertisedName = advertisedName,
            ),
            rssi = rssi,
            connectable = isConnectable,
            txPower = txPower.takeUnless { it == ScanResult.TX_POWER_NOT_PRESENT },
            serviceDataLengths = record?.serviceData
                .orEmpty()
                .mapKeys { (uuid, _) -> uuid.uuid.toString().lowercase() }
                .mapValues { (_, bytes) -> bytes.size },
            manufacturerDataLengths = buildMap {
                val manufacturerData = record?.manufacturerSpecificData ?: return@buildMap
                for (index in 0 until manufacturerData.size()) {
                    put(manufacturerData.keyAt(index), manufacturerData.valueAt(index).size)
                }
            },
            observedAtMonotonicNanos = timestampNanos,
        )
    }
}

private fun Context.hasBleScanPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
} else {
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

private fun BleScanMode.toPlatformScanMode(): Int = when (this) {
    BleScanMode.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
    BleScanMode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
    BleScanMode.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
}

private fun scanFailureDetail(code: Int): String = when (code) {
    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "A BLE scan is already active"
    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android could not register the BLE scanner"
    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "The requested BLE scan mode is unsupported"
    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Android BLE scanning failed internally"
    ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Bluetooth controller scan resources are exhausted"
    ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Android rate-limited BLE scanning"
    else -> "Android BLE scan failed with code $code"
}
