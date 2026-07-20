package dev.gumi.edge.shell.android.diagnostics

import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.sdk.CapabilityBinding
import dev.gumi.edge.sdk.CapabilitySet
import dev.gumi.edge.sdk.DeviceDescriptor
import dev.gumi.edge.sdk.DeviceDriverProvider
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.DeviceSession
import dev.gumi.edge.sdk.DeviceSessionEvent
import dev.gumi.edge.sdk.DriverId
import dev.gumi.edge.sdk.DriverMatch
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
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
import dev.gumi.edge.sdk.ble.BleGattService
import dev.gumi.edge.sdk.ble.BleLinkSnapshot
import dev.gumi.edge.sdk.ble.BleSessionEvent
import dev.gumi.edge.sdk.ble.BleSubscription
import dev.gumi.edge.sdk.ble.BleTransportSession
import dev.gumi.edge.sdk.ble.BleWriteKind
import dev.gumi.edge.sdk.capability.audio.AudioCodec
import dev.gumi.edge.sdk.capability.audio.AudioFormat
import dev.gumi.edge.sdk.capability.audio.AudioFrame
import dev.gumi.edge.sdk.capability.audio.AudioInputDescriptor
import dev.gumi.edge.sdk.capability.audio.AudioInputHandle
import dev.gumi.edge.sdk.capability.audio.AudioInputV1
import dev.gumi.edge.sdk.capability.audio.AudioPayloadFraming
import dev.gumi.edge.sdk.capability.audio.AudioStream
import dev.gumi.edge.sdk.capability.audio.AudioStreamException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAudioMetadataProbeControllerTest {
    @Test
    fun `shared inspector adapter exposes non content Opus TOC facts`() {
        val inspected = assertIs<AudioPacketMetadataInspection.Valid>(
            SharedOpusPacketMetadataInspector.inspect(
                TEST_AUDIO_FORMAT,
                OpaqueBytes.copyOf(byteArrayOf(0x78)),
            ),
        )

        assertEquals(15u, inspected.tocConfiguration)
        assertFalse(assertNotNull(inspected.encodedStereo))
        assertEquals(1u, inspected.frameCount)
        assertEquals(20_000u, inspected.frameDurationUs)
        assertEquals(960u, inspected.decodedSamples48k)
    }

    @Test
    fun `clean ten second capture qualifies only after every owner is closed`() = runTest {
        val rig = ProbeRig(testScheduler)

        rig.start()
        rig.emitQualifiedWindow()
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertTrue(rig.controller.state.value.running)
        assertNull(rig.controller.state.value.result)
        advanceTimeBy(1)
        runCurrent()

        val result = assertNotNull(rig.controller.state.value.result)
        assertTrue(result.qualified)
        assertEquals("AUDIO_METADATA_QUALIFIED", result.code)
        assertEquals(450L, result.facts?.frameCount)
        assertEquals(3_604L, result.facts?.totalPayloadBytes)
        assertEquals(8, result.facts?.minimumPayloadBytes)
        assertEquals(12, result.facts?.maximumPayloadBytes)
        assertEquals(1uL, result.facts?.firstSequence)
        assertEquals(450uL, result.facts?.lastSequence)
        assertEquals(20L, result.facts?.minimumInterarrivalMillis)
        assertEquals(20L, result.facts?.maximumInterarrivalMillis)
        assertEquals(8_980L, result.facts?.receiveSpanMillis)
        assertEquals(setOf(20_000u), result.facts?.opusFrameDurationsUs)
        assertEquals(setOf(15u), result.facts?.opusTocConfigurations)
        assertEquals(setOf(false), result.facts?.opusEncodedStereo)
        assertEquals(512, result.facts?.link?.mtu)
        assertTrue(rig.successLoggedAfterCleanup)
        assertTrue(rig.stream.closed)
        assertTrue(rig.device.closed)
        assertTrue(rig.transport.closed)
        assertEquals(512, rig.connectedOptions?.requestedMtu)
        assertEquals(
            1,
            rig.logs.count { it.startsWith("Stock live-audio metadata probe complete:") },
        )
        assertEquals(
            "Stock live-audio metadata probe attempt started: attempt=71",
            rig.logs.first(),
        )
        assertFalse(rig.logs.any { rig.endpoint.ephemeralId in it })
        assertFalse(rig.logs.any { "digest=" in it || "payload=" in it })
        rig.close()
    }

    @Test
    fun `default cleanup deadline contains a full platform disconnect window`() = runTest {
        val rig = ProbeRig(
            scheduler = testScheduler,
            deviceCloseDelayMillis = 5_001,
        )

        rig.start()
        rig.emitQualifiedWindow()
        advanceUntilIdle()

        val result = assertNotNull(rig.controller.state.value.result)
        assertTrue(result.qualified)
        assertEquals("AUDIO_METADATA_QUALIFIED", result.code)
        assertTrue(rig.successLoggedAfterCleanup)
        assertTrue(rig.device.closed)
        assertTrue(rig.transport.closed)
        assertFalse(rig.logs.any { "AUDIO_CLOSE_TIMEOUT" in it })
        rig.close()
    }

    @Test
    fun `sparse or short audio never qualifies as a ten second witness`() = runTest {
        val oneFrame = ProbeRig(testScheduler)
        oneFrame.start()
        oneFrame.stream.emit(frame(1uL, 8, receivedAtMonotonicMillis = 1_000))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("AUDIO_INSUFFICIENT_COVERAGE", oneFrame.controller.state.value.result?.code)
        oneFrame.close()

        val sparse = ProbeRig(testScheduler)
        sparse.start()
        sparse.stream.emit(frame(1uL, 8, receivedAtMonotonicMillis = 1_000))
        sparse.stream.emit(frame(2uL, 8, receivedAtMonotonicMillis = 1_125))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("AUDIO_INSUFFICIENT_COVERAGE", sparse.controller.state.value.result?.code)
        sparse.close()
    }

    @Test
    fun `coverage with a receive starvation gap remains non qualified`() = runTest {
        val rig = ProbeRig(testScheduler)
        rig.start()
        repeat(450) { index ->
            val arrival = 1_000L + index * 20L + if (index >= 225) 300L else 0L
            rig.stream.emit(frame((index + 1).toULong(), 8, receivedAtMonotonicMillis = arrival))
        }
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals("AUDIO_STREAM_STARVATION", rig.controller.state.value.result?.code)
        assertEquals(320L, rig.controller.state.value.result?.facts?.maximumInterarrivalMillis)
        rig.close()
    }

    @Test
    fun `sequence gap and discontinuity make capture non qualified`() = runTest {
        val rig = ProbeRig(testScheduler)

        rig.start()
        rig.stream.emit(frame(10uL, 8))
        rig.stream.emit(frame(12uL, 8, discontinuity = true))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        val result = assertNotNull(rig.controller.state.value.result)
        assertFalse(result.qualified)
        assertEquals("AUDIO_SEQUENCE_GAP", result.code)
        assertEquals(1L, result.facts?.sequenceGapCount)
        assertEquals(1L, result.facts?.discontinuityFlagCount)
        assertFalse(rig.logs.any { it.startsWith("Stock live-audio metadata probe complete:") })
        rig.close()
    }

    @Test
    fun `notification drop queued during stream close cannot look clean`() = runTest {
        val rig = ProbeRig(testScheduler, emitTrailingDropOnStreamClose = true)

        rig.start()
        rig.stream.emit(frame(1uL, 8))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        val result = assertNotNull(rig.controller.state.value.result)
        assertFalse(result.qualified)
        assertEquals("AUDIO_NOTIFICATIONS_DROPPED", result.code)
        assertTrue(rig.stream.closed && rig.device.closed && rig.transport.closed)
        assertFalse(rig.logs.any { it.startsWith("Stock live-audio metadata probe complete:") })
        rig.close()
    }

    @Test
    fun `disconnect aborts capture and is explicit`() = runTest {
        val rig = ProbeRig(testScheduler)

        rig.start()
        rig.stream.emit(frame(1uL, 8))
        runCurrent()
        rig.transport.disconnect()
        advanceUntilIdle()

        val result = assertNotNull(rig.controller.state.value.result)
        assertFalse(result.qualified)
        assertEquals("AUDIO_BLE_DISCONNECTED", result.code)
        assertTrue(rig.stream.closed)
        assertTrue(rig.device.closed)
        assertFalse(rig.logs.any { it.startsWith("Stock live-audio metadata probe complete:") })
        rig.close()
    }

    @Test
    fun `typed source framing failure remains redacted and distinct`() = runTest {
        val rig = ProbeRig(testScheduler)

        rig.start()
        runCurrent()
        rig.stream.fail(
            AudioStreamException(
                ExpectedFailure(
                    category = FailureCategory.CORRUPT,
                    code = FailureCode("OMI_AUDIO_FRAGMENTATION_UNSUPPORTED"),
                    retryable = false,
                ),
            ),
        )
        advanceUntilIdle()

        val result = assertNotNull(rig.controller.state.value.result)
        assertFalse(result.qualified)
        assertEquals("AUDIO_SOURCE_STREAM_FAILED", result.code)
        assertEquals("OMI_AUDIO_FRAGMENTATION_UNSUPPORTED", result.reasonCode)
        assertFalse(rig.logs.any { "OMI_AUDIO_FRAGMENTATION_UNSUPPORTED" in it })
        rig.close()
    }

    @Test
    fun `owner cancellation closes all resources and emits no success`() = runTest {
        val rig = ProbeRig(testScheduler)

        rig.start()
        rig.stream.emit(frame(1uL, 8))
        runCurrent()
        rig.controller.cancel()
        runCurrent()

        val result = assertNotNull(rig.controller.state.value.result)
        assertEquals("AUDIO_PROBE_CANCELLED", result.code)
        assertTrue(rig.stream.closed)
        assertTrue(rig.device.closed)
        assertTrue(rig.transport.closed)
        assertFalse(rig.logs.any { it.startsWith("Stock live-audio metadata probe complete:") })
        rig.close()
    }

    @Test
    fun `cancel rejects retry until slow stream close reaches its timeout`() = runTest {
        val rig = ProbeRig(
            testScheduler,
            hangStreamClose = true,
            closeTimeoutMillis = 100,
        )

        rig.start()
        rig.stream.emit(frame(1uL, 8))
        runCurrent()
        rig.controller.cancel()
        runCurrent()
        assertTrue(rig.controller.state.value.running)
        assertTrue(rig.controller.state.value.cancelling)

        rig.start()
        runCurrent()
        assertEquals(
            1,
            rig.logs.count { it.startsWith("Stock live-audio metadata probe attempt started:") },
        )

        advanceTimeBy(100)
        runCurrent()
        assertFalse(rig.controller.state.value.running)
        assertFalse(rig.controller.state.value.cancelling)
        assertEquals("AUDIO_CLOSE_TIMEOUT", rig.controller.state.value.result?.code)

        rig.start()
        assertTrue(rig.controller.state.value.running)
        assertEquals(
            2,
            rig.logs.count { it.startsWith("Stock live-audio metadata probe attempt started:") },
        )
        rig.controller.cancel()
        runCurrent()
        rig.close()
    }

    @Test
    fun `frame and byte bounds stop capture with distinct outcomes`() = runTest {
        val frameBoundRig = ProbeRig(
            testScheduler,
            disclosure = disclosure(maximumFrames = 1, maximumPayloadBytes = 100),
        )
        frameBoundRig.start()
        frameBoundRig.stream.emit(frame(1uL, 4))
        frameBoundRig.stream.emit(frame(2uL, 4))
        advanceUntilIdle()
        assertEquals(
            "AUDIO_FRAME_BOUND_REACHED",
            frameBoundRig.controller.state.value.result?.code,
        )
        assertEquals(1L, frameBoundRig.controller.state.value.result?.facts?.frameCount)
        frameBoundRig.close()

        val byteBoundRig = ProbeRig(
            testScheduler,
            disclosure = disclosure(maximumFrames = 10, maximumPayloadBytes = 4),
        )
        byteBoundRig.start()
        byteBoundRig.stream.emit(frame(1uL, 5))
        advanceUntilIdle()
        assertEquals(
            "AUDIO_BYTE_BOUND_REACHED",
            byteBoundRig.controller.state.value.result?.code,
        )
        assertEquals(0L, byteBoundRig.controller.state.value.result?.facts?.frameCount)
        byteBoundRig.close()
    }

    @Test
    fun `empty capture is explicit`() = runTest {
        val rig = ProbeRig(testScheduler)

        rig.start()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals("AUDIO_EMPTY_CAPTURE", rig.controller.state.value.result?.code)
        assertEquals(0L, rig.controller.state.value.result?.facts?.frameCount)
        rig.close()
    }

    @Test
    fun `stream close failure is non qualified while later owners are still closed`() = runTest {
        val rig = ProbeRig(testScheduler, failStreamClose = true)

        rig.start()
        rig.stream.emit(frame(1uL, 8))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals("AUDIO_STREAM_CLOSE_FAILED", rig.controller.state.value.result?.code)
        assertTrue(rig.device.closed)
        assertTrue(rig.transport.closed)
        assertFalse(rig.logs.any { it.startsWith("Stock live-audio metadata probe complete:") })
        rig.close()
    }

    @Test
    fun `event overflow is explicit and aborts capture`() = runTest {
        val rig = ProbeRig(testScheduler)

        rig.start()
        rig.stream.emit(frame(1uL, 8))
        runCurrent()
        rig.transport.emit(
            BleSessionEvent.Fault(
                dev.gumi.edge.sdk.ble.BleSessionFailureCode.EVENT_OVERFLOW,
                "redacted synthetic overflow",
            ),
        )
        advanceUntilIdle()

        assertEquals("AUDIO_BLE_EVENT_OVERFLOW", rig.controller.state.value.result?.code)
        assertFalse(rig.logs.any { it.startsWith("Stock live-audio metadata probe complete:") })
        rig.close()
    }

    @Test
    fun `packet rejection keeps stable result code and separate inspector reason`() = runTest {
        val rig = ProbeRig(
            testScheduler,
            packetInspection = AudioPacketMetadataInspection.Invalid("OPUS_FRAME_COUNT_NOT_ONE"),
        )

        rig.start()
        rig.stream.emit(frame(1uL, 8))
        advanceUntilIdle()

        val result = assertNotNull(rig.controller.state.value.result)
        assertEquals("AUDIO_INVALID_PACKET", result.code)
        assertEquals("OPUS_FRAME_COUNT_NOT_ONE", result.reasonCode)
        assertEquals("OPUS_FRAME_COUNT_NOT_ONE", result.facts?.packetInspectionFailureCode)
        assertFalse(rig.logs.any { "AUDIO_INVALID_PACKET:" in it })
        rig.close()
    }

    @Test
    fun `BLE receipt clock regression is non qualified`() = runTest {
        val rig = ProbeRig(testScheduler)

        rig.start()
        rig.stream.emit(frame(1uL, 8, receivedAtMonotonicMillis = 100))
        rig.stream.emit(frame(2uL, 8, receivedAtMonotonicMillis = 99))
        advanceUntilIdle()

        assertEquals(
            "AUDIO_MONOTONIC_CLOCK_REGRESSION",
            rig.controller.state.value.result?.code,
        )
        assertFalse(rig.logs.any { it.startsWith("Stock live-audio metadata probe complete:") })
        rig.close()
    }

    @Test
    fun `setup timeout is explicit and emits no success`() = runTest {
        val rig = ProbeRig(
            testScheduler,
            connectDelayMillis = 1_000,
            setupTimeoutMillis = 100,
        )

        rig.start()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(rig.controller.state.value.running)
        advanceTimeBy(1)
        runCurrent()

        assertEquals("AUDIO_SETUP_TIMEOUT", rig.controller.state.value.result?.code)
        assertFalse(rig.logs.any { it.startsWith("Stock live-audio metadata probe complete:") })
        rig.close()
    }

    @Test
    fun `live handle may also advertise stored without invoking storage`() = runTest {
        val rig = ProbeRig(testScheduler, descriptorStored = true)

        rig.start()
        rig.emitQualifiedWindow()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals("AUDIO_METADATA_QUALIFIED", rig.controller.state.value.result?.code)
        rig.close()
    }

    @Test
    fun `stream close timeout is explicit and later owners still close`() = runTest {
        val rig = ProbeRig(
            testScheduler,
            hangStreamClose = true,
            closeTimeoutMillis = 100,
        )

        rig.start()
        rig.stream.emit(frame(1uL, 8))
        advanceTimeBy(10_000)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertEquals("AUDIO_CLOSE_TIMEOUT", rig.controller.state.value.result?.code)
        assertTrue(rig.device.closed)
        assertTrue(rig.transport.closed)
        assertFalse(rig.logs.any { it.startsWith("Stock live-audio metadata probe complete:") })
        rig.close()
    }

    @Test
    fun `higher priority cleanup failure does not expose stale packet reason`() = runTest {
        val rig = ProbeRig(
            testScheduler,
            failStreamClose = true,
            packetInspection = AudioPacketMetadataInspection.Invalid("OPUS_EMPTY_PACKET"),
        )

        rig.start()
        rig.stream.emit(frame(1uL, 8))
        advanceUntilIdle()

        val result = assertNotNull(rig.controller.state.value.result)
        assertEquals("AUDIO_STREAM_CLOSE_FAILED", result.code)
        assertNull(result.reasonCode)
        rig.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ProbeRig(
    scheduler: TestCoroutineScheduler,
    disclosure: AudioMetadataProbeDisclosure = disclosure(),
    emitTrailingDropOnStreamClose: Boolean = false,
    failStreamClose: Boolean = false,
    hangStreamClose: Boolean = false,
    descriptorStored: Boolean = false,
    packetInspection: AudioPacketMetadataInspection = AudioPacketMetadataInspection.Valid(
        frameCount = 1u,
        frameDurationUs = 20_000u,
        decodedSamples48k = 960u,
        tocConfiguration = 15u,
        encodedStereo = false,
    ),
    connectDelayMillis: Long = 0,
    deviceCloseDelayMillis: Long = 0,
    setupTimeoutMillis: Long = 30_000,
    closeTimeoutMillis: Long = 15_000,
) {
    val endpoint = EndpointCandidate(
        transport = TransportKind.BLE,
        ephemeralId = "ble:redacted-audio-test",
    )
    val logs = mutableListOf<String>()
    val transport = FakeAudioTransport(endpoint)
    val stream = FakeAudioStream(
        onClose = {
            if (emitTrailingDropOnStreamClose) {
                transport.emit(BleSessionEvent.NotificationsDropped(TEST_AUDIO_TARGET, 1u))
            }
        },
        failClose = failStreamClose,
        hangClose = hangStreamClose,
    )
    private val handle = FakeAudioInputHandle(stream, stored = descriptorStored)
    val device = FakeNegotiatedDeviceSession(
        endpoint = endpoint,
        transport = transport,
        handle = handle,
        closeDelayMillis = deviceCloseDelayMillis,
    )
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    var successLoggedAfterCleanup = false
        private set
    var connectedOptions: BleConnectionOptions? = null
        private set
    val controller = AndroidAudioMetadataProbeController(
        central = object : BleCentral {
            override suspend fun connect(
                endpoint: EndpointCandidate,
                options: BleConnectionOptions,
            ): BleTransportSession {
                assertEquals(this@ProbeRig.endpoint, endpoint)
                connectedOptions = options
                delay(connectDelayMillis)
                return transport
            }
        },
        driverRegistry = DeviceDriverRegistry(listOf(FakeAudioDriverProvider(device))),
        packetInspector = AudioPacketMetadataInspector { _, _ -> packetInspection },
        scope = scope,
        log = { message ->
            if (message.startsWith("Stock live-audio metadata probe complete:")) {
                successLoggedAfterCleanup = stream.closed && device.closed && transport.closed
            }
            logs += message
        },
        nextAttemptId = { 71L },
        setupTimeoutMillis = setupTimeoutMillis,
        closeTimeoutMillis = closeTimeoutMillis,
        disclosure = disclosure,
    )

    fun start() {
        controller.probe(endpoint)
    }

    fun close() {
        controller.close()
    }
}

private class FakeAudioDriverProvider(
    private val session: FakeNegotiatedDeviceSession,
) : DeviceDriverProvider {
    override val id = DriverId("test.audio-driver")

    override fun match(candidate: EndpointCandidate) = DriverMatch(MatchConfidence.EXACT)

    override fun describe(candidate: EndpointCandidate): DeviceDescriptor = session.descriptor

    override suspend fun open(
        candidate: EndpointCandidate,
        transport: TransportSession,
    ): DeviceSession {
        assertEquals(session.endpoint, candidate)
        assertEquals(session.transport, transport)
        return session
    }
}

private class FakeNegotiatedDeviceSession(
    override val endpoint: EndpointCandidate,
    val transport: FakeAudioTransport,
    handle: FakeAudioInputHandle,
    private val closeDelayMillis: Long,
) : NegotiatedDeviceSession {
    override val deviceId: DeviceId? = null
    override val descriptor = DeviceDescriptor(
        driverId = DriverId("test.audio-driver"),
        manufacturer = "test",
        model = "typed-audio",
        protocolVersion = "test/1",
        capabilities = listOf(handle.descriptor),
    )
    override val capabilities = capabilitySet(handle)
    override val events: Flow<DeviceSessionEvent> = emptyFlow()
    var closed = false
        private set

    override suspend fun close() {
        delay(closeDelayMillis)
        closed = true
        transport.close()
    }
}

private class FakeAudioInputHandle(
    private val stream: FakeAudioStream,
    stored: Boolean,
) : AudioInputHandle {
    override val descriptor = AudioInputDescriptor(
        formats = setOf(TEST_AUDIO_FORMAT),
        live = true,
        stored = stored,
    )

    override suspend fun open(format: AudioFormat): AudioStream {
        assertEquals(TEST_AUDIO_FORMAT, format)
        return stream
    }
}

private class FakeAudioStream(
    private val onClose: suspend () -> Unit = {},
    private val failClose: Boolean = false,
    private val hangClose: Boolean = false,
) : AudioStream {
    private val channel = Channel<AudioFrame>(Channel.UNLIMITED)
    override val format: AudioFormat = TEST_AUDIO_FORMAT
    override val frames: Flow<AudioFrame> = channel.receiveAsFlow()
    var closed = false
        private set

    fun emit(frame: AudioFrame) {
        check(channel.trySend(frame).isSuccess)
    }

    fun fail(error: Throwable) {
        channel.close(error)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        onClose()
        if (hangClose) awaitCancellation()
        channel.close()
        if (failClose) error("synthetic stream close failure")
    }
}

private class FakeAudioTransport(
    override val endpoint: EndpointCandidate,
) : BleTransportSession {
    private val eventChannel = Channel<BleSessionEvent>(Channel.UNLIMITED)
    override val bleEvents: Flow<BleSessionEvent> = eventChannel.receiveAsFlow()
    override val events: Flow<TransportEvent> = emptyFlow()
    override val link = BleLinkSnapshot(512, null, null, BleBondState.NOT_BONDED)
    var closed = false
        private set

    fun emit(event: BleSessionEvent) {
        check(eventChannel.trySend(event).isSuccess)
    }

    fun disconnect() {
        emit(BleSessionEvent.Disconnected)
        eventChannel.close()
    }

    override suspend fun discoverServices(): List<BleGattService> = emptyList()

    override suspend fun read(target: BleCharacteristicTarget): OpaqueBytes =
        error("Audio metadata test does not read GATT values")

    override suspend fun write(
        target: BleCharacteristicTarget,
        value: OpaqueBytes,
        kind: BleWriteKind,
    ) = error("Audio metadata test does not write GATT values")

    override suspend fun subscribe(target: BleCharacteristicTarget): BleSubscription =
        error("Typed fake handle owns the synthetic audio stream")

    override suspend fun close() {
        if (closed) return
        closed = true
        eventChannel.trySend(BleSessionEvent.Closed)
        eventChannel.close()
    }
}

private fun capabilitySet(handle: FakeAudioInputHandle): CapabilitySet = when (
    val result = CapabilitySet.negotiate(
        advertised = listOf(handle.descriptor),
        bindings = listOf(CapabilityBinding(AudioInputV1, handle.descriptor, handle)),
    )
) {
    is OperationResult.Success -> result.value
    is OperationResult.Failure -> error("Synthetic capability negotiation failed: ${result.failure}")
}

private fun frame(
    sequence: ULong,
    size: Int,
    discontinuity: Boolean = false,
    receivedAtMonotonicMillis: Long = sequence.toLong() * 20L,
) = AudioFrame(
    sequence = sequence,
    payload = OpaqueBytes.copyOf(ByteArray(size)),
    discontinuityBefore = discontinuity,
    receivedAtMonotonicMillis = receivedAtMonotonicMillis,
)

private fun disclosure(
    maximumFrames: Long = 1_000,
    maximumPayloadBytes: Long = 1_276_000,
) = AudioMetadataProbeDisclosure(
    durationMillis = 10_000,
    minimumFrames = minOf(450, maximumFrames),
    maximumFrames = maximumFrames,
    maximumPayloadBytes = maximumPayloadBytes,
    minimumReceiveSpanMillis = 8_500,
    maximumInterarrivalMillis = 250,
    requestedAttMtu = 512,
)

private fun ProbeRig.emitQualifiedWindow() {
    repeat(450) { index ->
        stream.emit(
            frame(
                sequence = (index + 1).toULong(),
                size = if (index == 449) 12 else 8,
                receivedAtMonotonicMillis = 1_000L + index * 20L,
            ),
        )
    }
}

private val TEST_AUDIO_FORMAT = AudioFormat(
    codec = AudioCodec.OPUS,
    sampleRateHz = 16_000u,
    channels = 1u,
    payloadFraming = AudioPayloadFraming.RAW_OPUS_PACKET,
)

private val TEST_AUDIO_TARGET = BleCharacteristicTarget(
    "19b10000-e8f2-537e-4f6c-d104768a1214",
    "19b10001-e8f2-537e-4f6c-d104768a1214",
)
