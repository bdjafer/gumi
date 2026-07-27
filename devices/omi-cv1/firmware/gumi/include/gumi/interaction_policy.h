#ifndef GUMI_INTERACTION_POLICY_H
#define GUMI_INTERACTION_POLICY_H

#include <stdbool.h>
#include <stddef.h>

#include "gumi/button.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Product interaction policy consumes semantic physical gestures. It never
 * reads GPIO, drives feedback, acquires the microphone, writes media, or
 * authorizes maintenance.
 */
typedef enum {
    GUMI_INTERACTION_POLICY_MANUAL_RECORDING_PUSH_TO_TALK_V1 = 0,
    GUMI_INTERACTION_POLICY_CONTINUOUS_RECORDING_MARKER_V1,
} gumi_interaction_policy_profile;

typedef enum {
    GUMI_INTERACTION_POLICY_STATUS_OK = 0,
    GUMI_INTERACTION_POLICY_STATUS_INVALID_ARGUMENT,
    GUMI_INTERACTION_POLICY_STATUS_INVALID_PROFILE,
    GUMI_INTERACTION_POLICY_STATUS_INVALID_FACTS,
    GUMI_INTERACTION_POLICY_STATUS_OUTPUT_OVERFLOW,
} gumi_interaction_policy_status;

typedef enum {
    GUMI_INTERACTION_INTENT_SHOW_STATUS = 0,
    GUMI_INTERACTION_INTENT_START_BASE_RECORDING,
    GUMI_INTERACTION_INTENT_STOP_BASE_RECORDING,
    GUMI_INTERACTION_INTENT_BEGIN_VOICE_ACTION,
    GUMI_INTERACTION_INTENT_END_VOICE_ACTION,
    GUMI_INTERACTION_INTENT_BEGIN_INTERPRETATION_MARKER,
    GUMI_INTERACTION_INTENT_END_INTERPRETATION_MARKER,
} gumi_interaction_intent_type;

typedef struct {
    gumi_interaction_intent_type type;
    uint64_t at_ms;
} gumi_interaction_intent;

typedef enum {
    GUMI_INTERACTION_DISPOSITION_APPLIED = 0,
    GUMI_INTERACTION_DISPOSITION_IGNORED,
    GUMI_INTERACTION_DISPOSITION_REFUSED,
} gumi_interaction_disposition;

/*
 * These are current, coordinator-owned facts. The policy does not retain a
 * shadow copy, so stale gesture processing cannot become capture authority.
 */
typedef struct {
    bool capture_available;
    bool base_recording_active;
    bool voice_action_active;
    bool interpretation_marker_active;
    bool maintenance_exclusive;
    bool fatal_privacy;
} gumi_interaction_facts;

#define GUMI_INTERACTION_INTENT_CAPACITY 1U

typedef struct {
    gumi_interaction_disposition disposition;
    size_t intent_count;
    gumi_interaction_intent intents[GUMI_INTERACTION_INTENT_CAPACITY];
} gumi_interaction_policy_result;

const char *gumi_interaction_policy_profile_id(
    gumi_interaction_policy_profile profile
);

gumi_interaction_policy_status gumi_interaction_policy_evaluate(
    gumi_interaction_policy_profile profile,
    const gumi_button_event *event,
    const gumi_interaction_facts *facts,
    gumi_interaction_policy_result *result
);

#ifdef __cplusplus
}
#endif

#endif
