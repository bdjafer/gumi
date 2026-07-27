#include <errno.h>
#include <stdio.h>

#include "recording_storage_pm_policy.h"

static int failures;

static void expect_result(const char *name, int input, int expected)
{
    int actual =
        gumi_omi_v3012_recording_storage_normalize_resume_result(input);

    if (actual != expected) {
        fprintf(
            stderr,
            "FAIL %s: expected %d, got %d\n",
            name,
            expected,
            actual
        );
        failures += 1;
    }
}

int main(void)
{
    expect_result("successful resume", 0, 0);
    expect_result("already active host", -EALREADY, 0);
    expect_result("host without device PM", -ENOSYS, 0);
    expect_result("unsupported state transition", -ENOTSUP, -ENOTSUP);
    expect_result("real device failure", -EIO, -EIO);

    if (failures != 0) {
        return 1;
    }
    puts("recording storage PM policy: 5 checks passed");
    return 0;
}
