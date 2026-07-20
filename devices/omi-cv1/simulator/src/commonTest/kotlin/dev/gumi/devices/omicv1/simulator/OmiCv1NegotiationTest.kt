package dev.gumi.devices.omicv1.simulator

import dev.gumi.devices.omicv1.OmiCv1DriverProvider
import dev.gumi.devices.omicv1.OmiCv1GattProfile
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.DeviceOpenException
import dev.gumi.edge.sdk.NegotiatedDeviceSession
import dev.gumi.edge.sdk.OperationResult
import dev.gumi.edge.sdk.ble.BleCharacteristicTarget
import dev.gumi.edge.sdk.ble.BleConnectionOptions
import dev.gumi.edge.sdk.ble.BleGattCharacteristicProperty
import dev.gumi.edge.sdk.capability.audio.AudioInputV1
import dev.gumi.edge.sdk.capability.audio.AudioPayloadFraming
import dev.gumi.edge.sdk.capability.button.ButtonGestureKind
import dev.gumi.edge.sdk.capability.button.ButtonGestureV1
import dev.gumi.edge.sdk.capability.haptic.HapticPatternId
import dev.gumi.edge.sdk.capability.haptic.HapticV1
import dev.gumi.edge.sdk.capability.power.PowerStatusV1
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OmiCv1NegotiationTest {
    @Test
    fun `driver negotiates only handles actually backed by stock v3012`() = runTest {
        val simulator = OmiCv1Simulator()
        val driver = OmiCv1DriverProvider()
        val session = assertIs<NegotiatedDeviceSession>(
            driver.open(simulator.endpoint, simulator.connect(simulator.endpoint)),
        )

        assertEquals("omi-stock/3.0.12", session.descriptor.protocolVersion)
        assertEquals(
            setOf("gumi.audio-input", "gumi.button-gesture", "gumi.haptic", "gumi.power-status"),
            session.capabilities.keys().map { it.value }.toSet(),
        )
        assertEquals(4, session.capabilities.size)
        assertEquals(
            AudioPayloadFraming.RAW_OPUS_PACKET,
            assertNotNull(session.capabilities.handle(AudioInputV1)).descriptor.formats.single().payloadFraming,
        )
        assertEquals(
            setOf(
                ButtonGestureKind.SINGLE_TAP,
                ButtonGestureKind.DOUBLE_TAP,
                ButtonGestureKind.HOLD,
                ButtonGestureKind.RELEASE,
            ),
            assertNotNull(session.capabilities.handle(ButtonGestureV1)).descriptor.gestures,
        )
        assertEquals(47u, assertNotNull(session.capabilities.handle(PowerStatusV1)).read().batteryPercent)
        assertEquals(null, session.deviceId)
    }

    @Test
    fun `read-only UUID lookalike fails before reads writes or subscriptions`() = runTest {
        val readOnlyProfile = OmiCv1V3012Profile.services.map { service ->
            service.copy(
                characteristics = service.characteristics.map { characteristic ->
                    characteristic.copy(properties = setOf(BleGattCharacteristicProperty.READ))
                },
            )
        }
        val simulator = OmiCv1Simulator(gattServices = readOnlyProfile)

        val error = assertFailsWith<DeviceOpenException> {
            OmiCv1DriverProvider().open(simulator.endpoint, simulator.connect(simulator.endpoint))
        }

        assertEquals("OMI_REQUIRED_GATT_OPERATIONS_MISSING", error.failure.code.value)
        assertEquals(listOf(SimulatorOperation.DISCOVER), simulator.operations)
        assertEquals(emptyList(), simulator.writes)
    }

    @Test
    fun `source-only v3020 revision cannot relabel the observed v3012 simulator`() = runTest {
        val simulator = OmiCv1Simulator()
        val firmwareRevision = BleCharacteristicTarget(
            OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
            OmiCv1GattProfile.FIRMWARE_REVISION,
        )

        val relabel = assertFailsWith<IllegalArgumentException> {
            simulator.setReadValue(firmwareRevision, "3.0.20".encodeToByteArray())
        }

        assertTrue(relabel.message.orEmpty().contains("firmware identity is immutable"))
        val transport = simulator.connect(simulator.endpoint)
        val session = assertIs<NegotiatedDeviceSession>(
            OmiCv1DriverProvider().open(simulator.endpoint, transport),
        )
        val button = assertNotNull(session.capabilities.handle(ButtonGestureV1))

        assertEquals("omi-stock/3.0.12", session.descriptor.protocolVersion)
        assertTrue(ButtonGestureKind.HOLD in button.descriptor.gestures)
        assertEquals("3.0.12", transport.read(firmwareRevision).copyBytes().decodeToString())
        val collector = launch { assertEquals(ButtonGestureKind.UNKNOWN, button.events.first().gesture) }
        yield()
        simulator.emit(
            BleCharacteristicTarget(OmiCv1V3012Profile.BUTTON_SERVICE, OmiCv1V3012Profile.BUTTON_CHARACTERISTIC),
            byteArrayOf(99, 0, 0, 0, 0, 0, 0, 0),
        )
        collector.join()
        session.close()
    }

    @Test
    fun `audio notification ordinal gaps become explicit frame discontinuities`() = runTest {
        val simulator = OmiCv1Simulator(notificationBufferCapacity = 1)
        val session = assertIs<NegotiatedDeviceSession>(
            OmiCv1DriverProvider().open(
                simulator.endpoint,
                simulator.connect(simulator.endpoint, BleConnectionOptions(requestedMtu = 512)),
            ),
        )
        val audio = assertNotNull(session.capabilities.handle(AudioInputV1))
        val stream = audio.open(audio.descriptor.formats.single())

        simulator.emitAudioPacket(1u, byteArrayOf(1))
        simulator.emitAudioPacket(2u, byteArrayOf(2)) // bounded subscription drops ordinal 2
        val frames = mutableListOf<dev.gumi.edge.sdk.capability.audio.AudioFrame>()
        val collector = launch { stream.frames.take(2).toList(frames) }
        yield()
        simulator.emitAudioPacket(3u, byteArrayOf(3))
        collector.join()

        assertEquals(listOf(1uL, 3uL), frames.map { it.sequence })
        assertEquals(listOf(false, true), frames.map { it.discontinuityBefore })
        assertTrue(frames.all { it.receivedAtMonotonicMillis != null })
        stream.close()
        session.close()
    }

    @Test
    fun `typed audio button and haptic handles exercise the same simulated BLE session`() = runTest {
        val simulator = OmiCv1Simulator()
        val session = assertIs<NegotiatedDeviceSession>(
            OmiCv1DriverProvider().open(
                simulator.endpoint,
                simulator.connect(simulator.endpoint, BleConnectionOptions(requestedMtu = 512)),
            ),
        )
        val audioHandle = assertNotNull(session.capabilities.handle(AudioInputV1))
        val buttonHandle = assertNotNull(session.capabilities.handle(ButtonGestureV1))
        val hapticHandle = assertNotNull(session.capabilities.handle(HapticV1))
        val audioStream = audioHandle.open(audioHandle.descriptor.formats.single())

        var audioHex: String? = null
        var gesture: ButtonGestureKind? = null
        val audioCollector = launch { audioHex = audioStream.frames.first().payload.copyBytes().hex() }
        val buttonCollector = launch { gesture = buttonHandle.events.first().gesture }
        yield()
        simulator.emitAudioPacket(1u, byteArrayOf(0x01, 0x02, 0x03))
        simulator.emit(
            BleCharacteristicTarget(OmiCv1V3012Profile.BUTTON_SERVICE, OmiCv1V3012Profile.BUTTON_CHARACTERISTIC),
            byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0),
        )
        audioCollector.join()
        buttonCollector.join()

        val commandId = CommandId("haptic-command-1")
        assertIs<OperationResult.Success<Unit>>(
            hapticHandle.play(commandId, HapticPatternId("stock-short")),
        )
        assertIs<OperationResult.Success<Unit>>(
            hapticHandle.play(commandId, HapticPatternId("stock-short")),
        )
        assertEquals(1, simulator.writes.size)
        assertEquals("010203", audioHex)
        assertEquals(ButtonGestureKind.DOUBLE_TAP, gesture)

        audioStream.close()
        session.close()
    }

    @Test
    fun `arbitrary firmware revision injection fails before transport truth changes`() = runTest {
        val simulator = OmiCv1Simulator()
        val firmwareRevision = BleCharacteristicTarget(
            OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
            OmiCv1GattProfile.FIRMWARE_REVISION,
        )

        assertFailsWith<IllegalArgumentException> {
            simulator.setReadValue(firmwareRevision, "9.9.9".encodeToByteArray())
        }
        val transport = simulator.connect(simulator.endpoint)

        assertEquals("3.0.12", transport.read(firmwareRevision).copyBytes().decodeToString())
        assertEquals(listOf(SimulatorOperation.READ), simulator.operations)
        transport.close()
    }
}

private fun ByteArray.hex(): String = joinToString("") { byte ->
    byte.toUByte().toString(16).padStart(2, '0')
}
