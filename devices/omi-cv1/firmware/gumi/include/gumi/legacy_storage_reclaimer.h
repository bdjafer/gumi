#ifndef GUMI_LEGACY_STORAGE_RECLAIMER_H
#define GUMI_LEGACY_STORAGE_RECLAIMER_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_NONE = 0,
    GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_DELETE_EXACT_TARGET = 1,
    GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_REFUSE = 2,
} gumi_legacy_storage_reclaimer_action;

typedef enum {
    GUMI_LEGACY_STORAGE_RECLAIMER_REASON_NONE = 0,
    GUMI_LEGACY_STORAGE_RECLAIMER_REASON_ALREADY_ABSENT = 1,
    GUMI_LEGACY_STORAGE_RECLAIMER_REASON_TARGET_NOT_REGULAR_FILE = 2,
    GUMI_LEGACY_STORAGE_RECLAIMER_REASON_TARGET_SIZE_MISMATCH = 3,
    GUMI_LEGACY_STORAGE_RECLAIMER_REASON_INSUFFICIENT_SPACE_WITHOUT_TARGET = 4,
} gumi_legacy_storage_reclaimer_reason;

typedef struct {
    bool target_present;
    bool target_regular_file;
    uint64_t target_size_bytes;
    uint64_t free_bytes;
} gumi_legacy_storage_reclaimer_observation;

typedef struct {
    uint64_t expected_target_size_bytes;
    uint64_t minimum_free_bytes;
} gumi_legacy_storage_reclaimer_policy;

typedef struct {
    gumi_legacy_storage_reclaimer_action action;
    gumi_legacy_storage_reclaimer_reason reason;
} gumi_legacy_storage_reclaimer_decision;

/*
 * Pure policy boundary for the destructive maintenance image.
 *
 * A filesystem port may delete only when this function returns the single
 * DELETE_EXACT_TARGET action. Paths are intentionally owned by the port, not
 * supplied by a caller.
 */
gumi_legacy_storage_reclaimer_decision gumi_legacy_storage_reclaimer_decide(
    const gumi_legacy_storage_reclaimer_policy *policy,
    const gumi_legacy_storage_reclaimer_observation *observation
);

#ifdef __cplusplus
}
#endif

#endif
