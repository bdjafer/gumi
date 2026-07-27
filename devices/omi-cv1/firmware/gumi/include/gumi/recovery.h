#ifndef GUMI_RECOVERY_H
#define GUMI_RECOVERY_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    GUMI_RECOVERY_STATUS_OK = 0,
    GUMI_RECOVERY_STATUS_INVALID_ARGUMENT,
    GUMI_RECOVERY_STATUS_INVALID_STATE,
    GUMI_RECOVERY_STATUS_TIME_REGRESSION,
    GUMI_RECOVERY_STATUS_STALE_TRANSITION,
    GUMI_RECOVERY_STATUS_COUNTER_EXHAUSTED,
} gumi_recovery_status;

typedef enum {
    GUMI_RECOVERY_PHASE_COLD = 0,
    GUMI_RECOVERY_PHASE_STARTING_TRANSPORT,
    GUMI_RECOVERY_PHASE_VERIFYING_MICROPHONE_OFF,
    GUMI_RECOVERY_PHASE_RUNNING_SELF_TESTS,
    GUMI_RECOVERY_PHASE_ENABLING_FUNCTIONAL_SERVICES,
    GUMI_RECOVERY_PHASE_OPERATIONAL,
    GUMI_RECOVERY_PHASE_QUIESCING_TO_SAFE_MODE,
    GUMI_RECOVERY_PHASE_SAFE_MODE,
    GUMI_RECOVERY_PHASE_RECOVERY_UNAVAILABLE,
} gumi_recovery_phase;

typedef enum {
    GUMI_RECOVERY_REASON_NONE = 0,
    GUMI_RECOVERY_REASON_EXPLICIT_SAFE_MODE,
    GUMI_RECOVERY_REASON_PERSISTED_SAFE_MODE,
    GUMI_RECOVERY_REASON_WATCHDOG_OR_LOCKUP_RESET,
    GUMI_RECOVERY_REASON_TRANSPORT_UNAVAILABLE,
    GUMI_RECOVERY_REASON_MICROPHONE_OFF_UNVERIFIED,
    GUMI_RECOVERY_REASON_SELF_TEST_FAILED,
    GUMI_RECOVERY_REASON_FUNCTIONAL_SERVICES_FAILED,
    GUMI_RECOVERY_REASON_RUNTIME_FAULT,
    GUMI_RECOVERY_REASON_QUIESCE_FAILED,
} gumi_recovery_reason;

typedef enum {
    GUMI_RECOVERY_ACTION_START_TRANSPORT = 0,
    GUMI_RECOVERY_ACTION_VERIFY_MICROPHONE_OFF,
    GUMI_RECOVERY_ACTION_RUN_FUNCTIONAL_SELF_TESTS,
    GUMI_RECOVERY_ACTION_ENABLE_FUNCTIONAL_SERVICES,
    GUMI_RECOVERY_ACTION_QUIESCE_FUNCTIONAL_SERVICES,
} gumi_recovery_action_type;

typedef enum {
    GUMI_RECOVERY_COMPLETION_TRANSPORT_READY = 0,
    GUMI_RECOVERY_COMPLETION_TRANSPORT_FAILED,
    GUMI_RECOVERY_COMPLETION_MICROPHONE_VERIFIED_OFF,
    GUMI_RECOVERY_COMPLETION_MICROPHONE_OFF_FAILED,
    GUMI_RECOVERY_COMPLETION_SELF_TESTS_PASSED,
    GUMI_RECOVERY_COMPLETION_SELF_TESTS_FAILED,
    GUMI_RECOVERY_COMPLETION_FUNCTIONAL_SERVICES_READY,
    GUMI_RECOVERY_COMPLETION_FUNCTIONAL_SERVICES_FAILED,
    GUMI_RECOVERY_COMPLETION_FUNCTIONAL_SERVICES_QUIESCED,
    GUMI_RECOVERY_COMPLETION_FUNCTIONAL_QUIESCE_FAILED,
} gumi_recovery_completion;

typedef enum {
    GUMI_RECOVERY_EVENT_RECOVERY_AVAILABLE = 0,
    GUMI_RECOVERY_EVENT_OPERATIONAL_READY,
    GUMI_RECOVERY_EVENT_SAFE_MODE_READY,
    GUMI_RECOVERY_EVENT_RECOVERY_UNAVAILABLE,
} gumi_recovery_event_type;

typedef struct {
    bool explicit_safe_mode;
    bool persisted_safe_mode;
    bool watchdog_or_lockup_reset;
} gumi_recovery_boot_evidence;

typedef struct {
    uint64_t at_ms;
    uint64_t transition_id;
    gumi_recovery_action_type type;
} gumi_recovery_action;

typedef struct {
    uint64_t at_ms;
    gumi_recovery_event_type type;
    gumi_recovery_reason reason;
} gumi_recovery_event;

typedef struct {
    bool has_action;
    gumi_recovery_action action;
    bool has_event;
    gumi_recovery_event event;
} gumi_recovery_result;

/* Public for caller-owned static allocation and diagnostics. Mutate only through this API. */
typedef struct {
    uint64_t current_ms;
    uint64_t transition_id;
    uint64_t next_transition_id;
    gumi_recovery_phase phase;
    gumi_recovery_reason reason;
    bool safe_mode_requested;
    bool recovery_transport_ready;
    bool microphone_verified_off;
    bool self_tests_passed;
    bool functional_services_ready;
    bool capture_permitted;
    bool initialized;
} gumi_recovery_supervisor;

#define GUMI_RECOVERY_STATUS_WIRE_SIZE 4U
#define GUMI_RECOVERY_STATUS_WIRE_VERSION 1U

gumi_recovery_status gumi_recovery_supervisor_init(
    gumi_recovery_supervisor *state,
    const gumi_recovery_boot_evidence *evidence
);

/* The first emitted action is always START_TRANSPORT. */
gumi_recovery_status gumi_recovery_begin(
    gumi_recovery_supervisor *state,
    uint64_t at_ms,
    gumi_recovery_result *result
);

gumi_recovery_status gumi_recovery_complete(
    gumi_recovery_supervisor *state,
    uint64_t at_ms,
    uint64_t transition_id,
    gumi_recovery_completion completion,
    gumi_recovery_result *result
);

/* Revokes capture admission synchronously before requesting platform quiescence. */
gumi_recovery_status gumi_recovery_runtime_fault(
    gumi_recovery_supervisor *state,
    uint64_t at_ms,
    gumi_recovery_result *result
);

bool gumi_recovery_capture_is_permitted(const gumi_recovery_supervisor *state);
bool gumi_recovery_transport_is_available(const gumi_recovery_supervisor *state);

/*
 * Versioned read-only GATT payload:
 * [schema, phase, reason, flags]. Flags are transport, microphone-off, self-tests,
 * functional-services, capture-admission, and the observed overwrite-only boot policy.
 */
gumi_recovery_status gumi_recovery_encode_status(
    const gumi_recovery_supervisor *state,
    uint8_t output[GUMI_RECOVERY_STATUS_WIRE_SIZE]
);

#ifdef __cplusplus
}
#endif

#endif
