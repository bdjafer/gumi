#include "gumi/interaction_policy.h"

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

static gumi_button_event event(gumi_button_event_type type)
{
    const gumi_button_event value = {
        .at_ms = 42U,
        .type = type,
        .confirmation_operation_token = 0U,
    };
    return value;
}

static gumi_interaction_facts ready(void)
{
    const gumi_interaction_facts facts = {
        .capture_available = true,
    };
    return facts;
}

static gumi_interaction_policy_result evaluate(
    gumi_interaction_policy_profile profile,
    gumi_button_event_type type,
    const gumi_interaction_facts *facts
)
{
    gumi_button_event input = event(type);
    gumi_interaction_policy_result result;
    CHECK(
        gumi_interaction_policy_evaluate(profile, &input, facts, &result) ==
            GUMI_INTERACTION_POLICY_STATUS_OK
    );
    return result;
}

static void expect_intent(
    const gumi_interaction_policy_result *result,
    gumi_interaction_intent_type type
)
{
    CHECK(result->disposition == GUMI_INTERACTION_DISPOSITION_APPLIED);
    CHECK(result->intent_count == 1U);
    CHECK(result->intents[0].type == type);
    CHECK(result->intents[0].at_ms == 42U);
}

static void test_profile_ids_are_versioned(void)
{
    CHECK(strcmp(
        gumi_interaction_policy_profile_id(
            GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1
        ),
        "manual-recording-push-to-talk-v1"
    ) == 0);
    CHECK(strcmp(
        gumi_interaction_policy_profile_id(
            GUMI_INTERACTION_POLICY_CONTINUOUS_RECORDING_MARKER_V1
        ),
        "continuous-recording-marker-v1"
    ) == 0);
    CHECK(gumi_interaction_policy_profile_id((gumi_interaction_policy_profile)99) == NULL);
    tests_run += 1U;
}

static void test_manual_double_tap_uses_live_recording_fact(void)
{
    gumi_interaction_facts facts = ready();
    gumi_interaction_policy_result result = evaluate(
        GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1,
        GUMI_BUTTON_EVENT_DOUBLE_TAP,
        &facts
    );
    expect_intent(&result, GUMI_INTERACTION_INTENT_START_BASE_RECORDING);
    facts.base_recording_active = true;
    result = evaluate(
        GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1,
        GUMI_BUTTON_EVENT_DOUBLE_TAP,
        &facts
    );
    expect_intent(&result, GUMI_INTERACTION_INTENT_STOP_BASE_RECORDING);
    tests_run += 1U;
}

static void test_manual_hold_is_voice_action_not_recording_policy(void)
{
    gumi_interaction_facts facts = ready();
    gumi_interaction_policy_result result = evaluate(
        GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1,
        GUMI_BUTTON_EVENT_HOLD_COMMITTED,
        &facts
    );
    expect_intent(&result, GUMI_INTERACTION_INTENT_BEGIN_VOICE_ACTION);
    facts.voice_action_active = true;
    result = evaluate(
        GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1,
        GUMI_BUTTON_EVENT_HOLD_RELEASED,
        &facts
    );
    expect_intent(&result, GUMI_INTERACTION_INTENT_END_VOICE_ACTION);
    tests_run += 1U;
}

static void test_continuous_profile_does_not_toggle_recording(void)
{
    gumi_interaction_facts facts = ready();
    facts.base_recording_active = true;
    gumi_interaction_policy_result result = evaluate(
        GUMI_INTERACTION_POLICY_CONTINUOUS_RECORDING_MARKER_V1,
        GUMI_BUTTON_EVENT_DOUBLE_TAP,
        &facts
    );
    expect_intent(&result, GUMI_INTERACTION_INTENT_SHOW_STATUS);
    tests_run += 1U;
}

static void test_continuous_hold_opens_and_closes_marker(void)
{
    gumi_interaction_facts facts = ready();
    facts.base_recording_active = true;
    gumi_interaction_policy_result result = evaluate(
        GUMI_INTERACTION_POLICY_CONTINUOUS_RECORDING_MARKER_V1,
        GUMI_BUTTON_EVENT_HOLD_COMMITTED,
        &facts
    );
    expect_intent(
        &result, GUMI_INTERACTION_INTENT_BEGIN_INTERPRETATION_MARKER
    );
    facts.interpretation_marker_active = true;
    result = evaluate(
        GUMI_INTERACTION_POLICY_CONTINUOUS_RECORDING_MARKER_V1,
        GUMI_BUTTON_EVENT_HOLD_RELEASED,
        &facts
    );
    expect_intent(
        &result, GUMI_INTERACTION_INTENT_END_INTERPRETATION_MARKER
    );
    tests_run += 1U;
}

static void test_continuous_marker_requires_active_recording(void)
{
    gumi_interaction_facts facts = ready();
    gumi_interaction_policy_result result = evaluate(
        GUMI_INTERACTION_POLICY_CONTINUOUS_RECORDING_MARKER_V1,
        GUMI_BUTTON_EVENT_HOLD_COMMITTED,
        &facts
    );
    CHECK(result.disposition == GUMI_INTERACTION_DISPOSITION_REFUSED);
    CHECK(result.intent_count == 0U);
    tests_run += 1U;
}

static void test_maintenance_and_fatal_privacy_keep_only_status(void)
{
    gumi_interaction_facts facts = {
        .maintenance_exclusive = true,
    };
    gumi_interaction_policy_result result = evaluate(
        GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1,
        GUMI_BUTTON_EVENT_DOUBLE_TAP,
        &facts
    );
    CHECK(result.disposition == GUMI_INTERACTION_DISPOSITION_IGNORED);
    CHECK(result.intent_count == 0U);
    result = evaluate(
        GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1,
        GUMI_BUTTON_EVENT_SINGLE_TAP,
        &facts
    );
    expect_intent(&result, GUMI_INTERACTION_INTENT_SHOW_STATUS);
    facts.maintenance_exclusive = false;
    facts.fatal_privacy = true;
    result = evaluate(
        GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1,
        GUMI_BUTTON_EVENT_HOLD_COMMITTED,
        &facts
    );
    CHECK(result.disposition == GUMI_INTERACTION_DISPOSITION_IGNORED);
    CHECK(result.intent_count == 0U);
    tests_run += 1U;
}

static void test_invalid_fact_combinations_are_rejected(void)
{
    gumi_interaction_facts facts = ready();
    gumi_button_event input = event(GUMI_BUTTON_EVENT_SINGLE_TAP);
    gumi_interaction_policy_result result;
    facts.fatal_privacy = true;
    CHECK(
        gumi_interaction_policy_evaluate(
            GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1,
            &input,
            &facts,
            &result
        ) == GUMI_INTERACTION_POLICY_STATUS_INVALID_FACTS
    );
    tests_run += 1U;
}

int main(void)
{
    test_profile_ids_are_versioned();
    test_manual_double_tap_uses_live_recording_fact();
    test_manual_hold_is_voice_action_not_recording_policy();
    test_continuous_profile_does_not_toggle_recording();
    test_continuous_hold_opens_and_closes_marker();
    test_continuous_marker_requires_active_recording();
    test_maintenance_and_fatal_privacy_keep_only_status();
    test_invalid_fact_combinations_are_rejected();
    printf("PASS: %u portable interaction-policy tests\n", tests_run);
    return EXIT_SUCCESS;
}
