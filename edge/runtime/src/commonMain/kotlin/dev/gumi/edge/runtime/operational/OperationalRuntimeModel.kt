package dev.gumi.edge.runtime.operational

import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.capability.capture.DeviceCaptureState
import dev.gumi.edge.sdk.capability.power.PowerStatus

enum class OperationalRuntimeLifecycle {
    NEW,
    STARTING,
    READY,
    DEGRADED,
    STOPPING,
    STOPPED,
    CLOSED,
}

enum class OperationalLinkState {
    UNKNOWN,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}

enum class OperationalStorageState {
    CLOSED,
    OPENING,
    READY,
    DEGRADED,
}

/** Identifies whose pending-media facts [OperationalRuntimeProjection.backlog] describe. */
enum class OperationalBacklogScope {
    /** No attributed backlog observation is available. */
    UNAVAILABLE,

    /** The backlog is durably attributed to this exact provisioned device. */
    DEVICE,

    /** The backlog belongs to the edge host's process-global spool, not this device. */
    EDGE_HOST,
}

enum class OperationalCaptureTruth {
    UNVERIFIED,
    DEVICE_REPORTED,
}

data class OperationalBacklog(
    val pendingChunkCount: ULong,
    val retainedPayloadBytes: ULong,
) {
    companion object {
        val Empty = OperationalBacklog(0uL, 0uL)
    }
}

/**
 * Exact owner identity carried through one operational acquisition and all of its completions.
 * [sessionGeneration] is process-local; it is never persisted or treated as device identity.
 */
data class OperationalRuntimeOperation(
    val hostOperation: RuntimeHostOperation,
    val sessionGeneration: ULong,
) {
    init {
        require(sessionGeneration > 0uL) { "Operational session generation must be positive" }
    }
}

/**
 * Host-neutral operational truth for one runtime owner.
 *
 * Ephemeral endpoints, transport addresses, media, and credentials are intentionally absent.
 */
data class OperationalRuntimeProjection(
    val lifecycle: OperationalRuntimeLifecycle = OperationalRuntimeLifecycle.NEW,
    val ownerOperation: RuntimeHostOperation? = null,
    val sessionGeneration: ULong? = null,
    val deviceId: DeviceId? = null,
    val link: OperationalLinkState = OperationalLinkState.UNKNOWN,
    val capture: OperationalCaptureTruth = OperationalCaptureTruth.UNVERIFIED,
    /** Last accepted device observation; retained conservatively after disconnect. */
    val captureState: DeviceCaptureState? = null,
    /** Process-lineage revision, independent from a device protocol's wrapping generation. */
    val captureObservationRevision: ULong = 0uL,
    val power: PowerStatus? = null,
    /** Advances only when a concrete device power observation is accepted for this session. */
    val powerObservationRevision: ULong = 0uL,
    val storage: OperationalStorageState = OperationalStorageState.CLOSED,
    val backlog: OperationalBacklog = OperationalBacklog.Empty,
    val backlogScope: OperationalBacklogScope = OperationalBacklogScope.UNAVAILABLE,
    val lastFailure: ExpectedFailure? = null,
    val staleEventCount: ULong = 0uL,
    val sequence: Long = 0L,
) {
    init {
        require((ownerOperation == null) == (sessionGeneration == null)) {
            "Operational owner and session generation must be present together"
        }
        require(sequence >= 0L) { "Operational runtime sequence cannot be negative" }
        require((power == null) == (powerObservationRevision == 0uL)) {
            "Operational power value and observation revision must be present together"
        }
        require((captureState == null) == (captureObservationRevision == 0uL)) {
            "Operational capture value and observation revision must be present together"
        }
        require(
            capture != OperationalCaptureTruth.DEVICE_REPORTED || captureState != null,
        ) {
            "Device-reported operational capture truth needs a concrete observation"
        }
        if (lifecycle == OperationalRuntimeLifecycle.READY) {
            require(ownerOperation != null && deviceId != null) {
                "A ready operational runtime needs an owner and stable bound device"
            }
            require(link == OperationalLinkState.CONNECTED) {
                "A ready operational runtime needs a connected transport"
            }
            require(storage == OperationalStorageState.READY) {
                "A ready operational runtime needs reconciled durable storage"
            }
        }
        if (backlogScope == OperationalBacklogScope.UNAVAILABLE) {
            require(backlog == OperationalBacklog.Empty) {
                "Unavailable operational backlog cannot carry attributed counts"
            }
        }
    }
}
