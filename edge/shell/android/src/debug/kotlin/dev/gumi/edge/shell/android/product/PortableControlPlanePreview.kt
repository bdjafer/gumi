package dev.gumi.edge.shell.android.product

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.shell.application.AxisObservation
import dev.gumi.edge.shell.application.BacklogStatus
import dev.gumi.edge.shell.application.CaptureAssurance
import dev.gumi.edge.shell.application.CapturePresentation
import dev.gumi.edge.shell.application.CapturePresentationKind
import dev.gumi.edge.shell.application.FaultSeverity
import dev.gumi.edge.shell.application.FaultStatus
import dev.gumi.edge.shell.application.FleetCaptureProjection
import dev.gumi.edge.shell.application.FleetCaptureState
import dev.gumi.edge.shell.application.FleetShellProjection
import dev.gumi.edge.shell.application.LinkState
import dev.gumi.edge.shell.application.MaintenanceState
import dev.gumi.edge.shell.application.ObservationFreshness
import dev.gumi.edge.shell.application.PortableControlPlanePresentation
import dev.gumi.edge.shell.application.PortableControlPlaneProjector
import dev.gumi.edge.shell.application.PowerLevel
import dev.gumi.edge.shell.application.PowerState
import dev.gumi.edge.shell.application.PowerStatus
import dev.gumi.edge.shell.application.ProjectionAuthority
import dev.gumi.edge.shell.application.ShellProjection
import dev.gumi.edge.shell.application.StorageState
import dev.gumi.edge.shell.application.StorageStatus
import dev.gumi.edge.shell.application.SyncState
import dev.gumi.edge.shell.application.SyncStatus
import dev.gumi.edge.shell.application.UpdateStage
import dev.gumi.edge.shell.application.UpdateStatus

internal object PortableControlPlaneFixtures {
    private const val NOW = 1_720_000_000_000L
    private const val SESSION = 7uL
    private val pendantId = DeviceId("fixture-pendant")
    private val glassesId = DeviceId("fixture-glasses")

    val allVerifiedOff: PortableControlPlanePresentation by lazy {
        val devices = listOf(
            device(
                id = pendantId,
                displayName = "Gumi pendant",
                capture = verifiedOffCapture(),
                pendingItems = 3uL,
                pendingBytes = 196_608uL,
            ),
            device(
                id = glassesId,
                displayName = "Studio glasses",
                capture = verifiedOffCapture(),
                pendingItems = 0uL,
                pendingBytes = 0uL,
            ),
        )
        PortableControlPlaneProjector.project(
            fleet = fleet(
                devices = devices,
                state = FleetCaptureState.ALL_VERIFIED_OFF,
                active = emptySet(),
                uncertain = emptySet(),
            ),
            preferredDeviceId = pendantId,
        )
    }

    val activeRecording: PortableControlPlanePresentation by lazy {
        val devices = listOf(
            device(
                id = pendantId,
                displayName = "Gumi pendant",
                capture = activeCapture(),
                pendingItems = 8uL,
                pendingBytes = 1_310_720uL,
                syncState = SyncState.CLOUD_OFFLINE_SAVED_LOCALLY,
            ),
            device(
                id = glassesId,
                displayName = "Studio glasses",
                capture = verifiedOffCapture(),
                pendingItems = 0uL,
                pendingBytes = 0uL,
            ),
        )
        PortableControlPlaneProjector.project(
            fleet = fleet(
                devices = devices,
                state = FleetCaptureState.ACTIVE,
                active = setOf(pendantId),
                uncertain = emptySet(),
            ),
        )
    }

    val collisionRisk: PortableControlPlanePresentation by lazy {
        val devices = listOf(
            device(
                id = pendantId,
                displayName = "Gumi pendant",
                capture = activeCapture(),
                pendingItems = 2uL,
                pendingBytes = 131_072uL,
            ),
            device(
                id = glassesId,
                displayName = "Studio glasses",
                capture = uncertainCapture(),
                pendingItems = null,
                pendingBytes = null,
                link = LinkState.DEGRADED,
            ),
        )
        PortableControlPlaneProjector.project(
            fleet = fleet(
                devices = devices,
                state = FleetCaptureState.ACTIVE,
                active = setOf(pendantId),
                uncertain = setOf(glassesId),
            ),
            preferredDeviceId = glassesId,
        )
    }

    val empty: PortableControlPlanePresentation by lazy {
        PortableControlPlaneProjector.project(
            fleet = fleet(
                devices = emptyList(),
                state = FleetCaptureState.NO_MANAGED_DEVICES,
                active = emptySet(),
                uncertain = emptySet(),
            ),
        )
    }

    private fun fleet(
        devices: List<ShellProjection>,
        state: FleetCaptureState,
        active: Set<DeviceId>,
        uncertain: Set<DeviceId>,
    ) = FleetShellProjection(
        devices = devices,
        capture = FleetCaptureProjection(
            state = state,
            label = state.name,
            activeDeviceIds = active,
            uncertainDeviceIds = uncertain,
        ),
    )

    private fun device(
        id: DeviceId,
        displayName: String,
        capture: CapturePresentation,
        pendingItems: ULong?,
        pendingBytes: ULong?,
        syncState: SyncState = SyncState.CURRENT,
        link: LinkState = LinkState.READY,
    ) = ShellProjection(
        deviceId = id,
        displayName = displayName,
        projectedAtEpochMillis = NOW,
        pendingCommandId = null,
        link = deviceObservation(link),
        capture = deviceObservation(capture),
        maintenance = deviceObservation(MaintenanceState.NORMAL),
        update = edgeObservation(UpdateStatus(UpdateStage.IDLE)),
        sync = edgeObservation(
            SyncStatus(
                state = syncState,
                backlog = BacklogStatus(
                    pendingItems = pendingItems,
                    pendingBytes = pendingBytes,
                    oldestItemAtEpochMillis = if (pendingItems != null && pendingItems > 0uL) {
                        NOW - 180_000L
                    } else {
                        null
                    },
                ),
            ),
        ),
        power = deviceObservation(
            PowerStatus(
                state = PowerState.OPERATIONAL,
                batteryPercent = 76u,
                level = PowerLevel.NORMAL,
                charging = false,
            ),
        ),
        storage = edgeObservation(
            StorageStatus(
                state = StorageState.HEALTHY,
                availableBytes = 3_221_225_472uL,
                capacityBytes = 4_294_967_296uL,
            ),
        ),
        fault = deviceObservation(FaultStatus(FaultSeverity.NONE)),
    )

    private fun verifiedOffCapture() = CapturePresentation(
        kind = CapturePresentationKind.VERIFIED_OFF,
        assurance = CaptureAssurance.VERIFIED_OFF,
        label = "Microphone off — device confirmed",
        accessibilityLabel = "Microphone off, confirmed by device",
        lastReportedMode = null,
        requestedMode = null,
    )

    private fun activeCapture() = CapturePresentation(
        kind = CapturePresentationKind.RECORDING,
        assurance = CaptureAssurance.ACTIVE,
        label = "Recording locally",
        accessibilityLabel = "Microphone active, recording locally",
        lastReportedMode = null,
        requestedMode = null,
    )

    private fun uncertainCapture() = CapturePresentation(
        kind = CapturePresentationKind.MAY_BE_RECORDING,
        assurance = CaptureAssurance.MAY_BE_ACTIVE,
        label = "Microphone state uncertain — treat as recording",
        accessibilityLabel = "Microphone state uncertain, treat as recording",
        lastReportedMode = null,
        requestedMode = null,
    )

    private fun <T> deviceObservation(value: T) = AxisObservation(
        value = value,
        authority = ProjectionAuthority.DEVICE_REPORTED,
        observedAtEpochMillis = NOW - 1_000L,
        freshness = ObservationFreshness.FRESH,
        connectionSessionGeneration = SESSION,
    )

    private fun <T> edgeObservation(value: T) = AxisObservation(
        value = value,
        authority = ProjectionAuthority.EDGE_INFERRED,
        observedAtEpochMillis = NOW - 1_000L,
        freshness = ObservationFreshness.FRESH,
        connectionSessionGeneration = SESSION,
    )
}

@Preview(name = "All microphones off", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun AllVerifiedOffPreview() {
    PortableControlPlaneSurface(
        presentation = PortableControlPlaneFixtures.allVerifiedOff,
        onSelectDevice = {},
        onAction = { _, _ -> },
    )
}

@Preview(name = "Recording offline", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun ActiveRecordingPreview() {
    PortableControlPlaneSurface(
        presentation = PortableControlPlaneFixtures.activeRecording,
        onSelectDevice = {},
        onAction = { _, _ -> },
    )
}

@Preview(name = "Collision risk, large text", widthDp = 390, heightDp = 844, fontScale = 1.5f)
@Composable
private fun CollisionRiskPreview() {
    PortableControlPlaneSurface(
        presentation = PortableControlPlaneFixtures.collisionRisk,
        onSelectDevice = {},
        onAction = { _, _ -> },
    )
}

@Preview(name = "No managed devices", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun EmptyFleetPreview() {
    PortableControlPlaneSurface(
        presentation = PortableControlPlaneFixtures.empty,
        onSelectDevice = {},
        onAction = { _, _ -> },
    )
}
