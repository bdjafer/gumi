package dev.gumi.edge.runtime.device

import dev.gumi.edge.runtime.capture.CaptureCommand
import dev.gumi.edge.runtime.capture.CaptureCommandKind
import dev.gumi.edge.runtime.capture.CaptureMode
import dev.gumi.edge.runtime.capture.CaptureReductionOutcome
import dev.gumi.edge.runtime.capture.CaptureTerminalOutcome
import dev.gumi.edge.runtime.capture.CaptureTruth
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSupervisorTest {
    @Test
    fun `directory gives one serialized owner to each stable device identity`() = runTest {
        val directory = DeviceSupervisorDirectory(this)
        val deviceId = DeviceId("one-owned-device")

        val first = assertIs<DeviceSupervisorClaimResult.Claimed>(
            directory.claim(deviceId, FakeCaptureHardware()),
        )
        val duplicate = assertIs<DeviceSupervisorClaimResult.AlreadyOwned>(
            directory.claim(deviceId, FakeCaptureHardware()),
        )

        assertTrue(first.supervisor === duplicate.supervisor)
        directory.release(deviceId)
        val reclaimed = assertIs<DeviceSupervisorClaimResult.Claimed>(
            directory.claim(deviceId, FakeCaptureHardware()),
        )
        assertTrue(reclaimed.supervisor !== first.supervisor)
        directory.close()
        val afterClose = assertIs<DeviceSupervisorClaimResult.Rejected>(
            directory.claim(deviceId, FakeCaptureHardware()),
        )
        assertEquals("DEVICE_SUPERVISOR_DIRECTORY_CLOSED", afterClose.failure.code.value)
    }

    @Test
    fun `slow release keeps same device owned without blocking unrelated devices`() = runTest {
        val directory = DeviceSupervisorDirectory(this)
        val firstDevice = DeviceId("slow-closing-device")
        val otherDevice = DeviceId("independent-device")
        val cleanupGate = CompletableDeferred<Unit>()
        val firstHardware = FakeCaptureHardware(
            modeHandler = { request ->
                withContext(NonCancellable) {
                    cleanupGate.await()
                    CaptureModeCompletion.Acquired(request.correlationId, request.targetMode)
                }
            },
        )
        val first = assertIs<DeviceSupervisorClaimResult.Claimed>(
            directory.claim(firstDevice, firstHardware),
        ).supervisor
        assertEquals(DeviceSupervisorStartResult.Started, first.start())
        first.publish(DeviceSupervisorEvent.LinkChanged(DeviceLinkState.CONNECTED, SESSION))
        first.publish(DeviceSupervisorEvent.CaptureStateObserved(CaptureMode.IDLE, SESSION))
        runCurrent()
        first.submit(
            DeviceSupervisorCommand.Capture(
                command("slow-directory-release", CaptureCommandKind.START_RECORDING),
            ),
        )
        runCurrent()
        assertEquals(1, firstHardware.modeRequests.size)

        val release = async { directory.release(firstDevice) }
        runCurrent()
        try {
            assertEquals(DeviceSupervisorLifecycle.CLOSING, first.projection.value.lifecycle)

            assertIs<DeviceSupervisorClaimResult.Claimed>(
                directory.claim(otherDevice, FakeCaptureHardware()),
            )
            val sameDevice = assertIs<DeviceSupervisorClaimResult.AlreadyOwned>(
                directory.claim(firstDevice, FakeCaptureHardware()),
            )
            assertTrue(sameDevice.supervisor === first)
        } finally {
            cleanupGate.complete(Unit)
            release.await()
        }

        assertEquals(DeviceSupervisorLifecycle.CLOSED, first.projection.value.lifecycle)
        val replacement = assertIs<DeviceSupervisorClaimResult.Claimed>(
            directory.claim(firstDevice, FakeCaptureHardware()),
        )
        assertTrue(replacement.supervisor !== first)
        directory.close()
    }

    @Test
    fun `boot is unverified and cannot issue a mode effect before explicit device evidence`() = runTest {
        val hardware = FakeCaptureHardware()
        val supervisor = supervisor(hardware)
        supervisor.start()

        assertIs<CaptureTruth.Unverified>(supervisor.projection.value.capture.truth)
        supervisor.submit(
            DeviceSupervisorCommand.Capture(
                command("unverified-boot", CaptureCommandKind.START_RECORDING),
            ),
        )
        runCurrent()

        assertTrue(hardware.modeRequests.isEmpty())
        val terminal = supervisor.projection.value.capture.terminalCommands.values.single()
        assertEquals(
            "CAPTURE_TRUTH_UNVERIFIED",
            assertIs<CaptureTerminalOutcome.Rejected>(terminal.outcome).failure.code.value,
        )
        supervisor.close()
    }

    @Test
    fun `capture observation from another connection generation cannot verify boot truth`() = runTest {
        val supervisor = supervisor(FakeCaptureHardware())
        supervisor.start()
        supervisor.publish(
            DeviceSupervisorEvent.LinkChanged(DeviceLinkState.CONNECTED, SESSION),
        )
        supervisor.publish(
            DeviceSupervisorEvent.CaptureStateObserved(CaptureMode.IDLE, SESSION + 1u),
        )
        runCurrent()

        assertIs<CaptureTruth.Unverified>(supervisor.projection.value.capture.truth)
        val rejected = assertIs<DeviceSupervisorOutcome.CaptureObservationRejected>(
            supervisor.projection.value.lastOutcome,
        )
        assertEquals("CAPTURE_OBSERVATION_SESSION_MISMATCH", rejected.failure.code.value)
        supervisor.close()
    }

    @Test
    fun `concurrent commands are serialized while the slow effect runs outside the owner`() = runTest {
        val modeGate = CompletableDeferred<CaptureModeCompletion>()
        val hardware = FakeCaptureHardware(
            modeHandler = { modeGate.await() },
        )
        val supervisor = supervisor(hardware, mailboxCapacity = 16)
        startWithIdleEvidence(supervisor)
        val commands = (1..8).map { index ->
            command("concurrent-$index", CaptureCommandKind.START_RECORDING)
        }

        val submissions = commands.map { command ->
            async { supervisor.submit(DeviceSupervisorCommand.Capture(command)) }
        }.awaitAll()
        runCurrent()

        assertTrue(submissions.all { it == DeviceSupervisorSubmissionResult.Accepted })
        assertEquals(1, hardware.modeRequests.size)
        val projection = supervisor.projection.value
        assertNotNull(projection.capture.transition)
        assertEquals(7, projection.capture.terminalCommands.size)
        assertTrue(
            projection.capture.terminalCommands.values.all {
                it.outcome is CaptureTerminalOutcome.Rejected
            },
        )

        val request = hardware.modeRequests.single()
        modeGate.complete(
            CaptureModeCompletion.Acquired(request.correlationId, request.targetMode),
        )
        runCurrent()

        assertEquals(
            CaptureMode.RECORDING,
            assertIs<CaptureTruth.Acquired>(supervisor.projection.value.capture.truth).mode,
        )
        supervisor.close()
    }

    @Test
    fun `duplicate command is idempotent and never repeats its hardware effect`() = runTest {
        val modeGate = CompletableDeferred<CaptureModeCompletion>()
        val hardware = FakeCaptureHardware(modeHandler = { modeGate.await() })
        val supervisor = supervisor(hardware)
        val command = command("duplicate", CaptureCommandKind.START_RECORDING)
        startWithIdleEvidence(supervisor)

        assertEquals(
            DeviceSupervisorSubmissionResult.Accepted,
            supervisor.submit(DeviceSupervisorCommand.Capture(command)),
        )
        assertEquals(
            DeviceSupervisorSubmissionResult.Accepted,
            supervisor.submit(DeviceSupervisorCommand.Capture(command)),
        )
        runCurrent()

        assertEquals(1, hardware.modeRequests.size)
        val duplicate = assertIs<DeviceSupervisorOutcome.CaptureReduced>(
            supervisor.projection.value.lastOutcome,
        )
        val outcome = assertIs<CaptureReductionOutcome.CommandDuplicate>(duplicate.outcome)
        assertTrue(outcome.inFlight)

        val request = hardware.modeRequests.single()
        modeGate.complete(CaptureModeCompletion.Acquired(request.correlationId, request.targetMode))
        runCurrent()

        val terminalDuplicate = command.copy()
        supervisor.submit(DeviceSupervisorCommand.Capture(terminalDuplicate))
        runCurrent()
        assertEquals(1, hardware.modeRequests.size)
        assertTrue(
            assertIs<CaptureReductionOutcome.CommandDuplicate>(
                assertIs<DeviceSupervisorOutcome.CaptureReduced>(
                    supervisor.projection.value.lastOutcome,
                ).outcome,
            ).inFlight.not(),
        )
        supervisor.close()
    }

    @Test
    fun `stale slow-effect completion cannot resolve the active transition`() = runTest {
        val staleCorrelation = CorrelationId("stale-hardware-completion")
        val hardware = FakeCaptureHardware(
            modeHandler = { request ->
                CaptureModeCompletion.Acquired(staleCorrelation, request.targetMode)
            },
        )
        val supervisor = supervisor(hardware)
        val command = command("current", CaptureCommandKind.START_RECORDING)
        startWithIdleEvidence(supervisor)

        supervisor.submit(DeviceSupervisorCommand.Capture(command))
        runCurrent()

        assertEquals(command, supervisor.projection.value.capture.transition?.command)
        assertEquals(
            CaptureMode.IDLE,
            assertIs<CaptureTruth.Acquired>(supervisor.projection.value.capture.truth).mode,
        )
        val reduced = assertIs<DeviceSupervisorOutcome.CaptureReduced>(
            supervisor.projection.value.lastOutcome,
        )
        assertEquals(
            CaptureReductionOutcome.StaleHardwareCompletion(staleCorrelation),
            reduced.outcome,
        )
        supervisor.close()
    }

    @Test
    fun `link loss is orthogonal and does not rewrite verified capture truth`() = runTest {
        val hardware = FakeCaptureHardware()
        val supervisor = supervisor(hardware)
        startWithIdleEvidence(supervisor)
        supervisor.submit(
            DeviceSupervisorCommand.Capture(
                command("recording", CaptureCommandKind.START_RECORDING),
            ),
        )
        runCurrent()
        val captureBeforeDisconnect = supervisor.projection.value.capture
        assertEquals(
            CaptureMode.RECORDING,
            assertIs<CaptureTruth.Acquired>(captureBeforeDisconnect.truth).mode,
        )

        supervisor.publish(
            DeviceSupervisorEvent.LinkChanged(DeviceLinkState.DISCONNECTED, SESSION),
        )
        runCurrent()

        assertEquals(DeviceLinkState.DISCONNECTED, supervisor.projection.value.link)
        assertEquals(captureBeforeDisconnect, supervisor.projection.value.capture)
        supervisor.close()
    }

    @Test
    fun `fatal capture fault remains Unknown until correlated emergency release completes`() = runTest {
        val releaseGate = CompletableDeferred<EmergencyCaptureReleaseCompletion>()
        val hardware = FakeCaptureHardware(
            releaseHandler = { releaseGate.await() },
        )
        val supervisor = supervisor(hardware)
        val recovery = CorrelationId("fatal-recovery")
        val fault = failure("CAPTURE_DEVICE_FAULT", recovery)
        startWithIdleEvidence(supervisor)

        supervisor.publish(
            DeviceSupervisorEvent.FatalCaptureFault(fault, recovery, SESSION),
        )
        runCurrent()

        val unknown = assertIs<CaptureTruth.Unknown>(supervisor.projection.value.capture.truth)
        assertEquals(fault, unknown.failure)
        assertEquals(recovery, unknown.recoveryCorrelationId)
        assertEquals(
            listOf(EmergencyCaptureReleaseRequest(recovery)),
            hardware.releaseRequests,
        )

        releaseGate.complete(EmergencyCaptureReleaseCompletion.Released(recovery))
        runCurrent()

        val released = assertIs<CaptureTruth.Acquired>(supervisor.projection.value.capture.truth)
        assertEquals(CaptureMode.IDLE, released.mode)
        assertEquals(
            dev.gumi.edge.runtime.capture.CaptureProofSource.EMERGENCY_RELEASE,
            released.proof.source,
        )
        val outcome = assertIs<DeviceSupervisorOutcome.CaptureReduced>(
            supervisor.projection.value.lastOutcome,
        )
        assertEquals(
            CaptureReductionOutcome.FailSafeReleaseConfirmed(recovery),
            outcome.outcome,
        )
        supervisor.close()
    }

    @Test
    fun `fatal release settles a causally older acquire before establishing Idle`() = runTest {
        val acquireGate = CompletableDeferred<Unit>()
        val physicalOrder = mutableListOf<String>()
        val hardware = FakeCaptureHardware(
            modeHandler = { request ->
                withContext(NonCancellable) {
                    acquireGate.await()
                    physicalOrder += "acquired-${request.targetMode}"
                    CaptureModeCompletion.Acquired(request.correlationId, request.targetMode)
                }
            },
            releaseHandler = { request ->
                physicalOrder += "released"
                EmergencyCaptureReleaseCompletion.Released(request.recoveryCorrelationId)
            },
        )
        val supervisor = supervisor(hardware)
        startWithIdleEvidence(supervisor)
        supervisor.submit(
            DeviceSupervisorCommand.Capture(
                command("racing-acquire", CaptureCommandKind.START_RECORDING),
            ),
        )
        runCurrent()

        val recovery = CorrelationId("fatal-during-acquire")
        supervisor.publish(
            DeviceSupervisorEvent.FatalCaptureFault(
                failure("CAPTURE_DEVICE_FAULT", recovery),
                recovery,
                SESSION,
            ),
        )
        runCurrent()

        assertIs<CaptureTruth.Unknown>(supervisor.projection.value.capture.truth)
        assertTrue(hardware.releaseRequests.isEmpty())
        assertTrue(physicalOrder.isEmpty())

        acquireGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("acquired-RECORDING", "released"), physicalOrder)
        val finalTruth = assertIs<CaptureTruth.Acquired>(supervisor.projection.value.capture.truth)
        assertEquals(CaptureMode.IDLE, finalTruth.mode)
        assertEquals(
            dev.gumi.edge.runtime.capture.CaptureProofSource.EMERGENCY_RELEASE,
            finalTruth.proof.source,
        )
        assertTrue(finalTruth.proof.causalGeneration > 2u)
        supervisor.close()
    }

    @Test
    fun `unexpected mode effect failure enters fail-safe and requests emergency release`() = runTest {
        val releaseGate = CompletableDeferred<EmergencyCaptureReleaseCompletion>()
        val hardware = FakeCaptureHardware(
            modeHandler = { error("transport exploded") },
            releaseHandler = { releaseGate.await() },
        )
        val supervisor = supervisor(hardware)
        val command = command("effect-failure", CaptureCommandKind.START_RECORDING)
        startWithIdleEvidence(supervisor)

        supervisor.submit(DeviceSupervisorCommand.Capture(command))
        runCurrent()

        assertIs<CaptureTruth.Unknown>(supervisor.projection.value.capture.truth)
        assertEquals(1, hardware.releaseRequests.size)
        val outcome = assertIs<DeviceSupervisorOutcome.CaptureEffectFailed>(
            supervisor.projection.value.lastOutcome,
        )
        assertEquals("CAPTURE_HARDWARE_EFFECT_FAILED", outcome.failure.code.value)
        assertIs<CaptureReductionOutcome.EnteredFailSafe>(outcome.failSafeOutcome)
        assertEquals(outcome.failure, supervisor.projection.value.lastFailure)

        releaseGate.complete(
            EmergencyCaptureReleaseCompletion.Released(command.correlationId),
        )
        runCurrent()
        supervisor.close()
    }

    @Test
    fun `failed emergency release never launders Unknown capture truth into Idle`() = runTest {
        val recovery = CorrelationId("failed-emergency-release")
        val releaseFailure = failure("CAPTURE_RELEASE_FAILED", recovery)
        val hardware = FakeCaptureHardware(
            releaseHandler = { request ->
                EmergencyCaptureReleaseCompletion.Failed(
                    request.recoveryCorrelationId,
                    releaseFailure,
                )
            },
        )
        val supervisor = supervisor(hardware)
        val fatal = failure("CAPTURE_DEVICE_FAULT", recovery)
        startWithIdleEvidence(supervisor)

        supervisor.publish(
            DeviceSupervisorEvent.FatalCaptureFault(fatal, recovery, SESSION),
        )
        runCurrent()

        assertEquals(
            fatal,
            assertIs<CaptureTruth.Unknown>(supervisor.projection.value.capture.truth).failure,
        )
        val outcome = assertIs<DeviceSupervisorOutcome.CaptureEffectFailed>(
            supervisor.projection.value.lastOutcome,
        )
        assertEquals(releaseFailure, outcome.failure)
        assertEquals(releaseFailure, supervisor.projection.value.lastFailure)
        supervisor.close()
    }

    @Test
    fun `close is idempotent and commands and events are explicitly rejected afterwards`() = runTest {
        val supervisor = supervisor(FakeCaptureHardware())
        assertEquals(DeviceSupervisorStartResult.Started, supervisor.start())
        assertEquals(DeviceSupervisorStartResult.AlreadyRunning, supervisor.start())

        supervisor.close()
        supervisor.close()

        assertEquals(DeviceSupervisorLifecycle.CLOSED, supervisor.projection.value.lifecycle)
        val commandRejection = assertIs<DeviceSupervisorSubmissionResult.Rejected>(
            supervisor.submit(
                DeviceSupervisorCommand.Capture(
                    command("after-close", CaptureCommandKind.START_RECORDING),
                ),
            ),
        )
        assertEquals("DEVICE_SUPERVISOR_CLOSED", commandRejection.failure.code.value)
        val eventRejection = assertIs<DeviceSupervisorSubmissionResult.Rejected>(
            supervisor.publish(
                DeviceSupervisorEvent.LinkChanged(DeviceLinkState.CONNECTED, SESSION),
            ),
        )
        assertEquals("DEVICE_SUPERVISOR_CLOSED", eventRejection.failure.code.value)
        assertIs<DeviceSupervisorStartResult.Rejected>(supervisor.start())
    }

    @Test
    fun `bounded mailbox reports overload instead of blocking or dropping silently`() = runTest {
        val supervisor = supervisor(FakeCaptureHardware(), mailboxCapacity = 1)
        supervisor.start()

        val accepted = supervisor.publish(
            DeviceSupervisorEvent.LinkChanged(DeviceLinkState.CONNECTED, SESSION),
        )
        val overloaded = supervisor.publish(
            DeviceSupervisorEvent.LinkChanged(DeviceLinkState.DISCONNECTED, SESSION),
        )

        assertEquals(DeviceSupervisorSubmissionResult.Accepted, accepted)
        val rejection = assertIs<DeviceSupervisorSubmissionResult.Rejected>(overloaded)
        assertEquals(FailureCategory.RESOURCE_EXHAUSTED, rejection.failure.category)
        assertEquals("DEVICE_SUPERVISOR_MAILBOX_FULL", rejection.failure.code.value)
        assertTrue(rejection.failure.retryable)
        supervisor.close()
    }

    private fun kotlinx.coroutines.test.TestScope.supervisor(
        hardware: CaptureHardwarePort,
        mailboxCapacity: Int = DeviceSupervisor.DEFAULT_MAILBOX_CAPACITY,
    ) = DeviceSupervisor(
        deviceId = DeviceId("device-under-test"),
        parentScope = this,
        captureHardware = hardware,
        mailboxCapacity = mailboxCapacity,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.startWithIdleEvidence(
        supervisor: DeviceSupervisor,
    ) {
        assertEquals(DeviceSupervisorStartResult.Started, supervisor.start())
        assertEquals(
            DeviceSupervisorSubmissionResult.Accepted,
            supervisor.publish(
                DeviceSupervisorEvent.LinkChanged(DeviceLinkState.CONNECTED, SESSION),
            ),
        )
        assertEquals(
            DeviceSupervisorSubmissionResult.Accepted,
            supervisor.publish(
                DeviceSupervisorEvent.CaptureStateObserved(CaptureMode.IDLE, SESSION),
            ),
        )
        runCurrent()
        assertEquals(
            CaptureMode.IDLE,
            assertIs<CaptureTruth.Acquired>(supervisor.projection.value.capture.truth).mode,
        )
    }

    private fun command(id: String, kind: CaptureCommandKind) = CaptureCommand(
        id = CommandId("command-$id"),
        correlationId = CorrelationId("correlation-$id"),
        kind = kind,
    )

    private fun failure(code: String, correlationId: CorrelationId) = ExpectedFailure(
        category = FailureCategory.INTERNAL,
        code = FailureCode(code),
        retryable = false,
        correlationId = correlationId,
    )
}

private const val SESSION: ULong = 11u

private class FakeCaptureHardware(
    private val modeHandler: suspend (CaptureModeRequest) -> CaptureModeCompletion = { request ->
        CaptureModeCompletion.Acquired(request.correlationId, request.targetMode)
    },
    private val releaseHandler:
        suspend (EmergencyCaptureReleaseRequest) -> EmergencyCaptureReleaseCompletion = { request ->
            EmergencyCaptureReleaseCompletion.Released(request.recoveryCorrelationId)
        },
) : CaptureHardwarePort {
    val modeRequests = mutableListOf<CaptureModeRequest>()
    val releaseRequests = mutableListOf<EmergencyCaptureReleaseRequest>()

    override suspend fun requestMode(request: CaptureModeRequest): CaptureModeCompletion {
        modeRequests += request
        return modeHandler(request)
    }

    override suspend fun emergencyRelease(
        request: EmergencyCaptureReleaseRequest,
    ): EmergencyCaptureReleaseCompletion {
        releaseRequests += request
        return releaseHandler(request)
    }
}
