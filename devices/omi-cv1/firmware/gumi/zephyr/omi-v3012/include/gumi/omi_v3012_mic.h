#ifndef GUMI_OMI_V3012_MIC_H
#define GUMI_OMI_V3012_MIC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    GUMI_OMI_V3012_MIC_UNINITIALIZED = 0,
    GUMI_OMI_V3012_MIC_VERIFIED_OFF,
    GUMI_OMI_V3012_MIC_STARTING,
    GUMI_OMI_V3012_MIC_RUNNING,
    GUMI_OMI_V3012_MIC_STOPPING,
    GUMI_OMI_V3012_MIC_UNKNOWN,
} gumi_omi_v3012_mic_truth;

typedef void (*gumi_omi_v3012_mic_frame_handler)(
    const int16_t *samples,
    size_t sample_count,
    void *context
);

/*
 * Called from the microphone thread. It must be non-blocking and must enqueue lifecycle work rather
 * than call acquire/release directly.
 */
typedef void (*gumi_omi_v3012_mic_fault_handler)(int error, void *context);

/* Configure PDM without starting it. Successful initialization proves microphone-off. */
int gumi_omi_v3012_mic_init(
    gumi_omi_v3012_mic_frame_handler frame_handler,
    gumi_omi_v3012_mic_fault_handler fault_handler,
    void *context,
    uint8_t gain_level
);

/* Thread-context APIs. Acquire is valid only from VERIFIED_OFF; release is idempotent when off. */
int gumi_omi_v3012_mic_acquire(void);
int gumi_omi_v3012_mic_release(void);
int gumi_omi_v3012_mic_set_gain(uint8_t gain_level);

gumi_omi_v3012_mic_truth gumi_omi_v3012_mic_get_truth(void);

#ifdef __cplusplus
}
#endif

#endif
