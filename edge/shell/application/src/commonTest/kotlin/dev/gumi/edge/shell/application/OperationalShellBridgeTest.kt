package dev.gumi.edge.shell.application

import dev.gumi.edge.runtime.capture.CaptureTruth
import dev.gumi.edge.runtime.capture.CaptureMode
import dev.gumi.edge.runtime.operational.OperationalCaptureTruth
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.operational.OperationalBacklog
import dev.gumi.edge.runtime.operational.OperationalBacklogScope
import dev.gumi.edge.runtime.operational.OperationalLinkState
import dev.gumi.edge.runtime.operational.OperationalPowerRefreshPort
import dev.gumi.edge.runtime.operational.OperationalPowerRefreshRequest
import dev.gumi.edge.runtime.operational.OperationalPowerRefreshResult
import dev.gumi.edge.runtime.operational.OperationalRuntimeLifecycle
import dev.gumi.edge.runtime.operational.OperationalRuntimeProjection
import dev.gumi.edge.runtime.operational.OperationalStorageState
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.capability.capture.DeviceCaptureAvailability
import dev.gumi.edge.sdk.capability.capture.DeviceCaptureState
import dev.gumi.edge.sdk.capability.capture.DeviceMaintenanceTruth
import dev.gumi.edge.sdk.capability.capture.DeviceMicrophoneTruth
import dev.gumi.edge.sdk.capability.capture.DevicePrivacyOutputTruth
import dev.gumi.edge.sdk.capability.capture.DeviceRecordingTruth
import dev.gumi.edge.sdk.capability.capture.DeviceSemanticSignalTruth
import dev.gumi.edge.sdk.capability.capture.DeviceVoiceActionTruth
import dev.gumi.edge.sdk.capability.power.PowerStatus as DevicePowerStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationalShellBridgeTest {
    @Test
    fun `pre-binding projection uses stable provisioned identity and unavailable unknown power`() =
        runTest {
            val projectionPort = RecordingProjectionPort()
            val bridge = bridge(projectionPort = projectionPort)
            val starting = OperationalRuntimeProjection(
                lifecycle = OperationalRuntimeLifecycle.STARTING,
                ownerOperation = OWNER,
                sessionGeneration = SESSION,
                deviceId = null,
                link = OperationalLinkState.CONNECTING,
                storage = OperationalStorageState.CLOSED,
                sequence = 1L,
            )

            bridge.publish(starting)

            val snapshot = projectionPort.updates.single().snapshot
            assertEquals(DEVICE, snapshot.deviceId)
            assertEquals(PowerState.UNKNOWN, snapshot.power.value.state)
            assertNull(snapshot.power.value.batteryPercent)
            assertNull(snapshot.power.value.charging)
            assertEquals(ObservationFreshness.UNAVAILABLE, snapshot.power.freshness)
            assertEquals(ProjectionAuthority.EDGE_INFERRED, snapshot.power.authority)
        }

    @Test
    fun `ready projection preserves operational authority without inventing capture truth`() = runTest {
        val projectionPort = RecordingProjectionPort()
        val bridge = bridge(projectionPort = projectionPort, clock = MutableClock(10_000))

        val result = assertIs<OperationalShellPublishResult.Forwarded>(
            bridge.publish(readyProjection(sequence = 8L)),
        )

        assertIs<ShellUpdateResult.Applied>(result.downstream)
        val update = result.update
        assertEquals(SESSION, update.ownerGeneration)
        assertEquals(8L, update.sequence)
        with(update.snapshot) {
            assertEquals(DEVICE, deviceId)
            assertEquals("Omi CV1", displayName)
            assertIs<CaptureTruth.Unverified>(capture.value.truth)
            assertEquals(ObservationFreshness.UNAVAILABLE, capture.freshness)
            assertEquals(ProjectionAuthority.EDGE_INFERRED, capture.authority)
            assertNull(capture.connectionSessionGeneration)
            assertEquals(LinkState.READY, link.value)
            assertEquals(ProjectionAuthority.EDGE_INFERRED, link.authority)
            assertEquals(SESSION, link.connectionSessionGeneration)
            assertEquals(42u, power.value.batteryPercent)
            assertEquals(true, power.value.charging)
            assertEquals(PowerLevel.UNKNOWN, power.value.level)
            assertEquals(ProjectionAuthority.DEVICE_REPORTED, power.authority)
            assertEquals(ObservationFreshness.FRESH, power.freshness)
            assertEquals(StorageState.HEALTHY, storage.value.state)
            assertEquals(ProjectionAuthority.EDGE_INFERRED, storage.authority)
            assertEquals(3uL, sync.value.backlog.pendingItems)
            assertEquals(4_096uL, sync.value.backlog.pendingBytes)
            assertEquals(SyncState.UNKNOWN, sync.value.state)
            assertEquals(ProjectionAuthority.EDGE_INFERRED, sync.authority)
            assertEquals(ObservationFreshness.UNAVAILABLE, this.update.freshness)
        }
        val projected = ShellProjector.project(update.snapshot, 10_000)
        assertEquals(CapturePresentationKind.UNKNOWN, projected.capture.value.kind)
        assertEquals(CaptureAssurance.MAY_BE_ACTIVE, projected.capture.value.assurance)
    }

    @Test
    fun `device capture capability becomes fresh physical truth and disconnect preserves uncertainty`() =
        runTest {
            val projectionPort = RecordingProjectionPort()
            val clock = MutableClock(1_000)
            val bridge = bridge(projectionPort = projectionPort, clock = clock)
            val idle = readyProjection(sequence = 1L).copy(
                capture = OperationalCaptureTruth.DEVICE_REPORTED,
                captureState = deviceCapture(recording = false, generation = 4UL),
                captureObservationRevision = 1UL,
            )

            bridge.publish(idle)
            val idleCapture = projectionPort.updates.last().snapshot.capture
            assertEquals(ProjectionAuthority.DEVICE_REPORTED, idleCapture.authority)
            assertEquals(ObservationFreshness.FRESH, idleCapture.freshness)
            assertEquals(
                CaptureMode.IDLE,
                assertIs<CaptureTruth.Acquired>(idleCapture.value.truth).mode,
            )
            assertEquals(SESSION, idleCapture.connectionSessionGeneration)

            clock.now = 2_000
            val recording = idle.copy(
                sequence = 2L,
                captureState = deviceCapture(recording = true, generation = 5UL),
                captureObservationRevision = 2UL,
            )
            bridge.publish(recording)
            val activeCapture = projectionPort.updates.last().snapshot.capture
            assertEquals(
                CaptureMode.RECORDING,
                assertIs<CaptureTruth.Acquired>(activeCapture.value.truth).mode,
            )
            assertEquals(2_000L, activeCapture.observedAtEpochMillis)

            clock.now = 3_000
            bridge.publish(
                recording.copy(
                    lifecycle = OperationalRuntimeLifecycle.DEGRADED,
                    link = OperationalLinkState.DISCONNECTED,
                    capture = OperationalCaptureTruth.UNVERIFIED,
                    sequence = 3L,
                ),
            )
            val disconnected = projectionPort.updates.last().snapshot.capture
            val uncertain = assertIs<CaptureTruth.Unverified>(disconnected.value.truth)
            assertEquals(CaptureMode.RECORDING, uncertain.lastReportedMode)
            assertEquals(ObservationFreshness.STALE, disconnected.freshness)
            assertNull(disconnected.connectionSessionGeneration)
            assertEquals(
                CapturePresentationKind.MAY_BE_RECORDING,
                ShellProjector.project(projectionPort.updates.last().snapshot, 3_000)
                    .capture.value.kind,
            )
        }

    @Test
    fun `edge host backlog is never presented as per device sync truth`() = runTest {
        val projectionPort = RecordingProjectionPort()
        val clock = MutableClock(1_000)
        val bridge = bridge(projectionPort = projectionPort, clock = clock)
        val hostGlobal = readyProjection(sequence = 1L).copy(
            backlog = OperationalBacklog(9uL, 65_536uL),
            backlogScope = OperationalBacklogScope.EDGE_HOST,
        )

        bridge.publish(hostGlobal)

        val first = projectionPort.updates.single().snapshot.sync
        assertEquals(0uL, first.value.backlog.pendingItems)
        assertEquals(0uL, first.value.backlog.pendingBytes)
        assertEquals(ObservationFreshness.UNAVAILABLE, first.freshness)

        clock.now = 2_000
        bridge.publish(
            hostGlobal.copy(
                sequence = 2L,
                backlog = OperationalBacklog(10uL, 70_000uL),
            ),
        )
        val changedGlobalCount = projectionPort.updates.last().snapshot.sync
        assertEquals(1_000L, changedGlobalCount.observedAtEpochMillis)
        assertEquals(ObservationFreshness.UNAVAILABLE, changedGlobalCount.freshness)
        assertEquals(0uL, changedGlobalCount.value.backlog.pendingItems)
    }

    @Test
    fun `stale conflicting foreign and resurrected projections are rejected before publication`() =
        runTest {
            val projectionPort = RecordingProjectionPort()
            val bridge = bridge(projectionPort = projectionPort)
            val accepted = readyProjection(sequence = 10L)
            assertIs<OperationalShellPublishResult.Forwarded>(bridge.publish(accepted))

            assertIs<OperationalShellPublishResult.Duplicate>(bridge.publish(accepted))
            assertRejected(
                bridge.publish(accepted.copy(sequence = 9L)),
                "OPERATIONAL_SHELL_STALE_SEQUENCE",
            )
            assertRejected(
                bridge.publish(
                    accepted.copy(
                        power = DevicePowerStatus(99u, false, 2L),
                    ),
                ),
                "OPERATIONAL_SHELL_SEQUENCE_CONFLICT",
            )
            assertRejected(
                bridge.publish(
                    accepted.copy(
                        sequence = 11L,
                        power = null,
                        powerObservationRevision = 0uL,
                    ),
                ),
                "OPERATIONAL_SHELL_STALE_POWER_REVISION",
            )
            assertRejected(
                bridge.publish(
                    accepted.copy(
                        sequence = 11L,
                        power = DevicePowerStatus(99u, false, 2L),
                    ),
                ),
                "OPERATIONAL_SHELL_POWER_REVISION_CONFLICT",
            )
            assertRejected(
                bridge.publish(
                    accepted.copy(
                        sequence = 11L,
                        ownerOperation = operation("foreign", OWNER.generation),
                    ),
                ),
                "OPERATIONAL_SHELL_FOREIGN_OWNER_PROJECTION",
            )
            assertRejected(
                bridge.publish(
                    accepted.copy(
                        sequence = 11L,
                        deviceId = DeviceId("another-provisioned-device"),
                    ),
                ),
                "OPERATIONAL_SHELL_FOREIGN_DEVICE_PROJECTION",
            )

            val stopped = accepted.copy(
                lifecycle = OperationalRuntimeLifecycle.STOPPED,
                ownerOperation = null,
                sessionGeneration = null,
                link = OperationalLinkState.DISCONNECTED,
                storage = OperationalStorageState.CLOSED,
                sequence = 11L,
            )
            assertIs<OperationalShellPublishResult.Forwarded>(bridge.publish(stopped))
            assertRejected(
                bridge.publish(accepted.copy(sequence = 12L)),
                "OPERATIONAL_SHELL_ENDED_OWNER_RESURRECTION",
            )
            assertEquals(2, projectionPort.updates.size)
        }

    @Test
    fun `unrelated runtime updates preserve axis receipt while an equal power observation advances it`() =
        runTest {
            val projectionPort = RecordingProjectionPort()
            val clock = MutableClock(1_000)
            val bridge = bridge(projectionPort = projectionPort, clock = clock)
            val first = readyProjection(sequence = 1L)
            bridge.publish(first)

            clock.now = 2_000
            bridge.publish(
                first.copy(
                    sequence = 2L,
                    staleEventCount = 1uL,
                ),
            )
            val unrelated = projectionPort.updates.last().snapshot
            assertEquals(1_000L, unrelated.power.observedAtEpochMillis)
            assertEquals(1_000L, unrelated.link.observedAtEpochMillis)
            assertEquals(1_000L, unrelated.storage.observedAtEpochMillis)
            assertEquals(1_000L, unrelated.sync.observedAtEpochMillis)
            assertEquals(1_000L, unrelated.fault.observedAtEpochMillis)

            clock.now = 3_000
            bridge.publish(
                first.copy(
                    sequence = 3L,
                    powerObservationRevision = 2uL,
                ),
            )
            val refreshed = projectionPort.updates.last().snapshot
            assertEquals(3_000L, refreshed.power.observedAtEpochMillis)
            assertEquals(3_000L, refreshed.link.observedAtEpochMillis)
            assertEquals(1_000L, refreshed.storage.observedAtEpochMillis)
            assertEquals(1_000L, refreshed.sync.observedAtEpochMillis)
            assertEquals(1_000L, refreshed.fault.observedAtEpochMillis)
            assertEquals(42u, refreshed.power.value.batteryPercent)
        }

    @Test
    fun `disconnect is immediate while prior device power becomes stale and capture stays unverified`() =
        runTest {
            val projectionPort = RecordingProjectionPort()
            val clock = MutableClock(1_000)
            val bridge = bridge(projectionPort = projectionPort, clock = clock)
            val connected = readyProjection(sequence = 4L)
            bridge.publish(connected)

            clock.now = 2_000
            val disconnectFailure = ExpectedFailure(
                FailureCategory.DISCONNECTED,
                FailureCode("TEST_DEVICE_DISCONNECTED"),
                retryable = true,
            )
            bridge.publish(
                connected.copy(
                    lifecycle = OperationalRuntimeLifecycle.DEGRADED,
                    link = OperationalLinkState.DISCONNECTED,
                    lastFailure = disconnectFailure,
                    sequence = 5L,
                ),
            )

            val snapshot = projectionPort.updates.last().snapshot
            assertEquals(LinkState.DISCONNECTED, snapshot.link.value)
            assertEquals(ObservationFreshness.FRESH, snapshot.link.freshness)
            assertEquals(ObservationFreshness.STALE, snapshot.power.freshness)
            assertEquals(1_000L, snapshot.power.observedAtEpochMillis)
            assertEquals(42u, snapshot.power.value.batteryPercent)
            assertIs<CaptureTruth.Unverified>(snapshot.capture.value.truth)
            assertEquals(ObservationFreshness.UNAVAILABLE, snapshot.capture.freshness)
            assertEquals(FaultSeverity.RECOVERABLE, snapshot.fault.value.severity)
            assertEquals(disconnectFailure, snapshot.fault.value.failure)
            assertEquals(
                CapturePresentationKind.UNKNOWN,
                ShellProjector.project(snapshot, 2_000).capture.value.kind,
            )
            val unsupported = assertIs<ShellCommandResult.Terminal>(
                bridge.submit(routed(ShellIntent.StartRecording, "disconnected-capture")),
            )
            assertEquals(
                "OPERATIONAL_STOCK_CAPTURE_CONTROL_UNAVAILABLE",
                unsupported.failure?.code?.value,
            )
        }

    @Test
    fun `stock capture commands are explicit rejections and never reach a device effect`() = runTest {
        val projectionPort = RecordingProjectionPort()
        val refreshPort = RecordingPowerRefreshPort()
        val bridge = bridge(projectionPort, refreshPort)
        bridge.publish(readyProjection(sequence = 1L))
        val intents = listOf(
            ShellIntent.StartRecording,
            ShellIntent.StopRecording,
            ShellIntent.StartVoiceTurn(VoiceTurnAdmission("voice-lease", 2_000L)),
            ShellIntent.StopVoiceTurn,
        )

        intents.forEachIndexed { index, intent ->
            val result = assertIs<ShellCommandResult.Terminal>(
                bridge.submit(routed(intent, "capture-$index")),
            )
            assertEquals(ShellTerminalOutcome.REJECTED, result.outcome)
            assertEquals(
                "OPERATIONAL_STOCK_CAPTURE_CONTROL_UNAVAILABLE",
                result.failure?.code?.value,
            )
            assertEquals(CorrelationId("correlation-capture-$index"), result.failure?.correlationId)
        }
        assertTrue(refreshPort.requests.isEmpty())
    }

    @Test
    fun `bridge terminal ledger converges pre-runtime rejection and rejects changed command facts`() =
        runTest {
            val projectionPort = RecordingProjectionPort()
            val refreshPort = RecordingPowerRefreshPort()
            val bridge = bridge(projectionPort, refreshPort)
            val status = routed(ShellIntent.RepeatStatus, "offline-ledger")

            val first = assertIs<ShellCommandResult.Terminal>(bridge.submit(status))
            assertEquals(ShellTerminalOutcome.REJECTED, first.outcome)
            assertEquals("OPERATIONAL_SHELL_RUNTIME_NOT_READY", first.failure?.code?.value)
            assertTrue(!first.replayed)

            bridge.publish(readyProjection(sequence = 1L))
            val replay = assertIs<ShellCommandResult.Terminal>(bridge.submit(status))
            assertEquals(first.outcome, replay.outcome)
            assertEquals(first.failure, replay.failure)
            assertTrue(replay.replayed)
            assertTrue(refreshPort.requests.isEmpty())

            val conflict = assertIs<ShellCommandResult.Terminal>(
                bridge.submit(
                    status.copy(
                        command = status.command.copy(intent = ShellIntent.StartRecording),
                    ),
                ),
            )
            assertEquals(ShellTerminalOutcome.REJECTED, conflict.outcome)
            assertEquals("OPERATIONAL_SHELL_COMMAND_ID_CONFLICT", conflict.failure?.code?.value)
            assertTrue(refreshPort.requests.isEmpty())

            val capture = routed(ShellIntent.StartRecording, "capture-ledger")
            val captureFirst = assertIs<ShellCommandResult.Terminal>(bridge.submit(capture))
            val captureReplay = assertIs<ShellCommandResult.Terminal>(bridge.submit(capture))
            assertEquals(
                "OPERATIONAL_STOCK_CAPTURE_CONTROL_UNAVAILABLE",
                captureFirst.failure?.code?.value,
            )
            assertEquals(captureFirst.failure, captureReplay.failure)
            assertTrue(captureReplay.replayed)
        }

    @Test
    fun `first in-flight command identity wins over a concurrent conflicting envelope`() = runTest {
        val refreshPort = BlockingPowerRefreshPort()
        val bridge = bridge(refreshPort = refreshPort)
        bridge.publish(readyProjection(sequence = 1L))
        val status = routed(ShellIntent.RepeatStatus, "concurrent-ledger")

        val first = async { bridge.submit(status) }
        refreshPort.entered.await()
        val conflicting = async {
            bridge.submit(
                status.copy(command = status.command.copy(intent = ShellIntent.StartRecording)),
            )
        }
        yield()

        assertEquals(1, refreshPort.requests.size)
        assertTrue(!conflicting.isCompleted)
        refreshPort.release.complete(Unit)
        assertEquals(
            ShellTerminalOutcome.COMPLETED,
            assertIs<ShellCommandResult.Terminal>(first.await()).outcome,
        )
        val conflict = assertIs<ShellCommandResult.Terminal>(conflicting.await())
        assertEquals("OPERATIONAL_SHELL_COMMAND_ID_CONFLICT", conflict.failure?.code?.value)
        assertEquals(1, refreshPort.requests.size)
    }

    @Test
    fun `repeat status invokes the exact operational owner and stale shell owners fail closed`() =
        runTest {
            val projectionPort = RecordingProjectionPort()
            val refreshPort = RecordingPowerRefreshPort()
            val bridge = bridge(projectionPort, refreshPort)
            bridge.publish(readyProjection(sequence = 2L))

            val stale = assertIs<ShellCommandResult.Terminal>(
                bridge.submit(routed(ShellIntent.RepeatStatus, "stale", SESSION - 1uL)),
            )
            assertEquals(ShellTerminalOutcome.REJECTED, stale.outcome)
            assertEquals("OPERATIONAL_SHELL_STALE_OWNER_COMMAND", stale.failure?.code?.value)
            assertTrue(refreshPort.requests.isEmpty())

            val refresh = routed(ShellIntent.RepeatStatus, "refresh")
            val result = assertIs<ShellCommandResult.Terminal>(bridge.submit(refresh))
            assertEquals(ShellTerminalOutcome.COMPLETED, result.outcome)
            assertTrue(!result.replayed)
            val request = refreshPort.requests.single()
            assertEquals(CommandId("command-refresh"), request.commandId)
            assertEquals(CorrelationId("correlation-refresh"), request.correlationId)
            assertEquals(OWNER, request.expectedOwner.hostOperation)
            assertEquals(SESSION, request.expectedOwner.sessionGeneration)
            val replay = assertIs<ShellCommandResult.Terminal>(bridge.submit(refresh))
            assertEquals(ShellTerminalOutcome.COMPLETED, replay.outcome)
            assertTrue(replay.replayed)
            assertEquals(1, refreshPort.requests.size)
        }

    private fun bridge(
        projectionPort: RecordingProjectionPort = RecordingProjectionPort(),
        refreshPort: OperationalPowerRefreshPort = RecordingPowerRefreshPort(),
        clock: MutableClock = MutableClock(1_000),
    ) = OperationalShellBridge(
        provisionedDeviceId = DEVICE,
        displayName = "Omi CV1",
        projections = projectionPort,
        powerRefresh = refreshPort,
        receiptClock = clock,
    )

    private fun readyProjection(sequence: Long): OperationalRuntimeProjection =
        OperationalRuntimeProjection(
            lifecycle = OperationalRuntimeLifecycle.READY,
            ownerOperation = OWNER,
            sessionGeneration = SESSION,
            deviceId = DEVICE,
            link = OperationalLinkState.CONNECTED,
            power = DevicePowerStatus(42u, true, 1L),
            powerObservationRevision = 1uL,
            storage = OperationalStorageState.READY,
            backlog = OperationalBacklog(3uL, 4_096uL),
            backlogScope = OperationalBacklogScope.DEVICE,
            sequence = sequence,
        )

    private fun routed(
        intent: ShellIntent,
        suffix: String,
        owner: ULong = SESSION,
    ) = RoutedShellCommand(
        command = ShellCommand(
            id = CommandId("command-$suffix"),
            correlationId = CorrelationId("correlation-$suffix"),
            targetDeviceId = DEVICE,
            issuedAtEpochMillis = 1_000L,
            intent = intent,
        ),
        expectedOwnerGeneration = owner,
    )

    private fun deviceCapture(
        recording: Boolean,
        generation: ULong,
    ) = DeviceCaptureState(
        generation = generation,
        microphone = if (recording) {
            DeviceMicrophoneTruth.ACQUIRED
        } else {
            DeviceMicrophoneTruth.VERIFIED_OFF
        },
        recording = if (recording) {
            DeviceRecordingTruth.ACTIVE
        } else {
            DeviceRecordingTruth.INACTIVE
        },
        voiceAction = DeviceVoiceActionTruth.INACTIVE,
        semanticSignal = DeviceSemanticSignalTruth.INACTIVE,
        privacyOutput = if (recording) {
            DevicePrivacyOutputTruth.ACTIVE
        } else {
            DevicePrivacyOutputTruth.INACTIVE
        },
        maintenance = DeviceMaintenanceTruth.NORMAL,
        availability = if (recording) {
            DeviceCaptureAvailability.BUSY
        } else {
            DeviceCaptureAvailability.READY
        },
        activeRecordingId = if (recording) 9UL else null,
        freeBytes = 8UL * 1024UL * 1024UL,
        faultCode = null,
        observedAtMonotonicMillis = null,
    )

    private fun assertRejected(
        result: OperationalShellPublishResult,
        code: String,
    ) {
        assertEquals(code, assertIs<OperationalShellPublishResult.Rejected>(result).failure.code.value)
    }

    private class RecordingProjectionPort : ShellRuntimeProjectionPort {
        val updates = mutableListOf<DeviceShellUpdate>()

        override suspend fun publish(update: DeviceShellUpdate): ShellUpdateResult {
            updates += update
            return ShellUpdateResult.Applied
        }

        override suspend fun forget(
            deviceId: DeviceId,
            expectedOwnerGeneration: ULong,
        ): ShellForgetResult = error("The operational bridge never deprovisions through publication")

        override suspend fun refresh(): FleetShellProjection =
            FleetShellProjector.aggregate(emptyList())
    }

    private class RecordingPowerRefreshPort : OperationalPowerRefreshPort {
        val requests = mutableListOf<OperationalPowerRefreshRequest>()

        override suspend fun refreshPower(
            request: OperationalPowerRefreshRequest,
        ): OperationalPowerRefreshResult {
            requests += request
            return OperationalPowerRefreshResult.Completed(
                request,
                DevicePowerStatus(42u, true, 2L),
            )
        }
    }

    private class BlockingPowerRefreshPort : OperationalPowerRefreshPort {
        val requests = mutableListOf<OperationalPowerRefreshRequest>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun refreshPower(
            request: OperationalPowerRefreshRequest,
        ): OperationalPowerRefreshResult {
            requests += request
            entered.complete(Unit)
            release.await()
            return OperationalPowerRefreshResult.Completed(
                request,
                DevicePowerStatus(42u, true, 2L),
            )
        }
    }

    private class MutableClock(var now: Long) : ShellClock {
        override fun nowEpochMillis(): Long = now
    }

    private companion object {
        val DEVICE = DeviceId("provisioned-omi-cv1")
        val OWNER = operation("owner", 5uL)
        const val SESSION = 3uL
    }
}

private fun operation(suffix: String, generation: ULong): RuntimeHostOperation =
    RuntimeHostOperation(
        commandId = CommandId("host-command-$suffix"),
        correlationId = CorrelationId("host-correlation-$suffix"),
        generation = generation,
    )
