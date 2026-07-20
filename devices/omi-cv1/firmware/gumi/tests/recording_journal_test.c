#include "gumi/recording_journal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned int tests_run;

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #condition); \
        exit(EXIT_FAILURE); \
    } \
} while (0)

#define CHECK_STATUS(expression, expected) do { \
    const gumi_recording_journal_status actual_status = (expression); \
    if (actual_status != (expected)) { \
        fprintf( \
            stderr, \
            "%s:%d: status %d, expected %d: %s\n", \
            __FILE__, \
            __LINE__, \
            (int)actual_status, \
            (int)(expected), \
            #expression \
        ); \
        exit(EXIT_FAILURE); \
    } \
} while (0)

static void put_u32_le(uint8_t *output, uint32_t value)
{
    output[0] = (uint8_t)(value & UINT32_C(0xff));
    output[1] = (uint8_t)((value >> 8U) & UINT32_C(0xff));
    output[2] = (uint8_t)((value >> 16U) & UINT32_C(0xff));
    output[3] = (uint8_t)((value >> 24U) & UINT32_C(0xff));
}

static gumi_recording_journal_config config_fixture(void)
{
    gumi_recording_journal_config config;
    size_t index;

    memset(&config, 0, sizeof(config));
    config.session_id = UINT64_C(0x0102030405060708);
    config.recording_id = UINT64_C(0x1112131415161718);
    config.sample_rate = UINT32_C(16000);
    config.frame_samples = UINT32_C(320);
    config.max_codec_payload_bytes = GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES;
    config.key_id = UINT32_C(7);
    config.codec = GUMI_RECORDING_JOURNAL_CODEC_OPUS;
    config.protection = GUMI_RECORDING_JOURNAL_PROTECTION_AES_256_GCM_V1;
    for (index = 0U; index < sizeof(config.nonce_base); index += 1U) {
        config.nonce_base[index] = (uint8_t)(0xa0U + index);
    }
    return config;
}

/* Test-only authenticated transform. Production must use PSA AES-256-GCM. */
static void fake_tag(
    const gumi_recording_journal_plan *plan,
    const uint8_t *plaintext,
    size_t plaintext_size,
    uint8_t tag[GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES]
)
{
    uint8_t auth_input[
        GUMI_RECORDING_JOURNAL_AAD_BYTES +
        GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES +
        GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES
    ];
    uint32_t checksum;
    size_t index;
    const size_t prefix_size =
        GUMI_RECORDING_JOURNAL_AAD_BYTES + GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES;

    CHECK(plaintext_size <= GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES);
    memcpy(auth_input, plan->aad, GUMI_RECORDING_JOURNAL_AAD_BYTES);
    memcpy(
        &auth_input[GUMI_RECORDING_JOURNAL_AAD_BYTES],
        plan->nonce,
        GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES
    );
    memcpy(&auth_input[prefix_size], plaintext, plaintext_size);
    checksum = gumi_recording_journal_crc32c(auth_input, prefix_size + plaintext_size);
    for (index = 0U; index < GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES; index += 1U) {
        tag[index] = (uint8_t)(
            (checksum >> ((index % 4U) * 8U)) ^ (uint32_t)(0x31U + index)
        );
    }
}

static void fake_protect(
    const gumi_recording_journal_plan *plan,
    const uint8_t *plaintext,
    size_t plaintext_size,
    uint8_t *protected_payload,
    size_t *protected_size
)
{
    uint8_t tag[GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES];
    size_t index;

    CHECK(plan != NULL);
    CHECK(plaintext != NULL);
    CHECK(plaintext_size == plan->plaintext_size);
    for (index = 0U; index < plaintext_size; index += 1U) {
        protected_payload[index] = (uint8_t)(
            plaintext[index] ^ plan->nonce[index % sizeof(plan->nonce)] ^ UINT8_C(0x5a)
        );
    }
    fake_tag(plan, plaintext, plaintext_size, tag);
    memcpy(&protected_payload[plaintext_size], tag, sizeof(tag));
    *protected_size = plaintext_size + sizeof(tag);
}

static bool fake_unprotect(
    const gumi_recording_journal_plan *plan,
    const uint8_t *protected_payload,
    size_t protected_size,
    uint8_t *plaintext,
    size_t *plaintext_size
)
{
    uint8_t expected_tag[GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES];
    size_t index;

    if (protected_size != plan->protected_size) {
        return false;
    }
    *plaintext_size = protected_size - GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES;
    for (index = 0U; index < *plaintext_size; index += 1U) {
        plaintext[index] = (uint8_t)(
            protected_payload[index] ^ plan->nonce[index % sizeof(plan->nonce)] ^ UINT8_C(0x5a)
        );
    }
    fake_tag(plan, plaintext, *plaintext_size, expected_tag);
    return memcmp(
        &protected_payload[*plaintext_size],
        expected_tag,
        sizeof(expected_tag)
    ) == 0;
}

static size_t begin_writer(
    gumi_recording_journal_writer *writer,
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES]
)
{
    const gumi_recording_journal_config config = config_fixture();
    size_t encoded_size = 0U;

    CHECK_STATUS(
        gumi_recording_journal_writer_begin(
            writer,
            &config,
            header,
            GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES,
            &encoded_size
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    CHECK(encoded_size == GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES);
    return encoded_size;
}

static size_t append_audio(
    gumi_recording_journal_writer *writer,
    uint64_t source_sequence,
    const uint8_t *codec_payload,
    size_t codec_payload_size,
    uint8_t record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES],
    gumi_recording_journal_plan *committed_plan
)
{
    uint8_t protected_payload[GUMI_RECORDING_JOURNAL_MAX_PROTECTED_PAYLOAD_BYTES];
    size_t protected_size;
    size_t encoded_size = 0U;

    CHECK(codec_payload_size <= UINT32_MAX);
    CHECK_STATUS(
        gumi_recording_journal_plan_audio(
            writer,
            source_sequence,
            writer->config.frame_samples,
            (uint32_t)codec_payload_size,
            committed_plan
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    fake_protect(
        committed_plan,
        codec_payload,
        codec_payload_size,
        protected_payload,
        &protected_size
    );
    CHECK_STATUS(
        gumi_recording_journal_commit_audio(
            writer,
            committed_plan,
            protected_payload,
            protected_size,
            record,
            GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES,
            &encoded_size
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    return encoded_size;
}

static size_t finalize_writer(
    gumi_recording_journal_writer *writer,
    uint8_t record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES],
    gumi_recording_journal_plan *committed_plan
)
{
    uint8_t protected_payload[
        GUMI_RECORDING_JOURNAL_COMMIT_PLAINTEXT_BYTES +
        GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES
    ];
    size_t protected_size;
    size_t encoded_size = 0U;

    CHECK_STATUS(
        gumi_recording_journal_plan_finalize(writer, committed_plan),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    fake_protect(
        committed_plan,
        committed_plan->commit_plaintext,
        sizeof(committed_plan->commit_plaintext),
        protected_payload,
        &protected_size
    );
    CHECK_STATUS(
        gumi_recording_journal_commit_finalize(
            writer,
            committed_plan,
            protected_payload,
            protected_size,
            record,
            GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES,
            &encoded_size
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    return encoded_size;
}

static void recover_record(
    gumi_recording_journal_recovery *recovery,
    const uint8_t *record,
    size_t record_size,
    const uint8_t *expected_plaintext,
    size_t expected_plaintext_size
)
{
    gumi_recording_journal_record_view view;
    uint8_t plaintext[GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES];
    size_t plaintext_size = 0U;

    CHECK_STATUS(
        gumi_recording_journal_recovery_inspect_next(
            recovery,
            record,
            record_size,
            &view
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    CHECK(fake_unprotect(
        &view.plan,
        view.protected_payload,
        view.plan.protected_size,
        plaintext,
        &plaintext_size
    ));
    CHECK(plaintext_size == expected_plaintext_size);
    CHECK(memcmp(plaintext, expected_plaintext, plaintext_size) == 0);
    CHECK_STATUS(
        gumi_recording_journal_recovery_accept_next(
            recovery,
            record,
            record_size,
            plaintext,
            plaintext_size
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
}

static void test_crc32c_known_vector(void)
{
    static const uint8_t value[] = "123456789";

    CHECK(
        gumi_recording_journal_crc32c(value, sizeof(value) - 1U) == UINT32_C(0xe3069283)
    );
    CHECK(gumi_recording_journal_crc32c(NULL, 0U) == UINT32_C(0));
    tests_run += 1U;
}

static void test_header_round_trip_and_canonical_bytes(void)
{
    gumi_recording_journal_writer writer;
    gumi_recording_journal_recovery recovery;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];

    (void)begin_writer(&writer, header);
    CHECK(memcmp(header, "GUMIJNL1", 8U) == 0);
    CHECK(header[8] == 1U && header[9] == 0U);
    CHECK(header[10] == GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES);
    CHECK(header[16] == 0x08U && header[23] == 0x01U);
    CHECK_STATUS(
        gumi_recording_journal_recovery_init(&recovery, header, sizeof(header)),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    CHECK(recovery.config.session_id == writer.config.session_id);
    CHECK(recovery.config.recording_id == writer.config.recording_id);
    CHECK(recovery.config.key_id == writer.config.key_id);
    CHECK(memcmp(recovery.config.nonce_base, writer.config.nonce_base, 12U) == 0);
    CHECK(recovery.valid_prefix_bytes == GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES);
    tests_run += 1U;
}

static void test_begin_rejects_unsafe_configuration_transactionally(void)
{
    gumi_recording_journal_config config = config_fixture();
    gumi_recording_journal_writer writer;
    gumi_recording_journal_writer original;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    size_t encoded_size = 99U;

    memset(&writer, 0xa5, sizeof(writer));
    original = writer;
    memset(config.nonce_base, 0, sizeof(config.nonce_base));
    CHECK_STATUS(
        gumi_recording_journal_writer_begin(
            &writer,
            &config,
            header,
            sizeof(header),
            &encoded_size
        ),
        GUMI_RECORDING_JOURNAL_STATUS_INVALID_CONFIGURATION
    );
    CHECK(memcmp(&writer, &original, sizeof(writer)) == 0);
    CHECK(encoded_size == 99U);

    config = config_fixture();
    CHECK_STATUS(
        gumi_recording_journal_writer_begin(
            &writer,
            &config,
            header,
            sizeof(header) - 1U,
            &encoded_size
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OUTPUT_TOO_SMALL
    );
    CHECK(memcmp(&writer, &original, sizeof(writer)) == 0);
    tests_run += 1U;
}

static void test_plans_bind_unique_nonce_and_aad(void)
{
    static const uint8_t first_payload[] = {1U, 2U, 3U};
    gumi_recording_journal_writer writer;
    gumi_recording_journal_plan first;
    gumi_recording_journal_plan second;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    uint8_t record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];

    (void)begin_writer(&writer, header);
    (void)append_audio(&writer, 1U, first_payload, sizeof(first_payload), record, &first);
    CHECK_STATUS(
        gumi_recording_journal_plan_audio(
            &writer,
            2U,
            writer.config.frame_samples,
            4U,
            &second
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    CHECK(memcmp(first.nonce, second.nonce, sizeof(first.nonce)) != 0);
    CHECK(memcmp(first.aad, second.aad, sizeof(first.aad)) != 0);
    CHECK(memcmp(first.nonce, writer.config.nonce_base, 4U) == 0);
    CHECK(memcmp(second.nonce, writer.config.nonce_base, 4U) == 0);
    tests_run += 1U;
}

static void test_two_frame_round_trip_and_commit(void)
{
    static const uint8_t first_payload[] = {1U, 3U, 5U, 7U, 9U};
    static const uint8_t second_payload[] = {2U, 4U, 6U, 8U};
    gumi_recording_journal_writer writer;
    gumi_recording_journal_recovery recovery;
    gumi_recording_journal_plan plan;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    uint8_t first_record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    uint8_t second_record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    uint8_t commit_record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    size_t first_size;
    size_t second_size;
    size_t commit_size;

    (void)begin_writer(&writer, header);
    first_size = append_audio(
        &writer, 1U, first_payload, sizeof(first_payload), first_record, &plan
    );
    second_size = append_audio(
        &writer, 2U, second_payload, sizeof(second_payload), second_record, &plan
    );
    commit_size = finalize_writer(&writer, commit_record, &plan);
    CHECK(writer.finalized);
    CHECK(writer.audio_record_count == 2U);
    CHECK(writer.total_pcm_samples == 640U);

    CHECK_STATUS(
        gumi_recording_journal_recovery_init(&recovery, header, sizeof(header)),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    recover_record(&recovery, first_record, first_size, first_payload, sizeof(first_payload));
    recover_record(&recovery, second_record, second_size, second_payload, sizeof(second_payload));
    recover_record(
        &recovery,
        commit_record,
        commit_size,
        plan.commit_plaintext,
        sizeof(plan.commit_plaintext)
    );
    CHECK(recovery.committed);
    CHECK(recovery.audio_record_count == 2U);
    CHECK(recovery.total_pcm_samples == 640U);
    CHECK(
        recovery.valid_prefix_bytes ==
        sizeof(header) + first_size + second_size + commit_size
    );
    CHECK_STATUS(
        gumi_recording_journal_plan_finalize(&writer, &plan),
        GUMI_RECORDING_JOURNAL_STATUS_INVALID_STATE
    );
    tests_run += 1U;
}

static void test_sequence_gap_and_stale_plan_do_not_advance(void)
{
    gumi_recording_journal_writer writer;
    gumi_recording_journal_plan plan;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    uint8_t protected_payload[17];
    uint8_t record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    size_t protected_size;
    size_t record_size = 0U;
    static const uint8_t payload[] = {0x44U};

    (void)begin_writer(&writer, header);
    CHECK_STATUS(
        gumi_recording_journal_plan_audio(
            &writer,
            2U,
            writer.config.frame_samples,
            sizeof(payload),
            &plan
        ),
        GUMI_RECORDING_JOURNAL_STATUS_SEQUENCE_GAP
    );
    CHECK_STATUS(
        gumi_recording_journal_plan_audio(
            &writer,
            1U,
            writer.config.frame_samples,
            sizeof(payload),
            &plan
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    fake_protect(&plan, payload, sizeof(payload), protected_payload, &protected_size);
    plan.aad[0] ^= UINT8_C(1);
    CHECK_STATUS(
        gumi_recording_journal_commit_audio(
            &writer,
            &plan,
            protected_payload,
            protected_size,
            record,
            sizeof(record),
            &record_size
        ),
        GUMI_RECORDING_JOURNAL_STATUS_STALE_PLAN
    );
    CHECK(writer.next_ordinal == 1U);
    CHECK(writer.audio_record_count == 0U);
    tests_run += 1U;
}

static void test_short_output_does_not_advance_writer(void)
{
    static const uint8_t payload[] = {8U, 9U, 10U};
    gumi_recording_journal_writer writer;
    gumi_recording_journal_plan plan;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    uint8_t protected_payload[sizeof(payload) + GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES];
    uint8_t record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    size_t protected_size;
    size_t record_size = 123U;

    (void)begin_writer(&writer, header);
    CHECK_STATUS(
        gumi_recording_journal_plan_audio(
            &writer,
            1U,
            writer.config.frame_samples,
            sizeof(payload),
            &plan
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    fake_protect(&plan, payload, sizeof(payload), protected_payload, &protected_size);
    CHECK_STATUS(
        gumi_recording_journal_commit_audio(
            &writer,
            &plan,
            protected_payload,
            protected_size,
            record,
            GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES + protected_size - 1U,
            &record_size
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OUTPUT_TOO_SMALL
    );
    CHECK(writer.next_ordinal == 1U);
    CHECK(writer.audio_record_count == 0U);
    CHECK(record_size == 123U);
    tests_run += 1U;
}

static void test_audio_plan_enforces_codec_bounds(void)
{
    gumi_recording_journal_writer writer;
    gumi_recording_journal_plan plan;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];

    (void)begin_writer(&writer, header);
    CHECK_STATUS(
        gumi_recording_journal_plan_audio(&writer, 1U, 319U, 1U, &plan),
        GUMI_RECORDING_JOURNAL_STATUS_INVALID_CONFIGURATION
    );
    CHECK_STATUS(
        gumi_recording_journal_plan_audio(&writer, 1U, 320U, 0U, &plan),
        GUMI_RECORDING_JOURNAL_STATUS_INVALID_CONFIGURATION
    );
    CHECK_STATUS(
        gumi_recording_journal_plan_audio(&writer, 1U, 320U, 257U, &plan),
        GUMI_RECORDING_JOURNAL_STATUS_INVALID_CONFIGURATION
    );
    CHECK_STATUS(
        gumi_recording_journal_plan_audio(&writer, 1U, 320U, 256U, &plan),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    CHECK(plan.protected_size == 272U);
    tests_run += 1U;
}

static void test_structural_corruption_is_rejected_transactionally(void)
{
    static const uint8_t payload[] = {0x10U, 0x20U, 0x30U};
    gumi_recording_journal_writer writer;
    gumi_recording_journal_recovery recovery;
    gumi_recording_journal_plan plan;
    gumi_recording_journal_record_view view;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    uint8_t record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    uint8_t corrupt[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    size_t record_size;

    (void)begin_writer(&writer, header);
    record_size = append_audio(&writer, 1U, payload, sizeof(payload), record, &plan);
    CHECK_STATUS(
        gumi_recording_journal_recovery_init(&recovery, header, sizeof(header)),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );

    memcpy(corrupt, record, record_size);
    corrupt[48] ^= UINT8_C(1);
    CHECK_STATUS(
        gumi_recording_journal_recovery_inspect_next(
            &recovery,
            corrupt,
            record_size,
            &view
        ),
        GUMI_RECORDING_JOURNAL_STATUS_CORRUPT
    );
    CHECK(recovery.valid_prefix_bytes == sizeof(header));

    memcpy(corrupt, record, record_size);
    corrupt[24] ^= UINT8_C(1);
    CHECK_STATUS(
        gumi_recording_journal_recovery_inspect_next(
            &recovery,
            corrupt,
            record_size,
            &view
        ),
        GUMI_RECORDING_JOURNAL_STATUS_CORRUPT
    );
    CHECK(recovery.next_ordinal == 1U);
    tests_run += 1U;
}

static void test_truncated_tail_preserves_authenticated_prefix(void)
{
    static const uint8_t first_payload[] = {1U, 2U};
    static const uint8_t second_payload[] = {3U, 4U, 5U};
    gumi_recording_journal_writer writer;
    gumi_recording_journal_recovery recovery;
    gumi_recording_journal_plan plan;
    gumi_recording_journal_record_view view;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    uint8_t first_record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    uint8_t second_record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    size_t first_size;
    size_t second_size;

    (void)begin_writer(&writer, header);
    first_size = append_audio(
        &writer, 1U, first_payload, sizeof(first_payload), first_record, &plan
    );
    second_size = append_audio(
        &writer, 2U, second_payload, sizeof(second_payload), second_record, &plan
    );
    CHECK_STATUS(
        gumi_recording_journal_recovery_init(&recovery, header, sizeof(header)),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    recover_record(&recovery, first_record, first_size, first_payload, sizeof(first_payload));
    CHECK_STATUS(
        gumi_recording_journal_recovery_inspect_next(
            &recovery,
            second_record,
            second_size - 1U,
            &view
        ),
        GUMI_RECORDING_JOURNAL_STATUS_TRUNCATED
    );
    CHECK(recovery.audio_record_count == 1U);
    CHECK(recovery.valid_prefix_bytes == sizeof(header) + first_size);
    CHECK_STATUS(
        gumi_recording_journal_recovery_inspect_next(
            &recovery,
            second_record,
            second_size + 1U,
            &view
        ),
        GUMI_RECORDING_JOURNAL_STATUS_TRAILING_DATA
    );
    tests_run += 1U;
}

static void test_bad_authentication_never_advances_recovery(void)
{
    static const uint8_t payload[] = {0xaaU, 0xbbU};
    gumi_recording_journal_writer writer;
    gumi_recording_journal_recovery recovery;
    gumi_recording_journal_plan plan;
    gumi_recording_journal_record_view view;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    uint8_t record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    uint8_t plaintext[GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES];
    size_t record_size;
    size_t plaintext_size = 0U;

    (void)begin_writer(&writer, header);
    record_size = append_audio(&writer, 1U, payload, sizeof(payload), record, &plan);
    CHECK_STATUS(
        gumi_recording_journal_recovery_init(&recovery, header, sizeof(header)),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    CHECK_STATUS(
        gumi_recording_journal_recovery_inspect_next(
            &recovery,
            record,
            record_size,
            &view
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    record[record_size - 1U] ^= UINT8_C(1);
    CHECK(!fake_unprotect(
        &view.plan,
        view.protected_payload,
        view.plan.protected_size,
        plaintext,
        &plaintext_size
    ));
    CHECK(recovery.next_ordinal == 1U);
    CHECK(recovery.valid_prefix_bytes == sizeof(header));
    tests_run += 1U;
}

static void test_authenticated_wrong_commit_summary_is_corrupt(void)
{
    static const uint8_t payload[] = {0x51U, 0x52U};
    gumi_recording_journal_writer writer;
    gumi_recording_journal_recovery recovery;
    gumi_recording_journal_plan audio_plan;
    gumi_recording_journal_plan commit_plan;
    gumi_recording_journal_record_view view;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    uint8_t audio_record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    uint8_t commit_record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    uint8_t wrong_summary[GUMI_RECORDING_JOURNAL_COMMIT_PLAINTEXT_BYTES];
    uint8_t protected_summary[
        GUMI_RECORDING_JOURNAL_COMMIT_PLAINTEXT_BYTES +
        GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES
    ];
    uint8_t decrypted[GUMI_RECORDING_JOURNAL_COMMIT_PLAINTEXT_BYTES];
    size_t audio_size;
    size_t protected_size;
    size_t commit_size = 0U;
    size_t decrypted_size = 0U;

    (void)begin_writer(&writer, header);
    audio_size = append_audio(
        &writer, 1U, payload, sizeof(payload), audio_record, &audio_plan
    );
    CHECK_STATUS(
        gumi_recording_journal_plan_finalize(&writer, &commit_plan),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    memcpy(wrong_summary, commit_plan.commit_plaintext, sizeof(wrong_summary));
    wrong_summary[0] ^= UINT8_C(1);
    fake_protect(
        &commit_plan,
        wrong_summary,
        sizeof(wrong_summary),
        protected_summary,
        &protected_size
    );
    CHECK_STATUS(
        gumi_recording_journal_commit_finalize(
            &writer,
            &commit_plan,
            protected_summary,
            protected_size,
            commit_record,
            sizeof(commit_record),
            &commit_size
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );

    CHECK_STATUS(
        gumi_recording_journal_recovery_init(&recovery, header, sizeof(header)),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    recover_record(&recovery, audio_record, audio_size, payload, sizeof(payload));
    CHECK_STATUS(
        gumi_recording_journal_recovery_inspect_next(
            &recovery,
            commit_record,
            commit_size,
            &view
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    CHECK(fake_unprotect(
        &view.plan,
        view.protected_payload,
        view.plan.protected_size,
        decrypted,
        &decrypted_size
    ));
    CHECK_STATUS(
        gumi_recording_journal_recovery_accept_next(
            &recovery,
            commit_record,
            commit_size,
            decrypted,
            decrypted_size
        ),
        GUMI_RECORDING_JOURNAL_STATUS_CORRUPT
    );
    CHECK(!recovery.committed);
    tests_run += 1U;
}

static void test_empty_recording_can_commit(void)
{
    gumi_recording_journal_writer writer;
    gumi_recording_journal_recovery recovery;
    gumi_recording_journal_plan plan;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    uint8_t commit_record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    size_t commit_size;

    (void)begin_writer(&writer, header);
    commit_size = finalize_writer(&writer, commit_record, &plan);
    CHECK_STATUS(
        gumi_recording_journal_recovery_init(&recovery, header, sizeof(header)),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    recover_record(
        &recovery,
        commit_record,
        commit_size,
        plan.commit_plaintext,
        sizeof(plan.commit_plaintext)
    );
    CHECK(recovery.committed);
    CHECK(recovery.audio_record_count == 0U);
    CHECK(recovery.total_pcm_samples == 0U);
    tests_run += 1U;
}

static void test_counter_exhaustion_is_fail_closed(void)
{
    gumi_recording_journal_writer writer;
    gumi_recording_journal_plan plan;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];

    (void)begin_writer(&writer, header);
    writer.next_ordinal = UINT64_MAX;
    CHECK_STATUS(
        gumi_recording_journal_plan_audio(
            &writer,
            1U,
            writer.config.frame_samples,
            1U,
            &plan
        ),
        GUMI_RECORDING_JOURNAL_STATUS_COUNTER_EXHAUSTED
    );
    CHECK(writer.audio_record_count == 0U);
    tests_run += 1U;
}

static void test_output_may_alias_protected_payload(void)
{
    static const uint8_t payload[] = {4U, 3U, 2U, 1U};
    gumi_recording_journal_writer writer;
    gumi_recording_journal_recovery recovery;
    gumi_recording_journal_plan plan;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    uint8_t shared[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    size_t protected_size;
    size_t record_size = 0U;

    (void)begin_writer(&writer, header);
    CHECK_STATUS(
        gumi_recording_journal_plan_audio(
            &writer,
            1U,
            writer.config.frame_samples,
            sizeof(payload),
            &plan
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    fake_protect(&plan, payload, sizeof(payload), shared, &protected_size);
    CHECK_STATUS(
        gumi_recording_journal_commit_audio(
            &writer,
            &plan,
            shared,
            protected_size,
            shared,
            sizeof(shared),
            &record_size
        ),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    CHECK_STATUS(
        gumi_recording_journal_recovery_init(&recovery, header, sizeof(header)),
        GUMI_RECORDING_JOURNAL_STATUS_OK
    );
    recover_record(&recovery, shared, record_size, payload, sizeof(payload));
    tests_run += 1U;
}

static void test_header_corruption_and_version_are_distinct(void)
{
    gumi_recording_journal_writer writer;
    gumi_recording_journal_recovery recovery;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];

    (void)begin_writer(&writer, header);
    header[20] ^= UINT8_C(1);
    CHECK_STATUS(
        gumi_recording_journal_recovery_init(&recovery, header, sizeof(header)),
        GUMI_RECORDING_JOURNAL_STATUS_CORRUPT
    );

    (void)begin_writer(&writer, header);
    header[8] = 2U;
    put_u32_le(&header[60], gumi_recording_journal_crc32c(header, 60U));
    CHECK_STATUS(
        gumi_recording_journal_recovery_init(&recovery, header, sizeof(header)),
        GUMI_RECORDING_JOURNAL_STATUS_UNSUPPORTED_VERSION
    );
    tests_run += 1U;
}

int main(void)
{
    test_crc32c_known_vector();
    test_header_round_trip_and_canonical_bytes();
    test_begin_rejects_unsafe_configuration_transactionally();
    test_plans_bind_unique_nonce_and_aad();
    test_two_frame_round_trip_and_commit();
    test_sequence_gap_and_stale_plan_do_not_advance();
    test_short_output_does_not_advance_writer();
    test_audio_plan_enforces_codec_bounds();
    test_structural_corruption_is_rejected_transactionally();
    test_truncated_tail_preserves_authenticated_prefix();
    test_bad_authentication_never_advances_recovery();
    test_authenticated_wrong_commit_summary_is_corrupt();
    test_empty_recording_can_commit();
    test_counter_exhaustion_is_fail_closed();
    test_output_may_alias_protected_payload();
    test_header_corruption_and_version_are_distinct();
    printf("PASS: %u portable encrypted recording-journal tests\n", tests_run);
    return EXIT_SUCCESS;
}
