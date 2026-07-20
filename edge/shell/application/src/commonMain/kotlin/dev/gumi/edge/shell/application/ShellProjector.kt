package dev.gumi.edge.shell.application

import dev.gumi.edge.runtime.capture.CaptureMode
import dev.gumi.edge.runtime.capture.CaptureState
import dev.gumi.edge.runtime.capture.CaptureTruth

object ShellProjector {
    fun project(
        snapshot: DeviceShellSnapshot,
        projectedAtEpochMillis: Long,
        freshnessPolicy: ShellFreshnessPolicy = ShellFreshnessPolicy(),
    ): ShellProjection {
        require(projectedAtEpochMillis >= 0) { "Projection time cannot be negative" }

        val normalized = snapshot.copy(
            capture = snapshot.capture.atProjectionTime(
                projectedAtEpochMillis,
                freshnessPolicy.captureMaxAgeMillis,
            ),
            link = snapshot.link.atProjectionTime(
                projectedAtEpochMillis,
                freshnessPolicy.linkMaxAgeMillis,
            ),
            maintenance = snapshot.maintenance.atProjectionTime(
                projectedAtEpochMillis,
                freshnessPolicy.otherAxisMaxAgeMillis,
            ),
            update = snapshot.update.atProjectionTime(
                projectedAtEpochMillis,
                freshnessPolicy.otherAxisMaxAgeMillis,
            ),
            sync = snapshot.sync.atProjectionTime(
                projectedAtEpochMillis,
                freshnessPolicy.otherAxisMaxAgeMillis,
            ),
            power = snapshot.power.atProjectionTime(
                projectedAtEpochMillis,
                freshnessPolicy.otherAxisMaxAgeMillis,
            ),
            storage = snapshot.storage.atProjectionTime(
                projectedAtEpochMillis,
                freshnessPolicy.otherAxisMaxAgeMillis,
            ),
            fault = snapshot.fault.atProjectionTime(
                projectedAtEpochMillis,
                freshnessPolicy.otherAxisMaxAgeMillis,
            ),
        )
        val capture = projectCapture(normalized, projectedAtEpochMillis)
        return ShellProjection(
            deviceId = normalized.deviceId,
            displayName = normalized.displayName,
            projectedAtEpochMillis = projectedAtEpochMillis,
            pendingCommandId = normalized.capture.value.transition?.command?.id
                ?: normalized.pendingCommandId,
            link = normalized.link,
            capture = capture,
            maintenance = normalized.maintenance,
            update = normalized.update,
            sync = normalized.sync,
            power = normalized.power,
            storage = normalized.storage,
            fault = normalized.fault,
        )
    }

    private fun projectCapture(
        snapshot: DeviceShellSnapshot,
        projectedAtEpochMillis: Long,
    ): AxisObservation<CapturePresentation> {
        val observation = snapshot.capture
        val state = observation.value
        val unknownTruth = state.truth as? CaptureTruth.Unknown
        val acquiredTruth = state.truth as? CaptureTruth.Acquired
        val sessionEvidenceIsCoherent = acquiredTruth != null &&
            observation.connectionSessionGeneration != null &&
            observation.connectionSessionGeneration == snapshot.link.connectionSessionGeneration &&
            observation.connectionSessionGeneration ==
            acquiredTruth.proof.connectionSessionGeneration
        val unknownEvidenceIsCoherent = unknownTruth != null &&
            observation.connectionSessionGeneration != null &&
            observation.connectionSessionGeneration == snapshot.link.connectionSessionGeneration &&
            observation.connectionSessionGeneration == unknownTruth.connectionSessionGeneration
        val deviceEvidenceIsCurrent = observation.authority == ProjectionAuthority.DEVICE_REPORTED &&
            observation.freshness == ObservationFreshness.FRESH &&
            snapshot.link.value == LinkState.READY &&
            snapshot.link.freshness == ObservationFreshness.FRESH &&
            sessionEvidenceIsCoherent
        val fatalPrivacy = snapshot.fault.value.severity == FaultSeverity.FATAL_PRIVACY

        val presentation = when {
            fatalPrivacy -> unknown(
                state = state,
                failure = snapshot.fault.value.failure,
            )

            unknownTruth != null -> unknown(
                state = state,
                failure = unknownTruth.failure,
            )

            !deviceEvidenceIsCurrent -> untrustedOrStale(snapshot)
            else -> authoritative(snapshot, projectedAtEpochMillis)
        }

        val effectiveFreshness = if (
            deviceEvidenceIsCurrent ||
            unknownEvidenceIsCoherent &&
            observation.freshness == ObservationFreshness.FRESH &&
            snapshot.link.freshness == ObservationFreshness.FRESH
        ) {
            observation.freshness
        } else if (observation.freshness == ObservationFreshness.UNAVAILABLE) {
            ObservationFreshness.UNAVAILABLE
        } else {
            ObservationFreshness.STALE
        }

        return AxisObservation(
            value = presentation,
            authority = observation.authority,
            observedAtEpochMillis = observation.observedAtEpochMillis,
            freshness = effectiveFreshness,
            connectionSessionGeneration = observation.connectionSessionGeneration,
        )
    }

    private fun authoritative(
        snapshot: DeviceShellSnapshot,
        projectedAtEpochMillis: Long,
    ): CapturePresentation {
        val state = snapshot.capture.value
        val truth = state.truth as CaptureTruth.Acquired
        val requested = state.transition?.targetMode

        return when (truth.mode) {
            CaptureMode.IDLE -> when (requested) {
                null,
                CaptureMode.IDLE,
                -> presentation(
                    kind = CapturePresentationKind.VERIFIED_OFF,
                    assurance = CaptureAssurance.VERIFIED_OFF,
                    label = "Microphone off — confirmed by ${snapshot.displayName} " +
                        "${age(snapshot.capture.observedAtEpochMillis, projectedAtEpochMillis)} ago",
                    state = state,
                )

                CaptureMode.RECORDING,
                CaptureMode.VOICE_TURN,
                -> presentation(
                    kind = CapturePresentationKind.STARTING,
                    assurance = CaptureAssurance.MAY_BE_ACTIVE,
                    label = "Starting capture…",
                    state = state,
                )
            }

            CaptureMode.RECORDING -> when (requested) {
                CaptureMode.IDLE -> presentation(
                    kind = CapturePresentationKind.STOPPING,
                    assurance = CaptureAssurance.ACTIVE,
                    label = "Stopping — microphone may still be active",
                    state = state,
                )

                CaptureMode.VOICE_TURN -> presentation(
                    kind = CapturePresentationKind.RECORDING_STARTING_VOICE_TURN,
                    assurance = CaptureAssurance.ACTIVE,
                    label = "Recording locally — starting voice turn…",
                    state = state,
                )

                null,
                CaptureMode.RECORDING,
                -> presentation(
                    kind = CapturePresentationKind.RECORDING,
                    assurance = CaptureAssurance.ACTIVE,
                    label = recordingLabel(snapshot.sync),
                    state = state,
                )
            }

            CaptureMode.VOICE_TURN -> when {
                requested == CaptureMode.IDLE -> presentation(
                    kind = CapturePresentationKind.STOPPING,
                    assurance = CaptureAssurance.ACTIVE,
                    label = "Stopping — microphone may still be active",
                    state = state,
                )

                requested == CaptureMode.RECORDING -> presentation(
                    kind = CapturePresentationKind.RECORDING_ENDING_VOICE_TURN,
                    assurance = CaptureAssurance.ACTIVE,
                    label = "Recording locally — ending voice turn…",
                    state = state,
                )

                state.resumeAfterVoiceTurn == CaptureMode.RECORDING -> presentation(
                    kind = CapturePresentationKind.RECORDING_WITH_VOICE_TURN,
                    assurance = CaptureAssurance.ACTIVE,
                    label = "Recording + voice turn",
                    state = state,
                )

                else -> presentation(
                    kind = CapturePresentationKind.VOICE_TURN,
                    assurance = CaptureAssurance.ACTIVE,
                    label = "Listening for this voice turn",
                    state = state,
                )
            }
        }
    }

    private fun untrustedOrStale(snapshot: DeviceShellSnapshot): CapturePresentation {
        val state = snapshot.capture.value
        val lastReportedMode = when (val truth = state.truth) {
            is CaptureTruth.Acquired -> truth.mode
            is CaptureTruth.Unverified -> truth.lastReportedMode
            is CaptureTruth.Unknown -> null
        }
        val baseRecordingWasReported = lastReportedMode == CaptureMode.RECORDING ||
            lastReportedMode == CaptureMode.VOICE_TURN &&
            state.resumeAfterVoiceTurn == CaptureMode.RECORDING

        if (baseRecordingWasReported) {
            val label = if (snapshot.link.value == LinkState.DISCONNECTED) {
                "Device disconnected; recording may continue locally"
            } else {
                "Recording may still be active — device confirmation is stale"
            }
            return presentation(
                kind = CapturePresentationKind.MAY_BE_RECORDING,
                assurance = CaptureAssurance.MAY_BE_ACTIVE,
                label = label,
                state = state,
            )
        }
        return unknown(state)
    }

    private fun unknown(
        state: CaptureState,
        failure: dev.gumi.edge.sdk.ExpectedFailure? = null,
    ): CapturePresentation = presentation(
        kind = CapturePresentationKind.UNKNOWN,
        assurance = CaptureAssurance.MAY_BE_ACTIVE,
        label = "Microphone state unknown — check the device privacy light",
        state = state,
        failure = failure,
    )

    private fun presentation(
        kind: CapturePresentationKind,
        assurance: CaptureAssurance,
        label: String,
        state: CaptureState,
        failure: dev.gumi.edge.sdk.ExpectedFailure? = null,
    ) = CapturePresentation(
        kind = kind,
        assurance = assurance,
        label = label,
        lastReportedMode = when (val truth = state.truth) {
            is CaptureTruth.Acquired -> truth.mode
            is CaptureTruth.Unverified -> truth.lastReportedMode
            is CaptureTruth.Unknown -> null
        },
        requestedMode = state.transition?.targetMode,
        failure = failure,
    )

    private fun recordingLabel(sync: AxisObservation<SyncStatus>): String {
        if (sync.freshness != ObservationFreshness.FRESH) return "Recording locally"
        return when (sync.value.state) {
            SyncState.UPLOADING -> "Recording — uploading"
            SyncState.CLOUD_OFFLINE_SAVED_LOCALLY ->
                "Recording — cloud offline, saved locally"

            SyncState.CURRENT,
            SyncState.BLOCKED,
            SyncState.UNKNOWN,
            -> "Recording locally"
        }
    }

    private fun age(observedAtEpochMillis: Long, projectedAtEpochMillis: Long): String {
        val seconds = ((projectedAtEpochMillis - observedAtEpochMillis).coerceAtLeast(0)) / 1_000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3_600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3_600}h"
            else -> "${seconds / 86_400}d"
        }
    }
}

private fun <T> AxisObservation<T>.atProjectionTime(
    projectedAtEpochMillis: Long,
    maxAgeMillis: Long,
): AxisObservation<T> {
    if (freshness != ObservationFreshness.FRESH) return this
    val timestampIsCurrent = observedAtEpochMillis <= projectedAtEpochMillis &&
        projectedAtEpochMillis - observedAtEpochMillis <= maxAgeMillis
    return if (timestampIsCurrent) this else copy(freshness = ObservationFreshness.STALE)
}

object FleetShellProjector {
    fun aggregate(devices: Collection<ShellProjection>): FleetShellProjection {
        require(devices.map { it.deviceId }.toSet().size == devices.size) {
            "A fleet projection cannot contain duplicate device identities"
        }
        val ordered = devices.sortedBy { it.deviceId.value }
        if (ordered.isEmpty()) {
            return FleetShellProjection(
                devices = emptyList(),
                capture = FleetCaptureProjection(
                    state = FleetCaptureState.NO_MANAGED_DEVICES,
                    label = "No managed devices",
                    activeDeviceIds = emptySet(),
                    uncertainDeviceIds = emptySet(),
                ),
            )
        }

        val active = ordered
            .filter { it.capture.value.assurance == CaptureAssurance.ACTIVE }
            .mapTo(linkedSetOf()) { it.deviceId }
        val uncertain = ordered
            .filter { it.capture.value.assurance == CaptureAssurance.MAY_BE_ACTIVE }
            .mapTo(linkedSetOf()) { it.deviceId }
        val capture = when {
            active.isNotEmpty() -> FleetCaptureProjection(
                state = FleetCaptureState.ACTIVE,
                label = countLabel(active.size, "Microphone active", "Microphones active"),
                activeDeviceIds = active,
                uncertainDeviceIds = uncertain,
            )

            uncertain.isNotEmpty() -> FleetCaptureProjection(
                state = FleetCaptureState.MAY_BE_ACTIVE,
                label = "Microphone state uncertain — treat as recording",
                activeDeviceIds = emptySet(),
                uncertainDeviceIds = uncertain,
            )

            else -> FleetCaptureProjection(
                state = FleetCaptureState.ALL_VERIFIED_OFF,
                label = "All microphones off — device confirmed",
                activeDeviceIds = emptySet(),
                uncertainDeviceIds = emptySet(),
            )
        }
        return FleetShellProjection(devices = ordered, capture = capture)
    }

    private fun countLabel(count: Int, singular: String, plural: String): String =
        if (count == 1) singular else "$plural on $count devices"
}
