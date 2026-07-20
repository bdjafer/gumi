package dev.gumi.edge.shell.android.diagnostics

import android.util.Log
import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.runtime.media.ogg.RFC6716_MAX_SINGLE_FRAME_PACKET_BYTES
import dev.gumi.edge.sdk.DeviceOpenException
import dev.gumi.edge.sdk.DeviceSession
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.NegotiatedDeviceSession
import dev.gumi.edge.sdk.OpaqueBytes
import dev.gumi.edge.sdk.ble.BleCentral
import dev.gumi.edge.sdk.ble.BleConnectionOptions
import dev.gumi.edge.sdk.ble.BleLinkSnapshot
import dev.gumi.edge.sdk.ble.BleSessionEvent
import dev.gumi.edge.sdk.ble.BleSessionException
import dev.gumi.edge.sdk.ble.BleSessionFailureCode
import dev.gumi.edge.sdk.ble.BleTransportSession
import dev.gumi.edge.sdk.capability.audio.AudioFormat
import dev.gumi.edge.sdk.capability.audio.AudioFrame
import dev.gumi.edge.sdk.capability.audio.AudioInputV1
import dev.gumi.edge.sdk.capability.audio.AudioStream
import dev.gumi.edge.sdk.capability.audio.AudioStreamException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class AudioMetadataProbeDisclosure(
    val durationMillis: Long,
    val minimumFrames: Long,
    val maximumFrames: Long,
    val maximumPayloadBytes: Long,
    val minimumReceiveSpanMillis: Long,
    val maximumInterarrivalMillis: Long,
    val requestedAttMtu: Int,
    val transientInMemoryOnly: Boolean = true,
    val operations: List<String> = listOf(
        "Connect and discover services through the negotiated device driver",
        "Request ATT MTU 512 before subscribing to the stock live-audio stream",
        "Read negotiated device identity fields required by the stock driver",
        "Read and require stock audio codec ID 21 (Opus)",
        "Strip and validate the stock 3-byte sequence/fragment envelope in memory",
        "Write the audio CCCD to enable live notifications",
        "Observe audio frames in memory for exactly 10 seconds",
        "Disable the audio subscription and close stream, device session, and BLE transport",
    ),
    val unavailableOperations: List<String> = listOf(
        "stored-audio or ring reads",
        "stored-audio cursor advance",
        "haptic actions",
        "firmware or configuration writes",
        "network upload",
        "file or database persistence",
        "audio content logging",
        "background service",
    ),
)

sealed interface AudioPacketMetadataInspection {
    data class Valid(
        val frameCount: UInt,
        val frameDurationUs: UInt,
        val decodedSamples48k: UInt,
        val tocConfiguration: UInt? = null,
        val encodedStereo: Boolean? = null,
    ) : AudioPacketMetadataInspection

    data class Invalid(val code: String) : AudioPacketMetadataInspection {
        init {
            require(STABLE_REASON_CODE.matches(code)) {
                "Packet inspection reason must be a stable non-content code"
            }
        }
    }

    data object NotApplicable : AudioPacketMetadataInspection
}

/** Adapter seam for the shared common packet inspector. It never retains or renders payload bytes. */
fun interface AudioPacketMetadataInspector {
    fun inspect(format: AudioFormat, payload: OpaqueBytes): AudioPacketMetadataInspection
}

data class AudioMetadataFacts(
    val format: AudioFormat,
    val link: BleLinkSnapshot?,
    val frameCount: Long,
    val totalPayloadBytes: Long,
    val minimumPayloadBytes: Int?,
    val maximumPayloadBytes: Int?,
    val firstSequence: ULong?,
    val lastSequence: ULong?,
    val sequenceGapCount: Long,
    val discontinuityFlagCount: Long,
    val minimumInterarrivalMillis: Long?,
    val maximumInterarrivalMillis: Long?,
    val receiveSpanMillis: Long?,
    val opusFrameCounts: Set<UInt>,
    val opusFrameDurationsUs: Set<UInt>,
    val opusDecodedSamples48k: Set<UInt>,
    val opusTocConfigurations: Set<UInt>,
    val opusEncodedStereo: Set<Boolean>,
    val packetInspectionFailureCode: String?,
)

data class AudioMetadataProbeResult(
    val qualified: Boolean,
    val code: String,
    val reasonCode: String? = null,
    val facts: AudioMetadataFacts?,
)

data class AudioMetadataProbeState(
    val running: Boolean = false,
    val cancelling: Boolean = false,
    val result: AudioMetadataProbeResult? = null,
)

/**
 * Owner-triggered, metadata-only witness for one negotiated stock live-audio stream.
 *
 * The controller resolves [AudioInputV1] through the typed negotiated capability set. It never
 * reaches into an Omi implementation, retains a payload, logs content, or invokes another
 * capability. BLE lifecycle events are collected concurrently and drained through transport close
 * before a qualified result can be published.
 */
class AndroidAudioMetadataProbeController(
    private val central: BleCentral,
    private val driverRegistry: DeviceDriverRegistry,
    private val packetInspector: AudioPacketMetadataInspector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val log: (String) -> Unit = { message -> Log.i(LOG_TAG, message) },
    private val nextAttemptId: () -> Long = ::nextProcessAttemptId,
    private val setupTimeoutMillis: Long = SETUP_TIMEOUT_MILLIS,
    private val closeTimeoutMillis: Long = CLOSE_TIMEOUT_MILLIS,
    val disclosure: AudioMetadataProbeDisclosure = DEFAULT_DISCLOSURE,
    private val operationGate: DiagnosticOperationGate = DiagnosticOperationGate(),
) : AutoCloseable {
    init {
        require(setupTimeoutMillis > 0) { "Setup timeout must be positive" }
        require(closeTimeoutMillis > 0) { "Close timeout must be positive" }
        require(disclosure.durationMillis > 0) { "Capture duration must be positive" }
        require(disclosure.minimumFrames in 1..disclosure.maximumFrames) {
            "Minimum frame coverage must be positive and no greater than the frame bound"
        }
        require(disclosure.maximumFrames > 0) { "Frame bound must be positive" }
        require(disclosure.maximumPayloadBytes > 0) { "Payload bound must be positive" }
        require(disclosure.minimumReceiveSpanMillis in 1..disclosure.durationMillis) {
            "Minimum receive span must fit the capture duration"
        }
        require(disclosure.maximumInterarrivalMillis > 0) {
            "Maximum accepted interarrival must be positive"
        }
        require(disclosure.requestedAttMtu in 23..517) {
            "Requested ATT MTU must be between 23 and 517"
        }
    }

    private val mutableState = MutableStateFlow(AudioMetadataProbeState())
    private var activeOperation: AudioProbeOperation? = null

    val state: StateFlow<AudioMetadataProbeState> = mutableState.asStateFlow()

    /** Must only be called from the second, owner-confirmed disclosure action. */
    fun probe(endpoint: EndpointCandidate) {
        if (activeOperation != null) return
        val lease = operationGate.tryAcquire(DiagnosticOperation.AUDIO_METADATA) ?: return
        val attemptId = try {
            nextAttemptId().also { require(it > 0) { "Attempt ID must be positive" } }
        } catch (error: Exception) {
            lease.close()
            throw error
        }
        val operation = AudioProbeOperation(lease)
        activeOperation = operation
        log("Stock live-audio metadata probe attempt started: attempt=$attemptId")
        mutableState.value = AudioMetadataProbeState(running = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var transport: BleTransportSession? = null
            var deviceSession: DeviceSession? = null
            var audioStream: AudioStream? = null
            var lifecycleJob: Job? = null
            var accumulator: AudioMetadataAccumulator? = null
            var captureOutcome: CaptureOutcome? = null
            var setupFailure: String? = null
            var sourceFailureReasonCode: String? = null
            var cancelled = false
            val lifecycle = AudioLifecycleObservation()
            val lifecycleFailure = CompletableDeferred<Unit>()

            try {
                withTimeout(setupTimeoutMillis) {
                    transport = central.connect(
                        endpoint,
                        BleConnectionOptions(requestedMtu = disclosure.requestedAttMtu),
                    )
                    lifecycleJob = launchLifecycleObserver(
                        transport = checkNotNull(transport),
                        observation = lifecycle,
                        failureSignal = lifecycleFailure,
                    )
                    deviceSession = driverRegistry.open(endpoint, checkNotNull(transport))
                    val negotiated = deviceSession as? NegotiatedDeviceSession
                        ?: throw ProbeRejected(AUDIO_NEGOTIATED_SESSION_REQUIRED)
                    val audio = negotiated.capabilities.handle(AudioInputV1)
                        ?: throw ProbeRejected(AUDIO_INPUT_UNAVAILABLE)
                    if (!audio.descriptor.live) {
                        throw ProbeRejected(AUDIO_LIVE_REQUIRED)
                    }
                    val format = audio.descriptor.formats.singleOrNull()
                        ?: throw ProbeRejected(AUDIO_FORMAT_AMBIGUOUS)
                    val openedStream = audio.open(format)
                    require(openedStream.format == format) {
                        "Audio stream format differs from its negotiated descriptor"
                    }
                    audioStream = openedStream
                    accumulator = AudioMetadataAccumulator(
                        format = format,
                        maximumFrames = disclosure.maximumFrames,
                        maximumPayloadBytes = disclosure.maximumPayloadBytes,
                        packetInspector = packetInspector,
                    )
                }

                captureOutcome = capture(
                    stream = checkNotNull(audioStream),
                    accumulator = checkNotNull(accumulator),
                    lifecycleFailure = lifecycleFailure,
                )
            } catch (_: TimeoutCancellationException) {
                setupFailure = AUDIO_SETUP_TIMEOUT
            } catch (error: ProbeRejected) {
                setupFailure = error.code
            } catch (_: DeviceOpenException) {
                setupFailure = AUDIO_DRIVER_NEGOTIATION_FAILED
            } catch (error: AudioStreamException) {
                setupFailure = AUDIO_SOURCE_STREAM_FAILED
                sourceFailureReasonCode = error.failure.code.value
            } catch (error: BleSessionException) {
                setupFailure = when (error.code) {
                    BleSessionFailureCode.TIMEOUT -> AUDIO_SETUP_TIMEOUT
                    BleSessionFailureCode.EVENT_OVERFLOW -> AUDIO_BLE_EVENT_OVERFLOW
                    BleSessionFailureCode.DISCONNECTED -> AUDIO_BLE_DISCONNECTED
                    else -> AUDIO_BLE_OPERATION_FAILED
                }
            } catch (_: CancellationException) {
                cancelled = true
            } catch (_: Exception) {
                setupFailure = AUDIO_PROBE_FAILED
            } finally {
                val linkSnapshot = transport?.link
                val cleanup = withContext(NonCancellable) {
                    closeAll(audioStream, deviceSession, transport, lifecycleJob)
                }
                val facts = accumulator?.facts(linkSnapshot)
                val code = when {
                    cleanup != null -> cleanup
                    cancelled || operation.cancelRequested -> AUDIO_PROBE_CANCELLED
                    lifecycle.notificationDrops > 0uL -> AUDIO_NOTIFICATIONS_DROPPED
                    lifecycle.eventOverflow -> AUDIO_BLE_EVENT_OVERFLOW
                    lifecycle.disconnected -> AUDIO_BLE_DISCONNECTED
                    lifecycle.faultCode != null -> AUDIO_BLE_LIFECYCLE_FAULT
                    lifecycle.collectorFailed -> AUDIO_BLE_LIFECYCLE_FAILED
                    transport != null && !lifecycle.closed -> AUDIO_BLE_CLOSE_UNOBSERVED
                    setupFailure != null -> setupFailure
                    captureOutcome is CaptureOutcome.Rejected -> captureOutcome.code
                    facts == null || facts.frameCount == 0L -> AUDIO_EMPTY_CAPTURE
                    captureOutcome != CaptureOutcome.DurationReached -> AUDIO_STREAM_ENDED_EARLY
                    facts.sequenceGapCount > 0L -> AUDIO_SEQUENCE_GAP
                    facts.discontinuityFlagCount > 0L -> AUDIO_DISCONTINUITY
                    facts.frameCount < disclosure.minimumFrames ||
                        facts.receiveSpanMillis == null ||
                        facts.receiveSpanMillis < disclosure.minimumReceiveSpanMillis ->
                        AUDIO_INSUFFICIENT_COVERAGE
                    facts.maximumInterarrivalMillis == null ||
                        facts.maximumInterarrivalMillis > disclosure.maximumInterarrivalMillis ->
                        AUDIO_STREAM_STARVATION
                    else -> AUDIO_QUALIFIED
                }
                val qualified = code == AUDIO_QUALIFIED
                val result = AudioMetadataProbeResult(
                    qualified = qualified,
                    code = code,
                    reasonCode = when (code) {
                        AUDIO_INVALID_PACKET -> facts?.packetInspectionFailureCode
                        AUDIO_SOURCE_STREAM_FAILED -> sourceFailureReasonCode
                        else -> null
                    },
                    facts = facts,
                )
                try {
                    if (qualified) {
                        log(result.qualifiedLog(attemptId))
                    } else {
                        log("Stock live-audio metadata probe failed: attempt=$attemptId, code=$code")
                    }
                } finally {
                    finishOperation(operation, AudioMetadataProbeState(result = result))
                }
            }
        }
        operation.job = job
        job.invokeOnCompletion { finishOperation(operation, AudioMetadataProbeState()) }
        job.start()
    }

    fun cancel() {
        val operation = activeOperation ?: return
        operation.cancelRequested = true
        operation.lease.markCancelling()
        mutableState.value = mutableState.value.copy(running = true, cancelling = true)
        operation.job?.cancel()
    }

    override fun close() {
        cancel()
        scope.cancel()
    }

    private fun finishOperation(
        operation: AudioProbeOperation,
        terminalState: AudioMetadataProbeState,
    ) {
        if (activeOperation === operation) {
            activeOperation = null
            mutableState.value = terminalState
        }
        operation.lease.close()
    }

    private fun launchLifecycleObserver(
        transport: BleTransportSession,
        observation: AudioLifecycleObservation,
        failureSignal: CompletableDeferred<Unit>,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            transport.bleEvents.collect { event ->
                when (event) {
                    is BleSessionEvent.LinkChanged -> Unit
                    is BleSessionEvent.NotificationsDropped -> {
                        observation.notificationDrops =
                            saturatedAdd(observation.notificationDrops, event.count.toULong())
                        failureSignal.complete(Unit)
                    }
                    is BleSessionEvent.Fault -> {
                        observation.eventOverflow =
                            observation.eventOverflow || event.code == BleSessionFailureCode.EVENT_OVERFLOW
                        observation.faultCode = event.code
                        failureSignal.complete(Unit)
                    }
                    BleSessionEvent.Disconnected -> {
                        observation.disconnected = true
                        failureSignal.complete(Unit)
                    }
                    BleSessionEvent.Closed -> observation.closed = true
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            observation.collectorFailed = true
            failureSignal.complete(Unit)
        }
    }

    private suspend fun capture(
        stream: AudioStream,
        accumulator: AudioMetadataAccumulator,
        lifecycleFailure: CompletableDeferred<Unit>,
    ): CaptureOutcome = coroutineScope {
        val frames = async {
            try {
                stream.frames.collect(accumulator::accept)
                CaptureOutcome.StreamEnded
            } catch (error: ProbeRejected) {
                CaptureOutcome.Rejected(error.code)
            }
        }
        val deadline = async {
            delay(disclosure.durationMillis)
            CaptureOutcome.DurationReached
        }
        try {
            select {
                frames.onAwait { it }
                deadline.onAwait { it }
                lifecycleFailure.onAwait { CaptureOutcome.LifecycleFailed }
            }
        } finally {
            frames.cancelAndJoin()
            deadline.cancelAndJoin()
        }
    }

    private suspend fun closeAll(
        stream: AudioStream?,
        deviceSession: DeviceSession?,
        transport: BleTransportSession?,
        lifecycleJob: Job?,
    ): String? {
        var failure: String? = null
        suspend fun closeOne(code: String, block: suspend () -> Unit) {
            try {
                withTimeout(closeTimeoutMillis) { block() }
            } catch (_: TimeoutCancellationException) {
                if (failure == null) failure = AUDIO_CLOSE_TIMEOUT
            } catch (_: Exception) {
                if (failure == null) failure = code
            }
        }

        if (stream != null) closeOne(AUDIO_STREAM_CLOSE_FAILED, stream::close)
        if (deviceSession != null) closeOne(AUDIO_DEVICE_CLOSE_FAILED, deviceSession::close)
        if (transport != null) closeOne(AUDIO_TRANSPORT_CLOSE_FAILED, transport::close)
        if (lifecycleJob != null) {
            try {
                withTimeout(closeTimeoutMillis) { lifecycleJob.join() }
            } catch (_: TimeoutCancellationException) {
                if (failure == null) failure = AUDIO_BLE_CLOSE_UNOBSERVED
                lifecycleJob.cancel()
            }
        }
        return failure
    }

    private companion object {
        const val LOG_TAG = "GumiAudioProbe"
        const val SETUP_TIMEOUT_MILLIS = 30_000L
        // Subscription disable may consume the platform's ten-second ATT timeout; device close may
        // then consume its own five-second disconnect window. Each independently bounded cleanup
        // step needs a deadline larger than the longest platform operation it contains.
        const val CLOSE_TIMEOUT_MILLIS = 15_000L
        const val CAPTURE_DURATION_MILLIS = 10_000L
        const val MINIMUM_FRAMES = 450L
        const val MAXIMUM_FRAMES = 1_000L
        const val MINIMUM_RECEIVE_SPAN_MILLIS = 8_500L
        const val MAXIMUM_INTERARRIVAL_MILLIS = 250L
        const val STOCK_AUDIO_ATT_MTU = 512
        const val MAXIMUM_PAYLOAD_BYTES =
            MAXIMUM_FRAMES * RFC6716_MAX_SINGLE_FRAME_PACKET_BYTES

        val DEFAULT_DISCLOSURE = AudioMetadataProbeDisclosure(
            durationMillis = CAPTURE_DURATION_MILLIS,
            minimumFrames = MINIMUM_FRAMES,
            maximumFrames = MAXIMUM_FRAMES,
            maximumPayloadBytes = MAXIMUM_PAYLOAD_BYTES,
            minimumReceiveSpanMillis = MINIMUM_RECEIVE_SPAN_MILLIS,
            maximumInterarrivalMillis = MAXIMUM_INTERARRIVAL_MILLIS,
            requestedAttMtu = STOCK_AUDIO_ATT_MTU,
        )
        val PROCESS_ATTEMPT_IDS = AtomicLong(0)

        fun nextProcessAttemptId(): Long = PROCESS_ATTEMPT_IDS.incrementAndGet()
    }
}

private class AudioProbeOperation(
    val lease: DiagnosticOperationLease,
    var job: Job? = null,
    var cancelRequested: Boolean = false,
)

private sealed interface CaptureOutcome {
    data object DurationReached : CaptureOutcome
    data object StreamEnded : CaptureOutcome
    data object LifecycleFailed : CaptureOutcome
    data class Rejected(val code: String) : CaptureOutcome
}

private class AudioLifecycleObservation {
    var notificationDrops: ULong = 0uL
    var disconnected: Boolean = false
    var closed: Boolean = false
    var eventOverflow: Boolean = false
    var faultCode: BleSessionFailureCode? = null
    var collectorFailed: Boolean = false
}

private class AudioMetadataAccumulator(
    private val format: AudioFormat,
    private val maximumFrames: Long,
    private val maximumPayloadBytes: Long,
    private val packetInspector: AudioPacketMetadataInspector,
) {
    private var frameCount = 0L
    private var totalPayloadBytes = 0L
    private var minimumPayloadBytes: Int? = null
    private var maximumPayloadBytesSeen: Int? = null
    private var firstSequence: ULong? = null
    private var lastSequence: ULong? = null
    private var sequenceGapCount = 0L
    private var discontinuityFlagCount = 0L
    private var firstArrivalMillis: Long? = null
    private var lastArrivalMillis: Long? = null
    private var minimumInterarrivalMillis: Long? = null
    private var maximumInterarrivalMillis: Long? = null
    private val opusFrameCounts = linkedSetOf<UInt>()
    private val opusFrameDurationsUs = linkedSetOf<UInt>()
    private val opusDecodedSamples48k = linkedSetOf<UInt>()
    private val opusTocConfigurations = linkedSetOf<UInt>()
    private val opusEncodedStereo = linkedSetOf<Boolean>()
    private var packetInspectionFailureCode: String? = null

    fun accept(frame: AudioFrame) {
        if (frameCount >= maximumFrames) throw ProbeRejected(AUDIO_FRAME_BOUND_REACHED)
        val payloadSize = frame.payload.size
        if (payloadSize.toLong() > maximumPayloadBytes - totalPayloadBytes) {
            throw ProbeRejected(AUDIO_BYTE_BOUND_REACHED)
        }
        val sequence = frame.sequence ?: throw ProbeRejected(AUDIO_SEQUENCE_UNAVAILABLE)
        val prior = lastSequence
        if (prior != null && (prior == ULong.MAX_VALUE || sequence != prior + 1uL)) {
            sequenceGapCount = saturatedIncrement(sequenceGapCount)
        }
        if (frame.discontinuityBefore) {
            discontinuityFlagCount = saturatedIncrement(discontinuityFlagCount)
        }

        when (val inspected = packetInspector.inspect(format, frame.payload)) {
            is AudioPacketMetadataInspection.Valid -> {
                opusFrameCounts += inspected.frameCount
                opusFrameDurationsUs += inspected.frameDurationUs
                opusDecodedSamples48k += inspected.decodedSamples48k
                inspected.tocConfiguration?.let(opusTocConfigurations::add)
                inspected.encodedStereo?.let(opusEncodedStereo::add)
            }
            is AudioPacketMetadataInspection.Invalid -> {
                packetInspectionFailureCode = inspected.code
                throw ProbeRejected(AUDIO_INVALID_PACKET)
            }
            AudioPacketMetadataInspection.NotApplicable -> Unit
        }

        val arrival = frame.receivedAtMonotonicMillis
            ?: throw ProbeRejected(AUDIO_RECEIVE_TIME_UNAVAILABLE)
        val previousArrival = lastArrivalMillis
        if (previousArrival != null) {
            if (arrival < previousArrival) throw ProbeRejected(AUDIO_MONOTONIC_CLOCK_REGRESSION)
            val interarrival = arrival - previousArrival
            minimumInterarrivalMillis = minimumInterarrivalMillis?.coerceAtMost(interarrival) ?: interarrival
            maximumInterarrivalMillis = maximumInterarrivalMillis?.coerceAtLeast(interarrival) ?: interarrival
        }
        lastArrivalMillis = arrival
        if (firstArrivalMillis == null) firstArrivalMillis = arrival

        frameCount++
        totalPayloadBytes += payloadSize
        minimumPayloadBytes = minimumPayloadBytes?.coerceAtMost(payloadSize) ?: payloadSize
        maximumPayloadBytesSeen = maximumPayloadBytesSeen?.coerceAtLeast(payloadSize) ?: payloadSize
        if (firstSequence == null) firstSequence = sequence
        lastSequence = sequence
    }

    fun facts(link: BleLinkSnapshot?) = AudioMetadataFacts(
        format = format,
        link = link,
        frameCount = frameCount,
        totalPayloadBytes = totalPayloadBytes,
        minimumPayloadBytes = minimumPayloadBytes,
        maximumPayloadBytes = maximumPayloadBytesSeen,
        firstSequence = firstSequence,
        lastSequence = lastSequence,
        sequenceGapCount = sequenceGapCount,
        discontinuityFlagCount = discontinuityFlagCount,
        minimumInterarrivalMillis = minimumInterarrivalMillis,
        maximumInterarrivalMillis = maximumInterarrivalMillis,
        receiveSpanMillis = firstArrivalMillis?.let { first ->
            lastArrivalMillis?.let { last -> last - first }
        },
        opusFrameCounts = opusFrameCounts.toSet(),
        opusFrameDurationsUs = opusFrameDurationsUs.toSet(),
        opusDecodedSamples48k = opusDecodedSamples48k.toSet(),
        opusTocConfigurations = opusTocConfigurations.toSet(),
        opusEncodedStereo = opusEncodedStereo.toSet(),
        packetInspectionFailureCode = packetInspectionFailureCode,
    )
}

private class ProbeRejected(val code: String) : Exception(code)

private fun AudioMetadataProbeResult.qualifiedLog(attemptId: Long): String {
    val facts = checkNotNull(facts)
    return "Stock live-audio metadata probe complete: " +
        "attempt=$attemptId, frames=${facts.frameCount}, " +
        "totalPayloadBytes=${facts.totalPayloadBytes}, " +
        "payloadMin=${facts.minimumPayloadBytes}, payloadMax=${facts.maximumPayloadBytes}, " +
        "format=${facts.format.codec}/${facts.format.sampleRateHz}/" +
        "${facts.format.channels}/${facts.format.payloadFraming}, " +
        "mtu=${facts.link?.mtu}, txPhy=${facts.link?.txPhy}, rxPhy=${facts.link?.rxPhy}, " +
        "bond=${facts.link?.bondState}, " +
        "firstSequence=${facts.firstSequence}, lastSequence=${facts.lastSequence}, " +
        "gaps=${facts.sequenceGapCount}, discontinuities=${facts.discontinuityFlagCount}, " +
        "receiveSpanMillis=${facts.receiveSpanMillis}, " +
        "interarrivalMinMillis=${facts.minimumInterarrivalMillis}, " +
        "interarrivalMaxMillis=${facts.maximumInterarrivalMillis}, " +
        "opusConfigurations=${facts.opusTocConfigurations.sorted()}, " +
        "opusEncodedStereo=${facts.opusEncodedStereo.sorted()}, " +
        "opusFrameCounts=${facts.opusFrameCounts.sorted()}, " +
        "opusFrameDurationsUs=${facts.opusFrameDurationsUs.sorted()}, " +
        "opusDecodedSamples48k=${facts.opusDecodedSamples48k.sorted()}"
}

private fun saturatedIncrement(value: Long): Long =
    if (value == Long.MAX_VALUE) value else value + 1L

private fun saturatedAdd(value: ULong, delta: ULong): ULong =
    if (ULong.MAX_VALUE - value < delta) ULong.MAX_VALUE else value + delta

private val STABLE_REASON_CODE = Regex("^[A-Z][A-Z0-9_]*$")

private const val AUDIO_QUALIFIED = "AUDIO_METADATA_QUALIFIED"
private const val AUDIO_EMPTY_CAPTURE = "AUDIO_EMPTY_CAPTURE"
private const val AUDIO_INSUFFICIENT_COVERAGE = "AUDIO_INSUFFICIENT_COVERAGE"
private const val AUDIO_STREAM_STARVATION = "AUDIO_STREAM_STARVATION"
private const val AUDIO_FRAME_BOUND_REACHED = "AUDIO_FRAME_BOUND_REACHED"
private const val AUDIO_BYTE_BOUND_REACHED = "AUDIO_BYTE_BOUND_REACHED"
private const val AUDIO_SEQUENCE_UNAVAILABLE = "AUDIO_SEQUENCE_UNAVAILABLE"
private const val AUDIO_SEQUENCE_GAP = "AUDIO_SEQUENCE_GAP"
private const val AUDIO_DISCONTINUITY = "AUDIO_DISCONTINUITY"
private const val AUDIO_INVALID_PACKET = "AUDIO_INVALID_PACKET"
private const val AUDIO_SOURCE_STREAM_FAILED = "AUDIO_SOURCE_STREAM_FAILED"
private const val AUDIO_MONOTONIC_CLOCK_REGRESSION = "AUDIO_MONOTONIC_CLOCK_REGRESSION"
private const val AUDIO_RECEIVE_TIME_UNAVAILABLE = "AUDIO_RECEIVE_TIME_UNAVAILABLE"
private const val AUDIO_NOTIFICATIONS_DROPPED = "AUDIO_NOTIFICATIONS_DROPPED"
private const val AUDIO_BLE_EVENT_OVERFLOW = "AUDIO_BLE_EVENT_OVERFLOW"
private const val AUDIO_BLE_DISCONNECTED = "AUDIO_BLE_DISCONNECTED"
private const val AUDIO_BLE_LIFECYCLE_FAULT = "AUDIO_BLE_LIFECYCLE_FAULT"
private const val AUDIO_BLE_LIFECYCLE_FAILED = "AUDIO_BLE_LIFECYCLE_FAILED"
private const val AUDIO_BLE_CLOSE_UNOBSERVED = "AUDIO_BLE_CLOSE_UNOBSERVED"
private const val AUDIO_STREAM_ENDED_EARLY = "AUDIO_STREAM_ENDED_EARLY"
private const val AUDIO_SETUP_TIMEOUT = "AUDIO_SETUP_TIMEOUT"
private const val AUDIO_CLOSE_TIMEOUT = "AUDIO_CLOSE_TIMEOUT"
private const val AUDIO_STREAM_CLOSE_FAILED = "AUDIO_STREAM_CLOSE_FAILED"
private const val AUDIO_DEVICE_CLOSE_FAILED = "AUDIO_DEVICE_CLOSE_FAILED"
private const val AUDIO_TRANSPORT_CLOSE_FAILED = "AUDIO_TRANSPORT_CLOSE_FAILED"
private const val AUDIO_NEGOTIATED_SESSION_REQUIRED = "AUDIO_NEGOTIATED_SESSION_REQUIRED"
private const val AUDIO_INPUT_UNAVAILABLE = "AUDIO_INPUT_UNAVAILABLE"
private const val AUDIO_LIVE_REQUIRED = "AUDIO_LIVE_REQUIRED"
private const val AUDIO_FORMAT_AMBIGUOUS = "AUDIO_FORMAT_AMBIGUOUS"
private const val AUDIO_DRIVER_NEGOTIATION_FAILED = "AUDIO_DRIVER_NEGOTIATION_FAILED"
private const val AUDIO_BLE_OPERATION_FAILED = "AUDIO_BLE_OPERATION_FAILED"
private const val AUDIO_PROBE_CANCELLED = "AUDIO_PROBE_CANCELLED"
private const val AUDIO_PROBE_FAILED = "AUDIO_PROBE_FAILED"
