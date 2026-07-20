#include "gumi/recording_journal.h"

#include <limits.h>
#include <string.h>

#define GUMI_JOURNAL_FORMAT_VERSION 1U
#define GUMI_JOURNAL_RECORD_VERSION 1U

static const uint8_t file_magic[8] = {'G', 'U', 'M', 'I', 'J', 'N', 'L', '1'};
static const uint8_t record_magic[4] = {'G', 'M', 'R', '1'};

static void put_u16_le(uint8_t *output, uint16_t value)
{
    output[0] = (uint8_t)(value & UINT16_C(0x00ff));
    output[1] = (uint8_t)((value >> 8U) & UINT16_C(0x00ff));
}

static void put_u32_le(uint8_t *output, uint32_t value)
{
    output[0] = (uint8_t)(value & UINT32_C(0x000000ff));
    output[1] = (uint8_t)((value >> 8U) & UINT32_C(0x000000ff));
    output[2] = (uint8_t)((value >> 16U) & UINT32_C(0x000000ff));
    output[3] = (uint8_t)((value >> 24U) & UINT32_C(0x000000ff));
}

static void put_u64_le(uint8_t *output, uint64_t value)
{
    unsigned int index;

    for (index = 0U; index < 8U; index += 1U) {
        output[index] = (uint8_t)((value >> (index * 8U)) & UINT64_C(0xff));
    }
}

static uint16_t get_u16_le(const uint8_t *input)
{
    return (uint16_t)((uint16_t)input[0] | ((uint16_t)input[1] << 8U));
}

static uint32_t get_u32_le(const uint8_t *input)
{
    return (uint32_t)input[0] |
           ((uint32_t)input[1] << 8U) |
           ((uint32_t)input[2] << 16U) |
           ((uint32_t)input[3] << 24U);
}

static uint64_t get_u64_le(const uint8_t *input)
{
    uint64_t value = UINT64_C(0);
    unsigned int index;

    for (index = 0U; index < 8U; index += 1U) {
        value |= (uint64_t)input[index] << (index * 8U);
    }
    return value;
}

static uint32_t crc32c_extend(uint32_t previous, const uint8_t *bytes, size_t size)
{
    uint32_t crc = ~previous;
    size_t index;
    unsigned int bit;

    for (index = 0U; index < size; index += 1U) {
        crc ^= bytes[index];
        for (bit = 0U; bit < 8U; bit += 1U) {
            const uint32_t mask = (uint32_t)(0U - (crc & UINT32_C(1)));
            crc = (crc >> 1U) ^ (UINT32_C(0x82f63b78) & mask);
        }
    }
    return ~crc;
}

uint32_t gumi_recording_journal_crc32c(const uint8_t *bytes, size_t size)
{
    if (bytes == NULL && size != 0U) {
        return UINT32_C(0);
    }
    return crc32c_extend(UINT32_C(0), bytes, size);
}

static bool nonce_is_nonzero(const uint8_t *nonce)
{
    size_t index;
    uint8_t combined = 0U;

    for (index = 0U; index < GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES; index += 1U) {
        combined = (uint8_t)(combined | nonce[index]);
    }
    return combined != 0U;
}

static bool config_is_valid(const gumi_recording_journal_config *config)
{
    return config != NULL &&
           config->session_id != UINT64_C(0) &&
           config->recording_id != UINT64_C(0) &&
           config->sample_rate != UINT32_C(0) &&
           config->frame_samples != UINT32_C(0) &&
           config->max_codec_payload_bytes != UINT32_C(0) &&
           config->max_codec_payload_bytes <= GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES &&
           config->key_id != UINT32_C(0) &&
           config->codec == GUMI_RECORDING_JOURNAL_CODEC_OPUS &&
           config->protection == GUMI_RECORDING_JOURNAL_PROTECTION_AES_256_GCM_V1 &&
           nonce_is_nonzero(config->nonce_base);
}

static void encode_file_prefix(
    const gumi_recording_journal_config *config,
    uint8_t prefix[60]
)
{
    memset(prefix, 0, 60U);
    memcpy(prefix, file_magic, sizeof(file_magic));
    put_u16_le(&prefix[8], GUMI_JOURNAL_FORMAT_VERSION);
    put_u16_le(&prefix[10], GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES);
    put_u16_le(&prefix[12], (uint16_t)config->codec);
    put_u16_le(&prefix[14], (uint16_t)config->protection);
    put_u64_le(&prefix[16], config->session_id);
    put_u64_le(&prefix[24], config->recording_id);
    put_u32_le(&prefix[32], config->sample_rate);
    put_u32_le(&prefix[36], config->frame_samples);
    put_u16_le(&prefix[40], (uint16_t)config->max_codec_payload_bytes);
    put_u16_le(&prefix[42], GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES);
    put_u32_le(&prefix[44], config->key_id);
    memcpy(&prefix[48], config->nonce_base, GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES);
}

static void encode_file_header(
    const gumi_recording_journal_config *config,
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES]
)
{
    encode_file_prefix(config, header);
    put_u32_le(&header[60], gumi_recording_journal_crc32c(header, 60U));
}

static gumi_recording_journal_status decode_file_header(
    const uint8_t *header,
    size_t size,
    gumi_recording_journal_config *config
)
{
    gumi_recording_journal_config decoded;

    if (header == NULL || config == NULL) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT;
    }
    if (size < GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES) {
        return GUMI_RECORDING_JOURNAL_STATUS_TRUNCATED;
    }
    if (size > GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES) {
        return GUMI_RECORDING_JOURNAL_STATUS_TRAILING_DATA;
    }
    if (memcmp(header, file_magic, sizeof(file_magic)) != 0) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }
    if (get_u16_le(&header[8]) != GUMI_JOURNAL_FORMAT_VERSION) {
        return GUMI_RECORDING_JOURNAL_STATUS_UNSUPPORTED_VERSION;
    }
    if (get_u16_le(&header[10]) != GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES ||
        get_u16_le(&header[42]) != GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES ||
        get_u32_le(&header[60]) != gumi_recording_journal_crc32c(header, 60U)) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }

    memset(&decoded, 0, sizeof(decoded));
    decoded.codec = (gumi_recording_journal_codec)get_u16_le(&header[12]);
    decoded.protection = (gumi_recording_journal_protection)get_u16_le(&header[14]);
    decoded.session_id = get_u64_le(&header[16]);
    decoded.recording_id = get_u64_le(&header[24]);
    decoded.sample_rate = get_u32_le(&header[32]);
    decoded.frame_samples = get_u32_le(&header[36]);
    decoded.max_codec_payload_bytes = get_u16_le(&header[40]);
    decoded.key_id = get_u32_le(&header[44]);
    memcpy(decoded.nonce_base, &header[48], GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES);
    if (!config_is_valid(&decoded)) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_CONFIGURATION;
    }
    *config = decoded;
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}

static void derive_nonce(
    const uint8_t base[GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES],
    uint64_t ordinal,
    uint8_t nonce[GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES]
)
{
    unsigned int index;

    memcpy(nonce, base, GUMI_RECORDING_JOURNAL_AES_GCM_NONCE_BYTES);
    for (index = 0U; index < 8U; index += 1U) {
        nonce[4U + index] = (uint8_t)(
            nonce[4U + index] ^
            (uint8_t)((ordinal >> ((7U - index) * 8U)) & UINT64_C(0xff))
        );
    }
}

static void encode_record_prefix(
    const gumi_recording_journal_plan *plan,
    uint8_t prefix[40]
)
{
    memset(prefix, 0, 40U);
    memcpy(prefix, record_magic, sizeof(record_magic));
    prefix[4] = GUMI_JOURNAL_RECORD_VERSION;
    prefix[5] = (uint8_t)plan->kind;
    put_u16_le(&prefix[6], GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES);
    put_u64_le(&prefix[8], plan->session_id);
    put_u64_le(&prefix[16], plan->ordinal);
    put_u64_le(&prefix[24], plan->source_sequence);
    put_u32_le(&prefix[32], plan->pcm_sample_count);
    put_u32_le(&prefix[36], plan->protected_size);
}

static void build_plan(
    const gumi_recording_journal_config *config,
    gumi_recording_journal_record_kind kind,
    uint64_t ordinal,
    uint64_t source_sequence,
    uint32_t pcm_sample_count,
    uint32_t plaintext_size,
    gumi_recording_journal_plan *plan
)
{
    uint8_t record_prefix[40];

    memset(plan, 0, sizeof(*plan));
    plan->session_id = config->session_id;
    plan->ordinal = ordinal;
    plan->source_sequence = source_sequence;
    plan->pcm_sample_count = pcm_sample_count;
    plan->plaintext_size = plaintext_size;
    plan->protected_size = plaintext_size + GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES;
    plan->kind = kind;
    derive_nonce(config->nonce_base, ordinal, plan->nonce);
    encode_file_prefix(config, plan->aad);
    encode_record_prefix(plan, record_prefix);
    memcpy(&plan->aad[60], record_prefix, sizeof(record_prefix));
}

static bool plan_matches(
    const gumi_recording_journal_plan *left,
    const gumi_recording_journal_plan *right
)
{
    return left->session_id == right->session_id &&
           left->ordinal == right->ordinal &&
           left->source_sequence == right->source_sequence &&
           left->pcm_sample_count == right->pcm_sample_count &&
           left->plaintext_size == right->plaintext_size &&
           left->protected_size == right->protected_size &&
           left->kind == right->kind &&
           memcmp(left->nonce, right->nonce, sizeof(left->nonce)) == 0 &&
           memcmp(left->aad, right->aad, sizeof(left->aad)) == 0 &&
           memcmp(
               left->commit_plaintext,
               right->commit_plaintext,
               sizeof(left->commit_plaintext)
           ) == 0;
}

static void encode_record_header(
    const gumi_recording_journal_plan *plan,
    uint32_t payload_crc,
    uint8_t header[GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES]
)
{
    encode_record_prefix(plan, header);
    put_u32_le(&header[40], payload_crc);
    put_u32_le(&header[44], gumi_recording_journal_crc32c(header, 44U));
}

static gumi_recording_journal_status encode_protected_record(
    const gumi_recording_journal_plan *plan,
    const uint8_t *protected_payload,
    size_t protected_size,
    uint8_t *encoded_record,
    size_t capacity,
    size_t *encoded_size
)
{
    size_t required;
    uint32_t payload_crc;

    if (plan == NULL || protected_payload == NULL || encoded_record == NULL || encoded_size == NULL) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT;
    }
    if (protected_size != plan->protected_size) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_CONFIGURATION;
    }
    required = GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES + protected_size;
    if (capacity < required) {
        return GUMI_RECORDING_JOURNAL_STATUS_OUTPUT_TOO_SMALL;
    }

    memmove(
        &encoded_record[GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES],
        protected_payload,
        protected_size
    );
    payload_crc = gumi_recording_journal_crc32c(
        &encoded_record[GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES],
        protected_size
    );
    encode_record_header(plan, payload_crc, encoded_record);
    *encoded_size = required;
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}

gumi_recording_journal_status gumi_recording_journal_writer_begin(
    gumi_recording_journal_writer *writer,
    const gumi_recording_journal_config *config,
    uint8_t *encoded_header,
    size_t capacity,
    size_t *encoded_size
)
{
    gumi_recording_journal_writer next;

    if (writer == NULL || config == NULL || encoded_header == NULL || encoded_size == NULL) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT;
    }
    if (!config_is_valid(config)) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_CONFIGURATION;
    }
    if (capacity < GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES) {
        return GUMI_RECORDING_JOURNAL_STATUS_OUTPUT_TOO_SMALL;
    }

    memset(&next, 0, sizeof(next));
    next.config = *config;
    next.next_ordinal = UINT64_C(1);
    next.next_source_sequence = UINT64_C(1);
    next.initialized = true;
    encode_file_header(config, encoded_header);
    *encoded_size = GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES;
    *writer = next;
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}

gumi_recording_journal_status gumi_recording_journal_plan_audio(
    const gumi_recording_journal_writer *writer,
    uint64_t source_sequence,
    uint32_t pcm_sample_count,
    uint32_t codec_payload_size,
    gumi_recording_journal_plan *plan
)
{
    if (writer == NULL || plan == NULL) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT;
    }
    if (!writer->initialized || writer->finalized) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_STATE;
    }
    if (!config_is_valid(&writer->config)) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_CONFIGURATION;
    }
    if (source_sequence != writer->next_source_sequence) {
        return GUMI_RECORDING_JOURNAL_STATUS_SEQUENCE_GAP;
    }
    if (pcm_sample_count != writer->config.frame_samples || codec_payload_size == UINT32_C(0) ||
        codec_payload_size > writer->config.max_codec_payload_bytes) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_CONFIGURATION;
    }
    if (writer->next_ordinal == UINT64_MAX || writer->next_source_sequence == UINT64_MAX ||
        writer->audio_record_count == UINT64_MAX ||
        writer->total_pcm_samples > UINT64_MAX - pcm_sample_count) {
        return GUMI_RECORDING_JOURNAL_STATUS_COUNTER_EXHAUSTED;
    }

    build_plan(
        &writer->config,
        GUMI_RECORDING_JOURNAL_RECORD_AUDIO,
        writer->next_ordinal,
        source_sequence,
        pcm_sample_count,
        codec_payload_size,
        plan
    );
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}

gumi_recording_journal_status gumi_recording_journal_commit_audio(
    gumi_recording_journal_writer *writer,
    const gumi_recording_journal_plan *plan,
    const uint8_t *protected_payload,
    size_t protected_size,
    uint8_t *encoded_record,
    size_t capacity,
    size_t *encoded_size
)
{
    gumi_recording_journal_plan expected;
    gumi_recording_journal_status status;

    if (writer == NULL || plan == NULL) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT;
    }
    status = gumi_recording_journal_plan_audio(
        writer,
        plan->source_sequence,
        plan->pcm_sample_count,
        plan->plaintext_size,
        &expected
    );
    if (status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        return status;
    }
    if (!plan_matches(plan, &expected)) {
        return GUMI_RECORDING_JOURNAL_STATUS_STALE_PLAN;
    }
    status = encode_protected_record(
        &expected,
        protected_payload,
        protected_size,
        encoded_record,
        capacity,
        encoded_size
    );
    if (status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        return status;
    }

    writer->protected_chain_crc32c = crc32c_extend(
        writer->protected_chain_crc32c,
        encoded_record,
        *encoded_size
    );
    writer->next_ordinal += UINT64_C(1);
    writer->next_source_sequence += UINT64_C(1);
    writer->audio_record_count += UINT64_C(1);
    writer->total_pcm_samples += plan->pcm_sample_count;
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}

gumi_recording_journal_status gumi_recording_journal_plan_finalize(
    const gumi_recording_journal_writer *writer,
    gumi_recording_journal_plan *plan
)
{
    uint64_t final_source_sequence;

    if (writer == NULL || plan == NULL) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT;
    }
    if (!writer->initialized || writer->finalized) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_STATE;
    }
    if (!config_is_valid(&writer->config)) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_CONFIGURATION;
    }
    if (writer->next_ordinal == UINT64_C(0)) {
        return GUMI_RECORDING_JOURNAL_STATUS_COUNTER_EXHAUSTED;
    }

    final_source_sequence = writer->audio_record_count == UINT64_C(0)
        ? UINT64_C(0)
        : writer->next_source_sequence - UINT64_C(1);
    build_plan(
        &writer->config,
        GUMI_RECORDING_JOURNAL_RECORD_COMMIT,
        writer->next_ordinal,
        final_source_sequence,
        UINT32_C(0),
        GUMI_RECORDING_JOURNAL_COMMIT_PLAINTEXT_BYTES,
        plan
    );
    put_u64_le(&plan->commit_plaintext[0], writer->audio_record_count);
    put_u64_le(&plan->commit_plaintext[8], writer->total_pcm_samples);
    put_u64_le(&plan->commit_plaintext[16], final_source_sequence);
    put_u32_le(&plan->commit_plaintext[24], writer->protected_chain_crc32c);
    put_u32_le(&plan->commit_plaintext[28], UINT32_C(0));
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}

gumi_recording_journal_status gumi_recording_journal_commit_finalize(
    gumi_recording_journal_writer *writer,
    const gumi_recording_journal_plan *plan,
    const uint8_t *protected_payload,
    size_t protected_size,
    uint8_t *encoded_record,
    size_t capacity,
    size_t *encoded_size
)
{
    gumi_recording_journal_plan expected;
    gumi_recording_journal_status status;

    if (writer == NULL || plan == NULL) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT;
    }
    status = gumi_recording_journal_plan_finalize(writer, &expected);
    if (status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        return status;
    }
    if (!plan_matches(plan, &expected)) {
        return GUMI_RECORDING_JOURNAL_STATUS_STALE_PLAN;
    }
    status = encode_protected_record(
        &expected,
        protected_payload,
        protected_size,
        encoded_record,
        capacity,
        encoded_size
    );
    if (status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        return status;
    }
    writer->finalized = true;
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}

gumi_recording_journal_status gumi_recording_journal_recovery_init(
    gumi_recording_journal_recovery *recovery,
    const uint8_t *encoded_header,
    size_t encoded_size
)
{
    gumi_recording_journal_recovery next;
    gumi_recording_journal_status status;

    if (recovery == NULL) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT;
    }
    memset(&next, 0, sizeof(next));
    status = decode_file_header(encoded_header, encoded_size, &next.config);
    if (status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        return status;
    }
    next.next_ordinal = UINT64_C(1);
    next.next_source_sequence = UINT64_C(1);
    next.valid_prefix_bytes = GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES;
    next.initialized = true;
    *recovery = next;
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}

static gumi_recording_journal_status parse_record(
    const uint8_t *encoded_record,
    size_t encoded_size,
    gumi_recording_journal_plan *decoded_plan,
    const uint8_t **protected_payload
)
{
    uint32_t protected_size;
    size_t required;

    if (encoded_record == NULL || decoded_plan == NULL || protected_payload == NULL) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT;
    }
    if (encoded_size < GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES) {
        return GUMI_RECORDING_JOURNAL_STATUS_TRUNCATED;
    }
    if (memcmp(encoded_record, record_magic, sizeof(record_magic)) != 0) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }
    if (encoded_record[4] != GUMI_JOURNAL_RECORD_VERSION) {
        return GUMI_RECORDING_JOURNAL_STATUS_UNSUPPORTED_VERSION;
    }
    if (get_u16_le(&encoded_record[6]) != GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }
    protected_size = get_u32_le(&encoded_record[36]);
    if (protected_size > GUMI_RECORDING_JOURNAL_MAX_PROTECTED_PAYLOAD_BYTES) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }
    required = GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES + (size_t)protected_size;
    if (encoded_size < required) {
        return GUMI_RECORDING_JOURNAL_STATUS_TRUNCATED;
    }
    if (encoded_size > required) {
        return GUMI_RECORDING_JOURNAL_STATUS_TRAILING_DATA;
    }
    if (get_u32_le(&encoded_record[44]) !=
        gumi_recording_journal_crc32c(encoded_record, 44U)) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }
    if (get_u32_le(&encoded_record[40]) != gumi_recording_journal_crc32c(
            &encoded_record[GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES],
            protected_size
        )) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }

    memset(decoded_plan, 0, sizeof(*decoded_plan));
    decoded_plan->kind = (gumi_recording_journal_record_kind)encoded_record[5];
    decoded_plan->session_id = get_u64_le(&encoded_record[8]);
    decoded_plan->ordinal = get_u64_le(&encoded_record[16]);
    decoded_plan->source_sequence = get_u64_le(&encoded_record[24]);
    decoded_plan->pcm_sample_count = get_u32_le(&encoded_record[32]);
    decoded_plan->protected_size = protected_size;
    if (protected_size < GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }
    decoded_plan->plaintext_size =
        protected_size - GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES;
    *protected_payload = &encoded_record[GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES];
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}

static gumi_recording_journal_status expected_recovery_plan(
    const gumi_recording_journal_recovery *recovery,
    const gumi_recording_journal_plan *decoded,
    gumi_recording_journal_plan *expected
)
{
    uint64_t final_source_sequence;

    if (decoded->session_id != recovery->config.session_id ||
        decoded->ordinal != recovery->next_ordinal) {
        return GUMI_RECORDING_JOURNAL_STATUS_SEQUENCE_GAP;
    }
    if (decoded->kind == GUMI_RECORDING_JOURNAL_RECORD_AUDIO) {
        if (recovery->next_ordinal == UINT64_MAX ||
            recovery->next_source_sequence == UINT64_MAX ||
            recovery->audio_record_count == UINT64_MAX ||
            recovery->total_pcm_samples > UINT64_MAX - decoded->pcm_sample_count) {
            return GUMI_RECORDING_JOURNAL_STATUS_COUNTER_EXHAUSTED;
        }
        if (decoded->source_sequence != recovery->next_source_sequence) {
            return GUMI_RECORDING_JOURNAL_STATUS_SEQUENCE_GAP;
        }
        if (decoded->pcm_sample_count != recovery->config.frame_samples ||
            decoded->plaintext_size == UINT32_C(0) ||
            decoded->plaintext_size > recovery->config.max_codec_payload_bytes) {
            return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
        }
        build_plan(
            &recovery->config,
            GUMI_RECORDING_JOURNAL_RECORD_AUDIO,
            recovery->next_ordinal,
            recovery->next_source_sequence,
            decoded->pcm_sample_count,
            decoded->plaintext_size,
            expected
        );
        return GUMI_RECORDING_JOURNAL_STATUS_OK;
    }
    if (decoded->kind != GUMI_RECORDING_JOURNAL_RECORD_COMMIT) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }
    final_source_sequence = recovery->audio_record_count == UINT64_C(0)
        ? UINT64_C(0)
        : recovery->next_source_sequence - UINT64_C(1);
    if (decoded->source_sequence != final_source_sequence ||
        decoded->pcm_sample_count != UINT32_C(0) ||
        decoded->plaintext_size != GUMI_RECORDING_JOURNAL_COMMIT_PLAINTEXT_BYTES) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }
    build_plan(
        &recovery->config,
        GUMI_RECORDING_JOURNAL_RECORD_COMMIT,
        recovery->next_ordinal,
        final_source_sequence,
        UINT32_C(0),
        GUMI_RECORDING_JOURNAL_COMMIT_PLAINTEXT_BYTES,
        expected
    );
    put_u64_le(&expected->commit_plaintext[0], recovery->audio_record_count);
    put_u64_le(&expected->commit_plaintext[8], recovery->total_pcm_samples);
    put_u64_le(&expected->commit_plaintext[16], final_source_sequence);
    put_u32_le(&expected->commit_plaintext[24], recovery->protected_chain_crc32c);
    put_u32_le(&expected->commit_plaintext[28], UINT32_C(0));
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}

gumi_recording_journal_status gumi_recording_journal_recovery_inspect_next(
    const gumi_recording_journal_recovery *recovery,
    const uint8_t *encoded_record,
    size_t encoded_size,
    gumi_recording_journal_record_view *view
)
{
    gumi_recording_journal_plan decoded;
    gumi_recording_journal_plan expected;
    const uint8_t *protected_payload;
    gumi_recording_journal_status status;

    if (recovery == NULL || view == NULL) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT;
    }
    if (!recovery->initialized || recovery->committed ||
        !config_is_valid(&recovery->config)) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_STATE;
    }
    status = parse_record(encoded_record, encoded_size, &decoded, &protected_payload);
    if (status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        return status;
    }
    status = expected_recovery_plan(recovery, &decoded, &expected);
    if (status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        return status;
    }
    if (memcmp(encoded_record, &expected.aad[60], 40U) != 0) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }
    memset(view, 0, sizeof(*view));
    view->plan = expected;
    view->protected_payload = protected_payload;
    view->encoded_size = encoded_size;
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}

gumi_recording_journal_status gumi_recording_journal_recovery_accept_next(
    gumi_recording_journal_recovery *recovery,
    const uint8_t *encoded_record,
    size_t encoded_size,
    const uint8_t *authenticated_plaintext,
    size_t plaintext_size
)
{
    gumi_recording_journal_record_view view;
    gumi_recording_journal_status status;
    uint64_t next_valid_prefix;

    if (recovery == NULL || authenticated_plaintext == NULL) {
        return GUMI_RECORDING_JOURNAL_STATUS_INVALID_ARGUMENT;
    }
    status = gumi_recording_journal_recovery_inspect_next(
        recovery,
        encoded_record,
        encoded_size,
        &view
    );
    if (status != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        return status;
    }
    if (plaintext_size != view.plan.plaintext_size) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }
    if (recovery->valid_prefix_bytes > UINT64_MAX - encoded_size) {
        return GUMI_RECORDING_JOURNAL_STATUS_COUNTER_EXHAUSTED;
    }
    next_valid_prefix = recovery->valid_prefix_bytes + encoded_size;

    if (view.plan.kind == GUMI_RECORDING_JOURNAL_RECORD_AUDIO) {
        recovery->protected_chain_crc32c = crc32c_extend(
            recovery->protected_chain_crc32c,
            encoded_record,
            encoded_size
        );
        recovery->next_ordinal += UINT64_C(1);
        recovery->next_source_sequence += UINT64_C(1);
        recovery->audio_record_count += UINT64_C(1);
        recovery->total_pcm_samples += view.plan.pcm_sample_count;
        recovery->valid_prefix_bytes = next_valid_prefix;
        return GUMI_RECORDING_JOURNAL_STATUS_OK;
    }

    if (get_u64_le(&authenticated_plaintext[0]) != recovery->audio_record_count ||
        get_u64_le(&authenticated_plaintext[8]) != recovery->total_pcm_samples ||
        get_u64_le(&authenticated_plaintext[16]) != view.plan.source_sequence ||
        get_u32_le(&authenticated_plaintext[24]) != recovery->protected_chain_crc32c ||
        get_u32_le(&authenticated_plaintext[28]) != UINT32_C(0)) {
        return GUMI_RECORDING_JOURNAL_STATUS_CORRUPT;
    }
    recovery->valid_prefix_bytes = next_valid_prefix;
    recovery->committed = true;
    return GUMI_RECORDING_JOURNAL_STATUS_OK;
}
