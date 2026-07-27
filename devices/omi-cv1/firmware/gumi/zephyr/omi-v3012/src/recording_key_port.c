#include "gumi/omi_v3012_recording_key.h"
#include "gumi/omi_v3012_recording_root_provisioner.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>

#include <hw_unique_key.h>
#include <mbedtls/platform_util.h>
#include <zephyr/kernel.h>

static const uint8_t recording_key_label[] =
    GUMI_OMI_V3012_RECORDING_KEY_LABEL;
static const uint8_t recording_key_context[] =
    GUMI_OMI_V3012_RECORDING_KEY_CONTEXT;

K_MUTEX_DEFINE(gumi_recording_key_lock);

static psa_key_id_t gumi_recording_key_id = PSA_KEY_ID_NULL;
static gumi_omi_v3012_recording_key_truth gumi_recording_key_truth =
    GUMI_OMI_V3012_RECORDING_KEY_UNINITIALIZED;

static int psa_error(psa_status_t status)
{
    switch (status) {
        case PSA_SUCCESS:
            return 0;
        case PSA_ERROR_NOT_PERMITTED:
            return -EACCES;
        case PSA_ERROR_NOT_SUPPORTED:
            return -ENOTSUP;
        case PSA_ERROR_INSUFFICIENT_MEMORY:
            return -ENOMEM;
        case PSA_ERROR_INSUFFICIENT_ENTROPY:
            return -EAGAIN;
        case PSA_ERROR_ALREADY_EXISTS:
            return -EEXIST;
        case PSA_ERROR_INVALID_ARGUMENT:
            return -EINVAL;
        case PSA_ERROR_BAD_STATE:
            return -EPIPE;
        default:
            return -EIO;
    }
}

int gumi_omi_v3012_recording_key_open(void)
{
    psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
    uint8_t key_bytes[GUMI_OMI_V3012_RECORDING_KEY_BYTES];
    psa_key_id_t imported_key = PSA_KEY_ID_NULL;
    psa_status_t status;
    int error;

    k_mutex_lock(&gumi_recording_key_lock, K_FOREVER);
    if (gumi_recording_key_truth !=
        GUMI_OMI_V3012_RECORDING_KEY_UNINITIALIZED) {
        k_mutex_unlock(&gumi_recording_key_lock);
        return -EALREADY;
    }

    if (!hw_unique_key_is_written(HUK_KEYSLOT_MEXT)) {
        gumi_recording_key_truth =
            GUMI_OMI_V3012_RECORDING_KEY_ROOT_MISSING;
        k_mutex_unlock(&gumi_recording_key_lock);
        return -ENOENT;
    }
    error = hw_unique_key_derive_key(
        HUK_KEYSLOT_MEXT,
        recording_key_context,
        sizeof(recording_key_context) - 1U,
        recording_key_label,
        sizeof(recording_key_label) - 1U,
        key_bytes,
        sizeof(key_bytes)
    );
    if (error != HW_UNIQUE_KEY_SUCCESS) {
        mbedtls_platform_zeroize(key_bytes, sizeof(key_bytes));
        gumi_recording_key_truth = GUMI_OMI_V3012_RECORDING_KEY_FAULTED;
        k_mutex_unlock(&gumi_recording_key_lock);
        return error == -HW_UNIQUE_KEY_ERR_MISSING ? -ENOENT : -EKEYREJECTED;
    }

    status = psa_crypto_init();
    if (status == PSA_SUCCESS) {
        psa_set_key_lifetime(&attributes, PSA_KEY_LIFETIME_VOLATILE);
        psa_set_key_usage_flags(
            &attributes,
            PSA_KEY_USAGE_ENCRYPT | PSA_KEY_USAGE_DECRYPT
        );
        psa_set_key_algorithm(&attributes, PSA_ALG_GCM);
        psa_set_key_type(&attributes, PSA_KEY_TYPE_AES);
        psa_set_key_bits(
            &attributes,
            GUMI_OMI_V3012_RECORDING_KEY_BYTES * 8U
        );
        status = psa_import_key(
            &attributes,
            key_bytes,
            sizeof(key_bytes),
            &imported_key
        );
    }
    psa_reset_key_attributes(&attributes);
    mbedtls_platform_zeroize(key_bytes, sizeof(key_bytes));
    if (status != PSA_SUCCESS || imported_key == PSA_KEY_ID_NULL) {
        gumi_recording_key_truth = GUMI_OMI_V3012_RECORDING_KEY_FAULTED;
        k_mutex_unlock(&gumi_recording_key_lock);
        return status == PSA_SUCCESS ? -EIO : psa_error(status);
    }

    gumi_recording_key_id = imported_key;
    gumi_recording_key_truth = GUMI_OMI_V3012_RECORDING_KEY_READY;
    k_mutex_unlock(&gumi_recording_key_lock);
    return 0;
}

int gumi_omi_v3012_recording_key_borrow(psa_key_id_t *key_id)
{
    if (key_id == NULL) {
        return -EINVAL;
    }
    k_mutex_lock(&gumi_recording_key_lock, K_FOREVER);
    if (gumi_recording_key_truth != GUMI_OMI_V3012_RECORDING_KEY_READY ||
        gumi_recording_key_id == PSA_KEY_ID_NULL) {
        k_mutex_unlock(&gumi_recording_key_lock);
        return -ENOENT;
    }
    *key_id = gumi_recording_key_id;
    k_mutex_unlock(&gumi_recording_key_lock);
    return 0;
}

int gumi_omi_v3012_recording_key_close(void)
{
    psa_status_t status;

    k_mutex_lock(&gumi_recording_key_lock, K_FOREVER);
    if (gumi_recording_key_truth != GUMI_OMI_V3012_RECORDING_KEY_READY ||
        gumi_recording_key_id == PSA_KEY_ID_NULL) {
        k_mutex_unlock(&gumi_recording_key_lock);
        return -EALREADY;
    }
    status = psa_destroy_key(gumi_recording_key_id);
    gumi_recording_key_id = PSA_KEY_ID_NULL;
    gumi_recording_key_truth = status == PSA_SUCCESS
        ? GUMI_OMI_V3012_RECORDING_KEY_UNINITIALIZED
        : GUMI_OMI_V3012_RECORDING_KEY_FAULTED;
    k_mutex_unlock(&gumi_recording_key_lock);
    return psa_error(status);
}

gumi_omi_v3012_recording_key_truth
gumi_omi_v3012_recording_key_get_truth(void)
{
    gumi_omi_v3012_recording_key_truth truth;

    k_mutex_lock(&gumi_recording_key_lock, K_FOREVER);
    truth = gumi_recording_key_truth;
    k_mutex_unlock(&gumi_recording_key_lock);
    return truth;
}
