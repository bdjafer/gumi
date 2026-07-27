#include "gumi/omi_v3012_recording_storage.h"
#include "gumi/omi_v3012_recording_key.h"

int main(void)
{
    return gumi_omi_v3012_recording_storage_get_truth() ==
                   GUMI_OMI_V3012_RECORDING_STORAGE_UNINITIALIZED &&
               gumi_omi_v3012_recording_storage_last_error() == 0 &&
               gumi_omi_v3012_recording_key_get_truth() ==
                   GUMI_OMI_V3012_RECORDING_KEY_UNINITIALIZED
        ? 0
        : 1;
}
