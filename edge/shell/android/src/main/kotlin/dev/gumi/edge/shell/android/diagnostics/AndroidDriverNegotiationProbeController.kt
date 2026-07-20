package dev.gumi.edge.shell.android.diagnostics

import android.util.Log
import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.sdk.CapabilityDescriptor
import dev.gumi.edge.sdk.DeviceOpenException
import dev.gumi.edge.sdk.DeviceSession
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.NegotiatedDeviceSession
import dev.gumi.edge.sdk.ble.BleCentral
import dev.gumi.edge.sdk.ble.BleLinkSnapshot
import dev.gumi.edge.sdk.ble.BleSessionException
import dev.gumi.edge.sdk.ble.BleTransportSession
import dev.gumi.edge.sdk.capability.audio.AudioInputDescriptor
import dev.gumi.edge.sdk.capability.button.ButtonGestureDescriptor
import dev.gumi.edge.sdk.capability.haptic.HapticDescriptor
import dev.gumi.edge.sdk.capability.power.PowerStatusDescriptor
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class NegotiatedCapabilityProjection(
    val key: String,
    val version: String,
    val required: Boolean,
    /** Attribute names only. Values can contain device- or protocol-specific evidence. */
    val attributeNames: Set<String>,
    /** Allowlisted, typed descriptor fields that are safe to render on the local diagnostics UI. */
    val fields: Map<String, String>,
)

data class DriverNegotiationProjection(
    val manufacturer: String,
    val model: String,
    val driver: String,
    val protocol: String,
    val capabilities: List<NegotiatedCapabilityProjection>,
    val link: BleLinkSnapshot,
)

data class DriverNegotiationProbeState(
    val connecting: Boolean = false,
    val cancelling: Boolean = false,
    val projection: DriverNegotiationProjection? = null,
    val error: String? = null,
)

/**
 * Explicit diagnostic owner for one connect -> driver negotiation -> disconnect operation.
 *
 * A successful Omi negotiation currently performs service discovery, three Device Information
 * reads, and the exact audio codec-ID read required by the driver. This controller intentionally
 * does not obtain or collect any capability stream and never calls a capability action. The
 * negotiated device session is always closed before the result is published as idle, including
 * cancellation and failure paths.
 */
class AndroidDriverNegotiationProbeController(
    private val central: BleCentral,
    private val driverRegistry: DeviceDriverRegistry,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val log: (String) -> Unit = { message -> Log.i(LOG_TAG, message) },
    private val nextAttemptId: () -> Long = ::nextProcessAttemptId,
    private val closeTimeoutMillis: Long = CLOSE_TIMEOUT_MILLIS,
    private val operationGate: DiagnosticOperationGate = DiagnosticOperationGate(),
) : AutoCloseable {
    init {
        require(closeTimeoutMillis > 0) { "Close timeout must be positive" }
    }

    private val mutableState = MutableStateFlow(DriverNegotiationProbeState())
    private var activeOperation: DriverProbeOperation? = null

    val state: StateFlow<DriverNegotiationProbeState> = mutableState.asStateFlow()

    /** Must only be called from the user's disclosed connect-and-negotiate action. */
    fun probe(endpoint: EndpointCandidate) {
        if (activeOperation != null) return
        val lease = operationGate.tryAcquire(DiagnosticOperation.DRIVER_NEGOTIATION) ?: return
        val attemptId = try {
            nextAttemptId().also { require(it > 0) { "Attempt ID must be positive" } }
        } catch (error: Exception) {
            lease.close()
            throw error
        }
        val operation = DriverProbeOperation(lease)
        activeOperation = operation
        log("Operational driver negotiation attempt started: attempt=$attemptId")
        mutableState.value = DriverNegotiationProbeState(connecting = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var transport: BleTransportSession? = null
            var deviceSession: DeviceSession? = null
            var terminalState: DriverNegotiationProbeState? = null
            var terminalLog: String? = null
            try {
                transport = central.connect(endpoint)
                deviceSession = driverRegistry.open(endpoint, transport)
                val negotiated = deviceSession as? NegotiatedDeviceSession
                    ?: error("The selected driver did not return a negotiated session")
                val projection = negotiated.toProjection(transport.link)

                terminalLog = projection.redactedLogShape(attemptId)
                terminalState = DriverNegotiationProbeState(projection = projection)
            } catch (error: CancellationException) {
                throw error
            } catch (error: DeviceOpenException) {
                terminalState = DriverNegotiationProbeState(
                    error = "${error.failure.code.value}: driver negotiation was rejected",
                )
            } catch (error: BleSessionException) {
                terminalState = DriverNegotiationProbeState(
                    error = "${error.code}: BLE connect or negotiation failed",
                )
            } catch (error: Exception) {
                terminalState = DriverNegotiationProbeState(
                    error = "Driver negotiation failed (${error::class.simpleName})",
                )
            } finally {
                val cleanupFailure = withContext(NonCancellable) {
                    closeAll(deviceSession, transport)
                }
                val finalState = when {
                    cleanupFailure != null -> DriverNegotiationProbeState(
                        error = "$cleanupFailure: driver negotiation cleanup failed",
                    )
                    operation.cancelRequested -> DriverNegotiationProbeState()
                    else -> terminalState ?: DriverNegotiationProbeState()
                }
                try {
                    when {
                        cleanupFailure != null -> log(
                            "Operational driver negotiation failed: " +
                                "attempt=$attemptId, code=$cleanupFailure",
                        )
                        !operation.cancelRequested -> terminalLog?.let(log)
                    }
                } finally {
                    finishOperation(operation, finalState)
                }
            }
        }
        operation.job = job
        job.invokeOnCompletion { finishOperation(operation, DriverNegotiationProbeState()) }
        job.start()
    }

    fun cancel() {
        val operation = activeOperation ?: return
        operation.cancelRequested = true
        operation.lease.markCancelling()
        mutableState.value = mutableState.value.copy(connecting = true, cancelling = true)
        operation.job?.cancel()
    }

    override fun close() {
        cancel()
        scope.cancel()
    }

    private suspend fun closeAll(
        deviceSession: DeviceSession?,
        transport: BleTransportSession?,
    ): String? {
        var failure: String? = null

        suspend fun closeOne(
            timeoutCode: String,
            failureCode: String,
            block: suspend () -> Unit,
        ) {
            try {
                withTimeout(closeTimeoutMillis) { block() }
            } catch (_: TimeoutCancellationException) {
                if (failure == null) failure = timeoutCode
            } catch (_: Exception) {
                if (failure == null) failure = failureCode
            }
        }

        if (deviceSession != null) {
            closeOne(
                DRIVER_DEVICE_CLOSE_TIMEOUT,
                DRIVER_DEVICE_CLOSE_FAILED,
                deviceSession::close,
            )
        }
        if (transport != null) {
            closeOne(
                DRIVER_TRANSPORT_CLOSE_TIMEOUT,
                DRIVER_TRANSPORT_CLOSE_FAILED,
                transport::close,
            )
        }
        return failure
    }

    private fun finishOperation(
        operation: DriverProbeOperation,
        terminalState: DriverNegotiationProbeState,
    ) {
        if (activeOperation === operation) {
            activeOperation = null
            mutableState.value = terminalState
        }
        operation.lease.close()
    }

    private companion object {
        const val LOG_TAG = "GumiDriverProbe"
        // AndroidBleTransportSession may spend its full five-second platform disconnect window
        // before it can close the local manager. The diagnostic deadline must strictly contain
        // that operation instead of racing it and turning successful negotiation into a false
        // DRIVER_DEVICE_CLOSE_TIMEOUT.
        const val CLOSE_TIMEOUT_MILLIS = 10_000L
        const val DRIVER_DEVICE_CLOSE_TIMEOUT = "DRIVER_DEVICE_CLOSE_TIMEOUT"
        const val DRIVER_DEVICE_CLOSE_FAILED = "DRIVER_DEVICE_CLOSE_FAILED"
        const val DRIVER_TRANSPORT_CLOSE_TIMEOUT = "DRIVER_TRANSPORT_CLOSE_TIMEOUT"
        const val DRIVER_TRANSPORT_CLOSE_FAILED = "DRIVER_TRANSPORT_CLOSE_FAILED"
        val PROCESS_ATTEMPT_IDS = AtomicLong(0)

        fun nextProcessAttemptId(): Long = PROCESS_ATTEMPT_IDS.incrementAndGet()
    }
}

private class DriverProbeOperation(
    val lease: DiagnosticOperationLease,
    var job: Job? = null,
    var cancelRequested: Boolean = false,
)

private fun NegotiatedDeviceSession.toProjection(link: BleLinkSnapshot): DriverNegotiationProjection =
    DriverNegotiationProjection(
        manufacturer = descriptor.manufacturer,
        model = descriptor.model,
        driver = descriptor.driverId.value,
        protocol = descriptor.protocolVersion,
        capabilities = capabilities.descriptors
            .sortedBy { it.key.value }
            .map(CapabilityDescriptor::toProjection),
        link = link,
    )

private fun CapabilityDescriptor.toProjection() = NegotiatedCapabilityProjection(
    key = key.value,
    version = version.toString(),
    required = required,
    attributeNames = attributes.keys.toSortedSet(),
    fields = when (this) {
        is AudioInputDescriptor -> mapOf(
            "formats" to formats.sortedBy { it.codec.name }.joinToString { format ->
                "${format.codec} ${format.sampleRateHz}Hz ${format.channels}ch" +
                    (format.frameDurationMillis?.let { " ${it}ms" } ?: "")
            },
            "live" to live.toString(),
            "stored" to stored.toString(),
        )
        is ButtonGestureDescriptor -> mapOf(
            "gestures" to gestures.sortedBy { it.name }.joinToString(),
            "configurable" to configurable.toString(),
        )
        is HapticDescriptor -> mapOf(
            "patterns" to patterns.sortedBy { it.value }.joinToString { it.value },
        )
        is PowerStatusDescriptor -> mapOf(
            "reportsBatteryPercent" to reportsBatteryPercent.toString(),
            "reportsCharging" to reportsCharging.toString(),
        )
        else -> emptyMap()
    },
)

private fun DriverNegotiationProjection.redactedLogShape(attemptId: Long): String =
    "Operational driver negotiation complete: " +
        "attempt=$attemptId, manufacturer=$manufacturer, " +
        "model=$model, " +
        "driver=$driver, " +
        "protocol=$protocol, " +
        "capabilities=${capabilities.joinToString(prefix = "[", postfix = "]") { capability ->
            "${capability.key}@${capability.version}" +
                "(required=${capability.required}," +
                "attributeNames=${capability.attributeNames}," +
                "fields=${capability.fields.toSortedMap()})"
        }}, " +
        "link=(mtu=${link.mtu},txPhy=${link.txPhy},rxPhy=${link.rxPhy},bond=${link.bondState})"
