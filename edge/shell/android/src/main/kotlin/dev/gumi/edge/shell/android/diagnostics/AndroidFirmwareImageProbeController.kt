package dev.gumi.edge.shell.android.diagnostics

import android.util.Log
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspectionException
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspector
import dev.gumi.edge.sdk.firmware.FirmwareImageStateReadDisclosure
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

data class FirmwareImageProbeState(
    val inspecting: Boolean = false,
    val inspection: FirmwareImageStateInspection? = null,
    val error: String? = null,
)

class AndroidFirmwareImageProbeController(
    private val inspector: FirmwareImageStateInspector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(FirmwareImageProbeState())
    private var inspectionJob: Job? = null

    val disclosure: FirmwareImageStateReadDisclosure = inspector.disclosure
    val state: StateFlow<FirmwareImageProbeState> = mutableState.asStateFlow()

    fun inspect(endpoint: EndpointCandidate) {
        if (inspectionJob?.isActive == true) return
        mutableState.update { FirmwareImageProbeState(inspecting = true) }
        inspectionJob = scope.launch {
            try {
                val inspection = inspector.inspect(endpoint)
                mutableState.update { FirmwareImageProbeState(inspection = inspection) }
                Log.i(
                    LOG_TAG,
                    "MCU image-state semantic read complete: " +
                        "protocol=${inspection.protocol}, slots=${inspection.slots.size}, " +
                        "splitStatus=${inspection.splitStatus}",
                )
                inspection.slots.forEach { slot ->
                    Log.i(
                        LOG_TAG,
                        "Image ${slot.imageNumber} slot ${slot.slotNumber}: " +
                            "version=${slot.version}, hash=${slot.hash?.hex}, " +
                            "bootable=${slot.bootable}, pending=${slot.pending}, " +
                            "confirmed=${slot.confirmed}, active=${slot.active}, " +
                            "permanent=${slot.permanent}, compressed=${slot.compressed}",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: FirmwareImageStateInspectionException) {
                mutableState.update {
                    FirmwareImageProbeState(error = "${error.code}: ${error.message}")
                }
            } catch (error: Exception) {
                mutableState.update {
                    FirmwareImageProbeState(
                        error = "MCU image-state read failed (${error::class.simpleName})",
                    )
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
        const val LOG_TAG = "GumiFirmwareProbe"
    }
}
