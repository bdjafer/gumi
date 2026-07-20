package dev.gumi.edge.shell.application

import dev.gumi.edge.runtime.capture.CaptureMode
import dev.gumi.edge.runtime.capture.CaptureProof
import dev.gumi.edge.runtime.capture.CaptureProofSource
import dev.gumi.edge.runtime.capture.CaptureState
import dev.gumi.edge.runtime.capture.CaptureTruth
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableControlPlaneProjectorTest {
    @Test
    fun `workflow distinguishes no devices all off one active uncertainty and collision risk`() {
        assertEquals(
            ShellCaptureWorkflowState.NO_MANAGED_DEVICES,
            product().workflow.state,
        )
        assertEquals(
            ShellCaptureWorkflowState.ALL_VERIFIED_OFF,
            product(off("a"), off("b")).workflow.state,
        )
        assertEquals(
            ShellCaptureWorkflowState.ONE_ACTIVE,
            product(off("a"), recording("b")).workflow.state,
        )
        assertEquals(
            ShellCaptureWorkflowState.UNCERTAIN,
            product(off("a"), uncertain("b")).workflow.state,
        )

        val twoActive = product(recording("a"), recording("b")).workflow
        assertEquals(ShellCaptureWorkflowState.COLLISION_RISK, twoActive.state)
        assertEquals(setOf(DeviceId("a"), DeviceId("b")), twoActive.activeDeviceIds)
        assertTrue(twoActive.label.contains("More than one microphone"))

        val activeAndUnknown = product(recording("a"), uncertain("b")).workflow
        assertEquals(ShellCaptureWorkflowState.COLLISION_RISK, activeAndUnknown.state)
        assertEquals(setOf(DeviceId("a")), activeAndUnknown.activeDeviceIds)
        assertEquals(setOf(DeviceId("b")), activeAndUnknown.uncertainDeviceIds)
    }

    @Test
    fun `focus is explicit then sole active then first attached then stable order`() {
        val detached = off("a", link = LinkState.DISCONNECTED)
        val attached = off("b")

        assertEquals(
            DeviceId("b"),
            product(detached, attached).selectedDeviceId,
        )
        assertEquals(
            DeviceId("a"),
            product(detached, attached, preferred = DeviceId("a")).selectedDeviceId,
        )
        assertEquals(
            DeviceId("b"),
            product(detached, recording("b"), preferred = DeviceId("removed")).selectedDeviceId,
        )
        assertEquals(
            DeviceId("a"),
            product(detached, off("b", link = LinkState.DISCONNECTED)).selectedDeviceId,
        )
        assertNull(product().selectedDeviceId)
    }

    @Test
    fun `one capture policy blocks a second start while retaining every safety stop`() {
        val projection = product(recording("a"), off("b"))
        val active = projection.device("a")
        val idle = projection.device("b")

        assertTrue(active.enabled(ShellControlAction.START_VOICE_TURN))
        assertTrue(active.enabled(ShellControlAction.STOP_CAPTURE))
        assertFalse(active.enabled(ShellControlAction.START_RECORDING))
        assertFalse(idle.enabled(ShellControlAction.START_RECORDING))
        assertFalse(idle.enabled(ShellControlAction.START_VOICE_TURN))
        assertEquals(
            "FLEET_CAPTURE_NOT_QUIESCENT",
            idle.reason(ShellControlAction.START_RECORDING),
        )

        val collision = product(recording("a"), uncertain("b"))
        assertFalse(collision.device("a").enabled(ShellControlAction.START_VOICE_TURN))
        assertTrue(collision.device("a").enabled(ShellControlAction.STOP_CAPTURE))
        assertTrue(collision.device("b").enabled(ShellControlAction.STOP_CAPTURE))
    }

    @Test
    fun `capture admission reservation closes the pre-publication race and keeps a stop`() {
        val reservation = ShellCaptureAdmissionReservation(
            commandId = dev.gumi.edge.sdk.CommandId("reserved-start"),
            targetDeviceId = DeviceId("a"),
        )
        val projection = PortableControlPlaneProjector.project(
            fleet = FleetShellProjector.aggregate(listOf(off("a"), off("b"))),
            admissionReservation = reservation,
        )

        assertEquals(ShellCaptureWorkflowState.STARTING, projection.workflow.state)
        assertEquals(reservation, projection.workflow.admissionReservation)
        assertEquals(DeviceId("a"), projection.selectedDeviceId)
        assertTrue(projection.device("a").captureAdmissionReserved)
        assertTrue(projection.device("a").enabled(ShellControlAction.STOP_CAPTURE))
        assertFalse(projection.device("a").enabled(ShellControlAction.START_RECORDING))
        assertFalse(projection.device("b").enabled(ShellControlAction.START_RECORDING))
        assertEquals(
            "CAPTURE_ADMISSION_RESERVED",
            projection.device("b").reason(ShellControlAction.START_RECORDING),
        )

        val orphaned = PortableControlPlaneProjector.project(
            fleet = FleetShellProjector.aggregate(listOf(off("b"))),
            admissionReservation = reservation,
        )
        assertEquals(ShellCaptureWorkflowState.COLLISION_RISK, orphaned.workflow.state)
        assertFalse(orphaned.device("b").enabled(ShellControlAction.START_RECORDING))

        val conflictsWithActive = PortableControlPlaneProjector.project(
            fleet = FleetShellProjector.aggregate(listOf(recording("a"), off("b"))),
            admissionReservation = reservation.copy(targetDeviceId = DeviceId("b")),
        )
        assertEquals(ShellCaptureWorkflowState.COLLISION_RISK, conflictsWithActive.workflow.state)
        assertFalse(
            conflictsWithActive.device("a").enabled(ShellControlAction.START_VOICE_TURN),
        )
        assertEquals(
            "CAPTURE_ADMISSION_RESERVED",
            conflictsWithActive.device("a").reason(ShellControlAction.START_VOICE_TURN),
        )
    }

    @Test
    fun `all-off fleet admits either target but does not turn advisory admission into authority`() {
        val projection = product(off("a"), off("b"))

        projection.devices.forEach { device ->
            assertTrue(device.enabled(ShellControlAction.START_RECORDING))
            assertTrue(device.enabled(ShellControlAction.START_VOICE_TURN))
            assertTrue(
                device.control.actions.getValue(ShellControlAction.START_RECORDING)
                    .requiresRuntimeRevalidation,
            )
        }
    }

    @Test
    fun `physical output is trusted only from a fresh current device session`() {
        val device = recording("a")
        val current = output("a", DeviceVisibleOutputSemantic.PRIVACY_RECORDING)
        assertEquals(
            ShellPhysicalOutputKind.PRIVACY_ACTIVE_CONFIRMED,
            product(device, output = mapOf(current.deviceId to current))
                .device("a").physicalOutput.kind,
        )

        val variants = listOf(
            current.copy(visible = current.visible.copy(authority = ProjectionAuthority.EDGE_INFERRED)),
            current.copy(visible = current.visible.copy(freshness = ObservationFreshness.STALE)),
            current.copy(visible = current.visible.copy(observedAtEpochMillis = NOW - 30_001)),
            current.copy(visible = current.visible.copy(observedAtEpochMillis = NOW + 1)),
            current.copy(visible = current.visible.copy(connectionSessionGeneration = SESSION + 1uL)),
            current.copy(visible = current.visible.copy(connectionSessionGeneration = null)),
        )
        variants.forEach { variant ->
            assertEquals(
                ShellPhysicalOutputKind.UNVERIFIED,
                product(device, output = mapOf(variant.deviceId to variant))
                    .device("a").physicalOutput.kind,
            )
        }
        assertEquals(
            ShellPhysicalOutputKind.UNVERIFIED,
            product(device).device("a").physicalOutput.kind,
        )
    }

    @Test
    fun `reported visible semantics are checked against capture instead of copied blindly`() {
        val activePrivacy = productWithOutput(
            recording("a"),
            DeviceVisibleOutputSemantic.PRIVACY_VOICE_TURN,
        ).device("a")
        assertEquals(
            ShellPhysicalOutputKind.PRIVACY_ACTIVE_CONFIRMED,
            activePrivacy.physicalOutput.kind,
        )

        val activeUnknown = productWithOutput(
            uncertain("a", link = LinkState.READY),
            DeviceVisibleOutputSemantic.PRIVACY_UNKNOWN,
        ).device("a")
        assertEquals(
            ShellPhysicalOutputKind.PRIVACY_UNKNOWN_CONFIRMED,
            activeUnknown.physicalOutput.kind,
        )

        val activeWithoutSignal = productWithOutput(
            recording("a"),
            DeviceVisibleOutputSemantic.NO_SIGNAL,
        ).device("a")
        assertEquals(ShellPhysicalOutputKind.CONTRADICTORY, activeWithoutSignal.physicalOutput.kind)
        assertEquals(ShellFaultKind.PRIVACY_CRITICAL, activeWithoutSignal.fault.kind)
        assertTrue(activeWithoutSignal.enabled(ShellControlAction.STOP_CAPTURE))

        val idlePrivacy = productWithOutput(
            off("a"),
            DeviceVisibleOutputSemantic.PRIVACY_RECORDING,
        ).device("a")
        assertEquals(ShellPhysicalOutputKind.CONTRADICTORY, idlePrivacy.physicalOutput.kind)
        assertFalse(idlePrivacy.enabled(ShellControlAction.START_RECORDING))
        assertEquals(
            "PHYSICAL_OUTPUT_NOT_TRUSTWORTHY",
            idlePrivacy.reason(ShellControlAction.START_RECORDING),
        )

        val idleStatus = productWithOutput(
            off("a"),
            DeviceVisibleOutputSemantic.UPDATING,
        ).device("a")
        assertEquals(ShellPhysicalOutputKind.STATUS_CONFIRMED, idleStatus.physicalOutput.kind)
    }

    @Test
    fun `every operational visible semantic has one conservative product classification`() {
        val statusSemantics = setOf(
            DeviceVisibleOutputSemantic.BOOTING,
            DeviceVisibleOutputSemantic.PAIRING,
            DeviceVisibleOutputSemantic.UPDATING,
            DeviceVisibleOutputSemantic.VALIDATING,
            DeviceVisibleOutputSemantic.RECOVERY_REQUIRED,
            DeviceVisibleOutputSemantic.WARNING,
            DeviceVisibleOutputSemantic.STATUS,
        )
        val expected = DeviceVisibleOutputSemantic.entries
            .filterNot { it == DeviceVisibleOutputSemantic.UNKNOWN }
            .associateWith { semantic ->
                when {
                    semantic == DeviceVisibleOutputSemantic.NO_SIGNAL ->
                        ShellPhysicalOutputKind.NO_SIGNAL_CONFIRMED

                    semantic in statusSemantics -> ShellPhysicalOutputKind.STATUS_CONFIRMED
                    else -> ShellPhysicalOutputKind.CONTRADICTORY
                }
            }

        expected.forEach { (semantic, kind) ->
            assertEquals(kind, productWithOutput(off("a"), semantic).device("a").physicalOutput.kind)
        }

        val unknown = DeviceOutputTruth(
            DeviceId("a"),
            observation(
                DeviceVisibleOutput(
                    DeviceVisibleOutputSemantic.UNKNOWN,
                    DeviceVisibleOutputHealth.UNKNOWN,
                ),
            ),
        )
        assertEquals(
            ShellPhysicalOutputKind.UNVERIFIED,
            product(off("a"), output = mapOf(unknown.deviceId to unknown))
                .device("a").physicalOutput.kind,
        )
    }

    @Test
    fun `reported output drive failure elevates fault and prevents acquisition but never stop`() {
        val failed = DeviceOutputTruth(
            DeviceId("a"),
            observation(
                DeviceVisibleOutput(
                    DeviceVisibleOutputSemantic.UNKNOWN,
                    DeviceVisibleOutputHealth.DRIVE_FAILED,
                ),
            ),
        )
        val idle = product(
            off("a"),
            output = mapOf(failed.deviceId to failed),
        ).device("a")
        assertEquals(ShellPhysicalOutputKind.FAILED, idle.physicalOutput.kind)
        assertEquals(ShellFaultKind.PRIVACY_CRITICAL, idle.fault.kind)
        assertFalse(idle.enabled(ShellControlAction.START_RECORDING))
        assertFalse(idle.enabled(ShellControlAction.START_VOICE_TURN))

        val active = product(
            recording("a"),
            output = mapOf(failed.deviceId to failed),
        ).device("a")
        assertTrue(active.enabled(ShellControlAction.STOP_CAPTURE))
    }

    @Test
    fun `fault presentation preserves unresolved privacy failure and never clears stale evidence`() {
        val cases = listOf(
            Triple(FaultSeverity.NONE, ObservationFreshness.FRESH, ShellFaultKind.CLEAR),
            Triple(FaultSeverity.WARNING, ObservationFreshness.FRESH, ShellFaultKind.WARNING),
            Triple(FaultSeverity.RECOVERABLE, ObservationFreshness.FRESH, ShellFaultKind.RECOVERABLE),
            Triple(FaultSeverity.FATAL_PRIVACY, ObservationFreshness.FRESH, ShellFaultKind.PRIVACY_CRITICAL),
            Triple(FaultSeverity.NONE, ObservationFreshness.STALE, ShellFaultKind.UNKNOWN),
            Triple(FaultSeverity.FATAL_PRIVACY, ObservationFreshness.STALE, ShellFaultKind.PRIVACY_CRITICAL),
        )

        cases.forEachIndexed { index, (severity, freshness, expected) ->
            val projection = product(
                off(
                    id = "case-$index",
                    fault = FaultStatus(
                        severity,
                        if (severity == FaultSeverity.NONE) null else FAILURE,
                    ),
                    faultFreshness = freshness,
                ),
            )
            assertEquals(expected, projection.devices.single().fault.kind)
        }
    }

    @Test
    fun `attachment presentation covers every link state and refuses stale connected claims`() {
        val expected = mapOf(
            LinkState.DISCONNECTED to ShellAttachmentKind.DETACHED,
            LinkState.CONNECTING to ShellAttachmentKind.ATTACHING,
            LinkState.AUTHENTICATING to ShellAttachmentKind.ATTACHING,
            LinkState.READY to ShellAttachmentKind.ATTACHED,
            LinkState.DEGRADED to ShellAttachmentKind.DEGRADED,
        )
        expected.forEach { (link, kind) ->
            assertEquals(kind, product(off(link.name, link = link)).devices.single().attachment.kind)
        }
        assertEquals(
            ShellAttachmentKind.UNKNOWN,
            product(off("stale", linkFreshness = ObservationFreshness.STALE))
                .devices.single().attachment.kind,
        )
    }

    @Test
    fun `malformed output identity and impossible visible states fail closed`() {
        val report = output("a", DeviceVisibleOutputSemantic.NO_SIGNAL)
        assertFailsWith<IllegalArgumentException> {
            product(off("a"), output = mapOf(DeviceId("b") to report))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceVisibleOutput(
                DeviceVisibleOutputSemantic.PRIVACY_RECORDING,
                DeviceVisibleOutputHealth.DRIVE_FAILED,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceVisibleOutput(
                DeviceVisibleOutputSemantic.UNKNOWN,
                DeviceVisibleOutputHealth.OPERATIONAL,
            )
        }
    }

    private fun product(
        vararg devices: ShellProjection,
        preferred: DeviceId? = null,
        output: Map<DeviceId, DeviceOutputTruth> = emptyMap(),
    ) = PortableControlPlaneProjector.project(
        FleetShellProjector.aggregate(devices.toList()),
        output,
        preferred,
    )

    private fun productWithOutput(
        device: ShellProjection,
        semantic: DeviceVisibleOutputSemantic,
    ): PortableControlPlanePresentation {
        val output = output(device.deviceId.value, semantic)
        return product(device, output = mapOf(output.deviceId to output))
    }

    private fun PortableControlPlanePresentation.device(id: String) =
        devices.single { it.control.device.deviceId == DeviceId(id) }

    private fun ShellProductDevicePresentation.enabled(action: ShellControlAction): Boolean =
        control.actions.getValue(action).enabled

    private fun ShellProductDevicePresentation.reason(action: ShellControlAction): String? =
        control.actions.getValue(action).blockedReasonCode

    private fun off(
        id: String,
        link: LinkState = LinkState.READY,
        linkFreshness: ObservationFreshness = ObservationFreshness.FRESH,
        fault: FaultStatus = FaultStatus(FaultSeverity.NONE),
        faultFreshness: ObservationFreshness = ObservationFreshness.FRESH,
    ) = projected(
        id = id,
        mode = CaptureMode.IDLE,
        link = link,
        linkFreshness = linkFreshness,
        fault = fault,
        faultFreshness = faultFreshness,
    )

    private fun recording(id: String) = projected(id, CaptureMode.RECORDING)

    private fun uncertain(
        id: String,
        link: LinkState = LinkState.DISCONNECTED,
    ) = projected(id, CaptureMode.IDLE, link = link, captureFreshness = ObservationFreshness.STALE)

    private fun projected(
        id: String,
        mode: CaptureMode,
        link: LinkState = LinkState.READY,
        linkFreshness: ObservationFreshness = ObservationFreshness.FRESH,
        captureFreshness: ObservationFreshness = ObservationFreshness.FRESH,
        fault: FaultStatus = FaultStatus(FaultSeverity.NONE),
        faultFreshness: ObservationFreshness = ObservationFreshness.FRESH,
    ): ShellProjection {
        val deviceId = DeviceId(id)
        return ShellProjector.project(
            DeviceShellSnapshot(
                deviceId = deviceId,
                displayName = "Device $id",
                capture = observation(acquired(mode), freshness = captureFreshness),
                link = observation(link, freshness = linkFreshness),
                maintenance = observation(MaintenanceState.NORMAL),
                update = observation(UpdateStatus(UpdateStage.IDLE)),
                sync = observation(SyncStatus(SyncState.CURRENT, BacklogStatus())),
                power = observation(PowerStatus(PowerState.OPERATIONAL, level = PowerLevel.NORMAL)),
                storage = observation(StorageStatus(StorageState.HEALTHY)),
                fault = observation(fault, freshness = faultFreshness),
            ),
            NOW,
        )
    }

    private fun output(
        id: String,
        semantic: DeviceVisibleOutputSemantic,
    ) = DeviceOutputTruth(
        deviceId = DeviceId(id),
        visible = observation(
            DeviceVisibleOutput(semantic, DeviceVisibleOutputHealth.OPERATIONAL),
        ),
    )

    private fun acquired(mode: CaptureMode) = CaptureState(
        truth = CaptureTruth.Acquired(
            mode,
            CaptureProof(SESSION, 1uL, CaptureProofSource.DEVICE_OBSERVATION),
        ),
        resumeAfterVoiceTurn = if (mode == CaptureMode.VOICE_TURN) CaptureMode.IDLE else null,
    )

    private fun <T> observation(
        value: T,
        authority: ProjectionAuthority = ProjectionAuthority.DEVICE_REPORTED,
        observedAt: Long = NOW,
        freshness: ObservationFreshness = ObservationFreshness.FRESH,
        session: ULong? = SESSION,
    ) = AxisObservation(
        value = value,
        authority = authority,
        observedAtEpochMillis = observedAt,
        freshness = freshness,
        connectionSessionGeneration = session,
    )

    private companion object {
        const val NOW = 100_000L
        const val SESSION = 7uL
        val FAILURE = ExpectedFailure(
            FailureCategory.UNAVAILABLE,
            FailureCode("TEST_DEVICE_FAULT"),
            retryable = false,
        )
    }
}
