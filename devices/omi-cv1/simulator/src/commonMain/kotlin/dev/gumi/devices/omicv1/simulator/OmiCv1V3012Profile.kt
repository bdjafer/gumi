package dev.gumi.devices.omicv1.simulator

import dev.gumi.devices.omicv1.OmiCv1GattProfile
import dev.gumi.devices.omicv1.OmiCv1Protocol
import dev.gumi.edge.sdk.ble.BleGattAttributePermission
import dev.gumi.edge.sdk.ble.BleGattCharacteristic
import dev.gumi.edge.sdk.ble.BleGattCharacteristicProperty
import dev.gumi.edge.sdk.ble.BleGattService

object OmiCv1V3012Profile {
    const val GENERIC_ATTRIBUTE_SERVICE = "00001801-0000-1000-8000-00805f9b34fb"
    const val GENERIC_ACCESS_SERVICE = "00001800-0000-1000-8000-00805f9b34fb"
    const val SMP_SERVICE = "8d53dc1d-1db7-4cd3-868b-8a527460aa84"
    const val SMP_CHARACTERISTIC = "da2e7828-fbce-4e01-ae9e-261174997c48"
    const val BUTTON_SERVICE = "23ba7924-0000-1000-7450-346eac492e92"
    const val BUTTON_CHARACTERISTIC = "23ba7925-0000-1000-7450-346eac492e92"
    const val HAPTIC_SERVICE = "cab1ab95-2ea5-4f4d-bb56-874b72cfc984"
    const val HAPTIC_CHARACTERISTIC = "cab1ab96-2ea5-4f4d-bb56-874b72cfc984"
    const val AUDIO_CHARACTERISTIC = "19b10001-e8f2-537e-4f6c-d104768a1214"
    const val AUDIO_CODEC_CHARACTERISTIC = "19b10002-e8f2-537e-4f6c-d104768a1214"
    const val SETTINGS_SERVICE = "19b10010-e8f2-537e-4f6c-d104768a1214"
    const val LED_DIM_CHARACTERISTIC = "19b10011-e8f2-537e-4f6c-d104768a1214"
    const val MIC_GAIN_CHARACTERISTIC = "19b10012-e8f2-537e-4f6c-d104768a1214"
    const val FEATURES_SERVICE = "19b10020-e8f2-537e-4f6c-d104768a1214"
    const val FEATURES_CHARACTERISTIC = "19b10021-e8f2-537e-4f6c-d104768a1214"
    const val STORAGE_CONTROL = "30295781-4301-eabd-2904-2849adfeae43"

    const val GENERIC_ACCESS_DEVICE_NAME = "00002a00-0000-1000-8000-00805f9b34fb"
    const val GENERIC_ACCESS_APPEARANCE = "00002a01-0000-1000-8000-00805f9b34fb"
    const val GENERIC_ACCESS_CONNECTION_PARAMETERS = "00002a04-0000-1000-8000-00805f9b34fb"
    const val GATT_SERVICE_CHANGED = "00002a05-0000-1000-8000-00805f9b34fb"
    const val GATT_CLIENT_SUPPORTED_FEATURES = "00002b29-0000-1000-8000-00805f9b34fb"
    const val GATT_DATABASE_HASH = "00002b2a-0000-1000-8000-00805f9b34fb"

    val services: List<BleGattService> = listOf(
        service(
            GENERIC_ATTRIBUTE_SERVICE,
            characteristic(GATT_SERVICE_CHANGED, "indicate"),
            characteristic(GATT_CLIENT_SUPPORTED_FEATURES, "read", "write"),
            characteristic(GATT_DATABASE_HASH, "read"),
        ),
        service(
            GENERIC_ACCESS_SERVICE,
            characteristic(GENERIC_ACCESS_DEVICE_NAME, "read"),
            characteristic(GENERIC_ACCESS_APPEARANCE, "read"),
            characteristic(GENERIC_ACCESS_CONNECTION_PARAMETERS, "read"),
        ),
        service(
            OmiCv1GattProfile.BATTERY_SERVICE,
            characteristic(OmiCv1GattProfile.BATTERY_LEVEL, "read", "notify"),
        ),
        service(
            OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
            characteristic(OmiCv1GattProfile.MODEL_NUMBER, "read"),
            characteristic(OmiCv1GattProfile.MANUFACTURER_NAME, "read"),
            characteristic(OmiCv1GattProfile.FIRMWARE_REVISION, "read"),
            characteristic(OmiCv1GattProfile.HARDWARE_REVISION, "read"),
        ),
        service(SMP_SERVICE, characteristic(SMP_CHARACTERISTIC, "write_without_response", "notify")),
        service(BUTTON_SERVICE, characteristic(BUTTON_CHARACTERISTIC, "read", "notify")),
        service(HAPTIC_SERVICE, characteristic(HAPTIC_CHARACTERISTIC, "write")),
        service(
            OmiCv1Protocol.AUDIO_SERVICE_UUID,
            characteristic(AUDIO_CHARACTERISTIC, "read", "notify"),
            characteristic(AUDIO_CODEC_CHARACTERISTIC, "read"),
        ),
        service(
            SETTINGS_SERVICE,
            characteristic(LED_DIM_CHARACTERISTIC, "read", "write"),
            characteristic(MIC_GAIN_CHARACTERISTIC, "read", "write"),
        ),
        service(FEATURES_SERVICE, characteristic(FEATURES_CHARACTERISTIC, "read")),
        service(
            OmiCv1GattProfile.STORAGE_SERVICE,
            characteristic(STORAGE_CONTROL, "write", "notify"),
            characteristic(OmiCv1GattProfile.STORAGE_STATUS, "read", "notify"),
        ),
    )

    private fun service(
        uuid: String,
        vararg characteristics: Pair<String, Set<BleGattCharacteristicProperty>>,
    ) = BleGattService(
        uuid = uuid,
        primary = true,
        characteristics = characteristics.map { (characteristicUuid, properties) ->
            BleGattCharacteristic(
                serviceUuid = uuid,
                uuid = characteristicUuid,
                properties = properties,
                permissions = properties.mapNotNullTo(linkedSetOf()) { property ->
                    when (property) {
                        BleGattCharacteristicProperty.READ -> BleGattAttributePermission.READ
                        BleGattCharacteristicProperty.WRITE,
                        BleGattCharacteristicProperty.WRITE_WITHOUT_RESPONSE,
                        -> BleGattAttributePermission.WRITE
                        else -> null
                    }
                },
                descriptors = emptyList(),
            )
        },
    )

    private fun characteristic(
        uuid: String,
        vararg properties: String,
    ): Pair<String, Set<BleGattCharacteristicProperty>> = uuid to properties.mapTo(linkedSetOf()) {
        when (it) {
            "read" -> BleGattCharacteristicProperty.READ
            "write" -> BleGattCharacteristicProperty.WRITE
            "write_without_response" -> BleGattCharacteristicProperty.WRITE_WITHOUT_RESPONSE
            "notify" -> BleGattCharacteristicProperty.NOTIFY
            "indicate" -> BleGattCharacteristicProperty.INDICATE
            else -> error("Unsupported simulator property $it")
        }
    }
}
