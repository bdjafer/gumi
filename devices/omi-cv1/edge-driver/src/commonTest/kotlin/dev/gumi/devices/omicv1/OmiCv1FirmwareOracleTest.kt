package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OmiCv1FirmwareOracleTest {
    @Test
    fun `matches the two published active images and ignores empty secondary slots`() {
        val assessment = OmiCv1StockV3012FirmwareOracle.assess(
            inspection(
                activeImage(0, OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH),
                inactiveSlot(0),
                activeImage(1, OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH),
                inactiveSlot(1),
            ),
        )

        assertEquals(OmiCv1FirmwareOracleStatus.MATCHES_PUBLISHED_V3012, assessment.status)
        assertTrue(assessment.findings.isEmpty())
    }

    @Test
    fun `rejects a different application image hash`() {
        val assessment = OmiCv1StockV3012FirmwareOracle.assess(
            inspection(
                activeImage(0, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                activeImage(1, OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH),
            ),
        )

        assertEquals(OmiCv1FirmwareOracleStatus.MISMATCH, assessment.status)
        assertEquals(
            listOf(OmiCv1FirmwareFindingCode.HASH_MISMATCH),
            assessment.findings.map(OmiCv1FirmwareFinding::code),
        )
    }

    @Test
    fun `reports exact application identity when the network image is unobserved`() {
        val assessment = OmiCv1StockV3012FirmwareOracle.assess(
            inspection(
                activeImage(
                    0,
                    OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH,
                    version = OmiCv1StockV3012FirmwareOracle.MCUMGR_WIRE_VERSION,
                ),
            ),
        )

        assertEquals(
            OmiCv1FirmwareOracleStatus.APPLICATION_MATCH_NETWORK_UNOBSERVED,
            assessment.status,
        )
        assertEquals(
            listOf(OmiCv1FirmwareFindingCode.MISSING_ACTIVE_IMAGE),
            assessment.findings.map(OmiCv1FirmwareFinding::code),
        )
        assertEquals(1, assessment.findings.single().imageNumber)
    }

    @Test
    fun `keeps a network-only observation incomplete`() {
        val assessment = OmiCv1StockV3012FirmwareOracle.assess(
            inspection(activeImage(1, OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH)),
        )

        assertEquals(OmiCv1FirmwareOracleStatus.INCOMPLETE, assessment.status)
        assertEquals(
            listOf(OmiCv1FirmwareFindingCode.MISSING_ACTIVE_IMAGE),
            assessment.findings.map(OmiCv1FirmwareFinding::code),
        )
        assertEquals(0, assessment.findings.single().imageNumber)
    }

    @Test
    fun `rejects a non-equivalent application version even when its hash matches`() {
        val assessment = OmiCv1StockV3012FirmwareOracle.assess(
            inspection(
                activeImage(
                    0,
                    OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH,
                    version = "0.0.1",
                ),
            ),
        )

        assertEquals(OmiCv1FirmwareOracleStatus.MISMATCH, assessment.status)
        assertTrue(
            assessment.findings.any { it.code == OmiCv1FirmwareFindingCode.VERSION_MISMATCH },
        )
    }

    @Test
    fun `pending state takes transitional precedence over a mismatch`() {
        val assessment = OmiCv1StockV3012FirmwareOracle.assess(
            inspection(
                activeImage(
                    imageNumber = 0,
                    hash = OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH,
                ),
                activeImage(
                    imageNumber = 1,
                    hash = OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH,
                ).copy(pending = true, bootable = false),
            ),
        )

        assertEquals(OmiCv1FirmwareOracleStatus.TRANSITIONAL, assessment.status)
        assertTrue(assessment.findings.any { it.code == OmiCv1FirmwareFindingCode.PENDING_IMAGE })
        assertTrue(assessment.findings.any { it.code == OmiCv1FirmwareFindingCode.NOT_BOOTABLE })
    }

    @Test
    fun `recognizes exact behavior neutral canary with unobserved network`() {
        val assessment = OmiCv1GumiCanary0001FirmwareOracle.assess(
            inspection(
                activeImage(
                    0,
                    OmiCv1GumiCanary0001FirmwareOracle.APPLICATION_IMAGE_HASH,
                    version = OmiCv1StockV3012FirmwareOracle.MCUMGR_WIRE_VERSION,
                ),
            ),
        )

        assertEquals(
            OmiCv1FirmwareOracleStatus.GUMI_CANARY_APPLICATION_MATCH_NETWORK_UNOBSERVED,
            assessment.status,
        )
        assertEquals(OmiCv1GumiCanary0001FirmwareOracle.RELEASE_TAG, assessment.releaseTag)
        assertEquals(
            listOf(OmiCv1FirmwareFindingCode.MISSING_ACTIVE_IMAGE),
            assessment.findings.map(OmiCv1FirmwareFinding::code),
        )
    }

    @Test
    fun `recognizes exact behavior neutral canary with published network image`() {
        val assessment = OmiCv1KnownV3012FirmwareOracle.assess(
            inspection(
                activeImage(0, OmiCv1GumiCanary0001FirmwareOracle.APPLICATION_IMAGE_HASH),
                activeImage(1, OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH),
            ),
        )

        assertEquals(OmiCv1FirmwareOracleStatus.MATCHES_GUMI_CANARY_0001, assessment.status)
        assertTrue(assessment.findings.isEmpty())
    }

    @Test
    fun `canary oracle rejects stock application bytes`() {
        val assessment = OmiCv1GumiCanary0001FirmwareOracle.assess(
            inspection(activeImage(0, OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH)),
        )

        assertEquals(OmiCv1FirmwareOracleStatus.MISMATCH, assessment.status)
        assertEquals(
            OmiCv1GumiCanary0001FirmwareOracle.APPLICATION_IMAGE_HASH,
            assessment.findings.single().expected,
        )
    }

    private fun inspection(vararg slots: FirmwareImageSlot) = FirmwareImageStateInspection(
        endpoint = EndpointCandidate(TransportKind.BLE, "ephemeral"),
        protocol = "mcumgr-smp",
        slots = slots.toList(),
        splitStatus = 0,
    )

    private fun activeImage(
        imageNumber: Int,
        hash: String,
        version: String = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
    ) = FirmwareImageSlot(
        imageNumber = imageNumber,
        slotNumber = 0,
        version = version,
        hash = FirmwareImageHash(hash),
        bootable = true,
        pending = false,
        confirmed = true,
        active = true,
        permanent = true,
        compressed = false,
    )

    private fun inactiveSlot(imageNumber: Int) = FirmwareImageSlot(
        imageNumber = imageNumber,
        slotNumber = 1,
        version = null,
        hash = null,
        bootable = false,
        pending = false,
        confirmed = false,
        active = false,
        permanent = false,
        compressed = false,
    )
}
