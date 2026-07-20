package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.edge.sdk.firmware.FirmwareImageHash

/** Verified official application artifact. This is not an executable update release by itself. */
internal object OmiCv1StockV3012ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "official/Omi_CV1_v3.0.12/omi.signed.bin",
        fileSizeBytes = 228_632,
        fileSha256 = FirmwareImageHash(
            "877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db",
        ),
        payloadSizeBytes = 227_784,
        mcubootImageHash = FirmwareImageHash(OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}
