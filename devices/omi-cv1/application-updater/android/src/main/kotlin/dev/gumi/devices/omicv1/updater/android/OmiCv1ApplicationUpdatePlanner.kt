package dev.gumi.devices.omicv1.updater.android

import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import java.security.MessageDigest

internal object OmiCv1ApplicationUpdatePlanner {
    fun prepare(
        endpoint: EndpointCandidate,
        inspection: FirmwareImageStateInspection,
        release: OmiCv1ApplicationUpdateRelease,
        imageBytes: ByteArray,
    ): OmiCv1PreparedApplicationUpdate {
        requireExactActiveState(inspection, endpoint, release.source)
        val evidence = McubootApplicationArtifactInspector.inspect(imageBytes, release.target)
        rejectUnless(evidence.mcubootImageHash != release.source.applicationHash) {
            "Target hash equals the active application; confirm/reset would be ambiguous"
        }
        val planId = listOf(
            release.releaseId,
            release.intent.name,
            endpoint.transport.name,
            endpoint.ephemeralId,
            release.source.applicationHash.hex,
            release.source.networkHash.hex,
            release.source.networkEvidencePolicy.name,
            evidence.fileSha256.hex,
            evidence.mcubootImageHash.hex,
        ).joinToString("\u0000").encodeToByteArray().sha256Hex()
        return OmiCv1PreparedApplicationUpdate(planId, endpoint, release, evidence, imageBytes)
    }

    /** Re-runs the exact source/slot gate after authorization and immediately before any upload. */
    fun requirePreflightState(
        inspection: FirmwareImageStateInspection,
        plan: OmiCv1PreparedApplicationUpdate,
    ) {
        requireExactActiveState(inspection, plan.endpoint, plan.release.source)
    }

    fun requireStagedState(
        inspection: FirmwareImageStateInspection,
        plan: OmiCv1PreparedApplicationUpdate,
    ) {
        val target = requireUploadedState(inspection, plan, "Staged")
        rejectUnless(!target.pending && !target.permanent && !target.confirmed) {
            "Uploaded application was selected for boot before explicit confirmation"
        }
    }

    fun requireConfirmedState(
        inspection: FirmwareImageStateInspection,
        plan: OmiCv1PreparedApplicationUpdate,
    ) {
        val target = requireUploadedState(inspection, plan, "Confirmed")
        rejectUnless(target.pending || target.permanent || target.confirmed) {
            "Uploaded application is not marked for the next boot"
        }
    }

    private fun requireUploadedState(
        inspection: FirmwareImageStateInspection,
        plan: OmiCv1PreparedApplicationUpdate,
        stage: String,
    ): FirmwareImageSlot {
        requireInspectionIdentity(inspection, plan.endpoint, "$stage-state")
        requireActiveImage(
            inspection,
            APPLICATION_IMAGE_NUMBER,
            plan.release.source.applicationHash,
            plan.release.source.mcubootVersion,
        )
        requireNetworkState(inspection, plan.release.source)
        requireOnlyExpectedActiveImages(inspection)

        val populatedApplicationSecondarySlots = inspection.slots.filter {
            it.imageNumber == APPLICATION_IMAGE_NUMBER && !it.active && it.isPopulated()
        }
        rejectUnless(populatedApplicationSecondarySlots.size == 1) {
            "Application update must produce exactly one populated inactive slot"
        }
        val target = populatedApplicationSecondarySlots.single()
        rejectUnless(
            target.slotNumber == SECONDARY_SLOT_NUMBER &&
                target.hash == plan.release.target.mcubootImageHash &&
                target.version.matchesCompatibilityVersion(plan.release.target.mcubootVersion) &&
                target.bootable,
        ) { "Uploaded application is not the planned bootable image in secondary slot 1" }
        return target
    }

    private fun requireExactActiveState(
        inspection: FirmwareImageStateInspection,
        endpoint: EndpointCandidate,
        expected: OmiCv1ExpectedActiveImages,
    ) {
        requireInspectionIdentity(inspection, endpoint, "Preflight")
        rejectUnless(inspection.slots.none(FirmwareImageSlot::pending)) {
            "An image is already pending; update preflight requires stable state"
        }
        requireActiveImage(inspection, APPLICATION_IMAGE_NUMBER, expected.applicationHash, expected.mcubootVersion)
        requireNetworkState(inspection, expected)
        requireOnlyExpectedActiveImages(inspection)
        rejectUnless(inspection.slots.none {
            !it.active &&
                it.isPopulated() &&
                (it.imageNumber == APPLICATION_IMAGE_NUMBER || it.imageNumber == NETWORK_IMAGE_NUMBER)
        }) { "Update preflight requires empty application and network secondary slots" }
    }

    private fun requireNetworkState(
        inspection: FirmwareImageStateInspection,
        expected: OmiCv1ExpectedActiveImages,
    ) {
        val networkSlots = inspection.slots.filter { it.imageNumber == NETWORK_IMAGE_NUMBER }
        if (networkSlots.isEmpty()) {
            rejectUnless(
                expected.networkEvidencePolicy ==
                    OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
            ) { "Image 1 is unobserved and this release requires exact network evidence" }
            return
        }

        requireActiveImage(
            inspection,
            NETWORK_IMAGE_NUMBER,
            expected.networkHash,
            expected.mcubootVersion,
        )
        rejectUnless(inspection.slots.none {
            it.imageNumber == NETWORK_IMAGE_NUMBER && (it.pending || (!it.active && it.isPopulated()))
        }) { "Network secondary slot changed during an application-only update" }
    }

    fun validatePostRebootState(
        inspection: FirmwareImageStateInspection,
        pending: OmiCv1ApplicationUpdatePendingValidation,
    ): OmiCv1ApplicationUpdateValidation {
        requireInspectionIdentity(inspection, pending.endpoint, "Post-reboot")
        rejectUnless(inspection.slots.none(FirmwareImageSlot::pending)) {
            "Post-reboot image state is still transitional"
        }
        requireActiveImage(
            inspection,
            APPLICATION_IMAGE_NUMBER,
            pending.expectedApplicationHash,
            pending.expectedMcubootVersion,
        )
        val expected = OmiCv1ExpectedActiveImages(
            applicationHash = pending.expectedApplicationHash,
            networkHash = pending.expectedNetworkHash,
            mcubootVersion = pending.expectedMcubootVersion,
            networkEvidencePolicy = pending.networkEvidencePolicy,
        )
        requireNetworkState(inspection, expected)
        requireOnlyExpectedActiveImages(inspection)
        rejectUnless(inspection.slots.none {
            !it.active &&
                it.isPopulated() &&
                (it.imageNumber == APPLICATION_IMAGE_NUMBER || it.imageNumber == NETWORK_IMAGE_NUMBER)
        }) { "Post-reboot validation requires no populated secondary image" }
        return OmiCv1ApplicationUpdateValidation(
            planId = pending.planId,
            applicationHash = pending.expectedApplicationHash,
            networkImageObserved = inspection.slots.any { it.imageNumber == NETWORK_IMAGE_NUMBER },
        )
    }

    private fun requireInspectionIdentity(
        inspection: FirmwareImageStateInspection,
        endpoint: EndpointCandidate,
        label: String,
    ) {
        rejectUnless(inspection.endpoint == endpoint, OmiCv1ApplicationUpdateFailureCode.ENDPOINT_MISMATCH) {
            "$label image-state evidence belongs to a different endpoint"
        }
        rejectUnless(inspection.protocol == MCUMGR_SMP_PROTOCOL) {
            "$label image-state evidence did not come from MCU Manager SMP"
        }
    }

    private fun requireOnlyExpectedActiveImages(inspection: FirmwareImageStateInspection) {
        rejectUnless(inspection.slots.filter(FirmwareImageSlot::active).all {
            it.imageNumber == APPLICATION_IMAGE_NUMBER || it.imageNumber == NETWORK_IMAGE_NUMBER
        }) { "Unexpected active image exists" }
        rejectUnless(inspection.slots.none {
            it.imageNumber != APPLICATION_IMAGE_NUMBER &&
                it.imageNumber != NETWORK_IMAGE_NUMBER &&
                it.isPopulated()
        }) { "Unexpected populated image exists" }
    }

    private fun FirmwareImageSlot.isPopulated(): Boolean =
        hash != null || version != null || bootable || pending || confirmed || permanent || compressed

    private fun requireActiveImage(
        inspection: FirmwareImageStateInspection,
        imageNumber: Int,
        expectedHash: FirmwareImageHash,
        expectedVersion: String,
    ) {
        val active = inspection.slots.filter { it.imageNumber == imageNumber && it.active }
        rejectUnless(active.size == 1) { "Image $imageNumber must have exactly one active slot" }
        val slot = active.single()
        rejectUnless(slot.slotNumber == 0 && slot.bootable) { "Image $imageNumber active slot is not bootable slot 0" }
        rejectUnless(slot.hash == expectedHash) { "Image $imageNumber active hash does not match the update plan" }
        rejectUnless(slot.version.matchesCompatibilityVersion(expectedVersion)) {
            "Image $imageNumber version does not match compatibility policy"
        }
    }

    private fun String?.matchesCompatibilityVersion(expected: String): Boolean =
        this == expected ||
            expected == MCUBOOT_COMPATIBILITY_VERSION && this == MCUMGR_COMPATIBILITY_WIRE_VERSION

    private fun rejectUnless(
        condition: Boolean,
        code: OmiCv1ApplicationUpdateFailureCode = OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
        message: () -> String,
    ) {
        if (!condition) throw OmiCv1ApplicationUpdateException(code, message())
    }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    const val APPLICATION_IMAGE_NUMBER = 0
    const val NETWORK_IMAGE_NUMBER = 1
    const val SECONDARY_SLOT_NUMBER = 1
    const val MCUBOOT_COMPATIBILITY_VERSION = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION
    const val MCUMGR_COMPATIBILITY_WIRE_VERSION = OmiCv1StockV3012FirmwareOracle.MCUMGR_WIRE_VERSION
    const val MCUMGR_SMP_PROTOCOL = "mcumgr-smp"
}
