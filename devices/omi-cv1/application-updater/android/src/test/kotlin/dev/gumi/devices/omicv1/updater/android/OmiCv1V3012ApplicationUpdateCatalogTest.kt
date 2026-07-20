package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OmiCv1V3012ApplicationUpdateCatalogTest {
    @Test
    fun `canary transition is bound to exact stock application and network images`() {
        val release = OmiCv1V3012ApplicationUpdateCatalog.stockToCanary0001

        assertEquals(OmiCv1ApplicationUpdateIntent.MINIMAL_CANARY, release.intent)
        assertEquals(OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH, release.source.applicationHash.hex)
        assertEquals(OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH, release.source.networkHash.hex)
        assertEquals(
            OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
            release.source.networkEvidencePolicy,
        )
        assertEquals(OmiCv1Canary0001ApplicationArtifact.manifest, release.target)
        assertNotEquals(release.source.applicationHash, release.target.mcubootImageHash)
    }

    @Test
    fun `stock recovery accepts only the canary source and retains the stock network image`() {
        val release = OmiCv1V3012ApplicationUpdateCatalog.canary0001ToStock

        assertEquals(OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY, release.intent)
        assertEquals(OmiCv1Canary0001ApplicationArtifact.manifest.mcubootImageHash, release.source.applicationHash)
        assertEquals(OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH, release.source.networkHash.hex)
        assertEquals(
            OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
            release.source.networkEvidencePolicy,
        )
        assertEquals(OmiCv1StockV3012ApplicationArtifact.manifest, release.target)
        assertEquals(OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH, release.target.mcubootImageHash.hex)
    }

    @Test
    fun `qualified canary pins the exact independently verified build output`() {
        val manifest = OmiCv1Canary0001ApplicationArtifact.manifest

        assertEquals(228_724, manifest.fileSizeBytes)
        assertEquals(
            "65e4ae91637e6aa576f6d3f8286db33bc9bd36ece8ece1aaa6b6aa5c5b204f6d",
            manifest.fileSha256.hex,
        )
        assertEquals(
            "d3b3c74c10c4fa110763ae5523d0aeaa20b9f815b8674167486be6ff6f8052ce",
            manifest.mcubootImageHash.hex,
        )
        assertEquals("0.0.0+0", manifest.mcubootVersion)
    }
}
