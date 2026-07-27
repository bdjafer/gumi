package dev.gumi.devices.omicv1.updater.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.platforms.android.ble.AndroidBleEndpointDirectory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Closed Android GATT adapter for media-free capture-port qualification. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class AndroidOmiCv1CaptureSelftestProbe(
    context: Context,
    private val directory: AndroidBleEndpointDirectory,
) : OmiCv1CaptureSelftestProbe, OmiCv1CaptureSelftestRunner {
    private val applicationContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override suspend fun inspect(endpoint: EndpointCandidate): OmiCv1CaptureSelftestEvidence =
        execute(endpoint = endpoint, arm = false, onArmed = {})

    override suspend fun run(
        endpoint: EndpointCandidate,
        onArmed: (OmiCv1CaptureSelftestEvidence) -> Unit,
    ): OmiCv1CaptureSelftestEvidence = execute(endpoint = endpoint, arm = true, onArmed = onArmed)

    private suspend fun execute(
        endpoint: EndpointCandidate,
        arm: Boolean,
        onArmed: (OmiCv1CaptureSelftestEvidence) -> Unit,
    ): OmiCv1CaptureSelftestEvidence {
        if (!applicationContext.hasCaptureSelftestGattPermission()) {
            throw failure(
                OmiCv1ApplicationUpdateFailureCode.PERMISSION_DENIED,
                "Android Nearby Devices permission is required for capture self-test",
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
            "The process-local Omi endpoint expired before capture self-test",
        )
        return try {
            withTimeout(if (arm) RUN_TIMEOUT_MILLIS else INSPECT_TIMEOUT_MILLIS) {
                transact(device, arm, onArmed)
            }
        } catch (error: TimeoutCancellationException) {
            throw failure(
                OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED,
                if (arm) {
                    "Capture self-test timed out; inspect the Omi privacy LED and recover before retry"
                } else {
                    "Capture self-test GATT inspection timed out"
                },
                error,
            )
        }
    }

    private suspend fun transact(
        device: BluetoothDevice,
        arm: Boolean,
        onArmed: (OmiCv1CaptureSelftestEvidence) -> Unit,
    ): OmiCv1CaptureSelftestEvidence = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        var connection: BluetoothGatt? = null
        var stage = Stage.NEGOTIATING_MTU
        var discoveredServices: Set<String> = emptySet()
        var stockIdentityServiceHasCharacteristics = false
        var statusCharacteristic: BluetoothGattCharacteristic? = null
        var armCharacteristic: BluetoothGattCharacteristic? = null
        var baselineAttempt = -1L
        var armedReported = false
        var pendingNotification: ByteArray? = null
        val readPending = AtomicBoolean(false)

        fun closeConnection(gatt: BluetoothGatt?) {
            if (gatt == null) return
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }

        fun fail(error: Throwable) {
            if (!completed.compareAndSet(false, true)) return
            closeConnection(connection)
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        fun fail(
            message: String,
            code: OmiCv1ApplicationUpdateFailureCode =
                OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED,
        ) = fail(failure(code, message))

        fun complete(evidence: OmiCv1CaptureSelftestEvidence) {
            if (!completed.compareAndSet(false, true)) return
            closeConnection(connection)
            if (continuation.isActive) continuation.resume(evidence)
        }

        fun readStatus(gatt: BluetoothGatt, delayMillis: Long = 0L) {
            val characteristic = statusCharacteristic ?: run {
                fail("Capture self-test status characteristic disappeared")
                return
            }
            handler.postDelayed(
                {
                    if (!completed.get() && readPending.compareAndSet(false, true)) {
                        if (!gatt.readCharacteristic(characteristic)) {
                            readPending.set(false)
                            fail("Capture self-test status read did not start")
                        }
                    }
                },
                delayMillis,
            )
        }

        fun writeArm(gatt: BluetoothGatt) {
            val characteristic = armCharacteristic ?: run {
                fail("Capture self-test arm characteristic disappeared")
                return
            }
            stage = Stage.WRITING_ARM
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    OmiCv1CaptureSelftestProtocol.ARM_VALUE,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = OmiCv1CaptureSelftestProtocol.ARM_VALUE
                gatt.writeCharacteristic(characteristic)
            }
            if (!started) fail("Capture self-test arm write did not start")
        }

        fun consumeStatus(gatt: BluetoothGatt, bytes: ByteArray) {
            val evidence = try {
                OmiCv1CaptureSelftestProtocol.validate(
                    statusBytes = bytes,
                    discoveredServiceUuids = discoveredServices,
                    stockIdentityServiceHasCharacteristics =
                        stockIdentityServiceHasCharacteristics,
                )
            } catch (error: Throwable) {
                fail(error)
                return
            }
            when (stage) {
                Stage.READING_BASELINE -> {
                    if (!arm) {
                        complete(evidence)
                        return
                    }
                    try {
                        OmiCv1CaptureSelftestProtocol.requireSafeBaseline(evidence)
                    } catch (error: Throwable) {
                        fail(error)
                        return
                    }
                    baselineAttempt = evidence.attempt
                    writeArm(gatt)
                }

                Stage.POLLING -> {
                    when {
                        evidence.attempt == baselineAttempt -> Unit
                        evidence.attempt != baselineAttempt + 1L -> fail(
                            "Capture self-test attempt counter did not advance exactly once",
                            OmiCv1ApplicationUpdateFailureCode.CAPTURE_SELFTEST_EVIDENCE_REJECTED,
                        )
                        else -> {
                            if (!armedReported && evidence.phase == OmiCv1CaptureSelftestPhase.ARMED) {
                                armedReported = true
                                try {
                                    onArmed(evidence)
                                } catch (error: Throwable) {
                                    fail(error)
                                    return
                                }
                                TERMINAL_FALLBACK_READ_DELAYS_MILLIS.forEach { delay ->
                                    readStatus(gatt, delay)
                                }
                            }
                            if (evidence.phase.terminal) {
                                complete(evidence)
                            }
                        }
                    }
                }

                else -> fail("Capture self-test status arrived in invalid transaction stage $stage")
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Capture self-test GATT connection failed with status $status")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    /*
                     * The status value is 32 bytes. At the default 23-byte ATT MTU Android reads it
                     * in multiple requests, and a device-side phase transition between fragments
                     * can produce a torn payload. Require enough MTU for one coherent ATT response
                     * before reading any qualification evidence.
                     */
                    if (!gatt.requestMtu(REQUESTED_COHERENT_STATUS_MTU)) {
                        fail("Capture self-test coherent-status MTU request did not start")
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Capture self-test GATT disconnected before completion")
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (
                    status != BluetoothGatt.GATT_SUCCESS ||
                    mtu < MINIMUM_COHERENT_STATUS_MTU
                ) {
                    fail(
                        "Capture self-test requires ATT MTU >= $MINIMUM_COHERENT_STATUS_MTU " +
                            "for one coherent status read; negotiated $mtu with status $status",
                        OmiCv1ApplicationUpdateFailureCode.CAPTURE_SELFTEST_EVIDENCE_REJECTED,
                    )
                } else if (stage == Stage.NEGOTIATING_MTU) {
                    stage = Stage.DISCOVERING
                    if (!gatt.discoverServices()) {
                        fail("Capture self-test GATT service discovery did not start")
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (stage != Stage.DISCOVERING) {
                    fail(
                        "Capture self-test service discovery arrived in invalid transaction " +
                            "stage $stage",
                    )
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Capture self-test service discovery failed with status $status")
                    return
                }
                discoveredServices = gatt.services.mapTo(mutableSetOf()) {
                    it.uuid.toString().lowercase()
                }
                val identity = gatt.getService(STOCK_IDENTITY_SERVICE_UUID)
                stockIdentityServiceHasCharacteristics =
                    identity?.characteristics?.isNotEmpty() == true
                val service = gatt.getService(SELFTEST_SERVICE_UUID)
                val statusValue = service?.getCharacteristic(STATUS_CHARACTERISTIC_UUID)
                val armValue = service?.getCharacteristic(ARM_CHARACTERISTIC_UUID)
                if (
                    statusValue == null ||
                    statusValue.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0 ||
                    statusValue.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
                ) {
                    fail(
                        "Capture self-test status characteristic is absent or lacks read/notify",
                        OmiCv1ApplicationUpdateFailureCode.CAPTURE_SELFTEST_EVIDENCE_REJECTED,
                    )
                    return
                }
                if (
                    arm &&
                    (armValue == null ||
                        armValue.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0)
                ) {
                    fail(
                        "Capture self-test arm characteristic is absent or not acknowledged-write",
                        OmiCv1ApplicationUpdateFailureCode.CAPTURE_SELFTEST_EVIDENCE_REJECTED,
                    )
                    return
                }
                statusCharacteristic = statusValue
                armCharacteristic = armValue
                val notificationDescriptor =
                    statusValue.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                if (notificationDescriptor == null) {
                    fail(
                        "Capture self-test status notification descriptor is absent",
                        OmiCv1ApplicationUpdateFailureCode.CAPTURE_SELFTEST_EVIDENCE_REJECTED,
                    )
                    return
                }
                if (!gatt.setCharacteristicNotification(statusValue, true)) {
                    fail("Capture self-test local status notification enablement failed")
                    return
                }
                stage = Stage.ENABLING_NOTIFICATIONS
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        notificationDescriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    notificationDescriptor.value =
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(notificationDescriptor)
                }
                if (!started) {
                    fail("Capture self-test status notification descriptor write did not start")
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (
                    descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID ||
                    descriptor.characteristic.uuid != STATUS_CHARACTERISTIC_UUID
                ) {
                    return
                }
                if (stage != Stage.ENABLING_NOTIFICATIONS) {
                    fail(
                        "Capture self-test notification response arrived in invalid transaction " +
                            "stage $stage",
                    )
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Capture self-test notification enablement failed with status $status")
                } else {
                    stage = Stage.READING_BASELINE
                    readStatus(gatt)
                }
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (characteristic.uuid != STATUS_CHARACTERISTIC_UUID) return
                if (!readPending.compareAndSet(true, false)) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    consumeStatus(gatt, characteristic.value?.copyOf() ?: byteArrayOf())
                } else {
                    fail("Capture self-test status read failed with status $status")
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (characteristic.uuid != STATUS_CHARACTERISTIC_UUID) return
                if (!readPending.compareAndSet(true, false)) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    consumeStatus(gatt, value.copyOf())
                } else {
                    fail("Capture self-test status read failed with status $status")
                }
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid != STATUS_CHARACTERISTIC_UUID) return
                acceptNotification(gatt, characteristic.value?.copyOf() ?: byteArrayOf())
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid != STATUS_CHARACTERISTIC_UUID) return
                acceptNotification(gatt, value.copyOf())
            }

            private fun acceptNotification(gatt: BluetoothGatt, bytes: ByteArray) {
                when (stage) {
                    Stage.WRITING_ARM -> pendingNotification = bytes
                    Stage.POLLING -> consumeStatus(gatt, bytes)
                    else -> Unit
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (characteristic.uuid != ARM_CHARACTERISTIC_UUID) return
                if (stage != Stage.WRITING_ARM) {
                    fail("Capture self-test arm response arrived in invalid transaction stage $stage")
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Capture self-test arm write failed with status $status")
                } else {
                    stage = Stage.POLLING
                    pendingNotification?.also {
                        pendingNotification = null
                        consumeStatus(gatt, it)
                    }
                    handler.postDelayed(
                        {
                            if (!completed.get() && stage == Stage.POLLING && !armedReported) {
                                fail(
                                    "Capture self-test arm was not accepted within " +
                                        "${ARM_ACCEPTANCE_TIMEOUT_MILLIS}ms; release the button " +
                                        "and perform a fresh read-only status proof",
                                )
                            }
                        },
                        ARM_ACCEPTANCE_TIMEOUT_MILLIS,
                    )
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
            fail(
                failure(
                    OmiCv1ApplicationUpdateFailureCode.PERMISSION_DENIED,
                    "Android rejected capture self-test GATT permission",
                    error,
                ),
            )
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
        ENABLING_NOTIFICATIONS,
        READING_BASELINE,
        WRITING_ARM,
        POLLING,
    }

    private companion object {
        val SELFTEST_SERVICE_UUID: UUID = UUID.fromString(OmiCv1CaptureSelftestProtocol.SERVICE_UUID)
        val STATUS_CHARACTERISTIC_UUID: UUID =
            UUID.fromString(OmiCv1CaptureSelftestProtocol.STATUS_CHARACTERISTIC_UUID)
        val ARM_CHARACTERISTIC_UUID: UUID =
            UUID.fromString(OmiCv1CaptureSelftestProtocol.ARM_CHARACTERISTIC_UUID)
        val STOCK_IDENTITY_SERVICE_UUID: UUID =
            UUID.fromString(OmiCv1CaptureSelftestProtocol.STOCK_IDENTITY_SERVICE_UUID)
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val TERMINAL_FALLBACK_READ_DELAYS_MILLIS = longArrayOf(8_000L, 12_000L, 16_000L)
        const val ARM_ACCEPTANCE_TIMEOUT_MILLIS = 3_000L
        const val INSPECT_TIMEOUT_MILLIS = 15_000L
        const val RUN_TIMEOUT_MILLIS = 35_000L
        const val REQUESTED_COHERENT_STATUS_MTU = 64
        const val MINIMUM_COHERENT_STATUS_MTU =
            OmiCv1CaptureSelftestProtocol.STATUS_WIRE_SIZE + 1
    }
}

private fun Context.hasCaptureSelftestGattPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
