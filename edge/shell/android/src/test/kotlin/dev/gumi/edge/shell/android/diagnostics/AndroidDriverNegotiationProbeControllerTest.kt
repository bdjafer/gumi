package dev.gumi.edge.shell.android.diagnostics

import dev.gumi.devices.omicv1.OmiCv1DriverProvider
import dev.gumi.devices.omicv1.OmiCv1GattProfile
import dev.gumi.devices.omicv1.OmiCv1Protocol
import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.sdk.CapabilitySet
import dev.gumi.edge.sdk.DeviceDescriptor
import dev.gumi.edge.sdk.DeviceDriverProvider
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.DeviceSession
import dev.gumi.edge.sdk.DeviceSessionEvent
import dev.gumi.edge.sdk.DriverId
import dev.gumi.edge.sdk.DriverMatch
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.NegotiatedDeviceSession
import dev.gumi.edge.sdk.OpaqueBytes
import dev.gumi.edge.sdk.OperationResult
import dev.gumi.edge.sdk.TransportEvent
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.TransportSession
import dev.gumi.edge.sdk.ble.BleBondState
import dev.gumi.edge.sdk.ble.BleCentral
import dev.gumi.edge.sdk.ble.BleCharacteristicTarget
import dev.gumi.edge.sdk.ble.BleConnectionOptions
import dev.gumi.edge.sdk.ble.BleGattCharacteristic
import dev.gumi.edge.sdk.ble.BleGattCharacteristicProperty
import dev.gumi.edge.sdk.ble.BleGattService
import dev.gumi.edge.sdk.ble.BleLinkSnapshot
import dev.gumi.edge.sdk.ble.BleSessionEvent
import dev.gumi.edge.sdk.ble.BleSessionException
import dev.gumi.edge.sdk.ble.BleSessionFailureCode
import dev.gumi.edge.sdk.ble.BleSubscription
import dev.gumi.edge.sdk.ble.BleTransportSession
import dev.gumi.edge.sdk.ble.BleWriteKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidDriverNegotiationProbeControllerTest {
    @Test
    fun `tap negotiates through the registry without capability effects and always disconnects`() =
        runTest {
            val endpoint = omiEndpoint()
            val transport = RecordingBleTransport(endpoint)
            val logs = mutableListOf<String>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val controller = AndroidDriverNegotiationProbeController(
                central = FixedCentral(endpoint, transport),
                driverRegistry = DeviceDriverRegistry(listOf(OmiCv1DriverProvider())),
                scope = scope,
                log = { message ->
                    if (message.startsWith("Operational driver negotiation complete:")) {
                        assertTrue(transport.closed)
                    }
                    logs += message
                },
                nextAttemptId = { 17L },
            )

            controller.probe(endpoint)
            advanceUntilIdle()

            val state = controller.state.value
            assertFalse(state.connecting)
            assertNull(state.error)
            val projection = requireNotNull(state.projection)
            assertEquals("Based Hardware", projection.manufacturer)
            assertEquals("Omi CV 1", projection.model)
            assertEquals("omi-stock/3.0.12", projection.protocol)
            assertEquals(
                setOf(
                    "gumi.audio-input",
                    "gumi.button-gesture",
                    "gumi.haptic",
                    "gumi.power-status",
                ),
                projection.capabilities.mapTo(mutableSetOf()) { it.key },
            )
            assertEquals(1, transport.discoveries)
            assertEquals(
                setOf(
                    OmiCv1GattProfile.MANUFACTURER_NAME,
                    OmiCv1GattProfile.MODEL_NUMBER,
                    OmiCv1GattProfile.FIRMWARE_REVISION,
                    "19b10002-e8f2-537e-4f6c-d104768a1214",
                ),
                transport.reads.mapTo(mutableSetOf()) { it.characteristicUuid },
            )
            assertEquals(0, transport.writes)
            assertEquals(0, transport.subscriptions)
            assertTrue(transport.closed)

            assertEquals(
                "Operational driver negotiation attempt started: attempt=17",
                logs.first(),
            )
            val completionLog = logs.single { it.startsWith("Operational driver negotiation complete:") }
            assertTrue(completionLog.startsWith("Operational driver negotiation complete: attempt=17,"))
            assertTrue("manufacturer=Based Hardware" in completionLog)
            assertTrue("model=Omi CV 1" in completionLog)
            assertTrue("driver=gumi.device.omi-cv1" in completionLog)
            assertTrue("protocol=omi-stock/3.0.12" in completionLog)
            assertTrue("formats=OPUS 16000Hz 1ch 20ms" in completionLog)
            assertTrue("live=true" in completionLog)
            assertTrue("stored=false" in completionLog)
            assertTrue("gestures=DOUBLE_TAP, HOLD, RELEASE, SINGLE_TAP" in completionLog)
            assertTrue("configurable=false" in completionLog)
            assertTrue("patterns=stock-long, stock-medium, stock-short" in completionLog)
            assertTrue("reportsBatteryPercent=true" in completionLog)
            assertTrue("reportsCharging=false" in completionLog)
            assertTrue("link=(mtu=23,txPhy=null,rxPhy=null,bond=NOT_BONDED)" in completionLog)
            assertFalse(endpoint.ephemeralId in completionLog)
            assertTrue("capabilities=[" in completionLog)
            scope.cancel()
        }

    @Test
    fun `default cleanup deadline contains the platform disconnect deadline`() = runTest {
        val endpoint = omiEndpoint()
        val transport = RecordingBleTransport(endpoint)
        val session = ControlledNegotiatedSession(
            endpoint,
            SessionCloseBehavior.PLATFORM_DEADLINE_PLUS_MARGIN,
        )
        val logs = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = AndroidDriverNegotiationProbeController(
            central = FixedCentral(endpoint, transport),
            driverRegistry = DeviceDriverRegistry(listOf(FixedNegotiatedProvider(session))),
            scope = scope,
            log = logs::add,
            nextAttemptId = { 19L },
        )

        controller.probe(endpoint)
        advanceUntilIdle()

        assertNull(controller.state.value.error)
        assertNotNull(controller.state.value.projection)
        assertTrue(transport.closed)
        assertEquals(1, session.closeCalls)
        assertEquals(
            1,
            logs.count { it.startsWith("Operational driver negotiation complete: attempt=19,") },
        )
        assertFalse(logs.any { "DRIVER_DEVICE_CLOSE_TIMEOUT" in it })
        scope.cancel()
    }

    @Test
    fun `BLE failures remain redacted stable diagnostic state`() = runTest {
        val endpoint = omiEndpoint()
        val logs = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = AndroidDriverNegotiationProbeController(
            central = object : BleCentral {
                override suspend fun connect(
                    endpoint: EndpointCandidate,
                    options: BleConnectionOptions,
                ): BleTransportSession = throw BleSessionException(
                    BleSessionFailureCode.CONNECTION_FAILED,
                    "sensitive platform detail",
                )
            },
            driverRegistry = DeviceDriverRegistry(listOf(OmiCv1DriverProvider())),
            scope = scope,
            log = logs::add,
            nextAttemptId = { 18L },
        )

        controller.probe(endpoint)
        advanceUntilIdle()

        assertEquals(
            "CONNECTION_FAILED: BLE connect or negotiation failed",
            controller.state.value.error,
        )
        assertNull(controller.state.value.projection)
        assertFalse(controller.state.value.connecting)
        assertEquals(
            listOf("Operational driver negotiation attempt started: attempt=18"),
            logs,
        )
        scope.cancel()
    }

    @Test
    fun `cancel during slow transport release retains lease and rejects retry`() = runTest {
        val endpoint = omiEndpoint()
        val release = CompletableDeferred<Unit>()
        val transport = RecordingBleTransport(endpoint, closeRelease = release)
        val gate = DiagnosticOperationGate()
        val logs = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var attemptId = 20L
        val controller = AndroidDriverNegotiationProbeController(
            central = FixedCentral(endpoint, transport),
            driverRegistry = DeviceDriverRegistry(listOf(OmiCv1DriverProvider())),
            scope = scope,
            log = logs::add,
            nextAttemptId = { ++attemptId },
            operationGate = gate,
        )

        controller.probe(endpoint)
        runCurrent()
        assertTrue(transport.closeStarted.isCompleted)
        assertTrue(controller.state.value.connecting)

        controller.cancel()
        controller.probe(endpoint)
        runCurrent()
        assertTrue(controller.state.value.connecting)
        assertTrue(controller.state.value.cancelling)
        assertTrue(gate.state.value.cancelling)
        assertEquals(1, logs.count { it.contains("attempt started") })
        assertEquals(1, transport.discoveries)

        release.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.connecting)
        assertFalse(controller.state.value.cancelling)
        assertNull(controller.state.value.projection)
        assertFalse(gate.state.value.busy)
        assertFalse(logs.any { it.startsWith("Operational driver negotiation complete:") })

        controller.probe(endpoint)
        advanceUntilIdle()
        assertEquals(2, logs.count { it.contains("attempt started") })
        assertEquals(1, logs.count { it.startsWith("Operational driver negotiation complete:") })
        assertEquals(2, transport.discoveries)
        assertFalse(logs.any { endpoint.ephemeralId in it })
        scope.cancel()
    }

    @Test
    fun `device close timeout suppresses negotiated projection and completion marker`() = runTest {
        val endpoint = omiEndpoint()
        val transport = RecordingBleTransport(endpoint)
        val session = ControlledNegotiatedSession(endpoint, SessionCloseBehavior.HANG)
        val logs = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = AndroidDriverNegotiationProbeController(
            central = FixedCentral(endpoint, transport),
            driverRegistry = DeviceDriverRegistry(listOf(FixedNegotiatedProvider(session))),
            scope = scope,
            log = logs::add,
            nextAttemptId = { 22L },
            closeTimeoutMillis = 100,
        )

        controller.probe(endpoint)
        runCurrent()
        assertTrue(controller.state.value.connecting)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(
            "DRIVER_DEVICE_CLOSE_TIMEOUT: driver negotiation cleanup failed",
            controller.state.value.error,
        )
        assertNull(controller.state.value.projection)
        assertFalse(controller.state.value.connecting)
        assertTrue(transport.closed)
        assertEquals(1, session.closeCalls)
        assertFalse(logs.any { it.startsWith("Operational driver negotiation complete:") })
        assertEquals(
            "Operational driver negotiation failed: " +
                "attempt=22, code=DRIVER_DEVICE_CLOSE_TIMEOUT",
            logs.last(),
        )
        scope.cancel()
    }

    @Test
    fun `transport close failure is stable and suppresses completion marker`() = runTest {
        val endpoint = omiEndpoint()
        val transport = RecordingBleTransport(endpoint, failClose = true)
        val session = ControlledNegotiatedSession(endpoint, SessionCloseBehavior.SUCCESS)
        val logs = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = AndroidDriverNegotiationProbeController(
            central = FixedCentral(endpoint, transport),
            driverRegistry = DeviceDriverRegistry(listOf(FixedNegotiatedProvider(session))),
            scope = scope,
            log = logs::add,
            nextAttemptId = { 23L },
        )

        controller.probe(endpoint)
        advanceUntilIdle()

        assertEquals(
            "DRIVER_TRANSPORT_CLOSE_FAILED: driver negotiation cleanup failed",
            controller.state.value.error,
        )
        assertNull(controller.state.value.projection)
        assertFalse(controller.state.value.connecting)
        assertEquals(1, session.closeCalls)
        assertEquals(1, transport.closeCalls)
        assertFalse(logs.any { it.startsWith("Operational driver negotiation complete:") })
        assertFalse(logs.any { endpoint.ephemeralId in it })
        scope.cancel()
    }
}

private class FixedCentral(
    private val expectedEndpoint: EndpointCandidate,
    private val transport: BleTransportSession,
) : BleCentral {
    override suspend fun connect(
        endpoint: EndpointCandidate,
        options: BleConnectionOptions,
    ): BleTransportSession {
        assertEquals(expectedEndpoint, endpoint)
        return transport
    }
}

private class RecordingBleTransport(
    override val endpoint: EndpointCandidate,
    private val closeRelease: CompletableDeferred<Unit>? = null,
    private val failClose: Boolean = false,
) : BleTransportSession {
    override val events: Flow<TransportEvent> = emptyFlow()
    override val bleEvents: Flow<BleSessionEvent> = emptyFlow()
    override val link = BleLinkSnapshot(
        mtu = 23,
        txPhy = null,
        rxPhy = null,
        bondState = BleBondState.NOT_BONDED,
    )
    var discoveries = 0
    val reads = mutableListOf<BleCharacteristicTarget>()
    var writes = 0
    var subscriptions = 0
    var closed = false
    var closeCalls = 0
    val closeStarted = CompletableDeferred<Unit>()

    override suspend fun discoverServices(): List<BleGattService> {
        discoveries += 1
        return omiRequiredGattSurface()
    }

    override suspend fun read(target: BleCharacteristicTarget): OpaqueBytes {
        reads += target
        val bytes = when (target.characteristicUuid) {
            OmiCv1GattProfile.MANUFACTURER_NAME -> "Based Hardware".encodeToByteArray()
            OmiCv1GattProfile.MODEL_NUMBER -> "Omi CV 1".encodeToByteArray()
            OmiCv1GattProfile.FIRMWARE_REVISION -> "3.0.12".encodeToByteArray()
            "19b10002-e8f2-537e-4f6c-d104768a1214" -> byteArrayOf(21)
            else -> error("Unexpected read target ${target.characteristicUuid}")
        }
        return OpaqueBytes.copyOf(bytes)
    }

    override suspend fun write(
        target: BleCharacteristicTarget,
        value: OpaqueBytes,
        kind: BleWriteKind,
    ) {
        writes += 1
        error("Negotiation must not write")
    }

    override suspend fun subscribe(target: BleCharacteristicTarget): BleSubscription {
        subscriptions += 1
        error("Negotiation must not subscribe")
    }

    override suspend fun close() {
        closeCalls += 1
        closeStarted.complete(Unit)
        closeRelease?.await()
        if (failClose) error("sensitive synthetic transport close failure")
        closed = true
    }
}

private enum class SessionCloseBehavior {
    SUCCESS,
    HANG,
    PLATFORM_DEADLINE_PLUS_MARGIN,
}

private class FixedNegotiatedProvider(
    private val session: ControlledNegotiatedSession,
) : DeviceDriverProvider {
    override val id = session.descriptor.driverId

    override fun match(candidate: EndpointCandidate) = DriverMatch(MatchConfidence.EXACT)

    override fun describe(candidate: EndpointCandidate): DeviceDescriptor = session.descriptor

    override suspend fun open(
        candidate: EndpointCandidate,
        transport: TransportSession,
    ): DeviceSession {
        assertEquals(session.endpoint, candidate)
        return session
    }
}

private class ControlledNegotiatedSession(
    override val endpoint: EndpointCandidate,
    private val closeBehavior: SessionCloseBehavior,
) : NegotiatedDeviceSession {
    override val deviceId: DeviceId? = null
    override val descriptor = DeviceDescriptor(
        driverId = DriverId("test.controlled-driver"),
        manufacturer = "Gumi",
        model = "Controlled",
        protocolVersion = "test/1",
        capabilities = emptyList(),
    )
    override val capabilities = when (
        val negotiated = CapabilitySet.negotiate(emptyList(), emptyList())
    ) {
        is OperationResult.Success -> negotiated.value
        is OperationResult.Failure -> error(negotiated.failure.code.value)
    }
    override val events: Flow<DeviceSessionEvent> = emptyFlow()
    var closeCalls = 0
        private set

    override suspend fun close() {
        closeCalls += 1
        when (closeBehavior) {
            SessionCloseBehavior.SUCCESS -> Unit
            SessionCloseBehavior.HANG -> awaitCancellation()
            SessionCloseBehavior.PLATFORM_DEADLINE_PLUS_MARGIN -> delay(5_001)
        }
    }
}

private fun omiEndpoint() = EndpointCandidate(
    transport = TransportKind.BLE,
    ephemeralId = "ble:redacted-test-endpoint",
    advertisedServiceUuids = setOf(OmiCv1Protocol.AUDIO_SERVICE_UUID),
    advertisedName = "Omi",
)

private fun omiRequiredGattSurface(): List<BleGattService> = listOf(
    service(
        OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
        characteristic(OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE, OmiCv1GattProfile.MANUFACTURER_NAME),
        characteristic(OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE, OmiCv1GattProfile.MODEL_NUMBER),
        characteristic(OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE, OmiCv1GattProfile.FIRMWARE_REVISION),
    ),
    service(
        OmiCv1GattProfile.BATTERY_SERVICE,
        characteristic(
            OmiCv1GattProfile.BATTERY_SERVICE,
            OmiCv1GattProfile.BATTERY_LEVEL,
            BleGattCharacteristicProperty.READ,
            BleGattCharacteristicProperty.NOTIFY,
        ),
    ),
    service(
        OmiCv1Protocol.AUDIO_SERVICE_UUID,
        characteristic(
            OmiCv1Protocol.AUDIO_SERVICE_UUID,
            "19b10001-e8f2-537e-4f6c-d104768a1214",
            BleGattCharacteristicProperty.NOTIFY,
        ),
        characteristic(
            OmiCv1Protocol.AUDIO_SERVICE_UUID,
            "19b10002-e8f2-537e-4f6c-d104768a1214",
            BleGattCharacteristicProperty.READ,
        ),
    ),
    service(
        "23ba7924-0000-1000-7450-346eac492e92",
        characteristic(
            "23ba7924-0000-1000-7450-346eac492e92",
            "23ba7925-0000-1000-7450-346eac492e92",
            BleGattCharacteristicProperty.NOTIFY,
        ),
    ),
    service(
        "cab1ab95-2ea5-4f4d-bb56-874b72cfc984",
        characteristic(
            "cab1ab95-2ea5-4f4d-bb56-874b72cfc984",
            "cab1ab96-2ea5-4f4d-bb56-874b72cfc984",
            BleGattCharacteristicProperty.WRITE,
        ),
    ),
)

private fun service(
    uuid: String,
    vararg characteristics: BleGattCharacteristic,
) = BleGattService(uuid = uuid, primary = true, characteristics = characteristics.toList())

private fun characteristic(
    service: String,
    uuid: String,
    vararg properties: BleGattCharacteristicProperty,
) = BleGattCharacteristic(
    serviceUuid = service,
    uuid = uuid,
    properties = properties.toSet().ifEmpty { setOf(BleGattCharacteristicProperty.READ) },
    permissions = emptySet(),
    descriptors = emptyList(),
)
