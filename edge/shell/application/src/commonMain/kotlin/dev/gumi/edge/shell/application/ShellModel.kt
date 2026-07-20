package dev.gumi.edge.shell.application

import dev.gumi.edge.runtime.capture.CaptureMode
import dev.gumi.edge.runtime.capture.CaptureState
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure

enum class ProjectionAuthority {
    DEVICE_REPORTED,
    EDGE_INFERRED,
    CLOUD_REPORTED,
}

enum class ObservationFreshness {
    FRESH,
    STALE,
    UNAVAILABLE,
}

/** Provenance travels with each independent axis so one subsystem cannot launder another's truth. */
data class AxisObservation<out T>(
    val value: T,
    val authority: ProjectionAuthority,
    /** Host receipt time for this fact, not an untrusted peripheral clock reading. */
    val observedAtEpochMillis: Long,
    val freshness: ObservationFreshness,
    /** Physical connection generation when this fact came from a device session. */
    val connectionSessionGeneration: ULong? = null,
) {
    init {
        require(observedAtEpochMillis >= 0) { "Observation time cannot be negative" }
    }

    fun <R> map(transform: (T) -> R): AxisObservation<R> = AxisObservation(
        value = transform(value),
        authority = authority,
        observedAtEpochMillis = observedAtEpochMillis,
        freshness = freshness,
        connectionSessionGeneration = connectionSessionGeneration,
    )
}

/** Timestamp policy evaluated by the projector; caller-supplied FRESH is never sufficient alone. */
data class ShellFreshnessPolicy(
    val captureMaxAgeMillis: Long = 30_000,
    val linkMaxAgeMillis: Long = 30_000,
    val otherAxisMaxAgeMillis: Long = 60_000,
) {
    init {
        require(captureMaxAgeMillis >= 0) { "Capture freshness window cannot be negative" }
        require(linkMaxAgeMillis >= 0) { "Link freshness window cannot be negative" }
        require(otherAxisMaxAgeMillis >= 0) { "Axis freshness window cannot be negative" }
    }
}

enum class LinkState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    READY,
    DEGRADED,
}

enum class MaintenanceState {
    NORMAL,
    PAIRING,
    AWAITING_PHYSICAL_CONFIRMATION,
    UPDATING,
    VALIDATING,
    RECOVERY_REQUIRED,
    SHUTTING_DOWN,
}

enum class UpdateStage {
    IDLE,
    PREPARING,
    UPLOADING,
    VERIFYING,
    REBOOTING,
    VALIDATING,
    RECOVERY_REQUIRED,
}

data class UpdateStatus(
    val stage: UpdateStage,
    val progressPercent: UInt? = null,
) {
    init {
        require(progressPercent == null || progressPercent <= 100u) {
            "Update progress must be between zero and one hundred"
        }
        require(progressPercent == null || stage in PROGRESS_STAGES) {
            "Only an upload or verification stage may expose update progress"
        }
    }
}

enum class SyncState {
    CURRENT,
    UPLOADING,
    CLOUD_OFFLINE_SAVED_LOCALLY,
    BLOCKED,
    UNKNOWN,
}

data class BacklogStatus(
    val pendingItems: ULong? = null,
    val pendingBytes: ULong? = null,
    val oldestItemAtEpochMillis: Long? = null,
) {
    init {
        require(oldestItemAtEpochMillis == null || oldestItemAtEpochMillis >= 0) {
            "Oldest backlog item time cannot be negative"
        }
    }
}

data class SyncStatus(
    val state: SyncState,
    val backlog: BacklogStatus,
)

enum class PowerState {
    UNKNOWN,
    OFF,
    BOOTING,
    OPERATIONAL,
    SHUTTING_DOWN,
}

enum class PowerLevel {
    NORMAL,
    LOW,
    CRITICAL,
    UNKNOWN,
}

data class PowerStatus(
    val state: PowerState,
    val batteryPercent: UInt? = null,
    val level: PowerLevel = PowerLevel.UNKNOWN,
    val charging: Boolean? = null,
) {
    init {
        require(batteryPercent == null || batteryPercent <= 100u) {
            "Battery percentage must be between zero and one hundred"
        }
    }
}

enum class StorageState {
    HEALTHY,
    LOW,
    FULL,
    CORRUPT,
    UNKNOWN,
}

data class StorageStatus(
    val state: StorageState,
    val availableBytes: ULong? = null,
    val capacityBytes: ULong? = null,
) {
    init {
        require(
            availableBytes == null || capacityBytes == null || availableBytes <= capacityBytes,
        ) { "Available storage cannot exceed capacity" }
    }
}

enum class FaultSeverity {
    NONE,
    WARNING,
    RECOVERABLE,
    FATAL_PRIVACY,
}

data class FaultStatus(
    val severity: FaultSeverity,
    val failure: ExpectedFailure? = null,
)

data class DeviceShellSnapshot(
    val deviceId: DeviceId,
    val displayName: String,
    val capture: AxisObservation<CaptureState>,
    val link: AxisObservation<LinkState>,
    val maintenance: AxisObservation<MaintenanceState>,
    val update: AxisObservation<UpdateStatus>,
    val sync: AxisObservation<SyncStatus>,
    val power: AxisObservation<PowerStatus>,
    val storage: AxisObservation<StorageStatus>,
    val fault: AxisObservation<FaultStatus>,
    val pendingCommandId: CommandId? = null,
) {
    init {
        require(displayName.isNotBlank()) { "Device display name cannot be blank" }
        require(displayName == displayName.trim()) {
            "Device display name cannot have surrounding whitespace"
        }
        require(displayName.none(Char::isISOControl)) {
            "Device display name cannot contain control characters"
        }
        val captureCommand = capture.value.transition?.command?.id
        require(captureCommand == null || pendingCommandId == null || captureCommand == pendingCommandId) {
            "Snapshot cannot identify two different pending commands"
        }
    }
}

enum class CapturePresentationKind {
    VERIFIED_OFF,
    STARTING,
    RECORDING,
    RECORDING_STARTING_VOICE_TURN,
    VOICE_TURN,
    RECORDING_WITH_VOICE_TURN,
    RECORDING_ENDING_VOICE_TURN,
    STOPPING,
    MAY_BE_RECORDING,
    UNKNOWN,
}

enum class CaptureAssurance {
    VERIFIED_OFF,
    ACTIVE,
    MAY_BE_ACTIVE,
}

/** Text is mandatory and semantic. Rendering may add icons, but never relies on color alone. */
data class CapturePresentation(
    val kind: CapturePresentationKind,
    val assurance: CaptureAssurance,
    val label: String,
    val accessibilityLabel: String = label,
    val lastReportedMode: CaptureMode?,
    val requestedMode: CaptureMode?,
    val failure: ExpectedFailure? = null,
) {
    init {
        require(label.isNotBlank()) { "Capture label cannot be blank" }
        require(accessibilityLabel.isNotBlank()) { "Capture accessibility label cannot be blank" }
    }
}

data class ShellProjection(
    val deviceId: DeviceId,
    val displayName: String,
    val projectedAtEpochMillis: Long,
    val pendingCommandId: CommandId?,
    val link: AxisObservation<LinkState>,
    val capture: AxisObservation<CapturePresentation>,
    val maintenance: AxisObservation<MaintenanceState>,
    val update: AxisObservation<UpdateStatus>,
    val sync: AxisObservation<SyncStatus>,
    val power: AxisObservation<PowerStatus>,
    val storage: AxisObservation<StorageStatus>,
    val fault: AxisObservation<FaultStatus>,
)

enum class FleetCaptureState {
    ACTIVE,
    MAY_BE_ACTIVE,
    ALL_VERIFIED_OFF,
    NO_MANAGED_DEVICES,
}

data class FleetCaptureProjection(
    val state: FleetCaptureState,
    val label: String,
    val activeDeviceIds: Set<DeviceId>,
    val uncertainDeviceIds: Set<DeviceId>,
)

data class FleetShellProjection(
    val devices: List<ShellProjection>,
    val capture: FleetCaptureProjection,
)

private val PROGRESS_STAGES = setOf(UpdateStage.UPLOADING, UpdateStage.VERIFYING)
