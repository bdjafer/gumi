#ifndef GUMI_OMI_V3012_RECOVERY_H
#define GUMI_OMI_V3012_RECOVERY_H

#include "gumi/recovery.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Starts only the recovery transport: Zephyr Bluetooth, the statically registered SMP/DFU and
 * Device Information services, a read-only Gumi status service, and connectable advertising.
 * It does not initialize audio, storage, button, haptic, codec, or any functional service.
 */
int gumi_omi_v3012_recovery_transport_start(void);

/*
 * Atomically replaces the four-byte status snapshot served over GATT. Notification delivery is
 * best-effort; the read value remains authoritative when there is no connected subscriber.
 */
int gumi_omi_v3012_recovery_status_publish(
    const gumi_recovery_supervisor *supervisor
);

#ifdef __cplusplus
}
#endif

#endif
