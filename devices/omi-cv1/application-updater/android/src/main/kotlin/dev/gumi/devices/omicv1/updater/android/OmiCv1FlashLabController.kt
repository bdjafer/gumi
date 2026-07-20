package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
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
    VALIDATED,
    STOPPED_ON_FAILURE,
}

internal data class OmiCv1FlashLabChecklist(
    val omiBatteryAtLeast80: Boolean = false,
    val officialOmiAppStopped: Boolean = false,
    val chargerAvailable: Boolean = false,
    val noRollbackRiskAccepted: Boolean = false,
    val exactArtifactAuthorized: Boolean = false,
    val externalCanaryChecksComplete: Boolean = false,
) {
    val preflightComplete: Boolean get() =
        omiBatteryAtLeast80 &&
            officialOmiAppStopped &&
            chargerAvailable &&
            noRollbackRiskAccepted
}

internal data class OmiCv1FlashLabReview(
    val releaseId: String,
    val intent: OmiCv1ApplicationUpdateIntent,
    val sourceApplicationHash: String,
    val targetIdentity: String,
    val targetFileSha256: String,
    val targetImageHash: String,
    val targetImageNumber: Int,
    val networkPolicy: String,
)

internal data class OmiCv1FlashLabUiState(
    val phase: OmiCv1FlashLabPhase = OmiCv1FlashLabPhase.SAFETY_REVIEW,
    val permissionsGranted: Boolean = false,
    val phonePower: OmiCv1FlashLabPhonePower = OmiCv1FlashLabPhonePower(null, false),
    val checklist: OmiCv1FlashLabChecklist = OmiCv1FlashLabChecklist(),
    val candidates: List<OmiCv1FlashLabCandidate> = emptyList(),
    val selected: OmiCv1FlashLabCandidate? = null,
    val review: OmiCv1FlashLabReview? = null,
    val progress: OmiCv1ApplicationUpdateProgress? = null,
    val pendingValidation: OmiCv1ApplicationUpdatePendingValidation? = null,
    val validation: OmiCv1ApplicationUpdateValidation? = null,
    val completedIntent: OmiCv1ApplicationUpdateIntent? = null,
    val resetResponseObserved: Boolean? = null,
    val error: String? = null,
) {
    val readyForPreflight: Boolean get() =
        permissionsGranted && phonePower.adequateForUpdate && checklist.preflightComplete
}

internal class OmiCv1FlashLabController(
    private val scanner: OmiCv1FlashLabScanner,
    private val sessions: OmiCv1ApplicationImage0UpdateSessionFactory,
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
        val awaitingPostReboot = current.pendingValidation != null && current.validation == null
        mutableState.update {
            it.copy(
                phase = OmiCv1FlashLabPhase.SCANNING,
                candidates = emptyList(),
                selected = null,
                review = null,
                progress = null,
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
                    phase = if (it.pendingValidation != null) {
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
        val pending = current.pendingValidation
        if (pending != null && candidate.endpoint != pending.endpoint) {
            mutableState.update {
                it.copy(error = "This is not the same process-local Omi endpoint; do not continue")
            }
            return
        }
        scanJob?.cancel()
        scanJob = null
        mutableState.update {
            it.copy(
                phase = if (pending == null) {
                    OmiCv1FlashLabPhase.DEVICE_SELECTED
                } else {
                    OmiCv1FlashLabPhase.POST_REBOOT_DEVICE_SELECTED
                },
                selected = candidate,
                error = null,
            )
        }
    }

    fun runPreflight() {
        val current = mutableState.value
        val selected = current.selected ?: return
        if (current.phase != OmiCv1FlashLabPhase.DEVICE_SELECTED) return
        val refreshedPower = phonePower.read()
        if (!current.copy(phonePower = refreshedPower).readyForPreflight) {
            mutableState.update {
                it.copy(
                    phonePower = refreshedPower,
                    error = "Complete every safety check and charge the phone to at least $MIN_PHONE_BATTERY_PERCENT%",
                )
            }
            return
        }
        if (operationJob?.isActive == true) return
        mutableState.update {
            it.copy(
                phase = OmiCv1FlashLabPhase.READING_PREFLIGHT,
                phonePower = refreshedPower,
                error = null,
            )
        }
        operationJob = scope.launch {
            try {
                val inspection = inspectOnce(selected)
                val release = releaseFor(inspection)
                val imageBytes = artifacts.load(release.intent)
                val plan = OmiCv1ApplicationUpdatePlanner.prepare(
                    selected.endpoint,
                    inspection,
                    release,
                    imageBytes,
                )
                preparedPlan = plan
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

    fun authorizeAndExecute() {
        val current = mutableState.value
        val plan = preparedPlan ?: return
        if (current.phase != OmiCv1FlashLabPhase.READY_TO_AUTHORIZE) return
        if (!current.checklist.exactArtifactAuthorized) {
            mutableState.update { it.copy(error = "Acknowledge the exact artifact before authorization") }
            return
        }
        val refreshedPower = phonePower.read()
        if (!refreshedPower.adequateForUpdate || !current.checklist.preflightComplete) {
            preparedPlan = null
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
        val authorization = OmiCv1ApplicationUpdateAuthorization(
            plan = plan,
            planId = plan.planId,
            expiresAtMonotonicMillis = clock.now() + AUTHORIZATION_LIFETIME_MILLIS,
        )
        mutableState.update {
            it.copy(
                phase = OmiCv1FlashLabPhase.UPDATING,
                phonePower = refreshedPower,
                progress = OmiCv1ApplicationUpdateProgress(OmiCv1ApplicationUpdateStage.PREPARING),
                error = null,
            )
        }
        operationJob = scope.launch {
            try {
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
                        checklist = it.checklist.copy(
                            exactArtifactAuthorized = false,
                            externalCanaryChecksComplete = false,
                        ),
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                stopOnFailure("Update canceled; do not retry without reviewing fresh image state")
                throw error
            } catch (error: Throwable) {
                stopOnFailure(error.safeDetail("Application image-0 update stopped"))
            } finally {
                operationJob = null
            }
        }
    }

    fun validatePostReboot() {
        val current = mutableState.value
        val selected = current.selected ?: return
        val pending = current.pendingValidation ?: return
        if (current.phase != OmiCv1FlashLabPhase.POST_REBOOT_DEVICE_SELECTED) return
        if (selected.endpoint != pending.endpoint) {
            stopOnFailure("Post-reboot endpoint identity changed; do not continue")
            return
        }
        if (operationJob?.isActive == true) return
        mutableState.update {
            it.copy(phase = OmiCv1FlashLabPhase.VALIDATING_POST_REBOOT, error = null)
        }
        operationJob = scope.launch {
            try {
                val inspection = inspectOnce(selected)
                val validation = OmiCv1ApplicationUpdatePlanner.validatePostRebootState(
                    inspection,
                    pending,
                )
                val completedIntent = intentForTarget(validation.applicationHash)
                mutableState.update {
                    it.copy(
                        phase = OmiCv1FlashLabPhase.VALIDATED,
                        validation = validation,
                        completedIntent = completedIntent,
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
        if (
            current.completedIntent == OmiCv1ApplicationUpdateIntent.MINIMAL_CANARY &&
            !current.checklist.externalCanaryChecksComplete
        ) {
            mutableState.update {
                it.copy(error = "Complete and acknowledge the canary GATT/audio/indicator checks first")
            }
            return
        }
        preparedPlan = null
        mutableState.value = current.copy(
            phase = OmiCv1FlashLabPhase.SAFETY_REVIEW,
            candidates = emptyList(),
            selected = null,
            review = null,
            progress = null,
            pendingValidation = null,
            validation = null,
            resetResponseObserved = null,
            checklist = current.checklist.copy(
                exactArtifactAuthorized = false,
                externalCanaryChecksComplete = false,
            ),
            error = null,
        )
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
        return when (activeApplication.single().hash?.hex) {
            OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH ->
                OmiCv1V3012ApplicationUpdateCatalog.stockToCanary0001

            OmiCv1Canary0001ApplicationArtifact.manifest.mcubootImageHash.hex ->
                OmiCv1V3012ApplicationUpdateCatalog.canary0001ToStock

            else -> throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
                "The active application is neither exact stock v3.0.12 nor canary-0001",
            )
        }
    }

    private fun OmiCv1PreparedApplicationUpdate.toReview() = OmiCv1FlashLabReview(
        releaseId = release.releaseId,
        intent = release.intent,
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

    private fun intentForTarget(hash: FirmwareImageHash): OmiCv1ApplicationUpdateIntent = when (hash) {
        OmiCv1Canary0001ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.MINIMAL_CANARY

        OmiCv1StockV3012ApplicationArtifact.manifest.mcubootImageHash ->
            OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY

        else -> throw OmiCv1ApplicationUpdateException(
            OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
            "Validated target is outside the two qualified application transitions",
        )
    }

    private fun stopOnFailure(detail: String) {
        scanJob?.cancel()
        preparedPlan = null
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
    )

    private companion object {
        const val AUTHORIZATION_LIFETIME_MILLIS = 120_000L
    }
}
