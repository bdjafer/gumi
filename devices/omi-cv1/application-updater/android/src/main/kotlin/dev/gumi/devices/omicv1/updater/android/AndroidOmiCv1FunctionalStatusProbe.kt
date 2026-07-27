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
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.platforms.android.ble.AndroidBleEndpointDirectory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Android BLE adapter for one coherent read of functional status and capabilities. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class AndroidOmiCv1FunctionalStatusProbe(
    context: Context,
    private val directory: AndroidBleEndpointDirectory,
) : OmiCv1FunctionalStatusProbe {
    private val applicationContext = context.applicationContext

    override suspend fun inspect(endpoint: EndpointCandidate): OmiCv1FunctionalStatusEvidence {
        if (!applicationContext.hasFunctionalGattPermission()) {
            throw failure(
                OmiCv1ApplicationUpdateFailureCode.PERMISSION_DENIED,
                "Android Nearby Devices permission is required for functional validation",
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
            "The process-local Omi endpoint expired before functional validation",
        )
        return try {
            withTimeout(FUNCTIONAL_GATT_TIMEOUT_MILLIS) {
                inspect(device)
            }
        } catch (error: TimeoutCancellationException) {
            throw failure(
                OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED,
                "Functional GATT validation timed out",
                error,
            )
        }
    }

    private suspend fun inspect(device: BluetoothDevice): OmiCv1FunctionalStatusEvidence =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            var connection: BluetoothGatt? = null
            var stage = Stage.NEGOTIATING_MTU
            var discoveredServices: Set<String> = emptySet()
            var familyIdentityServiceHasCharacteristics = false
            var statusBytes: ByteArray? = null

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

            fun complete(capabilitiesBytes: ByteArray) {
                if (!completed.compareAndSet(false, true)) return
                val result = runCatching {
                    OmiCv1FunctionalStatusProtocol.validate(
                        statusBytes = requireNotNull(statusBytes) {
                            "Functional status was not read before capabilities"
                        },
                        capabilitiesBytes = capabilitiesBytes,
                        discoveredServiceUuids = discoveredServices,
                        familyIdentityServiceHasCharacteristics =
                            familyIdentityServiceHasCharacteristics,
                    )
                }
                closeConnection(connection)
                if (!continuation.isActive) return
                result.fold(continuation::resume, continuation::resumeWithException)
            }

            fun readCapabilities(gatt: BluetoothGatt) {
                val characteristic = gatt.getService(FUNCTIONAL_SERVICE_UUID)
                    ?.getCharacteristic(CAPABILITIES_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    fail(
                        "Functional capabilities characteristic is absent",
                        OmiCv1ApplicationUpdateFailureCode.FUNCTIONAL_EVIDENCE_REJECTED,
                    )
                } else if (
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0
                ) {
                    fail(
                        "Functional capabilities characteristic is not readable",
                        OmiCv1ApplicationUpdateFailureCode.FUNCTIONAL_EVIDENCE_REJECTED,
                    )
                } else {
                    stage = Stage.READING_CAPABILITIES
                    if (!gatt.readCharacteristic(characteristic)) {
                        fail("Functional capabilities read did not start")
                    }
                }
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Functional GATT connection failed with status $status")
                    } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                        /*
                         * The firmware snapshots each ATT read independently. Require the complete
                         * 40-byte value in one response so a transition cannot create torn evidence.
                         */
                        if (!gatt.requestMtu(REQUESTED_MTU)) {
                            fail("Functional coherent-status MTU request did not start")
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        fail("Functional GATT disconnected before evidence was read")
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS || mtu < MINIMUM_COHERENT_MTU) {
                        fail(
                            "Functional status requires ATT MTU >= $MINIMUM_COHERENT_MTU; " +
                                "negotiated $mtu with status $status",
                            OmiCv1ApplicationUpdateFailureCode.FUNCTIONAL_EVIDENCE_REJECTED,
                        )
                    } else if (stage == Stage.NEGOTIATING_MTU) {
                        stage = Stage.DISCOVERING
                        if (!gatt.discoverServices()) {
                            fail("Functional GATT service discovery did not start")
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (stage != Stage.DISCOVERING) return
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Functional GATT service discovery failed with status $status")
                        return
                    }
                    discoveredServices = gatt.services.mapTo(mutableSetOf()) {
                        it.uuid.toString().lowercase()
                    }
                    familyIdentityServiceHasCharacteristics =
                        gatt.getService(FAMILY_IDENTITY_SERVICE_UUID)
                            ?.characteristics
                            ?.isNotEmpty() == true
                    val characteristic = gatt.getService(FUNCTIONAL_SERVICE_UUID)
                        ?.getCharacteristic(STATUS_CHARACTERISTIC_UUID)
                    if (characteristic == null) {
                        fail(
                            "Functional status characteristic is absent",
                            OmiCv1ApplicationUpdateFailureCode.FUNCTIONAL_EVIDENCE_REJECTED,
                        )
                    } else if (
                        characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0
                    ) {
                        fail(
                            "Functional status characteristic is not readable",
                            OmiCv1ApplicationUpdateFailureCode.FUNCTIONAL_EVIDENCE_REJECTED,
                        )
                    } else {
                        stage = Stage.READING_STATUS
                        if (!gatt.readCharacteristic(characteristic)) {
                            fail("Functional status read did not start")
                        }
                    }
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    consumeRead(gatt, characteristic, characteristic.value?.copyOf() ?: byteArrayOf(), status)
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    consumeRead(gatt, characteristic, value.copyOf(), status)
                }

                private fun consumeRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Functional characteristic read failed with status $status")
                        return
                    }
                    when {
                        stage == Stage.READING_STATUS &&
                            characteristic.uuid == STATUS_CHARACTERISTIC_UUID -> {
                            statusBytes = value
                            readCapabilities(gatt)
                        }

                        stage == Stage.READING_CAPABILITIES &&
                            characteristic.uuid == CAPABILITIES_CHARACTERISTIC_UUID ->
                            complete(value)
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
                            "Android rejected functional GATT permission",
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

    private enum class Stage {
        NEGOTIATING_MTU,
        DISCOVERING,
        READING_STATUS,
        READING_CAPABILITIES,
    }

    private companion object {
        val FUNCTIONAL_SERVICE_UUID: UUID =
            UUID.fromString(OmiCv1FunctionalStatusProtocol.SERVICE_UUID)
        val STATUS_CHARACTERISTIC_UUID: UUID =
            UUID.fromString(OmiCv1FunctionalStatusProtocol.STATUS_CHARACTERISTIC_UUID)
        val CAPABILITIES_CHARACTERISTIC_UUID: UUID =
            UUID.fromString(OmiCv1FunctionalStatusProtocol.CAPABILITIES_CHARACTERISTIC_UUID)
        val FAMILY_IDENTITY_SERVICE_UUID: UUID =
            UUID.fromString(OmiCv1FunctionalStatusProtocol.FAMILY_IDENTITY_SERVICE_UUID)
        const val REQUESTED_MTU = 247
        const val MINIMUM_COHERENT_MTU =
            OmiCv1FunctionalStatusProtocol.STATUS_WIRE_SIZE + 3
        const val FUNCTIONAL_GATT_TIMEOUT_MILLIS = 15_000L
    }
}

private fun Context.hasFunctionalGattPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
