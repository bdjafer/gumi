#include "gumi/omi_v3012_codec.h"

#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/kernel.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/ring_buffer.h>

#include "opus.h"

#define GUMI_CODEC_PCM_CAPACITY_SAMPLES 16000U
#define GUMI_CODEC_PCM_CAPACITY_BYTES \
    (GUMI_CODEC_PCM_CAPACITY_SAMPLES * sizeof(int16_t))
#define GUMI_CODEC_FRAME_BYTES \
    (GUMI_OMI_V3012_CODEC_FRAME_SAMPLES * sizeof(int16_t))
#define GUMI_CODEC_OPUS_STORAGE_BYTES 12000U
#define GUMI_CODEC_BITRATE 32000
#define GUMI_CODEC_COMPLEXITY 3
#define GUMI_CODEC_JOIN_TIMEOUT_MS 1000
#define GUMI_CODEC_THREAD_STACK_SIZE 32000
#define GUMI_CODEC_THREAD_PRIORITY 4

K_THREAD_STACK_DEFINE(gumi_codec_thread_stack, GUMI_CODEC_THREAD_STACK_SIZE);
K_MUTEX_DEFINE(gumi_codec_lifecycle_lock);
K_SEM_DEFINE(gumi_codec_work, 0, 1);

static struct k_thread gumi_codec_thread;
static struct ring_buf gumi_codec_ring;
static uint8_t gumi_codec_ring_storage[GUMI_CODEC_PCM_CAPACITY_BYTES];
static int16_t gumi_codec_input[GUMI_OMI_V3012_CODEC_FRAME_SAMPLES];
static uint8_t gumi_codec_output[GUMI_OMI_V3012_CODEC_MAX_PACKET_BYTES];
__aligned(4) static uint8_t gumi_opus_storage[GUMI_CODEC_OPUS_STORAGE_BYTES];
static OpusEncoder *const gumi_opus = (OpusEncoder *)gumi_opus_storage;

static atomic_t gumi_codec_truth = ATOMIC_INIT(GUMI_OMI_V3012_CODEC_UNINITIALIZED);
static atomic_t gumi_accepting_pcm = ATOMIC_INIT(0);
static atomic_t gumi_run_requested = ATOMIC_INIT(0);
static atomic_t gumi_drain_requested = ATOMIC_INIT(0);
static atomic_t gumi_deliver_output = ATOMIC_INIT(0);
static atomic_t gumi_terminal_error = ATOMIC_INIT(0);
static atomic_t gumi_fault_reported = ATOMIC_INIT(0);

static gumi_omi_v3012_codec_packet_handler gumi_packet_handler;
static gumi_omi_v3012_codec_fault_handler gumi_fault_handler;
static void *gumi_handler_context;
static uint64_t gumi_session_id;
static uint64_t gumi_next_packet_sequence;
static uint64_t gumi_submitted_samples;
static uint64_t gumi_emitted_packets;
static uint64_t gumi_emitted_samples;
static uint64_t gumi_discarded_samples;
static bool gumi_thread_created;

static void set_truth(gumi_omi_v3012_codec_truth truth)
{
    atomic_set(&gumi_codec_truth, (atomic_val_t)truth);
}

static void report_fault_once(int error)
{
    if (gumi_fault_handler != NULL && atomic_cas(&gumi_fault_reported, 0, 1)) {
        gumi_fault_handler(gumi_session_id, error, gumi_handler_context);
    }
}

static void latch_fault(int error)
{
    if (error >= 0) {
        error = -EIO;
    }
    (void)atomic_cas(&gumi_terminal_error, 0, error);
    atomic_set(&gumi_accepting_pcm, 0);
    atomic_set(&gumi_deliver_output, 0);
    atomic_set(&gumi_drain_requested, 0);
    atomic_set(&gumi_run_requested, 0);
    set_truth(GUMI_OMI_V3012_CODEC_FAULTED);
    k_sem_give(&gumi_codec_work);
    report_fault_once(error);
}

static int apply_encoder_configuration(void)
{
    int required_size = opus_encoder_get_size(1);
    int error;

    if (required_size <= 0 || (size_t)required_size > sizeof(gumi_opus_storage)) {
        return -ENOMEM;
    }
    memset(gumi_opus_storage, 0, sizeof(gumi_opus_storage));
    error = opus_encoder_init(
        gumi_opus,
        GUMI_OMI_V3012_CODEC_SAMPLE_RATE,
        1,
        OPUS_APPLICATION_RESTRICTED_LOWDELAY
    );
    if (error != OPUS_OK) return -EIO;
    if (opus_encoder_ctl(gumi_opus, OPUS_SET_BITRATE(GUMI_CODEC_BITRATE)) != OPUS_OK) return -EIO;
    if (opus_encoder_ctl(gumi_opus, OPUS_SET_VBR(1)) != OPUS_OK) return -EIO;
    if (opus_encoder_ctl(gumi_opus, OPUS_SET_VBR_CONSTRAINT(0)) != OPUS_OK) return -EIO;
    if (opus_encoder_ctl(gumi_opus, OPUS_SET_COMPLEXITY(GUMI_CODEC_COMPLEXITY)) != OPUS_OK) return -EIO;
    if (opus_encoder_ctl(gumi_opus, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)) != OPUS_OK) return -EIO;
    if (opus_encoder_ctl(gumi_opus, OPUS_SET_LSB_DEPTH(16)) != OPUS_OK) return -EIO;
    if (opus_encoder_ctl(gumi_opus, OPUS_SET_DTX(0)) != OPUS_OK) return -EIO;
    if (opus_encoder_ctl(gumi_opus, OPUS_SET_INBAND_FEC(0)) != OPUS_OK) return -EIO;
    if (opus_encoder_ctl(gumi_opus, OPUS_SET_PACKET_LOSS_PERC(0)) != OPUS_OK) return -EIO;
    return 0;
}

static bool add_without_overflow(uint64_t *value, uint64_t amount)
{
    if (*value > UINT64_MAX - amount) {
        return false;
    }
    *value += amount;
    return true;
}

static void encode_one_frame(void)
{
    gumi_omi_v3012_codec_packet packet;
    uint32_t read;
    int encoded_size;

    read = ring_buf_get(&gumi_codec_ring, (uint8_t *)gumi_codec_input, GUMI_CODEC_FRAME_BYTES);
    if (read != GUMI_CODEC_FRAME_BYTES) {
        if (!add_without_overflow(&gumi_discarded_samples, read / sizeof(int16_t))) {
            latch_fault(-EOVERFLOW);
        } else {
            latch_fault(-EIO);
        }
        return;
    }

    encoded_size = opus_encode(
        gumi_opus,
        gumi_codec_input,
        GUMI_OMI_V3012_CODEC_FRAME_SAMPLES,
        gumi_codec_output,
        sizeof(gumi_codec_output)
    );
    if (encoded_size <= 0) {
        (void)add_without_overflow(
            &gumi_discarded_samples,
            GUMI_OMI_V3012_CODEC_FRAME_SAMPLES
        );
        latch_fault(encoded_size < 0 ? -EIO : -ENODATA);
        return;
    }
    if (atomic_get(&gumi_deliver_output) == 0) {
        if (!add_without_overflow(
                &gumi_discarded_samples,
                GUMI_OMI_V3012_CODEC_FRAME_SAMPLES
            )) {
            latch_fault(-EOVERFLOW);
        }
        return;
    }
    if (gumi_next_packet_sequence == UINT64_MAX) {
        (void)add_without_overflow(
            &gumi_discarded_samples,
            GUMI_OMI_V3012_CODEC_FRAME_SAMPLES
        );
        latch_fault(-EOVERFLOW);
        return;
    }

    packet.session_id = gumi_session_id;
    packet.packet_sequence = gumi_next_packet_sequence;
    packet.bytes = gumi_codec_output;
    packet.size = (size_t)encoded_size;
    packet.pcm_sample_count = GUMI_OMI_V3012_CODEC_FRAME_SAMPLES;
    gumi_packet_handler(&packet, gumi_handler_context);
    gumi_next_packet_sequence += UINT64_C(1);
    if (!add_without_overflow(&gumi_emitted_packets, UINT64_C(1)) ||
        !add_without_overflow(
            &gumi_emitted_samples,
            GUMI_OMI_V3012_CODEC_FRAME_SAMPLES
        )) {
        latch_fault(-EOVERFLOW);
    }
}

static void codec_thread_entry(void *first, void *second, void *third)
{
    ARG_UNUSED(first);
    ARG_UNUSED(second);
    ARG_UNUSED(third);

    for (;;) {
        k_sem_take(&gumi_codec_work, K_FOREVER);
        while (ring_buf_size_get(&gumi_codec_ring) >= GUMI_CODEC_FRAME_BYTES &&
               (atomic_get(&gumi_run_requested) != 0 ||
                atomic_get(&gumi_drain_requested) != 0)) {
            encode_one_frame();
        }
        if (atomic_get(&gumi_run_requested) == 0 &&
            (atomic_get(&gumi_drain_requested) == 0 ||
             ring_buf_size_get(&gumi_codec_ring) < GUMI_CODEC_FRAME_BYTES)) {
            break;
        }
    }
}

int gumi_omi_v3012_codec_init(
    gumi_omi_v3012_codec_packet_handler packet_handler,
    gumi_omi_v3012_codec_fault_handler fault_handler,
    void *context
)
{
    int error;

    if (packet_handler == NULL) {
        return -EINVAL;
    }
    k_mutex_lock(&gumi_codec_lifecycle_lock, K_FOREVER);
    if (gumi_omi_v3012_codec_get_truth() != GUMI_OMI_V3012_CODEC_UNINITIALIZED) {
        k_mutex_unlock(&gumi_codec_lifecycle_lock);
        return -EALREADY;
    }
    gumi_packet_handler = packet_handler;
    gumi_fault_handler = fault_handler;
    gumi_handler_context = context;
    ring_buf_init(
        &gumi_codec_ring,
        sizeof(gumi_codec_ring_storage),
        gumi_codec_ring_storage
    );
    error = apply_encoder_configuration();
    if (error < 0) {
        set_truth(GUMI_OMI_V3012_CODEC_UNKNOWN);
        k_mutex_unlock(&gumi_codec_lifecycle_lock);
        return error;
    }
    memset(gumi_opus_storage, 0, sizeof(gumi_opus_storage));
    set_truth(GUMI_OMI_V3012_CODEC_CLOSED);
    k_mutex_unlock(&gumi_codec_lifecycle_lock);
    return 0;
}

int gumi_omi_v3012_codec_open(uint64_t session_id)
{
    int error;

    if (session_id == UINT64_C(0)) {
        return -EINVAL;
    }
    k_mutex_lock(&gumi_codec_lifecycle_lock, K_FOREVER);
    if (gumi_omi_v3012_codec_get_truth() != GUMI_OMI_V3012_CODEC_CLOSED) {
        k_mutex_unlock(&gumi_codec_lifecycle_lock);
        return -EBUSY;
    }
    set_truth(GUMI_OMI_V3012_CODEC_OPENING);
    ring_buf_reset(&gumi_codec_ring);
    k_sem_reset(&gumi_codec_work);
    memset(gumi_codec_ring_storage, 0, sizeof(gumi_codec_ring_storage));
    memset(gumi_codec_input, 0, sizeof(gumi_codec_input));
    memset(gumi_codec_output, 0, sizeof(gumi_codec_output));
    error = apply_encoder_configuration();
    if (error < 0) {
        set_truth(GUMI_OMI_V3012_CODEC_UNKNOWN);
        k_mutex_unlock(&gumi_codec_lifecycle_lock);
        return error;
    }

    gumi_session_id = session_id;
    gumi_next_packet_sequence = UINT64_C(1);
    gumi_submitted_samples = UINT64_C(0);
    gumi_emitted_packets = UINT64_C(0);
    gumi_emitted_samples = UINT64_C(0);
    gumi_discarded_samples = UINT64_C(0);
    atomic_set(&gumi_terminal_error, 0);
    atomic_set(&gumi_fault_reported, 0);
    atomic_set(&gumi_drain_requested, 0);
    atomic_set(&gumi_deliver_output, 1);
    atomic_set(&gumi_run_requested, 1);
    atomic_set(&gumi_accepting_pcm, 1);
    (void)k_thread_create(
        &gumi_codec_thread,
        gumi_codec_thread_stack,
        K_THREAD_STACK_SIZEOF(gumi_codec_thread_stack),
        codec_thread_entry,
        NULL,
        NULL,
        NULL,
        K_PRIO_PREEMPT(GUMI_CODEC_THREAD_PRIORITY),
        0U,
        K_NO_WAIT
    );
    gumi_thread_created = true;
    set_truth(GUMI_OMI_V3012_CODEC_ACTIVE);
    k_mutex_unlock(&gumi_codec_lifecycle_lock);
    return 0;
}

int gumi_omi_v3012_codec_submit_pcm(
    uint64_t session_id,
    const int16_t *samples,
    size_t sample_count
)
{
    size_t byte_count;
    uint32_t written;
    int error = 0;

    if (session_id == UINT64_C(0) || samples == NULL || sample_count == 0U ||
        sample_count > GUMI_CODEC_PCM_CAPACITY_SAMPLES) {
        return -EINVAL;
    }
    if (sample_count > SIZE_MAX / sizeof(int16_t)) {
        return -EOVERFLOW;
    }
    byte_count = sample_count * sizeof(int16_t);

    k_mutex_lock(&gumi_codec_lifecycle_lock, K_FOREVER);
    if (session_id != gumi_session_id) {
        error = -ESTALE;
    } else if (gumi_omi_v3012_codec_get_truth() == GUMI_OMI_V3012_CODEC_FAULTED) {
        error = -EIO;
    } else if (gumi_omi_v3012_codec_get_truth() != GUMI_OMI_V3012_CODEC_ACTIVE ||
               atomic_get(&gumi_accepting_pcm) == 0) {
        error = -EPIPE;
    } else if (gumi_submitted_samples > UINT64_MAX - sample_count) {
        latch_fault(-EOVERFLOW);
        error = -EOVERFLOW;
    } else if (ring_buf_space_get(&gumi_codec_ring) < byte_count) {
        latch_fault(-ENOSPC);
        error = -ENOSPC;
    } else {
        written = ring_buf_put(&gumi_codec_ring, (const uint8_t *)samples, byte_count);
        if (written != byte_count) {
            latch_fault(-EIO);
            error = -EIO;
        } else {
            gumi_submitted_samples += sample_count;
            k_sem_give(&gumi_codec_work);
        }
    }
    k_mutex_unlock(&gumi_codec_lifecycle_lock);
    return error;
}

int gumi_omi_v3012_codec_close(
    uint64_t session_id,
    gumi_omi_v3012_codec_close_mode mode,
    gumi_omi_v3012_codec_close_result *result
)
{
    gumi_omi_v3012_codec_truth truth;
    uint64_t queued_samples;
    int join_error = 0;
    int terminal_error;

    if (session_id == UINT64_C(0) || result == NULL ||
        (mode != GUMI_OMI_V3012_CODEC_CLOSE_DISCARD &&
         mode != GUMI_OMI_V3012_CODEC_CLOSE_DRAIN_COMPLETE)) {
        return -EINVAL;
    }
    memset(result, 0, sizeof(*result));
    if (k_current_get() == &gumi_codec_thread) {
        return -EDEADLK;
    }

    k_mutex_lock(&gumi_codec_lifecycle_lock, K_FOREVER);
    truth = gumi_omi_v3012_codec_get_truth();
    if (session_id != gumi_session_id) {
        k_mutex_unlock(&gumi_codec_lifecycle_lock);
        return -ESTALE;
    }
    if (truth != GUMI_OMI_V3012_CODEC_ACTIVE && truth != GUMI_OMI_V3012_CODEC_FAULTED) {
        k_mutex_unlock(&gumi_codec_lifecycle_lock);
        return -EBUSY;
    }

    set_truth(GUMI_OMI_V3012_CODEC_CLOSING);
    atomic_set(&gumi_accepting_pcm, 0);
    if (truth == GUMI_OMI_V3012_CODEC_FAULTED ||
        mode == GUMI_OMI_V3012_CODEC_CLOSE_DISCARD) {
        atomic_set(&gumi_deliver_output, 0);
        atomic_set(&gumi_drain_requested, 0);
    } else {
        atomic_set(&gumi_deliver_output, 1);
        atomic_set(&gumi_drain_requested, 1);
    }
    atomic_set(&gumi_run_requested, 0);
    k_sem_give(&gumi_codec_work);
    if (gumi_thread_created) {
        join_error = k_thread_join(
            &gumi_codec_thread,
            K_MSEC(GUMI_CODEC_JOIN_TIMEOUT_MS)
        );
    }
    if (join_error != 0) {
        atomic_set(&gumi_deliver_output, 0);
        set_truth(GUMI_OMI_V3012_CODEC_UNKNOWN);
        k_mutex_unlock(&gumi_codec_lifecycle_lock);
        return join_error;
    }
    gumi_thread_created = false;

    queued_samples = ring_buf_size_get(&gumi_codec_ring) / sizeof(int16_t);
    if (!add_without_overflow(&gumi_discarded_samples, queued_samples)) {
        (void)atomic_cas(&gumi_terminal_error, 0, -EOVERFLOW);
    }
    if (gumi_emitted_samples > UINT64_MAX - gumi_discarded_samples ||
        gumi_emitted_samples + gumi_discarded_samples != gumi_submitted_samples) {
        (void)atomic_cas(&gumi_terminal_error, 0, -EIO);
    }
    terminal_error = atomic_get(&gumi_terminal_error);
    result->session_id = gumi_session_id;
    result->submitted_samples = gumi_submitted_samples;
    result->emitted_packets = gumi_emitted_packets;
    result->emitted_samples = gumi_emitted_samples;
    result->discarded_samples = gumi_discarded_samples;
    result->terminal_error = terminal_error;

    ring_buf_reset(&gumi_codec_ring);
    k_sem_reset(&gumi_codec_work);
    memset(gumi_codec_ring_storage, 0, sizeof(gumi_codec_ring_storage));
    memset(gumi_codec_input, 0, sizeof(gumi_codec_input));
    memset(gumi_codec_output, 0, sizeof(gumi_codec_output));
    memset(gumi_opus_storage, 0, sizeof(gumi_opus_storage));
    gumi_session_id = UINT64_C(0);
    gumi_next_packet_sequence = UINT64_C(0);
    gumi_submitted_samples = UINT64_C(0);
    gumi_emitted_packets = UINT64_C(0);
    gumi_emitted_samples = UINT64_C(0);
    gumi_discarded_samples = UINT64_C(0);
    atomic_set(&gumi_accepting_pcm, 0);
    atomic_set(&gumi_run_requested, 0);
    atomic_set(&gumi_drain_requested, 0);
    atomic_set(&gumi_deliver_output, 0);
    set_truth(GUMI_OMI_V3012_CODEC_CLOSED);
    k_mutex_unlock(&gumi_codec_lifecycle_lock);
    return terminal_error < 0 ? terminal_error : 0;
}

gumi_omi_v3012_codec_truth gumi_omi_v3012_codec_get_truth(void)
{
    return (gumi_omi_v3012_codec_truth)atomic_get(&gumi_codec_truth);
}
