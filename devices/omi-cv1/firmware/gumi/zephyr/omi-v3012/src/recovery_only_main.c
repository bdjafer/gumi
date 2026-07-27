#include "gumi/omi_v3012_mic.h"
#include "gumi/omi_v3012_recovery.h"
#include "gumi/recovery.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>

#include <zephyr/kernel.h>
#include <zephyr/sys/util.h>

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
    gumi_recovery_status status;
    int error;

    status = gumi_recovery_supervisor_init(&supervisor, &evidence);
    if (status != GUMI_RECOVERY_STATUS_OK) {
        return -EINVAL;
    }
    status = gumi_recovery_begin(&supervisor, monotonic_milliseconds(), &result);
    if (status != GUMI_RECOVERY_STATUS_OK || !result.has_action ||
        result.action.type != GUMI_RECOVERY_ACTION_START_TRANSPORT) {
        return -EINVAL;
    }
    error = gumi_omi_v3012_recovery_status_publish(&supervisor);
    if (error < 0) {
        return error;
    }

    error = gumi_omi_v3012_recovery_transport_start();
    if (error < 0) {
        (void)complete_transition(
            &supervisor,
            &result,
            GUMI_RECOVERY_COMPLETION_TRANSPORT_FAILED,
            &next
        );
        return error;
    }
    error = complete_transition(
        &supervisor,
        &result,
        GUMI_RECOVERY_COMPLETION_TRANSPORT_READY,
        &next
    );
    if (error < 0 || !next.has_action ||
        next.action.type != GUMI_RECOVERY_ACTION_VERIFY_MICROPHONE_OFF) {
        return error < 0 ? error : -EINVAL;
    }
    result = next;

    error = gumi_omi_v3012_mic_init(
        discard_microphone_frame,
        observe_unexpected_microphone_fault,
        NULL,
        0U
    );
    if (error < 0 ||
        gumi_omi_v3012_mic_get_truth() != GUMI_OMI_V3012_MIC_VERIFIED_OFF) {
        (void)complete_transition(
            &supervisor,
            &result,
            GUMI_RECOVERY_COMPLETION_MICROPHONE_OFF_FAILED,
            &next
        );
    } else {
        error = complete_transition(
            &supervisor,
            &result,
            GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF,
            &next
        );
        if (error < 0) {
            return error;
        }
    }

    for (;;) {
        k_sleep(K_FOREVER);
    }
}
