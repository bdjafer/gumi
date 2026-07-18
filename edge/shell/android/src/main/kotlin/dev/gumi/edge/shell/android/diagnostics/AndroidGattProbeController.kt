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
    val inspection: BleGattInspection? = null,
    val evidence: OmiCv1GattEvidence? = null,
    val error: String? = null,
)

class AndroidGattProbeController(
    private val inspector: BleGattInspector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(GattProbeState())
    private var inspectionJob: Job? = null

    val state: StateFlow<GattProbeState> = mutableState.asStateFlow()

    fun inspect(endpoint: EndpointCandidate) {
        if (inspectionJob?.isActive == true) return
        mutableState.update {
            GattProbeState(inspecting = true)
        }
        inspectionJob = scope.launch {
            try {
                val inspection = inspector.inspect(endpoint, OmiCv1GattProfile.readOnlyInspectionRequest)
                val evidence = OmiCv1GattProfile.decode(inspection)
                mutableState.update {
                    GattProbeState(
                        inspection = inspection,
                        evidence = evidence,
                    )
                }
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
                evidence.storageRawHex?.let { rawHex ->
                    Log.i(LOG_TAG, "Storage status raw hex: $rawHex")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: BleGattInspectionException) {
                mutableState.update {
                    GattProbeState(error = "${error.code}: ${error.message}")
                }
            } catch (error: Exception) {
                mutableState.update {
                    GattProbeState(error = "Read-only GATT inspection failed (${error::class.simpleName})")
                }
            } finally {
                mutableState.update { it.copy(inspecting = false) }
                inspectionJob = null
            }
        }
    }

    fun cancel() {
        inspectionJob?.cancel()
        inspectionJob = null
        mutableState.update { it.copy(inspecting = false) }
    }

    override fun close() {
        scope.cancel()
    }

    private companion object {
        const val LOG_TAG = "GumiGattProbe"
    }
}
