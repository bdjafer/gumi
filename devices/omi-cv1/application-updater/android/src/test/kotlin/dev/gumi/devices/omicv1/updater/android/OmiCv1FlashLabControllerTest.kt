package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1GattEvidence
import dev.gumi.devices.omicv1.OmiCv1StockV3007FirmwareIdentity
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class OmiCv1FlashLabControllerTest {
    @Test
    fun `dry run proves stock to recovery with fail-closed evidence and network unobserved`() = runTest {
        val harness = harness(
            preflight = stockUnobserved(),
            execution = listOf(
                stockUnobserved(),
                recoveryStagedUnobserved(),
                recoveryStagedUnobserved(confirmed = true),
            ),
            postReboot = recoveryActiveUnobserved(),
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
        val validation = requireNotNull(final.validation)
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, final.phase)
        assertEquals(OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY, final.completedIntent)
        assertEquals(
            OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.mcubootImageHash,
            validation.applicationHash,
        )
        assertFalse(validation.networkImageObserved)
        assertEquals("01070123", validation.recoveryStatus?.rawHex)
        assertEquals(1, harness.recoveryStatusProbe.inspectCount)

        harness.controller.recheckRecoveryStatus()
        runCurrent()

        assertEquals(OmiCv1FlashLabPhase.VALIDATED, harness.controller.state.value.phase)
        assertTrue(harness.controller.state.value.error.orEmpty().contains("10-minute"))
        assertEquals(1, harness.recoveryStatusProbe.inspectCount)

        harness.clock.advanceBy(600_000)
        harness.controller.recheckRecoveryStatus()
        runCurrent()

        assertEquals(OmiCv1FlashLabPhase.VALIDATED, harness.controller.state.value.phase)
        assertEquals("01070123", harness.controller.state.value.validation?.recoveryStatus?.rawHex)
        assertEquals(2, harness.recoveryStatusProbe.inspectCount)
    }

    @Test
    fun `dry run proves recovery to exact stock with a distinct authorization`() = runTest {
        val harness = harness(
            preflight = recoveryActiveUnobserved(),
            execution = listOf(
                recoveryActiveUnobserved(),
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
        assertEquals(1, harness.recoveryStatusProbe.inspectCount)
    }

    @Test
    fun `recovery source transition requires fresh recovery status before authorization`() = runTest {
        val harness = harness(
            preflight = recoveryActiveUnobserved(),
            execution = emptyList(),
            postReboot = recoveryActiveUnobserved(),
            recoveryStatusFailure = OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.RECOVERY_EVIDENCE_REJECTED,
                "Recovery topology mismatch",
            ),
        )

        harness.controller.refreshEnvironment(true)
        harness.controller.updateChecklist(readyChecklist())
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.runPreflight(OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY)
        runCurrent()

        val rejected = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.STOPPED_ON_FAILURE, rejected.phase)
        assertTrue(rejected.error.orEmpty().contains("RECOVERY_EVIDENCE_REJECTED"))
        assertEquals(recoveryActiveUnobserved(), rejected.preflightInspection)
        assertEquals(1, harness.recoveryStatusProbe.inspectCount)
        assertEquals(0, harness.executionSession.uploadCount)
    }

    @Test
    fun `rejected functional maintenance receipt preserves exact active source identity`() = runTest {
        val harness = harness(
            preflight = functionalRecording0005ActiveUnobserved(),
            execution = emptyList(),
            postReboot = functionalRecording0005ActiveUnobserved(),
            deviceManufacturer = OmiCv1V3012ApplicationUpdateCatalog.GUMI_MANUFACTURER,
            functionalStatuses = listOf(exactFunctionalReadyStatus()),
        )

        harness.controller.refreshEnvironment(true)
        harness.controller.updateChecklist(readyChecklist())
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.runPreflight(OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY)
        runCurrent()

        val stopped = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.STOPPED_ON_FAILURE, stopped.phase)
        assertTrue(
            stopped.error.orEmpty().contains(
                OmiCv1FunctionalRecording0005ApplicationArtifact.manifest.mcubootImageHash.hex,
            ),
        )
        assertTrue(
            stopped.error.orEmpty().contains(
                OmiCv1V3012ApplicationUpdateCatalog
                    .functionalRecording0005ToRecoveryOnly0001
                    .releaseId,
            ),
        )
        assertEquals(0, harness.executionSession.uploadCount)
    }

    @Test
    fun `device-reported low battery remains visible without blocking review`() = runTest {
        val harness = harness(
            preflight = stockUnobserved(),
            execution = emptyList(),
            postReboot = stockUnobserved(),
            deviceBatteryPercent = 1,
        )

        harness.controller.refreshEnvironment(true)
        harness.controller.updateChecklist(readyChecklist())
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.runPreflight()
        runCurrent()

        val reviewed = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.READY_TO_AUTHORIZE, reviewed.phase)
        assertEquals(1, reviewed.devicePreflight?.identity?.batteryPercent)
        assertEquals(stockUnobserved(), reviewed.preflightInspection)
        assertEquals(null, reviewed.error)
        assertEquals(0, harness.executionSession.uploadCount)
    }

    @Test
    fun `controller proves official dual-image normalization before enabling next transition`() = runTest {
        val source = stockV3007Unobserved()
        val normalized = stockUnobserved()
        val normalizationSession = FakeStockNormalizationSession(source)
        val harness = harness(
            preflight = source,
            execution = listOf(normalized),
            postReboot = normalized,
            deviceFirmwareRevisions = listOf("3.0.7", "3.0.7", "3.0.12"),
            normalizationSession = normalizationSession,
            stockNormalizationArtifacts = exactStockNormalizationArtifacts(),
        )

        harness.controller.refreshEnvironment(true)
        harness.controller.updateChecklist(readyChecklist())
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.runStockNormalizationPreflight()
        runCurrent()

        assertEquals(
            OmiCv1FlashLabOperation.OFFICIAL_STOCK_DUAL_IMAGE_NORMALIZATION,
            harness.controller.state.value.review?.operation,
        )
        harness.controller.updateChecklist(
            harness.controller.state.value.checklist.copy(exactArtifactAuthorized = true),
        )
        harness.controller.authorizeAndExecute()
        runCurrent()

        assertEquals(1, normalizationSession.normalizeCount)
        assertEquals(
            OmiCv1FlashLabPhase.AWAITING_POST_REBOOT_SCAN,
            harness.controller.state.value.phase,
        )

        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.validatePostReboot()
        runCurrent()

        val final = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, final.phase)
        assertEquals("3.0.12", final.normalizationValidation?.firmwareRevision)
        assertEquals(
            OmiCv1FlashLabOperation.OFFICIAL_STOCK_DUAL_IMAGE_NORMALIZATION,
            final.completedOperation,
        )
    }

    @Test
    fun `dry run proves provisioner to exact reclaimer then offers functional v0007`() = runTest {
        val harness = harness(
            preflight = recordingRootProvisionerActiveUnobserved(),
            execution = listOf(
                recordingRootProvisionerActiveUnobserved(),
                legacyStorageReclaimerStagedUnobserved(),
                legacyStorageReclaimerStagedUnobserved(confirmed = true),
            ),
            postReboot = legacyStorageReclaimerActiveUnobserved(),
            deviceManufacturer = OmiCv1V3012ApplicationUpdateCatalog.GUMI_MANUFACTURER,
        )

        harness.driveToReadyToAuthorize(OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER)
        assertEquals(1, harness.recordingRootProvisionerStatusProbe.inspectCount)
        assertEquals(
            OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER,
            harness.controller.state.value.review?.intent,
        )
        harness.authorizeAndExecute()
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.validatePostReboot()
        runCurrent()

        val final = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, final.phase)
        assertEquals(OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER, final.completedIntent)
        assertEquals(
            OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest.mcubootImageHash,
            final.validation?.applicationHash,
        )
        assertTrue(
            requireNotNull(final.validation?.legacyStorageReclaimerStatus).reclaimSucceeded,
        )
        assertEquals(1, harness.legacyStorageReclaimerStatusProbe.inspectCount)
        assertEquals(0, harness.recoveryStatusProbe.inspectCount)

        harness.controller.beginNextTransition()
        harness.controller.refreshEnvironment(true)
        harness.controller.updateChecklist(readyChecklist())
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.runPreflight(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING)
        runCurrent()
        assertEquals(
            OmiCv1V3012ApplicationUpdateCatalog
                .legacyStorageReclaimer0002ToFunctionalRecording0007
                .releaseId,
            harness.controller.state.value.review?.releaseId,
        )
        assertEquals(2, harness.legacyStorageReclaimerStatusProbe.inspectCount)
    }

    @Test
    fun `dry run proves active v0006 to the exact bounded reclaimer`() = runTest {
        val harness = harness(
            preflight = functionalRecordingActiveUnobserved(),
            execution = listOf(
                functionalRecordingActiveUnobserved(),
                functionalRecording0006ToLegacyReclaimerStagedUnobserved(),
                functionalRecording0006ToLegacyReclaimerStagedUnobserved(confirmed = true),
            ),
            postReboot = legacyStorageReclaimerActiveUnobserved(),
            deviceManufacturer = OmiCv1V3012ApplicationUpdateCatalog.GUMI_MANUFACTURER,
            functionalStatuses = listOf(exactFunctionalRecoveryMaintenanceStatus()),
        )

        harness.driveToReadyToAuthorize(OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER)
        val review = requireNotNull(harness.controller.state.value.review)
        assertEquals(
            OmiCv1V3012ApplicationUpdateCatalog
                .functionalRecording0006ToLegacyStorageReclaimer0002
                .releaseId,
            review.releaseId,
        )
        assertEquals(
            OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest.fileSha256.hex,
            review.targetFileSha256,
        )
        assertEquals(1, harness.functionalStatusProbe.inspectCount)

        harness.authorizeAndExecute()
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.validatePostReboot()
        runCurrent()

        val final = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, final.phase)
        assertEquals(OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER, final.completedIntent)
        assertTrue(
            requireNotNull(final.validation?.legacyStorageReclaimerStatus).reclaimSucceeded,
        )
        assertEquals(1, harness.legacyStorageReclaimerStatusProbe.inspectCount)
    }

    @Test
    fun `refused reclaimer blocks functional v0007 but keeps exact recovery eligible`() = runTest {
        val refused = exactLegacyStorageReclaimerRefusedStatus()
        val functionalHarness = harness(
            preflight = legacyStorageReclaimerActiveUnobserved(),
            execution = emptyList(),
            postReboot = legacyStorageReclaimerActiveUnobserved(),
            deviceManufacturer = OmiCv1V3012ApplicationUpdateCatalog.GUMI_MANUFACTURER,
            legacyReclaimerEvidence = refused,
        )

        functionalHarness.controller.refreshEnvironment(true)
        functionalHarness.controller.updateChecklist(readyChecklist())
        functionalHarness.controller.startScan()
        runCurrent()
        functionalHarness.scanEvents.emit(
            OmiCv1FlashLabScanEvent.Candidate(functionalHarness.candidate),
        )
        runCurrent()
        functionalHarness.controller.select(functionalHarness.candidate)
        functionalHarness.controller.runPreflight(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING)
        runCurrent()

        assertEquals(
            OmiCv1FlashLabPhase.STOPPED_ON_FAILURE,
            functionalHarness.controller.state.value.phase,
        )
        assertTrue(
            functionalHarness.controller.state.value.error.orEmpty()
                .contains("LEGACY_STORAGE_RECLAIMER_EVIDENCE_REJECTED"),
        )

        val recoveryHarness = harness(
            preflight = legacyStorageReclaimerActiveUnobserved(),
            execution = emptyList(),
            postReboot = legacyStorageReclaimerActiveUnobserved(),
            deviceManufacturer = OmiCv1V3012ApplicationUpdateCatalog.GUMI_MANUFACTURER,
            legacyReclaimerEvidence = refused,
        )
        recoveryHarness.driveToReadyToAuthorize(OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY)
        assertEquals(
            OmiCv1V3012ApplicationUpdateCatalog
                .legacyStorageReclaimer0002ToRecoveryOnly0001
                .releaseId,
            recoveryHarness.controller.state.value.review?.releaseId,
        )
    }

    @Test
    fun `OTA-stranded reclaimer v0001 is recognized and fails closed before artifact loading`() =
        runTest {
            val harness = harness(
                preflight = legacyStorageReclaimer0001ActiveUnobserved(),
                execution = emptyList(),
                postReboot = legacyStorageReclaimer0001ActiveUnobserved(),
                deviceManufacturer = OmiCv1V3012ApplicationUpdateCatalog.GUMI_MANUFACTURER,
            )

            harness.controller.refreshEnvironment(true)
            harness.controller.updateChecklist(readyChecklist())
            harness.controller.startScan()
            runCurrent()
            harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
            runCurrent()
            harness.controller.select(harness.candidate)
            harness.controller.runPreflight(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING)
            runCurrent()

            val stopped = harness.controller.state.value
            assertEquals(OmiCv1FlashLabPhase.STOPPED_ON_FAILURE, stopped.phase)
            assertTrue(stopped.error.orEmpty().contains("OTA-stranded"))
            assertTrue(stopped.error.orEmpty().contains("recover with SWD"))
            assertEquals(0, harness.legacyStorageReclaimerStatusProbe.inspectCount)
        }

    @Test
    fun `dry run repairs exact fail-closed v0003 directly to v0006`() = runTest {
        val harness = harness(
            preflight = functionalRecording0003ActiveUnobserved(),
            execution = listOf(
                functionalRecording0003ActiveUnobserved(),
                functionalRecording0003RepairStagedUnobserved(),
                functionalRecording0003RepairStagedUnobserved(confirmed = true),
            ),
            postReboot = functionalRecordingActiveUnobserved(),
            deviceManufacturer = OmiCv1V3012ApplicationUpdateCatalog.GUMI_MANUFACTURER,
            functionalStatuses = listOf(
                exactFunctionalRecoveryMaintenanceStatus(),
                exactFunctionalReadyStatus(),
            ),
        )

        harness.driveToReadyToAuthorize(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING)
        assertEquals(
            OmiCv1V3012ApplicationUpdateCatalog
                .functionalRecording0003ToFunctionalRecording0006
                .releaseId,
            harness.controller.state.value.review?.releaseId,
        )
        assertEquals(1, harness.functionalStatusProbe.inspectCount)

        harness.authorizeAndExecute()
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.validatePostReboot()
        runCurrent()

        val final = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, final.phase)
        assertEquals(
            OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.mcubootImageHash,
            final.validation?.applicationHash,
        )
        assertTrue(requireNotNull(final.validation?.functionalStatus).recordingReady)
        assertEquals(2, harness.functionalStatusProbe.inspectCount)
    }

    @Test
    fun `dry run repairs exact fail-closed v0004 directly to v0006`() = runTest {
        val harness = harness(
            preflight = functionalRecording0004ActiveUnobserved(),
            execution = listOf(
                functionalRecording0004ActiveUnobserved(),
                functionalRecording0004RepairStagedUnobserved(),
                functionalRecording0004RepairStagedUnobserved(confirmed = true),
            ),
            postReboot = functionalRecordingActiveUnobserved(),
            deviceManufacturer = OmiCv1V3012ApplicationUpdateCatalog.GUMI_MANUFACTURER,
            functionalStatuses = listOf(
                exactFunctionalRecoveryMaintenanceStatus(),
                exactFunctionalReadyStatus(),
            ),
        )

        harness.driveToReadyToAuthorize(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING)
        assertEquals(
            OmiCv1V3012ApplicationUpdateCatalog
                .functionalRecording0004ToFunctionalRecording0006
                .releaseId,
            harness.controller.state.value.review?.releaseId,
        )
        assertEquals(1, harness.functionalStatusProbe.inspectCount)

        harness.authorizeAndExecute()
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.validatePostReboot()
        runCurrent()

        val final = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, final.phase)
        assertEquals(
            OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.mcubootImageHash,
            final.validation?.applicationHash,
        )
        assertTrue(requireNotNull(final.validation?.functionalStatus).recordingReady)
        assertEquals(2, harness.functionalStatusProbe.inspectCount)
    }

    @Test
    fun `dry run repairs exact lockup-prone v0005 directly to v0006`() = runTest {
        val harness = harness(
            preflight = functionalRecording0005ActiveUnobserved(),
            execution = listOf(
                functionalRecording0005ActiveUnobserved(),
                functionalRecording0005RepairStagedUnobserved(),
                functionalRecording0005RepairStagedUnobserved(confirmed = true),
            ),
            postReboot = functionalRecordingActiveUnobserved(),
            deviceManufacturer = OmiCv1V3012ApplicationUpdateCatalog.GUMI_MANUFACTURER,
            functionalStatuses = listOf(
                exactFunctionalRecoveryMaintenanceStatus(),
                exactFunctionalReadyStatus(),
            ),
        )

        harness.driveToReadyToAuthorize(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING)
        assertEquals(
            OmiCv1V3012ApplicationUpdateCatalog
                .functionalRecording0005ToFunctionalRecording0006
                .releaseId,
            harness.controller.state.value.review?.releaseId,
        )
        assertEquals(1, harness.functionalStatusProbe.inspectCount)

        harness.authorizeAndExecute()
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.validatePostReboot()
        runCurrent()

        val final = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, final.phase)
        assertEquals(
            OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.mcubootImageHash,
            final.validation?.applicationHash,
        )
        assertTrue(requireNotNull(final.validation?.functionalStatus).recordingReady)
        assertEquals(2, harness.functionalStatusProbe.inspectCount)
    }

    @Test
    fun `dry run proves recovery to status-only recording-root provisioner`() = runTest {
        val harness = harness(
            preflight = recoveryActiveUnobserved(),
            execution = listOf(
                recoveryActiveUnobserved(),
                recordingRootProvisionerStagedUnobserved(),
                recordingRootProvisionerStagedUnobserved(confirmed = true),
            ),
            postReboot = recordingRootProvisionerActiveUnobserved(),
        )

        harness.driveToReadyToAuthorize(
            OmiCv1ApplicationUpdateIntent.RECORDING_ROOT_PROVISIONER,
        )
        assertEquals(
            OmiCv1ApplicationUpdateIntent.RECORDING_ROOT_PROVISIONER,
            harness.controller.state.value.review?.intent,
        )
        harness.authorizeAndExecute()
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.validatePostReboot()
        runCurrent()

        val final = harness.controller.state.value
        val evidence = requireNotNull(final.validation?.recordingRootProvisionerStatus)
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, final.phase)
        assertEquals(
            OmiCv1ApplicationUpdateIntent.RECORDING_ROOT_PROVISIONER,
            final.completedIntent,
        )
        assertEquals(
            OmiCv1RecordingRootProvisioner0001ApplicationArtifact.manifest.mcubootImageHash,
            requireNotNull(final.validation).applicationHash,
        )
        assertTrue(evidence.provisioningComplete)
        assertEquals(1, harness.recordingRootProvisionerStatusProbe.inspectCount)
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
            postReboot = recoveryActiveUnobserved(),
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
    fun `post reboot endpoint change is bound only after exact target and recovery proof`() = runTest {
        val reboundEndpoint = EndpointCandidate(TransportKind.BLE, "post-reboot-endpoint")
        val harness = harness(
            preflight = stockUnobserved(),
            execution = listOf(
                stockUnobserved(),
                recoveryStagedUnobserved(),
                recoveryStagedUnobserved(confirmed = true),
            ),
            postReboot = recoveryActiveUnobserved(reboundEndpoint),
        )
        harness.driveToReadyToAuthorize()
        harness.authorizeAndExecute()
        val other = harness.candidate.copy(
            endpoint = reboundEndpoint,
        )

        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(other))
        runCurrent()
        harness.controller.select(other)
        runCurrent()

        val selected = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.POST_REBOOT_DEVICE_SELECTED, selected.phase)
        assertEquals(reboundEndpoint, selected.selected?.endpoint)
        assertEquals(ownedEndpoint, selected.pendingValidation?.endpoint)
        assertEquals(0, harness.postRebootSession.inspectCount)

        harness.controller.validatePostReboot()
        runCurrent()

        val validated = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, validated.phase)
        assertEquals(reboundEndpoint, validated.pendingValidation?.endpoint)
        assertEquals(reboundEndpoint, harness.recoveryStatusProbe.inspectedEndpoints.single())
    }

    @Test
    fun `post reboot endpoint change is not bound when target proof fails`() = runTest {
        val reboundEndpoint = EndpointCandidate(TransportKind.BLE, "untrusted-post-reboot-endpoint")
        val harness = harness(
            preflight = stockUnobserved(),
            execution = listOf(
                stockUnobserved(),
                recoveryStagedUnobserved(),
                recoveryStagedUnobserved(confirmed = true),
            ),
            postReboot = stockUnobserved(reboundEndpoint),
        )
        harness.driveToReadyToAuthorize()
        harness.authorizeAndExecute()
        val other = harness.candidate.copy(endpoint = reboundEndpoint)

        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(other))
        runCurrent()
        harness.controller.select(other)
        runCurrent()
        harness.controller.validatePostReboot()
        runCurrent()

        val state = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.STOPPED_ON_FAILURE, state.phase)
        assertEquals(ownedEndpoint, state.pendingValidation?.endpoint)
        assertTrue(state.error.orEmpty().contains("PRECONDITION_FAILED"))
        assertEquals(0, harness.recoveryStatusProbe.inspectCount)
    }

    @Test
    fun `post reboot endpoint change is not bound when recovery proof fails`() = runTest {
        val reboundEndpoint = EndpointCandidate(TransportKind.BLE, "untrusted-recovery-endpoint")
        val harness = harness(
            preflight = stockUnobserved(),
            execution = listOf(
                stockUnobserved(),
                recoveryStagedUnobserved(),
                recoveryStagedUnobserved(confirmed = true),
            ),
            postReboot = recoveryActiveUnobserved(reboundEndpoint),
            recoveryStatusFailure = OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.RECOVERY_EVIDENCE_REJECTED,
                "Recovery topology mismatch",
            ),
        )
        harness.driveToReadyToAuthorize()
        harness.authorizeAndExecute()
        val other = harness.candidate.copy(endpoint = reboundEndpoint)

        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(other))
        runCurrent()
        harness.controller.select(other)
        runCurrent()
        harness.controller.validatePostReboot()
        runCurrent()

        val state = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.STOPPED_ON_FAILURE, state.phase)
        assertEquals(ownedEndpoint, state.pendingValidation?.endpoint)
        assertTrue(state.error.orEmpty().contains("RECOVERY_EVIDENCE_REJECTED"))
        assertEquals(1, harness.recoveryStatusProbe.inspectCount)
    }

    @Test
    fun `capture self-test requires three consecutive safe hardware results`() = runTest {
        val harness = harness(
            preflight = recoveryActiveUnobserved(),
            execution = listOf(
                recoveryActiveUnobserved(),
                captureSelftestStagedUnobserved(),
                captureSelftestStagedUnobserved(confirmed = true),
            ),
            postReboot = captureSelftestActiveUnobserved(),
            captureResults = listOf(
                exactSelftestPass(1),
                exactSelftestPass(2),
                exactSelftestPass(3),
            ),
        )

        harness.driveToReadyToAuthorize(OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST)
        assertEquals(
            OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST,
            harness.controller.state.value.review?.intent,
        )
        harness.authorizeAndExecute()
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.validatePostReboot()
        runCurrent()

        assertEquals(OmiCv1FlashLabPhase.VALIDATED, harness.controller.state.value.phase)
        assertEquals(
            OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST,
            harness.controller.state.value.completedIntent,
        )
        assertEquals(OmiCv1CaptureSelftestPhase.IDLE, harness.controller.state.value.validation?.captureSelftestStatus?.phase)

        repeat(3) { index ->
            harness.controller.runCaptureSelftest()
            runCurrent()
            assertEquals(OmiCv1FlashLabPhase.VALIDATED, harness.controller.state.value.phase)
            assertEquals(index + 1, harness.controller.state.value.captureSelftestConsecutivePasses)
        }
        assertEquals(3, harness.controller.state.value.captureSelftestRuns)
        assertEquals(3, harness.captureSelftest.runCount)
        assertEquals(1, harness.captureSelftest.inspectCount)
    }

    @Test
    fun `read-only resume re-proves exact active self-test without an update`() = runTest {
        val harness = harness(
            preflight = captureSelftestActiveUnobserved(),
            execution = emptyList(),
            postReboot = captureSelftestActiveUnobserved(),
            captureBaseline = exactSelftestPass(1),
            captureResults = listOf(exactSelftestPass(2)),
            phonePower = OmiCv1FlashLabPhonePower(percent = 59, charging = true),
        )

        harness.controller.refreshEnvironment(true)
        harness.controller.updateChecklist(readyChecklist())
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.resumeActiveCaptureSelftest()
        runCurrent()

        val resumed = harness.controller.state.value
        assertFalse(resumed.readyForPreflight)
        assertTrue(resumed.readyForReadOnlyCapture)
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, resumed.phase)
        assertEquals(
            OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST,
            resumed.completedIntent,
        )
        assertEquals(
            OmiCv1CapturePortSelftest0001ApplicationArtifact.manifest.mcubootImageHash,
            resumed.validation?.applicationHash,
        )
        assertEquals(OmiCv1CaptureSelftestPhase.PASSED, resumed.validation?.captureSelftestStatus?.phase)
        assertEquals(1, resumed.captureSelftestConsecutivePasses)
        assertEquals(1, resumed.captureSelftestRuns)
        assertEquals(0, harness.executionSession.uploadCount)
        assertEquals(1, harness.captureSelftest.inspectCount)

        harness.controller.runCaptureSelftest()
        runCurrent()

        assertEquals(2, harness.controller.state.value.captureSelftestConsecutivePasses)
    }

    @Test
    fun `read-only resume rejects any active image other than exact self-test`() = runTest {
        val harness = harness(
            preflight = recoveryActiveUnobserved(),
            execution = emptyList(),
            postReboot = recoveryActiveUnobserved(),
        )

        harness.controller.refreshEnvironment(true)
        harness.controller.updateChecklist(readyChecklist())
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.resumeActiveCaptureSelftest()
        runCurrent()

        val rejected = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.STOPPED_ON_FAILURE, rejected.phase)
        assertTrue(rejected.error.orEmpty().contains("PRECONDITION_FAILED"))
        assertEquals(0, harness.captureSelftest.inspectCount)
        assertEquals(0, harness.executionSession.uploadCount)
    }

    @Test
    fun `read-only functional resume re-proves exact active v0006 without an update`() = runTest {
        val harness = harness(
            preflight = functionalRecordingActiveUnobserved(),
            execution = emptyList(),
            postReboot = functionalRecordingActiveUnobserved(),
            deviceManufacturer = OmiCv1V3012ApplicationUpdateCatalog.GUMI_MANUFACTURER,
            phonePower = OmiCv1FlashLabPhonePower(percent = 59, charging = true),
        )

        harness.controller.refreshEnvironment(true)
        harness.controller.updateChecklist(readyChecklist())
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.resumeActiveFunctional()
        runCurrent()

        val resumed = harness.controller.state.value
        assertFalse(resumed.readyForPreflight)
        assertTrue(resumed.readyForReadOnlyCapture)
        assertEquals(OmiCv1FlashLabPhase.VALIDATED, resumed.phase)
        assertEquals(
            OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING,
            resumed.completedIntent,
        )
        assertEquals(
            OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.mcubootImageHash,
            resumed.validation?.applicationHash,
        )
        assertTrue(requireNotNull(resumed.validation?.functionalStatus).recordingReady)
        assertEquals(0, harness.executionSession.uploadCount)
        assertEquals(1, harness.functionalStatusProbe.inspectCount)
    }

    @Test
    fun `read-only functional resume rejects any other active image`() = runTest {
        val harness = harness(
            preflight = recoveryActiveUnobserved(),
            execution = emptyList(),
            postReboot = recoveryActiveUnobserved(),
        )

        harness.controller.refreshEnvironment(true)
        harness.controller.updateChecklist(readyChecklist())
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.resumeActiveFunctional()
        runCurrent()

        val rejected = harness.controller.state.value
        assertEquals(OmiCv1FlashLabPhase.STOPPED_ON_FAILURE, rejected.phase)
        assertTrue(rejected.error.orEmpty().contains("PRECONDITION_FAILED"))
        assertEquals(0, harness.functionalStatusProbe.inspectCount)
        assertEquals(0, harness.executionSession.uploadCount)
    }

    @Test
    fun `safe capture failure resets the consecutive pass run`() = runTest {
        val harness = harness(
            preflight = recoveryActiveUnobserved(),
            execution = listOf(
                recoveryActiveUnobserved(),
                captureSelftestStagedUnobserved(),
                captureSelftestStagedUnobserved(confirmed = true),
            ),
            postReboot = captureSelftestActiveUnobserved(),
            captureResults = listOf(
                exactSelftestPass(1),
                exactSelftestSafeFailure(2),
                exactSelftestPass(3),
            ),
        )

        harness.driveToReadyToAuthorize(OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST)
        harness.authorizeAndExecute()
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.validatePostReboot()
        runCurrent()

        harness.controller.runCaptureSelftest()
        runCurrent()
        assertEquals(1, harness.controller.state.value.captureSelftestConsecutivePasses)
        harness.controller.runCaptureSelftest()
        runCurrent()
        assertEquals(0, harness.controller.state.value.captureSelftestConsecutivePasses)
        assertTrue(harness.controller.state.value.error.orEmpty().contains("failed safely"))
        harness.controller.runCaptureSelftest()
        runCurrent()
        assertEquals(1, harness.controller.state.value.captureSelftestConsecutivePasses)
        assertEquals(3, harness.controller.state.value.captureSelftestRuns)
    }

    @Test
    fun `expired physical confirmation preserves completed capture passes`() = runTest {
        val harness = harness(
            preflight = recoveryActiveUnobserved(),
            execution = listOf(
                recoveryActiveUnobserved(),
                captureSelftestStagedUnobserved(),
                captureSelftestStagedUnobserved(confirmed = true),
            ),
            postReboot = captureSelftestActiveUnobserved(),
            captureResults = listOf(
                exactSelftestPass(1),
                exactSelftestConfirmationExpired(2),
                exactSelftestPass(3),
            ),
        )

        harness.driveToReadyToAuthorize(OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST)
        harness.authorizeAndExecute()
        harness.controller.startScan()
        runCurrent()
        harness.scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(harness.candidate))
        runCurrent()
        harness.controller.select(harness.candidate)
        harness.controller.validatePostReboot()
        runCurrent()

        harness.controller.runCaptureSelftest()
        runCurrent()
        assertEquals(1, harness.controller.state.value.captureSelftestConsecutivePasses)
        assertEquals(1, harness.controller.state.value.captureSelftestRuns)

        harness.controller.runCaptureSelftest()
        runCurrent()
        assertEquals(1, harness.controller.state.value.captureSelftestConsecutivePasses)
        assertEquals(1, harness.controller.state.value.captureSelftestRuns)
        assertTrue(harness.controller.state.value.error.orEmpty().contains("before capture began"))

        harness.controller.runCaptureSelftest()
        runCurrent()
        assertEquals(2, harness.controller.state.value.captureSelftestConsecutivePasses)
        assertEquals(2, harness.controller.state.value.captureSelftestRuns)
    }

    private inner class Harness(
        private val testScope: TestScope,
        val controller: OmiCv1FlashLabController,
        val scanEvents: MutableSharedFlow<OmiCv1FlashLabScanEvent>,
        val candidate: OmiCv1FlashLabCandidate,
        val executionSession: FakeSession,
        val postRebootSession: FakeSession,
        val recoveryStatusProbe: FakeRecoveryStatusProbe,
        val recordingRootProvisionerStatusProbe: FakeRecordingRootProvisionerStatusProbe,
        val legacyStorageReclaimerStatusProbe: FakeLegacyStorageReclaimerStatusProbe,
        val functionalStatusProbe: FakeFunctionalStatusProbe,
        val captureSelftest: FakeCaptureSelftest,
        val clock: FakeClock,
    ) {
        suspend fun driveToReadyToAuthorize(
            intent: OmiCv1ApplicationUpdateIntent? = null,
        ) {
            controller.refreshEnvironment(true)
            controller.updateChecklist(readyChecklist())
            controller.startScan()
            testScope.runCurrent()
            scanEvents.emit(OmiCv1FlashLabScanEvent.Candidate(candidate))
            testScope.runCurrent()
            controller.select(candidate)
            testScope.runCurrent()
            controller.runPreflight(intent)
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
        recoveryStatusFailure: Throwable? = null,
        captureBaseline: OmiCv1CaptureSelftestEvidence = exactSelftestIdle(),
        captureResults: List<OmiCv1CaptureSelftestEvidence> = emptyList(),
        deviceBatteryPercent: Int = 100,
        deviceManufacturer: String = OmiCv1V3012ApplicationUpdateCatalog.STOCK_MANUFACTURER,
        deviceFirmwareRevisions: List<String> = emptyList(),
        normalizationSession: OmiCv1StockNormalizationSession? = null,
        stockNormalizationArtifacts: OmiCv1StockNormalizationArtifacts? = null,
        legacyReclaimerEvidence: OmiCv1LegacyStorageReclaimerStatusEvidence =
            exactLegacyStorageReclaimerStatus(),
        functionalStatuses: List<OmiCv1FunctionalStatusEvidence> = listOf(
            exactFunctionalReadyStatus(),
            exactFunctionalRecoveryMaintenanceStatus(),
            exactFunctionalRecoveryMaintenanceStatus(),
        ),
        phonePower: OmiCv1FlashLabPhonePower =
            OmiCv1FlashLabPhonePower(percent = 100, charging = true),
    ): Harness {
        val scanEvents = MutableSharedFlow<OmiCv1FlashLabScanEvent>(extraBufferCapacity = 4)
        val candidate = candidate()
        val preflightSession = FakeSession(listOf(preflight))
        val executionSession = FakeSession(execution)
        val postRebootSession = FakeSession(listOf(postReboot))
        val firstRecheckSession = FakeSession(listOf(postReboot))
        val secondRecheckSession = FakeSession(listOf(postReboot))
        val sessions = ArrayDeque(
            listOf(
                preflightSession,
                executionSession,
                postRebootSession,
                firstRecheckSession,
                secondRecheckSession,
            ),
        )
        val recoveryStatusProbe = FakeRecoveryStatusProbe(
            exactRecoveryStatus(),
            postReboot.endpoint,
            recoveryStatusFailure,
        )
        val functionalStatusProbe = FakeFunctionalStatusProbe(
            functionalStatuses,
            postReboot.endpoint,
        )
        val recordingRootProvisionerStatusProbe = FakeRecordingRootProvisionerStatusProbe(
            exactRecordingRootProvisionerStatus(),
            postReboot.endpoint,
        )
        val legacyStorageReclaimerStatusProbe = FakeLegacyStorageReclaimerStatusProbe(
            legacyReclaimerEvidence,
            postReboot.endpoint,
        )
        val clock = FakeClock(100)
        val captureSelftest = FakeCaptureSelftest(
            baseline = captureBaseline,
            results = captureResults,
            expectedEndpoint = postReboot.endpoint,
        )
        val firmwareRevisions = ArrayDeque(
            deviceFirmwareRevisions.ifEmpty { List(8) { "3.0.12" } },
        )
        val controller = OmiCv1FlashLabController(
            scanner = OmiCv1FlashLabScanner { scanEvents },
            sessions = OmiCv1ApplicationImage0UpdateSessionFactory {
                sessions.removeFirst()
            },
            devicePreflight = OmiCv1FlashLabDevicePreflightProbe {
                exactDevicePreflight(
                    it,
                    deviceBatteryPercent,
                    deviceManufacturer,
                    firmwareRevisions.removeFirstOrNull() ?: "3.0.12",
                )
            },
            normalizationSessions = OmiCv1StockNormalizationSessionFactory {
                normalizationSession
                    ?: error("Stock normalization is outside this application-image-0 harness")
            },
            normalizationArtifacts = OmiCv1StockNormalizationArtifactSource {
                stockNormalizationArtifacts
                    ?: error("Stock normalization is outside this application-image-0 harness")
            },
            recoveryStatus = recoveryStatusProbe,
            recordingRootProvisionerStatus = recordingRootProvisionerStatusProbe,
            legacyStorageReclaimerStatus = legacyStorageReclaimerStatusProbe,
            functionalStatus = functionalStatusProbe,
            captureSelftest = captureSelftest,
            captureSelftestRunner = captureSelftest,
            artifacts = OmiCv1FlashLabArtifactSource { target -> exactArtifact(target) },
            phonePower = OmiCv1FlashLabPhonePowerSource { phonePower },
            clock = clock,
            scope = backgroundScope,
        )
        return Harness(
            this,
            controller,
            scanEvents,
            candidate,
            executionSession,
            postRebootSession,
            recoveryStatusProbe,
            recordingRootProvisionerStatusProbe,
            legacyStorageReclaimerStatusProbe,
            functionalStatusProbe,
            captureSelftest,
            clock,
        )
    }

    private class FakeClock(private var value: Long) : MonotonicMillisClock {
        override fun now(): Long = value

        fun advanceBy(millis: Long) {
            require(millis >= 0)
            value += millis
        }
    }

    private fun readyChecklist() = OmiCv1FlashLabChecklist(
        officialOmiAppStopped = true,
        chargerConnected = true,
        noRollbackRiskAccepted = true,
    )

    private fun candidate() = OmiCv1FlashLabCandidate(
        endpoint = ownedEndpoint,
        advertisedName = "Omi",
        rssi = -42,
        connectable = true,
    )

    private fun exactDevicePreflight(
        endpoint: EndpointCandidate,
        batteryPercent: Int,
        manufacturer: String,
        firmwareRevision: String,
    ) = OmiCv1FlashLabDevicePreflightEvidence(
        endpoint = endpoint,
        identity = OmiCv1GattEvidence(
            manufacturer = manufacturer,
            modelNumber = "Omi CV 1",
            firmwareRevision = firmwareRevision,
            hardwareRevision = when (firmwareRevision) {
                "3.0.12" -> "5.0"
                else -> "Based Hardware Omi"
            },
            softwareRevision = null,
            batteryPercent = batteryPercent,
            storage = null,
            storageRawHex = null,
            recoveryStatusRawHex = null,
            successfulReadLengths = emptyMap(),
            readFailures = emptyList(),
        ),
        serviceCount = 8,
        characteristicCount = 16,
    )

    private fun stockUnobserved(endpoint: EndpointCandidate = ownedEndpoint) = inspection(
        endpoint,
        activeSlot(
            0,
            OmiCv1V3012ApplicationUpdateCatalog.stockToRecoveryOnly0001.source.applicationHash,
        ),
    )

    private fun stockV3007Unobserved(endpoint: EndpointCandidate = ownedEndpoint) = inspection(
        endpoint,
        activeSlot(
            0,
            FirmwareImageHash(OmiCv1StockV3007FirmwareIdentity.APPLICATION_IMAGE_HASH),
        ),
    )

    private fun recoveryStagedUnobserved(confirmed: Boolean = false) =
        networkUnobservedStagedInspection(
            ownedEndpoint,
            OmiCv1V3012ApplicationUpdateCatalog.stockToRecoveryOnly0001,
            confirmed,
        )

    private fun recoveryActiveUnobserved(endpoint: EndpointCandidate = ownedEndpoint) = inspection(
        endpoint,
        activeSlot(0, OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.mcubootImageHash),
    )

    private fun stockStagedUnobserved(confirmed: Boolean = false) =
        networkUnobservedStagedInspection(
            ownedEndpoint,
            OmiCv1V3012ApplicationUpdateCatalog.recoveryOnly0001ToStock,
            confirmed,
        )

    private fun captureSelftestStagedUnobserved(confirmed: Boolean = false) =
        networkUnobservedStagedInspection(
            ownedEndpoint,
            OmiCv1V3012ApplicationUpdateCatalog.recoveryOnly0001ToCapturePortSelftest0001,
            confirmed,
        )

    private fun captureSelftestActiveUnobserved(endpoint: EndpointCandidate = ownedEndpoint) =
        inspection(
            endpoint,
            activeSlot(
                0,
                OmiCv1CapturePortSelftest0001ApplicationArtifact.manifest.mcubootImageHash,
            ),
        )

    private fun functionalRecordingStagedUnobserved(confirmed: Boolean = false) =
        networkUnobservedStagedInspection(
            ownedEndpoint,
            OmiCv1V3012ApplicationUpdateCatalog
                .recordingRootProvisioner0001ToFunctionalRecording0006,
            confirmed,
        )

    private fun functionalRecording0003RepairStagedUnobserved(confirmed: Boolean = false) =
        networkUnobservedStagedInspection(
            ownedEndpoint,
            OmiCv1V3012ApplicationUpdateCatalog
                .functionalRecording0003ToFunctionalRecording0006,
            confirmed,
        )

    private fun functionalRecording0004RepairStagedUnobserved(confirmed: Boolean = false) =
        networkUnobservedStagedInspection(
            ownedEndpoint,
            OmiCv1V3012ApplicationUpdateCatalog
                .functionalRecording0004ToFunctionalRecording0006,
            confirmed,
        )

    private fun functionalRecording0005RepairStagedUnobserved(confirmed: Boolean = false) =
        networkUnobservedStagedInspection(
            ownedEndpoint,
            OmiCv1V3012ApplicationUpdateCatalog
                .functionalRecording0005ToFunctionalRecording0006,
            confirmed,
        )

    private fun recordingRootProvisionerStagedUnobserved(confirmed: Boolean = false) =
        networkUnobservedStagedInspection(
            ownedEndpoint,
            OmiCv1V3012ApplicationUpdateCatalog
                .recoveryOnly0001ToRecordingRootProvisioner0001,
            confirmed,
        )

    private fun legacyStorageReclaimerStagedUnobserved(confirmed: Boolean = false) =
        networkUnobservedStagedInspection(
            ownedEndpoint,
            OmiCv1V3012ApplicationUpdateCatalog
                .recordingRootProvisioner0001ToLegacyStorageReclaimer0002,
            confirmed,
        )

    private fun functionalRecording0006ToLegacyReclaimerStagedUnobserved(
        confirmed: Boolean = false,
    ) = networkUnobservedStagedInspection(
        ownedEndpoint,
        OmiCv1V3012ApplicationUpdateCatalog
            .functionalRecording0006ToLegacyStorageReclaimer0002,
        confirmed,
    )

    private fun legacyStorageReclaimerActiveUnobserved(
        endpoint: EndpointCandidate = ownedEndpoint,
    ) = inspection(
        endpoint,
        activeSlot(
            0,
            OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest.mcubootImageHash,
        ),
    )

    private fun legacyStorageReclaimer0001ActiveUnobserved(
        endpoint: EndpointCandidate = ownedEndpoint,
    ) = inspection(
        endpoint,
        activeSlot(
            0,
            OmiCv1LegacyStorageReclaimer0001ApplicationArtifact.manifest.mcubootImageHash,
        ),
    )

    private fun recordingRootProvisionerActiveUnobserved(
        endpoint: EndpointCandidate = ownedEndpoint,
    ) = inspection(
        endpoint,
        activeSlot(
            0,
            OmiCv1RecordingRootProvisioner0001ApplicationArtifact.manifest.mcubootImageHash,
        ),
    )

    private fun functionalRecordingActiveUnobserved(endpoint: EndpointCandidate = ownedEndpoint) =
        inspection(
            endpoint,
            activeSlot(
                0,
                OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.mcubootImageHash,
            ),
        )

    private fun functionalRecording0003ActiveUnobserved(
        endpoint: EndpointCandidate = ownedEndpoint,
    ) = inspection(
        endpoint,
        activeSlot(
            0,
            OmiCv1FunctionalRecording0003ApplicationArtifact.manifest.mcubootImageHash,
            ),
        )

    private fun functionalRecording0004ActiveUnobserved(
        endpoint: EndpointCandidate = ownedEndpoint,
    ) = inspection(
        endpoint,
        activeSlot(
            0,
            OmiCv1FunctionalRecording0004ApplicationArtifact.manifest.mcubootImageHash,
        ),
    )

    private fun functionalRecording0005ActiveUnobserved(
        endpoint: EndpointCandidate = ownedEndpoint,
    ) = inspection(
        endpoint,
        activeSlot(
            0,
            OmiCv1FunctionalRecording0005ApplicationArtifact.manifest.mcubootImageHash,
        ),
    )

    private fun exactArtifact(target: OmiCv1ApplicationArtifactManifest): ByteArray {
        val root = repositoryRoot()
        val relative = when (target.identity) {
            OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.identity ->
                "local/firmware/omi-cv1/recovery-only-0001/omi.signed.bin"

            OmiCv1StockV3012ApplicationArtifact.manifest.identity ->
                "local/firmware/omi-cv1/stock-v3.0.12/omi.signed.bin"

            OmiCv1CapturePortSelftest0001ApplicationArtifact.manifest.identity ->
                "local/firmware/omi-cv1/capture-port-selftest-0001/omi.signed.bin"

            OmiCv1RecordingRootProvisioner0001ApplicationArtifact.manifest.identity ->
                "local/firmware/omi-cv1/recording-root-provisioner-0001/omi.signed.bin"

            OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest.identity ->
                "local/firmware/omi-cv1/legacy-storage-reclaimer-0002/omi.signed.bin"

            OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.identity ->
                "local/firmware/omi-cv1/functional-recording-0006/omi.signed.bin"

            OmiCv1FunctionalRecording0007ApplicationArtifact.manifest.identity ->
                "local/firmware/omi-cv1/functional-recording-0007/omi.signed.bin"

            else -> error("No test artifact for ${target.identity}")
        }
        return File(root, relative).readBytes()
    }

    private fun exactStockNormalizationArtifacts(): OmiCv1StockNormalizationArtifacts {
        val root = repositoryRoot()
        val application = File(
            root,
            "local/firmware/omi-cv1/stock-v3.0.12/omi.signed.bin",
        ).readBytes()
        val archive = File(
            root,
            "local/firmware/omi-cv1/stock-v3.0.12/Omi_CV1_OTA_v3.0.12.zip",
        )
        val network = ZipFile(archive).use { zip ->
            zip.getInputStream(requireNotNull(zip.getEntry("ipc_radio.bin"))).use { it.readBytes() }
        }
        return OmiCv1StockNormalizationArtifacts(application, network)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(workingDirectory)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Cannot locate Gumi repository root")
    }

    private fun exactRecoveryStatus() = OmiCv1RecoveryStatusProtocol.validate(
        statusBytes = byteArrayOf(0x01, 0x07, 0x01, 0x23),
        discoveredServiceUuids = setOf(
            OmiCv1RecoveryStatusProtocol.RECOVERY_SERVICE_UUID,
            OmiCv1RecoveryStatusProtocol.STOCK_IDENTITY_SERVICE_UUID,
        ),
        stockIdentityServiceHasCharacteristics = false,
    )

    private fun exactRecordingRootProvisionerStatus() =
        OmiCv1RecordingRootProvisionerStatusProtocol.validate(
            statusBytes = byteArrayOf(
                0x01, 0x03, 0x3f, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x05, 0x00, 0x00, 0x00,
            ),
            discoveredServiceUuids = setOf(
                OmiCv1RecordingRootProvisionerStatusProtocol.SERVICE_UUID,
                OmiCv1RecoveryStatusProtocol.RECOVERY_SERVICE_UUID,
                OmiCv1RecordingRootProvisionerStatusProtocol.FAMILY_IDENTITY_SERVICE_UUID,
            ),
            provisionerCharacteristicUuids = setOf(
                OmiCv1RecordingRootProvisionerStatusProtocol.STATUS_CHARACTERISTIC_UUID,
            ),
            familyIdentityServiceHasCharacteristics = false,
        )

    private fun exactLegacyStorageReclaimerStatus(): OmiCv1LegacyStorageReclaimerStatusEvidence {
        val bytes = ByteArray(OmiCv1LegacyStorageReclaimerStatusProtocol.STATUS_WIRE_SIZE)
        bytes[0] = 1
        bytes[1] = OmiCv1LegacyStorageReclaimerPhase.RECLAIMED.wireValue.toByte()
        bytes[2] = 0xff.toByte()
        bytes[3] = 0x01
        bytes.putU32(8, 5)
        bytes.putU64(
            12,
            OmiCv1LegacyStorageReclaimerStatusProtocol.EXACT_TARGET_SIZE_BYTES,
        )
        bytes.putU64(28, 500L * 1024L * 1024L)
        return OmiCv1LegacyStorageReclaimerStatusProtocol.validate(
            statusBytes = bytes,
            discoveredServiceUuids = setOf(
                OmiCv1LegacyStorageReclaimerStatusProtocol.SERVICE_UUID,
                OmiCv1RecoveryStatusProtocol.RECOVERY_SERVICE_UUID,
                OmiCv1LegacyStorageReclaimerStatusProtocol.FAMILY_IDENTITY_SERVICE_UUID,
            ),
            reclaimerCharacteristicUuids = setOf(
                OmiCv1LegacyStorageReclaimerStatusProtocol.STATUS_CHARACTERISTIC_UUID,
            ),
            familyIdentityServiceHasCharacteristics = false,
        )
    }

    private fun exactLegacyStorageReclaimerRefusedStatus():
        OmiCv1LegacyStorageReclaimerStatusEvidence {
        val bytes = ByteArray(OmiCv1LegacyStorageReclaimerStatusProtocol.STATUS_WIRE_SIZE)
        bytes[0] = 1
        bytes[1] = OmiCv1LegacyStorageReclaimerPhase.REFUSED.wireValue.toByte()
        bytes[2] = 0x0f
        bytes[3] = 0x01
        bytes.putU32(4, -1)
        bytes.putU32(8, 5)
        bytes.putU64(12, 123)
        return OmiCv1LegacyStorageReclaimerStatusProtocol.validate(
            statusBytes = bytes,
            discoveredServiceUuids = setOf(
                OmiCv1LegacyStorageReclaimerStatusProtocol.SERVICE_UUID,
                OmiCv1RecoveryStatusProtocol.RECOVERY_SERVICE_UUID,
                OmiCv1LegacyStorageReclaimerStatusProtocol.FAMILY_IDENTITY_SERVICE_UUID,
            ),
            reclaimerCharacteristicUuids = setOf(
                OmiCv1LegacyStorageReclaimerStatusProtocol.STATUS_CHARACTERISTIC_UUID,
            ),
            familyIdentityServiceHasCharacteristics = false,
        )
    }

    private fun exactFunctionalReadyStatus(): OmiCv1FunctionalStatusEvidence {
        val bytes = ByteArray(OmiCv1FunctionalStatusProtocol.STATUS_WIRE_SIZE)
        bytes[0] = 1
        bytes[1] = OmiCv1FunctionalCapturePhase.IDLE.wireValue.toByte()
        bytes[2] = OmiCv1FunctionalMicrophoneTruth.VERIFIED_OFF.wireValue.toByte()
        bytes[3] = OmiCv1FunctionalStorageState.HEALTHY.wireValue.toByte()
        bytes[4] = OmiCv1FunctionalKeyTruth.READY.wireValue.toByte()
        bytes[5] = OmiCv1FunctionalRecordingStorageTruth.READY.wireValue.toByte()
        bytes[6] = OmiCv1FunctionalCodecTruth.CLOSED.wireValue.toByte()
        bytes[7] = 0x61
        bytes.putU64(16, 8L * 1024L * 1024L)
        bytes.putU32(28, 1)
        return OmiCv1FunctionalStatusProtocol.validate(
            statusBytes = bytes,
            capabilitiesBytes = OmiCv1FunctionalStatusProtocol.EXPECTED_CAPABILITIES,
            discoveredServiceUuids = setOf(
                OmiCv1FunctionalStatusProtocol.SERVICE_UUID,
                OmiCv1FunctionalStatusProtocol.FAMILY_IDENTITY_SERVICE_UUID,
            ),
            familyIdentityServiceHasCharacteristics = false,
        )
    }

    private fun exactFunctionalRecoveryMaintenanceStatus(): OmiCv1FunctionalStatusEvidence {
        val bytes = ByteArray(OmiCv1FunctionalStatusProtocol.STATUS_WIRE_SIZE)
        bytes[0] = 1
        bytes[1] = OmiCv1FunctionalCapturePhase.IDLE.wireValue.toByte()
        bytes[2] = OmiCv1FunctionalMicrophoneTruth.VERIFIED_OFF.wireValue.toByte()
        bytes[3] = OmiCv1FunctionalStorageState.HEALTHY.wireValue.toByte()
        bytes[4] = OmiCv1FunctionalKeyTruth.READY.wireValue.toByte()
        bytes[5] = OmiCv1FunctionalRecordingStorageTruth.READY.wireValue.toByte()
        bytes[6] = OmiCv1FunctionalCodecTruth.CLOSED.wireValue.toByte()
        bytes[7] = 0x71
        bytes.putU64(16, 8L * 1024L * 1024L)
        bytes.putU32(28, 2)
        return OmiCv1FunctionalStatusProtocol.validate(
            statusBytes = bytes,
            capabilitiesBytes = OmiCv1FunctionalStatusProtocol.EXPECTED_CAPABILITIES,
            discoveredServiceUuids = setOf(
                OmiCv1FunctionalStatusProtocol.SERVICE_UUID,
                OmiCv1FunctionalStatusProtocol.FAMILY_IDENTITY_SERVICE_UUID,
            ),
            familyIdentityServiceHasCharacteristics = false,
        )
    }

    private fun exactSelftestIdle() = exactSelftestStatus(
        phase = OmiCv1CaptureSelftestPhase.IDLE,
        flags = 0x82,
        attempt = 0,
    )

    private fun exactSelftestPass(attempt: Int) = exactSelftestStatus(
        phase = OmiCv1CaptureSelftestPhase.PASSED,
        flags = 0xa2,
        attempt = attempt,
        pcmBlocks = 101,
        pcmSamples = 32_000,
        opusPackets = 100,
    )

    private fun exactSelftestSafeFailure(attempt: Int) = exactSelftestStatus(
        phase = OmiCv1CaptureSelftestPhase.FAILED_SAFE,
        failure = OmiCv1CaptureSelftestFailure.INSUFFICIENT_PCM,
        flags = 0x82,
        attempt = attempt,
    )

    private fun exactSelftestConfirmationExpired(attempt: Int) = exactSelftestStatus(
        phase = OmiCv1CaptureSelftestPhase.FAILED_SAFE,
        failure = OmiCv1CaptureSelftestFailure.CONFIRMATION_EXPIRED,
        flags = 0x82,
        attempt = attempt,
    )

    private fun exactSelftestArmed(attempt: Long) = exactSelftestStatus(
        phase = OmiCv1CaptureSelftestPhase.ARMED,
        flags = 0x8a,
        attempt = attempt.toInt(),
        leaseRemainingMillis = 15_000,
    )

    private fun exactSelftestStatus(
        phase: OmiCv1CaptureSelftestPhase,
        failure: OmiCv1CaptureSelftestFailure = OmiCv1CaptureSelftestFailure.NONE,
        flags: Int,
        attempt: Int,
        pcmBlocks: Int = 0,
        pcmSamples: Int = 0,
        opusPackets: Int = 0,
        leaseRemainingMillis: Int = 0,
    ): OmiCv1CaptureSelftestEvidence {
        val bytes = ByteArray(OmiCv1CaptureSelftestProtocol.STATUS_WIRE_SIZE)
        bytes[0] = 1
        bytes[1] = phase.wireValue.toByte()
        bytes[2] = failure.wireValue.toByte()
        bytes[3] = flags.toByte()
        bytes.putU32(4, attempt)
        bytes.putU32(8, pcmBlocks)
        bytes.putU32(12, pcmSamples)
        bytes.putU32(16, opusPackets)
        bytes.putU32(28, leaseRemainingMillis)
        return OmiCv1CaptureSelftestProtocol.validate(
            statusBytes = bytes,
            discoveredServiceUuids = setOf(
                OmiCv1CaptureSelftestProtocol.SERVICE_UUID,
                OmiCv1CaptureSelftestProtocol.STOCK_IDENTITY_SERVICE_UUID,
            ),
            stockIdentityServiceHasCharacteristics = false,
        )
    }

    private fun ByteArray.putU32(offset: Int, value: Int) {
        for (index in 0 until 4) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun ByteArray.putU64(offset: Int, value: Long) {
        for (index in 0 until 8) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private class FakeRecoveryStatusProbe(
        private val evidence: OmiCv1RecoveryStatusEvidence,
        private val expectedEndpoint: EndpointCandidate,
        private val failure: Throwable?,
    ) : OmiCv1RecoveryStatusProbe {
        var inspectCount = 0
        val inspectedEndpoints = mutableListOf<EndpointCandidate>()

        override suspend fun inspect(endpoint: EndpointCandidate): OmiCv1RecoveryStatusEvidence {
            assertEquals(expectedEndpoint, endpoint)
            inspectCount += 1
            inspectedEndpoints += endpoint
            failure?.let { throw it }
            return evidence
        }
    }

    private class FakeFunctionalStatusProbe(
        evidence: List<OmiCv1FunctionalStatusEvidence>,
        private val expectedEndpoint: EndpointCandidate,
    ) : OmiCv1FunctionalStatusProbe {
        private val evidence = ArrayDeque(evidence)
        var inspectCount = 0

        override suspend fun inspect(endpoint: EndpointCandidate): OmiCv1FunctionalStatusEvidence {
            assertEquals(expectedEndpoint, endpoint)
            inspectCount += 1
            return evidence.removeFirst()
        }
    }

    private class FakeRecordingRootProvisionerStatusProbe(
        private val evidence: OmiCv1RecordingRootProvisionerStatusEvidence,
        private val expectedEndpoint: EndpointCandidate,
    ) : OmiCv1RecordingRootProvisionerStatusProbe {
        var inspectCount = 0

        override suspend fun inspect(
            endpoint: EndpointCandidate,
        ): OmiCv1RecordingRootProvisionerStatusEvidence {
            assertEquals(expectedEndpoint, endpoint)
            inspectCount += 1
            return evidence
        }
    }

    private class FakeLegacyStorageReclaimerStatusProbe(
        private val evidence: OmiCv1LegacyStorageReclaimerStatusEvidence,
        private val expectedEndpoint: EndpointCandidate,
    ) : OmiCv1LegacyStorageReclaimerStatusProbe {
        var inspectCount = 0

        override suspend fun inspect(
            endpoint: EndpointCandidate,
        ): OmiCv1LegacyStorageReclaimerStatusEvidence {
            assertEquals(expectedEndpoint, endpoint)
            inspectCount += 1
            return evidence
        }
    }

    private inner class FakeCaptureSelftest(
        private val baseline: OmiCv1CaptureSelftestEvidence,
        results: List<OmiCv1CaptureSelftestEvidence>,
        private val expectedEndpoint: EndpointCandidate,
    ) : OmiCv1CaptureSelftestProbe, OmiCv1CaptureSelftestRunner {
        private val results = ArrayDeque(results)
        var inspectCount = 0
        var runCount = 0

        override suspend fun inspect(endpoint: EndpointCandidate): OmiCv1CaptureSelftestEvidence {
            assertEquals(expectedEndpoint, endpoint)
            inspectCount += 1
            return baseline
        }

        override suspend fun run(
            endpoint: EndpointCandidate,
            onArmed: (OmiCv1CaptureSelftestEvidence) -> Unit,
        ): OmiCv1CaptureSelftestEvidence {
            assertEquals(expectedEndpoint, endpoint)
            val result = results.removeFirst()
            runCount += 1
            onArmed(exactSelftestArmed(result.attempt))
            return result
        }
    }

    private class FakeStockNormalizationSession(
        private val inspection: FirmwareImageStateInspection,
    ) : OmiCv1StockNormalizationSession {
        var normalizeCount = 0

        override suspend fun inspect(): FirmwareImageStateInspection = inspection

        override suspend fun normalize(
            applicationBytes: ByteArray,
            networkBytes: ByteArray,
            onProgress: (OmiCv1StockNormalizationProgress) -> Unit,
        ) {
            normalizeCount += 1
            onProgress(
                OmiCv1StockNormalizationProgress(
                    OmiCv1StockNormalizationStage.REQUESTING_REBOOT,
                ),
            )
        }

        override fun cancel() = Unit
        override fun release() = Unit
    }

    private class FakeSession(
        inspections: List<FirmwareImageStateInspection>,
    ) : OmiCv1ApplicationImage0UpdateSession {
        private val inspections = ArrayDeque(inspections)
        var inspectCount = 0
        var uploadCount = 0
        var eraseCount = 0
        var confirmCount = 0
        var resetCount = 0
        var releaseCount = 0

        override suspend fun upload(
            imageBytes: ByteArray,
            mode: OmiCv1ApplicationUploadMode,
            onProgress: (Int, Int) -> Unit,
        ) {
            uploadCount += 1
            onProgress(imageBytes.size, imageBytes.size)
        }

        override suspend fun inspect(): FirmwareImageStateInspection {
            inspectCount += 1
            return inspections.removeFirst()
        }

        override suspend fun eraseInactiveApplicationSlot() {
            eraseCount += 1
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
