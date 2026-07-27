#ifndef GUMI_OMI_V3012_FUNCTIONAL_TRANSPORT_H
#define GUMI_OMI_V3012_FUNCTIONAL_TRANSPORT_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GUMI_OMI_V3012_FUNCTIONAL_STATUS_VERSION 1U
#define GUMI_OMI_V3012_FUNCTIONAL_STATUS_BYTES 40U
#define GUMI_OMI_V3012_FUNCTIONAL_CAPABILITY_BYTES 16U

enum {
    GUMI_OMI_V3012_FUNCTIONAL_FLAG_OPERATIONAL = 1U << 0,
    GUMI_OMI_V3012_FUNCTIONAL_FLAG_BASE_AUDIO_PERMITTED = 1U << 1,
    GUMI_OMI_V3012_FUNCTIONAL_FLAG_VOICE_AUDIO_PERMITTED = 1U << 2,
    GUMI_OMI_V3012_FUNCTIONAL_FLAG_PRIVACY_ASSERTED = 1U << 3,
    GUMI_OMI_V3012_FUNCTIONAL_FLAG_UPDATE_ADMITTED = 1U << 4,
    GUMI_OMI_V3012_FUNCTIONAL_FLAG_KEY_READY = 1U << 5,
    GUMI_OMI_V3012_FUNCTIONAL_FLAG_STORAGE_READY = 1U << 6,
    GUMI_OMI_V3012_FUNCTIONAL_FLAG_FAULTED = 1U << 7,
};

typedef struct {
    uint64_t active_recording_id;
    uint64_t free_bytes;
    uint32_t generation;
    int32_t last_error;
    uint8_t capture_phase;
    uint8_t mic_truth;
    uint8_t storage_state;
    uint8_t key_truth;
    uint8_t storage_truth;
    uint8_t codec_truth;
    uint8_t flags;
} gumi_omi_v3012_functional_status;

int gumi_omi_v3012_functional_status_encode(
    const gumi_omi_v3012_functional_status *status,
    uint8_t output[GUMI_OMI_V3012_FUNCTIONAL_STATUS_BYTES]
);

int gumi_omi_v3012_functional_transport_start(void);
int gumi_omi_v3012_functional_status_publish(
    const gumi_omi_v3012_functional_status *status
);

/*
 * Functional firmware admits application-image-0 bytes only while both values
 * are true. The intended policy is a boot-time physical confirmation plus
 * device-local proof that capture is idle and the microphone is verified off.
 */
void gumi_omi_v3012_functional_update_admission_set(
    bool physically_confirmed,
    bool capture_idle
);

#ifdef __cplusplus
}
#endif

#endif
