package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1StockV3007FirmwareIdentity
import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.edge.sdk.firmware.FirmwareImageHash

internal object OmiCv1StockV3007ApplicationArtifact {
    val manifest = OmiCv1McubootArtifactManifest(
        identity = "official/Omi_CV1_v3.0.7/omi.signed.bin",
        fileSizeBytes = 263_756,
        fileSha256 = FirmwareImageHash(
            "58a355ed2e348ffe4944fd9de889c294b012251458e7b20c2d5e88017e9c6b55",
        ),
        payloadSizeBytes = 262_908,
        mcubootImageHash =
            FirmwareImageHash(OmiCv1StockV3007FirmwareIdentity.APPLICATION_IMAGE_HASH),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3007FirmwareIdentity.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

internal object OmiCv1StockV3007NetworkArtifact {
    val manifest = OmiCv1McubootArtifactManifest(
        identity = "official/Omi_CV1_v3.0.7/ipc_radio.bin",
        fileSizeBytes = 175_092,
        fileSha256 = FirmwareImageHash(
            "f0bed1869b653e36b60858aad59f26dbec2c392a27ae542566fe535dedb2f8c2",
        ),
        payloadSizeBytes = 174_244,
        mcubootImageHash =
            FirmwareImageHash(OmiCv1StockV3007FirmwareIdentity.NETWORK_IMAGE_HASH),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3007FirmwareIdentity.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

internal object OmiCv1StockV3012NetworkArtifact {
    val manifest = OmiCv1McubootArtifactManifest(
        identity = "official/Omi_CV1_v3.0.12/ipc_radio.bin",
        fileSizeBytes = 175_092,
        fileSha256 = FirmwareImageHash(
            "0e1d067444ca78dd4ea64cb355bc167c1155b84f49b0c7028112c9930602d2a5",
        ),
        payloadSizeBytes = 174_244,
        mcubootImageHash =
            FirmwareImageHash(OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}
