package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import java.util.concurrent.atomic.AtomicBoolean

internal enum class OmiCv1ApplicationUpdateIntent {
    RECOVERY_ONLY,
    STOCK_RECOVERY,
    CAPTURE_PORT_SELFTEST,
    RECORDING_ROOT_PROVISIONER,
    LEGACY_STORAGE_RECLAIMER,
    FUNCTIONAL_RECORDING,
}

internal enum class OmiCv1ApplicationUploadMode {
    STANDARD,

    /**
     * Escape hatch for functional-recording-0001 only.
     *
     * That image never initializes PSA when the consumer unit has no provisioned HUK, but its
     * MCUmgr pre/post-upload SHA checks use PSA. The rescue transport therefore writes an exact
     * signed image plus erased-value alignment bytes while advertising one additional byte that
     * it deliberately never sends. Exact MCUboot slot-state inspection is still required before
     * confirm/reset.
     */
    INCOMPLETE_FLASH_BLOCK_RESCUE,
}

internal enum class OmiCv1NetworkImageEvidencePolicy {
    /** Require the published active network image and an empty observable secondary slot. */
    REQUIRE_EXACT_ACTIVE,

    /**
     * Accept no image-1 rows because stock v3.0.12 may not expose the network-core flash header.
     * If any network row is visible, it must still match the exact published image and stable state.
     */
    ALLOW_COMPLETELY_UNOBSERVED,
}

internal data class OmiCv1McubootArtifactManifest(
    val identity: String,
    val fileSizeBytes: Int,
    val fileSha256: FirmwareImageHash,
    val payloadSizeBytes: Int,
    val mcubootImageHash: FirmwareImageHash,
    val compatibilityKeyHash: FirmwareImageHash,
    val mcubootVersion: String,
    val signatureVerifiedOffline: Boolean,
) {
    init {
        require(identity.isNotBlank()) { "MCUboot artifact identity must not be blank" }
        require(fileSizeBytes > 0) { "MCUboot artifact must not be empty" }
        require(payloadSizeBytes > 0 && payloadSizeBytes < fileSizeBytes) {
            "MCUboot payload size must fit inside its image"
        }
        require(mcubootVersion.isNotBlank()) { "MCUboot version must not be blank" }
        require(signatureVerifiedOffline) { "Only an independently verified artifact may be planned" }
        fileSha256.requireSha256("MCUboot file digest")
        mcubootImageHash.requireSha256("MCUboot image digest")
        compatibilityKeyHash.requireSha256("Compatibility key digest")
    }
}

/** Compatibility name retained for the closed application-image-0 update workflow. */
internal typealias OmiCv1ApplicationArtifactManifest = OmiCv1McubootArtifactManifest

internal data class OmiCv1McubootArtifactEvidence(
    val fileSizeBytes: Int,
    val fileSha256: FirmwareImageHash,
    val headerSizeBytes: Int,
    val payloadSizeBytes: Int,
    val mcubootImageHash: FirmwareImageHash,
    val compatibilityKeyHash: FirmwareImageHash,
    val mcubootVersion: String,
) {
    init {
        fileSha256.requireSha256("Observed MCUboot file digest")
        mcubootImageHash.requireSha256("Observed MCUboot image digest")
        compatibilityKeyHash.requireSha256("Observed compatibility key digest")
    }
}

/** Compatibility name retained for the closed application-image-0 update workflow. */
internal typealias OmiCv1ApplicationArtifactEvidence = OmiCv1McubootArtifactEvidence

internal data class OmiCv1ExpectedActiveImages(
    val applicationHash: FirmwareImageHash,
    val networkHash: FirmwareImageHash,
    val mcubootVersion: String,
    val networkEvidencePolicy: OmiCv1NetworkImageEvidencePolicy =
        OmiCv1NetworkImageEvidencePolicy.REQUIRE_EXACT_ACTIVE,
) {
    init {
        applicationHash.requireSha256("Expected application image digest")
        networkHash.requireSha256("Expected network image digest")
        require(mcubootVersion.isNotBlank()) { "Expected MCUboot version must not be blank" }
    }
}

internal data class OmiCv1ApplicationUpdateRelease(
    val releaseId: String,
    val intent: OmiCv1ApplicationUpdateIntent,
    val source: OmiCv1ExpectedActiveImages,
    val sourceManufacturer: String,
    val target: OmiCv1ApplicationArtifactManifest,
    val uploadMode: OmiCv1ApplicationUploadMode = OmiCv1ApplicationUploadMode.STANDARD,
) {
    init {
        require(releaseId.isNotBlank()) { "Application update release ID must not be blank" }
        require(sourceManufacturer.isNotBlank()) {
            "Application update source manufacturer must not be blank"
        }
        require(source.mcubootVersion == target.mcubootVersion) {
            "Compatibility update must retain the installed MCUboot version"
        }
        require(source.applicationHash != target.mcubootImageHash) {
            "Application update target must differ from the active application hash"
        }
    }
}

internal class OmiCv1PreparedApplicationUpdate internal constructor(
    val planId: String,
    val endpoint: EndpointCandidate,
    val release: OmiCv1ApplicationUpdateRelease,
    val artifactEvidence: OmiCv1ApplicationArtifactEvidence,
    imageBytes: ByteArray,
) {
    private val immutableImageBytes = imageBytes.copyOf()

    fun copyImageBytes(): ByteArray = immutableImageBytes.copyOf()
}

/**
 * Process-local capability minted only after a future owner-review UI validates [planId].
 *
 * No production authorizer exists yet and the entire updater module is absent from the shell graph.
 */
internal class OmiCv1ApplicationUpdateAuthorization internal constructor(
    val plan: OmiCv1PreparedApplicationUpdate,
    val planId: String,
    val expiresAtMonotonicMillis: Long,
) {
    private val consumed = AtomicBoolean(false)

    init {
        require(plan.planId == planId) { "Owner authorization is bound to a different update plan" }
        require(expiresAtMonotonicMillis > 0) { "Owner authorization expiry must be positive" }
    }

    fun consume(nowMonotonicMillis: Long): OmiCv1PreparedApplicationUpdate {
        if (!consumed.compareAndSet(false, true)) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.AUTHORIZATION_REUSED,
                "Owner authorization has already been consumed",
            )
        }
        if (nowMonotonicMillis >= expiresAtMonotonicMillis) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.AUTHORIZATION_EXPIRED,
                "Owner authorization expired before the update began",
            )
        }
        return plan
    }
}

internal enum class OmiCv1ApplicationUpdateStage {
    PREPARING,
    VERIFYING_PREFLIGHT_STATE,
    UPLOADING,
    VERIFYING_STAGED_STATE,
    CONFIRMING,
    VERIFYING_CONFIRMED_STATE,
    REQUESTING_REBOOT,
    AWAITING_POST_REBOOT_VALIDATION,
}

internal data class OmiCv1ApplicationUpdateProgress(
    val stage: OmiCv1ApplicationUpdateStage,
    val bytesSent: Int? = null,
    val totalBytes: Int? = null,
) {
    init {
        require(bytesSent == null || bytesSent >= 0) { "Uploaded byte count must be non-negative" }
        require(totalBytes == null || totalBytes > 0) { "Upload total must be positive" }
        require(bytesSent == null || totalBytes == null || bytesSent <= totalBytes) {
            "Uploaded byte count must not exceed total bytes"
        }
    }
}

internal data class OmiCv1ApplicationUpdatePendingValidation(
    val planId: String,
    val endpoint: EndpointCandidate,
    val expectedApplicationHash: FirmwareImageHash,
    val expectedNetworkHash: FirmwareImageHash,
    val expectedMcubootVersion: String,
    val networkEvidencePolicy: OmiCv1NetworkImageEvidencePolicy,
    val resetResponseObserved: Boolean,
)

internal data class OmiCv1ApplicationUpdateValidation(
    val planId: String,
    val applicationHash: FirmwareImageHash,
    val networkImageObserved: Boolean,
    val recoveryStatus: OmiCv1RecoveryStatusEvidence? = null,
    val captureSelftestStatus: OmiCv1CaptureSelftestEvidence? = null,
    val recordingRootProvisionerStatus: OmiCv1RecordingRootProvisionerStatusEvidence? = null,
    val legacyStorageReclaimerStatus: OmiCv1LegacyStorageReclaimerStatusEvidence? = null,
    val functionalStatus: OmiCv1FunctionalStatusEvidence? = null,
)

internal enum class OmiCv1ApplicationUpdateFailureCode {
    AUTHORIZATION_EXPIRED,
    AUTHORIZATION_REUSED,
    ENDPOINT_MISMATCH,
    PRECONDITION_FAILED,
    ARTIFACT_REJECTED,
    STAGED_STATE_REJECTED,
    CONFIRMED_STATE_REJECTED,
    PERMISSION_DENIED,
    BLUETOOTH_UNAVAILABLE,
    ENDPOINT_EXPIRED,
    RECOVERY_EVIDENCE_REJECTED,
    CAPTURE_SELFTEST_EVIDENCE_REJECTED,
    RECORDING_ROOT_PROVISIONER_EVIDENCE_REJECTED,
    LEGACY_STORAGE_RECLAIMER_EVIDENCE_REJECTED,
    FUNCTIONAL_EVIDENCE_REJECTED,
    STOCK_NORMALIZATION_STATE_REJECTED,
    STOCK_NORMALIZATION_FAILED,
    TRANSPORT_FAILED,
}

internal class OmiCv1ApplicationUpdateException(
    val code: OmiCv1ApplicationUpdateFailureCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private fun FirmwareImageHash.requireSha256(label: String) {
    require(hex.length == 64) { "$label must be exactly 32 bytes" }
}
