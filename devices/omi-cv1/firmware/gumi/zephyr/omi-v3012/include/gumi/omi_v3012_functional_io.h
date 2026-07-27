#ifndef GUMI_OMI_V3012_FUNCTIONAL_IO_H
#define GUMI_OMI_V3012_FUNCTIONAL_IO_H

#include <stdbool.h>
#include <stdint.h>

#include "gumi/capture.h"
#include "gumi/feedback.h"

#ifdef __cplusplus
extern "C" {
#endif

int gumi_omi_v3012_functional_io_init(void);
int gumi_omi_v3012_functional_button_pressed(bool *pressed);

/*
 * Privacy actions bypass decorative pattern timing but still use this port as
 * the sole RGB writer. Assertion is continuous full red; deassertion is all
 * channels off.
 */
int gumi_omi_v3012_functional_privacy_set(bool asserted);

/* Applies one already-arbitrated logical pattern without blocking. */
int gumi_omi_v3012_functional_feedback_apply(
    gumi_feedback_pattern pattern,
    uint64_t at_ms
);

/* Starts one bounded, named switched-ERM pattern without blocking the caller. */
int gumi_omi_v3012_functional_haptic_play(gumi_capture_haptic haptic);

#ifdef __cplusplus
}
#endif

#endif
