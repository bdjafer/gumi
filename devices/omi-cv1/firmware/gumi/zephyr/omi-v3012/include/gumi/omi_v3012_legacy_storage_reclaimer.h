#ifndef GUMI_OMI_V3012_LEGACY_STORAGE_RECLAIMER_H
#define GUMI_OMI_V3012_LEGACY_STORAGE_RECLAIMER_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GUMI_OMI_V3012_LEGACY_RECLAIMER_STATUS_SCHEMA 1U
#define GUMI_OMI_V3012_LEGACY_RECLAIMER_STATUS_WIRE_SIZE 40U
#define GUMI_OMI_V3012_LEGACY_TARGET_SIZE_BYTES UINT64_C(505118720)
#define GUMI_OMI_V3012_LEGACY_MINIMUM_FREE_BYTES UINT64_C(4194304)

typedef enum {
    GUMI_OMI_V3012_LEGACY_RECLAIMER_COLD = 0,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_SAFE_TRANSPORT_READY = 1,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_INSPECTING = 2,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_RECLAIMING = 3,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_RECLAIMED = 4,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_ALREADY_ABSENT = 5,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_REFUSED = 6,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_FAILED = 7,
} gumi_omi_v3012_legacy_reclaimer_phase;

enum {
    GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_TRANSPORT_READY = 1U << 0,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_MICROPHONE_VERIFIED_OFF = 1U << 1,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_VOLUME_MOUNTED = 1U << 2,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_TARGET_OBSERVED = 1U << 3,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_TARGET_EXACT = 1U << 4,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_DELETE_ATTEMPTED = 1U << 5,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_TARGET_ABSENT = 1U << 6,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_MINIMUM_FREE_PROVEN = 1U << 7,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_FLAG_MUTATION_ADMITTED = 1U << 8,
};

typedef struct {
    gumi_omi_v3012_legacy_reclaimer_phase phase;
    uint16_t flags;
    int32_t last_error;
    uint32_t generation;
    uint64_t target_size_bytes;
    uint64_t free_bytes_before;
    uint64_t free_bytes_after;
} gumi_omi_v3012_legacy_reclaimer_status;

typedef enum {
    GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_RECLAIMED = 0,
    GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_ALREADY_ABSENT = 1,
    GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_REFUSED = 2,
    GUMI_OMI_V3012_LEGACY_RECLAIM_RESULT_FAILED = 3,
} gumi_omi_v3012_legacy_reclaim_result;

typedef struct {
    gumi_omi_v3012_legacy_reclaim_result result;
    uint16_t flags;
    int error;
    uint64_t target_size_bytes;
    uint64_t free_bytes_before;
    uint64_t free_bytes_after;
} gumi_omi_v3012_legacy_reclaim_outcome;

/*
 * Mounts the existing FAT volume with NO_FORMAT, inspects only the compiled-in
 * stock path /SD:/audio/a01.txt, and deletes it only if it is a regular file
 * with the exact qualified size. No caller-controlled path exists.
 */
int gumi_omi_v3012_legacy_storage_reclaim(
    gumi_omi_v3012_legacy_reclaim_outcome *outcome
);

int gumi_omi_v3012_legacy_reclaimer_status_publish(
    const gumi_omi_v3012_legacy_reclaimer_status *status
);

void gumi_omi_v3012_legacy_reclaimer_mgmt_guard_start(void);

void gumi_omi_v3012_legacy_reclaimer_mutation_admission_set(bool admitted);

#ifdef __cplusplus
}
#endif

#endif
