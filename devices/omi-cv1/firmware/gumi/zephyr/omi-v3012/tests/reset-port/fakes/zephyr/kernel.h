#ifndef GUMI_RESET_PORT_TEST_KERNEL_H
#define GUMI_RESET_PORT_TEST_KERNEL_H

#include <stdint.h>

void gumi_reset_port_test_busy_wait(uint32_t microseconds);

static inline void k_busy_wait(uint32_t microseconds)
{
    gumi_reset_port_test_busy_wait(microseconds);
}

#endif
