#include "gumi/omi_v3012_mic.h"
#include "gumi/omi_v3012_recording_root_provisioner.h"
#include "gumi/omi_v3012_recovery.h"
#include "gumi/recovery.h"

#include <errno.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <hw_unique_key.h>
#include <mbedtls/platform_util.h>
#include <psa/crypto.h>
#include <zephyr/device.h>
#include <zephyr/drivers/watchdog.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/reboot.h>
#include <zephyr/sys/util.h>
#include <zephyr/task_wdt/task_wdt.h>

#define GUMI_PROVISIONER_WATCHDOG_TIMEOUT_MS 15000U

#if DT_HAS_COMPAT_STATUS_OKAY(nordic_nrf_wdt)
#define GUMI_PROVISIONER_WATCHDOG_NODE \
    DT_COMPAT_GET_ANY_STATUS_OKAY(nordic_nrf_wdt)
#else
#define GUMI_PROVISIONER_WATCHDOG_NODE DT_INVALID_NODE
#endif

static const uint8_t recording_key_label[] =
    GUMI_OMI_V3012_RECORDING_KEY_LABEL;
static const uint8_t recording_key_context[] =
    GUMI_OMI_V3012_RECORDING_KEY_CONTEXT;

static gumi_omi_v3012_recording_root_status root_status;
static int provisioner_watchdog_channel = -1;

static void discard_microphone_frame(
    const int16_t *samples,
    size_t sample_count,
    void *context
)
{
    ARG_UNUSED(samples);
    ARG_UNUSED(sample_count);
    ARG_UNUSED(context);
}

static void observe_unexpected_microphone_fault(int error, void *context)
{
    ARG_UNUSED(error);
    ARG_UNUSED(context);
}

static uint64_t monotonic_milliseconds(void)
{
    int64_t now = k_uptime_get();

    return now > 0 ? (uint64_t)now : UINT64_C(0);
}

static int start_provisioner_watchdog(void)
{
    const struct device *watchdog =
        DEVICE_DT_GET_OR_NULL(GUMI_PROVISIONER_WATCHDOG_NODE);
    int error;

    if (watchdog == NULL || !device_is_ready(watchdog)) {
        return -ENODEV;
    }
    error = task_wdt_init(watchdog);
    if (error < 0) {
        return error;
    }
    provisioner_watchdog_channel = task_wdt_add(
        GUMI_PROVISIONER_WATCHDOG_TIMEOUT_MS,
        NULL,
        NULL
    );
    return provisioner_watchdog_channel < 0
        ? provisioner_watchdog_channel
        : 0;
}

static void feed_provisioner_watchdog(void)
{
    if (provisioner_watchdog_channel >= 0 &&
        task_wdt_feed(provisioner_watchdog_channel) < 0) {
        sys_reboot(SYS_REBOOT_COLD);
    }
}

static void publish_root_status(
    gumi_omi_v3012_recording_root_phase phase,
    int error
)
{
    root_status.phase = phase;
    root_status.last_error = error;
    root_status.generation += 1U;
    (void)gumi_omi_v3012_recording_root_status_publish(&root_status);
}

static int complete_transition(
    gumi_recovery_supervisor *supervisor,
    const gumi_recovery_result *previous,
    gumi_recovery_completion completion,
    gumi_recovery_result *next
)
{
    gumi_recovery_status status = gumi_recovery_complete(
        supervisor,
        monotonic_milliseconds(),
        previous->action.transition_id,
        completion,
        next
    );

    if (status != GUMI_RECOVERY_STATUS_OK) {
        return -EINVAL;
    }
    return gumi_omi_v3012_recovery_status_publish(supervisor);
}

static bool bytes_are_nonzero(const uint8_t *bytes, size_t size)
{
    uint8_t aggregate = 0U;
    size_t index;

    for (index = 0U; index < size; index += 1U) {
        aggregate = (uint8_t)(aggregate | bytes[index]);
    }
    return aggregate != 0U;
}

static int generate_nonzero_root(uint8_t root[GUMI_OMI_V3012_RECORDING_KEY_BYTES])
{
    psa_status_t status;
    unsigned int attempt;

    for (attempt = 0U; attempt < 2U; attempt += 1U) {
        status = psa_generate_random(
            root,
            GUMI_OMI_V3012_RECORDING_KEY_BYTES
        );
        if (status != PSA_SUCCESS) {
            return -EIO;
        }
        if (bytes_are_nonzero(
                root,
                GUMI_OMI_V3012_RECORDING_KEY_BYTES
            )) {
            return 0;
        }
    }
    return -EIO;
}

static int verify_recording_derivation(void)
{
    uint8_t derived[GUMI_OMI_V3012_RECORDING_KEY_BYTES];
    int error = hw_unique_key_derive_key(
        HUK_KEYSLOT_MEXT,
        recording_key_context,
        sizeof(recording_key_context) - 1U,
        recording_key_label,
        sizeof(recording_key_label) - 1U,
        derived,
        sizeof(derived)
    );

    mbedtls_platform_zeroize(derived, sizeof(derived));
    return error == HW_UNIQUE_KEY_SUCCESS ? 0 : -EKEYREJECTED;
}

static int provision_recording_root(bool *was_written)
{
    uint8_t root[GUMI_OMI_V3012_RECORDING_KEY_BYTES];
    psa_status_t crypto_status;
    int error;

    *was_written = false;
    crypto_status = psa_crypto_init();
    if (crypto_status != PSA_SUCCESS) {
        return -EIO;
    }
    if (!hw_unique_key_is_written(HUK_KEYSLOT_MEXT)) {
        memset(root, 0, sizeof(root));
        root_status.flags |=
            GUMI_OMI_V3012_RECORDING_ROOT_FLAG_WRITE_ATTEMPTED;
        publish_root_status(GUMI_OMI_V3012_RECORDING_ROOT_PROVISIONING, 0);
        error = generate_nonzero_root(root);
        if (error == 0) {
            /*
             * MEXT spans two irreversible KMU slots. Feed immediately before
             * entering the narrowest unavoidable partial-write risk window.
             */
            feed_provisioner_watchdog();
            error = hw_unique_key_write(HUK_KEYSLOT_MEXT, root);
        }
        mbedtls_platform_zeroize(root, sizeof(root));
        if (error != HW_UNIQUE_KEY_SUCCESS) {
            return error < 0 ? error : -EIO;
        }
        *was_written = true;
    }
    if (!hw_unique_key_is_written(HUK_KEYSLOT_MEXT)) {
        return -EIO;
    }
    root_status.flags |=
        GUMI_OMI_V3012_RECORDING_ROOT_FLAG_MEXT_PRESENT;
    error = verify_recording_derivation();
    if (error < 0) {
        return error;
    }
    root_status.flags |=
        GUMI_OMI_V3012_RECORDING_ROOT_FLAG_DERIVATION_VERIFIED;
    return 0;
}

int main(void)
{
    const gumi_recovery_boot_evidence evidence = {
        .explicit_safe_mode = true,
        .persisted_safe_mode = false,
        .watchdog_or_lockup_reset = false,
    };
    gumi_recovery_supervisor supervisor;
    gumi_recovery_result result;
    gumi_recovery_result next;
    gumi_recovery_status status;
    bool was_written = false;
    int error;

    memset(&root_status, 0, sizeof(root_status));
    gumi_omi_v3012_recording_root_mgmt_guard_start();
    publish_root_status(GUMI_OMI_V3012_RECORDING_ROOT_COLD, 0);
    error = start_provisioner_watchdog();
    if (error < 0) {
        publish_root_status(GUMI_OMI_V3012_RECORDING_ROOT_FAILED, error);
        return error;
    }
    status = gumi_recovery_supervisor_init(&supervisor, &evidence);
    if (status != GUMI_RECOVERY_STATUS_OK) {
        publish_root_status(GUMI_OMI_V3012_RECORDING_ROOT_FAILED, -EPROTO);
        return -EINVAL;
    }
    status = gumi_recovery_begin(&supervisor, monotonic_milliseconds(), &result);
    if (status != GUMI_RECOVERY_STATUS_OK || !result.has_action ||
        result.action.type != GUMI_RECOVERY_ACTION_START_TRANSPORT) {
        publish_root_status(GUMI_OMI_V3012_RECORDING_ROOT_FAILED, -EPROTO);
        return -EINVAL;
    }
    error = gumi_omi_v3012_recovery_status_publish(&supervisor);
    if (error < 0) {
        publish_root_status(GUMI_OMI_V3012_RECORDING_ROOT_FAILED, error);
        return error;
    }
    error = gumi_omi_v3012_recovery_transport_start();
    feed_provisioner_watchdog();
    if (error < 0) {
        (void)complete_transition(
            &supervisor,
            &result,
            GUMI_RECOVERY_COMPLETION_TRANSPORT_FAILED,
            &next
        );
        publish_root_status(GUMI_OMI_V3012_RECORDING_ROOT_FAILED, error);
        return error;
    }
    root_status.flags |=
        GUMI_OMI_V3012_RECORDING_ROOT_FLAG_TRANSPORT_READY;
    publish_root_status(
        GUMI_OMI_V3012_RECORDING_ROOT_SAFE_TRANSPORT_READY,
        0
    );
    error = complete_transition(
        &supervisor,
        &result,
        GUMI_RECOVERY_COMPLETION_TRANSPORT_READY,
        &next
    );
    if (error < 0 || !next.has_action ||
        next.action.type != GUMI_RECOVERY_ACTION_VERIFY_MICROPHONE_OFF) {
        publish_root_status(
            GUMI_OMI_V3012_RECORDING_ROOT_FAILED,
            error < 0 ? error : -EPROTO
        );
        return error < 0 ? error : -EINVAL;
    }
    result = next;
    error = gumi_omi_v3012_mic_init(
        discard_microphone_frame,
        observe_unexpected_microphone_fault,
        NULL,
        0U
    );
    feed_provisioner_watchdog();
    if (error < 0 ||
        gumi_omi_v3012_mic_get_truth() != GUMI_OMI_V3012_MIC_VERIFIED_OFF) {
        (void)complete_transition(
            &supervisor,
            &result,
            GUMI_RECOVERY_COMPLETION_MICROPHONE_OFF_FAILED,
            &next
        );
        publish_root_status(
            GUMI_OMI_V3012_RECORDING_ROOT_FAILED,
            error < 0 ? error : -EIO
        );
        return error < 0 ? error : -EIO;
    }
    root_status.flags |=
        GUMI_OMI_V3012_RECORDING_ROOT_FLAG_MICROPHONE_VERIFIED_OFF;
    error = complete_transition(
        &supervisor,
        &result,
        GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF,
        &next
    );
    if (error < 0) {
        publish_root_status(GUMI_OMI_V3012_RECORDING_ROOT_FAILED, error);
        return error;
    }

    error = provision_recording_root(&was_written);
    feed_provisioner_watchdog();
    if (error == 0) {
        gumi_omi_v3012_recording_root_mutation_admission_set(true);
        root_status.flags |=
            GUMI_OMI_V3012_RECORDING_ROOT_FLAG_MUTATION_ADMITTED;
    }
    publish_root_status(
        error < 0
            ? GUMI_OMI_V3012_RECORDING_ROOT_FAILED
            : was_written
                ? GUMI_OMI_V3012_RECORDING_ROOT_PROVISIONED
                : GUMI_OMI_V3012_RECORDING_ROOT_ALREADY_PRESENT,
        error
    );
    for (;;) {
        feed_provisioner_watchdog();
        k_sleep(K_SECONDS(1));
    }
}
