package dev.gumi.edge.shell.android.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiagnosticOperation {
    AUDIO_METADATA,
    DRIVER_NEGOTIATION,
    FIRMWARE_IMAGE_STATE,
    GATT_INSPECTION,
}

data class DiagnosticOperationGateState(
    val activeOperation: DiagnosticOperation? = null,
    val cancelling: Boolean = false,
) {
    val busy: Boolean
        get() = activeOperation != null
}

/**
 * Process-local lease gate for diagnostics that share one physical BLE device.
 *
 * A lease remains active until its owner has completed transport cleanup. Release is identity-safe:
 * a stale or repeated close can never release a lease acquired by a later operation.
 */
class DiagnosticOperationGate {
    private val lock = Any()
    private val mutableState = MutableStateFlow(DiagnosticOperationGateState())
    private var activeLease: DiagnosticOperationLease? = null

    val state: StateFlow<DiagnosticOperationGateState> = mutableState.asStateFlow()

    fun tryAcquire(operation: DiagnosticOperation): DiagnosticOperationLease? = synchronized(lock) {
        if (activeLease != null) return@synchronized null

        DiagnosticOperationLease(this, operation).also { lease ->
            activeLease = lease
            mutableState.value = DiagnosticOperationGateState(activeOperation = operation)
        }
    }

    internal fun markCancelling(lease: DiagnosticOperationLease) = synchronized(lock) {
        if (activeLease === lease) {
            mutableState.value = DiagnosticOperationGateState(
                activeOperation = lease.operation,
                cancelling = true,
            )
        }
    }

    internal fun release(lease: DiagnosticOperationLease) = synchronized(lock) {
        if (activeLease === lease) {
            activeLease = null
            mutableState.value = DiagnosticOperationGateState()
        }
    }
}

class DiagnosticOperationLease internal constructor(
    private val gate: DiagnosticOperationGate,
    val operation: DiagnosticOperation,
) : AutoCloseable {
    fun markCancelling() {
        gate.markCancelling(this)
    }

    override fun close() {
        gate.release(this)
    }
}
