#include "recording_storage_pm_policy.h"

#include <errno.h>

int gumi_omi_v3012_recording_storage_normalize_resume_result(int result)
{
    if (result == 0 || result == -EALREADY || result == -ENOSYS) {
        return 0;
    }
    return result;
}
