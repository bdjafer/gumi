package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1StockV3007FirmwareIdentity
import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

internal data class OmiCv1StockNormalizationRelease(
    val releaseId: String,
    val sourceApplication: OmiCv1McubootArtifactManifest,
    val sourceNetwork: OmiCv1McubootArtifactManifest,
    val targetApplication: OmiCv1McubootArtifactManifest,
    val targetNetwork: OmiCv1McubootArtifactManifest,
) {
    init {
        require(releaseId.isNotBlank())
        val artifacts = listOf(
            sourceApplication,
            sourceNetwork,
            targetApplication,
            targetNetwork,
        )
        require(artifacts.map { it.compatibilityKeyHash }.distinct().size == 1) {
            "Stock normalization artifacts must share one MCUboot compatibility key"
        }
        require(artifacts.map { it.mcubootVersion }.distinct().size == 1) {
            "Stock normalization artifacts must retain the installed MCUboot version"
        }
        require(sourceApplication.mcubootImageHash != targetApplication.mcubootImageHash)
        require(sourceNetwork.mcubootImageHash != targetNetwork.mcubootImageHash)
    }
}

internal object OmiCv1StockNormalizationCatalog {
    val v3007ToV3012 = OmiCv1StockNormalizationRelease(
        releaseId = "official-omi-cv1-v3007-to-v3012-dual-image",
        sourceApplication = OmiCv1StockV3007ApplicationArtifact.manifest,
        sourceNetwork = OmiCv1StockV3007NetworkArtifact.manifest,
        targetApplication = OmiCv1StockV3012ApplicationArtifact.manifest,
        targetNetwork = OmiCv1StockV3012NetworkArtifact.manifest,
    )
}

internal class OmiCv1StockNormalizationArtifacts(
    applicationBytes: ByteArray,
    networkBytes: ByteArray,
) {
    private val immutableApplication = applicationBytes.copyOf()
    private val immutableNetwork = networkBytes.copyOf()

    fun copyApplication(): ByteArray = immutableApplication.copyOf()
    fun copyNetwork(): ByteArray = immutableNetwork.copyOf()
}

internal fun interface OmiCv1StockNormalizationArtifactSource {
    fun load(): OmiCv1StockNormalizationArtifacts
}

internal class OmiCv1PreparedStockNormalization internal constructor(
    val planId: String,
    val endpoint: EndpointCandidate,
    val release: OmiCv1StockNormalizationRelease,
    val applicationEvidence: OmiCv1McubootArtifactEvidence,
    val networkEvidence: OmiCv1McubootArtifactEvidence,
    private val artifacts: OmiCv1StockNormalizationArtifacts,
) {
    fun copyApplicationBytes(): ByteArray = artifacts.copyApplication()
    fun copyNetworkBytes(): ByteArray = artifacts.copyNetwork()
}

internal class OmiCv1StockNormalizationAuthorization internal constructor(
    val plan: OmiCv1PreparedStockNormalization,
    val planId: String,
    val expiresAtMonotonicMillis: Long,
) {
    private val consumed = AtomicBoolean(false)

    init {
        require(plan.planId == planId)
        require(expiresAtMonotonicMillis > 0)
    }

    fun consume(nowMonotonicMillis: Long): OmiCv1PreparedStockNormalization {
        if (!consumed.compareAndSet(false, true)) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.AUTHORIZATION_REUSED,
                "Stock normalization authorization has already been consumed",
            )
        }
        if (nowMonotonicMillis >= expiresAtMonotonicMillis) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.AUTHORIZATION_EXPIRED,
                "Stock normalization authorization expired before the update began",
            )
        }
        return plan
    }
}

internal enum class OmiCv1StockNormalizationStage {
    VERIFYING_PREFLIGHT,
    VALIDATING_REMOTE_STATE,
    UPLOADING,
    CONFIRMING,
    REQUESTING_REBOOT,
    AWAITING_POST_REBOOT_VALIDATION,
}

internal data class OmiCv1StockNormalizationProgress(
    val stage: OmiCv1StockNormalizationStage,
    val bytesSent: Int? = null,
    val totalBytes: Int? = null,
)

internal data class OmiCv1StockNormalizationPendingValidation(
    val planId: String,
    val endpoint: EndpointCandidate,
    val expectedApplicationHash: FirmwareImageHash,
    val expectedNetworkHash: FirmwareImageHash,
    val expectedFirmwareRevision: String,
)

internal data class OmiCv1StockNormalizationValidation(
    val planId: String,
    val applicationHash: FirmwareImageHash,
    val networkImageObserved: Boolean,
    val firmwareRevision: String,
)

internal object OmiCv1StockNormalizationPlanner {
    fun prepare(
        endpoint: EndpointCandidate,
        inspection: FirmwareImageStateInspection,
        deviceEvidence: OmiCv1FlashLabDevicePreflightEvidence,
        release: OmiCv1StockNormalizationRelease,
        artifacts: OmiCv1StockNormalizationArtifacts,
    ): OmiCv1PreparedStockNormalization {
        requireSourceOrExactResumeState(inspection, endpoint, release)
        requireSourceDeviceEvidence(deviceEvidence, endpoint)
        val applicationEvidence =
            McubootArtifactInspector.inspect(artifacts.copyApplication(), release.targetApplication)
        val networkEvidence =
            McubootArtifactInspector.inspect(artifacts.copyNetwork(), release.targetNetwork)
        val planId = listOf(
            release.releaseId,
            endpoint.transport.name,
            endpoint.ephemeralId,
            release.sourceApplication.mcubootImageHash.hex,
            release.sourceNetwork.mcubootImageHash.hex,
            applicationEvidence.fileSha256.hex,
            applicationEvidence.mcubootImageHash.hex,
            networkEvidence.fileSha256.hex,
            networkEvidence.mcubootImageHash.hex,
        ).joinToString("\u0000").encodeToByteArray().sha256Hex()
        return OmiCv1PreparedStockNormalization(
            planId,
            endpoint,
            release,
            applicationEvidence,
            networkEvidence,
            artifacts,
        )
    }

    fun requireExecutablePreflight(
        inspection: FirmwareImageStateInspection,
        deviceEvidence: OmiCv1FlashLabDevicePreflightEvidence,
        plan: OmiCv1PreparedStockNormalization,
    ) {
        requireSourceOrExactResumeState(inspection, plan.endpoint, plan.release)
        requireSourceDeviceEvidence(deviceEvidence, plan.endpoint)
    }

    fun validatePostReboot(
        inspection: FirmwareImageStateInspection,
        deviceEvidence: OmiCv1FlashLabDevicePreflightEvidence,
        pending: OmiCv1StockNormalizationPendingValidation,
    ): OmiCv1StockNormalizationValidation {
        requireInspectionIdentity(inspection, pending.endpoint)
        rejectUnless(inspection.slots.none(FirmwareImageSlot::pending)) {
            "Normalized image state remains transitional after reboot"
        }
        requireExactActive(
            inspection,
            imageNumber = APPLICATION_IMAGE_NUMBER,
            hash = pending.expectedApplicationHash,
        )
        val networkSlots = inspection.slots.filter { it.imageNumber == NETWORK_IMAGE_NUMBER }
        if (networkSlots.isNotEmpty()) {
            requireExactActive(
                inspection,
                imageNumber = NETWORK_IMAGE_NUMBER,
                hash = pending.expectedNetworkHash,
            )
        }
        rejectUnless(inspection.slots.none { !it.active && it.isPopulated() }) {
            "Normalized post-reboot state retains a populated secondary slot"
        }
        rejectUnless(inspection.slots.filter(FirmwareImageSlot::active).all {
            it.imageNumber == APPLICATION_IMAGE_NUMBER || it.imageNumber == NETWORK_IMAGE_NUMBER
        }) { "Normalized post-reboot state contains an unexpected active image" }
        OmiCv1FlashLabDevicePreflightPolicy.requireIdentity(
            deviceEvidence,
            pending.endpoint,
            OmiCv1V3012ApplicationUpdateCatalog.STOCK_MANUFACTURER,
        )
        val firmwareRevision = deviceEvidence.identity.firmwareRevision
            ?: throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.STOCK_NORMALIZATION_STATE_REJECTED,
                "Normalized Device Information revision is unavailable",
            )
        rejectUnless(firmwareRevision == pending.expectedFirmwareRevision) {
            "Normalized Device Information revision is not ${pending.expectedFirmwareRevision}"
        }
        return OmiCv1StockNormalizationValidation(
            pending.planId,
            pending.expectedApplicationHash,
            networkSlots.isNotEmpty(),
            firmwareRevision,
        )
    }

    private fun requireSourceDeviceEvidence(
        evidence: OmiCv1FlashLabDevicePreflightEvidence,
        endpoint: EndpointCandidate,
    ) {
        OmiCv1FlashLabDevicePreflightPolicy.requireSafe(
            evidence,
            endpoint,
            OmiCv1V3012ApplicationUpdateCatalog.STOCK_MANUFACTURER,
        )
        rejectUnless(
            evidence.identity.firmwareRevision ==
                OmiCv1StockV3007FirmwareIdentity.DEVICE_INFORMATION_REVISION,
        ) {
            "Stock normalization requires Device Information revision " +
                OmiCv1StockV3007FirmwareIdentity.DEVICE_INFORMATION_REVISION
        }
    }

    private fun requireSourceOrExactResumeState(
        inspection: FirmwareImageStateInspection,
        endpoint: EndpointCandidate,
        release: OmiCv1StockNormalizationRelease,
    ) {
        requireInspectionIdentity(inspection, endpoint)
        requireExactActive(
            inspection,
            APPLICATION_IMAGE_NUMBER,
            release.sourceApplication.mcubootImageHash,
        )
        val activeNetwork = inspection.slots.filter {
            it.imageNumber == NETWORK_IMAGE_NUMBER && it.active
        }
        if (activeNetwork.isNotEmpty()) {
            requireExactActive(
                inspection,
                NETWORK_IMAGE_NUMBER,
                release.sourceNetwork.mcubootImageHash,
            )
        }
        rejectUnless(inspection.slots.filter(FirmwareImageSlot::active).all {
            it.imageNumber == APPLICATION_IMAGE_NUMBER || it.imageNumber == NETWORK_IMAGE_NUMBER
        }) { "Unexpected active image exists before stock normalization" }
        val populatedSecondary = inspection.slots.filter { !it.active && it.isPopulated() }
        rejectUnless(
            populatedSecondary.groupingBy(FirmwareImageSlot::imageNumber)
                .eachCount()
                .values
                .all { it == 1 },
        ) { "Stock normalization has duplicate populated secondary images" }
        populatedSecondary.forEach { slot ->
            val expectedHash = when (slot.imageNumber) {
                APPLICATION_IMAGE_NUMBER -> release.targetApplication.mcubootImageHash
                NETWORK_IMAGE_NUMBER -> release.targetNetwork.mcubootImageHash
                else -> null
            }
            rejectUnless(
                expectedHash != null &&
                    slot.slotNumber == SECONDARY_SLOT_NUMBER &&
                    slot.hash == expectedHash &&
                    slot.version.matchesCompatibilityVersion() &&
                    slot.bootable,
            ) { "Only an exact resumable v3.0.12 secondary image may already exist" }
        }
        inspection.slots.filter(FirmwareImageSlot::pending).forEach { slot ->
            rejectUnless(!slot.active && slot.slotNumber == SECONDARY_SLOT_NUMBER) {
                "Only an exact normalized secondary image may be pending"
            }
        }
    }

    private fun requireInspectionIdentity(
        inspection: FirmwareImageStateInspection,
        endpoint: EndpointCandidate,
    ) {
        rejectUnless(inspection.endpoint == endpoint, OmiCv1ApplicationUpdateFailureCode.ENDPOINT_MISMATCH) {
            "Stock normalization image-state evidence belongs to a different endpoint"
        }
        rejectUnless(inspection.protocol == OmiCv1ApplicationUpdatePlanner.MCUMGR_SMP_PROTOCOL) {
            "Stock normalization requires MCU Manager SMP image-state evidence"
        }
    }

    private fun requireExactActive(
        inspection: FirmwareImageStateInspection,
        imageNumber: Int,
        hash: FirmwareImageHash,
    ) {
        val active = inspection.slots.filter { it.imageNumber == imageNumber && it.active }
        rejectUnless(active.size == 1) { "Image $imageNumber must have exactly one active slot" }
        val slot = active.single()
        rejectUnless(
            slot.slotNumber == 0 &&
                slot.hash == hash &&
                slot.version.matchesCompatibilityVersion() &&
                slot.bootable,
        ) { "Image $imageNumber active slot does not match the exact normalization source" }
    }

    private fun FirmwareImageSlot.isPopulated(): Boolean =
        hash != null || version != null || bootable || pending || confirmed || permanent || compressed

    private fun String?.matchesCompatibilityVersion(): Boolean =
        this == OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION ||
            this == OmiCv1StockV3012FirmwareOracle.MCUMGR_WIRE_VERSION

    private fun rejectUnless(
        condition: Boolean,
        code: OmiCv1ApplicationUpdateFailureCode =
            OmiCv1ApplicationUpdateFailureCode.STOCK_NORMALIZATION_STATE_REJECTED,
        message: () -> String,
    ) {
        if (!condition) throw OmiCv1ApplicationUpdateException(code, message())
    }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    const val APPLICATION_IMAGE_NUMBER = 0
    const val NETWORK_IMAGE_NUMBER = 1
    const val SECONDARY_SLOT_NUMBER = 1
}

internal interface OmiCv1StockNormalizationSession {
    suspend fun inspect(): FirmwareImageStateInspection
    suspend fun normalize(
        applicationBytes: ByteArray,
        networkBytes: ByteArray,
        onProgress: (OmiCv1StockNormalizationProgress) -> Unit,
    )
    fun cancel()
    fun release()
}

internal fun interface OmiCv1StockNormalizationSessionFactory {
    fun open(endpoint: EndpointCandidate): OmiCv1StockNormalizationSession
}

internal class OmiCv1StockNormalizationExecutor(
    private val sessions: OmiCv1StockNormalizationSessionFactory,
    private val devicePreflight: OmiCv1FlashLabDevicePreflightProbe,
    private val clock: MonotonicMillisClock,
) {
    suspend fun execute(
        authorization: OmiCv1StockNormalizationAuthorization,
        onProgress: (OmiCv1StockNormalizationProgress) -> Unit,
    ): OmiCv1StockNormalizationPendingValidation {
        val plan = authorization.consume(clock.now())
        val session = sessions.open(plan.endpoint)
        return try {
            onProgress(OmiCv1StockNormalizationProgress(OmiCv1StockNormalizationStage.VERIFYING_PREFLIGHT))
            val inspection = session.inspect()
            val deviceEvidence = devicePreflight.inspect(plan.endpoint)
            OmiCv1StockNormalizationPlanner.requireExecutablePreflight(
                inspection,
                deviceEvidence,
                plan,
            )
            session.normalize(
                plan.copyApplicationBytes(),
                plan.copyNetworkBytes(),
                onProgress,
            )
            onProgress(
                OmiCv1StockNormalizationProgress(
                    OmiCv1StockNormalizationStage.AWAITING_POST_REBOOT_VALIDATION,
                ),
            )
            OmiCv1StockNormalizationPendingValidation(
                plan.planId,
                plan.endpoint,
                plan.release.targetApplication.mcubootImageHash,
                plan.release.targetNetwork.mcubootImageHash,
                OmiCv1StockV3012FirmwareOracle.DEVICE_INFORMATION_REVISION,
            )
        } catch (error: CancellationException) {
            session.cancel()
            throw error
        } catch (error: OmiCv1ApplicationUpdateException) {
            throw error
        } catch (error: Exception) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.STOCK_NORMALIZATION_FAILED,
                "Official dual-image stock normalization failed (${error::class.simpleName})",
                error,
            )
        } finally {
            session.release()
        }
    }
}
