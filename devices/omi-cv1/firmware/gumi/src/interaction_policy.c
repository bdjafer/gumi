#include "gumi/interaction_policy.h"

#include <string.h>

static bool valid_profile(gumi_interaction_policy_profile profile)
{
    return profile == GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1 ||
        profile == GUMI_INTERACTION_POLICY_CONTINUOUS_RECORDING_MARKER_V1;
}

static bool valid_facts(const gumi_interaction_facts *facts)
{
    if (facts == NULL) {
        return false;
    }
    if (facts->fatal_privacy && facts->capture_available) {
        return false;
    }
    if (facts->maintenance_exclusive && facts->capture_available) {
        return false;
    }
    if (facts->voice_action_active && facts->interpretation_marker_active) {
        return false;
    }
    if (facts->interpretation_marker_active &&
        !facts->base_recording_active) {
        return false;
    }
    return true;
}

static gumi_interaction_policy_status emit(
    gumi_interaction_policy_result *result,
    gumi_interaction_intent_type type,
    uint64_t at_ms
)
{
    if (result->intent_count >= GUMI_INTERACTION_INTENT_CAPACITY) {
        return GUMI_INTERACTION_POLICY_STATUS_OUTPUT_OVERFLOW;
    }
    result->intents[result->intent_count].type = type;
    result->intents[result->intent_count].at_ms = at_ms;
    result->intent_count += 1U;
    result->disposition = GUMI_INTERACTION_DISPOSITION_APPLIED;
    return GUMI_INTERACTION_POLICY_STATUS_OK;
}

static gumi_interaction_policy_status evaluate_manual(
    const gumi_button_event *event,
    const gumi_interaction_facts *facts,
    gumi_interaction_policy_result *result
)
{
    switch (event->type) {
        case GUMI_BUTTON_EVENT_SINGLE_TAP:
            return emit(
                result, GUMI_INTERACTION_INTENT_SHOW_STATUS, event->at_ms
            );
        case GUMI_BUTTON_EVENT_DOUBLE_TAP:
            if (!facts->capture_available) {
                result->disposition = GUMI_INTERACTION_DISPOSITION_REFUSED;
                return GUMI_INTERACTION_POLICY_STATUS_OK;
            }
            return emit(
                result,
                facts->base_recording_active
                    ? GUMI_INTERACTION_INTENT_STOP_BASE_RECORDING
                    : GUMI_INTERACTION_INTENT_START_BASE_RECORDING,
                event->at_ms
            );
        case GUMI_BUTTON_EVENT_HOLD_COMMITTED:
            if (!facts->capture_available || facts->voice_action_active) {
                result->disposition = facts->voice_action_active
                    ? GUMI_INTERACTION_DISPOSITION_IGNORED
                    : GUMI_INTERACTION_DISPOSITION_REFUSED;
                return GUMI_INTERACTION_POLICY_STATUS_OK;
            }
            return emit(
                result,
                GUMI_INTERACTION_INTENT_BEGIN_VOICE_ACTION,
                event->at_ms
            );
        case GUMI_BUTTON_EVENT_HOLD_RELEASED:
            if (!facts->voice_action_active) {
                return GUMI_INTERACTION_POLICY_STATUS_OK;
            }
            return emit(
                result,
                GUMI_INTERACTION_INTENT_END_VOICE_ACTION,
                event->at_ms
            );
        case GUMI_BUTTON_EVENT_PHYSICAL_CONFIRMATION:
            return GUMI_INTERACTION_POLICY_STATUS_OK;
        default:
            return GUMI_INTERACTION_POLICY_STATUS_INVALID_ARGUMENT;
    }
}

static gumi_interaction_policy_status evaluate_continuous(
    const gumi_button_event *event,
    const gumi_interaction_facts *facts,
    gumi_interaction_policy_result *result
)
{
    switch (event->type) {
        case GUMI_BUTTON_EVENT_SINGLE_TAP:
        case GUMI_BUTTON_EVENT_DOUBLE_TAP:
            return emit(
                result, GUMI_INTERACTION_INTENT_SHOW_STATUS, event->at_ms
            );
        case GUMI_BUTTON_EVENT_HOLD_COMMITTED:
            if (!facts->capture_available || !facts->base_recording_active) {
                result->disposition = GUMI_INTERACTION_DISPOSITION_REFUSED;
                return GUMI_INTERACTION_POLICY_STATUS_OK;
            }
            if (facts->interpretation_marker_active) {
                return GUMI_INTERACTION_POLICY_STATUS_OK;
            }
            return emit(
                result,
                GUMI_INTERACTION_INTENT_BEGIN_INTERPRETATION_MARKER,
                event->at_ms
            );
        case GUMI_BUTTON_EVENT_HOLD_RELEASED:
            if (!facts->interpretation_marker_active) {
                return GUMI_INTERACTION_POLICY_STATUS_OK;
            }
            return emit(
                result,
                GUMI_INTERACTION_INTENT_END_INTERPRETATION_MARKER,
                event->at_ms
            );
        case GUMI_BUTTON_EVENT_PHYSICAL_CONFIRMATION:
            return GUMI_INTERACTION_POLICY_STATUS_OK;
        default:
            return GUMI_INTERACTION_POLICY_STATUS_INVALID_ARGUMENT;
    }
}

const char *gumi_interaction_policy_profile_id(
    gumi_interaction_policy_profile profile
)
{
    switch (profile) {
        case GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1:
            return "manual-recording-push-to-talk-v1";
        case GUMI_INTERACTION_POLICY_CONTINUOUS_RECORDING_MARKER_V1:
            return "continuous-recording-marker-v1";
        default:
            return NULL;
    }
}

gumi_interaction_policy_status gumi_interaction_policy_evaluate(
    gumi_interaction_policy_profile profile,
    const gumi_button_event *event,
    const gumi_interaction_facts *facts,
    gumi_interaction_policy_result *result
)
{
    if (event == NULL || facts == NULL || result == NULL) {
        return GUMI_INTERACTION_POLICY_STATUS_INVALID_ARGUMENT;
    }
    memset(result, 0, sizeof(*result));
    result->disposition = GUMI_INTERACTION_DISPOSITION_IGNORED;
    if (!valid_profile(profile)) {
        return GUMI_INTERACTION_POLICY_STATUS_INVALID_PROFILE;
    }
    if (!valid_facts(facts)) {
        return GUMI_INTERACTION_POLICY_STATUS_INVALID_FACTS;
    }
    if (facts->maintenance_exclusive || facts->fatal_privacy) {
        if (event->type == GUMI_BUTTON_EVENT_SINGLE_TAP) {
            return emit(
                result, GUMI_INTERACTION_INTENT_SHOW_STATUS, event->at_ms
            );
        }
        return GUMI_INTERACTION_POLICY_STATUS_OK;
    }
    if (profile == GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1) {
        return evaluate_manual(event, facts, result);
    }
    return evaluate_continuous(event, facts, result);
}
