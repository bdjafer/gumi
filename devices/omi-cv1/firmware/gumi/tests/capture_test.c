#include "gumi/capture.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned int tests_run;

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #condition); \
        exit(EXIT_FAILURE); \
    } \
} while (0)

#define CHECK_STATUS(expression, expected) do { \
    gumi_capture_status actual_status = (expression); \
    CHECK(actual_status == (expected)); \
} while (0)

static void expect_action(
    const gumi_capture_result *result,
    size_t index,
    gumi_capture_action_type type,
    uint64_t transition_id
)
{
    CHECK(result->action_count > index);
    CHECK(result->actions[index].type == type);
    CHECK(result->actions[index].transition_id == transition_id);
}

static void expect_event(
    const gumi_capture_result *result,
    size_t index,
    gumi_capture_event_type type,
    gumi_capture_reason reason
)
{
    CHECK(result->event_count > index);
    CHECK(result->events[index].type == type);
    CHECK(result->events[index].reason == reason);
}

static gumi_capture_supervisor operational_idle(void)
{
    gumi_capture_supervisor state;
    gumi_capture_result result;

    CHECK_STATUS(gumi_capture_supervisor_init(&state), GUMI_CAPTURE_STATUS_OK);
    CHECK(state.phase == GUMI_CAPTURE_PHASE_BOOTING);
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF);
    CHECK(!gumi_capture_base_audio_is_permitted(&state));
    CHECK(!gumi_capture_voice_audio_is_permitted(&state));
    CHECK_STATUS(
        gumi_capture_required_self_tests_passed(&state, 0U, &result),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK(result.event_count == 1U);
    expect_event(&result, 0U, GUMI_CAPTURE_EVENT_BOOT_READY, GUMI_CAPTURE_REASON_NONE);
    CHECK(result.haptic == GUMI_CAPTURE_HAPTIC_READY);
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE);
    return state;
}

static uint64_t start_base_recording(gumi_capture_supervisor *state, uint64_t start_at)
{
    gumi_capture_result result;
    uint64_t transition_id;

    CHECK_STATUS(
        gumi_capture_request_base_start(state, start_at, &result),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK(result.action_count == 1U);
    transition_id = state->transition_id;
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_ASSERT_PRIVACY_GUARD, transition_id);
    CHECK_STATUS(
        gumi_capture_complete(
            state,
            start_at + 1U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED,
            &result
        ),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK(result.action_count == 2U);
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_PREPARE_LOCAL_DURABILITY, transition_id);
    expect_action(&result, 1U, GUMI_CAPTURE_ACTION_ACQUIRE_MICROPHONE, transition_id);
    CHECK_STATUS(
        gumi_capture_complete(
            state,
            start_at + 2U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRED,
            &result
        ),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK(!gumi_capture_base_audio_is_permitted(state));
    CHECK_STATUS(
        gumi_capture_complete(
            state,
            start_at + 3U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_READY,
            &result
        ),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK(result.event_count == 1U);
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_BASE_RECORDING_STARTED,
        GUMI_CAPTURE_REASON_NONE
    );
    CHECK(result.haptic == GUMI_CAPTURE_HAPTIC_RECORDING_STARTED);
    CHECK(state->phase == GUMI_CAPTURE_PHASE_BASE_ACTIVE);
    CHECK(gumi_capture_base_audio_is_permitted(state));
    return state->active_recording_id;
}

static gumi_capture_realtime_admission admission_until(uint64_t expires_at_ms)
{
    const gumi_capture_realtime_admission admission = {
        .authenticated = true,
        .token = UINT64_C(0xa11ce),
        .expires_at_ms = expires_at_ms,
    };
    return admission;
}

static void test_boot_is_verified_off_and_never_resumes_capture(void)
{
    gumi_capture_supervisor state = operational_idle();

    CHECK(state.power == GUMI_CAPTURE_POWER_OPERATIONAL);
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF);
    CHECK(!state.base_recording_active);
    CHECK(state.voice == GUMI_CAPTURE_VOICE_INACTIVE);
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_OFF);
    tests_run += 1U;
}

static void test_base_audio_opens_only_after_guard_mic_and_durability(void)
{
    gumi_capture_supervisor state = operational_idle();
    uint64_t recording_id = start_base_recording(&state, 10U);

    CHECK(recording_id != 0U);
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_ACQUIRED);
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_RECORDING);
    tests_run += 1U;
}

static void test_guard_failure_refuses_before_any_microphone_action(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_result result;
    uint64_t transition_id;

    CHECK_STATUS(gumi_capture_request_base_start(&state, 1U, &result), GUMI_CAPTURE_STATUS_OK);
    transition_id = state.transition_id;
    CHECK_STATUS(
        gumi_capture_complete(
            &state,
            2U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_FAILED,
            &result
        ),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK(result.action_count == 0U);
    CHECK(result.event_count == 1U);
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_BASE_RECORDING_REFUSED,
        GUMI_CAPTURE_REASON_PRIVACY_OUTPUT_UNAVAILABLE
    );
    CHECK(result.haptic == GUMI_CAPTURE_HAPTIC_FAULT);
    CHECK(state.phase == GUMI_CAPTURE_PHASE_FATAL_IDLE);
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF);
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_OUTPUT_UNAVAILABLE);
    tests_run += 1U;
}

static void test_microphone_acquire_failure_refuses_and_verifies_release(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_supervisor before;
    gumi_capture_result result;
    uint64_t start_transition;
    uint64_t cleanup_transition;

    CHECK_STATUS(gumi_capture_request_base_start(&state, 1U, &result), 0);
    start_transition = state.transition_id;
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 2U, start_transition,
            GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 3U, start_transition,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRE_FAILED, &result
        ), 0
    );
    cleanup_transition = state.transition_id;
    CHECK(cleanup_transition != start_transition && cleanup_transition != 0U);
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_BASE_RECORDING_REFUSED,
        GUMI_CAPTURE_REASON_MICROPHONE_UNAVAILABLE
    );
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE, cleanup_transition);
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_RELEASING);
    CHECK(!gumi_capture_base_audio_is_permitted(&state));

    before = state;
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 4U, start_transition,
            GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_READY, &result
        ),
        GUMI_CAPTURE_STATUS_STALE_TRANSITION
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 5U, cleanup_transition,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED, &result
        ), 0
    );
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_DEASSERT_PRIVACY_GUARD, cleanup_transition);
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE);
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF);
    CHECK(state.fault == GUMI_CAPTURE_FAULT_RECOVERABLE);
    tests_run += 1U;
}

static void test_base_stop_keeps_privacy_until_finalization_and_release(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_result result;
    uint64_t recording_id = start_base_recording(&state, 1U);
    uint64_t transition_id;

    CHECK_STATUS(gumi_capture_request_base_stop(&state, 10U, &result), GUMI_CAPTURE_STATUS_OK);
    transition_id = state.transition_id;
    CHECK(result.action_count == 1U);
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_FINALIZE_LOCAL_RECORDING, transition_id);
    CHECK(!gumi_capture_base_audio_is_permitted(&state));
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_RELEASING);
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_RECORDING);

    CHECK_STATUS(
        gumi_capture_complete(
            &state,
            11U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_LOCAL_RECORDING_FINALIZED,
            &result
        ),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK(result.action_count == 1U);
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE, transition_id);
    CHECK_STATUS(
        gumi_capture_complete(
            &state,
            12U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED,
            &result
        ),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK(result.action_count == 1U);
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_DEASSERT_PRIVACY_GUARD, transition_id);
    CHECK(result.event_count == 1U);
    expect_event(&result, 0U, GUMI_CAPTURE_EVENT_BASE_RECORDING_STOPPED, GUMI_CAPTURE_REASON_NONE);
    CHECK(result.events[0].recording_id == recording_id);
    CHECK(result.haptic == GUMI_CAPTURE_HAPTIC_RECORDING_STOPPED);
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE);
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_OFF);
    tests_run += 1U;
}

static void test_base_stop_finalize_failure_reports_interrupted_recording(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_result result;
    uint64_t recording_id = start_base_recording(&state, 1U);
    uint64_t transition_id;

    CHECK_STATUS(gumi_capture_request_base_stop(&state, 10U, &result), 0);
    transition_id = state.transition_id;
    expect_action(
        &result,
        0U,
        GUMI_CAPTURE_ACTION_FINALIZE_LOCAL_RECORDING,
        transition_id
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state,
            11U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_LOCAL_RECORDING_INTERRUPTED,
            &result
        ),
        0
    );
    expect_action(
        &result,
        0U,
        GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE,
        transition_id
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state,
            12U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED,
            &result
        ),
        0
    );
    expect_action(
        &result,
        0U,
        GUMI_CAPTURE_ACTION_DEASSERT_PRIVACY_GUARD,
        transition_id
    );
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_BASE_RECORDING_STOPPED,
        GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE
    );
    CHECK(result.events[0].recording_id == recording_id);
    CHECK(result.haptic == GUMI_CAPTURE_HAPTIC_FAULT);
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE);
    CHECK(state.fault == GUMI_CAPTURE_FAULT_RECOVERABLE);
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF);
    tests_run += 1U;
}

static void test_voice_from_idle_waits_for_every_effect_and_lease(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_realtime_admission admission = admission_until(1000U);
    gumi_capture_result result;
    uint64_t transition_id;

    CHECK_STATUS(
        gumi_capture_request_voice_start(&state, 10U, &admission, &result),
        GUMI_CAPTURE_STATUS_OK
    );
    transition_id = state.transition_id;
    CHECK_STATUS(
        gumi_capture_complete(
            &state,
            11U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED,
            &result
        ),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK(result.action_count == 3U);
    expect_action(&result, 2U, GUMI_CAPTURE_ACTION_OPEN_REALTIME_ROUTE, transition_id);
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_VOICE_TURN);
    CHECK_STATUS(
        gumi_capture_complete(
            &state,
            12U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_READY,
            &result
        ),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state,
            13U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_READY,
            &result
        ),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK(!gumi_capture_voice_audio_is_permitted(&state));
    CHECK_STATUS(
        gumi_capture_complete(
            &state,
            14U,
            transition_id,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRED,
            &result
        ),
        GUMI_CAPTURE_STATUS_OK
    );
    CHECK(gumi_capture_voice_audio_is_permitted(&state));
    expect_event(&result, 0U, GUMI_CAPTURE_EVENT_VOICE_TURN_STARTED, GUMI_CAPTURE_REASON_NONE);
    CHECK(result.haptic == GUMI_CAPTURE_HAPTIC_VOICE_READY);
    CHECK_STATUS(gumi_capture_request_voice_end(&state, 20U, &result), 0);
    transition_id = state.transition_id;
    CHECK(result.action_count == 3U);
    expect_action(
        &result, 0U, GUMI_CAPTURE_ACTION_CLOSE_REALTIME_ROUTE, transition_id
    );
    expect_action(
        &result, 1U, GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE, transition_id
    );
    expect_action(
        &result, 2U, GUMI_CAPTURE_ACTION_FINALIZE_LOCAL_RECORDING, transition_id
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 21U, transition_id,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_CLOSED, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 22U, transition_id,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 23U, transition_id,
            GUMI_CAPTURE_COMPLETION_LOCAL_RECORDING_FINALIZED, &result
        ), 0
    );
    expect_event(
        &result, 0U, GUMI_CAPTURE_EVENT_VOICE_TURN_ENDED,
        GUMI_CAPTURE_REASON_NONE
    );
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE);
    CHECK(!state.local_recording_open);
    tests_run += 1U;
}

static void test_durability_prepare_failure_closes_voice_resources(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_realtime_admission admission = admission_until(1000U);
    gumi_capture_result result;
    uint64_t start_transition;
    uint64_t cleanup_transition;

    CHECK_STATUS(gumi_capture_request_voice_start(&state, 10U, &admission, &result), 0);
    start_transition = state.transition_id;
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 11U, start_transition,
            GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 12U, start_transition,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRED, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 13U, start_transition,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_READY, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 14U, start_transition,
            GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_FAILED, &result
        ), 0
    );
    cleanup_transition = state.transition_id;
    CHECK(cleanup_transition != start_transition && cleanup_transition != 0U);
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_VOICE_TURN_REFUSED,
        GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE
    );
    CHECK(result.action_count == 2U);
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_CLOSE_REALTIME_ROUTE, cleanup_transition);
    expect_action(&result, 1U, GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE, cleanup_transition);
    CHECK(!gumi_capture_voice_audio_is_permitted(&state));
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 15U, cleanup_transition,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_CLOSED, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 16U, cleanup_transition,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED, &result
        ), 0
    );
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE);
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF);
    CHECK(state.fault == GUMI_CAPTURE_FAULT_RECOVERABLE);
    tests_run += 1U;
}

static void test_missing_or_expired_voice_admission_is_a_non_mutating_refusal(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_realtime_admission missing = { false, 0U, 0U };
    gumi_capture_realtime_admission expired = admission_until(20U);
    gumi_capture_result result;

    CHECK_STATUS(
        gumi_capture_request_voice_start(&state, 10U, &missing, &result),
        GUMI_CAPTURE_STATUS_OK
    );
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_VOICE_TURN_REFUSED,
        GUMI_CAPTURE_REASON_REALTIME_ADMISSION_UNAVAILABLE
    );
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE && state.transition_id == 0U);
    CHECK_STATUS(
        gumi_capture_request_voice_start(&state, 20U, &expired, &result),
        GUMI_CAPTURE_STATUS_OK
    );
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_VOICE_TURN_REFUSED,
        GUMI_CAPTURE_REASON_REALTIME_ADMISSION_EXPIRED
    );
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF);
    CHECK(!gumi_capture_voice_audio_is_permitted(&state));
    tests_run += 1U;
}

static void test_lease_expiry_at_commit_cancels_and_invalidates_old_transition(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_realtime_admission admission = admission_until(15U);
    gumi_capture_result result;
    gumi_capture_supervisor before;
    uint64_t start_transition;
    uint64_t stop_transition;

    CHECK_STATUS(gumi_capture_request_voice_start(&state, 10U, &admission, &result), 0);
    start_transition = state.transition_id;
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 11U, start_transition,
            GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED, &result
        ),
        0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 12U, start_transition,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRED, &result
        ),
        0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 13U, start_transition,
            GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_READY, &result
        ),
        0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 15U, start_transition,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_READY, &result
        ),
        0
    );
    stop_transition = state.transition_id;
    CHECK(stop_transition != start_transition && stop_transition != 0U);
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_VOICE_TURN_REFUSED,
        GUMI_CAPTURE_REASON_REALTIME_ADMISSION_EXPIRED
    );
    CHECK(result.action_count == 3U);
    expect_action(
        &result,
        2U,
        GUMI_CAPTURE_ACTION_COMMIT_LAST_DURABLE_FRAME,
        stop_transition
    );
    CHECK(!gumi_capture_voice_audio_is_permitted(&state));

    before = state;
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 16U, start_transition,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRED, &result
        ),
        GUMI_CAPTURE_STATUS_STALE_TRANSITION
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 16U, stop_transition,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_CLOSED, &result
        ),
        0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 17U, stop_transition,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED, &result
        ),
        0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 18U, stop_transition,
            GUMI_CAPTURE_COMPLETION_LAST_DURABLE_FRAME_COMMITTED, &result
        ),
        0
    );
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE);
    tests_run += 1U;
}

static void test_voice_release_before_guard_completion_cancels_without_acquisition(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_realtime_admission admission = admission_until(1000U);
    gumi_capture_result result;
    uint64_t abandoned_transition;

    CHECK_STATUS(gumi_capture_request_voice_start(&state, 10U, &admission, &result), 0);
    abandoned_transition = state.transition_id;
    CHECK_STATUS(gumi_capture_request_voice_end(&state, 11U, &result), 0);
    CHECK(result.action_count == 0U);
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE);
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF);
    CHECK(state.transition_id == 0U);
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 12U, abandoned_transition,
            GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED, &result
        ),
        GUMI_CAPTURE_STATUS_STALE_TRANSITION
    );
    tests_run += 1U;
}

static void test_voice_overlay_preserves_base_identity_and_never_releases_microphone(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_realtime_admission admission = admission_until(1000U);
    gumi_capture_result result;
    uint64_t recording_id = start_base_recording(&state, 1U);
    uint64_t transition_id;

    CHECK_STATUS(gumi_capture_request_voice_start(&state, 10U, &admission, &result), 0);
    transition_id = state.transition_id;
    CHECK(result.action_count == 1U);
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_OPEN_REALTIME_ROUTE, transition_id);
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_RECORDING);
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 11U, transition_id,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_READY, &result
        ),
        0
    );
    CHECK(state.active_recording_id == recording_id);
    CHECK(gumi_capture_base_audio_is_permitted(&state));
    CHECK(gumi_capture_voice_audio_is_permitted(&state));
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_VOICE_TURN);

    CHECK_STATUS(gumi_capture_request_voice_end(&state, 20U, &result), 0);
    transition_id = state.transition_id;
    CHECK(result.action_count == 1U);
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_CLOSE_REALTIME_ROUTE, transition_id);
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 21U, transition_id,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_CLOSED, &result
        ),
        0
    );
    CHECK(state.phase == GUMI_CAPTURE_PHASE_BASE_ACTIVE);
    CHECK(state.active_recording_id == recording_id);
    CHECK(gumi_capture_base_audio_is_permitted(&state));
    CHECK(!gumi_capture_voice_audio_is_permitted(&state));
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_RECORDING);
    tests_run += 1U;
}

static void test_realtime_route_failure_preserves_base_recording(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_realtime_admission admission = admission_until(1000U);
    gumi_capture_result result;
    uint64_t recording_id = start_base_recording(&state, 1U);
    uint64_t start_transition;
    uint64_t cleanup_transition;

    CHECK_STATUS(gumi_capture_request_voice_start(&state, 10U, &admission, &result), 0);
    start_transition = state.transition_id;
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 11U, start_transition,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_FAILED, &result
        ), 0
    );
    cleanup_transition = state.transition_id;
    CHECK(cleanup_transition != start_transition && cleanup_transition != 0U);
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_VOICE_TURN_REFUSED,
        GUMI_CAPTURE_REASON_REALTIME_ROUTE_UNAVAILABLE
    );
    CHECK(result.action_count == 1U);
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_CLOSE_REALTIME_ROUTE, cleanup_transition);
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 12U, cleanup_transition,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_CLOSED, &result
        ), 0
    );
    CHECK(state.phase == GUMI_CAPTURE_PHASE_BASE_ACTIVE);
    CHECK(state.active_recording_id == recording_id);
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_ACQUIRED);
    CHECK(gumi_capture_base_audio_is_permitted(&state));
    CHECK(!gumi_capture_voice_audio_is_permitted(&state));
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_RECORDING);
    tests_run += 1U;
}

static void test_storage_full_stops_only_after_durable_boundary(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_result result;
    uint64_t recording_id = start_base_recording(&state, 1U);
    uint64_t transition_id;

    CHECK_STATUS(
        gumi_capture_set_storage_state(&state, 10U, GUMI_CAPTURE_STORAGE_FULL, &result),
        0
    );
    transition_id = state.transition_id;
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_SAFE_CAPTURE_STOP_REQUESTED,
        GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE
    );
    expect_action(
        &result,
        0U,
        GUMI_CAPTURE_ACTION_COMMIT_LAST_DURABLE_FRAME,
        transition_id
    );
    CHECK(!gumi_capture_base_audio_is_permitted(&state));
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_RECORDING);
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 11U, transition_id,
            GUMI_CAPTURE_COMPLETION_LAST_DURABLE_FRAME_COMMITTED, &result
        ),
        0
    );
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE, transition_id);
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 12U, transition_id,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED, &result
        ),
        0
    );
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_BASE_RECORDING_STOPPED,
        GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE
    );
    CHECK(result.events[0].recording_id == recording_id);
    CHECK(state.storage == GUMI_CAPTURE_STORAGE_FULL);
    CHECK(state.fault == GUMI_CAPTURE_FAULT_RECOVERABLE);
    CHECK(result.haptic == GUMI_CAPTURE_HAPTIC_FAULT);
    tests_run += 1U;
}

static void test_microphone_pipeline_failure_does_not_falsify_storage_health(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_result result;
    uint64_t transition_id;

    (void)start_base_recording(&state, 1U);
    CHECK_STATUS(
        gumi_capture_recoverable_pipeline_failed(
            &state,
            10U,
            GUMI_CAPTURE_REASON_MICROPHONE_UNAVAILABLE,
            &result
        ),
        0
    );
    transition_id = state.transition_id;
    CHECK(state.storage == GUMI_CAPTURE_STORAGE_HEALTHY);
    CHECK(state.fault == GUMI_CAPTURE_FAULT_RECOVERABLE);
    CHECK(!gumi_capture_base_audio_is_permitted(&state));
    expect_action(
        &result,
        0U,
        GUMI_CAPTURE_ACTION_COMMIT_LAST_DURABLE_FRAME,
        transition_id
    );
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_SAFE_CAPTURE_STOP_REQUESTED,
        GUMI_CAPTURE_REASON_MICROPHONE_UNAVAILABLE
    );
    tests_run += 1U;
}

static void test_storage_failure_cancels_a_start_before_it_can_commit(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_result result;
    uint64_t abandoned_transition;
    uint64_t stop_transition;

    CHECK_STATUS(gumi_capture_request_base_start(&state, 10U, &result), 0);
    abandoned_transition = state.transition_id;
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 11U, abandoned_transition,
            GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED, &result
        ),
        0
    );
    CHECK_STATUS(
        gumi_capture_set_storage_state(
            &state, 12U, GUMI_CAPTURE_STORAGE_FULL, &result
        ),
        0
    );
    stop_transition = state.transition_id;
    CHECK(stop_transition != abandoned_transition);
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_BASE_RECORDING_REFUSED,
        GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE
    );
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE, stop_transition);
    CHECK(!gumi_capture_base_audio_is_permitted(&state));
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 13U, abandoned_transition,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRED, &result
        ),
        GUMI_CAPTURE_STATUS_STALE_TRANSITION
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 14U, stop_transition,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED, &result
        ),
        0
    );
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE);
    CHECK(state.storage == GUMI_CAPTURE_STORAGE_FULL);
    tests_run += 1U;
}

static void test_storage_failure_stops_voice_from_idle(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_realtime_admission admission = admission_until(1000U);
    gumi_capture_result result;
    uint64_t start_transition;
    uint64_t stop_transition;

    CHECK_STATUS(gumi_capture_request_voice_start(&state, 10U, &admission, &result), 0);
    start_transition = state.transition_id;
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 11U, start_transition,
            GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 12U, start_transition,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRED, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 13U, start_transition,
            GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_READY, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 14U, start_transition,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_READY, &result
        ), 0
    );
    CHECK(gumi_capture_voice_audio_is_permitted(&state));
    CHECK_STATUS(
        gumi_capture_set_storage_state(
            &state, 20U, GUMI_CAPTURE_STORAGE_CORRUPT, &result
        ), 0
    );
    stop_transition = state.transition_id;
    CHECK(result.action_count == 3U);
    CHECK(!gumi_capture_voice_audio_is_permitted(&state));
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_SAFE_CAPTURE_STOP_REQUESTED,
        GUMI_CAPTURE_REASON_STORAGE_CORRUPT
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 21U, stop_transition,
            GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_CLOSED, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 22U, stop_transition,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED, &result
        ), 0
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 23U, stop_transition,
            GUMI_CAPTURE_COMPLETION_LAST_DURABLE_FRAME_COMMITTED, &result
        ), 0
    );
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_VOICE_TURN_ENDED,
        GUMI_CAPTURE_REASON_STORAGE_CORRUPT
    );
    CHECK(result.haptic == GUMI_CAPTURE_HAPTIC_FAULT);
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE);
    tests_run += 1U;
}

static void test_active_privacy_failure_closes_audio_before_forcing_release(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_result result;
    uint64_t recording_id = start_base_recording(&state, 1U);
    uint64_t transition_id;

    CHECK_STATUS(gumi_capture_privacy_output_failed(&state, 10U, &result), 0);
    transition_id = state.transition_id;
    CHECK(!gumi_capture_base_audio_is_permitted(&state));
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_UNKNOWN);
    CHECK(gumi_capture_privacy_pattern_for(&state) == GUMI_CAPTURE_PRIVACY_OUTPUT_UNAVAILABLE);
    expect_action(&result, 0U, GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE, transition_id);
    expect_action(
        &result,
        1U,
        GUMI_CAPTURE_ACTION_COMMIT_LAST_DURABLE_FRAME,
        transition_id
    );
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_SAFE_CAPTURE_STOP_REQUESTED,
        GUMI_CAPTURE_REASON_PRIVACY_OUTPUT_UNAVAILABLE
    );
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 11U, transition_id,
            GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED, &result
        ),
        0
    );
    CHECK(result.action_count == 0U);
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 12U, transition_id,
            GUMI_CAPTURE_COMPLETION_LAST_DURABLE_FRAME_COMMITTED, &result
        ),
        0
    );
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_BASE_RECORDING_STOPPED,
        GUMI_CAPTURE_REASON_PRIVACY_OUTPUT_UNAVAILABLE
    );
    CHECK(result.events[0].recording_id == recording_id);
    CHECK(state.phase == GUMI_CAPTURE_PHASE_FATAL_IDLE);
    tests_run += 1U;
}

static void test_stale_and_regressing_completions_do_not_mutate_state(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_supervisor before;
    gumi_capture_result result;
    uint64_t transition_id;

    CHECK_STATUS(gumi_capture_request_base_start(&state, 10U, &result), 0);
    transition_id = state.transition_id;
    before = state;
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 11U, transition_id + 1U,
            GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED, &result
        ),
        GUMI_CAPTURE_STATUS_STALE_TRANSITION
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK_STATUS(
        gumi_capture_complete(
            &state, 9U, transition_id,
            GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED, &result
        ),
        GUMI_CAPTURE_STATUS_TIME_REGRESSION
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    tests_run += 1U;
}

static void test_watchdog_closes_identity_and_returns_to_off_boot(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_result result;
    uint64_t recording_id = start_base_recording(&state, 1U);

    CHECK_STATUS(gumi_capture_watchdog_boot(&state, 10U, &result), 0);
    expect_event(
        &result,
        0U,
        GUMI_CAPTURE_EVENT_CAPTURE_DISCONTINUITY,
        GUMI_CAPTURE_REASON_WATCHDOG_RESET
    );
    CHECK(result.events[0].recording_id == recording_id);
    CHECK(state.phase == GUMI_CAPTURE_PHASE_BOOTING);
    CHECK(state.mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF);
    CHECK(!state.base_recording_active);
    CHECK(!gumi_capture_base_audio_is_permitted(&state));
    CHECK_STATUS(gumi_capture_required_self_tests_passed(&state, 20U, &result), 0);
    CHECK(state.phase == GUMI_CAPTURE_PHASE_IDLE);
    CHECK(!state.base_recording_active);
    tests_run += 1U;
}

static void test_maintenance_excludes_capture(void)
{
    gumi_capture_supervisor state = operational_idle();
    gumi_capture_result result;

    CHECK_STATUS(gumi_capture_set_maintenance_exclusive(&state, 1U, true, &result), 0);
    CHECK_STATUS(
        gumi_capture_request_base_start(&state, 2U, &result),
        GUMI_CAPTURE_STATUS_INVALID_STATE
    );
    CHECK(state.maintenance_exclusive);
    CHECK_STATUS(gumi_capture_set_maintenance_exclusive(&state, 3U, false, &result), 0);
    CHECK_STATUS(gumi_capture_request_base_start(&state, 4U, &result), 0);
    tests_run += 1U;
}

int main(void)
{
    test_boot_is_verified_off_and_never_resumes_capture();
    test_base_audio_opens_only_after_guard_mic_and_durability();
    test_guard_failure_refuses_before_any_microphone_action();
    test_microphone_acquire_failure_refuses_and_verifies_release();
    test_base_stop_keeps_privacy_until_finalization_and_release();
    test_base_stop_finalize_failure_reports_interrupted_recording();
    test_voice_from_idle_waits_for_every_effect_and_lease();
    test_durability_prepare_failure_closes_voice_resources();
    test_missing_or_expired_voice_admission_is_a_non_mutating_refusal();
    test_lease_expiry_at_commit_cancels_and_invalidates_old_transition();
    test_voice_release_before_guard_completion_cancels_without_acquisition();
    test_voice_overlay_preserves_base_identity_and_never_releases_microphone();
    test_realtime_route_failure_preserves_base_recording();
    test_storage_full_stops_only_after_durable_boundary();
    test_microphone_pipeline_failure_does_not_falsify_storage_health();
    test_storage_failure_cancels_a_start_before_it_can_commit();
    test_storage_failure_stops_voice_from_idle();
    test_active_privacy_failure_closes_audio_before_forcing_release();
    test_stale_and_regressing_completions_do_not_mutate_state();
    test_watchdog_closes_identity_and_returns_to_off_boot();
    test_maintenance_excludes_capture();

    printf("PASS: %u portable capture-supervisor tests\n", tests_run);
    return EXIT_SUCCESS;
}
