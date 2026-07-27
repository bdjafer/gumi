#include "gumi/capture.h"

#include <string.h>

enum {
    STOP_NONE = 0,
    STOP_BASE_USER,
    STOP_BASE_DURABILITY,
    STOP_VOICE_IDLE_ACTIVE,
    STOP_VOICE_OVERLAY_ACTIVE,
    STOP_VOICE_IDLE_CANCELLED,
    STOP_VOICE_OVERLAY_CANCELLED,
    STOP_BASE_START_CANCELLED,
    STOP_SAFETY_PRIVACY,
};

static bool completion_is_valid(gumi_capture_completion completion)
{
    return completion >= GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED &&
           completion <= GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED;
}

static bool storage_is_valid(gumi_capture_storage_state storage)
{
    return storage >= GUMI_CAPTURE_STORAGE_HEALTHY &&
           storage <= GUMI_CAPTURE_STORAGE_CORRUPT;
}

static void clear_result(gumi_capture_result *result)
{
    memset(result, 0, sizeof(*result));
    result->haptic = GUMI_CAPTURE_HAPTIC_NONE;
}

static gumi_capture_status emit_action(
    gumi_capture_result *result,
    uint64_t at_ms,
    uint64_t transition_id,
    gumi_capture_action_type type
)
{
    gumi_capture_action *action;

    if (result->action_count >= GUMI_CAPTURE_ACTION_CAPACITY) {
        return GUMI_CAPTURE_STATUS_OUTPUT_OVERFLOW;
    }
    action = &result->actions[result->action_count];
    result->action_count += 1U;
    action->at_ms = at_ms;
    action->transition_id = transition_id;
    action->type = type;
    return GUMI_CAPTURE_STATUS_OK;
}

static gumi_capture_status emit_event(
    gumi_capture_result *result,
    uint64_t at_ms,
    uint64_t transition_id,
    uint64_t recording_id,
    gumi_capture_event_type type,
    gumi_capture_reason reason
)
{
    gumi_capture_event *event;

    if (result->event_count >= GUMI_CAPTURE_EVENT_CAPACITY) {
        return GUMI_CAPTURE_STATUS_OUTPUT_OVERFLOW;
    }
    event = &result->events[result->event_count];
    result->event_count += 1U;
    event->at_ms = at_ms;
    event->transition_id = transition_id;
    event->recording_id = recording_id;
    event->type = type;
    event->reason = reason;
    return GUMI_CAPTURE_STATUS_OK;
}

static gumi_capture_status allocate_transition(gumi_capture_supervisor *state)
{
    if (state->next_transition_id == UINT64_C(0)) {
        return GUMI_CAPTURE_STATUS_COUNTER_EXHAUSTED;
    }
    state->transition_id = state->next_transition_id;
    state->next_transition_id += UINT64_C(1);
    return GUMI_CAPTURE_STATUS_OK;
}

static void clear_start_flags(gumi_capture_supervisor *state)
{
    state->microphone_acquired = false;
    state->local_durability_ready = false;
    state->realtime_route_ready = false;
    state->realtime_action_issued = false;
    state->voice_admission_expires_at_ms = UINT64_C(0);
}

static void clear_stop_flags(gumi_capture_supervisor *state)
{
    state->stop_kind = STOP_NONE;
    state->stop_reason = GUMI_CAPTURE_REASON_NONE;
    state->stop_wait_finalize = false;
    state->stop_wait_durable_boundary = false;
    state->stop_wait_realtime_close = false;
    state->stop_wait_microphone_release = false;
    state->stop_was_base_active = false;
    state->stop_was_voice_active = false;
    state->stopping_recording_id = UINT64_C(0);
}

static gumi_capture_status validate_state(const gumi_capture_supervisor *state)
{
    if (!state->initialized) {
        return GUMI_CAPTURE_STATUS_INVALID_CONFIGURATION;
    }
    if (state->base_audio_permitted &&
        (!state->base_recording_active || state->mic_truth != GUMI_CAPTURE_MIC_ACQUIRED)) {
        return GUMI_CAPTURE_STATUS_INVALID_CONFIGURATION;
    }
    if (state->voice_audio_permitted &&
        (state->voice != GUMI_CAPTURE_VOICE_ACTIVE || state->mic_truth != GUMI_CAPTURE_MIC_ACQUIRED)) {
        return GUMI_CAPTURE_STATUS_INVALID_CONFIGURATION;
    }
    if (state->base_recording_active != (state->active_recording_id != UINT64_C(0))) {
        return GUMI_CAPTURE_STATUS_INVALID_CONFIGURATION;
    }
    if (state->power == GUMI_CAPTURE_POWER_BOOTING &&
        (state->mic_truth != GUMI_CAPTURE_MIC_VERIFIED_OFF ||
         state->base_recording_active || state->voice != GUMI_CAPTURE_VOICE_INACTIVE)) {
        return GUMI_CAPTURE_STATUS_INVALID_CONFIGURATION;
    }
    if (state->mic_truth != GUMI_CAPTURE_MIC_VERIFIED_OFF &&
        state->privacy_output_healthy && !state->privacy_guard_asserted) {
        return GUMI_CAPTURE_STATUS_INVALID_CONFIGURATION;
    }
    if (state->maintenance_exclusive &&
        state->phase != GUMI_CAPTURE_PHASE_IDLE && state->phase != GUMI_CAPTURE_PHASE_FATAL_IDLE) {
        return GUMI_CAPTURE_STATUS_INVALID_CONFIGURATION;
    }
    return GUMI_CAPTURE_STATUS_OK;
}

static gumi_capture_status validate_call(
    const gumi_capture_supervisor *state,
    uint64_t at_ms,
    const gumi_capture_result *result
)
{
    gumi_capture_status status;

    if (state == NULL || result == NULL) {
        return GUMI_CAPTURE_STATUS_INVALID_ARGUMENT;
    }
    status = validate_state(state);
    if (status != GUMI_CAPTURE_STATUS_OK) {
        return status;
    }
    if (at_ms < state->current_ms) {
        return GUMI_CAPTURE_STATUS_TIME_REGRESSION;
    }
    return GUMI_CAPTURE_STATUS_OK;
}

static gumi_capture_status commit_state(
    gumi_capture_supervisor *state,
    const gumi_capture_supervisor *next,
    gumi_capture_result *result,
    const gumi_capture_result *next_result
)
{
    gumi_capture_status status = validate_state(next);
    if (status != GUMI_CAPTURE_STATUS_OK) {
        return status;
    }
    *state = *next;
    *result = *next_result;
    return GUMI_CAPTURE_STATUS_OK;
}

gumi_capture_status gumi_capture_supervisor_init(gumi_capture_supervisor *state)
{
    if (state == NULL) {
        return GUMI_CAPTURE_STATUS_INVALID_ARGUMENT;
    }
    memset(state, 0, sizeof(*state));
    state->next_transition_id = UINT64_C(1);
    state->next_recording_id = UINT64_C(1);
    state->power = GUMI_CAPTURE_POWER_BOOTING;
    state->mic_truth = GUMI_CAPTURE_MIC_VERIFIED_OFF;
    state->voice = GUMI_CAPTURE_VOICE_INACTIVE;
    state->storage = GUMI_CAPTURE_STORAGE_HEALTHY;
    state->fault = GUMI_CAPTURE_FAULT_NONE;
    state->phase = GUMI_CAPTURE_PHASE_BOOTING;
    state->privacy_output_healthy = false;
    state->initialized = true;
    return validate_state(state);
}

gumi_capture_status gumi_capture_required_self_tests_passed(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
)
{
    gumi_capture_supervisor next;
    gumi_capture_result next_result;
    gumi_capture_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_STATUS_OK) {
        return status;
    }
    if (state->phase != GUMI_CAPTURE_PHASE_BOOTING ||
        state->mic_truth != GUMI_CAPTURE_MIC_VERIFIED_OFF) {
        return GUMI_CAPTURE_STATUS_INVALID_STATE;
    }

    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    next.power = GUMI_CAPTURE_POWER_OPERATIONAL;
    next.phase = GUMI_CAPTURE_PHASE_IDLE;
    next.privacy_output_healthy = true;
    status = emit_event(
        &next_result,
        at_ms,
        0U,
        0U,
        GUMI_CAPTURE_EVENT_BOOT_READY,
        GUMI_CAPTURE_REASON_NONE
    );
    if (status != GUMI_CAPTURE_STATUS_OK) {
        return status;
    }
    next_result.haptic = GUMI_CAPTURE_HAPTIC_READY;
    return commit_state(state, &next, result, &next_result);
}

gumi_capture_status gumi_capture_set_maintenance_exclusive(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    bool exclusive,
    gumi_capture_result *result
)
{
    gumi_capture_supervisor next;
    gumi_capture_result next_result;
    gumi_capture_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_STATUS_OK) {
        return status;
    }
    if (state->power != GUMI_CAPTURE_POWER_OPERATIONAL ||
        state->mic_truth != GUMI_CAPTURE_MIC_VERIFIED_OFF ||
        state->base_recording_active || state->voice != GUMI_CAPTURE_VOICE_INACTIVE ||
        (state->phase != GUMI_CAPTURE_PHASE_IDLE && state->phase != GUMI_CAPTURE_PHASE_FATAL_IDLE)) {
        return GUMI_CAPTURE_STATUS_INVALID_STATE;
    }
    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    next.maintenance_exclusive = exclusive;
    return commit_state(state, &next, result, &next_result);
}

static gumi_capture_status begin_guarded_start(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_phase phase,
    gumi_capture_result *result
)
{
    gumi_capture_status status = allocate_transition(state);
    if (status != GUMI_CAPTURE_STATUS_OK) {
        return status;
    }
    clear_start_flags(state);
    state->phase = phase;
    return emit_action(
        result,
        at_ms,
        state->transition_id,
        GUMI_CAPTURE_ACTION_ASSERT_PRIVACY_GUARD
    );
}

gumi_capture_status gumi_capture_request_base_start(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
)
{
    gumi_capture_supervisor next;
    gumi_capture_result next_result;
    gumi_capture_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_STATUS_OK) {
        return status;
    }
    if (state->phase != GUMI_CAPTURE_PHASE_IDLE || state->maintenance_exclusive ||
        state->mic_truth != GUMI_CAPTURE_MIC_VERIFIED_OFF || state->base_recording_active ||
        state->voice != GUMI_CAPTURE_VOICE_INACTIVE || !state->privacy_output_healthy ||
        state->fault == GUMI_CAPTURE_FAULT_FATAL_PRIVACY ||
        state->storage == GUMI_CAPTURE_STORAGE_FULL ||
        state->storage == GUMI_CAPTURE_STORAGE_CORRUPT) {
        return GUMI_CAPTURE_STATUS_INVALID_STATE;
    }

    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    status = begin_guarded_start(
        &next,
        at_ms,
        GUMI_CAPTURE_PHASE_STARTING_BASE,
        &next_result
    );
    if (status != GUMI_CAPTURE_STATUS_OK) {
        return status;
    }
    return commit_state(state, &next, result, &next_result);
}

static bool admission_is_valid_at(
    const gumi_capture_realtime_admission *admission,
    uint64_t at_ms
)
{
    return admission != NULL && admission->authenticated && admission->token != UINT64_C(0) &&
           at_ms < admission->expires_at_ms;
}

gumi_capture_status gumi_capture_request_voice_start(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    const gumi_capture_realtime_admission *admission,
    gumi_capture_result *result
)
{
    gumi_capture_supervisor next;
    gumi_capture_result next_result;
    gumi_capture_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_STATUS_OK) {
        return status;
    }
    if (admission == NULL) {
        return GUMI_CAPTURE_STATUS_INVALID_ARGUMENT;
    }
    if (state->maintenance_exclusive || state->voice != GUMI_CAPTURE_VOICE_INACTIVE ||
        state->fault == GUMI_CAPTURE_FAULT_FATAL_PRIVACY ||
        (state->phase != GUMI_CAPTURE_PHASE_IDLE &&
         state->phase != GUMI_CAPTURE_PHASE_BASE_ACTIVE)) {
        return GUMI_CAPTURE_STATUS_INVALID_STATE;
    }

    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    if (!admission_is_valid_at(admission, at_ms)) {
        status = emit_event(
            &next_result,
            at_ms,
            0U,
            next.active_recording_id,
            GUMI_CAPTURE_EVENT_VOICE_TURN_REFUSED,
            admission->authenticated && admission->token != 0U
                ? GUMI_CAPTURE_REASON_REALTIME_ADMISSION_EXPIRED
                : GUMI_CAPTURE_REASON_REALTIME_ADMISSION_UNAVAILABLE
        );
        if (status != GUMI_CAPTURE_STATUS_OK) {
            return status;
        }
        next_result.haptic = GUMI_CAPTURE_HAPTIC_REFUSED;
        return commit_state(state, &next, result, &next_result);
    }

    next.voice_admission_expires_at_ms = admission->expires_at_ms;
    next.voice = GUMI_CAPTURE_VOICE_STARTING;
    if (next.phase == GUMI_CAPTURE_PHASE_BASE_ACTIVE) {
        status = allocate_transition(&next);
        if (status != GUMI_CAPTURE_STATUS_OK) {
            return status;
        }
        clear_start_flags(&next);
        next.voice_admission_expires_at_ms = admission->expires_at_ms;
        next.realtime_action_issued = true;
        next.phase = GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY;
        status = emit_action(
            &next_result,
            at_ms,
            next.transition_id,
            GUMI_CAPTURE_ACTION_OPEN_REALTIME_ROUTE
        );
    } else {
        if (!next.privacy_output_healthy ||
            next.storage == GUMI_CAPTURE_STORAGE_FULL ||
            next.storage == GUMI_CAPTURE_STORAGE_CORRUPT) {
            return GUMI_CAPTURE_STATUS_INVALID_STATE;
        }
        status = begin_guarded_start(
            &next,
            at_ms,
            GUMI_CAPTURE_PHASE_STARTING_VOICE_IDLE,
            &next_result
        );
        next.voice_admission_expires_at_ms = admission->expires_at_ms;
    }
    if (status != GUMI_CAPTURE_STATUS_OK) {
        return status;
    }
    return commit_state(state, &next, result, &next_result);
}

static gumi_capture_status start_stop(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    unsigned int stop_kind,
    gumi_capture_reason reason,
    bool allocate_new_transition,
    gumi_capture_result *result
)
{
    gumi_capture_status status;

    if (allocate_new_transition) {
        status = allocate_transition(state);
        if (status != GUMI_CAPTURE_STATUS_OK) {
            return status;
        }
    }
    clear_stop_flags(state);
    state->phase = GUMI_CAPTURE_PHASE_STOPPING;
    state->stop_kind = stop_kind;
    state->stop_reason = reason;
    state->stop_was_base_active = state->base_recording_active;
    state->stop_was_voice_active = state->voice == GUMI_CAPTURE_VOICE_ACTIVE;
    state->stopping_recording_id = state->active_recording_id;
    state->voice_audio_permitted = false;

    switch (stop_kind) {
        case STOP_BASE_USER:
            state->base_audio_permitted = false;
            state->mic_truth = GUMI_CAPTURE_MIC_RELEASING;
            state->stop_wait_finalize = true;
            return emit_action(
                result,
                at_ms,
                state->transition_id,
                GUMI_CAPTURE_ACTION_FINALIZE_LOCAL_RECORDING
            );
        case STOP_BASE_DURABILITY:
            state->base_audio_permitted = false;
            state->mic_truth = GUMI_CAPTURE_MIC_RELEASING;
            state->stop_wait_durable_boundary = true;
            status = emit_action(
                result,
                at_ms,
                state->transition_id,
                GUMI_CAPTURE_ACTION_COMMIT_LAST_DURABLE_FRAME
            );
            if (status != GUMI_CAPTURE_STATUS_OK) return status;
            if (state->voice != GUMI_CAPTURE_VOICE_INACTIVE || state->realtime_action_issued) {
                state->voice = GUMI_CAPTURE_VOICE_ENDING;
                state->stop_wait_realtime_close = true;
                return emit_action(
                    result,
                    at_ms,
                    state->transition_id,
                    GUMI_CAPTURE_ACTION_CLOSE_REALTIME_ROUTE
                );
            }
            return GUMI_CAPTURE_STATUS_OK;
        case STOP_VOICE_IDLE_ACTIVE:
        case STOP_VOICE_IDLE_CANCELLED:
            state->mic_truth = GUMI_CAPTURE_MIC_RELEASING;
            state->voice = GUMI_CAPTURE_VOICE_ENDING;
            state->stop_wait_realtime_close = true;
            state->stop_wait_microphone_release = true;
            status = emit_action(
                result,
                at_ms,
                state->transition_id,
                GUMI_CAPTURE_ACTION_CLOSE_REALTIME_ROUTE
            );
            if (status != GUMI_CAPTURE_STATUS_OK) return status;
            status = emit_action(
                result,
                at_ms,
                state->transition_id,
                GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE
            );
            if (status != GUMI_CAPTURE_STATUS_OK) return status;
            if (!state->local_recording_open) {
                return GUMI_CAPTURE_STATUS_OK;
            }
            if (stop_kind == STOP_VOICE_IDLE_ACTIVE &&
                reason == GUMI_CAPTURE_REASON_NONE) {
                state->stop_wait_finalize = true;
                return emit_action(
                    result,
                    at_ms,
                    state->transition_id,
                    GUMI_CAPTURE_ACTION_FINALIZE_LOCAL_RECORDING
                );
            }
            state->stop_wait_durable_boundary = true;
            return emit_action(
                result,
                at_ms,
                state->transition_id,
                GUMI_CAPTURE_ACTION_COMMIT_LAST_DURABLE_FRAME
            );
        case STOP_VOICE_OVERLAY_ACTIVE:
        case STOP_VOICE_OVERLAY_CANCELLED:
            state->voice = GUMI_CAPTURE_VOICE_ENDING;
            state->stop_wait_realtime_close = true;
            return emit_action(
                result,
                at_ms,
                state->transition_id,
                GUMI_CAPTURE_ACTION_CLOSE_REALTIME_ROUTE
            );
        case STOP_BASE_START_CANCELLED:
            state->mic_truth = GUMI_CAPTURE_MIC_RELEASING;
            state->stop_wait_microphone_release = true;
            status = emit_action(
                result,
                at_ms,
                state->transition_id,
                GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE
            );
            if (status != GUMI_CAPTURE_STATUS_OK ||
                !state->local_recording_open) {
                return status;
            }
            state->stop_wait_durable_boundary = true;
            return emit_action(
                result,
                at_ms,
                state->transition_id,
                GUMI_CAPTURE_ACTION_COMMIT_LAST_DURABLE_FRAME
            );
        case STOP_SAFETY_PRIVACY:
            state->base_audio_permitted = false;
            state->voice_audio_permitted = false;
            state->mic_truth = GUMI_CAPTURE_MIC_UNKNOWN;
            state->voice = state->voice == GUMI_CAPTURE_VOICE_INACTIVE
                ? GUMI_CAPTURE_VOICE_INACTIVE
                : GUMI_CAPTURE_VOICE_ENDING;
            state->stop_wait_microphone_release = true;
            if (state->realtime_action_issued || state->stop_was_voice_active) {
                state->stop_wait_realtime_close = true;
                status = emit_action(
                    result,
                    at_ms,
                    state->transition_id,
                    GUMI_CAPTURE_ACTION_CLOSE_REALTIME_ROUTE
                );
                if (status != GUMI_CAPTURE_STATUS_OK) return status;
            }
            status = emit_action(
                result,
                at_ms,
                state->transition_id,
                GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE
            );
            if (status != GUMI_CAPTURE_STATUS_OK ||
                !state->local_recording_open) {
                return status;
            }
            state->stop_wait_durable_boundary = true;
            return emit_action(
                result,
                at_ms,
                state->transition_id,
                GUMI_CAPTURE_ACTION_COMMIT_LAST_DURABLE_FRAME
            );
        default:
            return GUMI_CAPTURE_STATUS_INVALID_CONFIGURATION;
    }
}

gumi_capture_status gumi_capture_request_base_stop(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
)
{
    gumi_capture_supervisor next;
    gumi_capture_result next_result;
    gumi_capture_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    if (state->phase != GUMI_CAPTURE_PHASE_BASE_ACTIVE || !state->base_recording_active ||
        state->voice != GUMI_CAPTURE_VOICE_INACTIVE) {
        return GUMI_CAPTURE_STATUS_INVALID_STATE;
    }
    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    status = start_stop(
        &next,
        at_ms,
        STOP_BASE_USER,
        GUMI_CAPTURE_REASON_NONE,
        true,
        &next_result
    );
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    return commit_state(state, &next, result, &next_result);
}

static gumi_capture_status cancel_starting_base(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_reason reason,
    gumi_capture_result *result
)
{
    gumi_capture_status status;

    state->transition_id = UINT64_C(0);
    status = allocate_transition(state);
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    status = emit_event(
        result,
        at_ms,
        state->transition_id,
        0U,
        GUMI_CAPTURE_EVENT_BASE_RECORDING_REFUSED,
        reason
    );
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    result->haptic = GUMI_CAPTURE_HAPTIC_FAULT;
    return start_stop(
        state,
        at_ms,
        STOP_BASE_START_CANCELLED,
        reason,
        false,
        result
    );
}

static gumi_capture_status cancel_starting_voice(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_reason reason,
    bool emit_refusal,
    gumi_capture_result *result
)
{
    bool overlay = state->phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY;
    gumi_capture_status status;

    if (!overlay && !state->privacy_guard_asserted) {
        if (emit_refusal) {
            status = emit_event(
                result,
                at_ms,
                state->transition_id,
                0U,
                GUMI_CAPTURE_EVENT_VOICE_TURN_REFUSED,
                reason
            );
            if (status != GUMI_CAPTURE_STATUS_OK) return status;
            result->haptic = GUMI_CAPTURE_HAPTIC_REFUSED;
        }
        state->transition_id = UINT64_C(0);
        state->voice = GUMI_CAPTURE_VOICE_INACTIVE;
        state->phase = GUMI_CAPTURE_PHASE_IDLE;
        clear_start_flags(state);
        return GUMI_CAPTURE_STATUS_OK;
    }

    state->transition_id = UINT64_C(0);
    status = allocate_transition(state);
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    if (emit_refusal) {
        status = emit_event(
            result,
            at_ms,
            state->transition_id,
            state->active_recording_id,
            GUMI_CAPTURE_EVENT_VOICE_TURN_REFUSED,
            reason
        );
        if (status != GUMI_CAPTURE_STATUS_OK) return status;
        result->haptic = GUMI_CAPTURE_HAPTIC_REFUSED;
    }
    return start_stop(
        state,
        at_ms,
        overlay ? STOP_VOICE_OVERLAY_CANCELLED : STOP_VOICE_IDLE_CANCELLED,
        reason,
        false,
        result
    );
}

gumi_capture_status gumi_capture_request_voice_end(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
)
{
    gumi_capture_supervisor next;
    gumi_capture_result next_result;
    gumi_capture_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    switch (state->phase) {
        case GUMI_CAPTURE_PHASE_VOICE_IDLE_ACTIVE:
            status = start_stop(
                &next,
                at_ms,
                STOP_VOICE_IDLE_ACTIVE,
                GUMI_CAPTURE_REASON_NONE,
                true,
                &next_result
            );
            break;
        case GUMI_CAPTURE_PHASE_VOICE_OVERLAY_ACTIVE:
            status = start_stop(
                &next,
                at_ms,
                STOP_VOICE_OVERLAY_ACTIVE,
                GUMI_CAPTURE_REASON_NONE,
                true,
                &next_result
            );
            break;
        case GUMI_CAPTURE_PHASE_STARTING_VOICE_IDLE:
        case GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY:
            status = cancel_starting_voice(
                &next,
                at_ms,
                GUMI_CAPTURE_REASON_NONE,
                false,
                &next_result
            );
            break;
        default:
            return GUMI_CAPTURE_STATUS_INVALID_STATE;
    }
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    return commit_state(state, &next, result, &next_result);
}

static gumi_capture_status complete_start_if_ready(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
)
{
    gumi_capture_status status;
    bool ready = false;

    switch (state->phase) {
        case GUMI_CAPTURE_PHASE_STARTING_BASE:
            ready = state->privacy_guard_asserted && state->microphone_acquired &&
                state->local_durability_ready;
            break;
        case GUMI_CAPTURE_PHASE_STARTING_VOICE_IDLE:
            ready = state->privacy_guard_asserted && state->microphone_acquired &&
                state->local_durability_ready && state->realtime_route_ready;
            break;
        case GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY:
            ready = state->realtime_route_ready;
            break;
        default:
            return GUMI_CAPTURE_STATUS_INVALID_STATE;
    }
    if (!ready) return GUMI_CAPTURE_STATUS_OK;

    if (state->phase != GUMI_CAPTURE_PHASE_STARTING_BASE &&
        at_ms >= state->voice_admission_expires_at_ms) {
        return cancel_starting_voice(
            state,
            at_ms,
            GUMI_CAPTURE_REASON_REALTIME_ADMISSION_EXPIRED,
            true,
            result
        );
    }

    if (state->phase == GUMI_CAPTURE_PHASE_STARTING_BASE) {
        if (state->next_recording_id == UINT64_C(0)) {
            return GUMI_CAPTURE_STATUS_COUNTER_EXHAUSTED;
        }
        state->active_recording_id = state->next_recording_id;
        state->next_recording_id += UINT64_C(1);
        state->base_recording_active = true;
        state->base_audio_permitted = true;
        state->phase = GUMI_CAPTURE_PHASE_BASE_ACTIVE;
        status = emit_event(
            result,
            at_ms,
            state->transition_id,
            state->active_recording_id,
            GUMI_CAPTURE_EVENT_BASE_RECORDING_STARTED,
            GUMI_CAPTURE_REASON_NONE
        );
        result->haptic = GUMI_CAPTURE_HAPTIC_RECORDING_STARTED;
    } else if (state->phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_IDLE) {
        state->voice = GUMI_CAPTURE_VOICE_ACTIVE;
        state->voice_audio_permitted = true;
        state->phase = GUMI_CAPTURE_PHASE_VOICE_IDLE_ACTIVE;
        status = emit_event(
            result,
            at_ms,
            state->transition_id,
            0U,
            GUMI_CAPTURE_EVENT_VOICE_TURN_STARTED,
            GUMI_CAPTURE_REASON_NONE
        );
        result->haptic = GUMI_CAPTURE_HAPTIC_VOICE_READY;
    } else {
        state->voice = GUMI_CAPTURE_VOICE_ACTIVE;
        state->voice_audio_permitted = true;
        state->phase = GUMI_CAPTURE_PHASE_VOICE_OVERLAY_ACTIVE;
        status = emit_event(
            result,
            at_ms,
            state->transition_id,
            state->active_recording_id,
            GUMI_CAPTURE_EVENT_VOICE_TURN_STARTED,
            GUMI_CAPTURE_REASON_NONE
        );
        result->haptic = GUMI_CAPTURE_HAPTIC_VOICE_READY;
    }
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    state->transition_id = UINT64_C(0);
    clear_start_flags(state);
    return GUMI_CAPTURE_STATUS_OK;
}

static gumi_capture_status refuse_guarded_start(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
)
{
    gumi_capture_event_type type = state->phase == GUMI_CAPTURE_PHASE_STARTING_BASE
        ? GUMI_CAPTURE_EVENT_BASE_RECORDING_REFUSED
        : GUMI_CAPTURE_EVENT_VOICE_TURN_REFUSED;
    gumi_capture_status status;

    state->privacy_output_healthy = false;
    state->privacy_guard_asserted = false;
    state->fault = GUMI_CAPTURE_FAULT_FATAL_PRIVACY;
    state->mic_truth = GUMI_CAPTURE_MIC_VERIFIED_OFF;
    state->voice = GUMI_CAPTURE_VOICE_INACTIVE;
    state->phase = GUMI_CAPTURE_PHASE_FATAL_IDLE;
    status = emit_event(
        result,
        at_ms,
        state->transition_id,
        state->active_recording_id,
        type,
        GUMI_CAPTURE_REASON_PRIVACY_OUTPUT_UNAVAILABLE
    );
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    result->haptic = GUMI_CAPTURE_HAPTIC_FAULT;
    state->transition_id = UINT64_C(0);
    clear_start_flags(state);
    return GUMI_CAPTURE_STATUS_OK;
}

static bool stop_is_complete(const gumi_capture_supervisor *state)
{
    return !state->stop_wait_finalize && !state->stop_wait_durable_boundary &&
           !state->stop_wait_realtime_close && !state->stop_wait_microphone_release;
}

static gumi_capture_status ensure_microphone_release_requested(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
)
{
    if (state->mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF ||
        state->stop_wait_microphone_release) {
        return GUMI_CAPTURE_STATUS_OK;
    }
    state->stop_wait_microphone_release = true;
    state->mic_truth = GUMI_CAPTURE_MIC_RELEASING;
    return emit_action(
        result,
        at_ms,
        state->transition_id,
        GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE
    );
}

static gumi_capture_status finish_stop(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
)
{
    gumi_capture_status status;
    unsigned int kind = state->stop_kind;
    bool preserve_base = kind == STOP_VOICE_OVERLAY_ACTIVE ||
        kind == STOP_VOICE_OVERLAY_CANCELLED;

    if (state->stop_was_base_active && !preserve_base) {
        status = emit_event(
            result,
            at_ms,
            state->transition_id,
            state->stopping_recording_id,
            GUMI_CAPTURE_EVENT_BASE_RECORDING_STOPPED,
            state->stop_reason
        );
        if (status != GUMI_CAPTURE_STATUS_OK) return status;
        if (kind == STOP_BASE_USER &&
            state->stop_reason == GUMI_CAPTURE_REASON_NONE) {
            result->haptic = GUMI_CAPTURE_HAPTIC_RECORDING_STOPPED;
        } else if (kind == STOP_BASE_DURABILITY || kind == STOP_SAFETY_PRIVACY) {
            result->haptic = GUMI_CAPTURE_HAPTIC_FAULT;
        } else if (state->stop_reason != GUMI_CAPTURE_REASON_NONE) {
            result->haptic = GUMI_CAPTURE_HAPTIC_FAULT;
        }
    }
    if (state->stop_was_voice_active) {
        status = emit_event(
            result,
            at_ms,
            state->transition_id,
            state->stopping_recording_id,
            GUMI_CAPTURE_EVENT_VOICE_TURN_ENDED,
            state->stop_reason
        );
        if (status != GUMI_CAPTURE_STATUS_OK) return status;
    }
    if (!state->stop_was_base_active &&
        (state->stop_reason == GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE ||
         state->stop_reason == GUMI_CAPTURE_REASON_STORAGE_CORRUPT ||
         state->stop_reason == GUMI_CAPTURE_REASON_PRIVACY_OUTPUT_UNAVAILABLE)) {
        result->haptic = GUMI_CAPTURE_HAPTIC_FAULT;
    }

    state->voice = GUMI_CAPTURE_VOICE_INACTIVE;
    state->voice_audio_permitted = false;
    state->local_recording_open = false;
    if (preserve_base) {
        state->phase = GUMI_CAPTURE_PHASE_BASE_ACTIVE;
        state->mic_truth = GUMI_CAPTURE_MIC_ACQUIRED;
        state->base_audio_permitted = true;
    } else {
        state->base_recording_active = false;
        state->active_recording_id = UINT64_C(0);
        state->base_audio_permitted = false;
        if (state->mic_truth != GUMI_CAPTURE_MIC_VERIFIED_OFF) {
            return GUMI_CAPTURE_STATUS_INVALID_CONFIGURATION;
        }
        state->phase = state->fault == GUMI_CAPTURE_FAULT_FATAL_PRIVACY
            ? GUMI_CAPTURE_PHASE_FATAL_IDLE
            : GUMI_CAPTURE_PHASE_IDLE;
    }
    state->transition_id = UINT64_C(0);
    clear_start_flags(state);
    clear_stop_flags(state);
    return GUMI_CAPTURE_STATUS_OK;
}

static gumi_capture_status complete_stop(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_completion completion,
    gumi_capture_result *result
)
{
    gumi_capture_status status;

    switch (completion) {
        case GUMI_CAPTURE_COMPLETION_LOCAL_RECORDING_FINALIZED:
            if (!state->stop_wait_finalize) return GUMI_CAPTURE_STATUS_INVALID_STATE;
            state->stop_wait_finalize = false;
            state->local_recording_open = false;
            status = ensure_microphone_release_requested(
                state, at_ms, result
            );
            if (status != GUMI_CAPTURE_STATUS_OK) return status;
            break;
        case GUMI_CAPTURE_COMPLETION_LOCAL_RECORDING_INTERRUPTED:
            if (!state->stop_wait_finalize) return GUMI_CAPTURE_STATUS_INVALID_STATE;
            state->stop_wait_finalize = false;
            state->local_recording_open = false;
            state->stop_reason =
                GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE;
            state->fault = GUMI_CAPTURE_FAULT_RECOVERABLE;
            status = ensure_microphone_release_requested(
                state, at_ms, result
            );
            if (status != GUMI_CAPTURE_STATUS_OK) return status;
            break;
        case GUMI_CAPTURE_COMPLETION_LAST_DURABLE_FRAME_COMMITTED:
            if (!state->stop_wait_durable_boundary) return GUMI_CAPTURE_STATUS_INVALID_STATE;
            state->stop_wait_durable_boundary = false;
            state->local_recording_open = false;
            status = ensure_microphone_release_requested(
                state, at_ms, result
            );
            if (status != GUMI_CAPTURE_STATUS_OK) return status;
            break;
        case GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_CLOSED:
            if (!state->stop_wait_realtime_close) return GUMI_CAPTURE_STATUS_INVALID_STATE;
            state->stop_wait_realtime_close = false;
            state->realtime_route_ready = false;
            break;
        case GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED:
            if (!state->stop_wait_microphone_release) return GUMI_CAPTURE_STATUS_INVALID_STATE;
            state->stop_wait_microphone_release = false;
            state->microphone_acquired = false;
            state->mic_truth = GUMI_CAPTURE_MIC_VERIFIED_OFF;
            state->base_audio_permitted = false;
            state->voice_audio_permitted = false;
            state->base_recording_active = false;
            state->active_recording_id = UINT64_C(0);
            if (state->privacy_guard_asserted && state->privacy_output_healthy) {
                status = emit_action(
                    result,
                    at_ms,
                    state->transition_id,
                    GUMI_CAPTURE_ACTION_DEASSERT_PRIVACY_GUARD
                );
                if (status != GUMI_CAPTURE_STATUS_OK) return status;
            }
            state->privacy_guard_asserted = false;
            break;
        default:
            return GUMI_CAPTURE_STATUS_INVALID_STATE;
    }

    if (stop_is_complete(state)) {
        return finish_stop(state, at_ms, result);
    }
    return GUMI_CAPTURE_STATUS_OK;
}

gumi_capture_status gumi_capture_complete(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    uint64_t transition_id,
    gumi_capture_completion completion,
    gumi_capture_result *result
)
{
    gumi_capture_supervisor next;
    gumi_capture_result next_result;
    gumi_capture_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    if (!completion_is_valid(completion)) return GUMI_CAPTURE_STATUS_INVALID_ARGUMENT;
    if (transition_id == UINT64_C(0) || transition_id != state->transition_id) {
        return GUMI_CAPTURE_STATUS_STALE_TRANSITION;
    }

    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    if (next.phase == GUMI_CAPTURE_PHASE_STOPPING) {
        status = complete_stop(&next, at_ms, completion, &next_result);
    } else if (next.phase == GUMI_CAPTURE_PHASE_STARTING_BASE ||
               next.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_IDLE ||
               next.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY) {
        switch (completion) {
            case GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_FAILED:
                if (next.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY) {
                    return GUMI_CAPTURE_STATUS_INVALID_STATE;
                }
                status = refuse_guarded_start(&next, at_ms, &next_result);
                break;
            case GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED:
                if (next.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY ||
                    next.privacy_guard_asserted) {
                    return GUMI_CAPTURE_STATUS_INVALID_STATE;
                }
                next.privacy_guard_asserted = true;
                next.mic_truth = GUMI_CAPTURE_MIC_ACQUIRING;
                status = emit_action(
                    &next_result,
                    at_ms,
                    next.transition_id,
                    GUMI_CAPTURE_ACTION_PREPARE_LOCAL_DURABILITY
                );
                if (status != GUMI_CAPTURE_STATUS_OK) return status;
                status = emit_action(
                    &next_result,
                    at_ms,
                    next.transition_id,
                    GUMI_CAPTURE_ACTION_ACQUIRE_MICROPHONE
                );
                if (status != GUMI_CAPTURE_STATUS_OK) return status;
                if (next.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_IDLE) {
                    next.realtime_action_issued = true;
                    status = emit_action(
                        &next_result,
                        at_ms,
                        next.transition_id,
                        GUMI_CAPTURE_ACTION_OPEN_REALTIME_ROUTE
                    );
                }
                break;
            case GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRED:
                if (next.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY ||
                    !next.privacy_guard_asserted || next.microphone_acquired) {
                    return GUMI_CAPTURE_STATUS_INVALID_STATE;
                }
                next.microphone_acquired = true;
                next.mic_truth = GUMI_CAPTURE_MIC_ACQUIRED;
                status = GUMI_CAPTURE_STATUS_OK;
                break;
            case GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRE_FAILED:
                if (next.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY ||
                    !next.privacy_guard_asserted || next.microphone_acquired ||
                    next.mic_truth != GUMI_CAPTURE_MIC_ACQUIRING) {
                    return GUMI_CAPTURE_STATUS_INVALID_STATE;
                }
                next.fault = GUMI_CAPTURE_FAULT_RECOVERABLE;
                status = next.phase == GUMI_CAPTURE_PHASE_STARTING_BASE
                    ? cancel_starting_base(
                        &next,
                        at_ms,
                        GUMI_CAPTURE_REASON_MICROPHONE_UNAVAILABLE,
                        &next_result
                    )
                    : cancel_starting_voice(
                        &next,
                        at_ms,
                        GUMI_CAPTURE_REASON_MICROPHONE_UNAVAILABLE,
                        true,
                        &next_result
                    );
                break;
            case GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_READY:
                if (next.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY ||
                    !next.privacy_guard_asserted || next.local_durability_ready) {
                    return GUMI_CAPTURE_STATUS_INVALID_STATE;
                }
                next.local_durability_ready = true;
                next.local_recording_open = true;
                status = GUMI_CAPTURE_STATUS_OK;
                break;
            case GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_FAILED:
                if (next.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY ||
                    !next.privacy_guard_asserted || next.local_durability_ready) {
                    return GUMI_CAPTURE_STATUS_INVALID_STATE;
                }
                next.fault = GUMI_CAPTURE_FAULT_RECOVERABLE;
                status = next.phase == GUMI_CAPTURE_PHASE_STARTING_BASE
                    ? cancel_starting_base(
                        &next,
                        at_ms,
                        GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE,
                        &next_result
                    )
                    : cancel_starting_voice(
                        &next,
                        at_ms,
                        GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE,
                        true,
                        &next_result
                    );
                break;
            case GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_READY:
                if (next.phase == GUMI_CAPTURE_PHASE_STARTING_BASE ||
                    !next.realtime_action_issued || next.realtime_route_ready) {
                    return GUMI_CAPTURE_STATUS_INVALID_STATE;
                }
                next.realtime_route_ready = true;
                status = GUMI_CAPTURE_STATUS_OK;
                break;
            case GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_FAILED:
                if (next.phase == GUMI_CAPTURE_PHASE_STARTING_BASE ||
                    !next.realtime_action_issued || next.realtime_route_ready) {
                    return GUMI_CAPTURE_STATUS_INVALID_STATE;
                }
                next.fault = GUMI_CAPTURE_FAULT_RECOVERABLE;
                status = cancel_starting_voice(
                    &next,
                    at_ms,
                    GUMI_CAPTURE_REASON_REALTIME_ROUTE_UNAVAILABLE,
                    true,
                    &next_result
                );
                break;
            default:
                return GUMI_CAPTURE_STATUS_INVALID_STATE;
        }
        if (status == GUMI_CAPTURE_STATUS_OK &&
            (completion == GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED ||
             completion == GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRED ||
             completion == GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_READY ||
             completion == GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_READY)) {
            status = complete_start_if_ready(&next, at_ms, &next_result);
        }
    } else {
        return GUMI_CAPTURE_STATUS_INVALID_STATE;
    }
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    return commit_state(state, &next, result, &next_result);
}

static gumi_capture_status begin_recoverable_pipeline_stop(
    gumi_capture_supervisor *next,
    uint64_t at_ms,
    gumi_capture_reason reason,
    gumi_capture_result *result
)
{
    gumi_capture_status status;

    next->fault = GUMI_CAPTURE_FAULT_RECOVERABLE;
    if (next->phase == GUMI_CAPTURE_PHASE_BASE_ACTIVE ||
        next->phase == GUMI_CAPTURE_PHASE_VOICE_OVERLAY_ACTIVE ||
        next->phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY) {
        status = start_stop(
            next,
            at_ms,
            STOP_BASE_DURABILITY,
            reason,
            true,
            result
        );
        if (status != GUMI_CAPTURE_STATUS_OK) return status;
        return emit_event(
            result,
            at_ms,
            next->transition_id,
            next->stopping_recording_id,
            GUMI_CAPTURE_EVENT_SAFE_CAPTURE_STOP_REQUESTED,
            reason
        );
    }
    if (next->phase == GUMI_CAPTURE_PHASE_VOICE_IDLE_ACTIVE) {
        status = start_stop(
            next,
            at_ms,
            STOP_VOICE_IDLE_ACTIVE,
            reason,
            true,
            result
        );
        if (status != GUMI_CAPTURE_STATUS_OK) return status;
        return emit_event(
            result,
            at_ms,
            next->transition_id,
            0U,
            GUMI_CAPTURE_EVENT_SAFE_CAPTURE_STOP_REQUESTED,
            reason
        );
    }
    if (next->phase == GUMI_CAPTURE_PHASE_STARTING_BASE) {
        status = emit_event(
            result,
            at_ms,
            next->transition_id,
            0U,
            GUMI_CAPTURE_EVENT_BASE_RECORDING_REFUSED,
            reason
        );
        if (status != GUMI_CAPTURE_STATUS_OK) return status;
        result->haptic = GUMI_CAPTURE_HAPTIC_FAULT;
        if (next->privacy_guard_asserted) {
            next->transition_id = UINT64_C(0);
            status = allocate_transition(next);
            if (status != GUMI_CAPTURE_STATUS_OK) return status;
            status = start_stop(
                next,
                at_ms,
                STOP_BASE_START_CANCELLED,
                reason,
                false,
                result
            );
            if (status != GUMI_CAPTURE_STATUS_OK) return status;
        } else {
            next->transition_id = UINT64_C(0);
            next->phase = GUMI_CAPTURE_PHASE_IDLE;
            clear_start_flags(next);
        }
        return GUMI_CAPTURE_STATUS_OK;
    }
    if (next->phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_IDLE) {
        status = cancel_starting_voice(next, at_ms, reason, true, result);
        if (status != GUMI_CAPTURE_STATUS_OK) return status;
        result->haptic = GUMI_CAPTURE_HAPTIC_FAULT;
    }
    return GUMI_CAPTURE_STATUS_OK;
}

gumi_capture_status gumi_capture_set_storage_state(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_storage_state storage,
    gumi_capture_result *result
)
{
    gumi_capture_supervisor next;
    gumi_capture_result next_result;
    gumi_capture_reason reason;
    gumi_capture_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    if (!storage_is_valid(storage)) return GUMI_CAPTURE_STATUS_INVALID_ARGUMENT;
    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    next.storage = storage;
    if (storage == GUMI_CAPTURE_STORAGE_FULL ||
        storage == GUMI_CAPTURE_STORAGE_CORRUPT) {
        reason = storage == GUMI_CAPTURE_STORAGE_CORRUPT
            ? GUMI_CAPTURE_REASON_STORAGE_CORRUPT
            : GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE;
        status = begin_recoverable_pipeline_stop(
            &next,
            at_ms,
            reason,
            &next_result
        );
        if (status != GUMI_CAPTURE_STATUS_OK) return status;
    }
    return commit_state(state, &next, result, &next_result);
}

gumi_capture_status gumi_capture_recoverable_pipeline_failed(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_reason reason,
    gumi_capture_result *result
)
{
    gumi_capture_supervisor next;
    gumi_capture_result next_result;
    gumi_capture_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    if (reason != GUMI_CAPTURE_REASON_MICROPHONE_UNAVAILABLE &&
        reason != GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE) {
        return GUMI_CAPTURE_STATUS_INVALID_ARGUMENT;
    }
    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    status = begin_recoverable_pipeline_stop(
        &next,
        at_ms,
        reason,
        &next_result
    );
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    return commit_state(state, &next, result, &next_result);
}

gumi_capture_status gumi_capture_privacy_output_failed(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
)
{
    gumi_capture_supervisor next;
    gumi_capture_result next_result;
    gumi_capture_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    if (!state->privacy_output_healthy) return GUMI_CAPTURE_STATUS_INVALID_STATE;
    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    next.privacy_output_healthy = false;
    next.fault = GUMI_CAPTURE_FAULT_FATAL_PRIVACY;

    if (next.mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF &&
        next.phase != GUMI_CAPTURE_PHASE_STARTING_BASE &&
        next.phase != GUMI_CAPTURE_PHASE_STARTING_VOICE_IDLE) {
        next.phase = GUMI_CAPTURE_PHASE_FATAL_IDLE;
        return commit_state(state, &next, result, &next_result);
    }
    if (next.phase == GUMI_CAPTURE_PHASE_STARTING_BASE ||
        next.phase == GUMI_CAPTURE_PHASE_STARTING_VOICE_IDLE) {
        if (!next.privacy_guard_asserted) {
            status = refuse_guarded_start(&next, at_ms, &next_result);
            if (status != GUMI_CAPTURE_STATUS_OK) return status;
            return commit_state(state, &next, result, &next_result);
        }
    }

    next.transition_id = UINT64_C(0);
    status = allocate_transition(&next);
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    status = start_stop(
        &next,
        at_ms,
        STOP_SAFETY_PRIVACY,
        GUMI_CAPTURE_REASON_PRIVACY_OUTPUT_UNAVAILABLE,
        false,
        &next_result
    );
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    status = emit_event(
        &next_result,
        at_ms,
        next.transition_id,
        next.stopping_recording_id,
        GUMI_CAPTURE_EVENT_SAFE_CAPTURE_STOP_REQUESTED,
        GUMI_CAPTURE_REASON_PRIVACY_OUTPUT_UNAVAILABLE
    );
    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    return commit_state(state, &next, result, &next_result);
}

gumi_capture_status gumi_capture_watchdog_boot(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
)
{
    gumi_capture_supervisor next;
    gumi_capture_result next_result;
    uint64_t interrupted_recording_id;
    bool interrupted;
    gumi_capture_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_STATUS_OK) return status;
    next = *state;
    clear_result(&next_result);
    interrupted_recording_id = next.active_recording_id != 0U
        ? next.active_recording_id
        : next.stopping_recording_id;
    interrupted = next.mic_truth != GUMI_CAPTURE_MIC_VERIFIED_OFF ||
        next.base_recording_active || next.local_recording_open ||
        next.voice != GUMI_CAPTURE_VOICE_INACTIVE;

    next.current_ms = at_ms;
    next.transition_id = 0U;
    next.power = GUMI_CAPTURE_POWER_BOOTING;
    next.mic_truth = GUMI_CAPTURE_MIC_VERIFIED_OFF;
    next.voice = GUMI_CAPTURE_VOICE_INACTIVE;
    next.phase = GUMI_CAPTURE_PHASE_BOOTING;
    next.base_recording_active = false;
    next.active_recording_id = 0U;
    next.base_audio_permitted = false;
    next.voice_audio_permitted = false;
    next.privacy_output_healthy = false;
    next.privacy_guard_asserted = false;
    next.local_recording_open = false;
    next.fault = GUMI_CAPTURE_FAULT_NONE;
    next.maintenance_exclusive = false;
    clear_start_flags(&next);
    clear_stop_flags(&next);
    if (interrupted) {
        status = emit_event(
            &next_result,
            at_ms,
            0U,
            interrupted_recording_id,
            GUMI_CAPTURE_EVENT_CAPTURE_DISCONTINUITY,
            GUMI_CAPTURE_REASON_WATCHDOG_RESET
        );
        if (status != GUMI_CAPTURE_STATUS_OK) return status;
    }
    return commit_state(state, &next, result, &next_result);
}

bool gumi_capture_base_audio_is_permitted(const gumi_capture_supervisor *state)
{
    return state != NULL && state->initialized && state->base_audio_permitted;
}

bool gumi_capture_voice_audio_is_permitted(const gumi_capture_supervisor *state)
{
    return state != NULL && state->initialized && state->voice_audio_permitted;
}

gumi_capture_privacy_pattern gumi_capture_privacy_pattern_for(
    const gumi_capture_supervisor *state
)
{
    if (state == NULL || !state->initialized) {
        return GUMI_CAPTURE_PRIVACY_OUTPUT_UNAVAILABLE;
    }
    if (!state->privacy_output_healthy && state->fault == GUMI_CAPTURE_FAULT_FATAL_PRIVACY) {
        return GUMI_CAPTURE_PRIVACY_OUTPUT_UNAVAILABLE;
    }
    if (state->mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF) {
        return GUMI_CAPTURE_PRIVACY_OFF;
    }
    if (state->mic_truth == GUMI_CAPTURE_MIC_UNKNOWN) {
        return GUMI_CAPTURE_PRIVACY_UNKNOWN;
    }
    if (state->voice == GUMI_CAPTURE_VOICE_ACTIVE ||
        state->voice == GUMI_CAPTURE_VOICE_ENDING ||
        (state->voice == GUMI_CAPTURE_VOICE_STARTING && !state->base_recording_active)) {
        return GUMI_CAPTURE_PRIVACY_VOICE_TURN;
    }
    return GUMI_CAPTURE_PRIVACY_RECORDING;
}
