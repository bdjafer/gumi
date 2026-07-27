#ifndef GUMI_SEMANTIC_SIGNAL_H
#define GUMI_SEMANTIC_SIGNAL_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    GUMI_SEMANTIC_SIGNAL_STATUS_OK = 0,
    GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_ARGUMENT,
    GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_STATE,
    GUMI_SEMANTIC_SIGNAL_STATUS_TIME_REGRESSION,
    GUMI_SEMANTIC_SIGNAL_STATUS_COUNTER_EXHAUSTED,
} gumi_semantic_signal_status;

typedef enum {
    GUMI_SEMANTIC_SIGNAL_INTERPRETATION = 0,
} gumi_semantic_signal_kind;

typedef enum {
    GUMI_SEMANTIC_SIGNAL_EVENT_STARTED = 0,
    GUMI_SEMANTIC_SIGNAL_EVENT_ENDED,
    GUMI_SEMANTIC_SIGNAL_EVENT_INTERRUPTED,
} gumi_semantic_signal_event_type;

typedef struct {
    gumi_semantic_signal_event_type type;
    gumi_semantic_signal_kind kind;
    uint64_t signal_id;
    uint64_t recording_id;
    uint64_t started_at_ms;
    uint64_t ended_at_ms;
} gumi_semantic_signal_event;

/* Public for static allocation and diagnostics. Mutate only through this API. */
typedef struct {
    uint64_t current_ms;
    uint64_t next_signal_id;
    uint64_t active_signal_id;
    uint64_t active_recording_id;
    uint64_t active_started_at_ms;
    gumi_semantic_signal_kind active_kind;
    bool active;
    bool initialized;
} gumi_semantic_signal_tracker;

gumi_semantic_signal_status gumi_semantic_signal_init(
    gumi_semantic_signal_tracker *state
);

gumi_semantic_signal_status gumi_semantic_signal_begin(
    gumi_semantic_signal_tracker *state,
    uint64_t at_ms,
    uint64_t recording_id,
    gumi_semantic_signal_kind kind,
    gumi_semantic_signal_event *event
);

gumi_semantic_signal_status gumi_semantic_signal_end(
    gumi_semantic_signal_tracker *state,
    uint64_t at_ms,
    gumi_semantic_signal_event *event
);

/*
 * Closes an active signal at the same durable recording boundary. The emitted
 * event remains data; this tracker never writes a journal or changes capture.
 */
gumi_semantic_signal_status gumi_semantic_signal_recording_closed(
    gumi_semantic_signal_tracker *state,
    uint64_t at_ms,
    uint64_t recording_id,
    gumi_semantic_signal_event *event
);

#ifdef __cplusplus
}
#endif

#endif
