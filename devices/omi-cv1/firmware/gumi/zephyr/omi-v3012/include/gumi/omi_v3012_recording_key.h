#ifndef GUMI_OMI_V3012_RECORDING_KEY_H
#define GUMI_OMI_V3012_RECORDING_KEY_H

#include <stdint.h>

#include <psa/crypto.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GUMI_OMI_V3012_RECORDING_KEY_VERSION UINT32_C(1)

typedef enum {
    GUMI_OMI_V3012_RECORDING_KEY_UNINITIALIZED = 0,
    GUMI_OMI_V3012_RECORDING_KEY_ROOT_MISSING,
    GUMI_OMI_V3012_RECORDING_KEY_READY,
    GUMI_OMI_V3012_RECORDING_KEY_FAULTED,
} gumi_omi_v3012_recording_key_truth;

/*
 * Derives the product recording key from an already-provisioned device HUK and
 * imports it as a volatile PSA AES-256-GCM key. This API has no key-writing or
 * persistent-storage path. An absent HUK is a fail-closed readiness result.
 */
int gumi_omi_v3012_recording_key_open(void);

/*
 * Returns the boot-local PSA handle. The stable on-media identifier is
 * GUMI_OMI_V3012_RECORDING_KEY_VERSION, never this transient handle.
 */
int gumi_omi_v3012_recording_key_borrow(psa_key_id_t *key_id);

int gumi_omi_v3012_recording_key_close(void);

gumi_omi_v3012_recording_key_truth
gumi_omi_v3012_recording_key_get_truth(void);

#ifdef __cplusplus
}
#endif

#endif
