#include "gumi/feedback.h"

#include <string.h>

enum {
    PRIORITY_STATUS = 1,
    PRIORITY_POWER = 2,
    PRIORITY_WARNING = 3,
    PRIORITY_MAINTENANCE = 4,
    PRIORITY_PRIVACY = 6,
};

typedef struct {
    uint32_t mask;
    unsigned int priority;
} candidate_set;

static bool pattern_is_valid(gumi_feedback_pattern pattern)
{
    return pattern >= GUMI_FEEDBACK_PATTERN_NONE &&
           pattern <= GUMI_FEEDBACK_PATTERN_DISCONNECTED_STATUS;
}

static bool maintenance_is_valid(gumi_feedback_maintenance maintenance)
{
    return maintenance >= GUMI_FEEDBACK_MAINTENANCE_NORMAL &&
           maintenance <= GUMI_FEEDBACK_MAINTENANCE_RECOVERY_REQUIRED;
}

static bool power_is_valid(gumi_feedback_power_level power)
{
    return power >= GUMI_FEEDBACK_POWER_NORMAL && power <= GUMI_FEEDBACK_POWER_UNKNOWN;
}

uint32_t gumi_feedback_pattern_mask(gumi_feedback_pattern pattern)
{
    if (pattern <= GUMI_FEEDBACK_PATTERN_NONE ||
        pattern > GUMI_FEEDBACK_PATTERN_DISCONNECTED_STATUS) {
        return UINT32_C(0);
    }
    return UINT32_C(1) << ((unsigned int) pattern - 1U);
}

static unsigned int bit_count(uint32_t value)
{
    unsigned int count = 0U;
    while (value != UINT32_C(0)) {
        count += value & UINT32_C(1);
        value >>= 1U;
    }
    return count;
}

static gumi_feedback_pattern single_pattern(uint32_t mask)
{
    unsigned int index = 1U;
    while ((mask & UINT32_C(1)) == UINT32_C(0)) {
        mask >>= 1U;
        index += 1U;
    }
    return (gumi_feedback_pattern) index;
}

static gumi_feedback_pattern maintenance_pattern(gumi_feedback_maintenance maintenance)
{
    switch (maintenance) {
        case GUMI_FEEDBACK_MAINTENANCE_NORMAL:
        case GUMI_FEEDBACK_MAINTENANCE_AWAITING_CONFIRMATION:
            return GUMI_FEEDBACK_PATTERN_NONE;
        case GUMI_FEEDBACK_MAINTENANCE_PAIRING:
            return GUMI_FEEDBACK_PATTERN_PAIRING;
        case GUMI_FEEDBACK_MAINTENANCE_UPDATING:
            return GUMI_FEEDBACK_PATTERN_UPDATING;
        case GUMI_FEEDBACK_MAINTENANCE_VALIDATING:
            return GUMI_FEEDBACK_PATTERN_VALIDATING;
        case GUMI_FEEDBACK_MAINTENANCE_RECOVERY_REQUIRED:
            return GUMI_FEEDBACK_PATTERN_RECOVERY_REQUIRED;
        default:
            return GUMI_FEEDBACK_PATTERN_NONE;
    }
}

static gumi_feedback_pattern privacy_pattern(const gumi_capture_supervisor *capture)
{
    switch (gumi_capture_privacy_pattern_for(capture)) {
        case GUMI_CAPTURE_PRIVACY_OFF:
            return GUMI_FEEDBACK_PATTERN_NONE;
        case GUMI_CAPTURE_PRIVACY_RECORDING:
            return GUMI_FEEDBACK_PATTERN_PRIVACY_RECORDING;
        case GUMI_CAPTURE_PRIVACY_VOICE_TURN:
            return GUMI_FEEDBACK_PATTERN_PRIVACY_VOICE_TURN;
        case GUMI_CAPTURE_PRIVACY_UNKNOWN:
            return GUMI_FEEDBACK_PATTERN_PRIVACY_UNKNOWN;
        case GUMI_CAPTURE_PRIVACY_OUTPUT_UNAVAILABLE:
            return GUMI_FEEDBACK_PATTERN_NONE;
        default:
            return GUMI_FEEDBACK_PATTERN_NONE;
    }
}

static void add_candidate(
    candidate_set *sets,
    unsigned int priority,
    gumi_feedback_pattern pattern,
    uint32_t *all
)
{
    uint32_t mask = gumi_feedback_pattern_mask(pattern);
    sets[priority].mask |= mask;
    sets[priority].priority = priority;
    *all |= mask;
}

gumi_feedback_status gumi_feedback_decide(
    const gumi_feedback_input *input,
    gumi_feedback_decision *decision
)
{
    candidate_set sets[PRIORITY_PRIVACY + 1U];
    gumi_feedback_pattern pattern;
    uint32_t all = UINT32_C(0);
    unsigned int priority;

    if (input == NULL || decision == NULL || input->capture == NULL) {
        return GUMI_FEEDBACK_STATUS_INVALID_ARGUMENT;
    }
    if (!input->capture->initialized || !maintenance_is_valid(input->maintenance) ||
        !power_is_valid(input->power_level) || !pattern_is_valid(input->requested_status)) {
        return GUMI_FEEDBACK_STATUS_INVALID_CONFIGURATION;
    }
    if (input->requested_status != GUMI_FEEDBACK_PATTERN_NONE &&
        input->requested_status != GUMI_FEEDBACK_PATTERN_READY_LINK_STATUS &&
        input->requested_status != GUMI_FEEDBACK_PATTERN_DISCONNECTED_STATUS) {
        return GUMI_FEEDBACK_STATUS_INVALID_CONFIGURATION;
    }

    memset(sets, 0, sizeof(sets));
    memset(decision, 0, sizeof(*decision));
    decision->selected = GUMI_FEEDBACK_PATTERN_NONE;

    pattern = privacy_pattern(input->capture);
    if (pattern != GUMI_FEEDBACK_PATTERN_NONE) {
        add_candidate(sets, PRIORITY_PRIVACY, pattern, &all);
    }
    pattern = maintenance_pattern(input->maintenance);
    if (pattern != GUMI_FEEDBACK_PATTERN_NONE) {
        add_candidate(sets, PRIORITY_MAINTENANCE, pattern, &all);
    }
    if (input->recoverable_warning || input->capture->fault == GUMI_CAPTURE_FAULT_RECOVERABLE) {
        add_candidate(
            sets,
            PRIORITY_WARNING,
            GUMI_FEEDBACK_PATTERN_RECOVERABLE_FAULT,
            &all
        );
    }
    if (input->capture->power == GUMI_CAPTURE_POWER_BOOTING) {
        add_candidate(sets, PRIORITY_POWER, GUMI_FEEDBACK_PATTERN_BOOTING, &all);
    }
    if (input->power_level == GUMI_FEEDBACK_POWER_LOW ||
        input->power_level == GUMI_FEEDBACK_POWER_CRITICAL) {
        add_candidate(sets, PRIORITY_POWER, GUMI_FEEDBACK_PATTERN_LOW_POWER, &all);
    }
    if (input->charging) {
        add_candidate(sets, PRIORITY_POWER, GUMI_FEEDBACK_PATTERN_CHARGING, &all);
    }
    if (input->requested_status != GUMI_FEEDBACK_PATTERN_NONE) {
        add_candidate(sets, PRIORITY_STATUS, input->requested_status, &all);
    }

    if (input->capture->fault == GUMI_CAPTURE_FAULT_FATAL_PRIVACY &&
        (!input->capture->privacy_output_healthy ||
         input->capture->mic_truth == GUMI_CAPTURE_MIC_VERIFIED_OFF)) {
        decision->status = GUMI_FEEDBACK_DECISION_FATAL_PRIVACY_OUTPUT_UNAVAILABLE;
        decision->suppressed_patterns = all;
        return GUMI_FEEDBACK_STATUS_OK;
    }
    if (all == UINT32_C(0)) {
        decision->status = GUMI_FEEDBACK_DECISION_NO_OUTPUT;
        return GUMI_FEEDBACK_STATUS_OK;
    }

    for (priority = PRIORITY_PRIVACY; priority > 0U; priority -= 1U) {
        uint32_t top = sets[priority].mask;
        if (top == UINT32_C(0)) continue;
        if (bit_count(top) > 1U) {
            decision->status = GUMI_FEEDBACK_DECISION_UNRESOLVED_SAME_PRIORITY;
            decision->unresolved_same_priority = top;
            decision->suppressed_patterns = all & ~top;
            return GUMI_FEEDBACK_STATUS_OK;
        }
        decision->status = GUMI_FEEDBACK_DECISION_SELECTED;
        decision->selected = single_pattern(top);
        decision->suppressed_patterns = all & ~top;
        return GUMI_FEEDBACK_STATUS_OK;
    }
    return GUMI_FEEDBACK_STATUS_INVALID_CONFIGURATION;
}
