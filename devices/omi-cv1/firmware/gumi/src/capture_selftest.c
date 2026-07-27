#include "gumi/capture_selftest.h"

#include <limits.h>
#include <string.h>

static bool action_is_valid(gumi_capture_selftest_action_type action)
{
    return action >= GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY &&
           action <= GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY;
}

static bool failure_is_rearmable(gumi_capture_selftest_failure failure)
{
    return failure == GUMI_CAPTURE_SELFTEST_FAILURE_CONFIRMATION_EXPIRED ||
           failure == GUMI_CAPTURE_SELFTEST_FAILURE_MICROPHONE_ACQUIRE ||
           failure == GUMI_CAPTURE_SELFTEST_FAILURE_INSUFFICIENT_PCM ||
           failure == GUMI_CAPTURE_SELFTEST_FAILURE_INSUFFICIENT_OPUS ||
           failure == GUMI_CAPTURE_SELFTEST_FAILURE_CODEC_DROPPED_SAMPLES ||
           failure == GUMI_CAPTURE_SELFTEST_FAILURE_CODEC_TERMINAL;
}

static bool phase_is_stable(gumi_capture_selftest_phase phase)
{
    return phase == GUMI_CAPTURE_SELFTEST_PHASE_IDLE ||
           phase == GUMI_CAPTURE_SELFTEST_PHASE_ARMED ||
           phase == GUMI_CAPTURE_SELFTEST_PHASE_EXERCISING ||
           phase == GUMI_CAPTURE_SELFTEST_PHASE_PASSED ||
           phase == GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE ||
           phase == GUMI_CAPTURE_SELFTEST_PHASE_FAILED_MICROPHONE_UNKNOWN;
}

static bool phase_matches_action(
    gumi_capture_selftest_phase phase,
    gumi_capture_selftest_action_type action
)
{
    switch (phase) {
    case GUMI_CAPTURE_SELFTEST_PHASE_ASSERTING_PRIVACY:
        return action == GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY;
    case GUMI_CAPTURE_SELFTEST_PHASE_OPENING_CODEC:
        return action == GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC;
    case GUMI_CAPTURE_SELFTEST_PHASE_ACQUIRING_MICROPHONE:
        return action == GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE;
    case GUMI_CAPTURE_SELFTEST_PHASE_RELEASING_MICROPHONE:
        return action == GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE;
    case GUMI_CAPTURE_SELFTEST_PHASE_CLOSING_CODEC:
        return action == GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN ||
               action == GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DISCARD;
    case GUMI_CAPTURE_SELFTEST_PHASE_DEASSERTING_PRIVACY:
        return action == GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY;
    default:
        return false;
    }
}

static gumi_capture_selftest_status validate_state(const gumi_capture_selftest *state)
{
    if (state == NULL || !state->initialized) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE;
    }
    if (state->action_outstanding) {
        if (phase_is_stable(state->phase) ||
            !phase_matches_action(state->phase, state->outstanding_action) ||
            state->transition_id == UINT64_C(0)) {
            return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE;
        }
    } else if (!phase_is_stable(state->phase)) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE;
    }
    if (!state->microphone_verified_off &&
        state->phase != GUMI_CAPTURE_SELFTEST_PHASE_EXERCISING &&
        state->phase != GUMI_CAPTURE_SELFTEST_PHASE_RELEASING_MICROPHONE &&
        state->phase != GUMI_CAPTURE_SELFTEST_PHASE_FAILED_MICROPHONE_UNKNOWN) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE;
    }
    if ((state->phase == GUMI_CAPTURE_SELFTEST_PHASE_EXERCISING ||
         state->phase == GUMI_CAPTURE_SELFTEST_PHASE_RELEASING_MICROPHONE) &&
        (!state->privacy_asserted || !state->codec_open || state->microphone_verified_off)) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE;
    }
    if (state->phase == GUMI_CAPTURE_SELFTEST_PHASE_ARMED &&
        (state->lease_expires_ms <= state->current_ms || state->privacy_asserted ||
         state->codec_open || !state->microphone_verified_off)) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE;
    }
    if (state->phase == GUMI_CAPTURE_SELFTEST_PHASE_PASSED &&
        (state->failure != GUMI_CAPTURE_SELFTEST_FAILURE_NONE ||
         state->privacy_asserted || state->codec_open ||
         !state->microphone_verified_off)) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE;
    }
    if ((state->phase == GUMI_CAPTURE_SELFTEST_PHASE_IDLE ||
         state->phase == GUMI_CAPTURE_SELFTEST_PHASE_ARMED) &&
        state->failure != GUMI_CAPTURE_SELFTEST_FAILURE_NONE) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE;
    }
    return GUMI_CAPTURE_SELFTEST_STATUS_OK;
}

static gumi_capture_selftest_status validate_call(
    const gumi_capture_selftest *state,
    uint64_t at_ms,
    const gumi_capture_selftest_result *result
)
{
    gumi_capture_selftest_status status;

    if (state == NULL || result == NULL) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_ARGUMENT;
    }
    status = validate_state(state);
    if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return status;
    }
    if (at_ms < state->current_ms) {
        return GUMI_CAPTURE_SELFTEST_STATUS_TIME_REGRESSION;
    }
    return GUMI_CAPTURE_SELFTEST_STATUS_OK;
}

static void clear_result(gumi_capture_selftest_result *result)
{
    memset(result, 0, sizeof(*result));
}

static gumi_capture_selftest_status emit_action(
    gumi_capture_selftest *state,
    gumi_capture_selftest_result *result,
    gumi_capture_selftest_phase phase,
    gumi_capture_selftest_action_type action
)
{
    if (state->next_transition_id == UINT64_C(0)) {
        return GUMI_CAPTURE_SELFTEST_STATUS_COUNTER_EXHAUSTED;
    }
    state->transition_id = state->next_transition_id;
    state->next_transition_id += UINT64_C(1);
    state->phase = phase;
    state->outstanding_action = action;
    state->action_outstanding = true;
    result->has_action = true;
    result->action.transition_id = state->transition_id;
    result->action.type = action;
    return GUMI_CAPTURE_SELFTEST_STATUS_OK;
}

static gumi_capture_selftest_status commit(
    gumi_capture_selftest *state,
    const gumi_capture_selftest *next,
    gumi_capture_selftest_result *result,
    const gumi_capture_selftest_result *next_result
)
{
    gumi_capture_selftest_status status = validate_state(next);

    if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return status;
    }
    *state = *next;
    *result = *next_result;
    return GUMI_CAPTURE_SELFTEST_STATUS_OK;
}

static void enter_safe_failure(
    gumi_capture_selftest *state,
    gumi_capture_selftest_failure failure
)
{
    state->phase = GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE;
    state->failure = failure;
    state->action_outstanding = false;
    state->should_pass = false;
}

static void enter_microphone_unknown(
    gumi_capture_selftest *state,
    gumi_capture_selftest_failure failure
)
{
    state->phase = GUMI_CAPTURE_SELFTEST_PHASE_FAILED_MICROPHONE_UNKNOWN;
    state->failure = failure;
    state->action_outstanding = false;
    state->microphone_verified_off = false;
    state->privacy_asserted = true;
    state->should_pass = false;
}

static gumi_capture_selftest_status begin_safe_cleanup(
    gumi_capture_selftest *state,
    gumi_capture_selftest_result *result
)
{
    if (!state->microphone_verified_off) {
        return emit_action(
            state,
            result,
            GUMI_CAPTURE_SELFTEST_PHASE_RELEASING_MICROPHONE,
            GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE
        );
    }
    if (state->codec_open) {
        return emit_action(
            state,
            result,
            GUMI_CAPTURE_SELFTEST_PHASE_CLOSING_CODEC,
            state->should_pass ? GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN
                               : GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DISCARD
        );
    }
    if (state->privacy_asserted) {
        return emit_action(
            state,
            result,
            GUMI_CAPTURE_SELFTEST_PHASE_DEASSERTING_PRIVACY,
            GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY
        );
    }
    if (state->should_pass) {
        state->phase = GUMI_CAPTURE_SELFTEST_PHASE_PASSED;
        state->failure = GUMI_CAPTURE_SELFTEST_FAILURE_NONE;
    } else {
        state->phase = GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE;
    }
    return GUMI_CAPTURE_SELFTEST_STATUS_OK;
}

static gumi_capture_selftest_failure evidence_failure(
    const gumi_capture_selftest_evidence *evidence
)
{
    if (evidence->terminal_error != 0) {
        return GUMI_CAPTURE_SELFTEST_FAILURE_CODEC_TERMINAL;
    }
    if (evidence->discarded_samples != UINT32_C(0)) {
        return GUMI_CAPTURE_SELFTEST_FAILURE_CODEC_DROPPED_SAMPLES;
    }
    if (evidence->pcm_samples < GUMI_CAPTURE_SELFTEST_MINIMUM_PCM_SAMPLES) {
        return GUMI_CAPTURE_SELFTEST_FAILURE_INSUFFICIENT_PCM;
    }
    if (evidence->opus_packets < GUMI_CAPTURE_SELFTEST_MINIMUM_OPUS_PACKETS) {
        return GUMI_CAPTURE_SELFTEST_FAILURE_INSUFFICIENT_OPUS;
    }
    return GUMI_CAPTURE_SELFTEST_FAILURE_NONE;
}

gumi_capture_selftest_status gumi_capture_selftest_init(
    gumi_capture_selftest *state,
    bool microphone_verified_off,
    bool recovery_transport_present
)
{
    if (state == NULL) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_ARGUMENT;
    }
    memset(state, 0, sizeof(*state));
    state->phase = microphone_verified_off ? GUMI_CAPTURE_SELFTEST_PHASE_IDLE
                                           : GUMI_CAPTURE_SELFTEST_PHASE_FAILED_MICROPHONE_UNKNOWN;
    state->failure = microphone_verified_off ? GUMI_CAPTURE_SELFTEST_FAILURE_NONE
                                             : GUMI_CAPTURE_SELFTEST_FAILURE_MICROPHONE_RELEASE;
    state->next_transition_id = UINT64_C(1);
    state->microphone_verified_off = microphone_verified_off;
    state->privacy_asserted = !microphone_verified_off;
    state->recovery_transport_present = recovery_transport_present;
    state->initialized = true;
    return validate_state(state);
}

gumi_capture_selftest_status gumi_capture_selftest_arm(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    uint64_t lease_expires_ms
)
{
    gumi_capture_selftest next;
    uint64_t lease_ms;
    gumi_capture_selftest_status status;

    if (state == NULL) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_ARGUMENT;
    }
    status = validate_state(state);
    if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return status;
    }
    if (at_ms < state->current_ms) {
        return GUMI_CAPTURE_SELFTEST_STATUS_TIME_REGRESSION;
    }
    if (lease_expires_ms <= at_ms) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_ARGUMENT;
    }
    lease_ms = lease_expires_ms - at_ms;
    if (lease_ms != UINT64_C(15000)) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_ARGUMENT;
    }
    if (!state->recovery_transport_present || !state->microphone_verified_off ||
        state->privacy_asserted || state->codec_open || state->action_outstanding ||
        (state->phase != GUMI_CAPTURE_SELFTEST_PHASE_IDLE &&
         state->phase != GUMI_CAPTURE_SELFTEST_PHASE_PASSED &&
         state->phase != GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE) ||
        (state->phase == GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE &&
         !failure_is_rearmable(state->failure))) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE;
    }
    if (state->attempt == UINT32_MAX) {
        return GUMI_CAPTURE_SELFTEST_STATUS_COUNTER_EXHAUSTED;
    }

    next = *state;
    next.current_ms = at_ms;
    next.lease_expires_ms = lease_expires_ms;
    next.attempt += UINT32_C(1);
    next.phase = GUMI_CAPTURE_SELFTEST_PHASE_ARMED;
    next.failure = GUMI_CAPTURE_SELFTEST_FAILURE_NONE;
    next.should_pass = false;
    memset(&next.evidence, 0, sizeof(next.evidence));
    status = validate_state(&next);
    if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return status;
    }
    *state = next;
    return GUMI_CAPTURE_SELFTEST_STATUS_OK;
}

gumi_capture_selftest_status gumi_capture_selftest_confirm(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    gumi_capture_selftest_result *result
)
{
    gumi_capture_selftest next;
    gumi_capture_selftest_result next_result;
    gumi_capture_selftest_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return status;
    }
    if (state->phase != GUMI_CAPTURE_SELFTEST_PHASE_ARMED ||
        at_ms >= state->lease_expires_ms) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE;
    }
    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    status = emit_action(
        &next,
        &next_result,
        GUMI_CAPTURE_SELFTEST_PHASE_ASSERTING_PRIVACY,
        GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY
    );
    if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return status;
    }
    return commit(state, &next, result, &next_result);
}

gumi_capture_selftest_status gumi_capture_selftest_advance(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    gumi_capture_selftest_result *result
)
{
    gumi_capture_selftest next;
    gumi_capture_selftest_result next_result;
    gumi_capture_selftest_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return status;
    }
    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;

    if (state->phase == GUMI_CAPTURE_SELFTEST_PHASE_ARMED &&
        at_ms >= state->lease_expires_ms) {
        enter_safe_failure(&next, GUMI_CAPTURE_SELFTEST_FAILURE_CONFIRMATION_EXPIRED);
    } else if (state->phase == GUMI_CAPTURE_SELFTEST_PHASE_EXERCISING &&
               at_ms >= state->exercise_ends_ms) {
        next.should_pass = true;
        status = begin_safe_cleanup(&next, &next_result);
        if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
            return status;
        }
    } else if (state->action_outstanding) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE;
    }
    return commit(state, &next, result, &next_result);
}

gumi_capture_selftest_status gumi_capture_selftest_complete(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    uint64_t transition_id,
    gumi_capture_selftest_action_type action,
    bool success,
    const gumi_capture_selftest_evidence *evidence,
    gumi_capture_selftest_result *result
)
{
    gumi_capture_selftest next;
    gumi_capture_selftest_result next_result;
    gumi_capture_selftest_status status = validate_call(state, at_ms, result);
    bool closes_codec;

    if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return status;
    }
    if (!action_is_valid(action)) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_ARGUMENT;
    }
    if (!state->action_outstanding || transition_id == UINT64_C(0) ||
        transition_id != state->transition_id || action != state->outstanding_action) {
        return GUMI_CAPTURE_SELFTEST_STATUS_STALE_TRANSITION;
    }
    closes_codec = action == GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN ||
                   action == GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DISCARD;
    if (closes_codec != (evidence != NULL)) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_ARGUMENT;
    }
    if (action == GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE && success &&
        UINT64_MAX - at_ms < GUMI_CAPTURE_SELFTEST_EXERCISE_MS) {
        return GUMI_CAPTURE_SELFTEST_STATUS_COUNTER_EXHAUSTED;
    }

    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    next.action_outstanding = false;

    switch (action) {
    case GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY:
        if (!success) {
            enter_safe_failure(&next, GUMI_CAPTURE_SELFTEST_FAILURE_PRIVACY_ASSERT);
            break;
        }
        next.privacy_asserted = true;
        status = emit_action(
            &next,
            &next_result,
            GUMI_CAPTURE_SELFTEST_PHASE_OPENING_CODEC,
            GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC
        );
        break;

    case GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC:
        if (!success) {
            next.should_pass = false;
            next.failure = GUMI_CAPTURE_SELFTEST_FAILURE_CODEC_OPEN;
            status = begin_safe_cleanup(&next, &next_result);
            break;
        }
        next.codec_open = true;
        status = emit_action(
            &next,
            &next_result,
            GUMI_CAPTURE_SELFTEST_PHASE_ACQUIRING_MICROPHONE,
            GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE
        );
        break;

    case GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE:
        if (!success) {
            next.failure = GUMI_CAPTURE_SELFTEST_FAILURE_MICROPHONE_ACQUIRE;
            next.should_pass = false;
            status = begin_safe_cleanup(&next, &next_result);
            break;
        }
        next.microphone_verified_off = false;
        next.phase = GUMI_CAPTURE_SELFTEST_PHASE_EXERCISING;
        next.exercise_ends_ms = at_ms + GUMI_CAPTURE_SELFTEST_EXERCISE_MS;
        status = GUMI_CAPTURE_SELFTEST_STATUS_OK;
        break;

    case GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE:
        if (!success) {
            enter_microphone_unknown(
                &next,
                GUMI_CAPTURE_SELFTEST_FAILURE_MICROPHONE_RELEASE
            );
            status = GUMI_CAPTURE_SELFTEST_STATUS_OK;
            break;
        }
        next.microphone_verified_off = true;
        status = begin_safe_cleanup(&next, &next_result);
        break;

    case GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN:
    case GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DISCARD:
        next.evidence = *evidence;
        next.codec_open = false;
        if (!success) {
            next.failure = GUMI_CAPTURE_SELFTEST_FAILURE_CODEC_CLOSE;
            next.should_pass = false;
        } else if (action == GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN) {
            next.failure = evidence_failure(evidence);
            next.should_pass = next.failure == GUMI_CAPTURE_SELFTEST_FAILURE_NONE;
        }
        status = begin_safe_cleanup(&next, &next_result);
        break;

    case GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY:
        if (!success) {
            next.failure = GUMI_CAPTURE_SELFTEST_FAILURE_PRIVACY_DEASSERT;
            next.should_pass = false;
            enter_safe_failure(&next, next.failure);
            break;
        }
        next.privacy_asserted = false;
        status = begin_safe_cleanup(&next, &next_result);
        break;

    default:
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_ARGUMENT;
    }

    if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return status;
    }
    return commit(state, &next, result, &next_result);
}

gumi_capture_selftest_status gumi_capture_selftest_async_port_failed(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    gumi_capture_selftest_result *result
)
{
    gumi_capture_selftest next;
    gumi_capture_selftest_result next_result;
    gumi_capture_selftest_status status = validate_call(state, at_ms, result);

    if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return status;
    }
    next = *state;
    clear_result(&next_result);
    next.current_ms = at_ms;
    next.failure = GUMI_CAPTURE_SELFTEST_FAILURE_ASYNC_PORT;
    next.should_pass = false;

    if (state->action_outstanding &&
        (state->outstanding_action == GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE ||
         state->outstanding_action == GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE)) {
        enter_microphone_unknown(&next, GUMI_CAPTURE_SELFTEST_FAILURE_ASYNC_PORT);
    } else if (state->action_outstanding) {
        /* The effect may have partially committed. Do not race it or permit re-arm. */
        next.action_outstanding = false;
        enter_safe_failure(&next, GUMI_CAPTURE_SELFTEST_FAILURE_ASYNC_PORT);
    } else {
        status = begin_safe_cleanup(&next, &next_result);
        if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
            return status;
        }
    }
    return commit(state, &next, result, &next_result);
}

static void encode_u32_le(uint8_t *output, uint32_t value)
{
    output[0] = (uint8_t)(value & UINT32_C(0xff));
    output[1] = (uint8_t)((value >> 8U) & UINT32_C(0xff));
    output[2] = (uint8_t)((value >> 16U) & UINT32_C(0xff));
    output[3] = (uint8_t)((value >> 24U) & UINT32_C(0xff));
}

gumi_capture_selftest_status gumi_capture_selftest_encode_status(
    const gumi_capture_selftest *state,
    uint8_t output[GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE]
)
{
    uint8_t flags = UINT8_C(0);
    uint64_t remaining = UINT64_C(0);
    gumi_capture_selftest_status status;

    if (state == NULL || output == NULL) {
        return GUMI_CAPTURE_SELFTEST_STATUS_INVALID_ARGUMENT;
    }
    status = validate_state(state);
    if (status != GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return status;
    }
    memset(output, 0, GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE);
    if (state->privacy_asserted) {
        flags |= UINT8_C(1) << 0U;
    }
    if (state->microphone_verified_off) {
        flags |= UINT8_C(1) << 1U;
    }
    if (state->codec_open) {
        flags |= UINT8_C(1) << 2U;
    }
    if (state->phase == GUMI_CAPTURE_SELFTEST_PHASE_ARMED) {
        flags |= UINT8_C(1) << 3U;
        remaining = state->lease_expires_ms - state->current_ms;
    }
    if (state->phase == GUMI_CAPTURE_SELFTEST_PHASE_EXERCISING) {
        flags |= UINT8_C(1) << 4U;
    }
    if (state->phase == GUMI_CAPTURE_SELFTEST_PHASE_PASSED) {
        flags |= UINT8_C(1) << 5U;
    }
    if (state->phase == GUMI_CAPTURE_SELFTEST_PHASE_FAILED_MICROPHONE_UNKNOWN) {
        flags |= UINT8_C(1) << 6U;
    }
    if (state->recovery_transport_present) {
        flags |= UINT8_C(1) << 7U;
    }

    output[0] = GUMI_CAPTURE_SELFTEST_STATUS_WIRE_VERSION;
    output[1] = (uint8_t)state->phase;
    output[2] = (uint8_t)state->failure;
    output[3] = flags;
    encode_u32_le(&output[4], state->attempt);
    encode_u32_le(&output[8], state->evidence.pcm_blocks);
    encode_u32_le(&output[12], state->evidence.pcm_samples);
    encode_u32_le(&output[16], state->evidence.opus_packets);
    encode_u32_le(&output[20], state->evidence.discarded_samples);
    encode_u32_le(&output[24], (uint32_t)state->evidence.terminal_error);
    encode_u32_le(
        &output[28],
        remaining > UINT32_MAX ? UINT32_MAX : (uint32_t)remaining
    );
    return GUMI_CAPTURE_SELFTEST_STATUS_OK;
}
