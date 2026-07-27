#ifndef GUMI_RESET_PORT_TEST_NRF_RESET_H
#define GUMI_RESET_PORT_TEST_NRF_RESET_H

#include <stdbool.h>

void gumi_reset_port_test_force_off(void *instance, bool force_off);

static inline void nrf_reset_network_force_off(void *instance, bool force_off)
{
    gumi_reset_port_test_force_off(instance, force_off);
}

#endif
