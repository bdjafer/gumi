package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.firmware.FirmwareMaintenanceStage
import kotlin.test.Test
import kotlin.test.assertEquals

class OmiCv1FlashLabMaintenanceProjectionTest {
    @Test
    fun `every diagnostic phase maps to portable maintenance vocabulary`() {
        val expected = mapOf(
            OmiCv1FlashLabPhase.SAFETY_REVIEW to FirmwareMaintenanceStage.SAFETY_REVIEW,
            OmiCv1FlashLabPhase.SCANNING to FirmwareMaintenanceStage.SAFETY_REVIEW,
            OmiCv1FlashLabPhase.DEVICE_SELECTED to FirmwareMaintenanceStage.SAFETY_REVIEW,
            OmiCv1FlashLabPhase.READING_PREFLIGHT to FirmwareMaintenanceStage.PREFLIGHT,
            OmiCv1FlashLabPhase.READY_TO_AUTHORIZE to
                FirmwareMaintenanceStage.AWAITING_AUTHORIZATION,
            OmiCv1FlashLabPhase.UPDATING to FirmwareMaintenanceStage.APPLYING,
            OmiCv1FlashLabPhase.AWAITING_POST_REBOOT_SCAN to
                FirmwareMaintenanceStage.AWAITING_RESTART,
            OmiCv1FlashLabPhase.POST_REBOOT_DEVICE_SELECTED to
                FirmwareMaintenanceStage.AWAITING_RESTART,
            OmiCv1FlashLabPhase.VALIDATING_POST_REBOOT to FirmwareMaintenanceStage.VALIDATING,
            OmiCv1FlashLabPhase.READING_ACTIVE_CAPTURE_SELFTEST to
                FirmwareMaintenanceStage.VALIDATING,
            OmiCv1FlashLabPhase.READING_ACTIVE_FUNCTIONAL to
                FirmwareMaintenanceStage.VALIDATING,
            OmiCv1FlashLabPhase.RECHECKING_RECOVERY to FirmwareMaintenanceStage.VALIDATING,
            OmiCv1FlashLabPhase.RECHECKING_FUNCTIONAL to FirmwareMaintenanceStage.VALIDATING,
            OmiCv1FlashLabPhase.RUNNING_CAPTURE_SELFTEST to FirmwareMaintenanceStage.VALIDATING,
            OmiCv1FlashLabPhase.AWAITING_CAPTURE_CONFIRMATION to
                FirmwareMaintenanceStage.VALIDATING,
            OmiCv1FlashLabPhase.VALIDATED to FirmwareMaintenanceStage.COMPLETE,
            OmiCv1FlashLabPhase.STOPPED_ON_FAILURE to FirmwareMaintenanceStage.FAILED,
        )

        assertEquals(OmiCv1FlashLabPhase.entries.toSet(), expected.keys)
        expected.forEach { (phase, stage) ->
            assertEquals(stage, OmiCv1FlashLabUiState(phase = phase).portableMaintenanceStage)
        }
    }
}
