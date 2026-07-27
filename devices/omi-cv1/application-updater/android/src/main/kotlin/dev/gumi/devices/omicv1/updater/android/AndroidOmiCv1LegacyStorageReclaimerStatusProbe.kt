package dev.gumi.devices.omicv1.updater.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.gumi.edge.platforms.android.ble.AndroidBleEndpointDirectory
import dev.gumi.edge.sdk.EndpointCandidate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Android BLE adapter for the reclaimer's single read-only evidence characteristic. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class AndroidOmiCv1LegacyStorageReclaimerStatusProbe(
    context: Context,
    private val directory: AndroidBleEndpointDirectory,
) : OmiCv1LegacyStorageReclaimerStatusProbe {
    private val applicationContext = context.applicationContext

    override suspend fun inspect(
        endpoint: EndpointCandidate,
    ): OmiCv1LegacyStorageReclaimerStatusEvidence {
        if (!applicationContext.hasLegacyReclaimerGattPermission()) {
            throw failure(
                OmiCv1ApplicationUpdateFailureCode.PERMISSION_DENIED,
                "Android Nearby Devices permission is required for reclaimer validation",
            )
        }
        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            throw failure(
                OmiCv1ApplicationUpdateFailureCode.BLUETOOTH_UNAVAILABLE,
                "Bluetooth is unavailable or disabled",
            )
        }
        val device = directory.resolve(endpoint) ?: throw failure(
            OmiCv1ApplicationUpdateFailureCode.ENDPOINT_EXPIRED,
            "The process-local Omi endpoint expired before reclaimer validation",
        )
        return try {
            withTimeout(RECLAIMER_GATT_TIMEOUT_MILLIS) {
                inspect(device)
            }
        } catch (error: TimeoutCancellationException) {
            throw failure(
                OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED,
                "Legacy-storage reclaimer GATT validation timed out",
                error,
            )
        }
    }

    private suspend fun inspect(
        device: BluetoothDevice,
    ): OmiCv1LegacyStorageReclaimerStatusEvidence =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            var connection: BluetoothGatt? = null
            var discoveredServices: Set<String> = emptySet()
            var reclaimerCharacteristics: Set<String> = emptySet()
            var familyIdentityServiceHasCharacteristics = false

            fun closeConnection(gatt: BluetoothGatt?) {
                if (gatt == null) return
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }

            fun fail(
                message: String,
                code: OmiCv1ApplicationUpdateFailureCode =
                    OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED,
            ) {
                if (!completed.compareAndSet(false, true)) return
                closeConnection(connection)
                if (continuation.isActive) {
                    continuation.resumeWithException(failure(code, message))
                }
            }

            fun complete(statusBytes: ByteArray) {
                if (!completed.compareAndSet(false, true)) return
                val result = runCatching {
                    OmiCv1LegacyStorageReclaimerStatusProtocol.validate(
                        statusBytes = statusBytes,
                        discoveredServiceUuids = discoveredServices,
                        reclaimerCharacteristicUuids = reclaimerCharacteristics,
                        familyIdentityServiceHasCharacteristics =
                            familyIdentityServiceHasCharacteristics,
                    )
                }
                closeConnection(connection)
                if (!continuation.isActive) return
                result.fold(continuation::resume, continuation::resumeWithException)
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Reclaimer GATT connection failed with status $status")
                    } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (!gatt.discoverServices()) {
                            fail("Reclaimer GATT service discovery did not start")
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        fail("Reclaimer GATT disconnected before evidence was read")
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Reclaimer GATT service discovery failed with status $status")
                        return
                    }
                    discoveredServices = gatt.services.mapTo(mutableSetOf()) {
                        it.uuid.toString().lowercase()
                    }
                    familyIdentityServiceHasCharacteristics =
                        gatt.getService(FAMILY_IDENTITY_SERVICE_UUID)
                            ?.characteristics
                            ?.isNotEmpty() == true
                    val service = gatt.getService(RECLAIMER_SERVICE_UUID)
                    reclaimerCharacteristics = service?.characteristics?.mapTo(mutableSetOf()) {
                        it.uuid.toString().lowercase()
                    } ?: emptySet()
                    val characteristic = service?.getCharacteristic(STATUS_CHARACTERISTIC_UUID)
                    if (characteristic == null) {
                        fail(
                            "Legacy-storage reclaimer status characteristic is absent",
                            OmiCv1ApplicationUpdateFailureCode
                                .LEGACY_STORAGE_RECLAIMER_EVIDENCE_REJECTED,
                        )
                    } else if (
                        characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0
                    ) {
                        fail(
                            "Legacy-storage reclaimer status characteristic is not readable",
                            OmiCv1ApplicationUpdateFailureCode
                                .LEGACY_STORAGE_RECLAIMER_EVIDENCE_REJECTED,
                        )
                    } else if (!gatt.readCharacteristic(characteristic)) {
                        fail("Legacy-storage reclaimer status read did not start")
                    }
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (characteristic.uuid != STATUS_CHARACTERISTIC_UUID) return
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        complete(characteristic.value?.copyOf() ?: byteArrayOf())
                    } else {
                        fail("Legacy-storage reclaimer status read failed with status $status")
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    if (characteristic.uuid != STATUS_CHARACTERISTIC_UUID) return
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        complete(value.copyOf())
                    } else {
                        fail("Legacy-storage reclaimer status read failed with status $status")
                    }
                }
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) closeConnection(connection)
            }
            try {
                connection = device.connectGatt(
                    applicationContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                )
            } catch (error: SecurityException) {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(
                        failure(
                            OmiCv1ApplicationUpdateFailureCode.PERMISSION_DENIED,
                            "Android rejected reclaimer GATT permission",
                            error,
                        ),
                    )
                }
            }
        }

    private fun failure(
        code: OmiCv1ApplicationUpdateFailureCode,
        message: String,
        cause: Throwable? = null,
    ) = OmiCv1ApplicationUpdateException(code, message, cause)

    private companion object {
        val RECLAIMER_SERVICE_UUID: UUID =
            UUID.fromString(OmiCv1LegacyStorageReclaimerStatusProtocol.SERVICE_UUID)
        val STATUS_CHARACTERISTIC_UUID: UUID =
            UUID.fromString(OmiCv1LegacyStorageReclaimerStatusProtocol.STATUS_CHARACTERISTIC_UUID)
        val FAMILY_IDENTITY_SERVICE_UUID: UUID =
            UUID.fromString(OmiCv1LegacyStorageReclaimerStatusProtocol.FAMILY_IDENTITY_SERVICE_UUID)
        const val RECLAIMER_GATT_TIMEOUT_MILLIS = 15_000L
    }
}

private fun Context.hasLegacyReclaimerGattPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
