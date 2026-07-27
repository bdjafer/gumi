#include "gumi/semantic_signal.h"

#include <limits.h>
#include <string.h>

static bool valid_kind(gumi_semantic_signal_kind kind)
{
    return kind == GUMI_SEMANTIC_SIGNAL_INTERPRETATION;
}

static gumi_semantic_signal_status validate(
    const gumi_semantic_signal_tracker *state,
    uint64_t at_ms
)
{
    if (state == NULL || !state->initialized) {
        return GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_ARGUMENT;
    }
    if (at_ms < state->current_ms) {
        return GUMI_SEMANTIC_SIGNAL_STATUS_TIME_REGRESSION;
    }
    return GUMI_SEMANTIC_SIGNAL_STATUS_OK;
}

static void emit_close(
    gumi_semantic_signal_tracker *state,
    uint64_t at_ms,
    gumi_semantic_signal_event_type type,
    gumi_semantic_signal_event *event
)
{
    event->type = type;
    event->kind = state->active_kind;
    event->signal_id = state->active_signal_id;
    event->recording_id = state->active_recording_id;
    event->started_at_ms = state->active_started_at_ms;
    event->ended_at_ms = at_ms;
    state->current_ms = at_ms;
    state->active_signal_id = 0U;
    state->active_recording_id = 0U;
    state->active_started_at_ms = 0U;
    state->active = false;
}

gumi_semantic_signal_status gumi_semantic_signal_init(
    gumi_semantic_signal_tracker *state
)
{
    if (state == NULL) {
        return GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_ARGUMENT;
    }
    memset(state, 0, sizeof(*state));
    state->next_signal_id = 1U;
    state->initialized = true;
    return GUMI_SEMANTIC_SIGNAL_STATUS_OK;
}

gumi_semantic_signal_status gumi_semantic_signal_begin(
    gumi_semantic_signal_tracker *state,
    uint64_t at_ms,
    uint64_t recording_id,
    gumi_semantic_signal_kind kind,
    gumi_semantic_signal_event *event
)
{
    gumi_semantic_signal_status status = validate(state, at_ms);

    if (status != GUMI_SEMANTIC_SIGNAL_STATUS_OK || event == NULL ||
        recording_id == 0U || !valid_kind(kind)) {
        return status == GUMI_SEMANTIC_SIGNAL_STATUS_OK
            ? GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_ARGUMENT
            : status;
    }
    if (state->active) {
        return GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_STATE;
    }
    if (state->next_signal_id == 0U || state->next_signal_id == UINT64_MAX) {
        return GUMI_SEMANTIC_SIGNAL_STATUS_COUNTER_EXHAUSTED;
    }
    memset(event, 0, sizeof(*event));
    state->current_ms = at_ms;
    state->active_signal_id = state->next_signal_id;
    state->next_signal_id += 1U;
    state->active_recording_id = recording_id;
    state->active_started_at_ms = at_ms;
    state->active_kind = kind;
    state->active = true;
    event->type = GUMI_SEMANTIC_SIGNAL_EVENT_STARTED;
    event->kind = kind;
    event->signal_id = state->active_signal_id;
    event->recording_id = recording_id;
    event->started_at_ms = at_ms;
    event->ended_at_ms = at_ms;
    return GUMI_SEMANTIC_SIGNAL_STATUS_OK;
}

gumi_semantic_signal_status gumi_semantic_signal_end(
    gumi_semantic_signal_tracker *state,
    uint64_t at_ms,
    gumi_semantic_signal_event *event
)
{
    gumi_semantic_signal_status status = validate(state, at_ms);

    if (status != GUMI_SEMANTIC_SIGNAL_STATUS_OK || event == NULL) {
        return status == GUMI_SEMANTIC_SIGNAL_STATUS_OK
            ? GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_ARGUMENT
            : status;
    }
    if (!state->active) {
        return GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_STATE;
    }
    memset(event, 0, sizeof(*event));
    emit_close(state, at_ms, GUMI_SEMANTIC_SIGNAL_EVENT_ENDED, event);
    return GUMI_SEMANTIC_SIGNAL_STATUS_OK;
}

gumi_semantic_signal_status gumi_semantic_signal_recording_closed(
    gumi_semantic_signal_tracker *state,
    uint64_t at_ms,
    uint64_t recording_id,
    gumi_semantic_signal_event *event
)
{
    gumi_semantic_signal_status status = validate(state, at_ms);

    if (status != GUMI_SEMANTIC_SIGNAL_STATUS_OK || event == NULL ||
        recording_id == 0U) {
        return status == GUMI_SEMANTIC_SIGNAL_STATUS_OK
            ? GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_ARGUMENT
            : status;
    }
    if (!state->active || state->active_recording_id != recording_id) {
        return GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_STATE;
    }
    memset(event, 0, sizeof(*event));
    emit_close(
        state, at_ms, GUMI_SEMANTIC_SIGNAL_EVENT_INTERRUPTED, event
    );
    return GUMI_SEMANTIC_SIGNAL_STATUS_OK;
}
