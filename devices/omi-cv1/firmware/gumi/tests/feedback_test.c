#include "gumi/feedback.h"

#include <stdio.h>
#include <stdlib.h>

static unsigned int tests_run;

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #condition); \
        exit(EXIT_FAILURE); \
    } \
} while (0)

static gumi_capture_supervisor idle_capture(void)
{
    gumi_capture_supervisor state;
    gumi_capture_result result;
    CHECK(gumi_capture_supervisor_init(&state) == GUMI_CAPTURE_STATUS_OK);
    CHECK(gumi_capture_required_self_tests_passed(&state, 0U, &result) == GUMI_CAPTURE_STATUS_OK);
    return state;
}

static gumi_feedback_input input_for(const gumi_capture_supervisor *capture)
{
    const gumi_feedback_input input = {
        .capture = capture,
        .maintenance = GUMI_FEEDBACK_MAINTENANCE_NORMAL,
        .power_level = GUMI_FEEDBACK_POWER_NORMAL,
        .requested_status = GUMI_FEEDBACK_PATTERN_NONE,
        .charging = false,
        .recoverable_warning = false,
    };
    return input;
}

static void force_recording_projection(gumi_capture_supervisor *capture)
{
    capture->mic_truth = GUMI_CAPTURE_MIC_ACQUIRED;
    capture->privacy_guard_asserted = true;
    capture->base_recording_active = true;
    capture->active_recording_id = 1U;
    capture->base_audio_permitted = true;
    capture->phase = GUMI_CAPTURE_PHASE_BASE_ACTIVE;
}

static void test_privacy_suppresses_every_lower_visual_candidate(void)
{
    gumi_capture_supervisor capture = idle_capture();
    gumi_feedback_input input;
    gumi_feedback_decision decision;
    uint32_t expected;

    force_recording_projection(&capture);
    capture.fault = GUMI_CAPTURE_FAULT_RECOVERABLE;
    input = input_for(&capture);
    input.maintenance = GUMI_FEEDBACK_MAINTENANCE_UPDATING;
    input.charging = true;
    input.requested_status = GUMI_FEEDBACK_PATTERN_READY_LINK_STATUS;
    CHECK(gumi_feedback_decide(&input, &decision) == GUMI_FEEDBACK_STATUS_OK);
    CHECK(decision.status == GUMI_FEEDBACK_DECISION_SELECTED);
    CHECK(decision.selected == GUMI_FEEDBACK_PATTERN_PRIVACY_RECORDING);
    expected = gumi_feedback_pattern_mask(GUMI_FEEDBACK_PATTERN_UPDATING) |
        gumi_feedback_pattern_mask(GUMI_FEEDBACK_PATTERN_RECOVERABLE_FAULT) |
        gumi_feedback_pattern_mask(GUMI_FEEDBACK_PATTERN_CHARGING) |
        gumi_feedback_pattern_mask(GUMI_FEEDBACK_PATTERN_READY_LINK_STATUS);
    CHECK(decision.suppressed_patterns == expected);
    tests_run += 1U;
}

static void test_voice_turn_is_the_only_privacy_modulation(void)
{
    gumi_capture_supervisor capture = idle_capture();
    gumi_feedback_input input;
    gumi_feedback_decision decision;

    force_recording_projection(&capture);
    capture.voice = GUMI_CAPTURE_VOICE_ACTIVE;
    capture.voice_audio_permitted = true;
    capture.phase = GUMI_CAPTURE_PHASE_VOICE_OVERLAY_ACTIVE;
    input = input_for(&capture);
    CHECK(gumi_feedback_decide(&input, &decision) == GUMI_FEEDBACK_STATUS_OK);
    CHECK(decision.selected == GUMI_FEEDBACK_PATTERN_PRIVACY_VOICE_TURN);
    CHECK(decision.suppressed_patterns == 0U);
    tests_run += 1U;
}

static void test_failed_privacy_driver_locks_out_lower_visual_output(void)
{
    gumi_capture_supervisor capture = idle_capture();
    gumi_feedback_input input = input_for(&capture);
    gumi_feedback_decision decision;

    capture.privacy_output_healthy = false;
    capture.fault = GUMI_CAPTURE_FAULT_FATAL_PRIVACY;
    capture.phase = GUMI_CAPTURE_PHASE_FATAL_IDLE;
    input.charging = true;
    input.requested_status = GUMI_FEEDBACK_PATTERN_DISCONNECTED_STATUS;
    CHECK(gumi_feedback_decide(&input, &decision) == GUMI_FEEDBACK_STATUS_OK);
    CHECK(decision.status == GUMI_FEEDBACK_DECISION_FATAL_PRIVACY_OUTPUT_UNAVAILABLE);
    CHECK(decision.selected == GUMI_FEEDBACK_PATTERN_NONE);
    CHECK(
        decision.suppressed_patterns ==
            (gumi_feedback_pattern_mask(GUMI_FEEDBACK_PATTERN_CHARGING) |
             gumi_feedback_pattern_mask(GUMI_FEEDBACK_PATTERN_DISCONNECTED_STATUS))
    );
    tests_run += 1U;
}

static void test_low_power_and_charging_tie_stays_explicit(void)
{
    gumi_capture_supervisor capture = idle_capture();
    gumi_feedback_input input = input_for(&capture);
    gumi_feedback_decision decision;
    uint32_t tie;

    input.power_level = GUMI_FEEDBACK_POWER_LOW;
    input.charging = true;
    CHECK(gumi_feedback_decide(&input, &decision) == GUMI_FEEDBACK_STATUS_OK);
    tie = gumi_feedback_pattern_mask(GUMI_FEEDBACK_PATTERN_LOW_POWER) |
        gumi_feedback_pattern_mask(GUMI_FEEDBACK_PATTERN_CHARGING);
    CHECK(decision.status == GUMI_FEEDBACK_DECISION_UNRESOLVED_SAME_PRIORITY);
    CHECK(decision.selected == GUMI_FEEDBACK_PATTERN_NONE);
    CHECK(decision.unresolved_same_priority == tie);
    tests_run += 1U;
}

static void test_maintenance_suppresses_warning_and_status(void)
{
    gumi_capture_supervisor capture = idle_capture();
    gumi_feedback_input input = input_for(&capture);
    gumi_feedback_decision decision;

    input.maintenance = GUMI_FEEDBACK_MAINTENANCE_VALIDATING;
    input.recoverable_warning = true;
    input.requested_status = GUMI_FEEDBACK_PATTERN_READY_LINK_STATUS;
    CHECK(gumi_feedback_decide(&input, &decision) == GUMI_FEEDBACK_STATUS_OK);
    CHECK(decision.selected == GUMI_FEEDBACK_PATTERN_VALIDATING);
    CHECK(
        decision.suppressed_patterns ==
            (gumi_feedback_pattern_mask(GUMI_FEEDBACK_PATTERN_RECOVERABLE_FAULT) |
             gumi_feedback_pattern_mask(GUMI_FEEDBACK_PATTERN_READY_LINK_STATUS))
    );
    tests_run += 1U;
}

static void test_idle_without_candidates_has_no_output(void)
{
    gumi_capture_supervisor capture = idle_capture();
    gumi_feedback_input input = input_for(&capture);
    gumi_feedback_decision decision;

    CHECK(gumi_feedback_decide(&input, &decision) == GUMI_FEEDBACK_STATUS_OK);
    CHECK(decision.status == GUMI_FEEDBACK_DECISION_NO_OUTPUT);
    CHECK(decision.selected == GUMI_FEEDBACK_PATTERN_NONE);
    tests_run += 1U;
}

static void test_only_named_status_patterns_enter_lowest_tier(void)
{
    gumi_capture_supervisor capture = idle_capture();
    gumi_feedback_input input = input_for(&capture);
    gumi_feedback_decision decision;

    input.requested_status = GUMI_FEEDBACK_PATTERN_PRIVACY_RECORDING;
    CHECK(
        gumi_feedback_decide(&input, &decision) ==
            GUMI_FEEDBACK_STATUS_INVALID_CONFIGURATION
    );
    tests_run += 1U;
}

int main(void)
{
    test_privacy_suppresses_every_lower_visual_candidate();
    test_voice_turn_is_the_only_privacy_modulation();
    test_failed_privacy_driver_locks_out_lower_visual_output();
    test_low_power_and_charging_tie_stays_explicit();
    test_maintenance_suppresses_warning_and_status();
    test_idle_without_candidates_has_no_output();
    test_only_named_status_patterns_enter_lowest_tier();
    printf("PASS: %u portable feedback-arbiter tests\n", tests_run);
    return EXIT_SUCCESS;
}
