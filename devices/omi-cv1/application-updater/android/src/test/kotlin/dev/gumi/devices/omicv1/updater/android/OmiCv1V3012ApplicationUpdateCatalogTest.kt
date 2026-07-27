package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OmiCv1V3012ApplicationUpdateCatalogTest {
    @Test
    fun `recovery transition is bound to exact stock application and network images`() {
        val release = OmiCv1V3012ApplicationUpdateCatalog.stockToRecoveryOnly0001

        assertEquals(OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY, release.intent)
        assertEquals(OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH, release.source.applicationHash.hex)
        assertEquals(OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH, release.source.networkHash.hex)
        assertEquals(
            OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
            release.source.networkEvidencePolicy,
        )
        assertEquals(OmiCv1RecoveryOnly0001ApplicationArtifact.manifest, release.target)
        assertNotEquals(release.source.applicationHash, release.target.mcubootImageHash)
    }

    @Test
    fun `stock recovery accepts only the recovery source and retains the stock network image`() {
        val release = OmiCv1V3012ApplicationUpdateCatalog.recoveryOnly0001ToStock

        assertEquals(OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY, release.intent)
        assertEquals(
            OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.mcubootImageHash,
            release.source.applicationHash,
        )
        assertEquals(OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH, release.source.networkHash.hex)
        assertEquals(
            OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
            release.source.networkEvidencePolicy,
        )
        assertEquals(OmiCv1StockV3012ApplicationArtifact.manifest, release.target)
        assertEquals(OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH, release.target.mcubootImageHash.hex)
    }

    @Test
    fun `qualified recovery image pins the exact independently verified build output`() {
        val manifest = OmiCv1RecoveryOnly0001ApplicationArtifact.manifest

        assertEquals(106_936, manifest.fileSizeBytes)
        assertEquals(
            "d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc",
            manifest.fileSha256.hex,
        )
        assertEquals(
            "065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57",
            manifest.mcubootImageHash.hex,
        )
        assertEquals("0.0.0+0", manifest.mcubootVersion)
    }

    @Test
    fun `capture self-test and recovery return are closed exact transitions`() {
        val outbound =
            OmiCv1V3012ApplicationUpdateCatalog.recoveryOnly0001ToCapturePortSelftest0001
        val inbound =
            OmiCv1V3012ApplicationUpdateCatalog.capturePortSelftest0001ToRecoveryOnly0001
        val manifest = OmiCv1CapturePortSelftest0001ApplicationArtifact.manifest

        assertEquals(OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST, outbound.intent)
        assertEquals(
            OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.mcubootImageHash,
            outbound.source.applicationHash,
        )
        assertEquals(manifest, outbound.target)
        assertEquals(178_100, manifest.fileSizeBytes)
        assertEquals(177_252, manifest.payloadSizeBytes)
        assertEquals(
            "8f0d0fc35c4d3c56e8f94d528a0b56c0d4af7b44b7bdad2b2fa4f2963e2e3d0e",
            manifest.fileSha256.hex,
        )
        assertEquals(
            "e97fd653a66dce18a7dbd071bc9b21c4fdae4e229de5d245e76ed213c2ca4862",
            manifest.mcubootImageHash.hex,
        )
        assertEquals(OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY, inbound.intent)
        assertEquals(manifest.mcubootImageHash, inbound.source.applicationHash)
        assertEquals(OmiCv1RecoveryOnly0001ApplicationArtifact.manifest, inbound.target)
    }

    @Test
    fun `provisioning and dual-core-reset-repaired functional transitions form a closed exact chain`() {
        val provision =
            OmiCv1V3012ApplicationUpdateCatalog
                .recoveryOnly0001ToRecordingRootProvisioner0001
        val functional =
            OmiCv1V3012ApplicationUpdateCatalog
                .recordingRootProvisioner0001ToFunctionalRecording0006
        val inbound =
            OmiCv1V3012ApplicationUpdateCatalog.functionalRecording0006ToRecoveryOnly0001
        val provisionerManifest = OmiCv1RecordingRootProvisioner0001ApplicationArtifact.manifest
        val functionalManifest = OmiCv1FunctionalRecording0006ApplicationArtifact.manifest

        assertEquals(OmiCv1ApplicationUpdateIntent.RECORDING_ROOT_PROVISIONER, provision.intent)
        assertEquals(
            OmiCv1RecoveryOnly0001ApplicationArtifact.manifest.mcubootImageHash,
            provision.source.applicationHash,
        )
        assertEquals(provisionerManifest, provision.target)
        assertEquals(113_428, provisionerManifest.fileSizeBytes)
        assertEquals(112_580, provisionerManifest.payloadSizeBytes)
        assertEquals(
            "e53c58a3ef72773af501a9ae05ceb5ce715b6d8153923ef52609dc5ec1dfa38b",
            provisionerManifest.fileSha256.hex,
        )
        assertEquals(
            "8a8eef711fd72707905bda05e08ddebc0de2a986d3c1d21b7469c92e7d32b12e",
            provisionerManifest.mcubootImageHash.hex,
        )
        assertEquals(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING, functional.intent)
        assertEquals(provisionerManifest.mcubootImageHash, functional.source.applicationHash)
        assertEquals(functionalManifest, functional.target)
        assertEquals(221_576, functionalManifest.fileSizeBytes)
        assertEquals(220_728, functionalManifest.payloadSizeBytes)
        assertEquals(
            "eb811b62fffbf5c5f3ca1684b29073f6cf9cb49ae8e38d5201944c6c36d640a0",
            functionalManifest.fileSha256.hex,
        )
        assertEquals(
            "3a35a3324393e563e36b2748f0b1f842ce5db52834cd0023334d9c9ac3071aa1",
            functionalManifest.mcubootImageHash.hex,
        )
        assertEquals(OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY, inbound.intent)
        assertEquals(functionalManifest.mcubootImageHash, inbound.source.applicationHash)
        assertEquals(OmiCv1RecoveryOnly0001ApplicationArtifact.manifest, inbound.target)
    }

    @Test
    fun `OTA-safe reclaimer and capacity-evidence functional v0007 form a closed exact chain`() {
        val outbound =
            OmiCv1V3012ApplicationUpdateCatalog
                .functionalRecording0006ToLegacyStorageReclaimer0002
        val functional =
            OmiCv1V3012ApplicationUpdateCatalog
                .legacyStorageReclaimer0002ToFunctionalRecording0007
        val recovery =
            OmiCv1V3012ApplicationUpdateCatalog
                .legacyStorageReclaimer0002ToRecoveryOnly0001
        val reclaimerManifest = OmiCv1LegacyStorageReclaimer0002ApplicationArtifact.manifest
        val functionalManifest = OmiCv1FunctionalRecording0007ApplicationArtifact.manifest

        assertEquals(OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER, outbound.intent)
        assertEquals(
            OmiCv1FunctionalRecording0006ApplicationArtifact.manifest.mcubootImageHash,
            outbound.source.applicationHash,
        )
        assertEquals(reclaimerManifest, outbound.target)
        assertEquals(114_448, reclaimerManifest.fileSizeBytes)
        assertEquals(
            "59fb753783c51140f4ee22c9d18dcaaf40bcb240e69c93a3cb2e967999fb1960",
            reclaimerManifest.fileSha256.hex,
        )
        assertEquals(
            "8fc16e21b238dd4907abb6a4512db1002c4acf75cc079f8fa1ea49e467b412a2",
            reclaimerManifest.mcubootImageHash.hex,
        )
        assertEquals(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING, functional.intent)
        assertEquals(reclaimerManifest.mcubootImageHash, functional.source.applicationHash)
        assertEquals(functionalManifest, functional.target)
        assertEquals(221_592, functionalManifest.fileSizeBytes)
        assertEquals(
            "a0d292117b0f2455fc342a2ad39e2b9ce02054e096689018184065df91933b25",
            functionalManifest.fileSha256.hex,
        )
        assertEquals(OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY, recovery.intent)
        assertEquals(reclaimerManifest.mcubootImageHash, recovery.source.applicationHash)
        assertEquals(OmiCv1RecoveryOnly0001ApplicationArtifact.manifest, recovery.target)
    }

    @Test
    fun `failed v0003 has one exact direct current functional repair target`() {
        val repair =
            OmiCv1V3012ApplicationUpdateCatalog
                .functionalRecording0003ToFunctionalRecording0006

        assertEquals(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING, repair.intent)
        assertEquals(
            OmiCv1FunctionalRecording0003ApplicationArtifact.manifest.mcubootImageHash,
            repair.source.applicationHash,
        )
        assertEquals(
            OmiCv1FunctionalRecording0006ApplicationArtifact.manifest,
            repair.target,
        )
        assertNotEquals(repair.source.applicationHash, repair.target.mcubootImageHash)
    }

    @Test
    fun `failed v0004 has one exact direct current functional repair target`() {
        val repair =
            OmiCv1V3012ApplicationUpdateCatalog
                .functionalRecording0004ToFunctionalRecording0006

        assertEquals(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING, repair.intent)
        assertEquals(
            OmiCv1FunctionalRecording0004ApplicationArtifact.manifest.mcubootImageHash,
            repair.source.applicationHash,
        )
        assertEquals(
            OmiCv1FunctionalRecording0006ApplicationArtifact.manifest,
            repair.target,
        )
        assertNotEquals(repair.source.applicationHash, repair.target.mcubootImageHash)
    }

    @Test
    fun `lockup-prone v0005 has one exact direct dual-core-reset repair target`() {
        val repair =
            OmiCv1V3012ApplicationUpdateCatalog
                .functionalRecording0005ToFunctionalRecording0006

        assertEquals(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING, repair.intent)
        assertEquals(
            OmiCv1FunctionalRecording0005ApplicationArtifact.manifest.mcubootImageHash,
            repair.source.applicationHash,
        )
        assertEquals(
            OmiCv1FunctionalRecording0006ApplicationArtifact.manifest,
            repair.target,
        )
        assertNotEquals(repair.source.applicationHash, repair.target.mcubootImageHash)
    }

    @Test
    fun `legacy v0001 retains only its exact incomplete-block recovery escape`() {
        val rescue =
            OmiCv1V3012ApplicationUpdateCatalog.functionalRecording0001ToRecoveryOnly0001

        assertEquals(OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY, rescue.intent)
        assertEquals(
            OmiCv1FunctionalRecording0001ApplicationArtifact.manifest.mcubootImageHash,
            rescue.source.applicationHash,
        )
        assertEquals(
            OmiCv1ApplicationUploadMode.INCOMPLETE_FLASH_BLOCK_RESCUE,
            rescue.uploadMode,
        )
    }
}
