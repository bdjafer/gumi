#include "gumi/omi_v3012_legacy_storage_reclaimer.h"
#include "gumi/omi_v3012_mic.h"
#include "gumi/omi_v3012_recovery.h"
#include "gumi/omi_v3012_reset.h"
#include "gumi/recovery.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/device.h>
#include <zephyr/drivers/watchdog.h>
#include <zephyr/kernel.h>
#include <zephyr/task_wdt/task_wdt.h>
#include <zephyr/sys/util.h>

#define GUMI_RECLAIMER_WATCHDOG_TIMEOUT_MS 15000U

#if DT_HAS_COMPAT_STATUS_OKAY(nordic_nrf_wdt)
#define GUMI_RECLAIMER_WATCHDOG_NODE \
    DT_COMPAT_GET_ANY_STATUS_OKAY(nordic_nrf_wdt)
#else
#define GUMI_RECLAIMER_WATCHDOG_NODE DT_INVALID_NODE
#endif

static gumi_omi_v3012_legacy_reclaimer_status reclaimer_status;
static int reclaimer_watchdog_channel = -1;

static void discard_microphone_frame(
    const int16_t *samples,
    size_t sample_count,
    void *context
)
{
    ARG_UNUSED(samples);
    ARG_UNUSED(sample_count);
    ARG_UNUSED(context);
}

static void observe_unexpected_microphone_fault(int error, void *context)
{
    ARG_UNUSED(error);
    ARG_UNUSED(context);
}

static uint64_t monotonic_milliseconds(void)
{
    int64_t now = k_uptime_get();

    return now > 0 ? (uint64_t)now : UINT64_C(0);
}

static int start_reclaimer_watchdog(void)
{
    const struct device *watchdog =
        DEVICE_DT_GET_OR_NULL(GUMI_RECLAIMER_WATCHDOG_NODE);
    int error;

    if (watchdog == NULL || !device_is_ready(watchdog)) {
        return -ENODEV;
    }
    error = task_wdt_init(watchdog);
    if (error < 0) {
        return error;
    }
    reclaimer_watchdog_channel = task_wdt_add(
        GUMI_RECLAIMER_WATCHDOG_TIMEOUT_MS,
        NULL,
        NULL
    );
    return reclaimer_watchdog_channel < 0
        ? reclaimer_watchdog_channel
        : 0;
}

static void feed_reclaimer_watchdog(void)
{
    if (reclaimer_watchdog_channel >= 0 &&
        task_wdt_feed(reclaimer_watchdog_channel) < 0) {
        gumi_omi_v3012_whole_device_reboot();
    }
}

static void publish_status(
    gumi_omi_v3012_legacy_reclaimer_phase phase,
    int error
)
{
    reclaimer_status.phase = phase;
    reclaimer_status.last_error = error;
    reclaimer_status.generation += 1U;
    (void)gumi_omi_v3012_legacy_reclaimer_status_publish(
        &reclaimer_status
    );
}

static int complete_transition(
    gumi_recovery_supervisor *supervisor,
    const gumi_recovery_result *previous,
    gumi_recovery_completion completion,
    gumi_recovery_result *next
)
{
    gumi_recovery_status status = gumi_recovery_complete(
        supervisor,
        monotonic_milliseconds(),
        previous->action.transition_id,
        completion,
        next
    );

    if (status != GUMI_RECOVERY_STATUS_OK) {
        return -EINVAL;
    }
    return gumi_omi_v3012_recovery_status_publish(supervisor);
}

static void admit_followup_mutation(void)
{
    gumi_omi_v3012_legacy_reclaimer_mutation_admission_set(true);
    reclaimer_status.flags |=
        GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_MUTATION_ADMITTED;
}

int main(void)
{
    const gumi_recovery_boot_evidence evidence = {
        .explicit_safe_mode = true,
        .persisted_safe_mode = false,
        .watchdog_or_lockup_reset = false,
    };
    gumi_recovery_supervisor supervisor;
    gumi_recovery_result result;
    gumi_recovery_result next;
    gumi_omi_v3012_legacy_reclaim_outcome outcome;
    gumi_recovery_status recovery_result;
    int error;

    memset(&reclaimer_status, 0, sizeof(reclaimer_status));
    gumi_omi_v3012_legacy_reclaimer_mgmt_guard_start();
    publish_status(GUMI_OMI_V3012_LEGACY_RECLAIMER_COLD, 0);
    error = start_reclaimer_watchdog();
    if (error < 0) {
        publish_status(GUMI_OMI_V3012_LEGACY_RECLAIMER_FAILED, error);
        return error;
    }
    recovery_result = gumi_recovery_supervisor_init(
        &supervisor,
        &evidence
    );
    if (recovery_result != GUMI_RECOVERY_STATUS_OK) {
        publish_status(GUMI_OMI_V3012_LEGACY_RECLAIMER_FAILED, -EPROTO);
        return -EINVAL;
    }
    recovery_result = gumi_recovery_begin(
        &supervisor,
        monotonic_milliseconds(),
        &result
    );
    if (recovery_result != GUMI_RECOVERY_STATUS_OK ||
        !result.has_action ||
        result.action.type != GUMI_RECOVERY_ACTION_START_TRANSPORT) {
        publish_status(GUMI_OMI_V3012_LEGACY_RECLAIMER_FAILED, -EPROTO);
        return -EINVAL;
    }
    error = gumi_omi_v3012_recovery_status_publish(&supervisor);
    if (error < 0) {
        publish_status(GUMI_OMI_V3012_LEGACY_RECLAIMER_FAILED, error);
        return error;
    }
    gumi_omi_v3012_network_core_cold_start();
    error = gumi_omi_v3012_recovery_transport_start();
    feed_reclaimer_watchdog();
    if (error < 0) {
        (void)complete_transition(
            &supervisor,
            &result,
            GUMI_RECOVERY_COMPLETION_TRANSPORT_FAILED,
            &next
        );
        publish_status(GUMI_OMI_V3012_LEGACY_RECLAIMER_FAILED, error);
        return error;
    }
    reclaimer_status.flags |=
        GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_TRANSPORT_READY;
    publish_status(
        GUMI_OMI_V3012_LEGACY_RECLAIMER_SAFE_TRANSPORT_READY,
        0
    );
    error = complete_transition(
        &supervisor,
        &result,
        GUMI_RECOVERY_COMPLETION_TRANSPORT_READY,
        &next
    );
    if (error < 0 || !next.has_action ||
        next.action.type != GUMI_RECOVERY_ACTION_VERIFY_MICROPHONE_OFF) {
        publish_status(
            GUMI_OMI_V3012_LEGACY_RECLAIMER_FAILED,
            error < 0 ? error : -EPROTO
        );
        return error < 0 ? error : -EINVAL;
    }
    result = next;
    error = gumi_omi_v3012_mic_init(
        discard_microphone_frame,
        observe_unexpected_microphone_fault,
        NULL,
        0U
    );
    feed_reclaimer_watchdog();
    if (error < 0 ||
        gumi_omi_v3012_mic_get_truth() !=
            GUMI_OMI_V3012_MIC_VERIFIED_OFF) {
        (void)complete_transition(
            &supervisor,
            &result,
            GUMI_RECOVERY_COMPLETION_MICROPHONE_OFF_FAILED,
            &next
        );
        publish_status(
            GUMI_OMI_V3012_LEGACY_RECLAIMER_FAILED,
            error < 0 ? error : -EIO
        );
        return error < 0 ? error : -EIO;
    }
    reclaimer_status.flags |=
        GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_MICROPHONE_VERIFIED_OFF;
    error = complete_transition(
        &supervisor,
        &result,
        GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF,
        &next
    );
    if (error < 0) {
        publish_status(GUMI_OMI_V3012_LEGACY_RECLAIMER_FAILED, error);
        return error;
    }

    publish_status(GUMI_OMI_V3012_LEGACY_RECLAIMER_INSPECTING, 0);
    memset(&outcome, 0, sizeof(outcome));
    error = gumi_omi_v3012_legacy_storage_reclaim(&outcome);
    feed_reclaimer_watchdog();
    reclaimer_status.flags |= outcome.flags;
    reclaimer_status.target_size_bytes = outcome.target_size_bytes;
    reclaimer_status.free_bytes_before = outcome.free_bytes_before;
    reclaimer_status.free_bytes_after = outcome.free_bytes_after;
    if ((outcome.flags &
         GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_DELETE_ATTEMPTED) != 0U) {
        publish_status(GUMI_OMI_V3012_LEGACY_RECLAIMER_RECLAIMING, 0);
    }

    /*
     * Storage is closed before any further image or reset is admitted. Even a
     * refused/failed result must remain recoverable through an exact next image.
     */
    admit_followup_mutation();
    publish_status(
        outcome.result == GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_RECLAIMED
            ? GUMI_OMI_V3012_LEGACY_RECLAIMER_RECLAIMED
            : outcome.result ==
                GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_ALREADY_ABSENT
                ? GUMI_OMI_V3012_LEGACY_RECLAIMER_ALREADY_ABSENT
                : outcome.result ==
                    GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_REFUSED
                    ? GUMI_OMI_V3012_LEGACY_RECLAIMER_REFUSED
                    : GUMI_OMI_V3012_LEGACY_RECLAIMER_FAILED,
        error
    );
    for (;;) {
        feed_reclaimer_watchdog();
        k_sleep(K_SECONDS(1));
    }
}
