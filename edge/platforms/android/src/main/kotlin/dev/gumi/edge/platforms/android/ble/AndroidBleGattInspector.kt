package dev.gumi.edge.platforms.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.ble.BleBondState
import dev.gumi.edge.sdk.ble.BleGattAttributePermission
import dev.gumi.edge.sdk.ble.BleGattCharacteristic
import dev.gumi.edge.sdk.ble.BleGattCharacteristicProperty
import dev.gumi.edge.sdk.ble.BleGattDescriptor
import dev.gumi.edge.sdk.ble.BleGattInspection
import dev.gumi.edge.sdk.ble.BleGattInspectionException
import dev.gumi.edge.sdk.ble.BleGattInspectionFailureCode
import dev.gumi.edge.sdk.ble.BleGattInspectionRequest
import dev.gumi.edge.sdk.ble.BleGattInspector
import dev.gumi.edge.sdk.ble.BleGattReadFailureCode
import dev.gumi.edge.sdk.ble.BleGattReadResult
import dev.gumi.edge.sdk.ble.BleGattReadTarget
import dev.gumi.edge.sdk.ble.BleGattService
import dev.gumi.edge.sdk.ble.BleGattValue
import dev.gumi.edge.sdk.ble.BleLinkSnapshot
import dev.gumi.edge.sdk.ble.BlePhy
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend

class AndroidBleGattInspector(
    context: Context,
    private val endpointDirectory: AndroidBleEndpointDirectory,
) : BleGattInspector {
    private val applicationContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override suspend fun inspect(
        endpoint: EndpointCandidate,
        request: BleGattInspectionRequest,
    ): BleGattInspection {
        require(endpoint.transport == TransportKind.BLE) {
            "Android BLE inspection requires a BLE endpoint"
        }
        if (!applicationContext.hasBleConnectionPermission()) {
            throw BleGattInspectionException(
                BleGattInspectionFailureCode.PERMISSION_DENIED,
                "Android Nearby Devices permission is required",
            )
        }

        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            throw BleGattInspectionException(
                BleGattInspectionFailureCode.BLUETOOTH_UNAVAILABLE,
                "Bluetooth is unavailable or disabled",
            )
        }
        val device = endpointDirectory.resolve(endpoint.ephemeralId)
            ?: throw BleGattInspectionException(
                BleGattInspectionFailureCode.ENDPOINT_EXPIRED,
                "The ephemeral BLE endpoint expired; scan again",
            )

        val manager = ReadOnlyInspectionManager(applicationContext)
        try {
            manager.connect(device)
                .useAutoConnect(false)
                .retry(2, 200)
                .timeout(request.connectionTimeoutMillis)
                .suspend()

            val reads = request.reads
                .sortedWith(compareBy(BleGattReadTarget::serviceUuid, BleGattReadTarget::characteristicUuid))
                .map { target -> manager.read(target) }
            val phy = manager.readCurrentPhy()

            return BleGattInspection(
                endpoint = endpoint,
                services = manager.services,
                reads = reads,
                link = BleLinkSnapshot(
                    mtu = manager.currentMtu,
                    txPhy = phy?.first,
                    rxPhy = phy?.second,
                    bondState = device.toBondState(),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: BleGattInspectionException) {
            throw error
        } catch (error: SecurityException) {
            throw BleGattInspectionException(
                BleGattInspectionFailureCode.PERMISSION_DENIED,
                "Android rejected BLE connection permission",
            )
        } catch (error: Exception) {
            throw BleGattInspectionException(
                BleGattInspectionFailureCode.CONNECTION_FAILED,
                "Android BLE connection or discovery failed (${error::class.simpleName})",
            )
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    manager.disconnect().timeout(DISCONNECT_TIMEOUT_MILLIS).suspend()
                }
                manager.close()
            }
        }
    }

    private companion object {
        const val DISCONNECT_TIMEOUT_MILLIS = 5_000L
    }
}

private class ReadOnlyInspectionManager(context: Context) : BleManager(context) {
    @Volatile
    private var characteristicsByTarget = emptyMap<BleGattReadTarget, BluetoothGattCharacteristic>()

    @Volatile
    var services: List<BleGattService> = emptyList()
        private set

    val currentMtu: Int get() = getMtu()

    override fun getMinLogPriority(): Int = Log.ASSERT

    override fun log(priority: Int, message: String) = Unit

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val characteristics = mutableMapOf<BleGattReadTarget, BluetoothGattCharacteristic>()
        services = gatt.services.map { service ->
            service.toSdkService { target, characteristic ->
                characteristics[target] = characteristic
            }
        }
        characteristicsByTarget = characteristics
        return true
    }

    override fun initialize() = Unit

    override fun onServicesInvalidated() {
        characteristicsByTarget = emptyMap()
    }

    suspend fun read(target: BleGattReadTarget): BleGattReadResult {
        val characteristic = characteristicsByTarget[target]
            ?: return BleGattReadResult.Failure(
                target,
                BleGattReadFailureCode.MISSING,
                "Characteristic was not discovered",
            )
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            return BleGattReadResult.Failure(
                target,
                BleGattReadFailureCode.NOT_READABLE,
                "Characteristic does not declare the read property",
            )
        }

        return try {
            val bytes = readCharacteristic(characteristic).suspend().value ?: byteArrayOf()
            BleGattReadResult.Success(target, BleGattValue.copyOf(bytes))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            BleGattReadResult.Failure(
                target,
                BleGattReadFailureCode.PLATFORM_FAILED,
                "Android GATT read failed (${error::class.simpleName})",
            )
        }
    }

    suspend fun readCurrentPhy(): Pair<BlePhy, BlePhy>? = try {
        readPhy().suspend().let { (tx, rx) -> tx.toSdkPhy() to rx.toSdkPhy() }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }
}

private fun BluetoothGattService.toSdkService(
    observe: (BleGattReadTarget, BluetoothGattCharacteristic) -> Unit,
): BleGattService {
    val serviceUuid = uuid.toString().lowercase()
    return BleGattService(
        uuid = serviceUuid,
        primary = type == BluetoothGattService.SERVICE_TYPE_PRIMARY,
        characteristics = characteristics.map { characteristic ->
            val characteristicUuid = characteristic.uuid.toString().lowercase()
            val target = BleGattReadTarget(serviceUuid, characteristicUuid)
            observe(target, characteristic)
            BleGattCharacteristic(
                serviceUuid = serviceUuid,
                uuid = characteristicUuid,
                properties = characteristic.properties.toCharacteristicProperties(),
                permissions = characteristic.permissions.toAttributePermissions(),
                descriptors = characteristic.descriptors.map { descriptor ->
                    BleGattDescriptor(
                        uuid = descriptor.uuid.toString().lowercase(),
                        permissions = descriptor.permissions.toAttributePermissions(),
                    )
                },
            )
        },
    )
}

private fun Int.toCharacteristicProperties(): Set<BleGattCharacteristicProperty> {
    val flags = this
    return buildSet {
        addIfFlag(
            flags,
            BluetoothGattCharacteristic.PROPERTY_BROADCAST,
            BleGattCharacteristicProperty.BROADCAST,
        )
        addIfFlag(flags, BluetoothGattCharacteristic.PROPERTY_READ, BleGattCharacteristicProperty.READ)
        addIfFlag(
            flags,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BleGattCharacteristicProperty.WRITE_WITHOUT_RESPONSE,
        )
        addIfFlag(flags, BluetoothGattCharacteristic.PROPERTY_WRITE, BleGattCharacteristicProperty.WRITE)
        addIfFlag(flags, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BleGattCharacteristicProperty.NOTIFY)
        addIfFlag(flags, BluetoothGattCharacteristic.PROPERTY_INDICATE, BleGattCharacteristicProperty.INDICATE)
        addIfFlag(
            flags,
            BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE,
            BleGattCharacteristicProperty.SIGNED_WRITE,
        )
        addIfFlag(
            flags,
            BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS,
            BleGattCharacteristicProperty.EXTENDED,
        )
    }
}

private fun Int.toAttributePermissions(): Set<BleGattAttributePermission> {
    val flags = this
    return buildSet {
        addIfFlag(flags, BluetoothGattCharacteristic.PERMISSION_READ, BleGattAttributePermission.READ)
        addIfFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            BleGattAttributePermission.READ_ENCRYPTED,
        )
        addIfFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM,
            BleGattAttributePermission.READ_ENCRYPTED_MITM,
        )
        addIfFlag(flags, BluetoothGattCharacteristic.PERMISSION_WRITE, BleGattAttributePermission.WRITE)
        addIfFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            BleGattAttributePermission.WRITE_ENCRYPTED,
        )
        addIfFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM,
            BleGattAttributePermission.WRITE_ENCRYPTED_MITM,
        )
        addIfFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED,
            BleGattAttributePermission.WRITE_SIGNED,
        )
        addIfFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM,
            BleGattAttributePermission.WRITE_SIGNED_MITM,
        )
    }
}

private fun <T> MutableSet<T>.addIfFlag(flags: Int, flag: Int, value: T) {
    if (flags and flag != 0) add(value)
}

private fun Int.toSdkPhy(): BlePhy = when (this) {
    BluetoothDevice.PHY_LE_1M -> BlePhy.LE_1M
    BluetoothDevice.PHY_LE_2M -> BlePhy.LE_2M
    BluetoothDevice.PHY_LE_CODED -> BlePhy.LE_CODED
    else -> BlePhy.UNKNOWN
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.toBondState(): BleBondState = when (bondState) {
    BluetoothDevice.BOND_NONE -> BleBondState.NOT_BONDED
    BluetoothDevice.BOND_BONDING -> BleBondState.BONDING
    BluetoothDevice.BOND_BONDED -> BleBondState.BONDED
    else -> BleBondState.UNKNOWN
}

private fun Context.hasBleConnectionPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
