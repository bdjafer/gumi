#include "gumi/recovery.h"

#include <string.h>

static bool completion_is_valid(gumi_recovery_completion completion)
{
    return completion >= GUMI_RECOVERY_COMPLETION_TRANSPORT_READY &&
           completion <= GUMI_RECOVERY_COMPLETION_FUNCTIONAL_QUIESCE_FAILED;
}

static void clear_result(gumi_recovery_result *result)
{
    memset(result, 0, sizeof(*result));
}

static gumi_recovery_status validate_state(const gumi_recovery_supervisor *state)
{
    if (!state->initialized) {
        return GUMI_RECOVERY_STATUS_INVALID_STATE;
    }
    if (state->capture_permitted &&
        (state->phase != GUMI_RECOVERY_PHASE_OPERATIONAL ||
         state->safe_mode_requested || !state->recovery_transport_ready ||
         !state->microphone_verified_off || !state->self_tests_passed ||
         !state->functional_services_ready)) {
        return GUMI_RECOVERY_STATUS_INVALID_STATE;
    }
    if (state->phase == GUMI_RECOVERY_PHASE_OPERATIONAL && !state->capture_permitted) {
        return GUMI_RECOVERY_STATUS_INVALID_STATE;
    }
    if ((state->phase == GUMI_RECOVERY_PHASE_SAFE_MODE ||
         state->phase == GUMI_RECOVERY_PHASE_QUIESCING_TO_SAFE_MODE) &&
        (!state->recovery_transport_ready || state->capture_permitted)) {
        return GUMI_RECOVERY_STATUS_INVALID_STATE;
    }
    if (state->phase == GUMI_RECOVERY_PHASE_RECOVERY_UNAVAILABLE &&
        (state->recovery_transport_ready || state->capture_permitted)) {
        return GUMI_RECOVERY_STATUS_INVALID_STATE;
    }
    return GUMI_RECOVERY_STATUS_OK;
}

static gumi_recovery_status validate_call(
    const gumi_recovery_supervisor *state,
    uint64_t at_ms,
    const gumi_recovery_result *result
)
{
    gumi_recovery_status status;

    if (state == NULL || result == NULL) {
        return GUMI_RECOVERY_STATUS_INVALID_ARGUMENT;
    }
    status = validate_state(state);
    if (status != GUMI_RECOVERY_STATUS_OK) {
        return status;
    }
    if (at_ms < state->current_ms) {
        return GUMI_RECOVERY_STATUS_TIME_REGRESSION;
    }
    return GUMI_RECOVERY_STATUS_OK;
}

static gumi_recovery_status allocate_transition(gumi_recovery_supervisor *state)
{
    if (state->next_transition_id == UINT64_C(0)) {
        return GUMI_RECOVERY_STATUS_COUNTER_EXHAUSTED;
    }
    state->transition_id = state->next_transition_id;
    state->next_transition_id += UINT64_C(1);
    return GUMI_RECOVERY_STATUS_OK;
}

static gumi_recovery_status emit_action(
    gumi_recovery_supervisor *state,
    gumi_recovery_result *result,
    uint64_t at_ms,
    gumi_recovery_action_type type
)
{
    gumi_recovery_status status = allocate_transition(state);

    if (status != GUMI_RECOVERY_STATUS_OK) {
        return status;
    }
    result->has_action = true;
    result->action.at_ms = at_ms;
    result->action.transition_id = state->transition_id;
    result->action.type = type;
    return GUMI_RECOVERY_STATUS_OK;
}

static void emit_event(
    gumi_recovery_result *result,
    uint64_t at_ms,
    gumi_recovery_event_type type,
    gumi_recovery_reason reason
)
{
    result->has_event = true;
    result->event.at_ms = at_ms;
    result->event.type = type;
    result->event.reason = reason;
}

static gumi_recovery_status commit(
    gumi_recovery_supervisor *state,
    const gumi_recovery_supervisor *next,
    gumi_recovery_result *result,
    const gumi_recovery_result *next_result
)
{
    gumi_recovery_status status = validate_state(next);

    if (status != GUMI_RECOVERY_STATUS_OK) {
        return status;
    }
    *state = *next;
    *result = *next_result;
    return GUMI_RECOVERY_STATUS_OK;
}

static void enter_safe_mode(
    gumi_recovery_supervisor *state,
    gumi_recovery_result *result,
    uint64_t at_ms,
    gumi_recovery_reason reason
)
{
    state->phase = GUMI_RECOVERY_PHASE_SAFE_MODE;
    state->reason = reason;
    state->safe_mode_requested = true;
    state->capture_permitted = false;
    state->functional_services_ready = false;
    emit_event(result, at_ms, GUMI_RECOVERY_EVENT_SAFE_MODE_READY, reason);
}

gumi_recovery_status gumi_recovery_supervisor_init(
    gumi_recovery_supervisor *state,
    const gumi_recovery_boot_evidence *evidence
)
{
    if (state == NULL || evidence == NULL) {
        return GUMI_RECOVERY_STATUS_INVALID_ARGUMENT;
    }
    memset(state, 0, sizeof(*state));
    state->phase = GUMI_RECOVERY_PHASE_COLD;
    state->next_transition_id = UINT64_C(1);
    if (evidence->watchdog_or_lockup_reset) {
        state->safe_mode_requested = true;
        state->reason = GUMI_RECOVERY_REASON_WATCHDOG_OR_LOCKUP_RESET;
    } else if (evidence->persisted_safe_mode) {
        state->safe_mode_requested = true;
        state->reason = GUMI_RECOVERY_REASON_PERSISTED_SAFE_MODE;
    } else if (evidence->explicit_safe_mode) {
        state->safe_mode_requested = true;
        state->reason = GUMI_RECOVERY_REASON_EXPLICIT_SAFE_MODE;
    }
    state->initialized = true;
    return validate_state(state);
}

gumi_recovery_status gumi_recovery_begin(
    gumi_recovery_supervisor *state,
    uint64_t at_ms,
    gumi_recovery_result *result
)
{
    gumi_recovery_supervisor next;
    gumi_recovery_result next_result;
    gumi_recovery_status status = validate_call(state, at_ms, result);

    if (status != GUMI_RECOVERY_STATUS_OK) {
        return status;
    }
    if (state->phase != GUMI_RECOVERY_PHASE_COLD) {
        return GUMI_RECOVERY_STATUS_INVALID_STATE;
    }
    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    next.phase = GUMI_RECOVERY_PHASE_STARTING_TRANSPORT;
    status = emit_action(
        &next,
        &next_result,
        at_ms,
        GUMI_RECOVERY_ACTION_START_TRANSPORT
    );
    if (status != GUMI_RECOVERY_STATUS_OK) {
        return status;
    }
    return commit(state, &next, result, &next_result);
}

gumi_recovery_status gumi_recovery_complete(
    gumi_recovery_supervisor *state,
    uint64_t at_ms,
    uint64_t transition_id,
    gumi_recovery_completion completion,
    gumi_recovery_result *result
)
{
    gumi_recovery_supervisor next;
    gumi_recovery_result next_result;
    gumi_recovery_status status = validate_call(state, at_ms, result);

    if (status != GUMI_RECOVERY_STATUS_OK) {
        return status;
    }
    if (!completion_is_valid(completion)) {
        return GUMI_RECOVERY_STATUS_INVALID_ARGUMENT;
    }
    if (transition_id == UINT64_C(0) || transition_id != state->transition_id) {
        return GUMI_RECOVERY_STATUS_STALE_TRANSITION;
    }

    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;

    switch (state->phase) {
    case GUMI_RECOVERY_PHASE_STARTING_TRANSPORT:
        if (completion == GUMI_RECOVERY_COMPLETION_TRANSPORT_FAILED) {
            next.phase = GUMI_RECOVERY_PHASE_RECOVERY_UNAVAILABLE;
            next.reason = GUMI_RECOVERY_REASON_TRANSPORT_UNAVAILABLE;
            next.capture_permitted = false;
            emit_event(
                &next_result,
                at_ms,
                GUMI_RECOVERY_EVENT_RECOVERY_UNAVAILABLE,
                next.reason
            );
            return commit(state, &next, result, &next_result);
        }
        if (completion != GUMI_RECOVERY_COMPLETION_TRANSPORT_READY) {
            return GUMI_RECOVERY_STATUS_INVALID_STATE;
        }
        next.recovery_transport_ready = true;
        next.phase = GUMI_RECOVERY_PHASE_VERIFYING_MICROPHONE_OFF;
        emit_event(
            &next_result,
            at_ms,
            GUMI_RECOVERY_EVENT_RECOVERY_AVAILABLE,
            GUMI_RECOVERY_REASON_NONE
        );
        status = emit_action(
            &next,
            &next_result,
            at_ms,
            GUMI_RECOVERY_ACTION_VERIFY_MICROPHONE_OFF
        );
        break;

    case GUMI_RECOVERY_PHASE_VERIFYING_MICROPHONE_OFF:
        if (completion == GUMI_RECOVERY_COMPLETION_MICROPHONE_OFF_FAILED) {
            next.microphone_verified_off = false;
            enter_safe_mode(
                &next,
                &next_result,
                at_ms,
                GUMI_RECOVERY_REASON_MICROPHONE_OFF_UNVERIFIED
            );
            return commit(state, &next, result, &next_result);
        }
        if (completion != GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF) {
            return GUMI_RECOVERY_STATUS_INVALID_STATE;
        }
        next.microphone_verified_off = true;
        if (next.safe_mode_requested) {
            enter_safe_mode(&next, &next_result, at_ms, next.reason);
            return commit(state, &next, result, &next_result);
        }
        next.phase = GUMI_RECOVERY_PHASE_RUNNING_SELF_TESTS;
        status = emit_action(
            &next,
            &next_result,
            at_ms,
            GUMI_RECOVERY_ACTION_RUN_FUNCTIONAL_SELF_TESTS
        );
        break;

    case GUMI_RECOVERY_PHASE_RUNNING_SELF_TESTS:
        if (completion == GUMI_RECOVERY_COMPLETION_SELF_TESTS_FAILED) {
            enter_safe_mode(
                &next,
                &next_result,
                at_ms,
                GUMI_RECOVERY_REASON_SELF_TEST_FAILED
            );
            return commit(state, &next, result, &next_result);
        }
        if (completion != GUMI_RECOVERY_COMPLETION_SELF_TESTS_PASSED) {
            return GUMI_RECOVERY_STATUS_INVALID_STATE;
        }
        next.self_tests_passed = true;
        next.phase = GUMI_RECOVERY_PHASE_ENABLING_FUNCTIONAL_SERVICES;
        status = emit_action(
            &next,
            &next_result,
            at_ms,
            GUMI_RECOVERY_ACTION_ENABLE_FUNCTIONAL_SERVICES
        );
        break;

    case GUMI_RECOVERY_PHASE_ENABLING_FUNCTIONAL_SERVICES:
        if (completion == GUMI_RECOVERY_COMPLETION_FUNCTIONAL_SERVICES_FAILED) {
            enter_safe_mode(
                &next,
                &next_result,
                at_ms,
                GUMI_RECOVERY_REASON_FUNCTIONAL_SERVICES_FAILED
            );
            return commit(state, &next, result, &next_result);
        }
        if (completion != GUMI_RECOVERY_COMPLETION_FUNCTIONAL_SERVICES_READY) {
            return GUMI_RECOVERY_STATUS_INVALID_STATE;
        }
        next.functional_services_ready = true;
        next.capture_permitted = true;
        next.phase = GUMI_RECOVERY_PHASE_OPERATIONAL;
        next.reason = GUMI_RECOVERY_REASON_NONE;
        emit_event(
            &next_result,
            at_ms,
            GUMI_RECOVERY_EVENT_OPERATIONAL_READY,
            GUMI_RECOVERY_REASON_NONE
        );
        status = GUMI_RECOVERY_STATUS_OK;
        break;

    case GUMI_RECOVERY_PHASE_QUIESCING_TO_SAFE_MODE:
        if (completion == GUMI_RECOVERY_COMPLETION_FUNCTIONAL_QUIESCE_FAILED) {
            next.microphone_verified_off = false;
            enter_safe_mode(
                &next,
                &next_result,
                at_ms,
                GUMI_RECOVERY_REASON_QUIESCE_FAILED
            );
            return commit(state, &next, result, &next_result);
        }
        if (completion != GUMI_RECOVERY_COMPLETION_FUNCTIONAL_SERVICES_QUIESCED) {
            return GUMI_RECOVERY_STATUS_INVALID_STATE;
        }
        next.microphone_verified_off = true;
        enter_safe_mode(
            &next,
            &next_result,
            at_ms,
            GUMI_RECOVERY_REASON_RUNTIME_FAULT
        );
        return commit(state, &next, result, &next_result);

    default:
        return GUMI_RECOVERY_STATUS_INVALID_STATE;
    }

    if (status != GUMI_RECOVERY_STATUS_OK) {
        return status;
    }
    return commit(state, &next, result, &next_result);
}

gumi_recovery_status gumi_recovery_runtime_fault(
    gumi_recovery_supervisor *state,
    uint64_t at_ms,
    gumi_recovery_result *result
)
{
    gumi_recovery_supervisor next;
    gumi_recovery_result next_result;
    gumi_recovery_status status = validate_call(state, at_ms, result);

    if (status != GUMI_RECOVERY_STATUS_OK) {
        return status;
    }
    if (state->phase != GUMI_RECOVERY_PHASE_OPERATIONAL) {
        return GUMI_RECOVERY_STATUS_INVALID_STATE;
    }
    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    next.capture_permitted = false;
    next.safe_mode_requested = true;
    next.reason = GUMI_RECOVERY_REASON_RUNTIME_FAULT;
    next.phase = GUMI_RECOVERY_PHASE_QUIESCING_TO_SAFE_MODE;
    status = emit_action(
        &next,
        &next_result,
        at_ms,
        GUMI_RECOVERY_ACTION_QUIESCE_FUNCTIONAL_SERVICES
    );
    if (status != GUMI_RECOVERY_STATUS_OK) {
        return status;
    }
    return commit(state, &next, result, &next_result);
}

bool gumi_recovery_capture_is_permitted(const gumi_recovery_supervisor *state)
{
    return state != NULL && state->initialized && state->capture_permitted;
}

bool gumi_recovery_transport_is_available(const gumi_recovery_supervisor *state)
{
    return state != NULL && state->initialized && state->recovery_transport_ready;
}

gumi_recovery_status gumi_recovery_encode_status(
    const gumi_recovery_supervisor *state,
    uint8_t output[GUMI_RECOVERY_STATUS_WIRE_SIZE]
)
{
    uint8_t flags = UINT8_C(0x20);
    gumi_recovery_status status;

    if (state == NULL || output == NULL) {
        return GUMI_RECOVERY_STATUS_INVALID_ARGUMENT;
    }
    status = validate_state(state);
    if (status != GUMI_RECOVERY_STATUS_OK) {
        return status;
    }
    if (state->recovery_transport_ready) flags |= UINT8_C(0x01);
    if (state->microphone_verified_off) flags |= UINT8_C(0x02);
    if (state->self_tests_passed) flags |= UINT8_C(0x04);
    if (state->functional_services_ready) flags |= UINT8_C(0x08);
    if (state->capture_permitted) flags |= UINT8_C(0x10);
    output[0] = GUMI_RECOVERY_STATUS_WIRE_VERSION;
    output[1] = (uint8_t)state->phase;
    output[2] = (uint8_t)state->reason;
    output[3] = flags;
    return GUMI_RECOVERY_STATUS_OK;
}
