package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.NegotiatedDeviceSession
import dev.gumi.edge.sdk.OpaqueBytes
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.TransportEvent
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.ble.BleBondState
import dev.gumi.edge.sdk.ble.BleCharacteristicTarget
import dev.gumi.edge.sdk.ble.BleGattAttributePermission
import dev.gumi.edge.sdk.ble.BleGattCharacteristic
import dev.gumi.edge.sdk.ble.BleGattCharacteristicProperty
import dev.gumi.edge.sdk.ble.BleGattService
import dev.gumi.edge.sdk.ble.BleLinkSnapshot
import dev.gumi.edge.sdk.ble.BleSessionEvent
import dev.gumi.edge.sdk.ble.BleSubscription
import dev.gumi.edge.sdk.ble.BleTransportSession
import dev.gumi.edge.sdk.ble.BleWriteKind
import dev.gumi.edge.sdk.capability.capture.CaptureStateV1
import dev.gumi.edge.sdk.capability.capture.DeviceCaptureAvailability
import dev.gumi.edge.sdk.capability.capture.DeviceMicrophoneTruth
import dev.gumi.edge.sdk.capability.capture.DeviceRecordingTruth
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OmiCv1DriverTest {
    private val driver = OmiCv1DriverProvider()

    @Test
    fun `storage service UUID is an exact match`() {
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = "ble:fixture",
            advertisedServiceUuids = setOf(OmiCv1Protocol.OFFLINE_STORAGE_SERVICE_UUID.uppercase()),
        )

        assertEquals(MatchConfidence.EXACT, driver.match(endpoint).confidence)
        val metadata = driver.describe(endpoint)
        assertEquals("not-negotiated", metadata.protocolVersion)
        assertEquals(0, metadata.capabilities.size)
    }

    @Test
    fun `advertised audio service UUID is an exact match`() {
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = "ble:fixture",
            advertisedServiceUuids = setOf(OmiCv1Protocol.AUDIO_SERVICE_UUID),
        )

        assertEquals(MatchConfidence.EXACT, driver.match(endpoint).confidence)
    }

    @Test
    fun `advertised functional service is exact and negotiates read-only capture truth`() = runTest {
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = "ble:functional",
            advertisedServiceUuids = setOf(OmiCv1Protocol.FUNCTIONAL_SERVICE_UUID),
            advertisedName = "Gumi",
        )
        val transport = FunctionalTransport(endpoint)

        assertEquals(MatchConfidence.EXACT, driver.match(endpoint).confidence)
        val session = assertIs<NegotiatedDeviceSession>(driver.open(endpoint, transport))
        assertNull(session.deviceId)
        assertEquals(
            "gumi-functional/v1/gumi-functional-recording-0005/3.0.12",
            session.descriptor.protocolVersion,
        )
        val capture = assertNotNull(session.capabilities.handle(CaptureStateV1))
        assertEquals(true, capture.descriptor.localRecording)
        assertEquals(true, capture.descriptor.readOnly)
        assertFalse(capture.descriptor.liveMedia)
        assertFalse(capture.descriptor.mediaExport)
        assertFalse(capture.descriptor.semanticSignals)
        val state = capture.read()
        assertEquals(DeviceMicrophoneTruth.VERIFIED_OFF, state.microphone)
        assertEquals(DeviceRecordingTruth.INACTIVE, state.recording)
        assertEquals(DeviceCaptureAvailability.READY, state.availability)
        assertEquals(7UL, state.generation)
        assertNull(state.activeRecordingId)
        assertNull(state.observedAtMonotonicMillis)
    }

    @Test
    fun `name alone remains possible rather than exact`() {
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = "ble:fixture",
            advertisedName = "Omi",
        )

        assertEquals(MatchConfidence.POSSIBLE, driver.match(endpoint).confidence)
    }
}

private class FunctionalTransport(
    override val endpoint: EndpointCandidate,
) : BleTransportSession {
    override val link = BleLinkSnapshot(
        mtu = 247,
        txPhy = null,
        rxPhy = null,
        bondState = BleBondState.NOT_BONDED,
    )
    override val bleEvents = emptyFlow<BleSessionEvent>()
    override val events = emptyFlow<TransportEvent>()

    private val services = listOf(
        service(
            OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
            characteristic(
                OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
                OmiCv1GattProfile.MANUFACTURER_NAME,
            ),
            characteristic(
                OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
                OmiCv1GattProfile.MODEL_NUMBER,
            ),
            characteristic(
                OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
                OmiCv1GattProfile.FIRMWARE_REVISION,
            ),
            characteristic(
                OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
                OmiCv1GattProfile.SOFTWARE_REVISION,
            ),
        ),
        service(OmiCv1FunctionalGattV1.FAMILY_IDENTITY_SERVICE_UUID),
        service(
            OmiCv1FunctionalGattV1.SERVICE_UUID,
            characteristic(
                OmiCv1FunctionalGattV1.SERVICE_UUID,
                OmiCv1FunctionalGattV1.STATUS_CHARACTERISTIC_UUID,
                setOf(BleGattCharacteristicProperty.READ, BleGattCharacteristicProperty.NOTIFY),
            ),
            characteristic(
                OmiCv1FunctionalGattV1.SERVICE_UUID,
                OmiCv1FunctionalGattV1.CAPABILITIES_CHARACTERISTIC_UUID,
            ),
        ),
    )
    private val values = mapOf(
        OmiCv1Targets.manufacturer to "Gumi".encodeToByteArray(),
        OmiCv1Targets.model to "Omi CV 1".encodeToByteArray(),
        OmiCv1Targets.firmware to "3.0.12".encodeToByteArray(),
        OmiCv1FunctionalTargets.software to
            "gumi-functional-recording-0005".encodeToByteArray(),
        OmiCv1FunctionalTargets.capabilities to OmiCv1FunctionalGattV1.expectedCapabilities,
        OmiCv1FunctionalTargets.status to readyStatus(),
    )

    override suspend fun discoverServices(): List<BleGattService> = services

    override suspend fun read(target: BleCharacteristicTarget): OpaqueBytes =
        OpaqueBytes.copyOf(requireNotNull(values[target]))

    override suspend fun write(
        target: BleCharacteristicTarget,
        value: OpaqueBytes,
        kind: BleWriteKind,
    ) = error("Functional v1 is read-only")

    override suspend fun subscribe(target: BleCharacteristicTarget): BleSubscription =
        error("Subscription is not required by this negotiation test")

    override suspend fun close() = Unit
}

private fun service(
    uuid: String,
    vararg characteristics: BleGattCharacteristic,
) = BleGattService(uuid, primary = true, characteristics = characteristics.toList())

private fun characteristic(
    service: String,
    uuid: String,
    properties: Set<BleGattCharacteristicProperty> = setOf(BleGattCharacteristicProperty.READ),
) = BleGattCharacteristic(
    serviceUuid = service,
    uuid = uuid,
    properties = properties,
    permissions = setOf(BleGattAttributePermission.READ),
    descriptors = emptyList(),
)

private fun readyStatus(): ByteArray = ByteArray(OmiCv1FunctionalGattV1.STATUS_WIRE_SIZE).apply {
    this[0] = 1
    this[1] = OmiCv1FunctionalCapturePhase.IDLE.wireValue.toByte()
    this[2] = OmiCv1FunctionalMicrophoneTruth.VERIFIED_OFF.wireValue.toByte()
    this[3] = OmiCv1FunctionalStorageState.HEALTHY.wireValue.toByte()
    this[4] = OmiCv1FunctionalKeyTruth.READY.wireValue.toByte()
    this[5] = OmiCv1FunctionalRecordingStorageTruth.READY.wireValue.toByte()
    this[6] = OmiCv1FunctionalCodecTruth.CLOSED.wireValue.toByte()
    this[7] = (1 or (1 shl 5) or (1 shl 6)).toByte()
    putU64le(16, 8UL * 1024UL * 1024UL)
    putU32le(28, 7U)
}

private fun ByteArray.putU64le(offset: Int, value: ULong) {
    repeat(8) { index -> this[offset + index] = (value shr (index * 8)).toByte() }
}

private fun ByteArray.putU32le(offset: Int, value: UInt) {
    repeat(4) { index -> this[offset + index] = (value shr (index * 8)).toByte() }
}
