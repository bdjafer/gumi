package dev.gumi.edge.runtime.device

import dev.gumi.edge.runtime.capture.CaptureCommand
import dev.gumi.edge.runtime.capture.CaptureEffect
import dev.gumi.edge.runtime.capture.CaptureMode
import dev.gumi.edge.runtime.capture.CaptureReductionOutcome
import dev.gumi.edge.runtime.capture.CaptureState
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure

enum class DeviceSupervisorLifecycle {
    NEW,
    RUNNING,
    CLOSING,
    CLOSED,
}

enum class DeviceLinkState {
    UNKNOWN,
    CONNECTED,
    DISCONNECTED,
}

/** Commands are intent. They do not claim that the corresponding physical state was acquired. */
sealed interface DeviceSupervisorCommand {
    data class Capture(val command: CaptureCommand) : DeviceSupervisorCommand
}

/** Device facts enter the same serialized mailbox as commands. */
sealed interface DeviceSupervisorEvent {
    data class LinkChanged(
        val state: DeviceLinkState,
        val connectionSessionGeneration: ULong,
    ) : DeviceSupervisorEvent

    /** Explicit device evidence; the supervisor allocates its causal proof after validation. */
    data class CaptureStateObserved(
        val mode: CaptureMode,
        val connectionSessionGeneration: ULong,
    ) : DeviceSupervisorEvent

    data class FatalCaptureFault(
        val failure: ExpectedFailure,
        val recoveryCorrelationId: CorrelationId,
        val connectionSessionGeneration: ULong,
    ) : DeviceSupervisorEvent
}

sealed interface DeviceSupervisorOutcome {
    data class CaptureReduced(
        val outcome: CaptureReductionOutcome,
    ) : DeviceSupervisorOutcome

    data class LinkObserved(
        val state: DeviceLinkState,
    ) : DeviceSupervisorOutcome

    data class CaptureEffectFailed(
        val effect: CaptureEffect,
        val failure: ExpectedFailure,
        val failSafeOutcome: CaptureReductionOutcome? = null,
    ) : DeviceSupervisorOutcome

    data class CaptureObservationRejected(
        val failure: ExpectedFailure,
    ) : DeviceSupervisorOutcome
}

/** Immutable, host-neutral view published after each serialized state transition. */
data class DeviceSupervisorProjection(
    val deviceId: DeviceId,
    val lifecycle: DeviceSupervisorLifecycle = DeviceSupervisorLifecycle.NEW,
    val link: DeviceLinkState = DeviceLinkState.UNKNOWN,
    val connectionSessionGeneration: ULong? = null,
    val capture: CaptureState = CaptureState(),
    val sequence: Long = 0,
    val lastOutcome: DeviceSupervisorOutcome? = null,
    val lastFailure: ExpectedFailure? = null,
) {
    init {
        require(sequence >= 0) { "Device supervisor sequence cannot be negative" }
    }
}

sealed interface DeviceSupervisorStartResult {
    data object Started : DeviceSupervisorStartResult

    data object AlreadyRunning : DeviceSupervisorStartResult

    data class Rejected(val failure: ExpectedFailure) : DeviceSupervisorStartResult
}

sealed interface DeviceSupervisorSubmissionResult {
    data object Accepted : DeviceSupervisorSubmissionResult

    data class Rejected(val failure: ExpectedFailure) : DeviceSupervisorSubmissionResult
}
