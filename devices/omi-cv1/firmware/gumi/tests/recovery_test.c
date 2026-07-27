#include "gumi/recovery.h"

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
    gumi_recovery_status actual_status = (expression); \
    CHECK(actual_status == (expected)); \
} while (0)

static gumi_recovery_boot_evidence normal_boot(void)
{
    const gumi_recovery_boot_evidence evidence = {false, false, false};
    return evidence;
}

static uint64_t begin_recovery(
    gumi_recovery_supervisor *state,
    const gumi_recovery_boot_evidence *evidence,
    gumi_recovery_result *result
)
{
    CHECK_STATUS(gumi_recovery_supervisor_init(state, evidence), GUMI_RECOVERY_STATUS_OK);
    CHECK_STATUS(gumi_recovery_begin(state, 0U, result), GUMI_RECOVERY_STATUS_OK);
    CHECK(result->has_action);
    CHECK(result->action.type == GUMI_RECOVERY_ACTION_START_TRANSPORT);
    CHECK(!result->has_event);
    CHECK(!gumi_recovery_capture_is_permitted(state));
    return result->action.transition_id;
}

static uint64_t complete_and_expect_action(
    gumi_recovery_supervisor *state,
    uint64_t at_ms,
    uint64_t transition_id,
    gumi_recovery_completion completion,
    gumi_recovery_action_type action,
    gumi_recovery_result *result
)
{
    CHECK_STATUS(
        gumi_recovery_complete(state, at_ms, transition_id, completion, result),
        GUMI_RECOVERY_STATUS_OK
    );
    CHECK(result->has_action);
    CHECK(result->action.type == action);
    CHECK(result->action.transition_id != transition_id);
    return result->action.transition_id;
}

static gumi_recovery_supervisor operational_recovery(void)
{
    gumi_recovery_supervisor state;
    gumi_recovery_result result;
    gumi_recovery_boot_evidence evidence = normal_boot();
    uint64_t transition = begin_recovery(&state, &evidence, &result);

    transition = complete_and_expect_action(
        &state,
        1U,
        transition,
        GUMI_RECOVERY_COMPLETION_TRANSPORT_READY,
        GUMI_RECOVERY_ACTION_VERIFY_MICROPHONE_OFF,
        &result
    );
    CHECK(result.has_event);
    CHECK(result.event.type == GUMI_RECOVERY_EVENT_RECOVERY_AVAILABLE);
    transition = complete_and_expect_action(
        &state,
        2U,
        transition,
        GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF,
        GUMI_RECOVERY_ACTION_RUN_FUNCTIONAL_SELF_TESTS,
        &result
    );
    transition = complete_and_expect_action(
        &state,
        3U,
        transition,
        GUMI_RECOVERY_COMPLETION_SELF_TESTS_PASSED,
        GUMI_RECOVERY_ACTION_ENABLE_FUNCTIONAL_SERVICES,
        &result
    );
    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            4U,
            transition,
            GUMI_RECOVERY_COMPLETION_FUNCTIONAL_SERVICES_READY,
            &result
        ),
        GUMI_RECOVERY_STATUS_OK
    );
    CHECK(!result.has_action);
    CHECK(result.has_event);
    CHECK(result.event.type == GUMI_RECOVERY_EVENT_OPERATIONAL_READY);
    CHECK(state.phase == GUMI_RECOVERY_PHASE_OPERATIONAL);
    CHECK(gumi_recovery_capture_is_permitted(&state));
    return state;
}

static void test_recovery_transport_is_always_first_and_capture_is_last(void)
{
    gumi_recovery_supervisor state = operational_recovery();
    uint8_t wire[GUMI_RECOVERY_STATUS_WIRE_SIZE];

    CHECK(gumi_recovery_transport_is_available(&state));
    CHECK(state.microphone_verified_off);
    CHECK(state.self_tests_passed);
    CHECK(state.functional_services_ready);
    CHECK_STATUS(gumi_recovery_encode_status(&state, wire), GUMI_RECOVERY_STATUS_OK);
    CHECK(wire[0] == GUMI_RECOVERY_STATUS_WIRE_VERSION);
    CHECK(wire[1] == (uint8_t)GUMI_RECOVERY_PHASE_OPERATIONAL);
    CHECK(wire[2] == (uint8_t)GUMI_RECOVERY_REASON_NONE);
    CHECK(wire[3] == UINT8_C(0x3f));
    tests_run += 1U;
}

static void test_watchdog_boot_stops_in_verified_off_safe_mode(void)
{
    const gumi_recovery_boot_evidence evidence = {false, false, true};
    gumi_recovery_supervisor state;
    gumi_recovery_result result;
    uint8_t wire[GUMI_RECOVERY_STATUS_WIRE_SIZE];
    uint64_t transition = begin_recovery(&state, &evidence, &result);

    transition = complete_and_expect_action(
        &state,
        1U,
        transition,
        GUMI_RECOVERY_COMPLETION_TRANSPORT_READY,
        GUMI_RECOVERY_ACTION_VERIFY_MICROPHONE_OFF,
        &result
    );
    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            2U,
            transition,
            GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF,
            &result
        ),
        GUMI_RECOVERY_STATUS_OK
    );
    CHECK(!result.has_action);
    CHECK(result.has_event);
    CHECK(result.event.type == GUMI_RECOVERY_EVENT_SAFE_MODE_READY);
    CHECK(result.event.reason == GUMI_RECOVERY_REASON_WATCHDOG_OR_LOCKUP_RESET);
    CHECK(state.phase == GUMI_RECOVERY_PHASE_SAFE_MODE);
    CHECK(state.microphone_verified_off);
    CHECK(gumi_recovery_transport_is_available(&state));
    CHECK(!gumi_recovery_capture_is_permitted(&state));
    CHECK_STATUS(gumi_recovery_encode_status(&state, wire), GUMI_RECOVERY_STATUS_OK);
    CHECK(wire[3] == UINT8_C(0x23));
    tests_run += 1U;
}

static void test_persisted_safe_mode_takes_precedence_over_explicit_request(void)
{
    const gumi_recovery_boot_evidence evidence = {true, true, false};
    gumi_recovery_supervisor state;
    gumi_recovery_result result;
    uint64_t transition = begin_recovery(&state, &evidence, &result);

    transition = complete_and_expect_action(
        &state,
        1U,
        transition,
        GUMI_RECOVERY_COMPLETION_TRANSPORT_READY,
        GUMI_RECOVERY_ACTION_VERIFY_MICROPHONE_OFF,
        &result
    );
    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            2U,
            transition,
            GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF,
            &result
        ),
        GUMI_RECOVERY_STATUS_OK
    );
    CHECK(state.reason == GUMI_RECOVERY_REASON_PERSISTED_SAFE_MODE);
    tests_run += 1U;
}

static void test_transport_failure_never_invokes_functional_code(void)
{
    gumi_recovery_boot_evidence evidence = normal_boot();
    gumi_recovery_supervisor state;
    gumi_recovery_result result;
    uint64_t transition = begin_recovery(&state, &evidence, &result);

    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            1U,
            transition,
            GUMI_RECOVERY_COMPLETION_TRANSPORT_FAILED,
            &result
        ),
        GUMI_RECOVERY_STATUS_OK
    );
    CHECK(!result.has_action);
    CHECK(result.event.type == GUMI_RECOVERY_EVENT_RECOVERY_UNAVAILABLE);
    CHECK(state.phase == GUMI_RECOVERY_PHASE_RECOVERY_UNAVAILABLE);
    CHECK(!gumi_recovery_transport_is_available(&state));
    CHECK(!gumi_recovery_capture_is_permitted(&state));
    tests_run += 1U;
}

static void test_microphone_verification_failure_keeps_recovery_only(void)
{
    gumi_recovery_boot_evidence evidence = normal_boot();
    gumi_recovery_supervisor state;
    gumi_recovery_result result;
    uint64_t transition = begin_recovery(&state, &evidence, &result);

    transition = complete_and_expect_action(
        &state,
        1U,
        transition,
        GUMI_RECOVERY_COMPLETION_TRANSPORT_READY,
        GUMI_RECOVERY_ACTION_VERIFY_MICROPHONE_OFF,
        &result
    );
    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            2U,
            transition,
            GUMI_RECOVERY_COMPLETION_MICROPHONE_OFF_FAILED,
            &result
        ),
        GUMI_RECOVERY_STATUS_OK
    );
    CHECK(state.phase == GUMI_RECOVERY_PHASE_SAFE_MODE);
    CHECK(state.reason == GUMI_RECOVERY_REASON_MICROPHONE_OFF_UNVERIFIED);
    CHECK(!state.microphone_verified_off);
    CHECK(gumi_recovery_transport_is_available(&state));
    CHECK(!gumi_recovery_capture_is_permitted(&state));
    tests_run += 1U;
}

static void test_self_test_and_service_failures_never_admit_capture(void)
{
    gumi_recovery_boot_evidence evidence = normal_boot();
    gumi_recovery_supervisor state;
    gumi_recovery_result result;
    uint64_t transition = begin_recovery(&state, &evidence, &result);

    transition = complete_and_expect_action(
        &state,
        1U,
        transition,
        GUMI_RECOVERY_COMPLETION_TRANSPORT_READY,
        GUMI_RECOVERY_ACTION_VERIFY_MICROPHONE_OFF,
        &result
    );
    transition = complete_and_expect_action(
        &state,
        2U,
        transition,
        GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF,
        GUMI_RECOVERY_ACTION_RUN_FUNCTIONAL_SELF_TESTS,
        &result
    );
    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            3U,
            transition,
            GUMI_RECOVERY_COMPLETION_SELF_TESTS_FAILED,
            &result
        ),
        GUMI_RECOVERY_STATUS_OK
    );
    CHECK(state.reason == GUMI_RECOVERY_REASON_SELF_TEST_FAILED);
    CHECK(!gumi_recovery_capture_is_permitted(&state));

    transition = begin_recovery(&state, &evidence, &result);
    transition = complete_and_expect_action(
        &state,
        1U,
        transition,
        GUMI_RECOVERY_COMPLETION_TRANSPORT_READY,
        GUMI_RECOVERY_ACTION_VERIFY_MICROPHONE_OFF,
        &result
    );
    transition = complete_and_expect_action(
        &state,
        2U,
        transition,
        GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF,
        GUMI_RECOVERY_ACTION_RUN_FUNCTIONAL_SELF_TESTS,
        &result
    );
    transition = complete_and_expect_action(
        &state,
        3U,
        transition,
        GUMI_RECOVERY_COMPLETION_SELF_TESTS_PASSED,
        GUMI_RECOVERY_ACTION_ENABLE_FUNCTIONAL_SERVICES,
        &result
    );
    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            4U,
            transition,
            GUMI_RECOVERY_COMPLETION_FUNCTIONAL_SERVICES_FAILED,
            &result
        ),
        GUMI_RECOVERY_STATUS_OK
    );
    CHECK(state.reason == GUMI_RECOVERY_REASON_FUNCTIONAL_SERVICES_FAILED);
    CHECK(!gumi_recovery_capture_is_permitted(&state));
    tests_run += 1U;
}

static void test_runtime_fault_revokes_capture_before_platform_quiescence(void)
{
    gumi_recovery_supervisor state = operational_recovery();
    gumi_recovery_result result;
    uint64_t transition;

    CHECK_STATUS(gumi_recovery_runtime_fault(&state, 5U, &result), GUMI_RECOVERY_STATUS_OK);
    CHECK(!gumi_recovery_capture_is_permitted(&state));
    CHECK(state.phase == GUMI_RECOVERY_PHASE_QUIESCING_TO_SAFE_MODE);
    CHECK(result.action.type == GUMI_RECOVERY_ACTION_QUIESCE_FUNCTIONAL_SERVICES);
    transition = result.action.transition_id;
    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            6U,
            transition,
            GUMI_RECOVERY_COMPLETION_FUNCTIONAL_SERVICES_QUIESCED,
            &result
        ),
        GUMI_RECOVERY_STATUS_OK
    );
    CHECK(state.phase == GUMI_RECOVERY_PHASE_SAFE_MODE);
    CHECK(state.reason == GUMI_RECOVERY_REASON_RUNTIME_FAULT);
    CHECK(state.microphone_verified_off);
    CHECK(gumi_recovery_transport_is_available(&state));
    tests_run += 1U;
}

static void test_quiesce_failure_is_visible_and_never_reopens_capture(void)
{
    gumi_recovery_supervisor state = operational_recovery();
    gumi_recovery_result result;
    uint64_t transition;

    CHECK_STATUS(gumi_recovery_runtime_fault(&state, 5U, &result), GUMI_RECOVERY_STATUS_OK);
    transition = result.action.transition_id;
    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            6U,
            transition,
            GUMI_RECOVERY_COMPLETION_FUNCTIONAL_QUIESCE_FAILED,
            &result
        ),
        GUMI_RECOVERY_STATUS_OK
    );
    CHECK(state.phase == GUMI_RECOVERY_PHASE_SAFE_MODE);
    CHECK(state.reason == GUMI_RECOVERY_REASON_QUIESCE_FAILED);
    CHECK(!state.microphone_verified_off);
    CHECK(!gumi_recovery_capture_is_permitted(&state));
    tests_run += 1U;
}

static void test_stale_completion_and_time_regression_are_transactional(void)
{
    gumi_recovery_boot_evidence evidence = normal_boot();
    gumi_recovery_supervisor state;
    gumi_recovery_supervisor before;
    gumi_recovery_result result;
    gumi_recovery_result result_before;
    uint64_t transition = begin_recovery(&state, &evidence, &result);

    before = state;
    result_before = result;
    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            1U,
            transition + 1U,
            GUMI_RECOVERY_COMPLETION_TRANSPORT_READY,
            &result
        ),
        GUMI_RECOVERY_STATUS_STALE_TRANSITION
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK(memcmp(&result, &result_before, sizeof(result)) == 0);
    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            10U,
            transition,
            GUMI_RECOVERY_COMPLETION_TRANSPORT_READY,
            &result
        ),
        GUMI_RECOVERY_STATUS_OK
    );
    transition = result.action.transition_id;
    before = state;
    result_before = result;
    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            9U,
            transition,
            GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF,
            &result
        ),
        GUMI_RECOVERY_STATUS_TIME_REGRESSION
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK(memcmp(&result, &result_before, sizeof(result)) == 0);

    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            10U,
            transition,
            (gumi_recovery_completion)-1,
            &result
        ),
        GUMI_RECOVERY_STATUS_INVALID_ARGUMENT
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK(memcmp(&result, &result_before, sizeof(result)) == 0);

    CHECK_STATUS(
        gumi_recovery_complete(
            &state,
            10U,
            transition,
            (gumi_recovery_completion)(GUMI_RECOVERY_COMPLETION_FUNCTIONAL_QUIESCE_FAILED + 1),
            &result
        ),
        GUMI_RECOVERY_STATUS_INVALID_ARGUMENT
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK(memcmp(&result, &result_before, sizeof(result)) == 0);

    transition = complete_and_expect_action(
        &state,
        10U,
        transition,
        GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF,
        GUMI_RECOVERY_ACTION_RUN_FUNCTIONAL_SELF_TESTS,
        &result
    );
    CHECK(state.phase == GUMI_RECOVERY_PHASE_RUNNING_SELF_TESTS);
    CHECK(transition == state.transition_id);
    tests_run += 1U;
}

static void test_counter_exhaustion_does_not_partially_begin(void)
{
    gumi_recovery_boot_evidence evidence = normal_boot();
    gumi_recovery_supervisor state;
    gumi_recovery_supervisor before;
    gumi_recovery_result result;

    CHECK_STATUS(gumi_recovery_supervisor_init(&state, &evidence), GUMI_RECOVERY_STATUS_OK);
    state.next_transition_id = UINT64_C(0);
    before = state;
    CHECK_STATUS(
        gumi_recovery_begin(&state, 0U, &result),
        GUMI_RECOVERY_STATUS_COUNTER_EXHAUSTED
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    tests_run += 1U;
}

static void test_status_encoder_rejects_invalid_arguments(void)
{
    gumi_recovery_boot_evidence evidence = normal_boot();
    gumi_recovery_supervisor state;
    uint8_t wire[GUMI_RECOVERY_STATUS_WIRE_SIZE];

    CHECK_STATUS(gumi_recovery_supervisor_init(&state, &evidence), GUMI_RECOVERY_STATUS_OK);
    CHECK_STATUS(
        gumi_recovery_encode_status(NULL, wire),
        GUMI_RECOVERY_STATUS_INVALID_ARGUMENT
    );
    CHECK_STATUS(
        gumi_recovery_encode_status(&state, NULL),
        GUMI_RECOVERY_STATUS_INVALID_ARGUMENT
    );
    tests_run += 1U;
}

int main(void)
{
    test_recovery_transport_is_always_first_and_capture_is_last();
    test_watchdog_boot_stops_in_verified_off_safe_mode();
    test_persisted_safe_mode_takes_precedence_over_explicit_request();
    test_transport_failure_never_invokes_functional_code();
    test_microphone_verification_failure_keeps_recovery_only();
    test_self_test_and_service_failures_never_admit_capture();
    test_runtime_fault_revokes_capture_before_platform_quiescence();
    test_quiesce_failure_is_visible_and_never_reopens_capture();
    test_stale_completion_and_time_regression_are_transactional();
    test_counter_exhaustion_does_not_partially_begin();
    test_status_encoder_rejects_invalid_arguments();
    printf("recovery supervisor: %u tests passed\n", tests_run);
    return EXIT_SUCCESS;
}
