package dev.gumi.edge.shell.android.diagnostics

import android.util.Log
import dev.gumi.devices.omicv1.OmiCv1GattEvidence
import dev.gumi.devices.omicv1.OmiCv1GattProfile
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.ble.BleGattInspection
import dev.gumi.edge.sdk.ble.BleGattInspectionException
import dev.gumi.edge.sdk.ble.BleGattInspector
import dev.gumi.edge.sdk.ble.BleGattReadResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GattProbeState(
    val inspecting: Boolean = false,
    val cancelling: Boolean = false,
    val inspection: BleGattInspection? = null,
    val evidence: OmiCv1GattEvidence? = null,
    val error: String? = null,
)

class AndroidGattProbeController(
    private val inspector: BleGattInspector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val operationGate: DiagnosticOperationGate = DiagnosticOperationGate(),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(GattProbeState())
    private var activeOperation: GattProbeOperation? = null

    val state: StateFlow<GattProbeState> = mutableState.asStateFlow()

    fun inspect(endpoint: EndpointCandidate) {
        if (activeOperation != null) return
        val lease = operationGate.tryAcquire(DiagnosticOperation.GATT_INSPECTION) ?: return
        val operation = GattProbeOperation(lease)
        activeOperation = operation
        mutableState.value = GattProbeState(inspecting = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var terminalState = GattProbeState()
            try {
                val inspection = inspector.inspect(endpoint, OmiCv1GattProfile.readOnlyInspectionRequest)
                val evidence = OmiCv1GattProfile.decode(inspection)
                terminalState = GattProbeState(
                    inspection = inspection,
                    evidence = evidence,
                )
                if (!operation.cancelRequested) {
                    Log.i(
                        LOG_TAG,
                        "Read-only GATT inspection complete: " +
                            "services=${inspection.services.size}, " +
                            "characteristics=${inspection.services.sumOf { it.characteristics.size }}, " +
                            "mtu=${inspection.link.mtu}, " +
                            "txPhy=${inspection.link.txPhy}, " +
                            "rxPhy=${inspection.link.rxPhy}, " +
                            "bond=${inspection.link.bondState}, " +
                            "allowlistedReads=${inspection.reads.size}, " +
                            "readFailures=${evidence.readFailures.size}",
                    )
                    Log.i(
                        LOG_TAG,
                        "GATT service tree: " + inspection.services.joinToString(separator = "; ") { service ->
                            "${service.uuid}{" + service.characteristics.joinToString(separator = ",") { characteristic ->
                                "${characteristic.uuid}:${characteristic.properties.sortedBy { it.name }}"
                            } + "}"
                        },
                    )
                    Log.i(
                        LOG_TAG,
                        "Allowlisted read shapes: " + inspection.reads.joinToString(separator = ", ") { read ->
                            when (read) {
                                is BleGattReadResult.Success ->
                                    "${read.target.characteristicUuid}=${read.value.size}B"
                                is BleGattReadResult.Failure ->
                                    "${read.target.characteristicUuid}=FAIL(${read.code})"
                            }
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: BleGattInspectionException) {
                terminalState = GattProbeState(error = "${error.code}: ${error.message}")
            } catch (error: Exception) {
                terminalState =
                    GattProbeState(error = "Read-only GATT inspection failed (${error::class.simpleName})")
            } finally {
                finishOperation(
                    operation,
                    if (operation.cancelRequested) GattProbeState() else terminalState,
                )
            }
        }
        operation.job = job
        job.invokeOnCompletion { finishOperation(operation, GattProbeState()) }
        job.start()
    }

    fun cancel() {
        val operation = activeOperation ?: return
        operation.cancelRequested = true
        operation.lease.markCancelling()
        mutableState.update { it.copy(inspecting = true, cancelling = true) }
        operation.job?.cancel()
    }

    override fun close() {
        cancel()
        scope.cancel()
    }

    private fun finishOperation(operation: GattProbeOperation, terminalState: GattProbeState) {
        if (activeOperation === operation) {
            activeOperation = null
            mutableState.value = terminalState
        }
        operation.lease.close()
    }

    private companion object {
        const val LOG_TAG = "GumiGattProbe"
    }
}

private class GattProbeOperation(
    val lease: DiagnosticOperationLease,
    var job: Job? = null,
    var cancelRequested: Boolean = false,
)
