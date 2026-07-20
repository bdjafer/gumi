package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import java.util.concurrent.atomic.AtomicBoolean

internal enum class OmiCv1ApplicationUpdateIntent {
    MINIMAL_CANARY,
    STOCK_RECOVERY,
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

internal data class OmiCv1ApplicationArtifactManifest(
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
        require(identity.isNotBlank()) { "Application artifact identity must not be blank" }
        require(fileSizeBytes > 0) { "Application artifact must not be empty" }
        require(payloadSizeBytes > 0 && payloadSizeBytes < fileSizeBytes) {
            "Application payload size must fit inside its image"
        }
        require(mcubootVersion.isNotBlank()) { "MCUboot version must not be blank" }
        require(signatureVerifiedOffline) { "Only an independently verified artifact may be planned" }
        fileSha256.requireSha256("Application file digest")
        mcubootImageHash.requireSha256("MCUboot image digest")
        compatibilityKeyHash.requireSha256("Compatibility key digest")
    }
}

internal data class OmiCv1ApplicationArtifactEvidence(
    val fileSizeBytes: Int,
    val fileSha256: FirmwareImageHash,
    val headerSizeBytes: Int,
    val payloadSizeBytes: Int,
    val mcubootImageHash: FirmwareImageHash,
    val compatibilityKeyHash: FirmwareImageHash,
    val mcubootVersion: String,
) {
    init {
        fileSha256.requireSha256("Observed application file digest")
        mcubootImageHash.requireSha256("Observed MCUboot image digest")
        compatibilityKeyHash.requireSha256("Observed compatibility key digest")
    }
}

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
    val target: OmiCv1ApplicationArtifactManifest,
) {
    init {
        require(releaseId.isNotBlank()) { "Application update release ID must not be blank" }
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
