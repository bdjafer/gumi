#include "gumi/omi_v3012_reset.h"

#include <stdbool.h>

#include <hal/nrf_reset.h>
#include <soc.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/reboot.h>
#include <zephyr/sys/util.h>

#define GUMI_NETWORK_CORE_RESET_SETTLE_US 1000U

static void set_network_core_force_off(bool force_off)
{
    nrf_reset_network_force_off(NRF_RESET, force_off);
    k_busy_wait(GUMI_NETWORK_CORE_RESET_SETTLE_US);
}

void gumi_omi_v3012_network_core_cold_start(void)
{
    set_network_core_force_off(true);
    set_network_core_force_off(false);
}

FUNC_NORETURN void gumi_omi_v3012_whole_device_reboot(void)
{
    set_network_core_force_off(true);
    sys_reboot(SYS_REBOOT_COLD);
    CODE_UNREACHABLE;
}
