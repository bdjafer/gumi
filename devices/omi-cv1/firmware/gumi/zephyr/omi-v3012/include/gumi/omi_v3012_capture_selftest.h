#ifndef GUMI_OMI_V3012_CAPTURE_SELFTEST_H
#define GUMI_OMI_V3012_CAPTURE_SELFTEST_H

#include "gumi/capture_selftest.h"

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Starts BLE, constrained image-0 MCU Manager, identity, status, and one-byte arm control. */
int gumi_omi_v3012_capture_selftest_transport_start(void);

/* Main-thread handoff for a coalesced arm write. The GATT callback never mutates the supervisor. */
bool gumi_omi_v3012_capture_selftest_take_arm_request(void);

/* Copies and optionally notifies one complete 32-byte, media-free status snapshot. */
int gumi_omi_v3012_capture_selftest_status_publish(
    const gumi_capture_selftest *supervisor
);

/* Exclusive diagnostic RGB/button port. No stock feedback or button source is linked. */
int gumi_omi_v3012_capture_selftest_io_init(void);
int gumi_omi_v3012_capture_selftest_privacy_set(bool asserted);
int gumi_omi_v3012_capture_selftest_button_pressed(bool *pressed);

#ifdef __cplusplus
}
#endif

#endif
