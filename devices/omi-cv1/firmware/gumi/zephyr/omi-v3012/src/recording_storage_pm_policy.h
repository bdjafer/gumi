#ifndef GUMI_OMI_V3012_RECORDING_STORAGE_PM_POLICY_H
#define GUMI_OMI_V3012_RECORDING_STORAGE_PM_POLICY_H

/*
 * Normalize the advisory result of asking an already powered storage host to
 * resume. The Omi CV1 SPI SDHC driver deliberately has no device-PM callback,
 * so Zephyr reports -ENOSYS even though the host is usable through disk access.
 */
int gumi_omi_v3012_recording_storage_normalize_resume_result(int result);

#endif
