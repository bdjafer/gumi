#include "gumi/omi_v3012_legacy_storage_reclaimer.h"

#include "gumi/legacy_storage_reclaimer.h"

#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include <ff.h>
#include <zephyr/device.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/fs/fs.h>
#include <zephyr/pm/device.h>
#include <zephyr/storage/disk_access.h>

#include "recording_storage_pm_policy.h"

#define GUMI_LEGACY_STORAGE_DISK_NAME "SD"
#define GUMI_LEGACY_STORAGE_MOUNT_POINT "/SD:"
#define GUMI_LEGACY_STORAGE_TARGET "/SD:/audio/a01.txt"

static const struct device *const gumi_sd_device =
    DEVICE_DT_GET(DT_NODELABEL(sdhc0));
static const struct gpio_dt_spec gumi_sd_enable =
    GPIO_DT_SPEC_GET_OR(DT_NODELABEL(sdcard_en_pin), gpios, {0});

static int read_free_bytes(uint64_t *free_bytes)
{
    struct fs_statvfs stats;
    uint64_t fragment_size;
    uint64_t free_fragments;
    int error;

    if (free_bytes == NULL) {
        return -EINVAL;
    }
    error = fs_statvfs(GUMI_LEGACY_STORAGE_MOUNT_POINT, &stats);
    if (error < 0) {
        return error;
    }
    fragment_size = (uint64_t)stats.f_frsize;
    free_fragments = (uint64_t)stats.f_bfree;
    if (fragment_size == UINT64_C(0) ||
        free_fragments > UINT64_MAX / fragment_size) {
        return -EOVERFLOW;
    }
    *free_bytes = fragment_size * free_fragments;
    return 0;
}

static int finish_storage(
    struct fs_mount_t *mount,
    bool mounted,
    bool gpio_configured,
    int primary_error
)
{
    int error = primary_error;
    int cleanup_error;

    if (mounted) {
        cleanup_error = fs_unmount(mount);
        if (error == 0 && cleanup_error < 0) {
            error = cleanup_error;
        }
    }
    cleanup_error = pm_device_action_run(
        gumi_sd_device,
        PM_DEVICE_ACTION_SUSPEND
    );
    if (cleanup_error == -EALREADY || cleanup_error == -ENOSYS) {
        cleanup_error = 0;
    }
    if (error == 0 && cleanup_error < 0) {
        error = cleanup_error;
    }
    if (gpio_configured) {
        cleanup_error = gpio_pin_set_dt(&gumi_sd_enable, 0);
        if (error == 0 && cleanup_error < 0) {
            error = cleanup_error;
        }
    }
    return error;
}

int gumi_omi_v3012_legacy_storage_reclaim(
    gumi_omi_v3012_legacy_reclaim_outcome *outcome
)
{
    static const gumi_legacy_storage_reclaimer_policy policy = {
        .expected_target_size_bytes =
            GUMI_OMI_V3012_LEGACY_TARGET_SIZE_BYTES,
        .minimum_free_bytes =
            GUMI_OMI_V3012_LEGACY_MINIMUM_FREE_BYTES,
    };
    gumi_legacy_storage_reclaimer_observation observation = {0};
    gumi_legacy_storage_reclaimer_decision decision;
    struct fs_mount_t mount;
    FATFS fat_fs;
    struct fs_dirent target;
    bool gpio_configured = false;
    bool mounted = false;
    int error;

    if (outcome == NULL) {
        return -EINVAL;
    }
    memset(outcome, 0, sizeof(*outcome));
    outcome->result = GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_FAILED;

    if (!device_is_ready(gumi_sd_device) ||
        !gpio_is_ready_dt(&gumi_sd_enable)) {
        outcome->error = -ENODEV;
        return outcome->error;
    }
    error = gpio_pin_configure_dt(&gumi_sd_enable, GPIO_OUTPUT_ACTIVE);
    if (error < 0) {
        outcome->error = error;
        return error;
    }
    gpio_configured = true;
    error = pm_device_action_run(gumi_sd_device, PM_DEVICE_ACTION_RESUME);
    error = gumi_omi_v3012_recording_storage_normalize_resume_result(error);
    if (error < 0) {
        outcome->error = finish_storage(
            &mount,
            false,
            gpio_configured,
            error
        );
        return outcome->error;
    }

    memset(&mount, 0, sizeof(mount));
    memset(&fat_fs, 0, sizeof(fat_fs));
    mount.type = FS_FATFS;
    mount.fs_data = &fat_fs;
    mount.flags = FS_MOUNT_FLAG_NO_FORMAT | FS_MOUNT_FLAG_USE_DISK_ACCESS;
    mount.storage_dev = (void *)GUMI_LEGACY_STORAGE_DISK_NAME;
    mount.mnt_point = GUMI_LEGACY_STORAGE_MOUNT_POINT;
    error = fs_mount(&mount);
    if (error < 0) {
        outcome->error = finish_storage(
            &mount,
            false,
            gpio_configured,
            error
        );
        return outcome->error;
    }
    mounted = true;
    outcome->flags |= GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_VOLUME_MOUNTED;

    error = read_free_bytes(&outcome->free_bytes_before);
    if (error < 0) {
        outcome->error = finish_storage(
            &mount,
            mounted,
            gpio_configured,
            error
        );
        return outcome->error;
    }
    memset(&target, 0, sizeof(target));
    error = fs_stat(GUMI_LEGACY_STORAGE_TARGET, &target);
    if (error == 0) {
        observation.target_present = true;
        observation.target_regular_file =
            target.type == FS_DIR_ENTRY_FILE;
        observation.target_size_bytes = (uint64_t)target.size;
        outcome->target_size_bytes = observation.target_size_bytes;
        outcome->flags |=
            GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_TARGET_OBSERVED;
    } else if (error != -ENOENT) {
        outcome->error = finish_storage(
            &mount,
            mounted,
            gpio_configured,
            error
        );
        return outcome->error;
    }
    observation.free_bytes = outcome->free_bytes_before;
    decision = gumi_legacy_storage_reclaimer_decide(&policy, &observation);

    if (decision.action ==
        GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_DELETE_EXACT_TARGET) {
        outcome->flags |=
            GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_TARGET_EXACT |
            GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_DELETE_ATTEMPTED;
        error = fs_unlink(GUMI_LEGACY_STORAGE_TARGET);
        if (error < 0) {
            outcome->error = finish_storage(
                &mount,
                mounted,
                gpio_configured,
                error
            );
            return outcome->error;
        }
    } else if (decision.action ==
        GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_REFUSE) {
        outcome->result = GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_REFUSED;
        outcome->error = finish_storage(
            &mount,
            mounted,
            gpio_configured,
            -EPERM
        );
        return outcome->error;
    }

    memset(&target, 0, sizeof(target));
    error = fs_stat(GUMI_LEGACY_STORAGE_TARGET, &target);
    if (error != -ENOENT) {
        outcome->error = finish_storage(
            &mount,
            mounted,
            gpio_configured,
            error < 0 ? error : -EIO
        );
        return outcome->error;
    }
    outcome->flags |=
        GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_TARGET_ABSENT;
    error = read_free_bytes(&outcome->free_bytes_after);
    if (error == 0 &&
        outcome->free_bytes_after <
            GUMI_OMI_V3012_LEGACY_MINIMUM_FREE_BYTES) {
        error = -ENOSPC;
    }
    if (error == 0) {
        outcome->flags |=
            GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_MINIMUM_FREE_PROVEN;
        outcome->result =
            decision.action ==
                GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_DELETE_EXACT_TARGET
                ? GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_RECLAIMED
                : GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_ALREADY_ABSENT;
    }
    outcome->error = finish_storage(
        &mount,
        mounted,
        gpio_configured,
        error
    );
    if (outcome->error < 0) {
        outcome->result = GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_FAILED;
    }
    return outcome->error;
}
