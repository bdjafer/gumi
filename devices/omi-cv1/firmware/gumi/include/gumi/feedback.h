#ifndef GUMI_FEEDBACK_H
#define GUMI_FEEDBACK_H

#include "gumi/capture.h"

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    GUMI_FEEDBACK_STATUS_OK = 0,
    GUMI_FEEDBACK_STATUS_INVALID_ARGUMENT,
    GUMI_FEEDBACK_STATUS_INVALID_CONFIGURATION,
} gumi_feedback_status;

typedef enum {
    GUMI_FEEDBACK_PATTERN_NONE = 0,
    GUMI_FEEDBACK_PATTERN_PRIVACY_RECORDING,
    GUMI_FEEDBACK_PATTERN_PRIVACY_VOICE_TURN,
    GUMI_FEEDBACK_PATTERN_PRIVACY_UNKNOWN,
    GUMI_FEEDBACK_PATTERN_BOOTING,
    GUMI_FEEDBACK_PATTERN_PAIRING,
    GUMI_FEEDBACK_PATTERN_UPDATING,
    GUMI_FEEDBACK_PATTERN_VALIDATING,
    GUMI_FEEDBACK_PATTERN_RECOVERY_REQUIRED,
    GUMI_FEEDBACK_PATTERN_RECOVERABLE_FAULT,
    GUMI_FEEDBACK_PATTERN_CHARGING,
    GUMI_FEEDBACK_PATTERN_LOW_POWER,
    GUMI_FEEDBACK_PATTERN_READY_LINK_STATUS,
    GUMI_FEEDBACK_PATTERN_DISCONNECTED_STATUS,
} gumi_feedback_pattern;

typedef enum {
    GUMI_FEEDBACK_MAINTENANCE_NORMAL = 0,
    GUMI_FEEDBACK_MAINTENANCE_AWAITING_CONFIRMATION,
    GUMI_FEEDBACK_MAINTENANCE_PAIRING,
    GUMI_FEEDBACK_MAINTENANCE_UPDATING,
    GUMI_FEEDBACK_MAINTENANCE_VALIDATING,
    GUMI_FEEDBACK_MAINTENANCE_RECOVERY_REQUIRED,
} gumi_feedback_maintenance;

typedef enum {
    GUMI_FEEDBACK_POWER_NORMAL = 0,
    GUMI_FEEDBACK_POWER_LOW,
    GUMI_FEEDBACK_POWER_CRITICAL,
    GUMI_FEEDBACK_POWER_UNKNOWN,
} gumi_feedback_power_level;

typedef struct {
    const gumi_capture_supervisor *capture;
    gumi_feedback_maintenance maintenance;
    gumi_feedback_power_level power_level;
    gumi_feedback_pattern requested_status;
    bool charging;
    bool recoverable_warning;
} gumi_feedback_input;

typedef enum {
    GUMI_FEEDBACK_DECISION_SELECTED = 0,
    GUMI_FEEDBACK_DECISION_NO_OUTPUT,
    GUMI_FEEDBACK_DECISION_FATAL_PRIVACY_OUTPUT_UNAVAILABLE,
    GUMI_FEEDBACK_DECISION_UNRESOLVED_SAME_PRIORITY,
} gumi_feedback_decision_status;

typedef struct {
    gumi_feedback_pattern selected;
    uint32_t suppressed_patterns;
    uint32_t unresolved_same_priority;
    gumi_feedback_decision_status status;
} gumi_feedback_decision;

gumi_feedback_status gumi_feedback_decide(
    const gumi_feedback_input *input,
    gumi_feedback_decision *decision
);

uint32_t gumi_feedback_pattern_mask(gumi_feedback_pattern pattern);

#ifdef __cplusplus
}
#endif

#endif
