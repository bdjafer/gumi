package dev.gumi.edge.shell.application

import dev.gumi.edge.runtime.capture.CaptureCommand
import dev.gumi.edge.runtime.capture.CaptureCommandKind
import dev.gumi.edge.runtime.capture.CaptureMode
import dev.gumi.edge.runtime.capture.CaptureProof
import dev.gumi.edge.runtime.capture.CaptureProofSource
import dev.gumi.edge.runtime.capture.CaptureState
import dev.gumi.edge.runtime.capture.CaptureTransition
import dev.gumi.edge.runtime.capture.CaptureTruth
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ShellProjectorTest {
    @Test
    fun `fresh device Idle on a fresh ready link is the only verified-off projection`() {
        val snapshot = snapshot(captureState = verifiedState())

        val projection = ShellProjector.project(snapshot, projectedAtEpochMillis = 11_500)

        assertEquals(CapturePresentationKind.VERIFIED_OFF, projection.capture.value.kind)
        assertEquals(CaptureAssurance.VERIFIED_OFF, projection.capture.value.assurance)
        assertEquals(
            "Microphone off — confirmed by Gumi pendant 10s ago",
            projection.capture.value.label,
        )
        assertEquals(ObservationFreshness.FRESH, projection.capture.freshness)
        assertEquals(ProjectionAuthority.DEVICE_REPORTED, projection.capture.authority)
        assertNull(projection.pendingCommandId)
    }

    @Test
    fun `unverified boot Idle default never becomes a verified-off claim`() {
        val projection = project(CaptureState())

        assertUnknown(projection)
        assertEquals(ObservationFreshness.STALE, projection.capture.freshness)
    }

    @Test
    fun `caller fresh cannot override an expired or future capture timestamp`() {
        val expired = ShellProjector.project(
            snapshot(
                captureState = verifiedState(),
            ),
            NOW,
            ShellFreshnessPolicy(captureMaxAgeMillis = 1_000),
        )
        val future = ShellProjector.project(
            snapshot(
                captureState = verifiedState(),
                captureObservedAtEpochMillis = NOW + 1,
            ),
            NOW,
        )

        assertUnknown(expired)
        assertUnknown(future)
        assertEquals(ObservationFreshness.STALE, expired.capture.freshness)
        assertEquals(ObservationFreshness.STALE, future.capture.freshness)
    }

    @Test
    fun `mixed connection generations cannot combine into verified-off evidence`() {
        val mixedAxes = ShellProjector.project(
            snapshot(
                captureState = verifiedState(),
                captureSessionGeneration = SESSION,
                linkSessionGeneration = SESSION + 1u,
            ),
            NOW,
        )
        val mixedProof = ShellProjector.project(
            snapshot(
                captureState = verifiedState(),
                captureSessionGeneration = SESSION + 1u,
                linkSessionGeneration = SESSION + 1u,
            ),
            NOW,
        )

        assertUnknown(mixedAxes)
        assertUnknown(mixedProof)
    }

    @Test
    fun `a stale Idle observation becomes Unknown and never preserves an off claim`() {
        val projection = project(
            captureState = verifiedState(),
            captureFreshness = ObservationFreshness.STALE,
        )

        assertUnknown(projection)
        assertEquals(ObservationFreshness.STALE, projection.capture.freshness)
    }

    @Test
    fun `disconnect immediately invalidates even fresh device-reported Idle`() {
        val projection = project(
            captureState = verifiedState(),
            linkState = LinkState.DISCONNECTED,
        )

        assertUnknown(projection)
        assertEquals(ObservationFreshness.STALE, projection.capture.freshness)
        assertEquals(ObservationFreshness.FRESH, projection.link.freshness)
    }

    @Test
    fun `an unready or stale link cannot support a positive microphone-off claim`() {
        val connecting = project(verifiedState(), linkState = LinkState.CONNECTING)
        val staleLink = ShellProjector.project(
            snapshot(
                captureState = verifiedState(),
                linkFreshness = ObservationFreshness.STALE,
            ),
            NOW,
        )

        assertUnknown(connecting)
        assertUnknown(staleLink)
    }

    @Test
    fun `edge and cloud authority cannot launder Idle into verified hardware truth`() {
        val inferred = project(
            verifiedState(),
            captureAuthority = ProjectionAuthority.EDGE_INFERRED,
        )
        val cloud = project(
            verifiedState(),
            captureAuthority = ProjectionAuthority.CLOUD_REPORTED,
        )

        assertUnknown(inferred)
        assertUnknown(cloud)
    }

    @Test
    fun `last known Recording becomes may-be-recording when disconnected`() {
        val projection = project(
            captureState = verifiedState(CaptureMode.RECORDING),
            linkState = LinkState.DISCONNECTED,
        )

        assertEquals(CapturePresentationKind.MAY_BE_RECORDING, projection.capture.value.kind)
        assertEquals(CaptureAssurance.MAY_BE_ACTIVE, projection.capture.value.assurance)
        assertEquals(
            "Device disconnected; recording may continue locally",
            projection.capture.value.label,
        )
        assertEquals(CaptureMode.RECORDING, projection.capture.value.lastReportedMode)
    }

    @Test
    fun `last known Recording becomes may-be-recording when its evidence is stale`() {
        val projection = project(
            captureState = verifiedState(CaptureMode.RECORDING),
            captureFreshness = ObservationFreshness.STALE,
        )

        assertEquals(CapturePresentationKind.MAY_BE_RECORDING, projection.capture.value.kind)
        assertEquals(
            "Recording may still be active — device confirmation is stale",
            projection.capture.value.label,
        )
    }

    @Test
    fun `requesting Recording does not claim that hardware acquisition completed`() {
        val command = command("start", CaptureCommandKind.START_RECORDING)
        val state = CaptureState(
            truth = acquired(CaptureMode.IDLE),
            transition = CaptureTransition(command, CaptureMode.RECORDING),
        )

        val projection = project(state)

        assertEquals(CapturePresentationKind.STARTING, projection.capture.value.kind)
        assertEquals(CaptureAssurance.MAY_BE_ACTIVE, projection.capture.value.assurance)
        assertEquals(CaptureMode.IDLE, projection.capture.value.lastReportedMode)
        assertEquals(CaptureMode.RECORDING, projection.capture.value.requestedMode)
        assertEquals(command.id, projection.pendingCommandId)
        assertEquals("Starting capture…", projection.capture.value.label)
    }

    @Test
    fun `requesting stop retains active assurance until release is confirmed`() {
        val command = command("stop", CaptureCommandKind.STOP_RECORDING)
        val state = CaptureState(
            truth = acquired(CaptureMode.RECORDING),
            transition = CaptureTransition(command, CaptureMode.IDLE),
        )

        val projection = project(state)

        assertEquals(CapturePresentationKind.STOPPING, projection.capture.value.kind)
        assertEquals(CaptureAssurance.ACTIVE, projection.capture.value.assurance)
        assertEquals("Stopping — microphone may still be active", projection.capture.value.label)
        assertEquals(CaptureMode.RECORDING, projection.capture.value.lastReportedMode)
        assertEquals(CaptureMode.IDLE, projection.capture.value.requestedMode)
    }

    @Test
    fun `requesting VoiceTurn over Recording keeps Recording as acquired truth`() {
        val command = command("voice-start", CaptureCommandKind.START_VOICE_TURN)
        val state = CaptureState(
            truth = acquired(CaptureMode.RECORDING),
            transition = CaptureTransition(command, CaptureMode.VOICE_TURN),
            resumeAfterVoiceTurn = CaptureMode.RECORDING,
        )

        val projection = project(state)

        assertEquals(
            CapturePresentationKind.RECORDING_STARTING_VOICE_TURN,
            projection.capture.value.kind,
        )
        assertEquals(CaptureMode.RECORDING, projection.capture.value.lastReportedMode)
        assertEquals(CaptureMode.VOICE_TURN, projection.capture.value.requestedMode)
        assertEquals("Recording locally — starting voice turn…", projection.capture.value.label)
    }

    @Test
    fun `acquired VoiceTurn over Recording uses the combined wording`() {
        val state = CaptureState(
            truth = acquired(CaptureMode.VOICE_TURN),
            resumeAfterVoiceTurn = CaptureMode.RECORDING,
        )

        val projection = project(state)

        assertEquals(
            CapturePresentationKind.RECORDING_WITH_VOICE_TURN,
            projection.capture.value.kind,
        )
        assertEquals("Recording + voice turn", projection.capture.value.label)
        assertEquals(CaptureAssurance.ACTIVE, projection.capture.value.assurance)
    }

    @Test
    fun `acquired VoiceTurn from Idle has turn-only wording`() {
        val projection = project(
            CaptureState(
                truth = acquired(CaptureMode.VOICE_TURN),
                resumeAfterVoiceTurn = CaptureMode.IDLE,
            ),
        )

        assertEquals(CapturePresentationKind.VOICE_TURN, projection.capture.value.kind)
        assertEquals("Listening for this voice turn", projection.capture.value.label)
    }

    @Test
    fun `ending VoiceTurn over Recording preserves the base recording projection`() {
        val command = command("voice-stop", CaptureCommandKind.STOP_VOICE_TURN)
        val state = CaptureState(
            truth = acquired(CaptureMode.VOICE_TURN),
            transition = CaptureTransition(command, CaptureMode.RECORDING),
            resumeAfterVoiceTurn = CaptureMode.RECORDING,
        )

        val projection = project(state)

        assertEquals(
            CapturePresentationKind.RECORDING_ENDING_VOICE_TURN,
            projection.capture.value.kind,
        )
        assertEquals(CaptureAssurance.ACTIVE, projection.capture.value.assurance)
        assertEquals("Recording locally — ending voice turn…", projection.capture.value.label)
    }

    @Test
    fun `runtime Unknown is always fail-safe and carries its expected failure`() {
        val failure = failure("CAPTURE_TRUTH_UNKNOWN")
        val state = CaptureState(
            truth = CaptureTruth.Unknown(
                failure,
                CorrelationId("release-unknown"),
                1u,
                SESSION,
            ),
        )

        val projection = project(state)

        assertUnknown(projection)
        assertEquals(failure, projection.capture.value.failure)
        assertEquals(ObservationFreshness.FRESH, projection.capture.freshness)
        assertNull(projection.capture.value.lastReportedMode)
    }

    @Test
    fun `fatal privacy fault overrides an inconsistent Idle report`() {
        val failure = failure("PRIVACY_OUTPUT_FAILED")
        val projection = ShellProjector.project(
            snapshot(
                captureState = verifiedState(),
                faultStatus = FaultStatus(FaultSeverity.FATAL_PRIVACY, failure),
            ),
            NOW,
        )

        assertUnknown(projection)
        assertEquals(failure, projection.capture.value.failure)
    }

    @Test
    fun `recording wording reflects only fresh sync state without changing capture truth`() {
        val recording = verifiedState(CaptureMode.RECORDING)
        val uploading = project(
            recording,
            syncStatus = SyncStatus(SyncState.UPLOADING, BacklogStatus(pendingItems = 2u)),
        )
        val offline = project(
            recording,
            syncStatus = SyncStatus(
                SyncState.CLOUD_OFFLINE_SAVED_LOCALLY,
                BacklogStatus(pendingItems = 2u),
            ),
        )
        val staleSync = ShellProjector.project(
            snapshot(
                captureState = recording,
                syncStatus = SyncStatus(SyncState.UPLOADING, BacklogStatus(pendingItems = 2u)),
                syncFreshness = ObservationFreshness.STALE,
            ),
            NOW,
        )

        assertEquals("Recording — uploading", uploading.capture.value.label)
        assertEquals("Recording — cloud offline, saved locally", offline.capture.value.label)
        assertEquals("Recording locally", staleSync.capture.value.label)
        assertTrue(listOf(uploading, offline, staleSync).all {
            it.capture.value.assurance == CaptureAssurance.ACTIVE
        })
    }

    @Test
    fun `projection retains every orthogonal axis with its own provenance`() {
        val snapshot = snapshot(
            captureState = verifiedState(),
            maintenanceStatus = MaintenanceState.VALIDATING,
            updateStatus = UpdateStatus(UpdateStage.VALIDATING),
            syncStatus = SyncStatus(
                SyncState.BLOCKED,
                BacklogStatus(pendingItems = 7u, pendingBytes = 4_096u),
            ),
            powerStatus = PowerStatus(
                state = PowerState.OPERATIONAL,
                batteryPercent = 23u,
                level = PowerLevel.LOW,
            ),
            storageStatus = StorageStatus(
                state = StorageState.LOW,
                availableBytes = 1_024u,
                capacityBytes = 8_192u,
            ),
            faultStatus = FaultStatus(FaultSeverity.WARNING, failure("LOW_DURABLE_CAPACITY")),
        )

        val projection = ShellProjector.project(snapshot, NOW)

        assertSame(snapshot.link, projection.link)
        assertSame(snapshot.maintenance, projection.maintenance)
        assertSame(snapshot.update, projection.update)
        assertSame(snapshot.sync, projection.sync)
        assertSame(snapshot.power, projection.power)
        assertSame(snapshot.storage, projection.storage)
        assertSame(snapshot.fault, projection.fault)
        assertEquals(snapshot.capture.authority, projection.capture.authority)
        assertEquals(snapshot.capture.observedAtEpochMillis, projection.capture.observedAtEpochMillis)
        assertTrue(projection.capture.value.accessibilityLabel.isNotBlank())
    }

    @Test
    fun `snapshot rejects two competing pending command identities`() {
        val captureCommand = command("capture", CaptureCommandKind.START_RECORDING)
        assertFailsWith<IllegalArgumentException> {
            snapshot(
                captureState = CaptureState(
                    truth = acquired(CaptureMode.IDLE),
                    transition = CaptureTransition(captureCommand, CaptureMode.RECORDING),
                ),
                pendingCommandId = CommandId("different-command"),
            )
        }
    }

    private fun assertUnknown(projection: ShellProjection) {
        assertEquals(CapturePresentationKind.UNKNOWN, projection.capture.value.kind)
        assertEquals(CaptureAssurance.MAY_BE_ACTIVE, projection.capture.value.assurance)
        assertEquals(
            "Microphone state unknown — check the device privacy light",
            projection.capture.value.label,
        )
        assertEquals(projection.capture.value.label, projection.capture.value.accessibilityLabel)
    }
}

class FleetShellProjectorTest {
    @Test
    fun `a confirmed active microphone wins over uncertain and off devices`() {
        val active = project(
            verifiedState(CaptureMode.RECORDING),
            deviceId = "active",
        )
        val uncertain = project(
            verifiedState(),
            deviceId = "uncertain",
            linkState = LinkState.DISCONNECTED,
        )
        val off = project(verifiedState(), deviceId = "off")

        val fleet = FleetShellProjector.aggregate(listOf(off, uncertain, active))

        assertEquals(FleetCaptureState.ACTIVE, fleet.capture.state)
        assertEquals(setOf(DeviceId("active")), fleet.capture.activeDeviceIds)
        assertEquals(setOf(DeviceId("uncertain")), fleet.capture.uncertainDeviceIds)
        assertEquals(listOf("active", "off", "uncertain"), fleet.devices.map { it.deviceId.value })
    }

    @Test
    fun `uncertain wins when there is no confirmed active microphone`() {
        val fleet = FleetShellProjector.aggregate(
            listOf(
                project(verifiedState(), deviceId = "off"),
                project(
                    verifiedState(),
                    deviceId = "unknown",
                    captureFreshness = ObservationFreshness.STALE,
                ),
            ),
        )

        assertEquals(FleetCaptureState.MAY_BE_ACTIVE, fleet.capture.state)
        assertEquals("Microphone state uncertain — treat as recording", fleet.capture.label)
        assertEquals(setOf(DeviceId("unknown")), fleet.capture.uncertainDeviceIds)
    }

    @Test
    fun `all-off is shown only when every managed device has fresh verified-off evidence`() {
        val fleet = FleetShellProjector.aggregate(
            listOf(
                project(verifiedState(), deviceId = "one"),
                project(verifiedState(), deviceId = "two"),
            ),
        )

        assertEquals(FleetCaptureState.ALL_VERIFIED_OFF, fleet.capture.state)
        assertEquals("All microphones off — device confirmed", fleet.capture.label)
        assertTrue(fleet.capture.activeDeviceIds.isEmpty())
        assertTrue(fleet.capture.uncertainDeviceIds.isEmpty())
    }

    @Test
    fun `empty fleet never makes an all-microphones-off claim`() {
        val fleet = FleetShellProjector.aggregate(emptyList())

        assertEquals(FleetCaptureState.NO_MANAGED_DEVICES, fleet.capture.state)
        assertEquals("No managed devices", fleet.capture.label)
    }

    @Test
    fun `duplicate stable device identities are rejected`() {
        val projection = project(verifiedState(), deviceId = "same")

        assertFailsWith<IllegalArgumentException> {
            FleetShellProjector.aggregate(listOf(projection, projection))
        }
    }
}

private const val NOW = 11_500L
private const val SESSION: ULong = 9u

private fun verifiedState(mode: CaptureMode = CaptureMode.IDLE) = CaptureState(
    truth = acquired(mode),
    resumeAfterVoiceTurn = CaptureMode.IDLE.takeIf { mode == CaptureMode.VOICE_TURN },
)

private fun acquired(mode: CaptureMode) = CaptureTruth.Acquired(
    mode = mode,
    proof = CaptureProof(
        connectionSessionGeneration = SESSION,
        causalGeneration = 1u,
        source = CaptureProofSource.DEVICE_OBSERVATION,
    ),
)

private fun project(
    captureState: CaptureState,
    deviceId: String = "device-1",
    linkState: LinkState = LinkState.READY,
    captureFreshness: ObservationFreshness = ObservationFreshness.FRESH,
    captureAuthority: ProjectionAuthority = ProjectionAuthority.DEVICE_REPORTED,
    syncStatus: SyncStatus = SyncStatus(SyncState.CURRENT, BacklogStatus()),
): ShellProjection = ShellProjector.project(
    snapshot(
        captureState = captureState,
        deviceId = deviceId,
        linkState = linkState,
        captureFreshness = captureFreshness,
        captureAuthority = captureAuthority,
        syncStatus = syncStatus,
    ),
    NOW,
)

private fun snapshot(
    captureState: CaptureState,
    deviceId: String = "device-1",
    linkState: LinkState = LinkState.READY,
    linkFreshness: ObservationFreshness = ObservationFreshness.FRESH,
    captureFreshness: ObservationFreshness = ObservationFreshness.FRESH,
    captureAuthority: ProjectionAuthority = ProjectionAuthority.DEVICE_REPORTED,
    maintenanceStatus: MaintenanceState = MaintenanceState.NORMAL,
    updateStatus: UpdateStatus = UpdateStatus(UpdateStage.IDLE),
    syncStatus: SyncStatus = SyncStatus(SyncState.CURRENT, BacklogStatus()),
    syncFreshness: ObservationFreshness = ObservationFreshness.FRESH,
    powerStatus: PowerStatus = PowerStatus(PowerState.OPERATIONAL),
    storageStatus: StorageStatus = StorageStatus(StorageState.HEALTHY),
    faultStatus: FaultStatus = FaultStatus(FaultSeverity.NONE),
    pendingCommandId: CommandId? = null,
    captureObservedAtEpochMillis: Long = 1_500,
    linkObservedAtEpochMillis: Long = 1_500,
    captureSessionGeneration: ULong = SESSION,
    linkSessionGeneration: ULong = SESSION,
): DeviceShellSnapshot = DeviceShellSnapshot(
    deviceId = DeviceId(deviceId),
    displayName = "Gumi pendant",
    capture = observed(
        captureState,
        authority = captureAuthority,
        freshness = captureFreshness,
        observedAtEpochMillis = captureObservedAtEpochMillis,
        connectionSessionGeneration = captureSessionGeneration,
    ),
    link = observed(
        linkState,
        freshness = linkFreshness,
        observedAtEpochMillis = linkObservedAtEpochMillis,
        connectionSessionGeneration = linkSessionGeneration,
    ),
    maintenance = observed(maintenanceStatus),
    update = observed(updateStatus),
    sync = observed(syncStatus, freshness = syncFreshness),
    power = observed(powerStatus),
    storage = observed(storageStatus),
    fault = observed(faultStatus),
    pendingCommandId = pendingCommandId,
)

private fun <T> observed(
    value: T,
    authority: ProjectionAuthority = ProjectionAuthority.EDGE_INFERRED,
    freshness: ObservationFreshness = ObservationFreshness.FRESH,
    observedAtEpochMillis: Long = 1_500,
    connectionSessionGeneration: ULong? = SESSION,
) = AxisObservation(
    value = value,
    authority = authority,
    observedAtEpochMillis = observedAtEpochMillis,
    freshness = freshness,
    connectionSessionGeneration = connectionSessionGeneration,
)

private fun command(id: String, kind: CaptureCommandKind) = CaptureCommand(
    id = CommandId("command-$id"),
    correlationId = CorrelationId("correlation-$id"),
    kind = kind,
)

private fun failure(code: String) = ExpectedFailure(
    category = FailureCategory.REJECTED_POLICY,
    code = FailureCode(code),
    retryable = false,
)
