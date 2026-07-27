package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1GattEvidence
import dev.gumi.devices.omicv1.OmiCv1StockV3007FirmwareIdentity
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OmiCv1StockNormalizationTest {
    @Test
    fun `prepares exact official v3007 to v3012 dual-image plan`() {
        val endpoint = endpoint("normalization-source")
        val prepared = prepare(endpoint)

        assertEquals(
            OmiCv1StockV3012ApplicationArtifact.manifest.mcubootImageHash,
            prepared.applicationEvidence.mcubootImageHash,
        )
        assertEquals(
            OmiCv1StockV3012NetworkArtifact.manifest.mcubootImageHash,
            prepared.networkEvidence.mcubootImageHash,
        )
        assertTrue(prepared.planId.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `rejects the exact application hash when device information is not v3007`() {
        val endpoint = endpoint("wrong-device-revision")
        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1StockNormalizationPlanner.prepare(
                endpoint,
                sourceInspection(endpoint),
                deviceEvidence(endpoint, firmwareRevision = "3.0.12"),
                OmiCv1StockNormalizationCatalog.v3007ToV3012,
                exactArtifacts(),
            )
        }

        assertEquals(
            OmiCv1ApplicationUpdateFailureCode.STOCK_NORMALIZATION_STATE_REJECTED,
            error.code,
        )
    }

    @Test
    fun `accepts only exact target secondary images as a resumable state`() {
        val endpoint = endpoint("resume")
        val release = OmiCv1StockNormalizationCatalog.v3007ToV3012
        val resumable = sourceInspection(endpoint).copy(
            slots = sourceInspection(endpoint).slots + listOf(
                emptySlot(0).copy(
                    hash = release.targetApplication.mcubootImageHash,
                    version = "0.0.0",
                    bootable = true,
                ),
                emptySlot(1).copy(
                    hash = release.targetNetwork.mcubootImageHash,
                    version = "0.0.0",
                    bootable = true,
                    pending = true,
                    permanent = true,
                ),
            ),
        )

        OmiCv1StockNormalizationPlanner.prepare(
            endpoint,
            resumable,
            deviceEvidence(endpoint),
            release,
            exactArtifacts(),
        )

        val wrongSecondary = resumable.copy(
            slots = resumable.slots.map {
                if (it.imageNumber == 1 && !it.active) {
                    it.copy(hash = FirmwareImageHash("aa".repeat(32)))
                } else {
                    it
                }
            },
        )
        assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1StockNormalizationPlanner.prepare(
                endpoint,
                wrongSecondary,
                deviceEvidence(endpoint),
                release,
                exactArtifacts(),
            )
        }
    }

    @Test
    fun `executor repeats image-state device identity and battery gates before mutation`() = runTest {
        val endpoint = endpoint("executor")
        val prepared = prepare(endpoint)
        val session = FakeNormalizationSession(sourceInspection(endpoint))
        val executor = OmiCv1StockNormalizationExecutor(
            sessions = OmiCv1StockNormalizationSessionFactory { session },
            devicePreflight = OmiCv1FlashLabDevicePreflightProbe {
                deviceEvidence(it, batteryPercent = 100)
            },
            clock = MonotonicMillisClock { 100 },
        )
        val progress = mutableListOf<OmiCv1StockNormalizationProgress>()

        val pending = executor.execute(
            OmiCv1StockNormalizationAuthorization(
                prepared,
                prepared.planId,
                expiresAtMonotonicMillis = 1_000,
            ),
            progress::add,
        )

        assertEquals(1, session.normalizeCount)
        assertTrue(session.released)
        assertEquals(
            OmiCv1StockV3012NetworkArtifact.manifest.fileSizeBytes,
            session.networkBytes,
        )
        assertEquals(
            OmiCv1StockV3012ApplicationArtifact.manifest.mcubootImageHash,
            pending.expectedApplicationHash,
        )
        assertEquals(
            OmiCv1StockNormalizationStage.AWAITING_POST_REBOOT_VALIDATION,
            progress.last().stage,
        )
    }

    @Test
    fun `executor retains low battery evidence without blocking normalization`() = runTest {
        val endpoint = endpoint("low-battery")
        val prepared = prepare(endpoint)
        val session = FakeNormalizationSession(sourceInspection(endpoint))
        val executor = OmiCv1StockNormalizationExecutor(
            sessions = OmiCv1StockNormalizationSessionFactory { session },
            devicePreflight = OmiCv1FlashLabDevicePreflightProbe {
                deviceEvidence(it, batteryPercent = 1)
            },
            clock = MonotonicMillisClock { 100 },
        )

        executor.execute(
            OmiCv1StockNormalizationAuthorization(
                prepared,
                prepared.planId,
                expiresAtMonotonicMillis = 1_000,
            ),
            {},
        )

        assertEquals(1, session.normalizeCount)
        assertTrue(session.released)
    }

    @Test
    fun `post-reboot validation accepts exact v3012 application with network unobserved`() {
        val endpoint = endpoint("post-reboot")
        val pending = OmiCv1StockNormalizationPendingValidation(
            planId = "plan",
            endpoint = endpoint,
            expectedApplicationHash =
                OmiCv1StockV3012ApplicationArtifact.manifest.mcubootImageHash,
            expectedNetworkHash = OmiCv1StockV3012NetworkArtifact.manifest.mcubootImageHash,
            expectedFirmwareRevision = "3.0.12",
        )
        val validation = OmiCv1StockNormalizationPlanner.validatePostReboot(
            inspection(
                endpoint,
                activeSlot(0, pending.expectedApplicationHash),
            ),
            deviceEvidence(endpoint, firmwareRevision = "3.0.12"),
            pending,
        )

        assertFalse(validation.networkImageObserved)
        assertEquals("3.0.12", validation.firmwareRevision)
    }

    private fun prepare(endpoint: EndpointCandidate): OmiCv1PreparedStockNormalization =
        OmiCv1StockNormalizationPlanner.prepare(
            endpoint,
            sourceInspection(endpoint),
            deviceEvidence(endpoint),
            OmiCv1StockNormalizationCatalog.v3007ToV3012,
            exactArtifacts(),
        )

    private fun sourceInspection(endpoint: EndpointCandidate): FirmwareImageStateInspection =
        inspection(
            endpoint,
            activeSlot(
                0,
                FirmwareImageHash(OmiCv1StockV3007FirmwareIdentity.APPLICATION_IMAGE_HASH),
            ),
        )

    private fun deviceEvidence(
        endpoint: EndpointCandidate,
        firmwareRevision: String = "3.0.7",
        batteryPercent: Int = 100,
    ) = OmiCv1FlashLabDevicePreflightEvidence(
        endpoint = endpoint,
        identity = OmiCv1GattEvidence(
            manufacturer = "Based Hardware",
            modelNumber = "Omi CV 1",
            firmwareRevision = firmwareRevision,
            hardwareRevision = when (firmwareRevision) {
                "3.0.12" -> "5.0"
                else -> "Based Hardware Omi"
            },
            softwareRevision = null,
            batteryPercent = batteryPercent,
            storage = null,
            storageRawHex = null,
            recoveryStatusRawHex = null,
            successfulReadLengths = emptyMap(),
            readFailures = emptyList(),
        ),
        serviceCount = 8,
        characteristicCount = 16,
    )

    private fun exactArtifacts(): OmiCv1StockNormalizationArtifacts {
        val root = repositoryRoot()
        val application = File(
            root,
            "local/firmware/omi-cv1/stock-v3.0.12/omi.signed.bin",
        ).readBytes()
        val archive = File(
            root,
            "local/firmware/omi-cv1/stock-v3.0.12/Omi_CV1_OTA_v3.0.12.zip",
        )
        val network = ZipFile(archive).use { zip ->
            zip.getInputStream(requireNotNull(zip.getEntry("ipc_radio.bin"))).use { it.readBytes() }
        }
        return OmiCv1StockNormalizationArtifacts(application, network)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(workingDirectory)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Cannot locate Gumi repository root")
    }

    private class FakeNormalizationSession(
        private val inspection: FirmwareImageStateInspection,
    ) : OmiCv1StockNormalizationSession {
        var normalizeCount = 0
        var networkBytes = 0
        var released = false

        override suspend fun inspect(): FirmwareImageStateInspection = inspection

        override suspend fun normalize(
            applicationBytes: ByteArray,
            networkBytes: ByteArray,
            onProgress: (OmiCv1StockNormalizationProgress) -> Unit,
        ) {
            normalizeCount += 1
            this.networkBytes = networkBytes.size
            onProgress(
                OmiCv1StockNormalizationProgress(
                    OmiCv1StockNormalizationStage.UPLOADING,
                    applicationBytes.size,
                    applicationBytes.size,
                ),
            )
        }

        override fun cancel() = Unit

        override fun release() {
            released = true
        }
    }
}
