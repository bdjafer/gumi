#ifndef GUMI_OMI_V3012_RECORDING_ROOT_PROVISIONER_H
#define GUMI_OMI_V3012_RECORDING_ROOT_PROVISIONER_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GUMI_OMI_V3012_RECORDING_ROOT_STATUS_SCHEMA 1U
#define GUMI_OMI_V3012_RECORDING_ROOT_STATUS_WIRE_SIZE 12U

#define GUMI_OMI_V3012_RECORDING_KEY_BYTES 32U
#define GUMI_OMI_V3012_RECORDING_KEY_LABEL \
    "gumi.omi-cv1.recording/aes-256-gcm/v1"
#define GUMI_OMI_V3012_RECORDING_KEY_CONTEXT \
    "gumi-recording-journal-v1"

typedef enum {
    GUMI_OMI_V3012_RECORDING_ROOT_COLD = 0,
    GUMI_OMI_V3012_RECORDING_ROOT_SAFE_TRANSPORT_READY = 1,
    GUMI_OMI_V3012_RECORDING_ROOT_PROVISIONING = 2,
    GUMI_OMI_V3012_RECORDING_ROOT_PROVISIONED = 3,
    GUMI_OMI_V3012_RECORDING_ROOT_ALREADY_PRESENT = 4,
    GUMI_OMI_V3012_RECORDING_ROOT_FAILED = 5,
} gumi_omi_v3012_recording_root_phase;

enum {
    GUMI_OMI_V3012_RECORDING_ROOT_FLAG_TRANSPORT_READY = 1U << 0,
    GUMI_OMI_V3012_RECORDING_ROOT_FLAG_MICROPHONE_VERIFIED_OFF = 1U << 1,
    GUMI_OMI_V3012_RECORDING_ROOT_FLAG_WRITE_ATTEMPTED = 1U << 2,
    GUMI_OMI_V3012_RECORDING_ROOT_FLAG_MEXT_PRESENT = 1U << 3,
    GUMI_OMI_V3012_RECORDING_ROOT_FLAG_DERIVATION_VERIFIED = 1U << 4,
    GUMI_OMI_V3012_RECORDING_ROOT_FLAG_MUTATION_ADMITTED = 1U << 5,
};

typedef struct {
    uint32_t generation;
    int32_t last_error;
    gumi_omi_v3012_recording_root_phase phase;
    uint8_t flags;
} gumi_omi_v3012_recording_root_status;

int gumi_omi_v3012_recording_root_status_publish(
    const gumi_omi_v3012_recording_root_status *status
);

/*
 * Installs a second MCUmgr admission layer before Bluetooth starts. Image-0
 * writes and OS resets remain denied until the irreversible MEXT operation has
 * reached a verified terminal state.
 */
void gumi_omi_v3012_recording_root_mgmt_guard_start(void);

void gumi_omi_v3012_recording_root_mutation_admission_set(bool admitted);

#ifdef __cplusplus
}
#endif

#endif
