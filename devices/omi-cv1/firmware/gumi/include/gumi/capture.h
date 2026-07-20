#ifndef GUMI_CAPTURE_H
#define GUMI_CAPTURE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    GUMI_CAPTURE_STATUS_OK = 0,
    GUMI_CAPTURE_STATUS_INVALID_ARGUMENT,
    GUMI_CAPTURE_STATUS_INVALID_CONFIGURATION,
    GUMI_CAPTURE_STATUS_TIME_REGRESSION,
    GUMI_CAPTURE_STATUS_INVALID_STATE,
    GUMI_CAPTURE_STATUS_STALE_TRANSITION,
    GUMI_CAPTURE_STATUS_COUNTER_EXHAUSTED,
    GUMI_CAPTURE_STATUS_OUTPUT_OVERFLOW,
} gumi_capture_status;

typedef enum {
    GUMI_CAPTURE_POWER_BOOTING = 0,
    GUMI_CAPTURE_POWER_OPERATIONAL,
} gumi_capture_power;

typedef enum {
    GUMI_CAPTURE_MIC_VERIFIED_OFF = 0,
    GUMI_CAPTURE_MIC_ACQUIRING,
    GUMI_CAPTURE_MIC_ACQUIRED,
    GUMI_CAPTURE_MIC_RELEASING,
    GUMI_CAPTURE_MIC_UNKNOWN,
} gumi_capture_mic_truth;

typedef enum {
    GUMI_CAPTURE_VOICE_INACTIVE = 0,
    GUMI_CAPTURE_VOICE_STARTING,
    GUMI_CAPTURE_VOICE_ACTIVE,
    GUMI_CAPTURE_VOICE_ENDING,
} gumi_capture_voice_state;

typedef enum {
    GUMI_CAPTURE_STORAGE_HEALTHY = 0,
    GUMI_CAPTURE_STORAGE_LOW,
    GUMI_CAPTURE_STORAGE_FULL,
    GUMI_CAPTURE_STORAGE_CORRUPT,
} gumi_capture_storage_state;

typedef enum {
    GUMI_CAPTURE_FAULT_NONE = 0,
    GUMI_CAPTURE_FAULT_RECOVERABLE,
    GUMI_CAPTURE_FAULT_FATAL_PRIVACY,
} gumi_capture_fault;

typedef enum {
    GUMI_CAPTURE_PHASE_BOOTING = 0,
    GUMI_CAPTURE_PHASE_IDLE,
    GUMI_CAPTURE_PHASE_STARTING_BASE,
    GUMI_CAPTURE_PHASE_BASE_ACTIVE,
    GUMI_CAPTURE_PHASE_STARTING_VOICE_IDLE,
    GUMI_CAPTURE_PHASE_VOICE_IDLE_ACTIVE,
    GUMI_CAPTURE_PHASE_STARTING_VOICE_OVERLAY,
    GUMI_CAPTURE_PHASE_VOICE_OVERLAY_ACTIVE,
    GUMI_CAPTURE_PHASE_STOPPING,
    GUMI_CAPTURE_PHASE_FATAL_IDLE,
} gumi_capture_phase;

typedef enum {
    GUMI_CAPTURE_ACTION_ASSERT_PRIVACY_GUARD = 0,
    GUMI_CAPTURE_ACTION_PREPARE_LOCAL_DURABILITY,
    GUMI_CAPTURE_ACTION_ACQUIRE_MICROPHONE,
    GUMI_CAPTURE_ACTION_OPEN_REALTIME_ROUTE,
    GUMI_CAPTURE_ACTION_CLOSE_REALTIME_ROUTE,
    GUMI_CAPTURE_ACTION_FINALIZE_LOCAL_RECORDING,
    GUMI_CAPTURE_ACTION_COMMIT_LAST_DURABLE_FRAME,
    GUMI_CAPTURE_ACTION_RELEASE_MICROPHONE,
    GUMI_CAPTURE_ACTION_DEASSERT_PRIVACY_GUARD,
} gumi_capture_action_type;

typedef struct {
    uint64_t at_ms;
    uint64_t transition_id;
    gumi_capture_action_type type;
} gumi_capture_action;

typedef enum {
    GUMI_CAPTURE_EVENT_BOOT_READY = 0,
    GUMI_CAPTURE_EVENT_BASE_RECORDING_STARTED,
    GUMI_CAPTURE_EVENT_BASE_RECORDING_REFUSED,
    GUMI_CAPTURE_EVENT_SAFE_CAPTURE_STOP_REQUESTED,
    GUMI_CAPTURE_EVENT_BASE_RECORDING_STOPPED,
    GUMI_CAPTURE_EVENT_VOICE_TURN_STARTED,
    GUMI_CAPTURE_EVENT_VOICE_TURN_REFUSED,
    GUMI_CAPTURE_EVENT_VOICE_TURN_ENDED,
    GUMI_CAPTURE_EVENT_CAPTURE_DISCONTINUITY,
} gumi_capture_event_type;

typedef enum {
    GUMI_CAPTURE_REASON_NONE = 0,
    GUMI_CAPTURE_REASON_REALTIME_ADMISSION_UNAVAILABLE,
    GUMI_CAPTURE_REASON_REALTIME_ADMISSION_EXPIRED,
    GUMI_CAPTURE_REASON_PRIVACY_OUTPUT_UNAVAILABLE,
    GUMI_CAPTURE_REASON_MICROPHONE_UNAVAILABLE,
    GUMI_CAPTURE_REASON_LOCAL_DURABILITY_UNAVAILABLE,
    GUMI_CAPTURE_REASON_REALTIME_ROUTE_UNAVAILABLE,
    GUMI_CAPTURE_REASON_STORAGE_CORRUPT,
    GUMI_CAPTURE_REASON_WATCHDOG_RESET,
} gumi_capture_reason;

typedef struct {
    uint64_t at_ms;
    uint64_t transition_id;
    uint64_t recording_id;
    gumi_capture_event_type type;
    gumi_capture_reason reason;
} gumi_capture_event;

typedef enum {
    GUMI_CAPTURE_HAPTIC_NONE = 0,
    GUMI_CAPTURE_HAPTIC_READY,
    GUMI_CAPTURE_HAPTIC_VOICE_READY,
    GUMI_CAPTURE_HAPTIC_RECORDING_STARTED,
    GUMI_CAPTURE_HAPTIC_RECORDING_STOPPED,
    GUMI_CAPTURE_HAPTIC_REFUSED,
    GUMI_CAPTURE_HAPTIC_FAULT,
} gumi_capture_haptic;

#define GUMI_CAPTURE_ACTION_CAPACITY 4U
#define GUMI_CAPTURE_EVENT_CAPACITY 2U

typedef struct {
    size_t action_count;
    gumi_capture_action actions[GUMI_CAPTURE_ACTION_CAPACITY];
    size_t event_count;
    gumi_capture_event events[GUMI_CAPTURE_EVENT_CAPACITY];
    gumi_capture_haptic haptic;
} gumi_capture_result;

typedef struct {
    bool authenticated;
    uint64_t token;
    uint64_t expires_at_ms;
} gumi_capture_realtime_admission;

typedef enum {
    GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_ASSERTED = 0,
    GUMI_CAPTURE_COMPLETION_PRIVACY_GUARD_FAILED,
    GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRED,
    GUMI_CAPTURE_COMPLETION_MICROPHONE_ACQUIRE_FAILED,
    GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_READY,
    GUMI_CAPTURE_COMPLETION_LOCAL_DURABILITY_FAILED,
    GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_READY,
    GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_FAILED,
    GUMI_CAPTURE_COMPLETION_LOCAL_RECORDING_FINALIZED,
    GUMI_CAPTURE_COMPLETION_LAST_DURABLE_FRAME_COMMITTED,
    GUMI_CAPTURE_COMPLETION_REALTIME_ROUTE_CLOSED,
    GUMI_CAPTURE_COMPLETION_MICROPHONE_RELEASED,
} gumi_capture_completion;

typedef enum {
    GUMI_CAPTURE_PRIVACY_OFF = 0,
    GUMI_CAPTURE_PRIVACY_RECORDING,
    GUMI_CAPTURE_PRIVACY_VOICE_TURN,
    GUMI_CAPTURE_PRIVACY_UNKNOWN,
    GUMI_CAPTURE_PRIVACY_OUTPUT_UNAVAILABLE,
} gumi_capture_privacy_pattern;

/* Public for static allocation and diagnostics. Mutate only through the functions below. */
typedef struct {
    uint64_t current_ms;
    uint64_t transition_id;
    uint64_t next_transition_id;
    uint64_t active_recording_id;
    uint64_t next_recording_id;
    uint64_t stopping_recording_id;
    uint64_t voice_admission_expires_at_ms;
    gumi_capture_power power;
    gumi_capture_mic_truth mic_truth;
    gumi_capture_voice_state voice;
    gumi_capture_storage_state storage;
    gumi_capture_fault fault;
    gumi_capture_phase phase;
    unsigned int stop_kind;
    gumi_capture_reason stop_reason;
    bool maintenance_exclusive;
    bool base_recording_active;
    bool base_audio_permitted;
    bool voice_audio_permitted;
    bool privacy_output_healthy;
    bool privacy_guard_asserted;
    bool microphone_acquired;
    bool local_durability_ready;
    bool realtime_route_ready;
    bool realtime_action_issued;
    bool stop_wait_finalize;
    bool stop_wait_durable_boundary;
    bool stop_wait_realtime_close;
    bool stop_wait_microphone_release;
    bool stop_was_base_active;
    bool stop_was_voice_active;
    bool initialized;
} gumi_capture_supervisor;

gumi_capture_status gumi_capture_supervisor_init(gumi_capture_supervisor *state);

gumi_capture_status gumi_capture_required_self_tests_passed(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
);

gumi_capture_status gumi_capture_set_maintenance_exclusive(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    bool exclusive,
    gumi_capture_result *result
);

gumi_capture_status gumi_capture_request_base_start(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
);

gumi_capture_status gumi_capture_request_base_stop(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
);

gumi_capture_status gumi_capture_request_voice_start(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    const gumi_capture_realtime_admission *admission,
    gumi_capture_result *result
);

gumi_capture_status gumi_capture_request_voice_end(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
);

gumi_capture_status gumi_capture_complete(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    uint64_t transition_id,
    gumi_capture_completion completion,
    gumi_capture_result *result
);

gumi_capture_status gumi_capture_set_storage_state(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_storage_state storage,
    gumi_capture_result *result
);

gumi_capture_status gumi_capture_privacy_output_failed(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
);

gumi_capture_status gumi_capture_watchdog_boot(
    gumi_capture_supervisor *state,
    uint64_t at_ms,
    gumi_capture_result *result
);

bool gumi_capture_base_audio_is_permitted(const gumi_capture_supervisor *state);
bool gumi_capture_voice_audio_is_permitted(const gumi_capture_supervisor *state);
gumi_capture_privacy_pattern gumi_capture_privacy_pattern_for(
    const gumi_capture_supervisor *state
);

#ifdef __cplusplus
}
#endif

#endif
