#include "gumi/button.h"
#include "gumi/capture_selftest.h"
#include "gumi/omi_v3012_capture_selftest.h"
#include "gumi/omi_v3012_codec.h"
#include "gumi/omi_v3012_mic.h"

#include <errno.h>
#include <limits.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/kernel.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/util.h>

#define GUMI_MAIN_POLL_MS 10

static gumi_capture_selftest supervisor;
static gumi_button_debouncer button_debouncer;
static gumi_button_gesture button_gesture;
static atomic_t pcm_blocks = ATOMIC_INIT(0);
static atomic_t pcm_samples = ATOMIC_INIT(0);
static atomic_t opus_packets = ATOMIC_INIT(0);
static atomic_t async_error = ATOMIC_INIT(0);
static uint64_t active_session_id;
static bool gesture_active;
static bool ports_ready;

static uint64_t monotonic_milliseconds(void)
{
    int64_t now = k_uptime_get();

    return now > 0 ? (uint64_t)now : UINT64_C(0);
}

static uint32_t narrow_u64(uint64_t value)
{
    return value > UINT32_MAX ? UINT32_MAX : (uint32_t)value;
}

static void latch_async_error(int error)
{
    int latched = error < 0 ? error : -EIO;

    (void)atomic_cas(&async_error, 0, (atomic_val_t)latched);
}

static void discard_opus_packet(
    const gumi_omi_v3012_codec_packet *packet,
    void *context
)
{
    ARG_UNUSED(context);
    if (packet == NULL || packet->bytes == NULL || packet->size == 0U ||
        packet->size > GUMI_OMI_V3012_CODEC_MAX_PACKET_BYTES ||
        packet->session_id != active_session_id) {
        latch_async_error(-EBADMSG);
        return;
    }
    (void)atomic_inc(&opus_packets);
}

static void submit_microphone_frame(
    const int16_t *samples,
    size_t sample_count,
    void *context
)
{
    int error;

    ARG_UNUSED(context);
    if (active_session_id == UINT64_C(0) || samples == NULL || sample_count == 0U ||
        sample_count > (size_t)INT_MAX) {
        latch_async_error(-EINVAL);
        return;
    }
    error = gumi_omi_v3012_codec_submit_pcm(
        active_session_id,
        samples,
        sample_count
    );
    if (error < 0) {
        latch_async_error(error);
        return;
    }
    (void)atomic_inc(&pcm_blocks);
    (void)atomic_add(&pcm_samples, (atomic_val_t)sample_count);
}

static void microphone_fault(int error, void *context)
{
    ARG_UNUSED(context);
    latch_async_error(error);
}

static void codec_fault(uint64_t session_id, int error, void *context)
{
    ARG_UNUSED(context);
    if (session_id == active_session_id) {
        latch_async_error(error);
    }
}

static void publish_status(void)
{
    (void)gumi_omi_v3012_capture_selftest_status_publish(&supervisor);
}

static void terminal_fail_closed(void)
{
    gumi_capture_selftest fallback;

    ports_ready = false;
    gesture_active = false;
    active_session_id = UINT64_C(0);
    (void)gumi_omi_v3012_capture_selftest_privacy_set(true);
    (void)gumi_omi_v3012_mic_release();
    if (gumi_capture_selftest_init(&fallback, false, true) ==
        GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        (void)gumi_omi_v3012_capture_selftest_status_publish(&fallback);
    }
    for (;;) {
        k_sleep(K_FOREVER);
    }
}

static void fill_close_evidence(
    const gumi_omi_v3012_codec_close_result *close_result,
    gumi_capture_selftest_evidence *evidence
)
{
    memset(evidence, 0, sizeof(*evidence));
    evidence->pcm_blocks = (uint32_t)atomic_get(&pcm_blocks);
    evidence->pcm_samples = narrow_u64(close_result->submitted_samples);
    evidence->opus_packets = narrow_u64(close_result->emitted_packets);
    evidence->discarded_samples = narrow_u64(close_result->discarded_samples);
    evidence->terminal_error = (int32_t)close_result->terminal_error;
}

static void drive_actions(
    uint64_t at_ms,
    gumi_capture_selftest_result *result
)
{
    while (result->has_action) {
        gumi_capture_selftest_action action = result->action;
        gumi_capture_selftest_result next_result;
        gumi_capture_selftest_evidence evidence;
        gumi_omi_v3012_codec_close_result close_result;
        const gumi_capture_selftest_evidence *evidence_pointer = NULL;
        bool async_failure = false;
        bool success = false;
        int error = 0;

        memset(&evidence, 0, sizeof(evidence));
        memset(&close_result, 0, sizeof(close_result));
        switch (action.type) {
        case GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY:
            error = gumi_omi_v3012_capture_selftest_privacy_set(true);
            success = error == 0;
            if (!success) {
                ports_ready = false;
            }
            break;

        case GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC:
            atomic_set(&pcm_blocks, 0);
            atomic_set(&pcm_samples, 0);
            atomic_set(&opus_packets, 0);
            atomic_set(&async_error, 0);
            active_session_id = (uint64_t)supervisor.attempt;
            error = gumi_omi_v3012_codec_open(active_session_id);
            success = error == 0 &&
                      gumi_omi_v3012_codec_get_truth() ==
                          GUMI_OMI_V3012_CODEC_ACTIVE;
            if (!success) {
                active_session_id = UINT64_C(0);
                ports_ready = false;
            }
            break;

        case GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE:
            error = gumi_omi_v3012_mic_acquire();
            success = error == 0 &&
                      gumi_omi_v3012_mic_get_truth() ==
                          GUMI_OMI_V3012_MIC_RUNNING;
            if (!success &&
                gumi_omi_v3012_mic_get_truth() !=
                    GUMI_OMI_V3012_MIC_VERIFIED_OFF) {
                async_failure = true;
                ports_ready = false;
            }
            break;

        case GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE:
            error = gumi_omi_v3012_mic_release();
            success = error == 0 &&
                      gumi_omi_v3012_mic_get_truth() ==
                          GUMI_OMI_V3012_MIC_VERIFIED_OFF;
            break;

        case GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN:
        case GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DISCARD:
            error = gumi_omi_v3012_codec_close(
                active_session_id,
                action.type == GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN
                    ? GUMI_OMI_V3012_CODEC_CLOSE_DRAIN_COMPLETE
                    : GUMI_OMI_V3012_CODEC_CLOSE_DISCARD,
                &close_result
            );
            fill_close_evidence(&close_result, &evidence);
            if (error < 0 && evidence.terminal_error == 0) {
                evidence.terminal_error = (int32_t)error;
            }
            success = gumi_omi_v3012_codec_get_truth() ==
                      GUMI_OMI_V3012_CODEC_CLOSED;
            if (!success) {
                ports_ready = false;
            }
            evidence_pointer = &evidence;
            active_session_id = UINT64_C(0);
            break;

        case GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY:
            error = gumi_omi_v3012_capture_selftest_privacy_set(false);
            success = error == 0;
            if (!success) {
                ports_ready = false;
            }
            break;

        default:
            terminal_fail_closed();
        }

        if (async_failure) {
            if (gumi_capture_selftest_async_port_failed(
                    &supervisor, at_ms, &next_result
                ) != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
                terminal_fail_closed();
            }
        } else {
            if (gumi_capture_selftest_complete(
                    &supervisor,
                    at_ms,
                    action.transition_id,
                    action.type,
                    success,
                    evidence_pointer,
                    &next_result
                ) != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
                terminal_fail_closed();
            }
        }
        *result = next_result;
        publish_status();
    }
}

static void accept_gesture_events(
    const gumi_button_event_batch *events,
    uint64_t at_ms,
    gumi_capture_selftest_result *result
)
{
    size_t index;

    for (index = 0U; index < events->count; index += 1U) {
        const gumi_button_event *event = &events->events[index];

        if (event->type == GUMI_BUTTON_EVENT_PHYSICAL_CONFIRMATION &&
            event->confirmation_operation_token == supervisor.attempt &&
            supervisor.phase == GUMI_CAPTURE_SELFTEST_PHASE_ARMED) {
            if (gumi_capture_selftest_confirm(&supervisor, at_ms, result) !=
                GUMI_CAPTURE_SELFTEST_STATUS_OK) {
                terminal_fail_closed();
            }
            gesture_active = false;
            publish_status();
            drive_actions(at_ms, result);
        }
    }
}

static void poll_button(
    uint64_t at_ms,
    bool raw_pressed,
    gumi_capture_selftest_result *result
)
{
    gumi_button_edge_result edge;
    gumi_button_event_batch events;
    gumi_button_level level = raw_pressed ? GUMI_BUTTON_LEVEL_PRESSED
                                         : GUMI_BUTTON_LEVEL_RELEASED;

    if (gumi_button_debouncer_on_raw_level(
            &button_debouncer,
            at_ms,
            level,
            &edge
        ) != GUMI_BUTTON_STATUS_OK) {
        terminal_fail_closed();
    }
    if (gesture_active && edge.emitted) {
        if (gumi_button_gesture_accept_edge(
                &button_gesture,
                edge.at_ms,
                edge.level,
                &events
            ) != GUMI_BUTTON_STATUS_OK) {
            terminal_fail_closed();
        }
        accept_gesture_events(&events, at_ms, result);
    }
    if (gesture_active) {
        if (gumi_button_gesture_advance_to(&button_gesture, at_ms, &events) !=
            GUMI_BUTTON_STATUS_OK) {
            terminal_fail_closed();
        }
        accept_gesture_events(&events, at_ms, result);
    }
}

static void handle_arm_request(
    uint64_t at_ms,
    bool raw_pressed
)
{
    gumi_button_gesture_config config;
    uint64_t expires_ms;

    if (!ports_ready || raw_pressed ||
        button_debouncer.accepted_level != GUMI_BUTTON_LEVEL_RELEASED ||
        UINT64_MAX - at_ms < UINT64_C(15000)) {
        return;
    }
    expires_ms = at_ms + UINT64_C(15000);
    if (gumi_capture_selftest_arm(&supervisor, at_ms, expires_ms) !=
        GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return;
    }
    config.context = GUMI_BUTTON_CONTEXT_AWAITING_CONFIRMATION;
    config.confirmation_operation_token = supervisor.attempt;
    config.confirmation_lease_expires_at_ms = expires_ms;
    if (gumi_button_gesture_init(&button_gesture, &config) != GUMI_BUTTON_STATUS_OK) {
        terminal_fail_closed();
    }
    gesture_active = true;
    publish_status();
}

int main(void)
{
    gumi_capture_selftest_result result;
    gumi_button_level initial_level;
    bool raw_pressed = false;
    bool microphone_off;
    int io_error;
    int mic_error;
    int codec_error;
    int transport_error;

    io_error = gumi_omi_v3012_capture_selftest_io_init();
    if (io_error < 0) {
        (void)gumi_omi_v3012_capture_selftest_privacy_set(true);
    }
    transport_error = gumi_omi_v3012_capture_selftest_transport_start();
    if (transport_error < 0) {
        terminal_fail_closed();
    }
    mic_error = gumi_omi_v3012_mic_init(
        submit_microphone_frame,
        microphone_fault,
        NULL,
        0U
    );
    microphone_off = mic_error == 0 &&
        gumi_omi_v3012_mic_get_truth() == GUMI_OMI_V3012_MIC_VERIFIED_OFF;
    if (!microphone_off) {
        (void)gumi_omi_v3012_capture_selftest_privacy_set(true);
    }
    codec_error = gumi_omi_v3012_codec_init(discard_opus_packet, codec_fault, NULL);
    if (gumi_capture_selftest_init(&supervisor, microphone_off, true) !=
        GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        terminal_fail_closed();
    }
    ports_ready = io_error == 0 && mic_error == 0 && codec_error == 0;
    if (!ports_ready && microphone_off) {
        if (gumi_capture_selftest_async_port_failed(
                &supervisor,
                monotonic_milliseconds(),
                &result
            ) != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
            terminal_fail_closed();
        }
    }
    if (gumi_omi_v3012_capture_selftest_button_pressed(&raw_pressed) < 0) {
        ports_ready = false;
        raw_pressed = false;
        if (supervisor.phase != GUMI_CAPTURE_SELFTEST_PHASE_FAILED_MICROPHONE_UNKNOWN &&
            gumi_capture_selftest_async_port_failed(
                &supervisor, monotonic_milliseconds(), &result
            ) != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
            terminal_fail_closed();
        }
    }
    initial_level = raw_pressed ? GUMI_BUTTON_LEVEL_PRESSED
                                : GUMI_BUTTON_LEVEL_RELEASED;
    if (gumi_button_debouncer_init(
            &button_debouncer,
            GUMI_BUTTON_DEBOUNCE_MS,
            initial_level
        ) != GUMI_BUTTON_STATUS_OK) {
        terminal_fail_closed();
    }
    publish_status();

    for (;;) {
        uint64_t now_ms = monotonic_milliseconds();
        gumi_capture_selftest_phase previous_phase = supervisor.phase;
        int pending_error;

        if (gumi_omi_v3012_capture_selftest_button_pressed(&raw_pressed) < 0) {
            latch_async_error(-EIO);
        }
        poll_button(now_ms, raw_pressed, &result);
        if (gumi_omi_v3012_capture_selftest_take_arm_request()) {
            handle_arm_request(now_ms, raw_pressed);
        }

        pending_error = atomic_get(&async_error);
        if (pending_error != 0 &&
            supervisor.phase != GUMI_CAPTURE_SELFTEST_PHASE_FAILED_MICROPHONE_UNKNOWN) {
            atomic_set(&async_error, 0);
            ports_ready = false;
            if (gumi_capture_selftest_async_port_failed(
                    &supervisor,
                    now_ms,
                    &result
                ) != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
                terminal_fail_closed();
            }
            gesture_active = false;
            publish_status();
            drive_actions(now_ms, &result);
        }

        if (!supervisor.action_outstanding &&
            (supervisor.phase == GUMI_CAPTURE_SELFTEST_PHASE_ARMED ||
             supervisor.phase == GUMI_CAPTURE_SELFTEST_PHASE_EXERCISING)) {
            if (gumi_capture_selftest_advance(&supervisor, now_ms, &result) !=
                GUMI_CAPTURE_SELFTEST_STATUS_OK) {
                terminal_fail_closed();
            }
            if (supervisor.phase != previous_phase || result.has_action) {
                if (supervisor.phase != GUMI_CAPTURE_SELFTEST_PHASE_ARMED) {
                    gesture_active = false;
                }
                publish_status();
                drive_actions(now_ms, &result);
            }
        }
        k_msleep(GUMI_MAIN_POLL_MS);
    }
}
