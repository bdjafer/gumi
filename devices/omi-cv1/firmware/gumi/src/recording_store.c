#include "gumi/recording_store.h"

#include <string.h>

static bool io_is_complete(const gumi_recording_store_io *io)
{
    return io != NULL &&
           io->exists != NULL &&
           io->create_new != NULL &&
           io->append != NULL &&
           io->sync_file != NULL &&
           io->sync_volume != NULL &&
           io->close != NULL &&
           io->rename_no_replace != NULL &&
           io->protect != NULL;
}

static bool token_is_nonzero(
    const uint8_t token[GUMI_RECORDING_STORE_NAME_TOKEN_BYTES]
)
{
    uint8_t combined = 0U;
    size_t index;

    for (index = 0U; index < GUMI_RECORDING_STORE_NAME_TOKEN_BYTES; index += 1U) {
        combined = (uint8_t)(combined | token[index]);
    }
    return combined != 0U;
}

static char hex_digit(uint8_t nibble)
{
    return nibble < 10U
        ? (char)('0' + nibble)
        : (char)('A' + (nibble - 10U));
}

static void build_name(
    const uint8_t token[GUMI_RECORDING_STORE_NAME_TOKEN_BYTES],
    const char extension[3],
    char output[GUMI_RECORDING_STORE_OBJECT_NAME_BYTES]
)
{
    size_t index;

    for (index = 0U; index < GUMI_RECORDING_STORE_NAME_TOKEN_BYTES; index += 1U) {
        output[index * 2U] = hex_digit((uint8_t)(token[index] >> 4U));
        output[index * 2U + 1U] = hex_digit((uint8_t)(token[index] & UINT8_C(0x0f)));
    }
    output[8] = '.';
    output[9] = extension[0];
    output[10] = extension[1];
    output[11] = extension[2];
    output[12] = '\0';
}

static gumi_recording_store_status map_io(
    gumi_recording_store_io_status status
)
{
    switch (status) {
        case GUMI_RECORDING_STORE_IO_OK:
            return GUMI_RECORDING_STORE_STATUS_OK;
        case GUMI_RECORDING_STORE_IO_ALREADY_EXISTS:
            return GUMI_RECORDING_STORE_STATUS_NAME_COLLISION;
        case GUMI_RECORDING_STORE_IO_NO_SPACE:
            return GUMI_RECORDING_STORE_STATUS_NO_SPACE;
        case GUMI_RECORDING_STORE_IO_NOT_FOUND:
        case GUMI_RECORDING_STORE_IO_CORRUPT:
        case GUMI_RECORDING_STORE_IO_FAILURE:
        default:
            return GUMI_RECORDING_STORE_STATUS_IO_FAILURE;
    }
}

static void fail_store(gumi_recording_store *store)
{
    store->phase = GUMI_RECORDING_STORE_PHASE_FAILED;
}

static gumi_recording_store_status exact_append(
    gumi_recording_store *store,
    const uint8_t *bytes,
    size_t size
)
{
    size_t written = 0U;
    gumi_recording_store_status status;

    status = map_io(store->io->append(store->io_context, bytes, size, &written));
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        fail_store(store);
        return status;
    }
    if (written != size) {
        fail_store(store);
        return GUMI_RECORDING_STORE_STATUS_SHORT_WRITE;
    }
    return GUMI_RECORDING_STORE_STATUS_OK;
}

static gumi_recording_store_status sync_open_file(
    gumi_recording_store *store
)
{
    gumi_recording_store_status status;

    status = map_io(store->io->sync_file(store->io_context));
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        fail_store(store);
        return status;
    }
    status = map_io(store->io->sync_volume(store->io_context));
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        fail_store(store);
        return status;
    }
    store->records_since_sync = 0U;
    return GUMI_RECORDING_STORE_STATUS_OK;
}

static void best_effort_close(gumi_recording_store *store)
{
    if (store->file_open) {
        (void)store->io->close(store->io_context);
        store->file_open = false;
    }
}

gumi_recording_store_status gumi_recording_store_prepare(
    gumi_recording_store *store,
    const gumi_recording_store_config *config,
    const gumi_recording_store_io *io,
    void *io_context
)
{
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    size_t header_size = 0U;
    bool exists = false;
    gumi_recording_journal_status journal_status;
    gumi_recording_store_status status;

    if (store == NULL || config == NULL || io == NULL) {
        return GUMI_RECORDING_STORE_STATUS_INVALID_ARGUMENT;
    }
    if (!io_is_complete(io) ||
        !token_is_nonzero(config->name_token) ||
        config->sync_every_audio_records == 0U) {
        return GUMI_RECORDING_STORE_STATUS_INVALID_CONFIGURATION;
    }

    memset(store, 0, sizeof(*store));
    store->io = io;
    store->io_context = io_context;
    store->phase = GUMI_RECORDING_STORE_PHASE_EMPTY;
    store->initialized = true;
    build_name(config->name_token, "PRT", store->partial_name);
    build_name(config->name_token, "GMR", store->committed_name);

    status = map_io(io->exists(io_context, store->partial_name, &exists));
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        fail_store(store);
        return status;
    }
    if (exists) {
        fail_store(store);
        return GUMI_RECORDING_STORE_STATUS_NAME_COLLISION;
    }
    status = map_io(io->exists(io_context, store->committed_name, &exists));
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        fail_store(store);
        return status;
    }
    if (exists) {
        fail_store(store);
        return GUMI_RECORDING_STORE_STATUS_NAME_COLLISION;
    }

    journal_status = gumi_recording_journal_writer_begin(
        &store->writer,
        &config->journal,
        header,
        sizeof(header),
        &header_size
    );
    if (journal_status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        fail_store(store);
        return journal_status == GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT
            ? GUMI_RECORDING_STORE_STATUS_INVALID_ARGUMENT
            : GUMI_RECORDING_STORE_STATUS_INVALID_CONFIGURATION;
    }

    status = map_io(io->create_new(io_context, store->partial_name));
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        fail_store(store);
        return status;
    }
    store->file_open = true;
    status = exact_append(store, header, header_size);
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        best_effort_close(store);
        return status;
    }
    status = sync_open_file(store);
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        best_effort_close(store);
        return status;
    }
    store->phase = GUMI_RECORDING_STORE_PHASE_ACTIVE;
    store->sync_every_audio_records = config->sync_every_audio_records;
    return GUMI_RECORDING_STORE_STATUS_OK;
}

gumi_recording_store_status gumi_recording_store_append_audio(
    gumi_recording_store *store,
    uint64_t source_sequence,
    uint32_t pcm_sample_count,
    const uint8_t *codec_payload,
    size_t codec_payload_size
)
{
    gumi_recording_journal_writer next_writer;
    gumi_recording_journal_plan plan;
    uint8_t protected_payload[GUMI_RECORDING_JOURNAL_MAX_PROTECTED_PAYLOAD_BYTES];
    uint8_t encoded_record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    size_t protected_size = 0U;
    size_t encoded_size = 0U;
    uint32_t pending;
    gumi_recording_journal_status journal_status;
    gumi_recording_store_status status;

    if (store == NULL || codec_payload == NULL) {
        return GUMI_RECORDING_STORE_STATUS_INVALID_ARGUMENT;
    }
    if (!store->initialized || store->phase != GUMI_RECORDING_STORE_PHASE_ACTIVE ||
        !store->file_open) {
        return GUMI_RECORDING_STORE_STATUS_INVALID_STATE;
    }
    if (codec_payload_size == 0U ||
        codec_payload_size > GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES ||
        pcm_sample_count == 0U) {
        return GUMI_RECORDING_STORE_STATUS_INVALID_CONFIGURATION;
    }

    journal_status = gumi_recording_journal_plan_audio(
        &store->writer,
        source_sequence,
        pcm_sample_count,
        (uint32_t)codec_payload_size,
        &plan
    );
    if (journal_status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        return GUMI_RECORDING_STORE_STATUS_JOURNAL_FAILURE;
    }
    if (store->io->protect(
            store->io_context,
            &plan,
            codec_payload,
            codec_payload_size,
            protected_payload,
            sizeof(protected_payload),
            &protected_size
        ) != 0 ||
        protected_size != plan.protected_size) {
        fail_store(store);
        return GUMI_RECORDING_STORE_STATUS_CRYPTO_FAILURE;
    }

    next_writer = store->writer;
    journal_status = gumi_recording_journal_commit_audio(
        &next_writer,
        &plan,
        protected_payload,
        protected_size,
        encoded_record,
        sizeof(encoded_record),
        &encoded_size
    );
    if (journal_status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        fail_store(store);
        return GUMI_RECORDING_STORE_STATUS_JOURNAL_FAILURE;
    }
    status = exact_append(store, encoded_record, encoded_size);
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        return status;
    }
    store->writer = next_writer;
    pending = store->records_since_sync + 1U;
    store->records_since_sync = pending;
    if (pending >= store->sync_every_audio_records) {
        status = sync_open_file(store);
        if (status != GUMI_RECORDING_STORE_STATUS_OK) {
            return status;
        }
    }
    return GUMI_RECORDING_STORE_STATUS_OK;
}

gumi_recording_store_status gumi_recording_store_finalize(
    gumi_recording_store *store
)
{
    gumi_recording_journal_writer next_writer;
    gumi_recording_journal_plan plan;
    uint8_t protected_payload[GUMI_RECORDING_JOURNAL_MAX_PROTECTED_PAYLOAD_BYTES];
    uint8_t encoded_record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    size_t protected_size = 0U;
    size_t encoded_size = 0U;
    bool destination_exists = false;
    gumi_recording_journal_status journal_status;
    gumi_recording_store_status status;

    if (store == NULL) {
        return GUMI_RECORDING_STORE_STATUS_INVALID_ARGUMENT;
    }
    if (!store->initialized || store->phase != GUMI_RECORDING_STORE_PHASE_ACTIVE ||
        !store->file_open) {
        return GUMI_RECORDING_STORE_STATUS_INVALID_STATE;
    }

    journal_status = gumi_recording_journal_plan_finalize(&store->writer, &plan);
    if (journal_status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        fail_store(store);
        return GUMI_RECORDING_STORE_STATUS_JOURNAL_FAILURE;
    }
    if (store->io->protect(
            store->io_context,
            &plan,
            plan.commit_plaintext,
            plan.plaintext_size,
            protected_payload,
            sizeof(protected_payload),
            &protected_size
        ) != 0 ||
        protected_size != plan.protected_size) {
        fail_store(store);
        return GUMI_RECORDING_STORE_STATUS_CRYPTO_FAILURE;
    }

    next_writer = store->writer;
    journal_status = gumi_recording_journal_commit_finalize(
        &next_writer,
        &plan,
        protected_payload,
        protected_size,
        encoded_record,
        sizeof(encoded_record),
        &encoded_size
    );
    if (journal_status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        fail_store(store);
        return GUMI_RECORDING_STORE_STATUS_JOURNAL_FAILURE;
    }
    status = exact_append(store, encoded_record, encoded_size);
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        return status;
    }
    store->writer = next_writer;
    status = sync_open_file(store);
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        best_effort_close(store);
        return status;
    }
    status = map_io(store->io->close(store->io_context));
    store->file_open = false;
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        fail_store(store);
        return status;
    }
    status = map_io(store->io->exists(
        store->io_context,
        store->committed_name,
        &destination_exists
    ));
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        fail_store(store);
        return status;
    }
    if (destination_exists) {
        fail_store(store);
        return GUMI_RECORDING_STORE_STATUS_NAME_COLLISION;
    }
    status = map_io(store->io->rename_no_replace(
        store->io_context,
        store->partial_name,
        store->committed_name
    ));
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        fail_store(store);
        return status;
    }
    status = map_io(store->io->sync_volume(store->io_context));
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        fail_store(store);
        return status;
    }
    store->phase = GUMI_RECORDING_STORE_PHASE_COMMITTED;
    return GUMI_RECORDING_STORE_STATUS_OK;
}

gumi_recording_store_status gumi_recording_store_interrupt(
    gumi_recording_store *store
)
{
    gumi_recording_store_status first_failure = GUMI_RECORDING_STORE_STATUS_OK;
    gumi_recording_store_status status;

    if (store == NULL) {
        return GUMI_RECORDING_STORE_STATUS_INVALID_ARGUMENT;
    }
    if (!store->initialized ||
        (store->phase != GUMI_RECORDING_STORE_PHASE_ACTIVE &&
         store->phase != GUMI_RECORDING_STORE_PHASE_FAILED) ||
        !store->file_open) {
        return GUMI_RECORDING_STORE_STATUS_INVALID_STATE;
    }

    status = map_io(store->io->sync_file(store->io_context));
    if (status != GUMI_RECORDING_STORE_STATUS_OK) {
        first_failure = status;
    }
    status = map_io(store->io->sync_volume(store->io_context));
    if (first_failure == GUMI_RECORDING_STORE_STATUS_OK &&
        status != GUMI_RECORDING_STORE_STATUS_OK) {
        first_failure = status;
    }
    status = map_io(store->io->close(store->io_context));
    store->file_open = false;
    if (first_failure == GUMI_RECORDING_STORE_STATUS_OK &&
        status != GUMI_RECORDING_STORE_STATUS_OK) {
        first_failure = status;
    }
    store->phase = first_failure == GUMI_RECORDING_STORE_STATUS_OK
        ? GUMI_RECORDING_STORE_PHASE_INTERRUPTED
        : GUMI_RECORDING_STORE_PHASE_FAILED;
    return first_failure;
}
