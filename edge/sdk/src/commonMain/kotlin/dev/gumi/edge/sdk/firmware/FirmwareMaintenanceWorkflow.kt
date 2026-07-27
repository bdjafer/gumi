package dev.gumi.edge.sdk.firmware

/**
 * Device-neutral identity for one immutable firmware artifact.
 *
 * The digest, not a display name or version, is the authorization boundary.
 */
data class FirmwareArtifactIdentity(
    val artifactId: String,
    val version: String,
    val sha256: String,
    val imageNumber: Int,
    val byteCount: ULong? = null,
) {
    init {
        requireOpaque("Firmware artifact ID", artifactId)
        requireOpaque("Firmware artifact version", version)
        require(SHA256.matches(sha256)) {
            "Firmware artifact SHA-256 must be 64 lowercase hexadecimal characters"
        }
        require(imageNumber >= 0) { "Firmware image number must be non-negative" }
        require(byteCount == null || byteCount > 0uL) {
            "Firmware artifact byte count must be positive when known"
        }
    }
}

enum class FirmwareMaintenanceStage {
    SAFETY_REVIEW,
    PREFLIGHT,
    AWAITING_AUTHORIZATION,
    READY_TO_APPLY,
    APPLYING,
    AWAITING_RESTART,
    VALIDATING,
    COMPLETE,
    FAILED,
}

data class FirmwareSafetyReviewReceipt(
    val checklistVersion: String,
    val acceptedAtMonotonicMillis: Long,
) {
    init {
        requireOpaque("Firmware safety checklist version", checklistVersion)
        require(acceptedAtMonotonicMillis >= 0)
    }
}

data class FirmwarePreflightReceipt(
    /** Opaque evidence reference; never an endpoint address, credential, or raw device response. */
    val evidenceId: String,
    val artifactSha256: String,
    val observedAtMonotonicMillis: Long,
) {
    init {
        requireOpaque("Firmware preflight evidence ID", evidenceId)
        require(SHA256.matches(artifactSha256))
        require(observedAtMonotonicMillis >= 0)
    }
}

data class FirmwareArtifactAuthorization(
    val artifactId: String,
    val artifactSha256: String,
    val grantedAtMonotonicMillis: Long,
) {
    init {
        requireOpaque("Authorized firmware artifact ID", artifactId)
        require(SHA256.matches(artifactSha256))
        require(grantedAtMonotonicMillis >= 0)
    }
}

data class FirmwareValidationReceipt(
    val evidenceId: String,
    val validatedAtMonotonicMillis: Long,
) {
    init {
        requireOpaque("Firmware validation evidence ID", evidenceId)
        require(validatedAtMonotonicMillis >= 0)
    }
}

data class FirmwareMaintenanceFailure(
    val code: String,
    val outcomeMayBeUnknown: Boolean,
) {
    init {
        require(code.matches(FAILURE_CODE)) {
            "Firmware maintenance failure code must be a stable uppercase identifier"
        }
    }
}

/**
 * Portable application state for one explicit firmware-maintenance attempt.
 *
 * This type contains no BLE, MCUboot, Android, Omi, UI, or artifact-loading behavior. A device
 * adapter performs those effects and feeds their semantic results to [FirmwareMaintenanceWorkflow].
 */
data class FirmwareMaintenanceState(
    val artifact: FirmwareArtifactIdentity,
    val stage: FirmwareMaintenanceStage = FirmwareMaintenanceStage.SAFETY_REVIEW,
    val safetyReview: FirmwareSafetyReviewReceipt? = null,
    val preflight: FirmwarePreflightReceipt? = null,
    val authorization: FirmwareArtifactAuthorization? = null,
    val transferredBytes: ULong = 0uL,
    val validation: FirmwareValidationReceipt? = null,
    val failure: FirmwareMaintenanceFailure? = null,
) {
    init {
        require(transferredBytes <= (artifact.byteCount ?: ULong.MAX_VALUE)) {
            "Firmware transfer progress exceeds the immutable artifact size"
        }
        if (stage in REVIEWED_STAGES) {
            require(safetyReview != null) { "Firmware preflight requires accepted safety review" }
        }
        if (
            stage in setOf(
                FirmwareMaintenanceStage.AWAITING_AUTHORIZATION,
                FirmwareMaintenanceStage.READY_TO_APPLY,
                FirmwareMaintenanceStage.APPLYING,
                FirmwareMaintenanceStage.AWAITING_RESTART,
                FirmwareMaintenanceStage.VALIDATING,
                FirmwareMaintenanceStage.COMPLETE,
            )
        ) {
            require(preflight?.artifactSha256 == artifact.sha256) {
                "Firmware maintenance requires preflight for the exact artifact"
            }
        }
        if (
            stage in setOf(
                FirmwareMaintenanceStage.READY_TO_APPLY,
                FirmwareMaintenanceStage.APPLYING,
                FirmwareMaintenanceStage.AWAITING_RESTART,
                FirmwareMaintenanceStage.VALIDATING,
                FirmwareMaintenanceStage.COMPLETE,
            )
        ) {
            require(
                authorization?.artifactId == artifact.artifactId &&
                    authorization.artifactSha256 == artifact.sha256,
            ) { "Firmware maintenance requires authorization for the exact artifact" }
        }
        require((stage == FirmwareMaintenanceStage.COMPLETE) == (validation != null)) {
            "Only completed firmware maintenance carries validation evidence"
        }
        require((stage == FirmwareMaintenanceStage.FAILED) == (failure != null)) {
            "Only failed firmware maintenance carries failure evidence"
        }
        if (safetyReview != null && preflight != null) {
            require(preflight.observedAtMonotonicMillis >= safetyReview.acceptedAtMonotonicMillis) {
                "Firmware preflight evidence cannot predate safety review"
            }
        }
        if (preflight != null && authorization != null) {
            require(authorization.grantedAtMonotonicMillis >= preflight.observedAtMonotonicMillis) {
                "Firmware authorization cannot predate preflight"
            }
        }
        if (authorization != null && validation != null) {
            require(validation.validatedAtMonotonicMillis >= authorization.grantedAtMonotonicMillis) {
                "Firmware validation cannot predate authorization"
            }
        }
    }

    private companion object {
        val REVIEWED_STAGES = setOf(
            FirmwareMaintenanceStage.PREFLIGHT,
            FirmwareMaintenanceStage.AWAITING_AUTHORIZATION,
            FirmwareMaintenanceStage.READY_TO_APPLY,
            FirmwareMaintenanceStage.APPLYING,
            FirmwareMaintenanceStage.AWAITING_RESTART,
            FirmwareMaintenanceStage.VALIDATING,
            FirmwareMaintenanceStage.COMPLETE,
        )
    }
}

sealed interface FirmwareMaintenanceEvent {
    data class SafetyReviewAccepted(
        val receipt: FirmwareSafetyReviewReceipt,
    ) : FirmwareMaintenanceEvent

    data class PreflightPassed(
        val receipt: FirmwarePreflightReceipt,
    ) : FirmwareMaintenanceEvent

    data class ExactArtifactAuthorized(
        val authorization: FirmwareArtifactAuthorization,
    ) : FirmwareMaintenanceEvent

    data object ApplyStarted : FirmwareMaintenanceEvent

    data class TransferAdvanced(val transferredBytes: ULong) : FirmwareMaintenanceEvent

    data object ApplyAcceptedForRestart : FirmwareMaintenanceEvent

    data object ValidationStarted : FirmwareMaintenanceEvent

    data class ValidationPassed(
        val receipt: FirmwareValidationReceipt,
    ) : FirmwareMaintenanceEvent

    data class Failed(val failure: FirmwareMaintenanceFailure) : FirmwareMaintenanceEvent
}

sealed interface FirmwareMaintenanceTransition {
    data class Accepted(val state: FirmwareMaintenanceState) : FirmwareMaintenanceTransition

    data class Rejected(val state: FirmwareMaintenanceState, val reasonCode: String) :
        FirmwareMaintenanceTransition
}

/** Pure reducer shared by mobile, headless edge hosts, diagnostic tools, and future product UI. */
object FirmwareMaintenanceWorkflow {
    fun reduce(
        state: FirmwareMaintenanceState,
        event: FirmwareMaintenanceEvent,
    ): FirmwareMaintenanceTransition {
        if (state.stage in TERMINAL_STAGES) return rejected(state, "MAINTENANCE_ATTEMPT_TERMINAL")
        return when (event) {
            is FirmwareMaintenanceEvent.SafetyReviewAccepted ->
                if (state.stage == FirmwareMaintenanceStage.SAFETY_REVIEW) {
                    accepted(
                        state.copy(
                            stage = FirmwareMaintenanceStage.PREFLIGHT,
                            safetyReview = event.receipt,
                        ),
                    )
                } else {
                    rejected(state, "SAFETY_REVIEW_NOT_EXPECTED")
                }

            is FirmwareMaintenanceEvent.PreflightPassed ->
                if (state.stage != FirmwareMaintenanceStage.PREFLIGHT) {
                    rejected(state, "PREFLIGHT_NOT_EXPECTED")
                } else if (event.receipt.artifactSha256 != state.artifact.sha256) {
                    rejected(state, "PREFLIGHT_ARTIFACT_MISMATCH")
                } else if (
                    event.receipt.observedAtMonotonicMillis <
                    requireNotNull(state.safetyReview).acceptedAtMonotonicMillis
                ) {
                    rejected(state, "PREFLIGHT_TIME_REGRESSED")
                } else {
                    accepted(
                        state.copy(
                            stage = FirmwareMaintenanceStage.AWAITING_AUTHORIZATION,
                            preflight = event.receipt,
                        ),
                    )
                }

            is FirmwareMaintenanceEvent.ExactArtifactAuthorized ->
                if (state.stage != FirmwareMaintenanceStage.AWAITING_AUTHORIZATION) {
                    rejected(state, "ARTIFACT_AUTHORIZATION_NOT_EXPECTED")
                } else if (
                    event.authorization.artifactId != state.artifact.artifactId ||
                    event.authorization.artifactSha256 != state.artifact.sha256
                ) {
                    rejected(state, "AUTHORIZED_ARTIFACT_MISMATCH")
                } else if (
                    event.authorization.grantedAtMonotonicMillis <
                    requireNotNull(state.preflight).observedAtMonotonicMillis
                ) {
                    rejected(state, "ARTIFACT_AUTHORIZATION_TIME_REGRESSED")
                } else {
                    accepted(
                        state.copy(
                            stage = FirmwareMaintenanceStage.READY_TO_APPLY,
                            authorization = event.authorization,
                        ),
                    )
                }

            FirmwareMaintenanceEvent.ApplyStarted ->
                move(
                    state,
                    FirmwareMaintenanceStage.READY_TO_APPLY,
                    FirmwareMaintenanceStage.APPLYING,
                    "APPLY_NOT_READY",
                )

            is FirmwareMaintenanceEvent.TransferAdvanced ->
                when {
                    state.stage != FirmwareMaintenanceStage.APPLYING ->
                        rejected(state, "TRANSFER_NOT_ACTIVE")
                    event.transferredBytes < state.transferredBytes ->
                        rejected(state, "TRANSFER_PROGRESS_REGRESSED")
                    event.transferredBytes > (state.artifact.byteCount ?: ULong.MAX_VALUE) ->
                        rejected(state, "TRANSFER_PROGRESS_EXCEEDS_ARTIFACT")
                    else -> accepted(state.copy(transferredBytes = event.transferredBytes))
                }

            FirmwareMaintenanceEvent.ApplyAcceptedForRestart -> when {
                state.stage != FirmwareMaintenanceStage.APPLYING ->
                    rejected(state, "APPLY_NOT_ACTIVE")
                state.artifact.byteCount != null &&
                    state.transferredBytes != state.artifact.byteCount ->
                    rejected(state, "TRANSFER_INCOMPLETE")
                else -> accepted(state.copy(stage = FirmwareMaintenanceStage.AWAITING_RESTART))
            }

            FirmwareMaintenanceEvent.ValidationStarted ->
                move(
                    state,
                    FirmwareMaintenanceStage.AWAITING_RESTART,
                    FirmwareMaintenanceStage.VALIDATING,
                    "VALIDATION_NOT_READY",
                )

            is FirmwareMaintenanceEvent.ValidationPassed ->
                if (state.stage != FirmwareMaintenanceStage.VALIDATING) {
                    rejected(state, "VALIDATION_NOT_ACTIVE")
                } else if (
                    event.receipt.validatedAtMonotonicMillis <
                    requireNotNull(state.authorization).grantedAtMonotonicMillis
                ) {
                    rejected(state, "VALIDATION_TIME_REGRESSED")
                } else {
                    accepted(
                        state.copy(
                            stage = FirmwareMaintenanceStage.COMPLETE,
                            validation = event.receipt,
                        ),
                    )
                }

            is FirmwareMaintenanceEvent.Failed -> accepted(
                state.copy(
                    stage = FirmwareMaintenanceStage.FAILED,
                    failure = event.failure,
                ),
            )
        }
    }

    private fun move(
        state: FirmwareMaintenanceState,
        expected: FirmwareMaintenanceStage,
        next: FirmwareMaintenanceStage,
        rejection: String,
    ): FirmwareMaintenanceTransition =
        if (state.stage == expected) accepted(state.copy(stage = next))
        else rejected(state, rejection)

    private fun accepted(state: FirmwareMaintenanceState) =
        FirmwareMaintenanceTransition.Accepted(state)

    private fun rejected(state: FirmwareMaintenanceState, code: String) =
        FirmwareMaintenanceTransition.Rejected(state, code)

    private val TERMINAL_STAGES = setOf(
        FirmwareMaintenanceStage.COMPLETE,
        FirmwareMaintenanceStage.FAILED,
    )
}

private val SHA256 = Regex("^[0-9a-f]{64}$")
private val FAILURE_CODE = Regex("^[A-Z][A-Z0-9_]{1,95}$")

private fun requireOpaque(label: String, value: String) {
    require(value.isNotBlank()) { "$label cannot be blank" }
    require(value == value.trim()) { "$label cannot have surrounding whitespace" }
    require(value.length <= 200) { "$label cannot exceed 200 characters" }
    require(value.none(Char::isISOControl)) { "$label cannot contain control characters" }
}
