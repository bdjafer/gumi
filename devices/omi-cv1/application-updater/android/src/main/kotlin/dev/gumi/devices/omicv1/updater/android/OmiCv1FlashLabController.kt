package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import dev.gumi.edge.sdk.firmware.FirmwareMaintenanceStage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class OmiCv1FlashLabPhase {
    SAFETY_REVIEW,
    SCANNING,
    DEVICE_SELECTED,
    READING_PREFLIGHT,
    READY_TO_AUTHORIZE,
    UPDATING,
    AWAITING_POST_REBOOT_SCAN,
    POST_REBOOT_DEVICE_SELECTED,
    VALIDATING_POST_REBOOT,
    READING_ACTIVE_CAPTURE_SELFTEST,
    READING_ACTIVE_FUNCTIONAL,
    RECHECKING_RECOVERY,
    RECHECKING_FUNCTIONAL,
    RUNNING_CAPTURE_SELFTEST,
    AWAITING_CAPTURE_CONFIRMATION,
    VALIDATED,
    STOPPED_ON_FAILURE,
}

internal data class OmiCv1FlashLabChecklist(
    val officialOmiAppStopped: Boolean = false,
    val chargerConnected: Boolean = false,
    val noRollbackRiskAccepted: Boolean = false,
    val exactArtifactAuthorized: Boolean = false,
) {
    val preflightComplete: Boolean get() =
        officialOmiAppStopped &&
            chargerConnected &&
            noRollbackRiskAccepted
}

internal data class OmiCv1FlashLabReview(
    val operation: OmiCv1FlashLabOperation,
    val releaseId: String,
    val intent: OmiCv1ApplicationUpdateIntent?,
    val uploadMode: OmiCv1ApplicationUploadMode?,
    val sourceApplicationHash: String,
    val targetIdentity: String,
    val targetFileSha256: String?,
    val targetImageHash: String?,
    val targetImageNumber: Int,
    val targetSlotNumber: Int? = null,
    val networkPolicy: String,
    val targetNetworkIdentity: String? = null,
    val targetNetworkFileSha256: String? = null,
    val targetNetworkImageHash: String? = null,
)

internal enum class OmiCv1FlashLabOperation {
    APPLICATION_IMAGE_0,
    INACTIVE_APPLICATION_SLOT_ERASE,
    OFFICIAL_STOCK_DUAL_IMAGE_NORMALIZATION,
}

internal data class OmiCv1FlashLabUiState(
    val phase: OmiCv1FlashLabPhase = OmiCv1FlashLabPhase.SAFETY_REVIEW,
    val permissionsGranted: Boolean = false,
    val phonePower: OmiCv1FlashLabPhonePower = OmiCv1FlashLabPhonePower(null, false),
    val checklist: OmiCv1FlashLabChecklist = OmiCv1FlashLabChecklist(),
    val candidates: List<OmiCv1FlashLabCandidate> = emptyList(),
    val selected: OmiCv1FlashLabCandidate? = null,
    val preflightInspection: FirmwareImageStateInspection? = null,
    val devicePreflight: OmiCv1FlashLabDevicePreflightEvidence? = null,
    val review: OmiCv1FlashLabReview? = null,
    val progress: OmiCv1ApplicationUpdateProgress? = null,
    val pendingValidation: OmiCv1ApplicationUpdatePendingValidation? = null,
    val validation: OmiCv1ApplicationUpdateValidation? = null,
    val normalizationProgress: OmiCv1StockNormalizationProgress? = null,
    val normalizationPendingValidation: OmiCv1StockNormalizationPendingValidation? = null,
    val normalizationValidation: OmiCv1StockNormalizationValidation? = null,
    val inactiveSlotEraseStage: OmiCv1InactiveApplicationSlotEraseStage? = null,
    val inactiveSlotEraseValidation: OmiCv1InactiveApplicationSlotEraseValidation? = null,
    val completedIntent: OmiCv1ApplicationUpdateIntent? = null,
    val completedOperation: OmiCv1FlashLabOperation? = null,
    val recoveryValidatedAtMonotonicMillis: Long? = null,
    val captureSelftestConsecutivePasses: Int = 0,
    val captureSelftestRuns: Int = 0,
    val resetResponseObserved: Boolean? = null,
    val error: String? = null,
) {
    val readyForPreflight: Boolean get() =
        permissionsGranted && phonePower.adequateForUpdate && checklist.preflightComplete
    val readyForReadOnlyCapture: Boolean get() =
        permissionsGranted &&
            phonePower.adequateForReadOnlyCapture &&
            checklist.preflightComplete
    val pendingEndpoint: EndpointCandidate?
        get() = normalizationPendingValidation?.endpoint ?: pendingValidation?.endpoint
    val awaitingPostRebootValidation: Boolean
        get() =
            (normalizationPendingValidation != null && normalizationValidation == null) ||
                (pendingValidation != null && validation == null)

    /**
     * Portable lifecycle vocabulary for a future product renderer. Flash Lab keeps its detailed
     * Omi-only phases, while mobile, Linux, and later edge hosts can share this stable projection.
     */
    val portableMaintenanceStage: FirmwareMaintenanceStage
        get() = when (phase) {
            OmiCv1FlashLabPhase.SAFETY_REVIEW,
            OmiCv1FlashLabPhase.SCANNING,
            OmiCv1FlashLabPhase.DEVICE_SELECTED,
            -> FirmwareMaintenanceStage.SAFETY_REVIEW

            OmiCv1FlashLabPhase.READING_PREFLIGHT ->
                FirmwareMaintenanceStage.PREFLIGHT

            OmiCv1FlashLabPhase.READY_TO_AUTHORIZE ->
                FirmwareMaintenanceStage.AWAITING_AUTHORIZATION

            OmiCv1FlashLabPhase.UPDATING ->
                FirmwareMaintenanceStage.APPLYING

            OmiCv1FlashLabPhase.AWAITING_POST_REBOOT_SCAN,
            OmiCv1FlashLabPhase.POST_REBOOT_DEVICE_SELECTED,
            -> FirmwareMaintenanceStage.AWAITING_RESTART

            OmiCv1FlashLabPhase.VALIDATING_POST_REBOOT,
            OmiCv1FlashLabPhase.READING_ACTIVE_CAPTURE_SELFTEST,
            OmiCv1FlashLabPhase.READING_ACTIVE_FUNCTIONAL,
            OmiCv1FlashLabPhase.RECHECKING_RECOVERY,
            OmiCv1FlashLabPhase.RECHECKING_FUNCTIONAL,
            OmiCv1FlashLabPhase.RUNNING_CAPTURE_SELFTEST,
            OmiCv1FlashLabPhase.AWAITING_CAPTURE_CONFIRMATION,
            -> FirmwareMaintenanceStage.VALIDATING

            OmiCv1FlashLabPhase.VALIDATED ->
                FirmwareMaintenanceStage.COMPLETE

            OmiCv1FlashLabPhase.STOPPED_ON_FAILURE ->
                FirmwareMaintenanceStage.FAILED
        }
}

internal class OmiCv1FlashLabController(
    private val scanner: OmiCv1FlashLabScanner,
    private val sessions: OmiCv1ApplicationImage0UpdateSessionFactory,
    private val devicePreflight: OmiCv1FlashLabDevicePreflightProbe,
    private val normalizationSessions: OmiCv1StockNormalizationSessionFactory,
    private val normalizationArtifacts: OmiCv1StockNormalizationArtifactSource,
    private val recoveryStatus: OmiCv1RecoveryStatusProbe,
    private val recordingRootProvisionerStatus: OmiCv1RecordingRootProvisionerStatusProbe,
    private val legacyStorageReclaimerStatus: OmiCv1LegacyStorageReclaimerStatusProbe,
    private val functionalStatus: OmiCv1FunctionalStatusProbe,
    private val captureSelftest: OmiCv1CaptureSelftestProbe,
    private val captureSelftestRunner: OmiCv1CaptureSelftestRunner,
    private val artifacts: OmiCv1FlashLabArtifactSource,
    private val phonePower: OmiCv1FlashLabPhonePowerSource,
    private val clock: MonotonicMillisClock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(
        OmiCv1FlashLabUiState(phonePower = phonePower.read()),
    )
    private var scanJob: Job? = null
    private var operationJob: Job? = null
    private var preparedPlan: OmiCv1PreparedApplicationUpdate? = null
    private var preparedNormalization: OmiCv1PreparedStockNormalization? = null
    private var preparedInactiveSlotErase: OmiCv1PreparedInactiveApplicationSlotErase? = null

    val state: StateFlow<OmiCv1FlashLabUiState> = mutableState.asStateFlow()

    fun refreshEnvironment(permissionsGranted: Boolean) {
        if (mutableState.value.phase.isMutating()) return
        mutableState.update {
            it.copy(
                permissionsGranted = permissionsGranted,
                phonePower = phonePower.read(),
                error = null,
            )
        }
    }

    fun updateChecklist(value: OmiCv1FlashLabChecklist) {
        if (mutableState.value.phase.isMutating()) return
        mutableState.update { current ->
            current.copy(
                checklist = value.copy(
                    exactArtifactAuthorized = if (
                        current.phase == OmiCv1FlashLabPhase.READY_TO_AUTHORIZE
                    ) {
                        value.exactArtifactAuthorized
                    } else {
                        false
                    },
                ),
                error = null,
            )
        }
    }

    fun startScan() {
        val current = mutableState.value
        if (current.phase.isMutating() || current.phase == OmiCv1FlashLabPhase.STOPPED_ON_FAILURE) return
        if (!current.permissionsGranted) {
            mutableState.update { it.copy(error = "Grant Nearby Devices permission before scanning") }
            return
        }
        scanJob?.cancel()
        preparedPlan = null
        preparedNormalization = null
        preparedInactiveSlotErase = null
        val awaitingPostReboot = current.awaitingPostRebootValidation
        mutableState.update {
            it.copy(
                phase = OmiCv1FlashLabPhase.SCANNING,
                candidates = emptyList(),
                selected = null,
                preflightInspection = null,
                devicePreflight = null,
                review = null,
                progress = null,
                normalizationProgress = null,
                inactiveSlotEraseStage = null,
                inactiveSlotEraseValidation = null,
                error = null,
                checklist = it.checklist.copy(exactArtifactAuthorized = false),
            )
        }
        scanJob = scope.launch {
            try {
                scanner.scan().collect { event ->
                    when (event) {
                        is OmiCv1FlashLabScanEvent.Candidate -> recordCandidate(event.value)
                        is OmiCv1FlashLabScanEvent.Failure -> stopOnFailure(event.detail)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stopOnFailure(error.safeDetail("BLE scan failed"))
            } finally {
                scanJob = null
                if (mutableState.value.phase == OmiCv1FlashLabPhase.SCANNING) {
                    mutableState.update {
                        it.copy(
                            phase = if (awaitingPostReboot) {
                                OmiCv1FlashLabPhase.AWAITING_POST_REBOOT_SCAN
                            } else {
                                OmiCv1FlashLabPhase.SAFETY_REVIEW
                            },
                        )
                    }
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        val current = mutableState.value
        if (current.phase == OmiCv1FlashLabPhase.SCANNING) {
            mutableState.update {
                it.copy(
                    phase = if (it.pendingEndpoint != null) {
                        OmiCv1FlashLabPhase.AWAITING_POST_REBOOT_SCAN
                    } else {
                        OmiCv1FlashLabPhase.SAFETY_REVIEW
                    },
                )
            }
        }
    }

    fun select(candidate: OmiCv1FlashLabCandidate) {
        val current = mutableState.value
        if (current.phase != OmiCv1FlashLabPhase.SCANNING) return
        if (candidate !in current.candidates) return
        val awaitingPostReboot = current.pendingEndpoint != null
        scanJob?.cancel()
        scanJob = null
        mutableState.update {
            it.copy(
                phase = if (!awaitingPostReboot) {
                    OmiCv1FlashLabPhase.DEVICE_SELECTED
                } else {
                    OmiCv1FlashLabPhase.POST_REBOOT_DEVICE_SELECTED
                },
                selected = candidate,
                error = null,
            )
        }
    }

    fun runPreflight(requestedIntent: OmiCv1ApplicationUpdateIntent? = null) {
        runPreflightInternal(requestedIntent, stockNormalizationRequested = false)
    }

    fun runStockNormalizationPreflight() {
        runPreflightInternal(requestedIntent = null, stockNormalizationRequested = true)
    }

    fun runInactiveApplicationSlotErasePreflight() {
        val current = mutableState.value
        val selected = current.selected ?: return
        if (current.phase != OmiCv1FlashLabPhase.DEVICE_SELECTED) return
        val refreshedPower = phonePower.read()
        if (!current.copy(phonePower = refreshedPower).readyForPreflight) {
            mutableState.update {
                it.copy(
                    phonePower = refreshedPower,
                    error = "Complete every safety check and charge the phone to at least " +
                        "$MIN_PHONE_BATTERY_PERCENT%",
                )
            }
            return
        }
        if (operationJob?.isActive == true) return
        mutableState.update {
            it.copy(
                phase = OmiCv1FlashLabPhase.READING_PREFLIGHT,
                phonePower = refreshedPower,
                preflightInspection = null,
                devicePreflight = null,
                review = null,
                error = null,
            )
        }
        operationJob = scope.launch {
            try {
                val inspection = inspectOnce(selected)
                mutableState.update { it.copy(preflightInspection = inspection) }
                val observedDevice = devicePreflight.inspect(selected.endpoint)
                mutableState.update { it.copy(devicePreflight = observedDevice) }
                val release =
                    OmiCv1V3012ApplicationUpdateCatalog
                        .legacyStorageReclaimer0002ToFunctionalRecording0007
                OmiCv1FlashLabDevicePreflightPolicy.requireSafe(
                    observedDevice,
                    selected.endpoint,
                    release.sourceManufacturer,
                )
                val reclaimerEvidence = legacyStorageReclaimerStatus.inspect(selected.endpoint)
                OmiCv1LegacyStorageReclaimerStatusProtocol.requireReclaimSucceeded(
                    reclaimerEvidence,
                )
                val plan = OmiCv1InactiveApplicationSlotErasePlanner.prepare(
                    endpoint = selected.endpoint,
                    inspection = inspection,
                    expectedActiveApplicationHash = release.source.applicationHash,
                )
                preparedPlan = null
                preparedNormalization = null
                preparedInactiveSlotErase = plan
                mutableState.update {
                    it.copy(
                        phase = OmiCv1FlashLabPhase.READY_TO_AUTHORIZE,
                        review = plan.toReview(release.source.networkEvidencePolicy),
                        checklist = it.checklist.copy(exactArtifactAuthorized = false),
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stopOnFailure(error.safeDetail("Inactive-slot erase preflight was rejected"))
            } finally {
                operationJob = null
            }
        }
    }

    private fun runPreflightInternal(
        requestedIntent: OmiCv1ApplicationUpdateIntent?,
        stockNormalizationRequested: Boolean,
    ) {
        val current = mutableState.value
        val selected = current.selected ?: return
        if (current.phase != OmiCv1FlashLabPhase.DEVICE_SELECTED) return
        val refreshedPower = phonePower.read()
        if (!current.copy(phonePower = refreshedPower).readyForPreflight) {
            mutableState.update {
                it.copy(
                    phonePower = refreshedPower,
                    error = "Complete every safety check and charge the phone to at least " +
                        "$MIN_PHONE_BATTERY_PERCENT%",
                )
            }
            return
        }
        if (operationJob?.isActive == true) return
        mutableState.update {
            it.copy(
                phase = OmiCv1FlashLabPhase.READING_PREFLIGHT,
                phonePower = refreshedPower,
                preflightInspection = null,
                devicePreflight = null,
                error = null,
            )
        }
        operationJob = scope.launch {
            try {
                val inspection = inspectOnce(selected)
                mutableState.update { it.copy(preflightInspection = inspection) }
                val observedDevice = devicePreflight.inspect(selected.endpoint)
                mutableState.update { it.copy(devicePreflight = observedDevice) }
                if (stockNormalizationRequested) {
                    OmiCv1FlashLabDevicePreflightPolicy.requireSafe(
                        observedDevice,
                        selected.endpoint,
                        OmiCv1V3012ApplicationUpdateCatalog.STOCK_MANUFACTURER,
                    )
                    val artifacts = normalizationArtifacts.load()
                    val plan = OmiCv1StockNormalizationPlanner.prepare(
                        selected.endpoint,
                        inspection,
                        observedDevice,
                        OmiCv1StockNormalizationCatalog.v3007ToV3012,
                        artifacts,
                    )
                    preparedPlan = null
                    preparedNormalization = plan
                    mutableState.update {
                        it.copy(
                            phase = OmiCv1FlashLabPhase.READY_TO_AUTHORIZE,
                            review = plan.toReview(),
                            checklist = it.checklist.copy(exactArtifactAuthorized = false),
                            error = null,
                        )
                    }
                    return@launch
                }
                val release = releaseFor(inspection, requestedIntent)
                OmiCv1FlashLabDevicePreflightPolicy.requireSafe(
                    observedDevice,
                    selected.endpoint,
                    release.sourceManufacturer,
                )
                if (
                    release.source.applicationHash ==
                    OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.mcubootImageHash
                ) {
                    recoveryStatus.inspect(selected.endpoint)
                }
                if (
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog.functionalRecording0001ToRecoveryOnly0001 ||
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog.functionalRecording0003ToRecoveryOnly0001 ||
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog.functionalRecording0004ToRecoveryOnly0001 ||
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog.functionalRecording0005ToRecoveryOnly0001 ||
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog.functionalRecording0006ToRecoveryOnly0001 ||
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog
                        .functionalRecording0006ToLegacyStorageReclaimer0002 ||
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog.functionalRecording0007ToRecoveryOnly0001 ||
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog
                        .functionalRecording0003ToFunctionalRecording0006 ||
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog
                        .functionalRecording0004ToFunctionalRecording0006 ||
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog
                        .functionalRecording0005ToFunctionalRecording0006
                ) {
                    val evidence = functionalStatus.inspect(selected.endpoint)
                    try {
                        OmiCv1FunctionalStatusProtocol.requireRecoveryMaintenance(evidence)
                    } catch (error: OmiCv1ApplicationUpdateException) {
                        throw OmiCv1ApplicationUpdateException(
                            error.code,
                            "${error.message}; exact active application " +
                                "${release.source.applicationHash.hex}; selected release " +
                                release.releaseId,
                            error,
                        )
                    }
                }
                if (
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog
                        .recordingRootProvisioner0001ToFunctionalRecording0006 ||
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog
                        .recordingRootProvisioner0001ToLegacyStorageReclaimer0002
                ) {
                    recordingRootProvisionerStatus.inspect(selected.endpoint)
                }
                if (
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog
                        .legacyStorageReclaimer0002ToFunctionalRecording0007 ||
                    release ===
                    OmiCv1V3012ApplicationUpdateCatalog
                        .legacyStorageReclaimer0002ToRecoveryOnly0001
                ) {
                    val evidence = legacyStorageReclaimerStatus.inspect(selected.endpoint)
                    if (
                        release ===
                        OmiCv1V3012ApplicationUpdateCatalog
                            .legacyStorageReclaimer0002ToFunctionalRecording0007
                    ) {
                        OmiCv1LegacyStorageReclaimerStatusProtocol
                            .requireReclaimSucceeded(evidence)
                    } else {
                        OmiCv1LegacyStorageReclaimerStatusProtocol
                            .requireRecoveryEligible(evidence)
                    }
                }
                val imageBytes = artifacts.load(release.target)
                val plan = OmiCv1ApplicationUpdatePlanner.prepare(
                    selected.endpoint,
                    inspection,
                    release,
                    imageBytes,
                )
                preparedPlan = plan
                preparedNormalization = null
                mutableState.update {
                    it.copy(
                        phase = OmiCv1FlashLabPhase.READY_TO_AUTHORIZE,
                        review = plan.toReview(),
                        checklist = it.checklist.copy(exactArtifactAuthorized = false),
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stopOnFailure(error.safeDetail("Preflight was rejected"))
            } finally {
                operationJob = null
            }
        }
    }

    /**
     * Re-enters qualification after a phone-process restart without writing firmware.
     *
     * This path accepts only the exact known capture self-test image, repeats the complete
     * MCUboot/topology/status proof, and requires a safely re-armable microphone-off baseline.
     */
    fun resumeActiveCaptureSelftest() {
        val current = mutableState.value
        val selected = current.selected ?: return
        if (current.phase != OmiCv1FlashLabPhase.DEVICE_SELECTED) return
        val refreshedPower = phonePower.read()
        if (!current.copy(phonePower = refreshedPower).readyForReadOnlyCapture) {
            mutableState.update {
                it.copy(
                    phonePower = refreshedPower,
                    error = "Complete every safety check and charge the phone to at least " +
                        "$MIN_PHONE_READ_ONLY_CAPTURE_PERCENT% for read-only capture qualification",
                )
            }
            return
        }
        if (operationJob?.isActive == true) return
        mutableState.update {
            it.copy(
                phase = OmiCv1FlashLabPhase.READING_ACTIVE_CAPTURE_SELFTEST,
                phonePower = refreshedPower,
                error = null,
            )
        }
        operationJob = scope.launch {
            try {
                val source =
                    OmiCv1V3012ApplicationUpdateCatalog
                        .capturePortSelftest0001ToRecoveryOnly0001
                        .source
                val pending = OmiCv1ApplicationUpdatePendingValidation(
                    planId = "read-only-capture-selftest-resume",
                    endpoint = selected.endpoint,
                    expectedApplicationHash = source.applicationHash,
                    expectedNetworkHash = source.networkHash,
                    expectedMcubootVersion = source.mcubootVersion,
                    networkEvidencePolicy = source.networkEvidencePolicy,
                    resetResponseObserved = false,
                )
                val imageValidation = OmiCv1ApplicationUpdatePlanner.validatePostRebootState(
                    inspectOnce(selected),
                    pending,
                )
                val status = captureSelftest.inspect(selected.endpoint).also(
                    OmiCv1CaptureSelftestProtocol::requireSafeBaseline,
                )
                mutableState.update {
                    it.copy(
                        phase = OmiCv1FlashLabPhase.VALIDATED,
                        pendingValidation = pending,
                        validation = imageValidation.copy(captureSelftestStatus = status),
                        completedIntent = OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST,
                        // A fresh exact terminal read can recover one pass lost only at the
                        // Android transport boundary. Resetting to one prevents repeated app
                        // restarts from multiplying the same device-side attempt.
                        captureSelftestConsecutivePasses = if (status.passedSafely) 1 else 0,
                        captureSelftestRuns = if (status.phase.terminal) 1 else 0,
                        progress = null,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stopOnFailure(error.safeDetail("Read-only capture self-test resume failed"))
            } finally {
                operationJob = null
            }
        }
    }

    /**
     * Re-enters functional qualification after a phone-process restart without
     * creating an update authorization or writing any firmware.
     */
    fun resumeActiveFunctional() {
        val current = mutableState.value
        val selected = current.selected ?: return
        if (current.phase != OmiCv1FlashLabPhase.DEVICE_SELECTED) return
        val refreshedPower = phonePower.read()
        if (!current.copy(phonePower = refreshedPower).readyForReadOnlyCapture) {
            mutableState.update {
                it.copy(
                    phonePower = refreshedPower,
                    error = "Complete every safety check and charge the phone to at least " +
                        "$MIN_PHONE_READ_ONLY_CAPTURE_PERCENT% for read-only functional qualification",
                )
            }
            return
        }
        if (operationJob?.isActive == true) return
        mutableState.update {
            it.copy(
                phase = OmiCv1FlashLabPhase.READING_ACTIVE_FUNCTIONAL,
                phonePower = refreshedPower,
                error = null,
            )
        }
        operationJob = scope.launch {
            try {
                val inspection = inspectOnce(selected)
                val activeApplicationHash = inspection.slots.singleOrNull {
                    it.imageNumber ==
                        OmiCv1ApplicationUpdatePlanner.APPLICATION_IMAGE_NUMBER &&
                        it.active
                }?.hash
                val source = when (activeApplicationHash) {
                    OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.mcubootImageHash ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .functionalRecording0006ToRecoveryOnly0001
                            .source

                    OmiCv1FunctionalRecording0007ApplicationArtifact.manifest.mcubootImageHash ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .functionalRecording0007ToRecoveryOnly0001
                            .source

                    else -> throw OmiCv1ApplicationUpdateException(
                        OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
                        "Read-only functional resume requires exact v0006 or v0007",
                    )
                }
                val pending = OmiCv1ApplicationUpdatePendingValidation(
                    planId = "read-only-functional-resume",
                    endpoint = selected.endpoint,
                    expectedApplicationHash = source.applicationHash,
                    expectedNetworkHash = source.networkHash,
                    expectedMcubootVersion = source.mcubootVersion,
                    networkEvidencePolicy = source.networkEvidencePolicy,
                    resetResponseObserved = false,
                )
                val imageValidation = OmiCv1ApplicationUpdatePlanner.validatePostRebootState(
                    inspection,
                    pending,
                )
                val status = functionalStatus.inspect(selected.endpoint)
                mutableState.update {
                    it.copy(
                        phase = OmiCv1FlashLabPhase.VALIDATED,
                        pendingValidation = pending,
                        validation = imageValidation.copy(functionalStatus = status),
                        completedIntent = OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING,
                        progress = null,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stopOnFailure(error.safeDetail("Read-only functional resume failed"))
            } finally {
                operationJob = null
            }
        }
    }

    fun authorizeAndExecute() {
        val current = mutableState.value
        val applicationPlan = preparedPlan
        val normalizationPlan = preparedNormalization
        val inactiveSlotErasePlan = preparedInactiveSlotErase
        if (
            applicationPlan == null &&
            normalizationPlan == null &&
            inactiveSlotErasePlan == null
        ) {
            return
        }
        if (current.phase != OmiCv1FlashLabPhase.READY_TO_AUTHORIZE) return
        if (!current.checklist.exactArtifactAuthorized) {
            mutableState.update { it.copy(error = "Acknowledge the exact artifact before authorization") }
            return
        }
        val refreshedPower = phonePower.read()
        if (!refreshedPower.adequateForUpdate || !current.checklist.preflightComplete) {
            preparedPlan = null
            preparedNormalization = null
            preparedInactiveSlotErase = null
            mutableState.update {
                it.copy(
                    phase = OmiCv1FlashLabPhase.SAFETY_REVIEW,
                    phonePower = refreshedPower,
                    review = null,
                    error = "Power or safety state changed; run a fresh preflight",
                )
            }
            return
        }
        if (operationJob?.isActive == true) return
        mutableState.update {
            it.copy(
                phase = OmiCv1FlashLabPhase.UPDATING,
                phonePower = refreshedPower,
                progress = applicationPlan?.let {
                    OmiCv1ApplicationUpdateProgress(OmiCv1ApplicationUpdateStage.PREPARING)
                },
                normalizationProgress = normalizationPlan?.let {
                    OmiCv1StockNormalizationProgress(
                        OmiCv1StockNormalizationStage.VERIFYING_PREFLIGHT,
                    )
                },
                inactiveSlotEraseStage = inactiveSlotErasePlan?.let {
                    OmiCv1InactiveApplicationSlotEraseStage.VERIFYING_PREFLIGHT_STATE
                },
                error = null,
            )
        }
        operationJob = scope.launch {
            try {
                if (inactiveSlotErasePlan != null) {
                    val authorization = OmiCv1InactiveApplicationSlotEraseAuthorization(
                        inactiveSlotErasePlan,
                        inactiveSlotErasePlan.planId,
                        clock.now() + AUTHORIZATION_LIFETIME_MILLIS,
                    )
                    val validation = OmiCv1InactiveApplicationSlotEraseExecutor(
                        sessions,
                        clock,
                    ).execute(authorization) { stage ->
                        mutableState.update { it.copy(inactiveSlotEraseStage = stage) }
                    }
                    preparedInactiveSlotErase = null
                    mutableState.update {
                        it.copy(
                            phase = OmiCv1FlashLabPhase.VALIDATED,
                            review = null,
                            completedOperation =
                                OmiCv1FlashLabOperation.INACTIVE_APPLICATION_SLOT_ERASE,
                            inactiveSlotEraseValidation = validation,
                            checklist = it.checklist.copy(exactArtifactAuthorized = false),
                            error = null,
                        )
                    }
                } else if (normalizationPlan != null) {
                    val authorization = OmiCv1StockNormalizationAuthorization(
                        normalizationPlan,
                        normalizationPlan.planId,
                        clock.now() + AUTHORIZATION_LIFETIME_MILLIS,
                    )
                    val pending = OmiCv1StockNormalizationExecutor(
                        normalizationSessions,
                        devicePreflight,
                        clock,
                    ).execute(authorization) { progress ->
                        mutableState.update { it.copy(normalizationProgress = progress) }
                    }
                    preparedNormalization = null
                    mutableState.update {
                        it.copy(
                            phase = OmiCv1FlashLabPhase.AWAITING_POST_REBOOT_SCAN,
                            review = null,
                            normalizationPendingValidation = pending,
                            resetResponseObserved = true,
                            checklist = it.checklist.copy(exactArtifactAuthorized = false),
                            error = null,
                        )
                    }
                } else {
                    val plan = requireNotNull(applicationPlan)
                    val authorization = OmiCv1ApplicationUpdateAuthorization(
                        plan = plan,
                        planId = plan.planId,
                        expiresAtMonotonicMillis = clock.now() + AUTHORIZATION_LIFETIME_MILLIS,
                    )
                    val pending = OmiCv1ApplicationImage0UpdateExecutor(sessions, clock).execute(
                        authorization,
                    ) { progress ->
                        mutableState.update { it.copy(progress = progress) }
                    }
                    preparedPlan = null
                    mutableState.update {
                        it.copy(
                            phase = OmiCv1FlashLabPhase.AWAITING_POST_REBOOT_SCAN,
                            review = null,
                            pendingValidation = pending,
                            resetResponseObserved = pending.resetResponseObserved,
                            checklist = it.checklist.copy(exactArtifactAuthorized = false),
                            error = null,
                        )
                    }
                }
            } catch (error: CancellationException) {
                stopOnFailure(
                    "Update canceled; do not retry without reviewing fresh image state",
                )
                throw error
            } catch (error: Throwable) {
                stopOnFailure(
                    error.safeDetail(
                        if (normalizationPlan != null) {
                            "Official dual-image stock normalization stopped"
                        } else if (inactiveSlotErasePlan != null) {
                            "Inactive application slot erase stopped"
                        } else {
                            "Application image-0 update stopped"
                        },
                    ),
                )
            } finally {
                operationJob = null
            }
        }
    }

    fun validatePostReboot() {
        val current = mutableState.value
        val selected = current.selected ?: return
        val applicationPending = current.pendingValidation
        val normalizationPending = current.normalizationPendingValidation
        if (applicationPending == null && normalizationPending == null) return
        if (current.phase != OmiCv1FlashLabPhase.POST_REBOOT_DEVICE_SELECTED) return
        if (operationJob?.isActive == true) return
        mutableState.update {
            it.copy(phase = OmiCv1FlashLabPhase.VALIDATING_POST_REBOOT, error = null)
        }
        operationJob = scope.launch {
            try {
                val inspection = inspectOnce(selected)
                if (normalizationPending != null) {
                    val observedDevice = devicePreflight.inspect(selected.endpoint)
                    val candidatePending = normalizationPending.copy(endpoint = selected.endpoint)
                    val validation = OmiCv1StockNormalizationPlanner.validatePostReboot(
                        inspection,
                        observedDevice,
                        candidatePending,
                    )
                    mutableState.update {
                        it.copy(
                            phase = OmiCv1FlashLabPhase.VALIDATED,
                            preflightInspection = inspection,
                            devicePreflight = observedDevice,
                            normalizationPendingValidation = candidatePending,
                            normalizationValidation = validation,
                            completedOperation =
                                OmiCv1FlashLabOperation.OFFICIAL_STOCK_DUAL_IMAGE_NORMALIZATION,
                            normalizationProgress = null,
                            error = null,
                        )
                    }
                    return@launch
                }
                // A firmware transition may rotate the BLE address. Keep the scanned endpoint
                // untrusted until the complete target-image proof (and recovery proof, when
                // applicable) succeeds against that same process-local endpoint.
                val candidatePending = requireNotNull(applicationPending).copy(
                    endpoint = selected.endpoint,
                )
                val imageValidation = OmiCv1ApplicationUpdatePlanner.validatePostRebootState(
                    inspection,
                    candidatePending,
                )
                val completedIntent = intentForTarget(imageValidation.applicationHash)
                val validation = imageValidation.copy(
                    recoveryStatus = if (completedIntent == OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY) {
                        recoveryStatus.inspect(selected.endpoint)
                    } else {
                        null
                    },
                    captureSelftestStatus = if (
                        completedIntent == OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST
                    ) {
                        captureSelftest.inspect(selected.endpoint).also(
                            OmiCv1CaptureSelftestProtocol::requireSafeBaseline,
                        )
                    } else {
                        null
                    },
                    recordingRootProvisionerStatus = if (
                        completedIntent ==
                        OmiCv1ApplicationUpdateIntent.RECORDING_ROOT_PROVISIONER
                    ) {
                        recordingRootProvisionerStatus.inspect(selected.endpoint)
                    } else {
                        null
                    },
                    legacyStorageReclaimerStatus = if (
                        completedIntent ==
                        OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER
                    ) {
                        legacyStorageReclaimerStatus.inspect(selected.endpoint)
                    } else {
                        null
                    },
                    functionalStatus = if (
                        completedIntent == OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING
                    ) {
                        functionalStatus.inspect(selected.endpoint)
                    } else {
                        null
                    },
                )
                mutableState.update {
                    it.copy(
                        phase = OmiCv1FlashLabPhase.VALIDATED,
                        pendingValidation = candidatePending,
                        validation = validation,
                        completedIntent = completedIntent,
                        completedOperation = OmiCv1FlashLabOperation.APPLICATION_IMAGE_0,
                        recoveryValidatedAtMonotonicMillis = if (
                            completedIntent == OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY
                        ) {
                            clock.now()
                        } else {
                            null
                        },
                        captureSelftestConsecutivePasses = 0,
                        captureSelftestRuns = 0,
                        progress = null,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stopOnFailure(error.safeDetail("Post-reboot validation failed"))
            } finally {
                operationJob = null
            }
        }
    }

    /** Starts a separately reviewed transition; it never reuses the prior authorization. */
    fun beginNextTransition() {
        val current = mutableState.value
        if (current.phase != OmiCv1FlashLabPhase.VALIDATED) return
        preparedPlan = null
        preparedNormalization = null
        preparedInactiveSlotErase = null
        mutableState.value = current.copy(
            phase = OmiCv1FlashLabPhase.SAFETY_REVIEW,
            candidates = emptyList(),
            selected = null,
            preflightInspection = null,
            review = null,
            progress = null,
            pendingValidation = null,
            validation = null,
            normalizationProgress = null,
            normalizationPendingValidation = null,
            normalizationValidation = null,
            inactiveSlotEraseStage = null,
            inactiveSlotEraseValidation = null,
            completedIntent = null,
            completedOperation = null,
            recoveryValidatedAtMonotonicMillis = null,
            captureSelftestConsecutivePasses = 0,
            captureSelftestRuns = 0,
            resetResponseObserved = null,
            checklist = current.checklist.copy(
                exactArtifactAuthorized = false,
            ),
            error = null,
        )
    }

    /** Arms one bounded media-free attempt; the device still requires a physical two-second hold. */
    fun runCaptureSelftest() {
        val current = mutableState.value
        val selected = current.selected ?: return
        val baseline = current.validation?.captureSelftestStatus ?: return
        val refreshedPower = phonePower.read()
        if (
            current.phase != OmiCv1FlashLabPhase.VALIDATED ||
            current.completedIntent != OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST ||
            operationJob?.isActive == true
        ) {
            return
        }
        if (!refreshedPower.adequateForReadOnlyCapture) {
            mutableState.update {
                it.copy(
                    phonePower = refreshedPower,
                    error = "Charge the phone to at least " +
                        "$MIN_PHONE_READ_ONLY_CAPTURE_PERCENT% before a bounded self-test",
                )
            }
            return
        }
        try {
            OmiCv1CaptureSelftestProtocol.requireSafeBaseline(baseline)
        } catch (error: Throwable) {
            stopOnFailure(error.safeDetail("Capture self-test cannot be re-armed"))
            return
        }
        mutableState.update {
            it.copy(
                phase = OmiCv1FlashLabPhase.RUNNING_CAPTURE_SELFTEST,
                phonePower = refreshedPower,
                error = null,
            )
        }
        operationJob = scope.launch {
            try {
                val result = captureSelftestRunner.run(selected.endpoint) { armed ->
                    if (armed.attempt != baseline.attempt + 1L) {
                        throw OmiCv1ApplicationUpdateException(
                            OmiCv1ApplicationUpdateFailureCode.CAPTURE_SELFTEST_EVIDENCE_REJECTED,
                            "Capture self-test arm advanced an unexpected attempt counter",
                        )
                    }
                    mutableState.update {
                        it.copy(
                            phase = OmiCv1FlashLabPhase.AWAITING_CAPTURE_CONFIRMATION,
                            validation = it.validation?.copy(captureSelftestStatus = armed),
                            error = null,
                        )
                    }
                }
                if (result.attempt != baseline.attempt + 1L) {
                    throw OmiCv1ApplicationUpdateException(
                        OmiCv1ApplicationUpdateFailureCode.CAPTURE_SELFTEST_EVIDENCE_REJECTED,
                        "Capture self-test completed an unexpected attempt counter",
                    )
                }
                if (result.phase == OmiCv1CaptureSelftestPhase.FAILED_MICROPHONE_UNKNOWN) {
                    throw OmiCv1ApplicationUpdateException(
                        OmiCv1ApplicationUpdateFailureCode.CAPTURE_SELFTEST_EVIDENCE_REJECTED,
                        "Microphone release is unknown; red must remain on and recovery-only must be restored",
                    )
                }
                if (!result.passedSafely) {
                    OmiCv1CaptureSelftestProtocol.requireSafeBaseline(result)
                }
                mutableState.update {
                    val confirmationExpiredBeforeCapture =
                        result.phase == OmiCv1CaptureSelftestPhase.FAILED_SAFE &&
                            result.failure ==
                            OmiCv1CaptureSelftestFailure.CONFIRMATION_EXPIRED
                    it.copy(
                        phase = OmiCv1FlashLabPhase.VALIDATED,
                        validation = it.validation?.copy(captureSelftestStatus = result),
                        captureSelftestConsecutivePasses = when {
                            result.passedSafely -> it.captureSelftestConsecutivePasses + 1
                            confirmationExpiredBeforeCapture ->
                                it.captureSelftestConsecutivePasses
                            else -> 0
                        },
                        captureSelftestRuns = it.captureSelftestRuns +
                            if (confirmationExpiredBeforeCapture) 0 else 1,
                        error = when {
                            result.passedSafely -> null
                            confirmationExpiredBeforeCapture ->
                                "Attempt ${result.attempt} expired before capture began; " +
                                    "completed safe-pass count preserved"
                            else ->
                                "Attempt ${result.attempt} failed safely: " +
                                    "${result.failure.name}; consecutive pass count reset"
                        },
                    )
                }
            } catch (error: CancellationException) {
                stopOnFailure("Capture self-test canceled; inspect the Omi privacy LED before recovery")
                throw error
            } catch (error: Throwable) {
                stopOnFailure(error.safeDetail("Capture self-test stopped"))
            } finally {
                operationJob = null
            }
        }
    }

    /** Repeats the read-only image-state and recovery GATT proof against the bound endpoint. */
    fun recheckRecoveryStatus() {
        val current = mutableState.value
        val selected = current.selected ?: return
        val validation = current.validation ?: return
        val pending = current.pendingValidation ?: return
        val validatedAt = current.recoveryValidatedAtMonotonicMillis ?: return
        if (
            current.phase != OmiCv1FlashLabPhase.VALIDATED ||
            current.completedIntent != OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY ||
            validation.recoveryStatus == null ||
            operationJob?.isActive == true
        ) {
            return
        }
        val now = clock.now()
        if (now < validatedAt) {
            stopOnFailure("Monotonic clock regressed; recovery observation time cannot be proven")
            return
        }
        if (now - validatedAt < RECOVERY_OBSERVATION_MILLIS) {
            mutableState.update {
                it.copy(
                    error = "Wait the full 10-minute off-charger observation before rechecking recovery evidence",
                )
            }
            return
        }
        mutableState.update {
            it.copy(phase = OmiCv1FlashLabPhase.RECHECKING_RECOVERY, error = null)
        }
        operationJob = scope.launch {
            try {
                val imageValidation = OmiCv1ApplicationUpdatePlanner.validatePostRebootState(
                    inspectOnce(selected),
                    pending,
                )
                if (intentForTarget(imageValidation.applicationHash) != OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY) {
                    throw OmiCv1ApplicationUpdateException(
                        OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
                        "Recovery recheck observed a different application target",
                    )
                }
                val evidence = recoveryStatus.inspect(selected.endpoint)
                mutableState.update {
                    it.copy(
                        phase = OmiCv1FlashLabPhase.VALIDATED,
                        validation = imageValidation.copy(recoveryStatus = evidence),
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stopOnFailure(error.safeDetail("Recovery evidence recheck failed"))
            } finally {
                operationJob = null
            }
        }
    }

    /** Repeats exact image-state plus functional status/capability proof without writing firmware. */
    fun recheckFunctionalStatus() {
        val current = mutableState.value
        val selected = current.selected ?: return
        val pending = current.pendingValidation ?: return
        if (
            current.phase != OmiCv1FlashLabPhase.VALIDATED ||
            current.completedIntent != OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING ||
            current.validation?.functionalStatus == null ||
            operationJob?.isActive == true
        ) {
            return
        }
        mutableState.update {
            it.copy(phase = OmiCv1FlashLabPhase.RECHECKING_FUNCTIONAL, error = null)
        }
        operationJob = scope.launch {
            try {
                val imageValidation = OmiCv1ApplicationUpdatePlanner.validatePostRebootState(
                    inspectOnce(selected),
                    pending,
                )
                if (
                    intentForTarget(imageValidation.applicationHash) !=
                    OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING
                ) {
                    throw OmiCv1ApplicationUpdateException(
                        OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
                        "Functional recheck observed a different application target",
                    )
                }
                val evidence = functionalStatus.inspect(selected.endpoint)
                mutableState.update {
                    it.copy(
                        phase = OmiCv1FlashLabPhase.VALIDATED,
                        validation = imageValidation.copy(functionalStatus = evidence),
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stopOnFailure(error.safeDetail("Functional status recheck failed"))
            } finally {
                operationJob = null
            }
        }
    }

    override fun close() {
        scanJob?.cancel()
        operationJob?.cancel()
        scope.cancel()
    }

    private fun recordCandidate(candidate: OmiCv1FlashLabCandidate) {
        mutableState.update { current ->
            if (current.phase != OmiCv1FlashLabPhase.SCANNING) return@update current
            val byEndpoint = current.candidates.associateBy { it.endpoint.ephemeralId }.toMutableMap()
            byEndpoint[candidate.endpoint.ephemeralId] = candidate
            current.copy(candidates = byEndpoint.values.sortedByDescending { it.rssi })
        }
    }

    private suspend fun inspectOnce(
        selected: OmiCv1FlashLabCandidate,
    ): FirmwareImageStateInspection {
        val session = sessions.open(selected.endpoint)
        return try {
            session.inspect()
        } finally {
            session.release()
        }
    }

    private fun releaseFor(
        inspection: FirmwareImageStateInspection,
        requestedIntent: OmiCv1ApplicationUpdateIntent?,
    ): OmiCv1ApplicationUpdateRelease {
        val activeApplication = inspection.slots.filter {
            it.imageNumber == OmiCv1ApplicationUpdatePlanner.APPLICATION_IMAGE_NUMBER && it.active
        }
        if (activeApplication.size != 1) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
                "Preflight requires exactly one active application image",
            )
        }
        val activeHash = activeApplication.single().hash?.hex
        val release = when (activeHash) {
            OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH ->
                OmiCv1V3012ApplicationUpdateCatalog.stockToRecoveryOnly0001

            OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.mcubootImageHash.hex -> when (
                requestedIntent
            ) {
                OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST ->
                    OmiCv1V3012ApplicationUpdateCatalog.recoveryOnly0001ToCapturePortSelftest0001

                OmiCv1ApplicationUpdateIntent.RECORDING_ROOT_PROVISIONER ->
                    OmiCv1V3012ApplicationUpdateCatalog
                        .recoveryOnly0001ToRecordingRootProvisioner0001

                null, OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY ->
                    OmiCv1V3012ApplicationUpdateCatalog.recoveryOnly0001ToStock

                OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
                OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER,
                OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING,
                -> null
            }

            OmiCv1CapturePortSelftest0001ApplicationArtifact.manifest.mcubootImageHash.hex ->
                OmiCv1V3012ApplicationUpdateCatalog.capturePortSelftest0001ToRecoveryOnly0001

            OmiCv1FunctionalRecording0001ApplicationArtifact.manifest.mcubootImageHash.hex ->
                OmiCv1V3012ApplicationUpdateCatalog.functionalRecording0001ToRecoveryOnly0001

            OmiCv1RecordingRootProvisioner0001ApplicationArtifact.manifest.mcubootImageHash.hex ->
                when (requestedIntent) {
                    OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .recordingRootProvisioner0001ToLegacyStorageReclaimer0002

                    OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .recordingRootProvisioner0001ToRecoveryOnly0001

                    else -> null
                }

            OmiCv1FunctionalRecording0003ApplicationArtifact.manifest.mcubootImageHash.hex ->
                when (requestedIntent) {
                    OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .functionalRecording0003ToFunctionalRecording0006

                    null, OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .functionalRecording0003ToRecoveryOnly0001

                    else -> null
                }

            OmiCv1FunctionalRecording0004ApplicationArtifact.manifest.mcubootImageHash.hex ->
                when (requestedIntent) {
                    OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .functionalRecording0004ToFunctionalRecording0006

                    null, OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .functionalRecording0004ToRecoveryOnly0001

                    else -> null
                }

            OmiCv1FunctionalRecording0005ApplicationArtifact.manifest.mcubootImageHash.hex ->
                when (requestedIntent) {
                    OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .functionalRecording0005ToFunctionalRecording0006

                    null, OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .functionalRecording0005ToRecoveryOnly0001

                    else -> null
                }

            OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.mcubootImageHash.hex ->
                when (requestedIntent) {
                    OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .functionalRecording0006ToLegacyStorageReclaimer0002

                    null, OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .functionalRecording0006ToRecoveryOnly0001

                    else -> null
                }

            OmiCv1LegacyStorageReclaimer0001ApplicationArtifact.manifest.mcubootImageHash.hex ->
                throw OmiCv1ApplicationUpdateException(
                    OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
                    "The active legacy-storage-reclaimer-0001 is OTA-stranded: it powers down " +
                        "the SD peer on the shared SPI3 bus before a real secondary-slot write. " +
                        "No BLE update or reset is authorized from this image; recover with SWD.",
                )

            OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest.mcubootImageHash.hex ->
                when (requestedIntent) {
                    OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .legacyStorageReclaimer0002ToFunctionalRecording0007

                    null, OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .legacyStorageReclaimer0002ToRecoveryOnly0001

                    else -> null
                }

            OmiCv1FunctionalRecording0007ApplicationArtifact.manifest.mcubootImageHash.hex ->
                when (requestedIntent) {
                    null, OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY ->
                        OmiCv1V3012ApplicationUpdateCatalog
                            .functionalRecording0007ToRecoveryOnly0001

                    else -> null
                }

            else -> null
        }
        if (release == null || (requestedIntent != null && release.intent != requestedIntent)) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
                "The requested transition is not valid for the exact active application",
            )
        }
        return release
    }

    private fun OmiCv1PreparedApplicationUpdate.toReview() = OmiCv1FlashLabReview(
        operation = OmiCv1FlashLabOperation.APPLICATION_IMAGE_0,
        releaseId = release.releaseId,
        intent = release.intent,
        uploadMode = release.uploadMode,
        sourceApplicationHash = release.source.applicationHash.hex,
        targetIdentity = release.target.identity,
        targetFileSha256 = artifactEvidence.fileSha256.hex,
        targetImageHash = artifactEvidence.mcubootImageHash.hex,
        targetImageNumber = OmiCv1ApplicationUpdatePlanner.APPLICATION_IMAGE_NUMBER,
        networkPolicy = when (release.source.networkEvidencePolicy) {
            OmiCv1NetworkImageEvidencePolicy.REQUIRE_EXACT_ACTIVE ->
                "Require exact published image 1"

            OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED ->
                "Image 1 may be wholly unobserved; any visible row must match exact stock"
        },
    )

    private fun OmiCv1PreparedStockNormalization.toReview() = OmiCv1FlashLabReview(
        operation = OmiCv1FlashLabOperation.OFFICIAL_STOCK_DUAL_IMAGE_NORMALIZATION,
        releaseId = release.releaseId,
        intent = null,
        uploadMode = null,
        sourceApplicationHash = release.sourceApplication.mcubootImageHash.hex,
        targetIdentity = release.targetApplication.identity,
        targetFileSha256 = applicationEvidence.fileSha256.hex,
        targetImageHash = applicationEvidence.mcubootImageHash.hex,
        targetImageNumber = OmiCv1StockNormalizationPlanner.APPLICATION_IMAGE_NUMBER,
        networkPolicy =
            "Upload and confirm the exact official v3.0.12 application and network images " +
                "before one reset; resume accepts only those exact secondary hashes",
        targetNetworkIdentity = release.targetNetwork.identity,
        targetNetworkFileSha256 = networkEvidence.fileSha256.hex,
        targetNetworkImageHash = networkEvidence.mcubootImageHash.hex,
    )

    private fun OmiCv1PreparedInactiveApplicationSlotErase.toReview(
        networkEvidencePolicy: OmiCv1NetworkImageEvidencePolicy,
    ) = OmiCv1FlashLabReview(
        operation = OmiCv1FlashLabOperation.INACTIVE_APPLICATION_SLOT_ERASE,
        releaseId = OmiCv1InactiveApplicationSlotErasePlanner.RELEASE_ID,
        intent = null,
        uploadMode = null,
        sourceApplicationHash = expectedActiveApplicationHash.hex,
        targetIdentity = "inactive application image 0 slot 1",
        targetFileSha256 = null,
        targetImageHash = null,
        targetImageNumber = OmiCv1ApplicationUpdatePlanner.APPLICATION_IMAGE_NUMBER,
        targetSlotNumber = OmiCv1ApplicationUpdatePlanner.SECONDARY_SLOT_NUMBER,
        networkPolicy = when (networkEvidencePolicy) {
            OmiCv1NetworkImageEvidencePolicy.REQUIRE_EXACT_ACTIVE ->
                "Require exact published image 1"

            OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED ->
                "Image 1 may be wholly unobserved; any visible row must match exact stock"
        },
    )

    private fun intentForTarget(hash: FirmwareImageHash): OmiCv1ApplicationUpdateIntent = when (hash) {
        OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY

        OmiCv1StockV3012ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY

        OmiCv1CapturePortSelftest0001ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST

        OmiCv1RecordingRootProvisioner0001ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.RECORDING_ROOT_PROVISIONER

        OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER

        OmiCv1FunctionalRecording0003ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING

        OmiCv1FunctionalRecording0004ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING

        OmiCv1FunctionalRecording0005ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING

        OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING

        OmiCv1FunctionalRecording0007ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING

        else -> throw OmiCv1ApplicationUpdateException(
            OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
            "Validated target is outside the exact application release catalog",
        )
    }

    private fun stopOnFailure(detail: String) {
        scanJob?.cancel()
        preparedPlan = null
        preparedNormalization = null
        mutableState.update {
            it.copy(
                phase = OmiCv1FlashLabPhase.STOPPED_ON_FAILURE,
                review = null,
                checklist = it.checklist.copy(exactArtifactAuthorized = false),
                error = detail,
            )
        }
    }

    private fun Throwable.safeDetail(fallback: String): String = when (this) {
        is OmiCv1ApplicationUpdateException -> "$fallback (${code.name}): ${message ?: "rejected"}"
        else -> "$fallback (${this::class.simpleName ?: "error"})"
    }

    private fun OmiCv1FlashLabPhase.isMutating(): Boolean = this in setOf(
        OmiCv1FlashLabPhase.UPDATING,
        OmiCv1FlashLabPhase.VALIDATING_POST_REBOOT,
        OmiCv1FlashLabPhase.READING_ACTIVE_CAPTURE_SELFTEST,
        OmiCv1FlashLabPhase.READING_ACTIVE_FUNCTIONAL,
        OmiCv1FlashLabPhase.RECHECKING_RECOVERY,
        OmiCv1FlashLabPhase.RECHECKING_FUNCTIONAL,
        OmiCv1FlashLabPhase.RUNNING_CAPTURE_SELFTEST,
        OmiCv1FlashLabPhase.AWAITING_CAPTURE_CONFIRMATION,
    )

    private companion object {
        const val AUTHORIZATION_LIFETIME_MILLIS = 120_000L
        const val RECOVERY_OBSERVATION_MILLIS = 600_000L
    }
}
