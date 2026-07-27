#include "gumi/legacy_storage_reclaimer.h"

#include <stddef.h>

gumi_legacy_storage_reclaimer_decision gumi_legacy_storage_reclaimer_decide(
    const gumi_legacy_storage_reclaimer_policy *policy,
    const gumi_legacy_storage_reclaimer_observation *observation
)
{
    gumi_legacy_storage_reclaimer_decision decision = {
        .action = GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_REFUSE,
        .reason = GUMI_LEGACY_STORAGE_RECLAIMER_REASON_NONE,
    };

    if (policy == NULL || observation == NULL ||
        policy->expected_target_size_bytes == UINT64_C(0) ||
        policy->minimum_free_bytes == UINT64_C(0)) {
        return decision;
    }
    if (!observation->target_present) {
        if (observation->free_bytes >= policy->minimum_free_bytes) {
            decision.action = GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_NONE;
            decision.reason =
                GUMI_LEGACY_STORAGE_RECLAIMER_REASON_ALREADY_ABSENT;
        } else {
            decision.reason =
                GUMI_LEGACY_STORAGE_RECLAIMER_REASON_INSUFFICIENT_SPACE_WITHOUT_TARGET;
        }
        return decision;
    }
    if (!observation->target_regular_file) {
        decision.reason =
            GUMI_LEGACY_STORAGE_RECLAIMER_REASON_TARGET_NOT_REGULAR_FILE;
        return decision;
    }
    if (observation->target_size_bytes !=
        policy->expected_target_size_bytes) {
        decision.reason =
            GUMI_LEGACY_STORAGE_RECLAIMER_REASON_TARGET_SIZE_MISMATCH;
        return decision;
    }
    decision.action =
        GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_DELETE_EXACT_TARGET;
    return decision;
}
