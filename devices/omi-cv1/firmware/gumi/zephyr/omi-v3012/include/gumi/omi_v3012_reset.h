#ifndef GUMI_OMI_V3012_RESET_H
#define GUMI_OMI_V3012_RESET_H

#include <zephyr/toolchain.h>

/*
 * nRF5340 application-core resets do not themselves guarantee a fresh BLE
 * network core. Cold-start it before opening HCI so a prior application reset
 * cannot leave IPC bound to stale controller state.
 */
void gumi_omi_v3012_network_core_cold_start(void);

/*
 * Leave the network core forced off across the application-core reset. The
 * next boot's cold-start path releases it before Bluetooth is initialized.
 */
FUNC_NORETURN void gumi_omi_v3012_whole_device_reboot(void);

#endif
