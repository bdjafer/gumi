#include "gumi/omi_v3012_mic.h"

#include <errno.h>
#include <stdbool.h>

#include <nrfx_pdm.h>
#include <zephyr/audio/dmic.h>
#include <zephyr/device.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/atomic.h>

LOG_MODULE_REGISTER(gumi_omi_v3012_mic, CONFIG_LOG_DEFAULT_LEVEL);

#define GUMI_MIC_SAMPLE_RATE 16000U
#define GUMI_MIC_SAMPLE_BIT_WIDTH 16U
#define GUMI_MIC_CHANNEL_COUNT 2U
#define GUMI_MIC_BLOCK_MILLISECONDS 100U
#define GUMI_MIC_BYTES_PER_SAMPLE sizeof(int16_t)
#define GUMI_MIC_FRAMES_PER_BLOCK \
    (GUMI_MIC_SAMPLE_RATE * GUMI_MIC_BLOCK_MILLISECONDS / 1000U)
#define GUMI_MIC_BLOCK_SIZE \
    (GUMI_MIC_BYTES_PER_SAMPLE * GUMI_MIC_FRAMES_PER_BLOCK * GUMI_MIC_CHANNEL_COUNT)
#define GUMI_MIC_BLOCK_COUNT 4U
#define GUMI_MIC_READ_TIMEOUT_MS 125
#define GUMI_MIC_JOIN_TIMEOUT_MS 500
#define GUMI_MIC_STOP_BARRIER_ATTEMPTS 100U
#define GUMI_MIC_THREAD_STACK_SIZE 2048
#define GUMI_MIC_THREAD_PRIORITY 5

K_MEM_SLAB_DEFINE_STATIC(
    gumi_mic_mem_slab,
    GUMI_MIC_BLOCK_SIZE,
    GUMI_MIC_BLOCK_COUNT,
    4
);
K_THREAD_STACK_DEFINE(gumi_mic_thread_stack, GUMI_MIC_THREAD_STACK_SIZE);
K_MUTEX_DEFINE(gumi_mic_lifecycle_lock);

static const struct device *gumi_dmic;
static struct k_thread gumi_mic_thread;
static atomic_t gumi_mic_run_requested = ATOMIC_INIT(0);
static atomic_t gumi_mic_truth = ATOMIC_INIT(GUMI_OMI_V3012_MIC_UNINITIALIZED);
static atomic_t gumi_mic_fault_reported = ATOMIC_INIT(0);
static gumi_omi_v3012_mic_frame_handler gumi_frame_handler;
static gumi_omi_v3012_mic_fault_handler gumi_fault_handler;
static void *gumi_handler_context;
static bool gumi_thread_created;
static uint8_t gumi_gain_level;
static int16_t gumi_mono_buffer[GUMI_MIC_FRAMES_PER_BLOCK];

static void set_truth(gumi_omi_v3012_mic_truth truth)
{
    atomic_set(&gumi_mic_truth, (atomic_val_t)truth);
}

static void report_fault_once(int error)
{
    if (gumi_fault_handler != NULL && atomic_cas(&gumi_mic_fault_reported, 0, 1)) {
        gumi_fault_handler(error, gumi_handler_context);
    }
}

static int configure_driver(void)
{
    struct pcm_stream_cfg stream = {
        .pcm_rate = GUMI_MIC_SAMPLE_RATE,
        .pcm_width = GUMI_MIC_SAMPLE_BIT_WIDTH,
        .block_size = GUMI_MIC_BLOCK_SIZE,
        .mem_slab = &gumi_mic_mem_slab,
    };
    struct dmic_cfg config = {
        .io = {
            .min_pdm_clk_freq = 512000U,
            .max_pdm_clk_freq = 3500000U,
            .min_pdm_clk_dc = 48U,
            .max_pdm_clk_dc = 52U,
        },
        .streams = &stream,
        .channel = {
            .req_num_streams = 1U,
            .req_num_chan = GUMI_MIC_CHANNEL_COUNT,
            .req_chan_map_lo =
                dmic_build_channel_map(0U, 0U, PDM_CHAN_LEFT) |
                dmic_build_channel_map(1U, 0U, PDM_CHAN_RIGHT),
        },
    };

    return dmic_configure(gumi_dmic, &config);
}

static void apply_gain(uint8_t gain_level)
{
    static const uint8_t gain_map[9] = {
        0x00U,
        0x14U,
        0x1eU,
        0x28U,
        0x2eU,
        0x32U,
        0x3cU,
        0x46U,
        0x50U,
    };
    uint8_t bounded_level = gain_level > 8U ? 8U : gain_level;

#ifdef NRF_PDM0_S
    nrf_pdm_gain_set(NRF_PDM0_S, gain_map[bounded_level], gain_map[bounded_level]);
#else
    nrf_pdm_gain_set(NRF_PDM0_NS, gain_map[bounded_level], gain_map[bounded_level]);
#endif
}

static void stereo_to_mono(const int16_t *stereo, size_t frame_count)
{
    size_t frame;

    for (frame = 0U; frame < frame_count; frame += 1U) {
        int32_t left = stereo[frame * 2U];
        int32_t right = stereo[frame * 2U + 1U];
        gumi_mono_buffer[frame] = (int16_t)((left + right) / 2);
    }
}

static void consume_buffer(void *buffer, size_t size)
{
    size_t frame_count = size / (GUMI_MIC_BYTES_PER_SAMPLE * GUMI_MIC_CHANNEL_COUNT);

    if (size % (GUMI_MIC_BYTES_PER_SAMPLE * GUMI_MIC_CHANNEL_COUNT) != 0U ||
        frame_count > GUMI_MIC_FRAMES_PER_BLOCK) {
        report_fault_once(-EMSGSIZE);
    } else if (atomic_get(&gumi_mic_run_requested) != 0 && gumi_frame_handler != NULL) {
        stereo_to_mono((const int16_t *)buffer, frame_count);
        gumi_frame_handler(gumi_mono_buffer, frame_count, gumi_handler_context);
    }
    k_mem_slab_free(&gumi_mic_mem_slab, buffer);
}

static void mic_thread_entry(void *first, void *second, void *third)
{
    ARG_UNUSED(first);
    ARG_UNUSED(second);
    ARG_UNUSED(third);

    while (atomic_get(&gumi_mic_run_requested) != 0) {
        void *buffer = NULL;
        size_t size = 0U;
        int error = dmic_read(gumi_dmic, 0U, &buffer, &size, GUMI_MIC_READ_TIMEOUT_MS);

        if (error == -EAGAIN) {
            continue;
        }
        if (error < 0) {
            report_fault_once(error);
            k_msleep(10);
            continue;
        }
        consume_buffer(buffer, size);
    }
}

static void drain_completed_buffers(void)
{
    unsigned int drained;

    for (drained = 0U; drained < GUMI_MIC_BLOCK_COUNT; drained += 1U) {
        void *buffer = NULL;
        size_t size = 0U;

        if (dmic_read(gumi_dmic, 0U, &buffer, &size, 0) != 0) {
            break;
        }
        k_mem_slab_free(&gumi_mic_mem_slab, buffer);
    }
}

static int establish_stop_barrier(void)
{
    unsigned int attempt;
    int error = -EBUSY;

    for (attempt = 0U; attempt < GUMI_MIC_STOP_BARRIER_ATTEMPTS; attempt += 1U) {
        error = configure_driver();
        if (error != -EBUSY) {
            return error;
        }
        k_msleep(1);
    }
    return error;
}

int gumi_omi_v3012_mic_init(
    gumi_omi_v3012_mic_frame_handler frame_handler,
    gumi_omi_v3012_mic_fault_handler fault_handler,
    void *context,
    uint8_t gain_level
)
{
    int error;

    if (frame_handler == NULL) {
        return -EINVAL;
    }
    k_mutex_lock(&gumi_mic_lifecycle_lock, K_FOREVER);
    if (gumi_omi_v3012_mic_get_truth() != GUMI_OMI_V3012_MIC_UNINITIALIZED) {
        k_mutex_unlock(&gumi_mic_lifecycle_lock);
        return -EALREADY;
    }

    gumi_dmic = DEVICE_DT_GET(DT_ALIAS(dmic0));
    if (!device_is_ready(gumi_dmic)) {
        set_truth(GUMI_OMI_V3012_MIC_UNKNOWN);
        k_mutex_unlock(&gumi_mic_lifecycle_lock);
        return -ENODEV;
    }
    gumi_frame_handler = frame_handler;
    gumi_fault_handler = fault_handler;
    gumi_handler_context = context;
    gumi_gain_level = gain_level > 8U ? 8U : gain_level;
    error = configure_driver();
    if (error < 0) {
        set_truth(GUMI_OMI_V3012_MIC_UNKNOWN);
        k_mutex_unlock(&gumi_mic_lifecycle_lock);
        return error;
    }
    apply_gain(gumi_gain_level);
    set_truth(GUMI_OMI_V3012_MIC_VERIFIED_OFF);
    k_mutex_unlock(&gumi_mic_lifecycle_lock);
    return 0;
}

int gumi_omi_v3012_mic_acquire(void)
{
    int error;

    k_mutex_lock(&gumi_mic_lifecycle_lock, K_FOREVER);
    if (gumi_omi_v3012_mic_get_truth() != GUMI_OMI_V3012_MIC_VERIFIED_OFF) {
        k_mutex_unlock(&gumi_mic_lifecycle_lock);
        return -EBUSY;
    }

    set_truth(GUMI_OMI_V3012_MIC_STARTING);
    atomic_set(&gumi_mic_fault_reported, 0);
    atomic_set(&gumi_mic_run_requested, 1);
    apply_gain(gumi_gain_level);
    error = dmic_trigger(gumi_dmic, DMIC_TRIGGER_START);
    if (error < 0) {
        atomic_set(&gumi_mic_run_requested, 0);
        set_truth(GUMI_OMI_V3012_MIC_VERIFIED_OFF);
        k_mutex_unlock(&gumi_mic_lifecycle_lock);
        return error;
    }

    (void)k_thread_create(
        &gumi_mic_thread,
        gumi_mic_thread_stack,
        K_THREAD_STACK_SIZEOF(gumi_mic_thread_stack),
        mic_thread_entry,
        NULL,
        NULL,
        NULL,
        K_PRIO_PREEMPT(GUMI_MIC_THREAD_PRIORITY),
        0U,
        K_NO_WAIT
    );
    gumi_thread_created = true;
    set_truth(GUMI_OMI_V3012_MIC_RUNNING);
    k_mutex_unlock(&gumi_mic_lifecycle_lock);
    return 0;
}

int gumi_omi_v3012_mic_release(void)
{
    int stop_error;
    int join_error = 0;
    int barrier_error;

    k_mutex_lock(&gumi_mic_lifecycle_lock, K_FOREVER);
    if (gumi_omi_v3012_mic_get_truth() == GUMI_OMI_V3012_MIC_VERIFIED_OFF) {
        k_mutex_unlock(&gumi_mic_lifecycle_lock);
        return 0;
    }
    if (gumi_omi_v3012_mic_get_truth() != GUMI_OMI_V3012_MIC_RUNNING) {
        k_mutex_unlock(&gumi_mic_lifecycle_lock);
        return -EBUSY;
    }

    set_truth(GUMI_OMI_V3012_MIC_STOPPING);
    atomic_set(&gumi_mic_run_requested, 0);
    stop_error = dmic_trigger(gumi_dmic, DMIC_TRIGGER_STOP);
    if (gumi_thread_created) {
        join_error = k_thread_join(&gumi_mic_thread, K_MSEC(GUMI_MIC_JOIN_TIMEOUT_MS));
    }
    if (join_error != 0) {
        set_truth(GUMI_OMI_V3012_MIC_UNKNOWN);
        k_mutex_unlock(&gumi_mic_lifecycle_lock);
        return join_error;
    }
    gumi_thread_created = false;
    drain_completed_buffers();
    barrier_error = establish_stop_barrier();
    if (stop_error < 0 || barrier_error < 0) {
        set_truth(GUMI_OMI_V3012_MIC_UNKNOWN);
        k_mutex_unlock(&gumi_mic_lifecycle_lock);
        return stop_error < 0 ? stop_error : barrier_error;
    }

    apply_gain(gumi_gain_level);
    set_truth(GUMI_OMI_V3012_MIC_VERIFIED_OFF);
    k_mutex_unlock(&gumi_mic_lifecycle_lock);
    return 0;
}

int gumi_omi_v3012_mic_set_gain(uint8_t gain_level)
{
    k_mutex_lock(&gumi_mic_lifecycle_lock, K_FOREVER);
    if (gumi_omi_v3012_mic_get_truth() == GUMI_OMI_V3012_MIC_UNINITIALIZED ||
        gumi_omi_v3012_mic_get_truth() == GUMI_OMI_V3012_MIC_UNKNOWN) {
        k_mutex_unlock(&gumi_mic_lifecycle_lock);
        return -EIO;
    }
    gumi_gain_level = gain_level > 8U ? 8U : gain_level;
    apply_gain(gumi_gain_level);
    k_mutex_unlock(&gumi_mic_lifecycle_lock);
    return 0;
}

gumi_omi_v3012_mic_truth gumi_omi_v3012_mic_get_truth(void)
{
    return (gumi_omi_v3012_mic_truth)atomic_get(&gumi_mic_truth);
}
