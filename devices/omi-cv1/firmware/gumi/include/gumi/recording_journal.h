#ifndef GUMI_RECORDING_JOURNAL_H
#define GUMI_RECORDING_JOURNAL_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES 64U
#define GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES 48U
#define GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES 12U
#define GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES 16U
#define GUMI_RECORDING_JOURNAL_AAD_BYTES 100U
#define GUMI_RECORDING_JOURNAL_COMMIT_PLAINTEXT_BYTES 32U
#define GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES 256U
#define GUMI_RECORDING_JOURNAL_MAX_PROTECTED_PAYLOAD_BYTES \
    (GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES + \
     GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES)
#define GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES \
    (GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES + \
     GUMI_RECORDING_JOURNAL_MAX_PROTECTED_PAYLOAD_BYTES)

typedef enum {
    GUMI_RECORDING_JOURNAL_STATUS_OK = 0,
    GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT,
    GUMI_RECORDING_JOURNAL_STATUS_INVALID_CONFIGURATION,
    GUMI_RECORDING_JOURNAL_STATUS_INVALID_STATE,
    GUMI_RECORDING_JOURNAL_STATUS_COUNTER_EXHAUSTED,
    GUMI_RECORDING_JOURNAL_STATUS_OUTPUT_TOO_SMALL,
    GUMI_RECORDING_JOURNAL_STATUS_TRUNCATED,
    GUMI_RECORDING_JOURNAL_STATUS_CORRUPT,
    GUMI_RECORDING_JOURNAL_STATUS_UNSUPPORTED_VERSION,
    GUMI_RECORDING_JOURNAL_STATUS_SEQUENCE_GAP,
    GUMI_RECORDING_JOURNAL_STATUS_STALE_PLAN,
    GUMI_RECORDING_JOURNAL_STATUS_TRAILING_DATA,
} gumi_recording_journal_status;

typedef enum {
    GUMI_RECORDING_JOURNAL_CODEC_OPUS = 1,
} gumi_recording_journal_codec;

typedef enum {
    GUMI_RECORDING_JOURNAL_PROTECTION_AES_256_GCM_V1 = 1,
} gumi_recording_journal_protection;

typedef enum {
    GUMI_RECORDING_JOURNAL_RECORD_AUDIO = 1,
    GUMI_RECORDING_JOURNAL_RECORD_COMMIT = 2,
} gumi_recording_journal_record_kind;

typedef struct {
    uint64_t session_id;
    uint64_t recording_id;
    uint32_t sample_rate;
    uint32_t frame_samples;
    uint32_t max_codec_payload_bytes;
    uint32_t key_id;
    uint8_t nonce_base[GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES];
    gumi_recording_journal_codec codec;
    gumi_recording_journal_protection protection;
} gumi_recording_journal_config;

/*
 * A plan is immutable input to the platform crypto port. Encrypt exactly plaintext_size bytes with
 * AES-256-GCM, using nonce and aad, and return plaintext_size + 16 bytes of ciphertext and tag.
 * For a commit plan, commit_plaintext is the plaintext to encrypt.
 */
typedef struct {
    uint64_t session_id;
    uint64_t ordinal;
    uint64_t source_sequence;
    uint32_t pcm_sample_count;
    uint32_t plaintext_size;
    uint32_t protected_size;
    gumi_recording_journal_record_kind kind;
    uint8_t nonce[GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES];
    uint8_t aad[GUMI_RECORDING_JOURNAL_AAD_BYTES];
    uint8_t commit_plaintext[GUMI_RECORDING_JOURNAL_COMMIT_PLAINTEXT_BYTES];
} gumi_recording_journal_plan;

/* Public for caller-owned static allocation and diagnostics. Mutate only through this API. */
typedef struct {
    gumi_recording_journal_config config;
    uint64_t next_ordinal;
    uint64_t next_source_sequence;
    uint64_t audio_record_count;
    uint64_t total_pcm_samples;
    uint32_t protected_chain_crc32c;
    bool initialized;
    bool finalized;
} gumi_recording_journal_writer;

typedef struct {
    gumi_recording_journal_plan plan;
    const uint8_t *protected_payload;
    size_t encoded_size;
} gumi_recording_journal_record_view;

/*
 * valid_prefix_bytes ends after the last authenticated record accepted by recovery. A structurally
 * valid record is not accepted until its AES-GCM tag has been verified by the crypto port.
 */
typedef struct {
    gumi_recording_journal_config config;
    uint64_t next_ordinal;
    uint64_t next_source_sequence;
    uint64_t audio_record_count;
    uint64_t total_pcm_samples;
    uint64_t valid_prefix_bytes;
    uint32_t protected_chain_crc32c;
    bool initialized;
    bool committed;
} gumi_recording_journal_recovery;

uint32_t gumi_recording_journal_crc32c(const uint8_t *bytes, size_t size);

gumi_recording_journal_status gumi_recording_journal_writer_begin(
    gumi_recording_journal_writer *writer,
    const gumi_recording_journal_config *config,
    uint8_t *encoded_header,
    size_t capacity,
    size_t *encoded_size
);

gumi_recording_journal_status gumi_recording_journal_plan_audio(
    const gumi_recording_journal_writer *writer,
    uint64_t source_sequence,
    uint32_t pcm_sample_count,
    uint32_t codec_payload_size,
    gumi_recording_journal_plan *plan
);

gumi_recording_journal_status gumi_recording_journal_commit_audio(
    gumi_recording_journal_writer *writer,
    const gumi_recording_journal_plan *plan,
    const uint8_t *protected_payload,
    size_t protected_size,
    uint8_t *encoded_record,
    size_t capacity,
    size_t *encoded_size
);

gumi_recording_journal_status gumi_recording_journal_plan_finalize(
    const gumi_recording_journal_writer *writer,
    gumi_recording_journal_plan *plan
);

gumi_recording_journal_status gumi_recording_journal_commit_finalize(
    gumi_recording_journal_writer *writer,
    const gumi_recording_journal_plan *plan,
    const uint8_t *protected_payload,
    size_t protected_size,
    uint8_t *encoded_record,
    size_t capacity,
    size_t *encoded_size
);

gumi_recording_journal_status gumi_recording_journal_recovery_init(
    gumi_recording_journal_recovery *recovery,
    const uint8_t *encoded_header,
    size_t encoded_size
);

/* Inspect without advancing. The returned nonce/AAD must be used for AES-GCM verification. */
gumi_recording_journal_status gumi_recording_journal_recovery_inspect_next(
    const gumi_recording_journal_recovery *recovery,
    const uint8_t *encoded_record,
    size_t encoded_size,
    gumi_recording_journal_record_view *view
);

/*
 * Call only after authenticating and decrypting the inspected payload. A failed authentication must
 * never call this function and therefore cannot advance valid_prefix_bytes.
 */
gumi_recording_journal_status gumi_recording_journal_recovery_accept_next(
    gumi_recording_journal_recovery *recovery,
    const uint8_t *encoded_record,
    size_t encoded_size,
    const uint8_t *authenticated_plaintext,
    size_t plaintext_size
);

#ifdef __cplusplus
}
#endif

#endif
