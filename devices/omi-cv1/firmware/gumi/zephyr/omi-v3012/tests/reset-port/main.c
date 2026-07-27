#include "gumi/omi_v3012_reset.h"

#include <setjmp.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static jmp_buf reboot_boundary;
static char events[16];
static size_t event_count;
static int observed_reboot_type = -1;

static void record_event(char event)
{
    if (event_count >= sizeof(events)) {
        abort();
    }
    events[event_count++] = event;
}

void gumi_reset_port_test_force_off(void *instance, bool force_off)
{
    if (instance != (void *)0x1) {
        abort();
    }
    record_event(force_off ? 'F' : 'R');
}

void gumi_reset_port_test_busy_wait(uint32_t microseconds)
{
    if (microseconds != 1000U) {
        abort();
    }
    record_event('W');
}

void sys_reboot(int type)
{
    observed_reboot_type = type;
    record_event('B');
    longjmp(reboot_boundary, 1);
}

static void expect_events(const char *expected)
{
    size_t length = strlen(expected);

    if (event_count != length || memcmp(events, expected, length) != 0) {
        fprintf(stderr, "unexpected reset events\n");
        exit(1);
    }
}

int main(void)
{
    gumi_omi_v3012_network_core_cold_start();
    expect_events("FWRW");

    event_count = 0U;
    if (setjmp(reboot_boundary) == 0) {
        gumi_omi_v3012_whole_device_reboot();
    }
    expect_events("FWB");
    if (observed_reboot_type != 0) {
        fprintf(stderr, "whole-device reboot did not request a cold reset\n");
        return 1;
    }

    puts("reset-port-order=pass");
    return 0;
}
