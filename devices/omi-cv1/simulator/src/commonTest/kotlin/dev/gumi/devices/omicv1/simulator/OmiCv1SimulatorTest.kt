package dev.gumi.devices.omicv1.simulator

import dev.gumi.devices.omicv1.OmiCv1GattProfile
import dev.gumi.devices.omicv1.OmiCv1Protocol
import dev.gumi.edge.sdk.OpaqueBytes
import dev.gumi.edge.sdk.TransportEvent
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.ble.BleSessionEvent
import dev.gumi.edge.sdk.ble.BleCharacteristicTarget
import dev.gumi.edge.sdk.ble.BleConnectionOptions
import dev.gumi.edge.sdk.ble.BleSessionException
import dev.gumi.edge.sdk.ble.BleSessionFailureCode
import dev.gumi.edge.sdk.ble.BleWriteKind
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OmiCv1SimulatorTest {
    @Test
    fun `simulator presents the owned v3012 profile over BLE`() = runTest {
        val simulator = OmiCv1Simulator()
        val session = simulator.connect(simulator.endpoint)

        assertEquals(TransportKind.BLE, simulator.endpoint.transport)
        assertEquals(11, session.discoverServices().size)
        assertEquals(21, session.discoverServices().sumOf { it.characteristics.size })
        assertContentEquals(
            "3.0.12".encodeToByteArray(),
            session.read(target(OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE, OmiCv1GattProfile.FIRMWARE_REVISION))
                .copyBytes(),
        )
        assertContentEquals(
            byteArrayOf(21),
            session.read(target(OmiCv1Protocol.AUDIO_SERVICE_UUID, OmiCv1V3012Profile.AUDIO_CODEC_CHARACTERISTIC))
                .copyBytes(),
        )
        assertTrue(session.read(target(OmiCv1GattProfile.STORAGE_SERVICE, OmiCv1GattProfile.STORAGE_STATUS)).toString().contains("redacted"))
    }

    @Test
    fun `requested ATT MTU is negotiated against the stock transport ceiling`() = runTest {
        val simulator = OmiCv1Simulator()

        val defaultSession = simulator.connect(simulator.endpoint)
        assertEquals(23, defaultSession.link.mtu)
        defaultSession.close()

        val expandedSession = simulator.connect(
            simulator.endpoint,
            BleConnectionOptions(requestedMtu = 512),
        )
        assertEquals(498, expandedSession.link.mtu)
        expandedSession.close()
    }

    @Test
    fun `split duplicate and reordered notifications remain observable facts`() = runTest {
        val simulator = OmiCv1Simulator()
        val session = simulator.connect(simulator.endpoint)
        val audio = target(OmiCv1Protocol.AUDIO_SERVICE_UUID, OmiCv1V3012Profile.AUDIO_CHARACTERISTIC)
        val subscription = session.subscribe(audio)
        val observed = mutableListOf<ByteArray>()
        val collector = launch {
            subscription.notifications.take(7).toList().mapTo(observed) { it.value.copyBytes() }
        }
        yield()

        simulator.emitSplit(audio, byteArrayOf(1, 2, 3, 4), listOf(1, 3))
        simulator.emitDuplicate(audio, byteArrayOf(5, 6))
        simulator.emitReordered(
            audio,
            listOf(byteArrayOf(7), byteArrayOf(8), byteArrayOf(9)),
            listOf(2, 0, 1),
        )
        collector.join()

        assertEquals(
            listOf("01", "020304", "0506", "0506", "09", "07", "08"),
            observed.map(ByteArray::hex),
        )
    }

    @Test
    fun `planned failures disconnect and post-close behavior are deterministic`() = runTest {
        val simulator = OmiCv1Simulator()
        val session = simulator.connect(simulator.endpoint)
        val battery = target(OmiCv1GattProfile.BATTERY_SERVICE, OmiCv1GattProfile.BATTERY_LEVEL)
        simulator.failNext(
            SimulatorPlannedFailure(
                operation = SimulatorOperation.READ,
                target = battery,
                code = BleSessionFailureCode.TIMEOUT,
            ),
        )
        assertEquals(
            BleSessionFailureCode.TIMEOUT,
            assertFailsWith<BleSessionException> { session.read(battery) }.code,
        )

        simulator.disconnect()
        assertEquals(
            BleSessionFailureCode.DISCONNECTED,
            assertFailsWith<BleSessionException> { session.discoverServices() }.code,
        )
        session.close()
        session.close()
        assertEquals(
            BleSessionFailureCode.CLOSED,
            assertFailsWith<BleSessionException> { session.read(battery) }.code,
        )
    }

    @Test
    fun `write semantics follow characteristic properties and preserve redaction`() = runTest {
        val simulator = OmiCv1Simulator()
        val session = simulator.connect(simulator.endpoint)
        val haptic = target(OmiCv1V3012Profile.HAPTIC_SERVICE, OmiCv1V3012Profile.HAPTIC_CHARACTERISTIC)
        session.write(haptic, OpaqueBytes.copyOf(byteArrayOf(2)), BleWriteKind.WITH_RESPONSE)

        assertEquals(1, simulator.writes.size)
        assertEquals("OpaqueBytes([redacted], size=1)", simulator.writes.single().value.toString())
        assertEquals(
            BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
            assertFailsWith<BleSessionException> {
                session.write(haptic, OpaqueBytes.copyOf(byteArrayOf(2)), BleWriteKind.WITHOUT_RESPONSE)
            }.code,
        )
    }

    @Test
    fun `duplicate subscription is rejected exactly like Android`() = runTest {
        val simulator = OmiCv1Simulator()
        val session = simulator.connect(simulator.endpoint)
        val audio = target(OmiCv1Protocol.AUDIO_SERVICE_UUID, OmiCv1V3012Profile.AUDIO_CHARACTERISTIC)
        val first = session.subscribe(audio)

        assertEquals(
            BleSessionFailureCode.OPERATION_FAILED,
            assertFailsWith<BleSessionException> { session.subscribe(audio) }.code,
        )
        first.close()
        val replacement = session.subscribe(audio)
        replacement.close()
        session.close()
    }

    @Test
    fun `disconnect is retained as the first terminal outcome on both event surfaces`() = runTest {
        val simulator = OmiCv1Simulator()
        val session = simulator.connect(simulator.endpoint)
        val bleEvents = mutableListOf<BleSessionEvent>()
        val transportEvents = mutableListOf<TransportEvent>()
        val bleCollector = launch { session.bleEvents.toList(bleEvents) }
        val transportCollector = launch { session.events.toList(transportEvents) }
        yield()

        simulator.disconnect()
        session.close()
        bleCollector.join()
        transportCollector.join()

        assertEquals(listOf<BleSessionEvent>(BleSessionEvent.Disconnected), bleEvents)
        assertEquals(1, transportEvents.size)
        assertEquals("DISCONNECTED", (transportEvents.single() as TransportEvent.Fault).code)
    }

    @Test
    fun `event overflow becomes a non-droppable terminal failure and releases the connection`() = runTest {
        val simulator = OmiCv1Simulator(eventBufferCapacity = 1, notificationBufferCapacity = 1)
        val session = simulator.connect(simulator.endpoint)
        val audio = target(OmiCv1Protocol.AUDIO_SERVICE_UUID, OmiCv1V3012Profile.AUDIO_CHARACTERISTIC)
        session.subscribe(audio)
        simulator.emit(audio, byteArrayOf(1))
        simulator.emit(audio, byteArrayOf(2))
        simulator.emit(audio, byteArrayOf(3))

        val fault = session.bleEvents.toList().single() as BleSessionEvent.Fault
        assertEquals(BleSessionFailureCode.EVENT_OVERFLOW, fault.code)
        assertEquals(
            BleSessionFailureCode.EVENT_OVERFLOW,
            assertFailsWith<BleSessionException> { session.discoverServices() }.code,
        )

        val replacement = simulator.connect(simulator.endpoint)
        replacement.close()
    }

    @Test
    fun `notification and event flows reject a second lifetime collector`() = runTest {
        val simulator = OmiCv1Simulator()
        val session = simulator.connect(simulator.endpoint)
        val audio = target(OmiCv1Protocol.AUDIO_SERVICE_UUID, OmiCv1V3012Profile.AUDIO_CHARACTERISTIC)
        val subscription = session.subscribe(audio)
        subscription.close()
        assertEquals(emptyList(), subscription.notifications.toList())
        assertEquals(
            BleSessionFailureCode.OPERATION_FAILED,
            assertFailsWith<BleSessionException> { subscription.notifications.toList() }.code,
        )

        session.close()
        assertEquals(listOf(BleSessionEvent.Closed), session.bleEvents.toList())
        assertEquals(
            BleSessionFailureCode.OPERATION_FAILED,
            assertFailsWith<BleSessionException> { session.bleEvents.toList() }.code,
        )
    }

    private fun target(service: String, characteristic: String) =
        BleCharacteristicTarget(service, characteristic)
}

private fun ByteArray.hex(): String = joinToString("") { byte ->
    byte.toUByte().toString(16).padStart(2, '0')
}
