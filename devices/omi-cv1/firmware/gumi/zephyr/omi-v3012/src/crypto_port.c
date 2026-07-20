#include "gumi/omi_v3012_crypto.h"

#include <errno.h>
#include <string.h>

static int psa_error(psa_status_t status)
{
    switch (status) {
        case PSA_SUCCESS:
            return 0;
        case PSA_ERROR_INVALID_ARGUMENT:
            return -EINVAL;
        case PSA_ERROR_NOT_PERMITTED:
            return -EACCES;
        case PSA_ERROR_NOT_SUPPORTED:
            return -ENOTSUP;
        case PSA_ERROR_INSUFFICIENT_MEMORY:
            return -ENOMEM;
        case PSA_ERROR_INSUFFICIENT_STORAGE:
            return -ENOSPC;
        case PSA_ERROR_INSUFFICIENT_ENTROPY:
            return -EAGAIN;
        case PSA_ERROR_INVALID_HANDLE:
        case PSA_ERROR_DOES_NOT_EXIST:
            return -ENOENT;
        case PSA_ERROR_INVALID_SIGNATURE:
            return -EBADMSG;
        case PSA_ERROR_BAD_STATE:
            return -EPIPE;
        case PSA_ERROR_BUFFER_TOO_SMALL:
            return -ENOBUFS;
        case PSA_ERROR_COMMUNICATION_FAILURE:
        case PSA_ERROR_STORAGE_FAILURE:
        case PSA_ERROR_HARDWARE_FAILURE:
        case PSA_ERROR_CORRUPTION_DETECTED:
        default:
            return -EIO;
    }
}

static bool bytes_are_nonzero(const uint8_t *bytes, size_t size)
{
    uint8_t combined = 0U;
    size_t index;

    for (index = 0U; index < size; index += 1U) {
        combined = (uint8_t)(combined | bytes[index]);
    }
    return combined != 0U;
}

static bool plan_is_valid(const gumi_recording_journal_plan *plan)
{
    return plan != NULL &&
           plan->session_id != UINT64_C(0) &&
           plan->ordinal != UINT64_C(0) &&
           (plan->kind == GUMI_RECORDING_JOURNAL_RECORD_AUDIO ||
            plan->kind == GUMI_RECORDING_JOURNAL_RECORD_COMMIT) &&
           plan->plaintext_size != UINT32_C(0) &&
           plan->plaintext_size <= GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES &&
           plan->protected_size ==
               plan->plaintext_size + GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES &&
           plan->protected_size <= GUMI_RECORDING_JOURNAL_MAX_PROTECTED_PAYLOAD_BYTES;
}

int gumi_omi_v3012_crypto_init(void)
{
    return psa_error(psa_crypto_init());
}

int gumi_omi_v3012_crypto_random_nonzero(uint8_t *output, size_t size)
{
    psa_status_t status;
    unsigned int attempt;

    if (output == NULL || size == 0U) {
        return -EINVAL;
    }
    for (attempt = 0U; attempt < 2U; attempt += 1U) {
        status = psa_generate_random(output, size);
        if (status != PSA_SUCCESS) {
            memset(output, 0, size);
            return psa_error(status);
        }
        if (bytes_are_nonzero(output, size)) {
            return 0;
        }
    }
    memset(output, 0, size);
    return -EIO;
}

int gumi_omi_v3012_crypto_session_init(gumi_omi_v3012_crypto_session *session)
{
    if (session == NULL) {
        return -EINVAL;
    }
    memset(session, 0, sizeof(*session));
    session->initialized = true;
    return 0;
}

int gumi_omi_v3012_crypto_session_open(
    gumi_omi_v3012_crypto_session *session,
    psa_key_id_t key_id
)
{
    psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
    psa_key_usage_t usage;
    psa_status_t status;
    int error;

    if (session == NULL || !session->initialized || key_id == PSA_KEY_ID_NULL) {
        return -EINVAL;
    }
    if (session->open) {
        return -EBUSY;
    }
    status = psa_get_key_attributes(key_id, &attributes);
    if (status != PSA_SUCCESS) {
        return psa_error(status);
    }
    usage = psa_get_key_usage_flags(&attributes);
    if (psa_get_key_type(&attributes) != PSA_KEY_TYPE_AES ||
        psa_get_key_bits(&attributes) != 256U ||
        psa_get_key_algorithm(&attributes) != PSA_ALG_GCM ||
        (usage & (PSA_KEY_USAGE_ENCRYPT | PSA_KEY_USAGE_DECRYPT)) !=
            (PSA_KEY_USAGE_ENCRYPT | PSA_KEY_USAGE_DECRYPT)) {
        error = -EACCES;
    } else {
        session->key_id = key_id;
        session->open = true;
        error = 0;
    }
    psa_reset_key_attributes(&attributes);
    return error;
}

int gumi_omi_v3012_crypto_session_close(gumi_omi_v3012_crypto_session *session)
{
    if (session == NULL) {
        return -EINVAL;
    }
    if (!session->initialized || !session->open) {
        return -EALREADY;
    }
    session->key_id = PSA_KEY_ID_NULL;
    session->open = false;
    return 0;
}

int gumi_omi_v3012_crypto_protect(
    const gumi_omi_v3012_crypto_session *session,
    const gumi_recording_journal_plan *plan,
    const uint8_t *plaintext,
    size_t plaintext_size,
    uint8_t *protected_payload,
    size_t capacity,
    size_t *protected_size
)
{
    psa_status_t status;
    size_t output_size = 0U;

    if (session == NULL || !session->open || !plan_is_valid(plan) || plaintext == NULL ||
        protected_payload == NULL || protected_size == NULL) {
        return -EINVAL;
    }
    *protected_size = 0U;
    if (plaintext_size != plan->plaintext_size) {
        return -EINVAL;
    }
    if (capacity < plan->protected_size) {
        return -ENOBUFS;
    }
    status = psa_aead_encrypt(
        session->key_id,
        PSA_ALG_GCM,
        plan->nonce,
        sizeof(plan->nonce),
        plan->aad,
        sizeof(plan->aad),
        plaintext,
        plaintext_size,
        protected_payload,
        capacity,
        &output_size
    );
    if (status != PSA_SUCCESS || output_size != plan->protected_size) {
        memset(protected_payload, 0, plan->protected_size);
        return status == PSA_SUCCESS ? -EIO : psa_error(status);
    }
    *protected_size = output_size;
    return 0;
}

int gumi_omi_v3012_crypto_unprotect(
    const gumi_omi_v3012_crypto_session *session,
    const gumi_recording_journal_plan *plan,
    const uint8_t *protected_payload,
    size_t protected_size,
    uint8_t *plaintext,
    size_t capacity,
    size_t *plaintext_size
)
{
    psa_status_t status;
    size_t output_size = 0U;

    if (session == NULL || !session->open || !plan_is_valid(plan) || protected_payload == NULL ||
        plaintext == NULL || plaintext_size == NULL) {
        return -EINVAL;
    }
    *plaintext_size = 0U;
    if (protected_size != plan->protected_size) {
        return -EINVAL;
    }
    if (capacity < plan->plaintext_size) {
        return -ENOBUFS;
    }
    status = psa_aead_decrypt(
        session->key_id,
        PSA_ALG_GCM,
        plan->nonce,
        sizeof(plan->nonce),
        plan->aad,
        sizeof(plan->aad),
        protected_payload,
        protected_size,
        plaintext,
        capacity,
        &output_size
    );
    if (status != PSA_SUCCESS || output_size != plan->plaintext_size) {
        memset(plaintext, 0, plan->plaintext_size);
        return status == PSA_SUCCESS ? -EIO : psa_error(status);
    }
    *plaintext_size = output_size;
    return 0;
}
