#include "gumi/capture_selftest.h"

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
    gumi_capture_selftest_status actual_status = (expression); \
    CHECK(actual_status == (expected)); \
} while (0)

static gumi_capture_selftest new_state(void)
{
    gumi_capture_selftest state;

    CHECK_STATUS(
        gumi_capture_selftest_init(&state, true, true),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(state.phase == GUMI_CAPTURE_SELFTEST_PHASE_IDLE);
    return state;
}

static uint64_t expect_action(
    const gumi_capture_selftest_result *result,
    gumi_capture_selftest_action_type action
)
{
    CHECK(result->has_action);
    CHECK(result->action.type == action);
    CHECK(result->action.transition_id != UINT64_C(0));
    return result->action.transition_id;
}

static uint64_t arm_and_confirm(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    gumi_capture_selftest_result *result
)
{
    CHECK_STATUS(
        gumi_capture_selftest_arm(state, at_ms, at_ms + UINT64_C(15000)),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK_STATUS(
        gumi_capture_selftest_confirm(state, at_ms + UINT64_C(2000), result),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    return expect_action(result, GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY);
}

static uint64_t complete_and_expect(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    uint64_t transition,
    gumi_capture_selftest_action_type completed,
    bool success,
    const gumi_capture_selftest_evidence *evidence,
    gumi_capture_selftest_action_type expected,
    gumi_capture_selftest_result *result
)
{
    CHECK_STATUS(
        gumi_capture_selftest_complete(
            state, at_ms, transition, completed, success, evidence, result
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    return expect_action(result, expected);
}

static uint64_t enter_exercise(
    gumi_capture_selftest *state,
    gumi_capture_selftest_result *result
)
{
    uint64_t transition = arm_and_confirm(state, UINT64_C(10), result);

    transition = complete_and_expect(
        state, UINT64_C(2011), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY, true, NULL,
        GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC, result
    );
    transition = complete_and_expect(
        state, UINT64_C(2012), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC, true, NULL,
        GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE, result
    );
    CHECK_STATUS(
        gumi_capture_selftest_complete(
            state, UINT64_C(2013), transition,
            GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE, true, NULL, result
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(!result->has_action);
    CHECK(state->phase == GUMI_CAPTURE_SELFTEST_PHASE_EXERCISING);
    CHECK(state->privacy_asserted);
    CHECK(state->codec_open);
    CHECK(!state->microphone_verified_off);
    return state->exercise_ends_ms;
}

static gumi_capture_selftest_evidence passing_evidence(void)
{
    const gumi_capture_selftest_evidence evidence = {
        UINT32_C(150),
        UINT32_C(48000),
        UINT32_C(150),
        UINT32_C(0),
        INT32_C(0),
    };
    return evidence;
}

static void complete_successful_attempt(
    gumi_capture_selftest *state,
    gumi_capture_selftest_result *result
)
{
    gumi_capture_selftest_evidence evidence = passing_evidence();
    uint64_t end_ms = enter_exercise(state, result);
    uint64_t transition;

    CHECK_STATUS(
        gumi_capture_selftest_advance(state, end_ms, result),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    transition = expect_action(result, GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE);
    transition = complete_and_expect(
        state, end_ms + UINT64_C(1), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE, true, NULL,
        GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN, result
    );
    transition = complete_and_expect(
        state, end_ms + UINT64_C(2), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN, true, &evidence,
        GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY, result
    );
    CHECK_STATUS(
        gumi_capture_selftest_complete(
            state, end_ms + UINT64_C(3), transition,
            GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY, true, NULL, result
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(!result->has_action);
}

static void test_success_orders_privacy_microphone_codec_and_evidence(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest_result result;

    complete_successful_attempt(&state, &result);
    CHECK(state.phase == GUMI_CAPTURE_SELFTEST_PHASE_PASSED);
    CHECK(state.failure == GUMI_CAPTURE_SELFTEST_FAILURE_NONE);
    CHECK(state.microphone_verified_off);
    CHECK(!state.privacy_asserted);
    CHECK(!state.codec_open);
    CHECK(state.evidence.pcm_samples == UINT32_C(48000));
    CHECK(state.evidence.opus_packets == UINT32_C(150));
    tests_run += 1U;
}

static void test_confirmation_expiry_never_emits_effect(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest_result result = {0};

    CHECK_STATUS(
        gumi_capture_selftest_arm(&state, UINT64_C(5), UINT64_C(15005)),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK_STATUS(
        gumi_capture_selftest_advance(&state, UINT64_C(15005), &result),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(!result.has_action);
    CHECK(state.phase == GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE);
    CHECK(state.failure == GUMI_CAPTURE_SELFTEST_FAILURE_CONFIRMATION_EXPIRED);
    CHECK_STATUS(
        gumi_capture_selftest_confirm(&state, UINT64_C(15005), &result),
        GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE
    );
    tests_run += 1U;
}

static void test_privacy_failure_prevents_codec_and_microphone(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest_result result;
    uint64_t transition = arm_and_confirm(&state, UINT64_C(0), &result);

    CHECK_STATUS(
        gumi_capture_selftest_complete(
            &state, UINT64_C(2001), transition,
            GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY, false, NULL, &result
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(!result.has_action);
    CHECK(state.phase == GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE);
    CHECK(state.failure == GUMI_CAPTURE_SELFTEST_FAILURE_PRIVACY_ASSERT);
    CHECK(state.microphone_verified_off);
    CHECK(!state.codec_open);
    CHECK_STATUS(
        gumi_capture_selftest_arm(
            &state, UINT64_C(2002), UINT64_C(17002)
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE
    );
    tests_run += 1U;
}

static void test_microphone_acquire_failure_discards_codec_then_removes_red(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest_result result;
    gumi_capture_selftest_evidence evidence = {0};
    uint64_t transition = arm_and_confirm(&state, UINT64_C(0), &result);

    transition = complete_and_expect(
        &state, UINT64_C(2001), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY, true, NULL,
        GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC, &result
    );
    transition = complete_and_expect(
        &state, UINT64_C(2002), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC, true, NULL,
        GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE, &result
    );
    transition = complete_and_expect(
        &state, UINT64_C(2003), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE, false, NULL,
        GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DISCARD, &result
    );
    transition = complete_and_expect(
        &state, UINT64_C(2004), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DISCARD, true, &evidence,
        GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY, &result
    );
    CHECK_STATUS(
        gumi_capture_selftest_complete(
            &state, UINT64_C(2005), transition,
            GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY, true, NULL, &result
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(state.phase == GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE);
    CHECK(state.failure == GUMI_CAPTURE_SELFTEST_FAILURE_MICROPHONE_ACQUIRE);
    CHECK(state.microphone_verified_off);
    CHECK(!state.privacy_asserted);
    CHECK_STATUS(
        gumi_capture_selftest_arm(
            &state, UINT64_C(2006), UINT64_C(17006)
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    tests_run += 1U;
}

static void test_microphone_release_failure_is_terminal_and_red_stays(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest_result result;
    uint64_t end_ms = enter_exercise(&state, &result);
    uint64_t transition;

    CHECK_STATUS(
        gumi_capture_selftest_advance(&state, end_ms, &result),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    transition = expect_action(&result, GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE);
    CHECK_STATUS(
        gumi_capture_selftest_complete(
            &state, end_ms + UINT64_C(1), transition,
            GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE, false, NULL, &result
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(state.phase == GUMI_CAPTURE_SELFTEST_PHASE_FAILED_MICROPHONE_UNKNOWN);
    CHECK(!state.microphone_verified_off);
    CHECK(state.privacy_asserted);
    CHECK_STATUS(
        gumi_capture_selftest_arm(
            &state, end_ms + UINT64_C(2), end_ms + UINT64_C(15002)
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE
    );
    tests_run += 1U;
}

static void test_bad_codec_evidence_fails_after_safe_cleanup(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest_result result;
    gumi_capture_selftest_evidence evidence = passing_evidence();
    uint64_t end_ms = enter_exercise(&state, &result);
    uint64_t transition;

    evidence.discarded_samples = UINT32_C(160);
    CHECK_STATUS(
        gumi_capture_selftest_advance(&state, end_ms, &result),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    transition = expect_action(&result, GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE);
    transition = complete_and_expect(
        &state, end_ms + UINT64_C(1), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE, true, NULL,
        GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN, &result
    );
    transition = complete_and_expect(
        &state, end_ms + UINT64_C(2), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN, true, &evidence,
        GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY, &result
    );
    CHECK_STATUS(
        gumi_capture_selftest_complete(
            &state, end_ms + UINT64_C(3), transition,
            GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY, true, NULL, &result
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(state.phase == GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE);
    CHECK(state.failure == GUMI_CAPTURE_SELFTEST_FAILURE_CODEC_DROPPED_SAMPLES);
    tests_run += 1U;
}

static void test_stale_completion_is_transactional(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest before;
    gumi_capture_selftest_result result;
    gumi_capture_selftest_result before_result;
    uint64_t transition = arm_and_confirm(&state, UINT64_C(0), &result);

    before = state;
    before_result = result;
    CHECK_STATUS(
        gumi_capture_selftest_complete(
            &state, UINT64_C(2001), transition + UINT64_C(1),
            GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY, true, NULL, &result
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_STALE_TRANSITION
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK(memcmp(&result, &before_result, sizeof(result)) == 0);
    tests_run += 1U;
}

static void test_success_can_be_rearmed(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest_result result;

    complete_successful_attempt(&state, &result);
    CHECK_STATUS(
        gumi_capture_selftest_arm(
            &state, state.current_ms + UINT64_C(1), state.current_ms + UINT64_C(15001)
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(state.phase == GUMI_CAPTURE_SELFTEST_PHASE_ARMED);
    CHECK(state.attempt == UINT32_C(2));
    CHECK(state.evidence.pcm_samples == UINT32_C(0));
    tests_run += 1U;
}

static void test_acquire_deadline_overflow_is_transactional(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest before;
    gumi_capture_selftest_result result;
    gumi_capture_selftest_result before_result;
    uint64_t transition = arm_and_confirm(&state, UINT64_C(0), &result);

    transition = complete_and_expect(
        &state, UINT64_C(2001), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY, true, NULL,
        GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC, &result
    );
    transition = complete_and_expect(
        &state, UINT64_C(2002), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC, true, NULL,
        GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE, &result
    );
    before = state;
    before_result = result;
    CHECK_STATUS(
        gumi_capture_selftest_complete(
            &state, UINT64_MAX - UINT64_C(2999), transition,
            GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE, true, NULL, &result
        ),
        GUMI_CAPTURE_SELFTEST_STATUS_COUNTER_EXHAUSTED
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK(memcmp(&result, &before_result, sizeof(result)) == 0);
    tests_run += 1U;
}

static void test_async_failure_while_acquiring_assumes_microphone_unknown(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest_result result;
    uint64_t transition = arm_and_confirm(&state, UINT64_C(0), &result);

    transition = complete_and_expect(
        &state, UINT64_C(2001), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY, true, NULL,
        GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC, &result
    );
    (void)complete_and_expect(
        &state, UINT64_C(2002), transition,
        GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC, true, NULL,
        GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE, &result
    );
    CHECK_STATUS(
        gumi_capture_selftest_async_port_failed(&state, UINT64_C(2003), &result),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(state.phase == GUMI_CAPTURE_SELFTEST_PHASE_FAILED_MICROPHONE_UNKNOWN);
    CHECK(state.privacy_asserted);
    CHECK(!state.microphone_verified_off);
    CHECK(!result.has_action);
    tests_run += 1U;
}

static void test_async_safe_failure_cannot_be_rearmed(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest_result result;

    CHECK_STATUS(
        gumi_capture_selftest_async_port_failed(&state, UINT64_C(1), &result),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(state.phase == GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE);
    CHECK(state.failure == GUMI_CAPTURE_SELFTEST_FAILURE_ASYNC_PORT);
    CHECK_STATUS(
        gumi_capture_selftest_arm(&state, UINT64_C(2), UINT64_C(15002)),
        GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE
    );
    tests_run += 1U;
}

static void test_async_failure_while_running_releases_microphone_first(void)
{
    gumi_capture_selftest state = new_state();
    gumi_capture_selftest_result result;

    (void)enter_exercise(&state, &result);
    CHECK_STATUS(
        gumi_capture_selftest_async_port_failed(&state, state.current_ms, &result),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(expect_action(&result, GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE) !=
          UINT64_C(0));
    CHECK(state.failure == GUMI_CAPTURE_SELFTEST_FAILURE_ASYNC_PORT);
    CHECK(state.privacy_asserted);
    CHECK(!state.microphone_verified_off);
    tests_run += 1U;
}

static void test_wire_status_is_exact_and_contains_no_media(void)
{
    gumi_capture_selftest state = new_state();
    uint8_t wire[GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE];
    const uint8_t expected[GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE] = {
        0x01, 0x01, 0x00, 0x8a,
        0x01, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x98, 0x3a, 0x00, 0x00,
    };

    CHECK_STATUS(
        gumi_capture_selftest_arm(&state, UINT64_C(10), UINT64_C(15010)),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK_STATUS(
        gumi_capture_selftest_encode_status(&state, wire),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK(memcmp(wire, expected, sizeof(wire)) == 0);
    tests_run += 1U;
}

static void test_invalid_lease_and_missing_recovery_fail_closed(void)
{
    gumi_capture_selftest state = new_state();

    CHECK_STATUS(
        gumi_capture_selftest_arm(&state, UINT64_C(0), UINT64_C(14999)),
        GUMI_CAPTURE_SELFTEST_STATUS_INVALID_ARGUMENT
    );
    CHECK_STATUS(
        gumi_capture_selftest_init(&state, true, false),
        GUMI_CAPTURE_SELFTEST_STATUS_OK
    );
    CHECK_STATUS(
        gumi_capture_selftest_arm(&state, UINT64_C(0), UINT64_C(15000)),
        GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE
    );
    tests_run += 1U;
}

int main(void)
{
    test_success_orders_privacy_microphone_codec_and_evidence();
    test_confirmation_expiry_never_emits_effect();
    test_privacy_failure_prevents_codec_and_microphone();
    test_microphone_acquire_failure_discards_codec_then_removes_red();
    test_microphone_release_failure_is_terminal_and_red_stays();
    test_bad_codec_evidence_fails_after_safe_cleanup();
    test_stale_completion_is_transactional();
    test_success_can_be_rearmed();
    test_acquire_deadline_overflow_is_transactional();
    test_async_failure_while_acquiring_assumes_microphone_unknown();
    test_async_safe_failure_cannot_be_rearmed();
    test_async_failure_while_running_releases_microphone_first();
    test_wire_status_is_exact_and_contains_no_media();
    test_invalid_lease_and_missing_recovery_fail_closed();
    printf("capture self-test kernel: %u tests passed\n", tests_run);
    return EXIT_SUCCESS;
}
