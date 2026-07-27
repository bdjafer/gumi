package dev.gumi.devices.omicv1.updater.android

import android.content.Context

internal fun interface OmiCv1FlashLabArtifactSource {
    fun load(target: OmiCv1ApplicationArtifactManifest): ByteArray
}

/** Only exact catalogued application-image-0 artifacts have a route through this store. */
internal class AndroidOmiCv1FlashLabArtifactSource(context: Context) :
    OmiCv1FlashLabArtifactSource {
    private val assets = context.applicationContext.assets

    override fun load(target: OmiCv1ApplicationArtifactManifest): ByteArray {
        val path = when (target.identity) {
            OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.identity -> RECOVERY_ONLY_ASSET
            OmiCv1StockV3012ApplicationArtifact.manifest.identity -> STOCK_RECOVERY_ASSET
            OmiCv1CapturePortSelftest0001ApplicationArtifact.manifest.identity ->
                CAPTURE_SELFTEST_ASSET
            OmiCv1RecordingRootProvisioner0001ApplicationArtifact.manifest.identity ->
                RECORDING_ROOT_PROVISIONER_ASSET
            OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest.identity ->
                LEGACY_STORAGE_RECLAIMER_ASSET
            OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.identity ->
                FUNCTIONAL_RECORDING_0006_ASSET
            OmiCv1FunctionalRecording0007ApplicationArtifact.manifest.identity ->
                FUNCTIONAL_RECORDING_0007_ASSET
            else -> throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.ARTIFACT_REJECTED,
                "Catalogued target has no packaged artifact",
            )
        }
        val bytes = assets.open(path).use { input -> input.readBytes() }
        if (bytes.isEmpty() || bytes.size > MAX_APPLICATION_BYTES) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.ARTIFACT_REJECTED,
                "Packaged application artifact has an invalid size",
            )
        }
        return bytes
    }

    private companion object {
        const val MAX_APPLICATION_BYTES = 300_000
        const val RECOVERY_ONLY_ASSET = "firmware/recovery-only-0001-application-image-0.bin"
        const val STOCK_RECOVERY_ASSET = "firmware/stock-v3.0.12-application-image-0.bin"
        const val CAPTURE_SELFTEST_ASSET =
            "firmware/capture-port-selftest-0001-application-image-0.bin"
        const val RECORDING_ROOT_PROVISIONER_ASSET =
            "firmware/recording-root-provisioner-0001-application-image-0.bin"
        const val LEGACY_STORAGE_RECLAIMER_ASSET =
            "firmware/legacy-storage-reclaimer-0002-application-image-0.bin"
        const val FUNCTIONAL_RECORDING_0006_ASSET =
            "firmware/functional-recording-0006-application-image-0.bin"
        const val FUNCTIONAL_RECORDING_0007_ASSET =
            "firmware/functional-recording-0007-application-image-0.bin"
    }
}

internal class AndroidOmiCv1StockNormalizationArtifactSource(context: Context) :
    OmiCv1StockNormalizationArtifactSource {
    private val assets = context.applicationContext.assets

    override fun load(): OmiCv1StockNormalizationArtifacts =
        OmiCv1StockNormalizationArtifacts(
            applicationBytes = load(TARGET_APPLICATION_ASSET),
            networkBytes = load(TARGET_NETWORK_ASSET),
        )

    private fun load(path: String): ByteArray =
        assets.open(path).use { input -> input.readBytes() }.also { bytes ->
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
                throw OmiCv1ApplicationUpdateException(
                    OmiCv1ApplicationUpdateFailureCode.ARTIFACT_REJECTED,
                    "Packaged stock normalization artifact has an invalid size",
                )
            }
        }

    private companion object {
        const val MAX_IMAGE_BYTES = 300_000
        const val TARGET_APPLICATION_ASSET =
            "firmware/stock-v3.0.12-application-image-0.bin"
        const val TARGET_NETWORK_ASSET =
            "firmware/stock-v3.0.12-network-image-1.bin"
    }
}
