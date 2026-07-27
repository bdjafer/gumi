#include "gumi/button.h"
#include "gumi/capture.h"
#include "gumi/feedback.h"
#include "gumi/omi_v3012_codec.h"
#include "gumi/omi_v3012_crypto.h"
#include "gumi/omi_v3012_functional_io.h"
#include "gumi/omi_v3012_functional_transport.h"
#include "gumi/omi_v3012_mic.h"
#include "gumi/omi_v3012_recording_key.h"
#include "gumi/omi_v3012_recording_storage.h"
#include "gumi/omi_v3012_reset.h"

#include <errno.h>
#include <limits.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/device.h>
#include <zephyr/drivers/watchdog.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/reboot.h>
#include <zephyr/sys/util.h>
#include <zephyr/task_wdt/task_wdt.h>

#define GUMI_MAIN_POLL_MS 10
#define GUMI_UPDATE_CONFIRMATION_POLL_MS 10
#define GUMI_MAIN_WATCHDOG_TIMEOUT_MS 15000U
#define GUMI_RECORDING_NAME_ATTEMPTS 8U
#define GUMI_RECORDING_SYNC_EVERY_PACKETS 50U
#define GUMI_RECORDING_MINIMUM_FREE_BYTES (UINT64_C(4) * 1024U * 1024U)
#define GUMI_STATUS_PULSE_MS UINT64_C(300)
#define GUMI_RUNTIME_UPDATE_HOLD_MS UINT64_C(5000)
#define GUMI_RUNTIME_RESET_HOLD_MS UINT64_C(12000)
#define GUMI_ACTION_QUEUE_CAPACITY 16U

#if DT_HAS_COMPAT_STATUS_OKAY(nordic_nrf_wdt)
#define GUMI_MAIN_WATCHDOG_NODE \
    DT_COMPAT_GET_ANY_STATUS_OKAY(nordic_nrf_wdt)
#else
#define GUMI_MAIN_WATCHDOG_NODE DT_INVALID_NODE
#endif

enum {
    GUMI_ASYNC_MICROPHONE = 1U << 0,
    GUMI_ASYNC_CODEC = 1U << 1,
    GUMI_ASYNC_STORAGE = 1U << 2,
    GUMI_ASYNC_PRIVACY_OUTPUT = 1U << 3,
    GUMI_ASYNC_AUXILIARY_IO = 1U << 4,
    GUMI_ASYNC_BUTTON = 1U << 5,
    GUMI_ASYNC_WATCHDOG = 1U << 6,
};

typedef struct {
    gumi_capture_action entries[GUMI_ACTION_QUEUE_CAPACITY];
    size_t head;
    size_t count;
} action_queue;

static gumi_capture_supervisor supervisor;
static gumi_button_debouncer button_debouncer;
static gumi_button_gesture button_gesture;
static struct k_spinlock active_session_lock;
static uint64_t active_session_id;
static atomic_t audio_gate = ATOMIC_INIT(0);
static atomic_t async_faults = ATOMIC_INIT(0);
static atomic_t first_error = ATOMIC_INIT(0);
static bool capture_ports_ready;
static bool transport_ready;
static bool update_physically_confirmed;
static bool recoverable_warning;
static bool runtime_update_hold_tracking;
static uint64_t runtime_update_hold_started_ms;
static bool runtime_reset_hold_tracking;
static uint64_t runtime_reset_hold_started_ms;
static int main_watchdog_channel = -1;
static uint32_t status_generation;
static gumi_feedback_pattern requested_status = GUMI_FEEDBACK_PATTERN_NONE;
static uint64_t requested_status_until_ms;

static uint64_t monotonic_milliseconds(void)
{
    int64_t now = k_uptime_get();

    return now > 0 ? (uint64_t)now : UINT64_C(0);
}

static int start_main_watchdog(void)
{
    const struct device *watchdog =
        DEVICE_DT_GET_OR_NULL(GUMI_MAIN_WATCHDOG_NODE);
    int error;

    if (watchdog == NULL || !device_is_ready(watchdog)) {
        return -ENODEV;
    }
    error = task_wdt_init(watchdog);
    if (error < 0) {
        return error;
    }
    main_watchdog_channel = task_wdt_add(
        GUMI_MAIN_WATCHDOG_TIMEOUT_MS,
        NULL,
        NULL
    );
    return main_watchdog_channel < 0 ? main_watchdog_channel : 0;
}

static void feed_main_watchdog(void)
{
    if (main_watchdog_channel >= 0 &&
        task_wdt_feed(main_watchdog_channel) < 0) {
        gumi_omi_v3012_whole_device_reboot();
    }
}

static uint64_t active_session_get(void)
{
    uint64_t session_id;
    k_spinlock_key_t key = k_spin_lock(&active_session_lock);

    session_id = active_session_id;
    k_spin_unlock(&active_session_lock, key);
    return session_id;
}

static void active_session_set(uint64_t session_id)
{
    k_spinlock_key_t key = k_spin_lock(&active_session_lock);

    active_session_id = session_id;
    k_spin_unlock(&active_session_lock, key);
}

static void latch_error(unsigned int kind, int error)
{
    atomic_val_t normalized = (atomic_val_t)(error < 0 ? error : -EIO);

    (void)atomic_cas(&first_error, 0, normalized);
    (void)atomic_or(&async_faults, (atomic_val_t)kind);
}

static void synchronize_audio_gate(void)
{
    atomic_set(
        &audio_gate,
        gumi_capture_base_audio_is_permitted(&supervisor) ||
                gumi_capture_voice_audio_is_permitted(&supervisor)
            ? 1
            : 0
    );
}

static bool capture_is_idle_for_update(void)
{
    gumi_omi_v3012_mic_truth mic_truth = gumi_omi_v3012_mic_get_truth();

    return active_session_get() == UINT64_C(0) &&
           mic_truth == GUMI_OMI_V3012_MIC_VERIFIED_OFF &&
           !supervisor.base_recording_active &&
           supervisor.voice == GUMI_CAPTURE_VOICE_INACTIVE &&
           (supervisor.phase == GUMI_CAPTURE_PHASE_BOOTING ||
            supervisor.phase == GUMI_CAPTURE_PHASE_IDLE ||
            supervisor.phase == GUMI_CAPTURE_PHASE_FATAL_IDLE);
}

static uint8_t functional_flags(void)
{
    uint8_t flags = 0U;
    gumi_omi_v3012_recording_storage_truth storage_truth =
        gumi_omi_v3012_recording_storage_get_truth();

    if (supervisor.power == GUMI_CAPTURE_POWER_OPERATIONAL) {
        flags |= GUMI_OMI_V3012_FUNCTIONAL_FLAG_OPERATIONAL;
    }
    if (gumi_capture_base_audio_is_permitted(&supervisor)) {
        flags |= GUMI_OMI_V3012_FUNCTIONAL_FLAG_BASE_AUDIO_PERMITTED;
    }
    if (gumi_capture_voice_audio_is_permitted(&supervisor)) {
        flags |= GUMI_OMI_V3012_FUNCTIONAL_FLAG_VOICE_AUDIO_PERMITTED;
    }
    if (supervisor.privacy_guard_asserted) {
        flags |= GUMI_OMI_V3012_FUNCTIONAL_FLAG_PRIVACY_ASSERTED;
    }
    if (update_physically_confirmed && capture_is_idle_for_update()) {
        flags |= GUMI_OMI_V3012_FUNCTIONAL_FLAG_UPDATE_ADMITTED;
    }
    if (gumi_omi_v3012_recording_key_get_truth() ==
        GUMI_OMI_V3012_RECORDING_KEY_READY) {
        flags |= GUMI_OMI_V3012_FUNCTIONAL_FLAG_KEY_READY;
    }
    if (storage_truth == GUMI_OMI_V3012_RECORDING_STORAGE_READY ||
        storage_truth == GUMI_OMI_V3012_RECORDING_STORAGE_COMMITTED ||
        storage_truth == GUMI_OMI_V3012_RECORDING_STORAGE_INTERRUPTED) {
        flags |= GUMI_OMI_V3012_FUNCTIONAL_FLAG_STORAGE_READY;
    }
    if (!capture_ports_ready || recoverable_warning ||
        supervisor.fault != GUMI_CAPTURE_FAULT_NONE ||
        atomic_get(&first_error) != 0) {
        flags |= GUMI_OMI_V3012_FUNCTIONAL_FLAG_FAULTED;
    }
    return flags;
}

static void publish_status(void)
{
    gumi_omi_v3012_functional_status status;
    bool update_idle = capture_is_idle_for_update();

    gumi_omi_v3012_functional_update_admission_set(
        update_physically_confirmed,
        update_idle
    );
    if (!transport_ready) {
        return;
    }
    memset(&status, 0, sizeof(status));
    status.active_recording_id = supervisor.active_recording_id;
    status.free_bytes = gumi_omi_v3012_recording_storage_free_bytes();
    status.generation = ++status_generation;
    status.last_error = (int32_t)atomic_get(&first_error);
    status.capture_phase = (uint8_t)supervisor.phase;
    status.mic_truth = (uint8_t)supervisor.mic_truth;
    status.storage_state = (uint8_t)supervisor.storage;
    status.key_truth =
        (uint8_t)gumi_omi_v3012_recording_key_get_truth();
    status.storage_truth =
        (uint8_t)gumi_omi_v3012_recording_storage_get_truth();
    status.codec_truth = (uint8_t)gumi_omi_v3012_codec_get_truth();
    status.flags = functional_flags();
    if (gumi_omi_v3012_functional_status_publish(&status) < 0) {
        transport_ready = false;
    }
}

static void play_result_haptic(const gumi_capture_result *result)
{
    if (result->haptic != GUMI_CAPTURE_HAPTIC_NONE &&
        gumi_omi_v3012_functional_haptic_play(result->haptic) < 0) {
        latch_error(GUMI_ASYNC_AUXILIARY_IO, -EIO);
    }
}

static bool action_queue_push(action_queue *queue, const gumi_capture_action *action)
{
    size_t tail;

    if (queue->count >= ARRAY_SIZE(queue->entries)) {
        return false;
    }
    tail = (queue->head + queue->count) % ARRAY_SIZE(queue->entries);
    queue->entries[tail] = *action;
    queue->count += 1U;
    return true;
}

static bool action_queue_pop(action_queue *queue, gumi_capture_action *action)
{
    if (queue->count == 0U) {
        return false;
    }
    *action = queue->entries[queue->head];
    queue->head = (queue->head + 1U) % ARRAY_SIZE(queue->entries);
    queue->count -= 1U;
    return true;
}

static bool enqueue_result_actions(
    action_queue *queue,
    const gumi_capture_result *result
)
{
    size_t index;

    play_result_haptic(result);
    for (index = 0U; index < result->action_count; index += 1U) {
        if (!action_queue_push(queue, &result->actions[index])) {
            return false;
        }
    }
    return true;
}

static int random_recording_config(
    uint64_t recording_id,
    gumi_recording_store_config *config
)
{
    int error;

    if (recording_id == UINT64_C(0) || config == NULL) {
        return -EINVAL;
    }
    memset(config, 0, sizeof(*config));
    error = gumi_omi_v3012_crypto_random_nonzero(
        (uint8_t *)&config->journal.session_id,
        sizeof(config->journal.session_id)
    );
    if (error < 0) {
        return error;
    }
    error = gumi_omi_v3012_crypto_random_nonzero(
        config->journal.nonce_base,
        sizeof(config->journal.nonce_base)
    );
    if (error < 0) {
        return error;
    }
    error = gumi_omi_v3012_crypto_random_nonzero(
        config->name_token,
        sizeof(config->name_token)
    );
    if (error < 0) {
        return error;
    }
    config->journal.recording_id = recording_id;
    config->journal.sample_rate = GUMI_OMI_V3012_CODEC_SAMPLE_RATE;
    config->journal.frame_samples = GUMI_OMI_V3012_CODEC_FRAME_SAMPLES;
    config->journal.max_codec_payload_bytes =
        GUMI_OMI_V3012_CODEC_MAX_PACKET_BYTES;
    config->journal.key_id = GUMI_OMI_V3012_RECORDING_KEY_VERSION;
    config->journal.codec = GUMI_RECORDING_JOURNAL_CODEC_OPUS;
    config->journal.protection =
        GUMI_RECORDING_JOURNAL_PROTECTION_AES_256_GCM_V1;
    config->sync_every_audio_records = GUMI_RECORDING_SYNC_EVERY_PACKETS;
    return 0;
}

static int prepare_local_recording(void)
{
    gumi_recording_store_config config;
    uint64_t recording_id = supervisor.next_recording_id;
    unsigned int attempt;
    int error;

    error = random_recording_config(recording_id, &config);
    if (error < 0) {
        return error;
    }
    for (attempt = 0U; attempt < GUMI_RECORDING_NAME_ATTEMPTS; attempt += 1U) {
        error = gumi_omi_v3012_recording_storage_prepare(&config);
        if (error != -EEXIST) {
            break;
        }
        error = gumi_omi_v3012_crypto_random_nonzero(
            config.name_token,
            sizeof(config.name_token)
        );
        if (error < 0) {
            break;
        }
    }
    if (error < 0) {
        return error;
    }
    active_session_set(config.journal.session_id);
    error = gumi_omi_v3012_codec_open(config.journal.session_id);
    if (error < 0) {
        (void)gumi_omi_v3012_recording_storage_interrupt(
            config.journal.session_id
        );
        active_session_set(UINT64_C(0));
        return error;
    }
    return 0;
}

static bool close_codec_barrier(
    gumi_omi_v3012_codec_close_mode mode,
    int *terminal_error
)
{
    gumi_omi_v3012_codec_close_result result;
    uint64_t session_id = active_session_get();
    int error;

    if (terminal_error != NULL) {
        *terminal_error = 0;
    }
    if (session_id == UINT64_C(0)) {
        return false;
    }
    memset(&result, 0, sizeof(result));
    error = gumi_omi_v3012_codec_close(session_id, mode, &result);
    if (terminal_error != NULL) {
        *terminal_error = error < 0 ? error : result.terminal_error;
    }
    return gumi_omi_v3012_codec_get_truth() ==
           GUMI_OMI_V3012_CODEC_CLOSED;
}

static gumi_capture_completion finalize_local_recording(int *error)
{
    uint64_t session_id = active_session_get();
    int codec_error = 0;
    int storage_error;

    atomic_set(&audio_gate, 0);
    if (!close_codec_barrier(
            GUMI_OMI_V3012_CODEC_CLOSE_DRAIN_COMPLETE,
            &codec_error
        )) {
        *error = codec_error != 0 ? codec_error : -EIO;
        return GUMI_CAPTURE_COMPLETION_LOCAL_RECORDING_INTERRUPTED;
    }
    if (codec_error == 0) {
        storage_error =
            gumi_omi_v3012_recording_storage_finalize(session_id);
        if (storage_error == 0) {
            active_session_set(UINT64_C(0));
            *error = 0;
            return GUMI_CAPTURE_COMPLETION_LOCAL_RECORDING_FINALIZED;
        }
    } else {
        storage_error = codec_error;
    }

    if (gumi_omi_v3012_recording_storage_interrupt(session_id) == 0) {
        active_session_set(UINT64_C(0));
        *error = storage_error;
        return GUMI_CAPTURE_COMPLETION_LOCAL_RECORDING_INTERRUPTED;
    }
    *error = storage_error != 0 ? storage_error : -EIO;
    return GUMI_CAPTURE_COMPLETION_LOCAL_RECORDING_INTERRUPTED;
}

static bool interrupt_local_recording(int *error)
{
    uint64_t session_id = active_session_get();
    int codec_error = 0;
    int storage_error;

    atomic_set(&audio_gate, 0);
    if (!close_codec_barrier(
            GUMI_OMI_V3012_CODEC_CLOSE_DISCARD,
            &codec_error
        )) {
        *error = codec_error != 0 ? codec_error : -EIO;
        return false;
    }
    storage_error =
        gumi_omi_v3012_recording_storage_interrupt(session_id);
    if (storage_error < 0) {
        *error = storage_error;
        return false;
    }
    active_session_set(UINT64_C(0));
    *error = codec_error;
    return true;
}

static void terminal_fail_closed(int error)
{
    uint64_t session_id = active_session_get();
    int ignored;

    latch_error(GUMI_ASYNC_STORAGE, error);
    capture_ports_ready = false;
    atomic_set(&audio_gate, 0);
    (void)gumi_omi_v3012_mic_release();
    if (session_id != UINT64_C(0) &&
        close_codec_barrier(GUMI_OMI_V3012_CODEC_CLOSE_DISCARD, &ignored)) {
        (void)gumi_omi_v3012_recording_storage_interrupt(session_id);
        active_session_set(UINT64_C(0));
    }
    (void)gumi_omi_v3012_functional_privacy_set(true);
    publish_status();
    for (;;) {
        k_sleep(K_FOREVER);
    }
}

static void execute_action(
    const gumi_capture_action *action,
    gumi_capture_completion *completion,
    int *error
)
{
    bool interrupted;

    *error = 0;
    switch (action->type) {
        case GUMI_CAPTURE_ACTION_ASSERT_PRIVACY_GUARD:
            *error = gumi_omi_v3012_functional_privacy_set(true);
            *completion = *error == 0
                ? GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED
                : GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_FAILED;
            break;
        case GUMI_CAPTURE_ACTION_PREPARE_LOCAL_DURABILITY:
            *error = prepare_local_recording();
            *completion = *error == 0
                ? GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_READY
                : GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_FAILED;
            break;
        case GUMI_CAPTURE_ACTION_ACQUIRE_MICROPHONE:
            *error = gumi_omi_v3012_mic_acquire();
            *completion = *error == 0
                ? GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRED
                : GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRE_FAILED;
            break;
        case GUMI_CAPTURE_ACTION_OPEN_REALTIME_ROUTE:
            *error = -EACCES;
            *completion = GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_FAILED;
            break;
        case GUMI_CAPTURE_ACTION_CLOSE_REALTIME_ROUTE:
            *completion = GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_CLOSED;
            break;
        case GUMI_CAPTURE_ACTION_FINALIZE_LOCAL_RECORDING:
            *completion = finalize_local_recording(error);
            if (*completion ==
                    GUMI_CAPTURE_COMPLETION_LOCAL_RECORDING_INTERRUPTED &&
                active_session_get() != UINT64_C(0)) {
                terminal_fail_closed(*error);
            }
            break;
        case GUMI_CAPTURE_ACTION_COMMIT_LAST_DURABLE_FRAME:
            interrupted = interrupt_local_recording(error);
            if (!interrupted) {
                terminal_fail_closed(*error);
            }
            *completion =
                GUMI_CAPTURE_COMPLETION_LAST_DURABLE_FRAME_COMMITTED;
            break;
        case GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE:
            *error = gumi_omi_v3012_mic_release();
            if (*error < 0 ||
                gumi_omi_v3012_mic_get_truth() !=
                    GUMI_OMI_V3012_MIC_VERIFIED_OFF) {
                terminal_fail_closed(*error);
            }
            *completion = GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED;
            break;
        case GUMI_CAPTURE_ACTION_DEASSERT_PRIVACY_GUARD:
            *error = gumi_omi_v3012_functional_privacy_set(false);
            if (*error < 0) {
                terminal_fail_closed(*error);
            }
            /*
             * The supervisor clears the logical guard when it emits this
             * terminal action; there is intentionally no second completion.
             */
            *completion = GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED;
            break;
        default:
            terminal_fail_closed(-EINVAL);
    }
}

static void drive_capture_result(uint64_t at_ms, gumi_capture_result *initial)
{
    action_queue queue;
    gumi_capture_action action;

    memset(&queue, 0, sizeof(queue));
    synchronize_audio_gate();
    if (!enqueue_result_actions(&queue, initial)) {
        terminal_fail_closed(-EOVERFLOW);
    }
    publish_status();

    while (action_queue_pop(&queue, &action)) {
        gumi_capture_completion completion;
        gumi_capture_result next;
        int error;

        /*
         * Concurrent effects are serialized by this composition. If an
         * earlier failure replaced the transition, queued effects from the
         * abandoned transition must never touch hardware.
         */
        if (action.transition_id != supervisor.transition_id &&
            !(action.type ==
                  GUMI_CAPTURE_ACTION_DEASSERT_PRIVACY_GUARD &&
              supervisor.transition_id == UINT64_C(0) &&
              supervisor.mic_truth ==
                  GUMI_CAPTURE_MIC_VERIFIED_OFF)) {
            continue;
        }
        execute_action(&action, &completion, &error);
        if (error < 0) {
            (void)atomic_cas(&first_error, 0, (atomic_val_t)error);
            recoverable_warning = true;
        }
        if (action.type == GUMI_CAPTURE_ACTION_DEASSERT_PRIVACY_GUARD) {
            publish_status();
            continue;
        }
        if (gumi_capture_complete(
                &supervisor,
                at_ms,
                action.transition_id,
                completion,
                &next
            ) != GUMI_CAPTURE_STATUS_OK) {
            terminal_fail_closed(-EPROTO);
        }
        synchronize_audio_gate();
        if (!enqueue_result_actions(&queue, &next)) {
            terminal_fail_closed(-EOVERFLOW);
        }
        publish_status();
    }
}

static void submit_microphone_frame(
    const int16_t *samples,
    size_t sample_count,
    void *context
)
{
    uint64_t session_id;
    int error;

    ARG_UNUSED(context);
    if (atomic_get(&audio_gate) == 0) {
        return;
    }
    session_id = active_session_get();
    if (session_id == UINT64_C(0) || samples == NULL || sample_count == 0U ||
        sample_count > (size_t)INT_MAX) {
        latch_error(GUMI_ASYNC_MICROPHONE, -EINVAL);
        return;
    }
    error = gumi_omi_v3012_codec_submit_pcm(
        session_id,
        samples,
        sample_count
    );
    if (error < 0 && atomic_get(&audio_gate) != 0) {
        latch_error(GUMI_ASYNC_CODEC, error);
    }
}

static void store_opus_packet(
    const gumi_omi_v3012_codec_packet *packet,
    void *context
)
{
    uint64_t session_id = active_session_get();
    int error;

    ARG_UNUSED(context);
    if (packet == NULL || packet->bytes == NULL ||
        packet->session_id != session_id || packet->size == 0U ||
        packet->size > GUMI_OMI_V3012_CODEC_MAX_PACKET_BYTES ||
        packet->pcm_sample_count == 0U ||
        packet->pcm_sample_count > UINT32_MAX) {
        latch_error(GUMI_ASYNC_CODEC, -EBADMSG);
        return;
    }
    error = gumi_omi_v3012_recording_storage_submit(
        packet->session_id,
        packet->packet_sequence,
        (uint32_t)packet->pcm_sample_count,
        packet->bytes,
        packet->size
    );
    if (error < 0) {
        latch_error(GUMI_ASYNC_STORAGE, error);
    }
}

static void microphone_fault(int error, void *context)
{
    ARG_UNUSED(context);
    latch_error(GUMI_ASYNC_MICROPHONE, error);
}

static void codec_fault(uint64_t session_id, int error, void *context)
{
    ARG_UNUSED(context);
    if (session_id == active_session_get()) {
        latch_error(GUMI_ASYNC_CODEC, error);
    }
}

static void storage_fault(uint64_t session_id, int error, void *context)
{
    ARG_UNUSED(context);
    if (session_id == UINT64_C(0) || session_id == active_session_get()) {
        latch_error(GUMI_ASYNC_STORAGE, error);
    }
}

static bool pipeline_transition_can_fail(void)
{
    return supervisor.phase == GUMI_CAPTURE_PHASE_STARTING_BASE ||
           supervisor.phase == GUMI_CAPTURE_PHASE_BASE_ACTIVE ||
           supervisor.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_IDLE ||
           supervisor.phase == GUMI_CAPTURE_PHASE_VOICE_IDLE_ACTIVE ||
           supervisor.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY ||
           supervisor.phase == GUMI_CAPTURE_PHASE_VOICE_OVERLAY_ACTIVE;
}

static void handle_async_faults(uint64_t at_ms)
{
    unsigned int faults = (unsigned int)atomic_set(&async_faults, 0);
    gumi_capture_result result;
    gumi_capture_status status;
    int error = atomic_get(&first_error);

    if (faults == 0U) {
        return;
    }
    recoverable_warning = true;
    if ((faults & GUMI_ASYNC_PRIVACY_OUTPUT) != 0U &&
        supervisor.privacy_output_healthy) {
        status = gumi_capture_privacy_output_failed(
            &supervisor, at_ms, &result
        );
        if (status == GUMI_CAPTURE_STATUS_OK) {
            drive_capture_result(at_ms, &result);
        } else {
            terminal_fail_closed(error);
        }
        return;
    }
    if ((faults & GUMI_ASYNC_BUTTON) != 0U) {
        capture_ports_ready = false;
        if (!pipeline_transition_can_fail()) {
            publish_status();
            return;
        }
        status = gumi_capture_recoverable_pipeline_failed(
            &supervisor,
            at_ms,
            GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE,
            &result
        );
        if (status != GUMI_CAPTURE_STATUS_OK) {
            terminal_fail_closed(error);
        }
        drive_capture_result(at_ms, &result);
        return;
    }
    if ((faults & ~(unsigned int)GUMI_ASYNC_AUXILIARY_IO) == 0U) {
        publish_status();
        return;
    }
    if (!pipeline_transition_can_fail()) {
        publish_status();
        return;
    }
    if ((faults & GUMI_ASYNC_STORAGE) != 0U) {
        status = gumi_capture_set_storage_state(
            &supervisor,
            at_ms,
            error == -ENOSPC ? GUMI_CAPTURE_STORAGE_FULL
                             : GUMI_CAPTURE_STORAGE_CORRUPT,
            &result
        );
    } else {
        status = gumi_capture_recoverable_pipeline_failed(
            &supervisor,
            at_ms,
            (faults & GUMI_ASYNC_MICROPHONE) != 0U
                ? GUMI_CAPTURE_REASON_MICROPHONE_UNAVAILABLE
                : GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE,
            &result
        );
    }
    if (status != GUMI_CAPTURE_STATUS_OK) {
        terminal_fail_closed(error);
    }
    drive_capture_result(at_ms, &result);
}

static void request_capture_from_double_tap(uint64_t at_ms)
{
    gumi_capture_result result;
    gumi_capture_status status;

    if (!capture_ports_ready || supervisor.maintenance_exclusive) {
        (void)gumi_omi_v3012_functional_haptic_play(
            GUMI_CAPTURE_HAPTIC_REFUSED
        );
        return;
    }
    if (supervisor.phase == GUMI_CAPTURE_PHASE_BASE_ACTIVE) {
        status = gumi_capture_request_base_stop(
            &supervisor, at_ms, &result
        );
    } else if (supervisor.phase == GUMI_CAPTURE_PHASE_IDLE) {
        status = gumi_capture_request_base_start(
            &supervisor, at_ms, &result
        );
    } else {
        status = GUMI_CAPTURE_STATUS_INVALID_STATE;
    }
    if (status != GUMI_CAPTURE_STATUS_OK) {
        (void)gumi_omi_v3012_functional_haptic_play(
            GUMI_CAPTURE_HAPTIC_REFUSED
        );
        return;
    }
    drive_capture_result(at_ms, &result);
}

static void request_unauthenticated_voice(uint64_t at_ms)
{
    const gumi_capture_realtime_admission admission = {
        .authenticated = false,
        .token = UINT64_C(0),
        .expires_at_ms = UINT64_C(0),
    };
    gumi_capture_result result;

    if (gumi_capture_request_voice_start(
            &supervisor, at_ms, &admission, &result
        ) == GUMI_CAPTURE_STATUS_OK) {
        drive_capture_result(at_ms, &result);
    } else {
        (void)gumi_omi_v3012_functional_haptic_play(
            GUMI_CAPTURE_HAPTIC_REFUSED
        );
    }
}

static void accept_button_events(
    const gumi_button_event_batch *events,
    uint64_t at_ms
)
{
    size_t index;

    for (index = 0U; index < events->count; index += 1U) {
        switch (events->events[index].type) {
            case GUMI_BUTTON_EVENT_SINGLE_TAP:
                requested_status =
                    GUMI_FEEDBACK_PATTERN_READY_LINK_STATUS;
                requested_status_until_ms =
                    at_ms + GUMI_STATUS_PULSE_MS;
                break;
            case GUMI_BUTTON_EVENT_DOUBLE_TAP:
                request_capture_from_double_tap(at_ms);
                break;
            case GUMI_BUTTON_EVENT_HOLD_COMMITTED:
                request_unauthenticated_voice(at_ms);
                break;
            case GUMI_BUTTON_EVENT_HOLD_RELEASED:
            case GUMI_BUTTON_EVENT_PHYSICAL_CONFIRMATION:
                break;
            default:
                terminal_fail_closed(-EPROTO);
        }
    }
}

static void poll_button(uint64_t at_ms, bool raw_pressed)
{
    gumi_button_edge_result edge;
    gumi_button_event_batch events;
    gumi_button_level level = raw_pressed ? GUMI_BUTTON_LEVEL_PRESSED
                                         : GUMI_BUTTON_LEVEL_RELEASED;

    if (gumi_button_debouncer_on_raw_level(
            &button_debouncer, at_ms, level, &edge
        ) != GUMI_BUTTON_STATUS_OK) {
        terminal_fail_closed(-EPROTO);
    }
    if (edge.emitted) {
        if (gumi_button_gesture_accept_edge(
                &button_gesture, edge.at_ms, edge.level, &events
            ) != GUMI_BUTTON_STATUS_OK) {
            terminal_fail_closed(-EPROTO);
        }
        accept_button_events(&events, at_ms);
    }
    if (gumi_button_gesture_advance_to(
            &button_gesture, at_ms, &events
        ) != GUMI_BUTTON_STATUS_OK) {
        terminal_fail_closed(-EPROTO);
    }
    accept_button_events(&events, at_ms);
}

/*
 * A sealed unit without SWD must retain a device-local route back to the
 * recovery image even when it cannot be power-cycled. A deliberate five-second
 * hold while capture is already idle admits image 0 and makes maintenance
 * exclusive until reboot. Keeping the button held for twelve seconds total,
 * or making a new twelve-second hold after admission, forces a cold reboot.
 * The ordinary 500 ms hold remains refused because v1 has no authenticated
 * VoiceTurn route.
 */
static void poll_runtime_update_confirmation(
    uint64_t at_ms,
    bool raw_pressed
)
{
    gumi_capture_result result;

    if (update_physically_confirmed) {
        runtime_update_hold_tracking = false;
        if (!raw_pressed) {
            runtime_reset_hold_tracking = false;
            return;
        }
        if (!runtime_reset_hold_tracking) {
            runtime_reset_hold_tracking = true;
            runtime_reset_hold_started_ms = at_ms;
            return;
        }
        if (at_ms - runtime_reset_hold_started_ms >=
            GUMI_RUNTIME_RESET_HOLD_MS) {
            (void)gumi_omi_v3012_functional_privacy_set(false);
            gumi_omi_v3012_whole_device_reboot();
        }
        return;
    }
    runtime_reset_hold_tracking = false;
    if (!raw_pressed || !capture_is_idle_for_update()) {
        runtime_update_hold_tracking = false;
        return;
    }
    if (!runtime_update_hold_tracking) {
        runtime_update_hold_tracking = true;
        runtime_update_hold_started_ms = at_ms;
        return;
    }
    if (at_ms - runtime_update_hold_started_ms <
        GUMI_RUNTIME_UPDATE_HOLD_MS) {
        return;
    }

    runtime_reset_hold_tracking = true;
    runtime_reset_hold_started_ms = runtime_update_hold_started_ms;
    runtime_update_hold_tracking = false;
    update_physically_confirmed = true;
    if (supervisor.power == GUMI_CAPTURE_POWER_OPERATIONAL &&
        !supervisor.maintenance_exclusive) {
        if (gumi_capture_set_maintenance_exclusive(
                &supervisor, at_ms, true, &result
            ) != GUMI_CAPTURE_STATUS_OK) {
            terminal_fail_closed(-EPROTO);
        }
        drive_capture_result(at_ms, &result);
    } else {
        /*
         * A missing HUK or storage failure leaves the supervisor in BOOTING,
         * but microphone-off truth still makes recovery maintenance safe.
         */
        publish_status();
    }
}

static void apply_feedback(uint64_t at_ms)
{
    gumi_feedback_input input;
    gumi_feedback_decision decision;

    if (requested_status != GUMI_FEEDBACK_PATTERN_NONE &&
        at_ms >= requested_status_until_ms) {
        requested_status = GUMI_FEEDBACK_PATTERN_NONE;
    }
    memset(&input, 0, sizeof(input));
    input.capture = &supervisor;
    input.maintenance = update_physically_confirmed
        ? GUMI_FEEDBACK_MAINTENANCE_UPDATING
        : capture_ports_ready
            ? GUMI_FEEDBACK_MAINTENANCE_NORMAL
            : GUMI_FEEDBACK_MAINTENANCE_RECOVERY_REQUIRED;
    input.power_level = GUMI_FEEDBACK_POWER_NORMAL;
    input.requested_status = requested_status;
    input.recoverable_warning = recoverable_warning;
    if (gumi_feedback_decide(&input, &decision) !=
        GUMI_FEEDBACK_STATUS_OK) {
        latch_error(GUMI_ASYNC_PRIVACY_OUTPUT, -EPROTO);
        return;
    }
    if (decision.status == GUMI_FEEDBACK_DECISION_SELECTED ||
        decision.status == GUMI_FEEDBACK_DECISION_NO_OUTPUT) {
        if (gumi_omi_v3012_functional_feedback_apply(
                decision.selected, at_ms
            ) < 0) {
            latch_error(
                decision.selected ==
                        GUMI_FEEDBACK_PATTERN_PRIVACY_RECORDING ||
                    decision.selected ==
                        GUMI_FEEDBACK_PATTERN_PRIVACY_VOICE_TURN ||
                    decision.selected ==
                        GUMI_FEEDBACK_PATTERN_PRIVACY_UNKNOWN
                    ? GUMI_ASYNC_PRIVACY_OUTPUT
                    : GUMI_ASYNC_AUXILIARY_IO,
                -EIO
            );
        }
    } else if (decision.status ==
               GUMI_FEEDBACK_DECISION_FATAL_PRIVACY_OUTPUT_UNAVAILABLE) {
        return;
    } else {
        latch_error(GUMI_ASYNC_PRIVACY_OUTPUT, -EPROTO);
    }
}

static bool detect_boot_update_confirmation(void)
{
    uint64_t started_at_ms;
    bool pressed = false;

    if (gumi_omi_v3012_functional_button_pressed(&pressed) < 0 ||
        !pressed) {
        return false;
    }
    started_at_ms = monotonic_milliseconds();
    while (monotonic_milliseconds() - started_at_ms <
           GUMI_BUTTON_CONFIRMATION_HOLD_MS) {
        uint64_t now_ms = monotonic_milliseconds();

        (void)gumi_omi_v3012_functional_feedback_apply(
            GUMI_FEEDBACK_PATTERN_UPDATING, now_ms
        );
        feed_main_watchdog();
        k_msleep(GUMI_UPDATE_CONFIRMATION_POLL_MS);
        if (gumi_omi_v3012_functional_button_pressed(&pressed) < 0 ||
            !pressed) {
            (void)gumi_omi_v3012_functional_privacy_set(false);
            return false;
        }
    }
    return true;
}

int main(void)
{
    gumi_capture_result result;
    gumi_button_gesture_config gesture_config;
    gumi_button_level initial_level;
    psa_key_id_t recording_key = PSA_KEY_ID_NULL;
    bool raw_pressed = false;
    bool microphone_off;
    int crypto_error;
    int io_error;
    int mic_error;
    int codec_error;
    int watchdog_error;
    int key_error;
    int storage_error = -ENODEV;
    int transport_error;

    if (gumi_capture_supervisor_init(&supervisor) !=
        GUMI_CAPTURE_STATUS_OK) {
        terminal_fail_closed(-EPROTO);
    }
    watchdog_error = start_main_watchdog();
    io_error = gumi_omi_v3012_functional_io_init();
    mic_error = gumi_omi_v3012_mic_init(
        submit_microphone_frame,
        microphone_fault,
        NULL,
        0U
    );
    microphone_off = mic_error == 0 &&
        gumi_omi_v3012_mic_get_truth() ==
            GUMI_OMI_V3012_MIC_VERIFIED_OFF;
    if (io_error == 0 && microphone_off) {
        update_physically_confirmed =
            detect_boot_update_confirmation();
    }
    codec_error = gumi_omi_v3012_codec_init(
        store_opus_packet,
        codec_fault,
        NULL
    );
    feed_main_watchdog();
    /*
     * Crypto availability is independent of whether this consumer unit has a
     * provisioned recording root. MCUmgr integrity and future non-keyed crypto
     * users must remain usable when recording deliberately fails closed.
     */
    crypto_error = gumi_omi_v3012_crypto_init();
    feed_main_watchdog();
    key_error = crypto_error == 0
        ? gumi_omi_v3012_recording_key_open()
        : crypto_error;
    feed_main_watchdog();
    if (key_error == 0) {
        key_error = gumi_omi_v3012_recording_key_borrow(&recording_key);
    }
    if (key_error == 0) {
        storage_error = gumi_omi_v3012_recording_storage_init(
            recording_key,
            GUMI_OMI_V3012_RECORDING_KEY_VERSION,
            GUMI_RECORDING_MINIMUM_FREE_BYTES,
            storage_fault,
            NULL
        );
    }
    feed_main_watchdog();
    capture_ports_ready =
        io_error == 0 && microphone_off && codec_error == 0 &&
        watchdog_error == 0 && key_error == 0 && storage_error == 0;
    if (!capture_ports_ready) {
        recoverable_warning = true;
        (void)atomic_cas(
            &first_error,
            0,
            (atomic_val_t)(
                io_error < 0 ? io_error :
                mic_error < 0 ? mic_error :
                codec_error < 0 ? codec_error :
                watchdog_error < 0 ? watchdog_error :
                key_error < 0 ? key_error : storage_error
            )
        );
    } else if (gumi_capture_required_self_tests_passed(
            &supervisor,
            monotonic_milliseconds(),
            &result
        ) != GUMI_CAPTURE_STATUS_OK) {
        terminal_fail_closed(-EPROTO);
    } else {
        drive_capture_result(monotonic_milliseconds(), &result);
        if (update_physically_confirmed &&
            gumi_capture_set_maintenance_exclusive(
                &supervisor,
                monotonic_milliseconds(),
                true,
                &result
            ) != GUMI_CAPTURE_STATUS_OK) {
            terminal_fail_closed(-EPROTO);
        }
    }

    transport_error = gumi_omi_v3012_functional_transport_start();
    feed_main_watchdog();
    transport_ready = transport_error == 0;
    if (transport_error < 0) {
        (void)atomic_cas(
            &first_error, 0, (atomic_val_t)transport_error
        );
        recoverable_warning = true;
    }
    if (gumi_omi_v3012_functional_button_pressed(&raw_pressed) < 0) {
        raw_pressed = false;
        latch_error(GUMI_ASYNC_BUTTON, -EIO);
    }
    initial_level = raw_pressed ? GUMI_BUTTON_LEVEL_PRESSED
                                : GUMI_BUTTON_LEVEL_RELEASED;
    if (gumi_button_debouncer_init(
            &button_debouncer,
            GUMI_BUTTON_DEBOUNCE_MS,
            initial_level
        ) != GUMI_BUTTON_STATUS_OK) {
        terminal_fail_closed(-EPROTO);
    }
    memset(&gesture_config, 0, sizeof(gesture_config));
    gesture_config.context = update_physically_confirmed
        ? GUMI_BUTTON_CONTEXT_MAINTENANCE_EXCLUSIVE
        : GUMI_BUTTON_CONTEXT_NORMAL;
    if (gumi_button_gesture_init(&button_gesture, &gesture_config) !=
        GUMI_BUTTON_STATUS_OK) {
        terminal_fail_closed(-EPROTO);
    }
    publish_status();

    for (;;) {
        uint64_t now_ms = monotonic_milliseconds();

        feed_main_watchdog();
        if (gumi_omi_v3012_functional_button_pressed(&raw_pressed) < 0) {
            latch_error(GUMI_ASYNC_BUTTON, -EIO);
            raw_pressed = false;
        }
        poll_button(now_ms, raw_pressed);
        poll_runtime_update_confirmation(now_ms, raw_pressed);
        handle_async_faults(now_ms);
        apply_feedback(now_ms);
        k_msleep(GUMI_MAIN_POLL_MS);
    }
}
