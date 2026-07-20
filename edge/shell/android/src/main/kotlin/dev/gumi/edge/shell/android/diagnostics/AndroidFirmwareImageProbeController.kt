package dev.gumi.edge.shell.android.diagnostics

import android.util.Log
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspectionException
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspector
import dev.gumi.edge.sdk.firmware.FirmwareImageStateReadDisclosure
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class FirmwareImageProbeState(
    val inspecting: Boolean = false,
    val cancelling: Boolean = false,
    val inspection: FirmwareImageStateInspection? = null,
    val assessmentStatus: String? = null,
    val error: String? = null,
    /** Process-local gate identity only; never render, log, or persist this transport identifier. */
    internal val successfulEndpointEphemeralId: String? = null,
)

/** Returns one bounded, stable, non-sensitive assessment status for a completed inspection. */
fun interface FirmwareImageStateAssessment {
    fun assess(inspection: FirmwareImageStateInspection): String
}

class AndroidFirmwareImageProbeController(
    private val inspector: FirmwareImageStateInspector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val log: (String) -> Unit = { message -> Log.i(LOG_TAG, message) },
    private val nextAttemptId: () -> Long = ::nextProcessAttemptId,
    private val inspectionTimeoutMillis: Long = INSPECTION_TIMEOUT_MILLIS,
    private val assessment: FirmwareImageStateAssessment? = null,
    private val operationGate: DiagnosticOperationGate = DiagnosticOperationGate(),
) : AutoCloseable {
    init {
        require(inspectionTimeoutMillis > 0) { "Inspection timeout must be positive" }
    }

    private val mutableState = MutableStateFlow(FirmwareImageProbeState())
    private var activeOperation: FirmwareProbeOperation? = null

    val disclosure: FirmwareImageStateReadDisclosure = inspector.disclosure
    val state: StateFlow<FirmwareImageProbeState> = mutableState.asStateFlow()

    fun inspect(endpoint: EndpointCandidate) {
        if (activeOperation != null) return
        val lease = operationGate.tryAcquire(DiagnosticOperation.FIRMWARE_IMAGE_STATE) ?: return
        val attemptId = try {
            nextAttemptId().also { require(it > 0) { "Attempt ID must be positive" } }
        } catch (error: Exception) {
            lease.close()
            throw error
        }
        val operation = FirmwareProbeOperation(lease)
        activeOperation = operation
        log("MCU image-state semantic read attempt started: attempt=$attemptId")
        mutableState.value = FirmwareImageProbeState(inspecting = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var terminalState = FirmwareImageProbeState()
            try {
                val inspection = withTimeout(inspectionTimeoutMillis) {
                    inspector.inspect(endpoint)
                }
                require(inspection.endpoint == endpoint) {
                    "Firmware inspection belongs to a different endpoint"
                }
                val assessmentStatus = try {
                    assessment
                        ?.assess(inspection)
                        ?.also(::requireStableAssessmentStatus)
                        ?: ASSESSMENT_NOT_CONFIGURED
                } catch (_: Exception) {
                    throw FirmwareAssessmentFailed()
                }
                terminalState = FirmwareImageProbeState(
                    inspection = inspection,
                    assessmentStatus = assessmentStatus,
                    successfulEndpointEphemeralId = endpoint.ephemeralId,
                )
                if (!operation.cancelRequested) {
                    log(
                        "MCU image-state semantic read complete: " +
                            "attempt=$attemptId, protocol=${inspection.protocol}, " +
                            "slots=${inspection.slots.size}, " +
                            "splitStatus=${inspection.splitStatus}, " +
                            "assessment=$assessmentStatus",
                    )
                    inspection.slots.forEach { slot ->
                        log(
                            "Image ${slot.imageNumber} slot ${slot.slotNumber}: " +
                                "attempt=$attemptId, " +
                                "version=${slot.version}, hash=${slot.hash?.hex}, " +
                                "bootable=${slot.bootable}, pending=${slot.pending}, " +
                                "confirmed=${slot.confirmed}, active=${slot.active}, " +
                                "permanent=${slot.permanent}, compressed=${slot.compressed}",
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                terminalState = FirmwareImageProbeState(error = TIMEOUT_ERROR)
                log(
                    "MCU image-state semantic read failed: " +
                        "attempt=$attemptId, code=$TIMEOUT_CODE",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: FirmwareImageStateInspectionException) {
                terminalState = FirmwareImageProbeState(error = "${error.code}: ${error.message}")
            } catch (_: FirmwareAssessmentFailed) {
                terminalState = FirmwareImageProbeState(error = ASSESSMENT_ERROR)
            } catch (error: Exception) {
                terminalState = FirmwareImageProbeState(
                    error = "MCU image-state read failed (${error::class.simpleName})",
                )
            } finally {
                finishOperation(
                    operation,
                    if (operation.cancelRequested) FirmwareImageProbeState() else terminalState,
                )
            }
        }
        operation.job = job
        job.invokeOnCompletion { finishOperation(operation, FirmwareImageProbeState()) }
        job.start()
    }

    fun cancel() {
        val operation = activeOperation ?: return
        operation.cancelRequested = true
        operation.lease.markCancelling()
        mutableState.update { it.copy(inspecting = true, cancelling = true) }
        operation.job?.cancel()
    }

    /**
     * Invalidates the completed image-state result before a new BLE scan generation begins.
     * A prior result must not authorize audio through a still-resolvable endpoint-directory entry.
     */
    fun invalidateForNewScan() {
        if (activeOperation != null) return
        mutableState.value = FirmwareImageProbeState()
    }

    override fun close() {
        cancel()
        scope.cancel()
    }

    private fun finishOperation(
        operation: FirmwareProbeOperation,
        terminalState: FirmwareImageProbeState,
    ) {
        if (activeOperation === operation) {
            activeOperation = null
            mutableState.value = terminalState
        }
        operation.lease.close()
    }

    private companion object {
        const val LOG_TAG = "GumiFirmwareProbe"
        const val INSPECTION_TIMEOUT_MILLIS = 60_000L
        const val TIMEOUT_CODE = "MCU_IMAGE_STATE_TIMEOUT"
        const val TIMEOUT_ERROR = "$TIMEOUT_CODE: no response within 60 seconds"
        const val ASSESSMENT_NOT_CONFIGURED = "NOT_ASSESSED"
        const val ASSESSMENT_ERROR =
            "MCU_IMAGE_STATE_ASSESSMENT_FAILED: firmware assessment unavailable"
        val PROCESS_ATTEMPT_IDS = AtomicLong(0)
        val STABLE_ASSESSMENT_STATUS = Regex("[A-Z][A-Z0-9_]{0,63}")

        fun nextProcessAttemptId(): Long = PROCESS_ATTEMPT_IDS.incrementAndGet()

        fun requireStableAssessmentStatus(status: String) {
            require(STABLE_ASSESSMENT_STATUS.matches(status)) {
                "Firmware assessment must be a bounded stable status"
            }
        }
    }
}

private class FirmwareProbeOperation(
    val lease: DiagnosticOperationLease,
    var job: Job? = null,
    var cancelRequested: Boolean = false,
)

private class FirmwareAssessmentFailed : Exception()
