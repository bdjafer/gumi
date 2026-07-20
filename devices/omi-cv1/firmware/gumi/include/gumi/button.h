#ifndef GUMI_BUTTON_H
#define GUMI_BUTTON_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Device-owned timing constants from gumi.omi-cv1-human-io/v1. */
#define GUMI_BUTTON_DEBOUNCE_MS UINT64_C(30)
#define GUMI_BUTTON_DOUBLE_TAP_WINDOW_MS UINT64_C(350)
#define GUMI_BUTTON_HOLD_MS UINT64_C(500)
#define GUMI_BUTTON_CONFIRMATION_HOLD_MS UINT64_C(2000)

typedef enum {
    GUMI_BUTTON_STATUS_OK = 0,
    GUMI_BUTTON_STATUS_INVALID_ARGUMENT,
    GUMI_BUTTON_STATUS_INVALID_CONFIGURATION,
    GUMI_BUTTON_STATUS_TIME_REGRESSION,
    GUMI_BUTTON_STATUS_TIMESTAMP_PRECEDENCE,
    GUMI_BUTTON_STATUS_EDGE_ORDER,
    GUMI_BUTTON_STATUS_TIME_OVERFLOW,
    GUMI_BUTTON_STATUS_EVENT_OVERFLOW,
} gumi_button_status;

typedef enum {
    GUMI_BUTTON_LEVEL_RELEASED = 0,
    GUMI_BUTTON_LEVEL_PRESSED = 1,
} gumi_button_level;

typedef struct {
    bool emitted;
    uint64_t at_ms;
    gumi_button_level level;
} gumi_button_edge_result;

/**
 * Allocation-free stable-level debouncer.
 *
 * The structure is public so firmware can own it statically. Callers must treat its fields as
 * read-only and initialize it through gumi_button_debouncer_init().
 */
typedef struct {
    uint64_t stable_ms;
    uint64_t observed_since_ms;
    uint64_t current_ms;
    gumi_button_level observed_level;
    gumi_button_level accepted_level;
    bool initialized;
} gumi_button_debouncer;

gumi_button_status gumi_button_debouncer_init(
    gumi_button_debouncer *state,
    uint64_t stable_ms,
    gumi_button_level initial_level
);

gumi_button_status gumi_button_debouncer_on_raw_level(
    gumi_button_debouncer *state,
    uint64_t at_ms,
    gumi_button_level level,
    gumi_button_edge_result *result
);

gumi_button_status gumi_button_debouncer_advance_to(
    gumi_button_debouncer *state,
    uint64_t at_ms,
    gumi_button_edge_result *result
);

/* Recording state and realtime admission deliberately remain outside the physical recognizer. */
typedef enum {
    GUMI_BUTTON_CONTEXT_NORMAL = 0,
    GUMI_BUTTON_CONTEXT_MAINTENANCE_EXCLUSIVE,
    GUMI_BUTTON_CONTEXT_AWAITING_CONFIRMATION,
    GUMI_BUTTON_CONTEXT_FATAL_PRIVACY,
} gumi_button_context;

typedef enum {
    GUMI_BUTTON_EVENT_SINGLE_TAP = 0,
    GUMI_BUTTON_EVENT_DOUBLE_TAP,
    GUMI_BUTTON_EVENT_HOLD_COMMITTED,
    GUMI_BUTTON_EVENT_HOLD_RELEASED,
    GUMI_BUTTON_EVENT_PHYSICAL_CONFIRMATION,
} gumi_button_event_type;

typedef struct {
    uint64_t at_ms;
    gumi_button_event_type type;
    /* Zero except for PHYSICAL_CONFIRMATION. Zero is never a valid operation token. */
    uint32_t confirmation_operation_token;
} gumi_button_event;

#define GUMI_BUTTON_EVENT_BATCH_CAPACITY 2U

typedef struct {
    size_t count;
    gumi_button_event events[GUMI_BUTTON_EVENT_BATCH_CAPACITY];
} gumi_button_event_batch;

typedef struct {
    gumi_button_context context;
    /* Required and non-zero only for AWAITING_CONFIRMATION. */
    uint32_t confirmation_operation_token;
    /* The 2 s commitment deadline must be strictly earlier than this monotonic instant. */
    uint64_t confirmation_lease_expires_at_ms;
} gumi_button_gesture_config;

/**
 * Allocation-free physical gesture recognizer.
 *
 * Accepted edges at T must be supplied before advance_to(T). This makes release win at the exact
 * hold deadline and a second press win at the exact double-tap deadline. Policy actions such as
 * starting capture are intentionally not emitted here; the capture lifecycle must authorize them
 * against live device state.
 */
typedef struct {
    uint64_t current_ms;
    uint64_t pressed_at_ms;
    uint64_t first_tap_released_at_ms;
    uint64_t confirmation_lease_expires_at_ms;
    uint32_t confirmation_operation_token;
    gumi_button_context context;
    bool pressed;
    bool has_first_tap;
    bool second_press;
    bool hold_committed;
    bool confirmation_committed;
    bool deadlines_advanced_at_current_ms;
    bool initialized;
} gumi_button_gesture;

gumi_button_status gumi_button_gesture_init(
    gumi_button_gesture *state,
    const gumi_button_gesture_config *config
);

gumi_button_status gumi_button_gesture_accept_edge(
    gumi_button_gesture *state,
    uint64_t at_ms,
    gumi_button_level level,
    gumi_button_event_batch *events
);

gumi_button_status gumi_button_gesture_advance_to(
    gumi_button_gesture *state,
    uint64_t at_ms,
    gumi_button_event_batch *events
);

#ifdef __cplusplus
}
#endif

#endif
