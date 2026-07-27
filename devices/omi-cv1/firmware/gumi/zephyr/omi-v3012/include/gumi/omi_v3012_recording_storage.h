#ifndef GUMI_OMI_V3012_RECORDING_STORAGE_H
#define GUMI_OMI_V3012_RECORDING_STORAGE_H

#include <stddef.h>
#include <stdint.h>

#include <psa/crypto.h>

#include "gumi/recording_store.h"

#ifdef __cplusplus
extern "C" {
#endif

#define GUMI_OMI_V3012_RECORDING_STORAGE_QUEUE_CAPACITY 32U

typedef enum {
    GUMI_OMI_V3012_RECORDING_STORAGE_UNINITIALIZED = 0,
    GUMI_OMI_V3012_RECORDING_STORAGE_READY,
    GUMI_OMI_V3012_RECORDING_STORAGE_ACTIVE,
    GUMI_OMI_V3012_RECORDING_STORAGE_FINALIZING,
    GUMI_OMI_V3012_RECORDING_STORAGE_COMMITTED,
    GUMI_OMI_V3012_RECORDING_STORAGE_INTERRUPTED,
    GUMI_OMI_V3012_RECORDING_STORAGE_FAULTED,
    GUMI_OMI_V3012_RECORDING_STORAGE_UNKNOWN,
} gumi_omi_v3012_recording_storage_truth;

typedef void (*gumi_omi_v3012_recording_storage_fault_handler)(
    uint64_t session_id,
    int error,
    void *context
);

/*
 * Starts the sole filesystem-writer thread, powers and mounts the existing SD
 * slot without formatting, validates the fixed GUMI directory, and borrows an
 * already-provisioned AES-256-GCM PSA key. It never creates or destroys a key.
 */
int gumi_omi_v3012_recording_storage_init(
    psa_key_id_t key_id,
    uint32_t expected_key_version,
    uint64_t minimum_free_bytes,
    gumi_omi_v3012_recording_storage_fault_handler fault_handler,
    void *context
);

/*
 * Synchronous thread-context lifecycle calls. Every filesystem and crypto
 * operation executes on the sole writer thread. The caller must prepare before
 * opening the codec and must stop codec callbacks before finalize/interrupt.
 */
int gumi_omi_v3012_recording_storage_prepare(
    const gumi_recording_store_config *config
);

/*
 * Non-blocking codec-callback API. The packet bytes are copied into a bounded
 * fixed queue. Queue exhaustion faults the active recording; no packet is
 * silently dropped.
 */
int gumi_omi_v3012_recording_storage_submit(
    uint64_t session_id,
    uint64_t packet_sequence,
    uint32_t pcm_sample_count,
    const uint8_t *packet,
    size_t packet_size
);

int gumi_omi_v3012_recording_storage_finalize(uint64_t session_id);
int gumi_omi_v3012_recording_storage_interrupt(uint64_t session_id);

gumi_omi_v3012_recording_storage_truth
gumi_omi_v3012_recording_storage_get_truth(void);

int gumi_omi_v3012_recording_storage_last_error(void);
uint64_t gumi_omi_v3012_recording_storage_free_bytes(void);

#ifdef __cplusplus
}
#endif

#endif
