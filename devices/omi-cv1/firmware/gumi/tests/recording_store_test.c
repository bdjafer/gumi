#include "gumi/recording_store.h"

#include <stdint.h>
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
    gumi_recording_store_status actual_status = (expression); \
    CHECK(actual_status == (expected)); \
} while (0)

typedef struct {
    uint8_t bytes[4096];
    size_t size;
    char open_name[GUMI_RECORDING_STORE_OBJECT_NAME_BYTES];
    char committed_name[GUMI_RECORDING_STORE_OBJECT_NAME_BYTES];
    unsigned int file_syncs;
    unsigned int volume_syncs;
    unsigned int closes;
    unsigned int protects;
    bool partial_exists;
    bool committed_exists;
    bool open;
    bool force_short_write;
    bool force_no_space;
    bool force_crypto_failure;
    bool force_sync_failure;
    bool force_rename_collision;
} fake_io;

static gumi_recording_store_io_status fake_exists(
    void *context,
    const char *name,
    bool *exists
)
{
    fake_io *io = context;

    if (strstr(name, ".PRT") != NULL) {
        *exists = io->partial_exists;
    } else if (strstr(name, ".GMR") != NULL) {
        *exists = io->committed_exists || io->force_rename_collision;
    } else {
        return GUMI_RECORDING_STORE_IO_FAILURE;
    }
    return GUMI_RECORDING_STORE_IO_OK;
}

static gumi_recording_store_io_status fake_create(
    void *context,
    const char *name
)
{
    fake_io *io = context;

    if (io->partial_exists) {
        return GUMI_RECORDING_STORE_IO_ALREADY_EXISTS;
    }
    io->partial_exists = true;
    io->open = true;
    memcpy(io->open_name, name, sizeof(io->open_name));
    return GUMI_RECORDING_STORE_IO_OK;
}

static gumi_recording_store_io_status fake_append(
    void *context,
    const uint8_t *bytes,
    size_t size,
    size_t *written
)
{
    fake_io *io = context;

    if (io->force_no_space) {
        *written = 0U;
        return GUMI_RECORDING_STORE_IO_NO_SPACE;
    }
    if (!io->open || io->size + size > sizeof(io->bytes)) {
        *written = 0U;
        return GUMI_RECORDING_STORE_IO_FAILURE;
    }
    *written = io->force_short_write ? size - 1U : size;
    memcpy(&io->bytes[io->size], bytes, *written);
    io->size += *written;
    return GUMI_RECORDING_STORE_IO_OK;
}

static gumi_recording_store_io_status fake_sync_file(void *context)
{
    fake_io *io = context;
    io->file_syncs += 1U;
    return io->force_sync_failure
        ? GUMI_RECORDING_STORE_IO_FAILURE
        : GUMI_RECORDING_STORE_IO_OK;
}

static gumi_recording_store_io_status fake_sync_volume(void *context)
{
    fake_io *io = context;
    io->volume_syncs += 1U;
    return io->force_sync_failure
        ? GUMI_RECORDING_STORE_IO_FAILURE
        : GUMI_RECORDING_STORE_IO_OK;
}

static gumi_recording_store_io_status fake_close(void *context)
{
    fake_io *io = context;
    io->closes += 1U;
    io->open = false;
    return GUMI_RECORDING_STORE_IO_OK;
}

static gumi_recording_store_io_status fake_rename(
    void *context,
    const char *source,
    const char *destination
)
{
    fake_io *io = context;

    if (io->force_rename_collision || io->committed_exists) {
        return GUMI_RECORDING_STORE_IO_ALREADY_EXISTS;
    }
    if (!io->partial_exists || strstr(source, ".PRT") == NULL ||
        strstr(destination, ".GMR") == NULL) {
        return GUMI_RECORDING_STORE_IO_FAILURE;
    }
    io->partial_exists = false;
    io->committed_exists = true;
    memcpy(io->committed_name, destination, sizeof(io->committed_name));
    return GUMI_RECORDING_STORE_IO_OK;
}

static int fake_protect(
    void *context,
    const gumi_recording_journal_plan *plan,
    const uint8_t *plaintext,
    size_t plaintext_size,
    uint8_t *protected_payload,
    size_t capacity,
    size_t *protected_size
)
{
    fake_io *io = context;
    size_t index;

    io->protects += 1U;
    if (io->force_crypto_failure || capacity < plan->protected_size ||
        plaintext_size != plan->plaintext_size) {
        *protected_size = 0U;
        return -1;
    }
    for (index = 0U; index < plaintext_size; index += 1U) {
        protected_payload[index] = (uint8_t)(plaintext[index] ^ UINT8_C(0xa5));
    }
    memset(
        &protected_payload[plaintext_size],
        0x5a,
        GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES
    );
    *protected_size = plan->protected_size;
    return 0;
}

static const gumi_recording_store_io fake_operations = {
    .exists = fake_exists,
    .create_new = fake_create,
    .append = fake_append,
    .sync_file = fake_sync_file,
    .sync_volume = fake_sync_volume,
    .close = fake_close,
    .rename_no_replace = fake_rename,
    .protect = fake_protect,
};

static gumi_recording_store_config valid_config(void)
{
    gumi_recording_store_config config;

    memset(&config, 0, sizeof(config));
    config.journal.session_id = UINT64_C(0x1001);
    config.journal.recording_id = UINT64_C(0x2001);
    config.journal.sample_rate = UINT32_C(16000);
    config.journal.frame_samples = UINT32_C(320);
    config.journal.max_codec_payload_bytes = UINT32_C(256);
    config.journal.key_id = UINT32_C(7);
    config.journal.codec = GUMI_RECORDING_JOURNAL_CODEC_OPUS;
    config.journal.protection = GUMI_RECORDING_JOURNAL_PROTECTION_AES_256_GCM_V1;
    config.journal.nonce_base[0] = UINT8_C(1);
    config.name_token[0] = UINT8_C(0x01);
    config.name_token[1] = UINT8_C(0xab);
    config.name_token[2] = UINT8_C(0x23);
    config.name_token[3] = UINT8_C(0xcd);
    config.sync_every_audio_records = 2U;
    return config;
}

static void prepare(
    gumi_recording_store *store,
    fake_io *io,
    const gumi_recording_store_config *config
)
{
    memset(io, 0, sizeof(*io));
    CHECK_STATUS(
        gumi_recording_store_prepare(store, config, &fake_operations, io),
        GUMI_RECORDING_STORE_STATUS_OK
    );
}

static void test_prepare_is_create_new_and_durable(void)
{
    gumi_recording_store store;
    fake_io io;
    gumi_recording_store_config config = valid_config();

    prepare(&store, &io, &config);
    CHECK(store.phase == GUMI_RECORDING_STORE_PHASE_ACTIVE);
    CHECK(store.file_open);
    CHECK(strcmp(store.partial_name, "01AB23CD.PRT") == 0);
    CHECK(strcmp(store.committed_name, "01AB23CD.GMR") == 0);
    CHECK(io.size == GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES);
    CHECK(io.file_syncs == 1U && io.volume_syncs == 1U);
    tests_run += 1U;
}

static void test_prepare_rejects_both_name_collisions(void)
{
    gumi_recording_store store;
    fake_io io;
    gumi_recording_store_config config = valid_config();

    memset(&io, 0, sizeof(io));
    io.partial_exists = true;
    CHECK_STATUS(
        gumi_recording_store_prepare(&store, &config, &fake_operations, &io),
        GUMI_RECORDING_STORE_STATUS_NAME_COLLISION
    );
    memset(&io, 0, sizeof(io));
    io.committed_exists = true;
    CHECK_STATUS(
        gumi_recording_store_prepare(&store, &config, &fake_operations, &io),
        GUMI_RECORDING_STORE_STATUS_NAME_COLLISION
    );
    tests_run += 1U;
}

static void test_audio_advances_only_after_exact_append(void)
{
    gumi_recording_store store;
    fake_io io;
    gumi_recording_store_config config = valid_config();
    const uint8_t packet[] = {1U, 2U, 3U};

    prepare(&store, &io, &config);
    CHECK_STATUS(
        gumi_recording_store_append_audio(&store, 1U, 320U, packet, sizeof(packet)),
        GUMI_RECORDING_STORE_STATUS_OK
    );
    CHECK(store.writer.next_ordinal == 2U);
    CHECK(store.writer.audio_record_count == 1U);
    CHECK(io.file_syncs == 1U);
    io.force_short_write = true;
    CHECK_STATUS(
        gumi_recording_store_append_audio(&store, 2U, 320U, packet, sizeof(packet)),
        GUMI_RECORDING_STORE_STATUS_SHORT_WRITE
    );
    CHECK(store.writer.next_ordinal == 2U);
    CHECK(store.writer.audio_record_count == 1U);
    CHECK(store.phase == GUMI_RECORDING_STORE_PHASE_FAILED);
    tests_run += 1U;
}

static void test_sync_cadence_is_explicit(void)
{
    gumi_recording_store store;
    fake_io io;
    gumi_recording_store_config config = valid_config();
    const uint8_t packet[] = {1U};

    prepare(&store, &io, &config);
    CHECK_STATUS(gumi_recording_store_append_audio(&store, 1U, 320U, packet, 1U), 0);
    CHECK(io.file_syncs == 1U && io.volume_syncs == 1U);
    CHECK_STATUS(gumi_recording_store_append_audio(&store, 2U, 320U, packet, 1U), 0);
    CHECK(io.file_syncs == 2U && io.volume_syncs == 2U);
    tests_run += 1U;
}

static void test_crypto_failure_never_appends_or_advances(void)
{
    gumi_recording_store store;
    fake_io io;
    gumi_recording_store_config config = valid_config();
    const uint8_t packet[] = {1U, 2U};
    size_t size_before;

    prepare(&store, &io, &config);
    size_before = io.size;
    io.force_crypto_failure = true;
    CHECK_STATUS(
        gumi_recording_store_append_audio(&store, 1U, 320U, packet, sizeof(packet)),
        GUMI_RECORDING_STORE_STATUS_CRYPTO_FAILURE
    );
    CHECK(io.size == size_before);
    CHECK(store.writer.next_ordinal == 1U);
    CHECK(store.phase == GUMI_RECORDING_STORE_PHASE_FAILED);
    tests_run += 1U;
}

static void test_normal_finalize_commits_syncs_closes_and_renames(void)
{
    gumi_recording_store store;
    fake_io io;
    gumi_recording_store_config config = valid_config();
    const uint8_t packet[] = {9U, 8U, 7U};

    prepare(&store, &io, &config);
    CHECK_STATUS(
        gumi_recording_store_append_audio(&store, 1U, 320U, packet, sizeof(packet)),
        GUMI_RECORDING_STORE_STATUS_OK
    );
    CHECK_STATUS(gumi_recording_store_finalize(&store), GUMI_RECORDING_STORE_STATUS_OK);
    CHECK(store.phase == GUMI_RECORDING_STORE_PHASE_COMMITTED);
    CHECK(store.writer.finalized);
    CHECK(!store.file_open && !io.open);
    CHECK(!io.partial_exists && io.committed_exists);
    CHECK(strcmp(io.committed_name, "01AB23CD.GMR") == 0);
    CHECK(io.closes == 1U);
    CHECK(io.file_syncs == 2U);
    CHECK(io.volume_syncs == 3U);
    tests_run += 1U;
}

static void test_finalize_never_replaces_destination(void)
{
    gumi_recording_store store;
    fake_io io;
    gumi_recording_store_config config = valid_config();

    prepare(&store, &io, &config);
    io.force_rename_collision = true;
    CHECK_STATUS(
        gumi_recording_store_finalize(&store),
        GUMI_RECORDING_STORE_STATUS_NAME_COLLISION
    );
    CHECK(store.phase == GUMI_RECORDING_STORE_PHASE_FAILED);
    CHECK(io.partial_exists && !io.committed_exists);
    tests_run += 1U;
}

static void test_no_space_is_distinct_and_leaves_recoverable_partial(void)
{
    gumi_recording_store store;
    fake_io io;
    gumi_recording_store_config config = valid_config();
    const uint8_t packet[] = {4U};

    prepare(&store, &io, &config);
    io.force_no_space = true;
    CHECK_STATUS(
        gumi_recording_store_append_audio(&store, 1U, 320U, packet, sizeof(packet)),
        GUMI_RECORDING_STORE_STATUS_NO_SPACE
    );
    CHECK(store.phase == GUMI_RECORDING_STORE_PHASE_FAILED);
    CHECK(io.partial_exists && !io.committed_exists);
    tests_run += 1U;
}

static void test_interrupt_syncs_closes_and_never_commits(void)
{
    gumi_recording_store store;
    fake_io io;
    gumi_recording_store_config config = valid_config();
    const uint8_t packet[] = {5U, 6U};

    prepare(&store, &io, &config);
    CHECK_STATUS(
        gumi_recording_store_append_audio(&store, 1U, 320U, packet, sizeof(packet)),
        GUMI_RECORDING_STORE_STATUS_OK
    );
    CHECK_STATUS(
        gumi_recording_store_interrupt(&store),
        GUMI_RECORDING_STORE_STATUS_OK
    );
    CHECK(store.phase == GUMI_RECORDING_STORE_PHASE_INTERRUPTED);
    CHECK(!store.file_open);
    CHECK(io.partial_exists && !io.committed_exists);
    CHECK(!store.writer.finalized);
    tests_run += 1U;
}

static void test_sync_failure_fails_closed(void)
{
    gumi_recording_store store;
    fake_io io;
    gumi_recording_store_config config = valid_config();
    const uint8_t packet[] = {1U};

    config.sync_every_audio_records = 1U;
    prepare(&store, &io, &config);
    io.force_sync_failure = true;
    CHECK_STATUS(
        gumi_recording_store_append_audio(&store, 1U, 320U, packet, 1U),
        GUMI_RECORDING_STORE_STATUS_IO_FAILURE
    );
    CHECK(store.phase == GUMI_RECORDING_STORE_PHASE_FAILED);
    tests_run += 1U;
}

static void test_invalid_configuration_is_transactional(void)
{
    gumi_recording_store store;
    fake_io io;
    gumi_recording_store_config config = valid_config();

    memset(&io, 0, sizeof(io));
    memset(config.name_token, 0, sizeof(config.name_token));
    CHECK_STATUS(
        gumi_recording_store_prepare(&store, &config, &fake_operations, &io),
        GUMI_RECORDING_STORE_STATUS_INVALID_CONFIGURATION
    );
    CHECK(!io.partial_exists && io.size == 0U);
    tests_run += 1U;
}

int main(void)
{
    test_prepare_is_create_new_and_durable();
    test_prepare_rejects_both_name_collisions();
    test_audio_advances_only_after_exact_append();
    test_sync_cadence_is_explicit();
    test_crypto_failure_never_appends_or_advances();
    test_normal_finalize_commits_syncs_closes_and_renames();
    test_finalize_never_replaces_destination();
    test_no_space_is_distinct_and_leaves_recoverable_partial();
    test_interrupt_syncs_closes_and_never_commits();
    test_sync_failure_fails_closed();
    test_invalid_configuration_is_transactional();
    printf("PASS: %u portable recording-store tests\n", tests_run);
    return EXIT_SUCCESS;
}
