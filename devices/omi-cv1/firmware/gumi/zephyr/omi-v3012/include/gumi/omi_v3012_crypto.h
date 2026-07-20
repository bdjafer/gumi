#ifndef GUMI_OMI_V3012_CRYPTO_H
#define GUMI_OMI_V3012_CRYPTO_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <psa/crypto.h>

#include "gumi/recording_journal.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Key creation, HUK/KMU provisioning, rotation, and logical-key-version lookup are intentionally
 * outside this port. The provider retains ownership of key_id and must not destroy it while open.
 */
typedef struct {
    psa_key_id_t key_id;
    bool initialized;
    bool open;
} gumi_omi_v3012_crypto_session;

/* Initializes the pinned Nordic PSA backend. This performs no key or hardware provisioning. */
int gumi_omi_v3012_crypto_init(void);

/*
 * Fills caller-owned memory from PSA's CSPRNG. An all-zero result is retried once and then rejected;
 * this is intended for nonzero session IDs, nonce bases, and filesystem name tokens.
 */
int gumi_omi_v3012_crypto_random_nonzero(uint8_t *output, size_t size);

int gumi_omi_v3012_crypto_session_init(gumi_omi_v3012_crypto_session *session);

/* Validates an existing AES-256/GCM key with encrypt and decrypt usage, then borrows its handle. */
int gumi_omi_v3012_crypto_session_open(
    gumi_omi_v3012_crypto_session *session,
    psa_key_id_t key_id
);

/* Forgets the borrowed handle. It does not destroy, purge, rotate, or otherwise mutate the key. */
int gumi_omi_v3012_crypto_session_close(gumi_omi_v3012_crypto_session *session);

/* Encrypts exactly the plan plaintext and emits ciphertext followed by the 16-byte GCM tag. */
int gumi_omi_v3012_crypto_protect(
    const gumi_omi_v3012_crypto_session *session,
    const gumi_recording_journal_plan *plan,
    const uint8_t *plaintext,
    size_t plaintext_size,
    uint8_t *protected_payload,
    size_t capacity,
    size_t *protected_size
);

/* Authenticates before releasing plaintext; any failure zeroes the expected plaintext output span. */
int gumi_omi_v3012_crypto_unprotect(
    const gumi_omi_v3012_crypto_session *session,
    const gumi_recording_journal_plan *plan,
    const uint8_t *protected_payload,
    size_t protected_size,
    uint8_t *plaintext,
    size_t capacity,
    size_t *plaintext_size
);

#ifdef __cplusplus
}
#endif

#endif
