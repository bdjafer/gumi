#include "gumi/button.h"

#include <limits.h>
#include <string.h>

static bool level_is_valid(gumi_button_level level)
{
    return level == GUMI_BUTTON_LEVEL_RELEASED || level == GUMI_BUTTON_LEVEL_PRESSED;
}

static bool context_is_valid(gumi_button_context context)
{
    return context == GUMI_BUTTON_CONTEXT_NORMAL ||
           context == GUMI_BUTTON_CONTEXT_MAINTENANCE_EXCLUSIVE ||
           context == GUMI_BUTTON_CONTEXT_AWAITING_CONFIRMATION ||
           context == GUMI_BUTTON_CONTEXT_FATAL_PRIVACY;
}

static bool context_supports_taps(gumi_button_context context)
{
    return context == GUMI_BUTTON_CONTEXT_NORMAL ||
           context == GUMI_BUTTON_CONTEXT_MAINTENANCE_EXCLUSIVE ||
           context == GUMI_BUTTON_CONTEXT_FATAL_PRIVACY;
}

static bool add_would_overflow(uint64_t value, uint64_t increment)
{
    return value > UINT64_MAX - increment;
}

static void clear_edge_result(gumi_button_edge_result *result)
{
    result->emitted = false;
    result->at_ms = UINT64_C(0);
    result->level = GUMI_BUTTON_LEVEL_RELEASED;
}

static void clear_event_batch(gumi_button_event_batch *events)
{
    memset(events, 0, sizeof(*events));
}

static gumi_button_status emit_event(
    gumi_button_event_batch *events,
    uint64_t at_ms,
    gumi_button_event_type type,
    uint32_t confirmation_operation_token
)
{
    gumi_button_event *event;

    if (events->count >= GUMI_BUTTON_EVENT_BATCH_CAPACITY) {
        return GUMI_BUTTON_STATUS_EVENT_OVERFLOW;
    }
    event = &events->events[events->count];
    events->count += 1U;
    event->at_ms = at_ms;
    event->type = type;
    event->confirmation_operation_token = confirmation_operation_token;
    return GUMI_BUTTON_STATUS_OK;
}

gumi_button_status gumi_button_debouncer_init(
    gumi_button_debouncer *state,
    uint64_t stable_ms,
    gumi_button_level initial_level
)
{
    if (state == NULL) {
        return GUMI_BUTTON_STATUS_INVALID_ARGUMENT;
    }
    if (stable_ms == UINT64_C(0) || !level_is_valid(initial_level)) {
        return GUMI_BUTTON_STATUS_INVALID_CONFIGURATION;
    }

    memset(state, 0, sizeof(*state));
    state->stable_ms = stable_ms;
    state->observed_level = initial_level;
    state->accepted_level = initial_level;
    state->initialized = true;
    return GUMI_BUTTON_STATUS_OK;
}

static gumi_button_status debouncer_advance(
    gumi_button_debouncer *state,
    uint64_t at_ms,
    gumi_button_edge_result *result
)
{
    uint64_t deadline;

    if (at_ms < state->current_ms) {
        return GUMI_BUTTON_STATUS_TIME_REGRESSION;
    }
    if (state->observed_level != state->accepted_level &&
        add_would_overflow(state->observed_since_ms, state->stable_ms)) {
        return GUMI_BUTTON_STATUS_TIME_OVERFLOW;
    }

    state->current_ms = at_ms;
    if (state->observed_level == state->accepted_level) {
        return GUMI_BUTTON_STATUS_OK;
    }

    deadline = state->observed_since_ms + state->stable_ms;
    if (deadline > at_ms) {
        return GUMI_BUTTON_STATUS_OK;
    }

    state->accepted_level = state->observed_level;
    result->emitted = true;
    result->at_ms = deadline;
    result->level = state->accepted_level;
    return GUMI_BUTTON_STATUS_OK;
}

gumi_button_status gumi_button_debouncer_on_raw_level(
    gumi_button_debouncer *state,
    uint64_t at_ms,
    gumi_button_level level,
    gumi_button_edge_result *result
)
{
    gumi_button_debouncer next;
    gumi_button_edge_result next_result;
    gumi_button_status status;

    if (state == NULL || result == NULL || !level_is_valid(level)) {
        return GUMI_BUTTON_STATUS_INVALID_ARGUMENT;
    }
    if (!state->initialized) {
        return GUMI_BUTTON_STATUS_INVALID_CONFIGURATION;
    }
    if (at_ms < state->current_ms) {
        return GUMI_BUTTON_STATUS_TIME_REGRESSION;
    }

    next = *state;
    clear_edge_result(&next_result);
    status = debouncer_advance(&next, at_ms, &next_result);
    if (status != GUMI_BUTTON_STATUS_OK) {
        return status;
    }
    if (level != next.observed_level) {
        if (add_would_overflow(at_ms, next.stable_ms)) {
            return GUMI_BUTTON_STATUS_TIME_OVERFLOW;
        }
        next.observed_level = level;
        next.observed_since_ms = at_ms;
    }

    *state = next;
    *result = next_result;
    return GUMI_BUTTON_STATUS_OK;
}

gumi_button_status gumi_button_debouncer_advance_to(
    gumi_button_debouncer *state,
    uint64_t at_ms,
    gumi_button_edge_result *result
)
{
    gumi_button_debouncer next;
    gumi_button_edge_result next_result;
    gumi_button_status status;

    if (state == NULL || result == NULL) {
        return GUMI_BUTTON_STATUS_INVALID_ARGUMENT;
    }
    if (!state->initialized) {
        return GUMI_BUTTON_STATUS_INVALID_CONFIGURATION;
    }

    next = *state;
    clear_edge_result(&next_result);
    status = debouncer_advance(&next, at_ms, &next_result);
    if (status != GUMI_BUTTON_STATUS_OK) {
        return status;
    }

    *state = next;
    *result = next_result;
    return GUMI_BUTTON_STATUS_OK;
}

gumi_button_status gumi_button_gesture_init(
    gumi_button_gesture *state,
    const gumi_button_gesture_config *config
)
{
    bool confirmation_context;

    if (state == NULL || config == NULL) {
        return GUMI_BUTTON_STATUS_INVALID_ARGUMENT;
    }
    if (!context_is_valid(config->context)) {
        return GUMI_BUTTON_STATUS_INVALID_CONFIGURATION;
    }

    confirmation_context = config->context == GUMI_BUTTON_CONTEXT_AWAITING_CONFIRMATION;
    if (confirmation_context != (config->confirmation_operation_token != 0U)) {
        return GUMI_BUTTON_STATUS_INVALID_CONFIGURATION;
    }
    if (!confirmation_context && config->confirmation_lease_expires_at_ms != UINT64_C(0)) {
        return GUMI_BUTTON_STATUS_INVALID_CONFIGURATION;
    }

    memset(state, 0, sizeof(*state));
    state->context = config->context;
    state->confirmation_operation_token = config->confirmation_operation_token;
    state->confirmation_lease_expires_at_ms = config->confirmation_lease_expires_at_ms;
    state->initialized = true;
    return GUMI_BUTTON_STATUS_OK;
}

static gumi_button_status process_gesture_deadlines(
    gumi_button_gesture *state,
    uint64_t at_ms,
    bool inclusive,
    gumi_button_event_batch *events
)
{
    gumi_button_status status;

    if (state->pressed && !state->hold_committed && !state->confirmation_committed) {
        uint64_t duration = state->context == GUMI_BUTTON_CONTEXT_AWAITING_CONFIRMATION
            ? GUMI_BUTTON_CONFIRMATION_HOLD_MS
            : GUMI_BUTTON_HOLD_MS;
        uint64_t deadline = state->pressed_at_ms + duration;
        bool due = inclusive ? deadline <= at_ms : deadline < at_ms;

        if (due) {
            if (state->context == GUMI_BUTTON_CONTEXT_AWAITING_CONFIRMATION) {
                state->confirmation_committed = true;
                if (deadline < state->confirmation_lease_expires_at_ms) {
                    status = emit_event(
                        events,
                        deadline,
                        GUMI_BUTTON_EVENT_PHYSICAL_CONFIRMATION,
                        state->confirmation_operation_token
                    );
                    if (status != GUMI_BUTTON_STATUS_OK) {
                        return status;
                    }
                }
            } else if (context_supports_taps(state->context)) {
                state->hold_committed = true;
                state->has_first_tap = false;
                state->second_press = false;
                if (state->context == GUMI_BUTTON_CONTEXT_NORMAL) {
                    status = emit_event(
                        events,
                        deadline,
                        GUMI_BUTTON_EVENT_HOLD_COMMITTED,
                        0U
                    );
                    if (status != GUMI_BUTTON_STATUS_OK) {
                        return status;
                    }
                }
            }
        }
    }

    if (!state->pressed && state->has_first_tap) {
        uint64_t deadline = state->first_tap_released_at_ms + GUMI_BUTTON_DOUBLE_TAP_WINDOW_MS;
        bool due = inclusive ? deadline <= at_ms : deadline < at_ms;

        if (due) {
            state->has_first_tap = false;
            status = emit_event(events, deadline, GUMI_BUTTON_EVENT_SINGLE_TAP, 0U);
            if (status != GUMI_BUTTON_STATUS_OK) {
                return status;
            }
        }
    }
    return GUMI_BUTTON_STATUS_OK;
}

static gumi_button_status validate_edge_without_mutation(
    const gumi_button_gesture *state,
    uint64_t at_ms,
    gumi_button_level level
)
{
    uint64_t duration;

    if (!level_is_valid(level)) {
        return GUMI_BUTTON_STATUS_INVALID_ARGUMENT;
    }
    if (at_ms < state->current_ms) {
        return GUMI_BUTTON_STATUS_TIME_REGRESSION;
    }
    if (at_ms == state->current_ms && state->deadlines_advanced_at_current_ms) {
        return GUMI_BUTTON_STATUS_TIMESTAMP_PRECEDENCE;
    }
    if (level == GUMI_BUTTON_LEVEL_PRESSED && state->pressed) {
        return GUMI_BUTTON_STATUS_EDGE_ORDER;
    }
    if (level == GUMI_BUTTON_LEVEL_RELEASED && !state->pressed) {
        return GUMI_BUTTON_STATUS_EDGE_ORDER;
    }

    if (level == GUMI_BUTTON_LEVEL_PRESSED) {
        duration = state->context == GUMI_BUTTON_CONTEXT_AWAITING_CONFIRMATION
            ? GUMI_BUTTON_CONFIRMATION_HOLD_MS
            : GUMI_BUTTON_HOLD_MS;
        if (add_would_overflow(at_ms, duration)) {
            return GUMI_BUTTON_STATUS_TIME_OVERFLOW;
        }
    } else if (!state->hold_committed &&
               state->context != GUMI_BUTTON_CONTEXT_AWAITING_CONFIRMATION &&
               !state->second_press &&
               add_would_overflow(at_ms, GUMI_BUTTON_DOUBLE_TAP_WINDOW_MS)) {
        return GUMI_BUTTON_STATUS_TIME_OVERFLOW;
    }
    return GUMI_BUTTON_STATUS_OK;
}

gumi_button_status gumi_button_gesture_accept_edge(
    gumi_button_gesture *state,
    uint64_t at_ms,
    gumi_button_level level,
    gumi_button_event_batch *events
)
{
    gumi_button_gesture next;
    gumi_button_event_batch next_events;
    gumi_button_status status;

    if (state == NULL || events == NULL) {
        return GUMI_BUTTON_STATUS_INVALID_ARGUMENT;
    }
    if (!state->initialized) {
        return GUMI_BUTTON_STATUS_INVALID_CONFIGURATION;
    }
    status = validate_edge_without_mutation(state, at_ms, level);
    if (status != GUMI_BUTTON_STATUS_OK) {
        return status;
    }

    next = *state;
    clear_event_batch(&next_events);
    status = process_gesture_deadlines(&next, at_ms, false, &next_events);
    if (status != GUMI_BUTTON_STATUS_OK) {
        return status;
    }
    next.current_ms = at_ms;
    next.deadlines_advanced_at_current_ms = false;

    if (level == GUMI_BUTTON_LEVEL_PRESSED) {
        next.pressed = true;
        next.pressed_at_ms = at_ms;
        next.hold_committed = false;
        next.confirmation_committed = false;
        next.second_press = context_supports_taps(next.context) && next.has_first_tap &&
            at_ms <= next.first_tap_released_at_ms + GUMI_BUTTON_DOUBLE_TAP_WINDOW_MS;
    } else {
        next.pressed = false;
        if (next.context == GUMI_BUTTON_CONTEXT_AWAITING_CONFIRMATION) {
            next.confirmation_committed = false;
        } else if (next.hold_committed) {
            next.hold_committed = false;
            next.second_press = false;
            next.has_first_tap = false;
            if (next.context == GUMI_BUTTON_CONTEXT_NORMAL) {
                status = emit_event(
                    &next_events,
                    at_ms,
                    GUMI_BUTTON_EVENT_HOLD_RELEASED,
                    0U
                );
            }
        } else if (next.second_press) {
            next.second_press = false;
            next.has_first_tap = false;
            if (next.context == GUMI_BUTTON_CONTEXT_NORMAL) {
                status = emit_event(&next_events, at_ms, GUMI_BUTTON_EVENT_DOUBLE_TAP, 0U);
            }
        } else {
            next.has_first_tap = true;
            next.first_tap_released_at_ms = at_ms;
        }
        if (status != GUMI_BUTTON_STATUS_OK) {
            return status;
        }
    }

    *state = next;
    *events = next_events;
    return GUMI_BUTTON_STATUS_OK;
}

gumi_button_status gumi_button_gesture_advance_to(
    gumi_button_gesture *state,
    uint64_t at_ms,
    gumi_button_event_batch *events
)
{
    gumi_button_gesture next;
    gumi_button_event_batch next_events;
    gumi_button_status status;

    if (state == NULL || events == NULL) {
        return GUMI_BUTTON_STATUS_INVALID_ARGUMENT;
    }
    if (!state->initialized) {
        return GUMI_BUTTON_STATUS_INVALID_CONFIGURATION;
    }
    if (at_ms < state->current_ms) {
        return GUMI_BUTTON_STATUS_TIME_REGRESSION;
    }

    next = *state;
    clear_event_batch(&next_events);
    status = process_gesture_deadlines(&next, at_ms, true, &next_events);
    if (status != GUMI_BUTTON_STATUS_OK) {
        return status;
    }
    next.current_ms = at_ms;
    next.deadlines_advanced_at_current_ms = true;

    *state = next;
    *events = next_events;
    return GUMI_BUTTON_STATUS_OK;
}
