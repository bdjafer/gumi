package dev.gumi.edge.runtime.device

import dev.gumi.edge.runtime.capture.CaptureMode
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure

/** Narrow physical capture boundary owned by one [DeviceSupervisor]. */
interface CaptureHardwarePort {
    /**
     * Requests a mode without assuming that the requested mode was acquired.
     *
     * Implementations must return the correlation identity reported by the hardware operation.
     * A completion may therefore be recognized as stale without mutating current capture truth.
     * Cancellation is a physical fence: the call must not return from cancellation while detached
     * work can still acquire a mode later. It may settle a non-cancellable transport operation
     * first; the supervisor waits for that settlement before issuing an emergency release.
     */
    suspend fun requestMode(request: CaptureModeRequest): CaptureModeCompletion

    /**
     * Attempts to establish microphone-off after capture truth becomes unknown. The supervisor
     * invokes this only after every causally older mode request has settled.
     */
    suspend fun emergencyRelease(
        request: EmergencyCaptureReleaseRequest,
    ): EmergencyCaptureReleaseCompletion
}

data class CaptureModeRequest(
    val correlationId: CorrelationId,
    val targetMode: CaptureMode,
)

sealed interface CaptureModeCompletion {
    val correlationId: CorrelationId

    data class Acquired(
        override val correlationId: CorrelationId,
        val mode: CaptureMode,
    ) : CaptureModeCompletion

    data class Refused(
        override val correlationId: CorrelationId,
        val failure: ExpectedFailure,
    ) : CaptureModeCompletion

    /** The operation produced evidence that capture truth can no longer be trusted. */
    data class Fatal(
        override val correlationId: CorrelationId,
        val failure: ExpectedFailure,
    ) : CaptureModeCompletion
}

data class EmergencyCaptureReleaseRequest(
    val recoveryCorrelationId: CorrelationId,
)

sealed interface EmergencyCaptureReleaseCompletion {
    val recoveryCorrelationId: CorrelationId

    data class Released(
        override val recoveryCorrelationId: CorrelationId,
    ) : EmergencyCaptureReleaseCompletion

    /** Capture truth remains unknown; the supervisor never converts this into Idle. */
    data class Failed(
        override val recoveryCorrelationId: CorrelationId,
        val failure: ExpectedFailure,
    ) : EmergencyCaptureReleaseCompletion
}
