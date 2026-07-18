package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection

enum class OmiCv1FirmwareOracleStatus {
    MATCHES_PUBLISHED_V3012,
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

/**
 * Compares a semantic MCU Manager image-state read with the official Omi CV1 v3.0.12 OTA artifact.
 *
 * The published MCUboot TLV hashes identify image bytes more precisely than the Device Information
 * revision string. Confirmation/permanence flags are intentionally reported by the generic model but
 * are not treated as release identity: overwrite-only boot policy may represent them differently.
 */
object OmiCv1StockV3012FirmwareOracle {
    const val RELEASE_TAG = "Omi_CV1_v3.0.12"
    const val MCUBOOT_VERSION = "0.0.0+0"
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
                if (slot.version != MCUBOOT_VERSION) {
                    findings += slot.finding(
                        code = OmiCv1FirmwareFindingCode.VERSION_MISMATCH,
                        expected = MCUBOOT_VERSION,
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
