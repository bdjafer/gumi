package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.edge.sdk.firmware.FirmwareImageHash

/** Exact signed output qualified from the pinned canary-0001 build. */
internal object OmiCv1Canary0001ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/canary-0001/omi.signed.bin",
        fileSizeBytes = 228_724,
        fileSha256 = FirmwareImageHash(
            "65e4ae91637e6aa576f6d3f8286db33bc9bd36ece8ece1aaa6b6aa5c5b204f6d",
        ),
        payloadSizeBytes = 227_876,
        mcubootImageHash = FirmwareImageHash(
            "d3b3c74c10c4fa110763ae5523d0aeaa20b9f815b8674167486be6ff6f8052ce",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/**
 * The only qualified v3.0.12 application transitions.
 *
 * These values are inert metadata: no production authorizer or caller is composed into the shell.
 */
internal object OmiCv1V3012ApplicationUpdateCatalog {
    private val stockState = OmiCv1ExpectedActiveImages(
        applicationHash = FirmwareImageHash(OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH),
        networkHash = FirmwareImageHash(OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        networkEvidencePolicy =
            OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
    )

    val stockToCanary0001 = OmiCv1ApplicationUpdateRelease(
        releaseId = "omi-cv1-v3012-stock-to-gumi-canary-0001",
        intent = OmiCv1ApplicationUpdateIntent.MINIMAL_CANARY,
        source = stockState,
        target = OmiCv1Canary0001ApplicationArtifact.manifest,
    )

    val canary0001ToStock = OmiCv1ApplicationUpdateRelease(
        releaseId = "omi-cv1-v3012-gumi-canary-0001-to-stock",
        intent = OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY,
        source = stockState.copy(
            applicationHash = OmiCv1Canary0001ApplicationArtifact.manifest.mcubootImageHash,
        ),
        target = OmiCv1StockV3012ApplicationArtifact.manifest,
    )
}
