package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection

enum class OmiCv1FirmwareOracleStatus {
    MATCHES_PUBLISHED_V3012,
    APPLICATION_MATCH_NETWORK_UNOBSERVED,
    MATCHES_GUMI_CANARY_0001,
    GUMI_CANARY_APPLICATION_MATCH_NETWORK_UNOBSERVED,
    MATCHES_GUMI_RECOVERY_ONLY_0001,
    GUMI_RECOVERY_APPLICATION_MATCH_NETWORK_UNOBSERVED,
    MATCHES_GUMI_CAPTURE_PORT_SELFTEST_0001,
    GUMI_CAPTURE_PORT_SELFTEST_APPLICATION_MATCH_NETWORK_UNOBSERVED,
    INCOMPLETE,
    MISMATCH,
    TRANSITIONAL,
}

enum class OmiCv1FirmwareFindingCode {
    PENDING_IMAGE,
    MISSING_ACTIVE_IMAGE,
    DUPLICATE_ACTIVE_IMAGE,
    UNEXPECTED_ACTIVE_IMAGE,
    UNEXPECTED_ACTIVE_SLOT,
    VERSION_MISMATCH,
    HASH_MISMATCH,
    NOT_BOOTABLE,
}

data class OmiCv1FirmwareFinding(
    val code: OmiCv1FirmwareFindingCode,
    val imageNumber: Int?,
    val slotNumber: Int?,
    val expected: String?,
    val observed: String?,
)

data class OmiCv1FirmwareOracleAssessment(
    val status: OmiCv1FirmwareOracleStatus,
    val releaseTag: String,
    val findings: List<OmiCv1FirmwareFinding>,
)

/** Exact hashes independently extracted from Based Hardware's official v3.0.7 OTA release. */
object OmiCv1StockV3007FirmwareIdentity {
    const val RELEASE_TAG = "Omi_CV1_v3.0.7"
    const val DEVICE_INFORMATION_REVISION = "3.0.7"
    const val DEVICE_INFORMATION_HARDWARE_REVISION = "Based Hardware Omi"
    const val MCUBOOT_VERSION = "0.0.0+0"
    const val MCUMGR_WIRE_VERSION = "0.0.0"
    const val APPLICATION_IMAGE_HASH =
        "ab6364926c7df7371a013dfbcf1e3f73f9386b8500f5cbe4153c2883b798877e"
    const val NETWORK_IMAGE_HASH =
        "f8fc9da4ad429d3ac91e5ba12595a330b61d8f2e8cd4fb969be9349680937649"
}

/**
 * Compares a semantic MCU Manager image-state read with the official Omi CV1 v3.0.12 OTA artifact.
 *
 * The published MCUboot TLV hashes identify image bytes more precisely than the Device Information
 * revision string. Confirmation/permanence flags are intentionally reported by the generic model but
 * are not treated as release identity: overwrite-only boot policy may represent them differently.
 * Zephyr's MCU Manager response also omits build number zero from its version string and may omit a
 * configured image whose active-slot header is not readable, so those cases remain explicit rather
 * than being misclassified as byte mismatches.
 */
object OmiCv1StockV3012FirmwareOracle {
    const val RELEASE_TAG = "Omi_CV1_v3.0.12"
    const val DEVICE_INFORMATION_REVISION = "3.0.12"
    const val DEVICE_INFORMATION_HARDWARE_REVISION = "5.0"
    /** Canonical MCUboot header form used by the inspected release artifact. */
    const val MCUBOOT_VERSION = "0.0.0+0"
    /** Zephyr's image-state wire form omits a zero build number. */
    const val MCUMGR_WIRE_VERSION = "0.0.0"
    const val APPLICATION_IMAGE_HASH =
        "0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36"
    const val NETWORK_IMAGE_HASH =
        "267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089"

    private val expectedHashes = mapOf(
        0 to APPLICATION_IMAGE_HASH,
        1 to NETWORK_IMAGE_HASH,
    )

    fun assess(inspection: FirmwareImageStateInspection): OmiCv1FirmwareOracleAssessment {
        val findings = mutableListOf<OmiCv1FirmwareFinding>()

        inspection.slots.filter(FirmwareImageSlot::pending).forEach { slot ->
            findings += slot.finding(OmiCv1FirmwareFindingCode.PENDING_IMAGE)
        }

        val activeSlots = inspection.slots.filter(FirmwareImageSlot::active)
        activeSlots
            .filter { it.imageNumber !in expectedHashes }
            .forEach { slot ->
                findings += slot.finding(OmiCv1FirmwareFindingCode.UNEXPECTED_ACTIVE_IMAGE)
            }

        expectedHashes.forEach { (imageNumber, expectedHash) ->
            val activeForImage = activeSlots.filter { it.imageNumber == imageNumber }
            if (activeForImage.isEmpty()) {
                findings += OmiCv1FirmwareFinding(
                    code = OmiCv1FirmwareFindingCode.MISSING_ACTIVE_IMAGE,
                    imageNumber = imageNumber,
                    slotNumber = null,
                    expected = "one active image",
                    observed = "none",
                )
                return@forEach
            }
            if (activeForImage.size > 1) {
                findings += OmiCv1FirmwareFinding(
                    code = OmiCv1FirmwareFindingCode.DUPLICATE_ACTIVE_IMAGE,
                    imageNumber = imageNumber,
                    slotNumber = null,
                    expected = "one active image",
                    observed = activeForImage.size.toString(),
                )
            }
            activeForImage.forEach { slot ->
                if (slot.slotNumber != 0) {
                    findings += slot.finding(
                        code = OmiCv1FirmwareFindingCode.UNEXPECTED_ACTIVE_SLOT,
                        expected = "0",
                        observed = slot.slotNumber.toString(),
                    )
                }
                if (!slot.hasExpectedVersion()) {
                    findings += slot.finding(
                        code = OmiCv1FirmwareFindingCode.VERSION_MISMATCH,
                        expected = "$MCUBOOT_VERSION (MCUboot) / $MCUMGR_WIRE_VERSION (MCU Manager)",
                        observed = slot.version ?: "unavailable",
                    )
                }
                if (slot.hash?.hex != expectedHash) {
                    findings += slot.finding(
                        code = OmiCv1FirmwareFindingCode.HASH_MISMATCH,
                        expected = expectedHash,
                        observed = slot.hash?.hex ?: "unavailable",
                    )
                }
                if (!slot.bootable) {
                    findings += slot.finding(
                        code = OmiCv1FirmwareFindingCode.NOT_BOOTABLE,
                        expected = "true",
                        observed = "false",
                    )
                }
            }
        }

        val status = when {
            findings.any { it.code == OmiCv1FirmwareFindingCode.PENDING_IMAGE } ->
                OmiCv1FirmwareOracleStatus.TRANSITIONAL

            findings.any { it.code in mismatchFindingCodes } ->
                OmiCv1FirmwareOracleStatus.MISMATCH

            findings.isApplicationMatchWithUnobservedNetwork() ->
                OmiCv1FirmwareOracleStatus.APPLICATION_MATCH_NETWORK_UNOBSERVED

            findings.any { it.code == OmiCv1FirmwareFindingCode.MISSING_ACTIVE_IMAGE } ->
                OmiCv1FirmwareOracleStatus.INCOMPLETE

            else -> OmiCv1FirmwareOracleStatus.MATCHES_PUBLISHED_V3012
        }

        return OmiCv1FirmwareOracleAssessment(
            status = status,
            releaseTag = RELEASE_TAG,
            findings = findings,
        )
    }

    private val mismatchFindingCodes = setOf(
        OmiCv1FirmwareFindingCode.DUPLICATE_ACTIVE_IMAGE,
        OmiCv1FirmwareFindingCode.UNEXPECTED_ACTIVE_IMAGE,
        OmiCv1FirmwareFindingCode.UNEXPECTED_ACTIVE_SLOT,
        OmiCv1FirmwareFindingCode.VERSION_MISMATCH,
        OmiCv1FirmwareFindingCode.HASH_MISMATCH,
        OmiCv1FirmwareFindingCode.NOT_BOOTABLE,
    )
}

/**
 * Recognizes the behavior-neutral, signed Gumi canary built from the pinned v3.0.12 application.
 *
 * The canary changes only its disclosed identity and boot indicator. Its network-core expectation
 * remains the exact published v3.0.12 image. Complete absence of image 1 stays explicit because the
 * owned stock/canary MCU Manager surface omits an unreadable network-core header.
 */
object OmiCv1GumiCanary0001FirmwareOracle {
    const val RELEASE_TAG = "gumi-canary-0001"
    const val APPLICATION_IMAGE_HASH =
        "d3b3c74c10c4fa110763ae5523d0aeaa20b9f815b8674167486be6ff6f8052ce"

    fun assess(inspection: FirmwareImageStateInspection): OmiCv1FirmwareOracleAssessment {
        val activeApplication = inspection.slots.filter { it.active && it.imageNumber == 0 }
        if (
            activeApplication.size != 1 ||
            activeApplication.single().hash?.hex != APPLICATION_IMAGE_HASH
        ) {
            return OmiCv1FirmwareOracleAssessment(
                status = OmiCv1FirmwareOracleStatus.MISMATCH,
                releaseTag = RELEASE_TAG,
                findings = listOf(
                    OmiCv1FirmwareFinding(
                        code = OmiCv1FirmwareFindingCode.HASH_MISMATCH,
                        imageNumber = 0,
                        slotNumber = activeApplication.singleOrNull()?.slotNumber,
                        expected = APPLICATION_IMAGE_HASH,
                        observed = activeApplication.singleOrNull()?.hash?.hex ?: "unavailable",
                    ),
                ),
            )
        }

        val normalized = inspection.copy(
            slots = inspection.slots.map { slot ->
                if (slot.active && slot.imageNumber == 0) {
                    slot.copy(hash = dev.gumi.edge.sdk.firmware.FirmwareImageHash(
                        OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH,
                    ))
                } else {
                    slot
                }
            },
        )
        val stockShape = OmiCv1StockV3012FirmwareOracle.assess(normalized)
        val status = when (stockShape.status) {
            OmiCv1FirmwareOracleStatus.MATCHES_PUBLISHED_V3012 ->
                OmiCv1FirmwareOracleStatus.MATCHES_GUMI_CANARY_0001

            OmiCv1FirmwareOracleStatus.APPLICATION_MATCH_NETWORK_UNOBSERVED ->
                OmiCv1FirmwareOracleStatus.GUMI_CANARY_APPLICATION_MATCH_NETWORK_UNOBSERVED

            else -> stockShape.status
        }
        return stockShape.copy(status = status, releaseTag = RELEASE_TAG)
    }
}

/** Recognizes the signed, fail-closed recovery-only application built from the v3.0.12 workspace. */
object OmiCv1GumiRecoveryOnly0001FirmwareOracle {
    const val RELEASE_TAG = "gumi-recovery-only-0001"
    const val APPLICATION_IMAGE_HASH =
        "065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57"

    fun assess(inspection: FirmwareImageStateInspection): OmiCv1FirmwareOracleAssessment {
        val activeApplication = inspection.slots.filter { it.active && it.imageNumber == 0 }
        if (
            activeApplication.size != 1 ||
            activeApplication.single().hash?.hex != APPLICATION_IMAGE_HASH
        ) {
            return OmiCv1FirmwareOracleAssessment(
                status = OmiCv1FirmwareOracleStatus.MISMATCH,
                releaseTag = RELEASE_TAG,
                findings = listOf(
                    OmiCv1FirmwareFinding(
                        code = OmiCv1FirmwareFindingCode.HASH_MISMATCH,
                        imageNumber = 0,
                        slotNumber = activeApplication.singleOrNull()?.slotNumber,
                        expected = APPLICATION_IMAGE_HASH,
                        observed = activeApplication.singleOrNull()?.hash?.hex ?: "unavailable",
                    ),
                ),
            )
        }

        val normalized = inspection.copy(
            slots = inspection.slots.map { slot ->
                if (slot.active && slot.imageNumber == 0) {
                    slot.copy(
                        hash = dev.gumi.edge.sdk.firmware.FirmwareImageHash(
                            OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH,
                        ),
                    )
                } else {
                    slot
                }
            },
        )
        val stockShape = OmiCv1StockV3012FirmwareOracle.assess(normalized)
        val status = when (stockShape.status) {
            OmiCv1FirmwareOracleStatus.MATCHES_PUBLISHED_V3012 ->
                OmiCv1FirmwareOracleStatus.MATCHES_GUMI_RECOVERY_ONLY_0001

            OmiCv1FirmwareOracleStatus.APPLICATION_MATCH_NETWORK_UNOBSERVED ->
                OmiCv1FirmwareOracleStatus.GUMI_RECOVERY_APPLICATION_MATCH_NETWORK_UNOBSERVED

            else -> stockShape.status
        }
        return stockShape.copy(status = status, releaseTag = RELEASE_TAG)
    }
}

/** Recognizes the signed, media-free capture-port hardware qualification application. */
object OmiCv1GumiCapturePortSelftest0001FirmwareOracle {
    const val RELEASE_TAG = "gumi-capture-port-selftest-0001"
    const val APPLICATION_IMAGE_HASH =
        "e97fd653a66dce18a7dbd071bc9b21c4fdae4e229de5d245e76ed213c2ca4862"

    fun assess(inspection: FirmwareImageStateInspection): OmiCv1FirmwareOracleAssessment {
        val activeApplication = inspection.slots.filter { it.active && it.imageNumber == 0 }
        if (
            activeApplication.size != 1 ||
            activeApplication.single().hash?.hex != APPLICATION_IMAGE_HASH
        ) {
            return OmiCv1FirmwareOracleAssessment(
                status = OmiCv1FirmwareOracleStatus.MISMATCH,
                releaseTag = RELEASE_TAG,
                findings = listOf(
                    OmiCv1FirmwareFinding(
                        code = OmiCv1FirmwareFindingCode.HASH_MISMATCH,
                        imageNumber = 0,
                        slotNumber = activeApplication.singleOrNull()?.slotNumber,
                        expected = APPLICATION_IMAGE_HASH,
                        observed = activeApplication.singleOrNull()?.hash?.hex ?: "unavailable",
                    ),
                ),
            )
        }

        val normalized = inspection.copy(
            slots = inspection.slots.map { slot ->
                if (slot.active && slot.imageNumber == 0) {
                    slot.copy(
                        hash = dev.gumi.edge.sdk.firmware.FirmwareImageHash(
                            OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH,
                        ),
                    )
                } else {
                    slot
                }
            },
        )
        val stockShape = OmiCv1StockV3012FirmwareOracle.assess(normalized)
        val status = when (stockShape.status) {
            OmiCv1FirmwareOracleStatus.MATCHES_PUBLISHED_V3012 ->
                OmiCv1FirmwareOracleStatus.MATCHES_GUMI_CAPTURE_PORT_SELFTEST_0001

            OmiCv1FirmwareOracleStatus.APPLICATION_MATCH_NETWORK_UNOBSERVED ->
                OmiCv1FirmwareOracleStatus
                    .GUMI_CAPTURE_PORT_SELFTEST_APPLICATION_MATCH_NETWORK_UNOBSERVED

            else -> stockShape.status
        }
        return stockShape.copy(status = status, releaseTag = RELEASE_TAG)
    }
}

/** Selects only an exact known stock, canary, recovery, or capture self-test identity. */
object OmiCv1KnownV3012FirmwareOracle {
    fun assess(inspection: FirmwareImageStateInspection): OmiCv1FirmwareOracleAssessment {
        val captureSelftest = OmiCv1GumiCapturePortSelftest0001FirmwareOracle.assess(inspection)
        if (
            captureSelftest.status ==
            OmiCv1FirmwareOracleStatus.MATCHES_GUMI_CAPTURE_PORT_SELFTEST_0001 ||
            captureSelftest.status ==
            OmiCv1FirmwareOracleStatus
                .GUMI_CAPTURE_PORT_SELFTEST_APPLICATION_MATCH_NETWORK_UNOBSERVED
        ) {
            return captureSelftest
        }
        val recovery = OmiCv1GumiRecoveryOnly0001FirmwareOracle.assess(inspection)
        if (
            recovery.status == OmiCv1FirmwareOracleStatus.MATCHES_GUMI_RECOVERY_ONLY_0001 ||
            recovery.status ==
            OmiCv1FirmwareOracleStatus.GUMI_RECOVERY_APPLICATION_MATCH_NETWORK_UNOBSERVED
        ) {
            return recovery
        }
        val canary = OmiCv1GumiCanary0001FirmwareOracle.assess(inspection)
        if (
            canary.status == OmiCv1FirmwareOracleStatus.MATCHES_GUMI_CANARY_0001 ||
            canary.status ==
            OmiCv1FirmwareOracleStatus.GUMI_CANARY_APPLICATION_MATCH_NETWORK_UNOBSERVED
        ) {
            return canary
        }
        return OmiCv1StockV3012FirmwareOracle.assess(inspection)
    }
}

private fun FirmwareImageSlot.hasExpectedVersion(): Boolean =
    version == OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION ||
        version == OmiCv1StockV3012FirmwareOracle.MCUMGR_WIRE_VERSION

private fun List<OmiCv1FirmwareFinding>.isApplicationMatchWithUnobservedNetwork(): Boolean =
    size == 1 && single().let { finding ->
        finding.code == OmiCv1FirmwareFindingCode.MISSING_ACTIVE_IMAGE &&
            finding.imageNumber == 1
    }

private fun FirmwareImageSlot.finding(
    code: OmiCv1FirmwareFindingCode,
    expected: String? = null,
    observed: String? = null,
) = OmiCv1FirmwareFinding(
    code = code,
    imageNumber = imageNumber,
    slotNumber = slotNumber,
    expected = expected,
    observed = observed,
)
