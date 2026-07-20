package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class OmiCv1FlashLabControllerTest {
    @Test
    fun `dry run proves stock to canary with network unobserved`() = runTest {
        val harness = harness(
            preflight = stockUnobserved(),
            execution = listOf(
                stockUnobserved(),
                canaryStagedUnobserved(),
                canaryStagedUnobserved(confirmed = true),
            ),
            postReboot = canaryActiveUnobserved(),
        )

        harness.driveToReadyToAuthorize()
        harness.authorizeAndExecute()

        assertEquals(
            OmiCv1FlashLabPhase.AWAITING_POST_REBOOT_SCAN,
            harness.controller.state.value.phase,
        )
        assertEquals(true, harness.controller.state.value.resetResponseObserved)
        assertEquals(1, harness.executionSession.uploadCount)
        assertEquals(1, harness.executionSession.confirmCount)
        assertEquals(1, harness.executionSession.resetCount)

        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        runCurrent()
        harness.controller.validatePostReboot()
        runCurrent()

        val final = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, final.phase)
        assertEquals(OmiCv1ApplicationUpdateIntent.MINIMAL_CANARY, final.completedIntent)
        assertEquals(
            OmiCv1Canary0001ApplicationArtifact.manifest.mcubootImageHash,
            final.validation?.applicationHash,
        )
        assertFalse(final.validation?.networkImageObserved ?: true)
    }

    @Test
    fun `dry run proves canary to exact stock recovery with a distinct authorization`() = runTest {
        val harness = harness(
            preflight = canaryActiveUnobserved(),
            execution = listOf(
                canaryActiveUnobserved(),
                stockStagedUnobserved(),
                stockStagedUnobserved(confirmed = true),
            ),
            postReboot = stockUnobserved(),
        )

        harness.driveToReadyToAuthorize()
        assertEquals(
            OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY,
            harness.controller.state.value.review?.intent,
        )
        harness.authorizeAndExecute()
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        runCurrent()
        harness.controller.validatePostReboot()
        runCurrent()

        val final = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, final.phase)
        assertEquals(OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY, final.completedIntent)
        assertEquals(
            OmiCv1StockV3012ApplicationArtifact.manifest.mcubootImageHash,
            final.validation?.applicationHash,
        )
        assertEquals(1, harness.executionSession.uploadCount)
        assertEquals(1, harness.executionSession.confirmCount)
        assertEquals(1, harness.executionSession.resetCount)
    }

    @Test
    fun `fresh execution preflight rejects drift before any upload byte`() = runTest {
        val drifted = stockUnobserved().copy(
            slots = stockUnobserved().slots.map { slot ->
                if (slot.imageNumber == 0 && slot.active) {
                    slot.copy(hash = FirmwareImageHash("44".repeat(32)))
                } else {
                    slot
                }
            },
        )
        val harness = harness(
            preflight = stockUnobserved(),
            execution = listOf(drifted),
            postReboot = canaryActiveUnobserved(),
        )

        harness.driveToReadyToAuthorize()
        harness.authorizeAndExecute()

        val final = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.STOPPED_ON_FAILURE, final.phase)
        assertTrue(final.error.orEmpty().contains("PRECONDITION_FAILED"))
        assertEquals(0, harness.executionSession.uploadCount)
        assertEquals(0, harness.executionSession.confirmCount)
        assertEquals(0, harness.executionSession.resetCount)

        harness.controller.startScan()
        runCurrent()
        assertEquals(OmiCv1FlashLabPhase.STOPPED_ON_FAILURE, harness.controller.state.value.phase)
    }

    @Test
    fun `post reboot selection rejects a different process-local endpoint`() = runTest {
        val harness = harness(
            preflight = stockUnobserved(),
            execution = listOf(
                stockUnobserved(),
                canaryStagedUnobserved(),
                canaryStagedUnobserved(confirmed = true),
            ),
            postReboot = canaryActiveUnobserved(),
        )
        harness.driveToReadyToAuthorize()
        harness.authorizeAndExecute()
        val other = harness.candidate.copy(
            endpoint = EndpointCandidate(TransportKind.BLE, "other-process-local-endpoint"),
        )

        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(other))
        runCurrent()
        harness.controller.select(other)
        runCurrent()

        val state = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.SCANNING, state.phase)
        assertNull(state.selected)
        assertTrue(state.error.orEmpty().contains("not the same process-local Omi endpoint"))
        assertEquals(0, harness.postRebootSession.inspectCount)
    }

    private inner class Harness(
        private val testScope: TestScope,
        val controller: OmiCv1FlashLabController,
        val scanEvents: MutableSharedFlow<OmiCv1FlashLabScanEvent>,
        val candidate: OmiCv1FlashLabCandidate,
        val executionSession: FakeSession,
        val postRebootSession: FakeSession,
    ) {
        suspend fun driveToReadyToAuthorize() {
            controller.refreshEnvironment(true)
            controller.updateChecklist(readyChecklist())
            controller.startScan()
            testScope.runCurrent()
            scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(candidate))
            testScope.runCurrent()
            controller.select(candidate)
            testScope.runCurrent()
            controller.runPreflight()
            testScope.runCurrent()
            assertEquals(OmiCv1FlashLabPhase.READY_TO_AUTHORIZE, controller.state.value.phase)
        }

        fun authorizeAndExecute() {
            controller.updateChecklist(
                controller.state.value.checklist.copy(exactArtifactAuthorized = true),
            )
            controller.authorizeAndExecute()
            testScope.runCurrent()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.harness(
        preflight: FirmwareImageStateInspection,
        execution: List<FirmwareImageStateInspection>,
        postReboot: FirmwareImageStateInspection,
    ): Harness {
        val scanEvents = MutableSharedFlow<OmiCv1FlashLabScanEvent>(extraBufferCapacity = 4)
        val candidate = candidate()
        val preflightSession = FakeSession(listOf(preflight))
        val executionSession = FakeSession(execution)
        val postRebootSession = FakeSession(listOf(postReboot))
        val sessions = ArrayDeque(
            listOf(preflightSession, executionSession, postRebootSession),
        )
        val controller = OmiCv1FlashLabController(
            scanner = OmiCv1FlashLabScanner { scanEvents },
            sessions = OmiCv1ApplicationImage0UpdateSessionFactory {
                sessions.removeFirst()
            },
            artifacts = OmiCv1FlashLabArtifactSource { intent -> exactArtifact(intent) },
            phonePower = OmiCv1FlashLabPhonePowerSource {
                OmiCv1FlashLabPhonePower(percent = 100, charging = true)
            },
            clock = MonotonicMillisClock { 100 },
            scope = backgroundScope,
        )
        return Harness(this, controller, scanEvents, candidate, executionSession, postRebootSession)
    }

    private fun readyChecklist() = OmiCv1FlashLabChecklist(
        omiBatteryAtLeast80 = true,
        officialOmiAppStopped = true,
        chargerAvailable = true,
        noRollbackRiskAccepted = true,
    )

    private fun candidate() = OmiCv1FlashLabCandidate(
        endpoint = ownedEndpoint,
        advertisedName = "Omi",
        rssi = -42,
        connectable = true,
    )

    private fun stockUnobserved() = inspection(
        ownedEndpoint,
        activeSlot(
            0,
            OmiCv1V3012ApplicationUpdateCatalog.stockToCanary0001.source.applicationHash,
        ),
    )

    private fun canaryStagedUnobserved(confirmed: Boolean = false) =
        networkUnobservedStagedInspection(
            ownedEndpoint,
            OmiCv1V3012ApplicationUpdateCatalog.stockToCanary0001,
            confirmed,
        )

    private fun canaryActiveUnobserved() = inspection(
        ownedEndpoint,
        activeSlot(0, OmiCv1Canary0001ApplicationArtifact.manifest.mcubootImageHash),
    )

    private fun stockStagedUnobserved(confirmed: Boolean = false) =
        networkUnobservedStagedInspection(
            ownedEndpoint,
            OmiCv1V3012ApplicationUpdateCatalog.canary0001ToStock,
            confirmed,
        )

    private fun exactArtifact(intent: OmiCv1ApplicationUpdateIntent): ByteArray {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(workingDirectory)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Cannot locate Gumi repository root")
        val relative = when (intent) {
            OmiCv1ApplicationUpdateIntent.MINIMAL_CANARY ->
                "local/firmware/omi-cv1/canary-0001/omi.signed.bin"

            OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY ->
                "local/firmware/omi-cv1/stock-v3.0.12/omi.signed.bin"
        }
        return File(root, relative).readBytes()
    }

    private class FakeSession(
        inspections: List<FirmwareImageStateInspection>,
    ) : OmiCv1ApplicationImage0UpdateSession {
        private val inspections = ArrayDeque(inspections)
        var inspectCount = 0
        var uploadCount = 0
        var confirmCount = 0
        var resetCount = 0
        var releaseCount = 0

        override suspend fun upload(imageBytes: ByteArray, onProgress: (Int, Int) -> Unit) {
            uploadCount += 1
            onProgress(imageBytes.size, imageBytes.size)
        }

        override suspend fun inspect(): FirmwareImageStateInspection {
            inspectCount += 1
            return inspections.removeFirst()
        }

        override suspend fun confirm(mcubootImageHash: FirmwareImageHash) {
            confirmCount += 1
        }

        override suspend fun requestReset(): Boolean {
            resetCount += 1
            return true
        }

        override fun cancel() = Unit

        override fun release() {
            releaseCount += 1
        }
    }

    private companion object {
        val ownedEndpoint = EndpointCandidate(TransportKind.BLE, "owned-omi-endpoint")
    }
}
