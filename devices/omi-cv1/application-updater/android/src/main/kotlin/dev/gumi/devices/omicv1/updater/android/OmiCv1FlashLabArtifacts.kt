package dev.gumi.devices.omicv1.updater.android

import android.content.Context

internal fun interface OmiCv1FlashLabArtifactSource {
    fun load(intent: OmiCv1ApplicationUpdateIntent): ByteArray
}

/** Only the two build-verified application-image-0 assets have a route through this store. */
internal class AndroidOmiCv1FlashLabArtifactSource(context: Context) :
    OmiCv1FlashLabArtifactSource {
    private val assets = context.applicationContext.assets

    override fun load(intent: OmiCv1ApplicationUpdateIntent): ByteArray {
        val path = when (intent) {
            OmiCv1ApplicationUpdateIntent.MINIMAL_CANARY -> CANARY_ASSET
            OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY -> STOCK_RECOVERY_ASSET
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
        const val CANARY_ASSET = "firmware/canary-0001-application-image-0.bin"
        const val STOCK_RECOVERY_ASSET = "firmware/stock-v3.0.12-application-image-0.bin"
    }
}
