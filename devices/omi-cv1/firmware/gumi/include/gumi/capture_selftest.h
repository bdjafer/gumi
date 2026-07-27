#ifndef GUMI_CAPTURE_SELFTEST_H
#define GUMI_CAPTURE_SELFTEST_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GUMI_CAPTURE_SELFTEST_CONFIRMATION_HOLD_MS UINT64_C(2000)
#define GUMI_CAPTURE_SELFTEST_EXERCISE_MS UINT64_C(3000)
#define GUMI_CAPTURE_SELFTEST_MINIMUM_PCM_SAMPLES UINT32_C(32000)
#define GUMI_CAPTURE_SELFTEST_MINIMUM_OPUS_PACKETS UINT32_C(100)
#define GUMI_CAPTURE_SELFTEST_STATUS_WIRE_VERSION UINT8_C(1)
#define GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE 32U

typedef enum {
    GUMI_CAPTURE_SELFTEST_STATUS_OK = 0,
    GUMI_CAPTURE_SELFTEST_STATUS_INVALID_ARGUMENT,
    GUMI_CAPTURE_SELFTEST_STATUS_INVALID_STATE,
    GUMI_CAPTURE_SELFTEST_STATUS_TIME_REGRESSION,
    GUMI_CAPTURE_SELFTEST_STATUS_STALE_TRANSITION,
    GUMI_CAPTURE_SELFTEST_STATUS_COUNTER_EXHAUSTED,
} gumi_capture_selftest_status;

typedef enum {
    GUMI_CAPTURE_SELFTEST_PHASE_IDLE = 0,
    GUMI_CAPTURE_SELFTEST_PHASE_ARMED,
    GUMI_CAPTURE_SELFTEST_PHASE_ASSERTING_PRIVACY,
    GUMI_CAPTURE_SELFTEST_PHASE_OPENING_CODEC,
    GUMI_CAPTURE_SELFTEST_PHASE_ACQUIRING_MICROPHONE,
    GUMI_CAPTURE_SELFTEST_PHASE_EXERCISING,
    GUMI_CAPTURE_SELFTEST_PHASE_RELEASING_MICROPHONE,
    GUMI_CAPTURE_SELFTEST_PHASE_CLOSING_CODEC,
    GUMI_CAPTURE_SELFTEST_PHASE_DEASSERTING_PRIVACY,
    GUMI_CAPTURE_SELFTEST_PHASE_PASSED,
    GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE,
    GUMI_CAPTURE_SELFTEST_PHASE_FAILED_MICROPHONE_UNKNOWN,
} gumi_capture_selftest_phase;

typedef enum {
    GUMI_CAPTURE_SELFTEST_FAILURE_NONE = 0,
    GUMI_CAPTURE_SELFTEST_FAILURE_CONFIRMATION_EXPIRED,
    GUMI_CAPTURE_SELFTEST_FAILURE_PRIVACY_ASSERT,
    GUMI_CAPTURE_SELFTEST_FAILURE_CODEC_OPEN,
    GUMI_CAPTURE_SELFTEST_FAILURE_MICROPHONE_ACQUIRE,
    GUMI_CAPTURE_SELFTEST_FAILURE_MICROPHONE_RELEASE,
    GUMI_CAPTURE_SELFTEST_FAILURE_CODEC_CLOSE,
    GUMI_CAPTURE_SELFTEST_FAILURE_PRIVACY_DEASSERT,
    GUMI_CAPTURE_SELFTEST_FAILURE_INSUFFICIENT_PCM,
    GUMI_CAPTURE_SELFTEST_FAILURE_INSUFFICIENT_OPUS,
    GUMI_CAPTURE_SELFTEST_FAILURE_CODEC_DROPPED_SAMPLES,
    GUMI_CAPTURE_SELFTEST_FAILURE_CODEC_TERMINAL,
    GUMI_CAPTURE_SELFTEST_FAILURE_ASYNC_PORT,
} gumi_capture_selftest_failure;

typedef enum {
    GUMI_CAPTURE_SELFTEST_ACTION_ASSERT_PRIVACY = 0,
    GUMI_CAPTURE_SELFTEST_ACTION_OPEN_CODEC,
    GUMI_CAPTURE_SELFTEST_ACTION_ACQUIRE_MICROPHONE,
    GUMI_CAPTURE_SELFTEST_ACTION_RELEASE_MICROPHONE,
    GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DRAIN,
    GUMI_CAPTURE_SELFTEST_ACTION_CLOSE_CODEC_DISCARD,
    GUMI_CAPTURE_SELFTEST_ACTION_DEASSERT_PRIVACY,
} gumi_capture_selftest_action_type;

typedef struct {
    uint32_t pcm_blocks;
    uint32_t pcm_samples;
    uint32_t opus_packets;
    uint32_t discarded_samples;
    int32_t terminal_error;
} gumi_capture_selftest_evidence;

typedef struct {
    uint64_t transition_id;
    gumi_capture_selftest_action_type type;
} gumi_capture_selftest_action;

typedef struct {
    bool has_action;
    gumi_capture_selftest_action action;
} gumi_capture_selftest_result;

/* Public for caller-owned static allocation and diagnostics. Mutate only through this API. */
typedef struct {
    uint64_t current_ms;
    uint64_t lease_expires_ms;
    uint64_t exercise_ends_ms;
    uint64_t transition_id;
    uint64_t next_transition_id;
    uint32_t attempt;
    gumi_capture_selftest_phase phase;
    gumi_capture_selftest_failure failure;
    gumi_capture_selftest_action_type outstanding_action;
    gumi_capture_selftest_evidence evidence;
    bool action_outstanding;
    bool privacy_asserted;
    bool microphone_verified_off;
    bool codec_open;
    bool should_pass;
    bool recovery_transport_present;
    bool initialized;
} gumi_capture_selftest;

gumi_capture_selftest_status gumi_capture_selftest_init(
    gumi_capture_selftest *state,
    bool microphone_verified_off,
    bool recovery_transport_present
);

/* A lease is accepted only from a safe, microphone-off terminal state. */
gumi_capture_selftest_status gumi_capture_selftest_arm(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    uint64_t lease_expires_ms
);

/* Called only after the button layer proves a continuous two-second hold. */
gumi_capture_selftest_status gumi_capture_selftest_confirm(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    gumi_capture_selftest_result *result
);

/* Advances lease or exercise deadlines. */
gumi_capture_selftest_status gumi_capture_selftest_advance(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    gumi_capture_selftest_result *result
);

/* Completes exactly the currently outstanding platform effect. */
gumi_capture_selftest_status gumi_capture_selftest_complete(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    uint64_t transition_id,
    gumi_capture_selftest_action_type action,
    bool success,
    const gumi_capture_selftest_evidence *evidence,
    gumi_capture_selftest_result *result
);

/* Converts an unsolicited platform failure into ordered fail-closed cleanup. */
gumi_capture_selftest_status gumi_capture_selftest_async_port_failed(
    gumi_capture_selftest *state,
    uint64_t at_ms,
    gumi_capture_selftest_result *result
);

/* Read-only status; it contains counters and state, never audio bytes. */
gumi_capture_selftest_status gumi_capture_selftest_encode_status(
    const gumi_capture_selftest *state,
    uint8_t output[GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE]
);

#ifdef __cplusplus
}
#endif

#endif
