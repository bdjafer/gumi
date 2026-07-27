package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.edge.sdk.firmware.FirmwareImageHash

/** Exact signed output qualified from the pinned recovery-only-0001 build. */
internal object OmiCv1RecoveryOnly0001ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/recovery-only-0001/omi.signed.bin",
        fileSizeBytes = 106_936,
        fileSha256 = FirmwareImageHash(
            "d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc",
        ),
        payloadSizeBytes = 106_088,
        mcubootImageHash = FirmwareImageHash(
            "065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/** Exact signed output qualified from the pinned capture-port-selftest-0001 build. */
internal object OmiCv1CapturePortSelftest0001ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/capture-port-selftest-0001/omi.signed.bin",
        fileSizeBytes = 178_100,
        fileSha256 = FirmwareImageHash(
            "8f0d0fc35c4d3c56e8f94d528a0b56c0d4af7b44b7bdad2b2fa4f2963e2e3d0e",
        ),
        payloadSizeBytes = 177_252,
        mcubootImageHash = FirmwareImageHash(
            "e97fd653a66dce18a7dbd071bc9b21c4fdae4e229de5d245e76ed213c2ca4862",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/** Exact signed output qualified from the pinned functional-recording-0001 build. */
internal object OmiCv1FunctionalRecording0001ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/functional-recording-0001/omi.signed.bin",
        fileSizeBytes = 218_048,
        fileSha256 = FirmwareImageHash(
            "838c767f0d273767d422da751f4c2bc16bf1b27f35452833f992baf486c1ba45",
        ),
        payloadSizeBytes = 217_200,
        mcubootImageHash = FirmwareImageHash(
            "045918a8cc1ceb4be74dd486e9da7b14123daacbb9b26e0d6404b6617048c820",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/** Exact signed output qualified from the pinned recording-root-provisioner-0001 build. */
internal object OmiCv1RecordingRootProvisioner0001ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/recording-root-provisioner-0001/omi.signed.bin",
        fileSizeBytes = 113_428,
        fileSha256 = FirmwareImageHash(
            "e53c58a3ef72773af501a9ae05ceb5ce715b6d8153923ef52609dc5ec1dfa38b",
        ),
        payloadSizeBytes = 112_580,
        mcubootImageHash = FirmwareImageHash(
            "8a8eef711fd72707905bda05e08ddebc0de2a986d3c1d21b7469c92e7d32b12e",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/** Exact signed output qualified from the watchdog-hardened functional-recording-0003 build. */
internal object OmiCv1FunctionalRecording0003ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/functional-recording-0003/omi.signed.bin",
        fileSizeBytes = 220_444,
        fileSha256 = FirmwareImageHash(
            "3fda1c98da2bcd747e435b464feda563415949f6e0615193db25f1b658f3af1e",
        ),
        payloadSizeBytes = 219_596,
        mcubootImageHash = FirmwareImageHash(
            "0cd7ddea779427f25ff2b3341f1242e0e4611f1245b02fde81361c0d2cbcbecd",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/** Exact signed output qualified from the allocator-repaired functional-recording-0004 build. */
internal object OmiCv1FunctionalRecording0004ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/functional-recording-0004/omi.signed.bin",
        fileSizeBytes = 221_428,
        fileSha256 = FirmwareImageHash(
            "382a04633bf83329fb8ef3ded1ecbfb01ba6b53e8af1b2473e7f1795355ba7d2",
        ),
        payloadSizeBytes = 220_580,
        mcubootImageHash = FirmwareImageHash(
            "1a47d68c09d664fdf4e0d6549ebe8f8e7ccf08d00f7a93f01a54dc21d0a075d3",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/** Exact signed output qualified from the SD host PM-repaired functional-recording-0005 build. */
internal object OmiCv1FunctionalRecording0005ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/functional-recording-0005/omi.signed.bin",
        fileSizeBytes = 221_428,
        fileSha256 = FirmwareImageHash(
            "26ec3d961d1342440a53034c27591df04ca1e2de637f463e48063e86b1b26f27",
        ),
        payloadSizeBytes = 220_580,
        mcubootImageHash = FirmwareImageHash(
            "55663e5f90ce3e7b6e9373f8ff6432c215c68668e6759b9111cc815a31c54961",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/** Exact signed output qualified from the dual-core-reset-repaired functional-recording-0006 build. */
internal object OmiCv1FunctionalRecording0006ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/functional-recording-0006/omi.signed.bin",
        fileSizeBytes = 221_576,
        fileSha256 = FirmwareImageHash(
            "eb811b62fffbf5c5f3ca1684b29073f6cf9cb49ae8e38d5201944c6c36d640a0",
        ),
        payloadSizeBytes = 220_728,
        mcubootImageHash = FirmwareImageHash(
            "3a35a3324393e563e36b2748f0b1f842ce5db52834cd0023334d9c9ac3071aa1",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/**
 * Exact signed output from the superseded legacy-storage-reclaimer-0001 build.
 *
 * Retained only so the lab can recognize the physically observed OTA-stranded state. It must
 * never be selected as an update target or treated as an OTA-capable source.
 */
internal object OmiCv1LegacyStorageReclaimer0001ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/legacy-storage-reclaimer-0001/omi.signed.bin",
        fileSizeBytes = 114_448,
        fileSha256 = FirmwareImageHash(
            "a5c7ed5f396312bed89eafb015bdea27c503e45e918e2e0706cb08ef41e6e242",
        ),
        payloadSizeBytes = 113_600,
        mcubootImageHash = FirmwareImageHash(
            "701149e72c5da262ac57d869cb70e62368eb7e9f53666bdd5f242fb0cc31c655",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/** Exact signed output qualified from the OTA-handoff-repaired reclaimer v0002 build. */
internal object OmiCv1LegacyStorageReclaimer0002ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/legacy-storage-reclaimer-0002/omi.signed.bin",
        fileSizeBytes = 114_448,
        fileSha256 = FirmwareImageHash(
            "59fb753783c51140f4ee22c9d18dcaaf40bcb240e69c93a3cb2e967999fb1960",
        ),
        payloadSizeBytes = 113_600,
        mcubootImageHash = FirmwareImageHash(
            "8fc16e21b238dd4907abb6a4512db1002c4acf75cc079f8fa1ea49e467b412a2",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/** Exact signed output qualified from the capacity-evidence functional-recording-0007 build. */
internal object OmiCv1FunctionalRecording0007ApplicationArtifact {
    val manifest = OmiCv1ApplicationArtifactManifest(
        identity = "gumi/Omi_CV1_v3.0.12/functional-recording-0007/omi.signed.bin",
        fileSizeBytes = 221_592,
        fileSha256 = FirmwareImageHash(
            "a0d292117b0f2455fc342a2ad39e2b9ce02054e096689018184065df91933b25",
        ),
        payloadSizeBytes = 220_744,
        mcubootImageHash = FirmwareImageHash(
            "407df7c1f97b480f45d445d4045b5a124af2d431130a3f07b77b07726301d1e0",
        ),
        compatibilityKeyHash = FirmwareImageHash(
            "fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994",
        ),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        signatureVerifiedOffline = true,
    )
}

/**
 * The current recovery-only v3.0.12 application transitions.
 *
 * These values are inert metadata: no production authorizer or caller is composed into the shell.
 */
internal object OmiCv1V3012ApplicationUpdateCatalog {
    const val STOCK_MANUFACTURER = "Based Hardware"
    const val GUMI_MANUFACTURER = "Gumi"

    private val stockState = OmiCv1ExpectedActiveImages(
        applicationHash = FirmwareImageHash(OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH),
        networkHash = FirmwareImageHash(OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH),
        mcubootVersion = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
        networkEvidencePolicy =
            OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
    )

    val stockToRecoveryOnly0001 = OmiCv1ApplicationUpdateRelease(
        releaseId = "omi-cv1-v3012-stock-to-gumi-recovery-only-0001",
        intent = OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
        source = stockState,
        sourceManufacturer = STOCK_MANUFACTURER,
        target = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest,
    )

    val recoveryOnly0001ToStock = OmiCv1ApplicationUpdateRelease(
        releaseId = "omi-cv1-v3012-gumi-recovery-only-0001-to-stock",
        intent = OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY,
        source = stockState.copy(
            applicationHash = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = STOCK_MANUFACTURER,
        target = OmiCv1StockV3012ApplicationArtifact.manifest,
    )

    val recoveryOnly0001ToCapturePortSelftest0001 = OmiCv1ApplicationUpdateRelease(
        releaseId = "omi-cv1-v3012-gumi-recovery-only-0001-to-capture-port-selftest-0001",
        intent = OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST,
        source = stockState.copy(
            applicationHash = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = STOCK_MANUFACTURER,
        target = OmiCv1CapturePortSelftest0001ApplicationArtifact.manifest,
    )

    val capturePortSelftest0001ToRecoveryOnly0001 = OmiCv1ApplicationUpdateRelease(
        releaseId = "omi-cv1-v3012-capture-port-selftest-0001-to-gumi-recovery-only-0001",
        intent = OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
        source = stockState.copy(
            applicationHash = OmiCv1CapturePortSelftest0001ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = STOCK_MANUFACTURER,
        target = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest,
    )

    val recoveryOnly0001ToRecordingRootProvisioner0001 = OmiCv1ApplicationUpdateRelease(
        releaseId =
            "omi-cv1-v3012-gumi-recovery-only-0001-to-recording-root-provisioner-0001",
        intent = OmiCv1ApplicationUpdateIntent.RECORDING_ROOT_PROVISIONER,
        source = stockState.copy(
            applicationHash = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = STOCK_MANUFACTURER,
        target = OmiCv1RecordingRootProvisioner0001ApplicationArtifact.manifest,
    )

    val recordingRootProvisioner0001ToFunctionalRecording0006 =
        OmiCv1ApplicationUpdateRelease(
            releaseId =
                "omi-cv1-v3012-recording-root-provisioner-0001-to-functional-recording-0006",
            intent = OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING,
            source = stockState.copy(
                applicationHash =
                    OmiCv1RecordingRootProvisioner0001ApplicationArtifact.manifest.mcubootImageHash,
            ),
            sourceManufacturer = GUMI_MANUFACTURER,
            target = OmiCv1FunctionalRecording0006ApplicationArtifact.manifest,
        )

    val recordingRootProvisioner0001ToLegacyStorageReclaimer0002 =
        OmiCv1ApplicationUpdateRelease(
            releaseId =
                "omi-cv1-v3012-recording-root-provisioner-0001-to-legacy-storage-reclaimer-0002",
            intent = OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER,
            source = stockState.copy(
                applicationHash =
                    OmiCv1RecordingRootProvisioner0001ApplicationArtifact.manifest.mcubootImageHash,
            ),
            sourceManufacturer = GUMI_MANUFACTURER,
            target = OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest,
        )

    val functionalRecording0006ToLegacyStorageReclaimer0002 =
        OmiCv1ApplicationUpdateRelease(
            releaseId =
                "omi-cv1-v3012-functional-recording-0006-to-legacy-storage-reclaimer-0002",
            intent = OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER,
            source = stockState.copy(
                applicationHash =
                    OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.mcubootImageHash,
            ),
            sourceManufacturer = GUMI_MANUFACTURER,
            target = OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest,
        )

    val legacyStorageReclaimer0002ToFunctionalRecording0007 =
        OmiCv1ApplicationUpdateRelease(
            releaseId =
                "omi-cv1-v3012-legacy-storage-reclaimer-0002-to-functional-recording-0007",
            intent = OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING,
            source = stockState.copy(
                applicationHash =
                    OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest.mcubootImageHash,
            ),
            sourceManufacturer = GUMI_MANUFACTURER,
            target = OmiCv1FunctionalRecording0007ApplicationArtifact.manifest,
        )

    /** Direct repair for the allocator-stub failure physically observed on v0003. */
    val functionalRecording0003ToFunctionalRecording0006 =
        OmiCv1ApplicationUpdateRelease(
            releaseId =
                "omi-cv1-v3012-functional-recording-0003-to-functional-recording-0006",
            intent = OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING,
            source = stockState.copy(
                applicationHash =
                    OmiCv1FunctionalRecording0003ApplicationArtifact.manifest.mcubootImageHash,
            ),
            sourceManufacturer = GUMI_MANUFACTURER,
            target = OmiCv1FunctionalRecording0006ApplicationArtifact.manifest,
        )

    /** Direct repair for the SD host PM failure physically observed on v0004. */
    val functionalRecording0004ToFunctionalRecording0006 =
        OmiCv1ApplicationUpdateRelease(
            releaseId =
                "omi-cv1-v3012-functional-recording-0004-to-functional-recording-0006",
            intent = OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING,
            source = stockState.copy(
                applicationHash =
                    OmiCv1FunctionalRecording0004ApplicationArtifact.manifest.mcubootImageHash,
            ),
            sourceManufacturer = GUMI_MANUFACTURER,
            target = OmiCv1FunctionalRecording0006ApplicationArtifact.manifest,
        )

    /** Direct repair for the application-only reset lockup physically observed on v0005. */
    val functionalRecording0005ToFunctionalRecording0006 =
        OmiCv1ApplicationUpdateRelease(
            releaseId =
                "omi-cv1-v3012-functional-recording-0005-to-functional-recording-0006",
            intent = OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING,
            source = stockState.copy(
                applicationHash =
                    OmiCv1FunctionalRecording0005ApplicationArtifact.manifest.mcubootImageHash,
            ),
            sourceManufacturer = GUMI_MANUFACTURER,
            target = OmiCv1FunctionalRecording0006ApplicationArtifact.manifest,
        )

    val recordingRootProvisioner0001ToRecoveryOnly0001 = OmiCv1ApplicationUpdateRelease(
        releaseId =
            "omi-cv1-v3012-recording-root-provisioner-0001-to-gumi-recovery-only-0001",
        intent = OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
        source = stockState.copy(
            applicationHash =
                OmiCv1RecordingRootProvisioner0001ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = GUMI_MANUFACTURER,
        target = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest,
    )

    val functionalRecording0003ToRecoveryOnly0001 = OmiCv1ApplicationUpdateRelease(
        releaseId =
            "omi-cv1-v3012-functional-recording-0003-to-gumi-recovery-only-0001",
        intent = OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
        source = stockState.copy(
            applicationHash = OmiCv1FunctionalRecording0003ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = GUMI_MANUFACTURER,
        target = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest,
    )

    val functionalRecording0004ToRecoveryOnly0001 = OmiCv1ApplicationUpdateRelease(
        releaseId =
            "omi-cv1-v3012-functional-recording-0004-to-gumi-recovery-only-0001",
        intent = OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
        source = stockState.copy(
            applicationHash =
                OmiCv1FunctionalRecording0004ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = GUMI_MANUFACTURER,
        target = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest,
    )

    val functionalRecording0005ToRecoveryOnly0001 = OmiCv1ApplicationUpdateRelease(
        releaseId =
            "omi-cv1-v3012-functional-recording-0005-to-gumi-recovery-only-0001",
        intent = OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
        source = stockState.copy(
            applicationHash =
                OmiCv1FunctionalRecording0005ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = GUMI_MANUFACTURER,
        target = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest,
    )

    val functionalRecording0006ToRecoveryOnly0001 = OmiCv1ApplicationUpdateRelease(
        releaseId =
            "omi-cv1-v3012-functional-recording-0006-to-gumi-recovery-only-0001",
        intent = OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
        source = stockState.copy(
            applicationHash =
                OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = GUMI_MANUFACTURER,
        target = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest,
    )

    val legacyStorageReclaimer0002ToRecoveryOnly0001 = OmiCv1ApplicationUpdateRelease(
        releaseId =
            "omi-cv1-v3012-legacy-storage-reclaimer-0002-to-gumi-recovery-only-0001",
        intent = OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
        source = stockState.copy(
            applicationHash =
                OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = GUMI_MANUFACTURER,
        target = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest,
    )

    val functionalRecording0007ToRecoveryOnly0001 = OmiCv1ApplicationUpdateRelease(
        releaseId =
            "omi-cv1-v3012-functional-recording-0007-to-gumi-recovery-only-0001",
        intent = OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
        source = stockState.copy(
            applicationHash =
                OmiCv1FunctionalRecording0007ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = GUMI_MANUFACTURER,
        target = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest,
    )

    /** Emergency escape retained solely for the already-installed, lockup-prone v0001 image. */
    val functionalRecording0001ToRecoveryOnly0001 = OmiCv1ApplicationUpdateRelease(
        releaseId =
            "omi-cv1-v3012-functional-recording-0001-to-gumi-recovery-only-0001-rescue",
        intent = OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
        source = stockState.copy(
            applicationHash = OmiCv1FunctionalRecording0001ApplicationArtifact.manifest.mcubootImageHash,
        ),
        sourceManufacturer = GUMI_MANUFACTURER,
        target = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest,
        uploadMode = OmiCv1ApplicationUploadMode.INCOMPLETE_FLASH_BLOCK_RESCUE,
    )
}
