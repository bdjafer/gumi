#include "gumi/semantic_signal.h"

#include <stdio.h>
#include <stdlib.h>

static unsigned int tests_run;

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #condition); \
        exit(EXIT_FAILURE); \
    } \
} while (0)

static void test_marker_is_correlated_to_recording_and_time(void)
{
    gumi_semantic_signal_tracker state;
    gumi_semantic_signal_event event;

    CHECK(gumi_semantic_signal_init(&state) == GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(gumi_semantic_signal_begin(
        &state, 100U, 7U, GUMI_SEMANTIC_SIGNAL_INTERPRETATION, &event
    ) == GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(event.type == GUMI_SEMANTIC_SIGNAL_EVENT_STARTED);
    CHECK(event.signal_id == 1U);
    CHECK(event.recording_id == 7U);
    CHECK(event.started_at_ms == 100U);
    CHECK(state.active);
    CHECK(gumi_semantic_signal_end(&state, 150U, &event) ==
        GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(event.type == GUMI_SEMANTIC_SIGNAL_EVENT_ENDED);
    CHECK(event.signal_id == 1U);
    CHECK(event.recording_id == 7U);
    CHECK(event.started_at_ms == 100U);
    CHECK(event.ended_at_ms == 150U);
    CHECK(!state.active);
    tests_run += 1U;
}

static void test_recording_boundary_interrupts_open_marker(void)
{
    gumi_semantic_signal_tracker state;
    gumi_semantic_signal_event event;

    CHECK(gumi_semantic_signal_init(&state) == GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(gumi_semantic_signal_begin(
        &state, 5U, 11U, GUMI_SEMANTIC_SIGNAL_INTERPRETATION, &event
    ) == GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(gumi_semantic_signal_recording_closed(&state, 8U, 11U, &event) ==
        GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(event.type == GUMI_SEMANTIC_SIGNAL_EVENT_INTERRUPTED);
    CHECK(event.ended_at_ms == 8U);
    CHECK(!state.active);
    tests_run += 1U;
}

static void test_marker_cannot_exist_without_recording(void)
{
    gumi_semantic_signal_tracker state;
    gumi_semantic_signal_event event;

    CHECK(gumi_semantic_signal_init(&state) == GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(gumi_semantic_signal_begin(
        &state, 1U, 0U, GUMI_SEMANTIC_SIGNAL_INTERPRETATION, &event
    ) == GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_ARGUMENT);
    CHECK(!state.active);
    tests_run += 1U;
}

static void test_duplicate_begin_and_end_are_rejected(void)
{
    gumi_semantic_signal_tracker state;
    gumi_semantic_signal_event event;

    CHECK(gumi_semantic_signal_init(&state) == GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(gumi_semantic_signal_end(&state, 0U, &event) ==
        GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_STATE);
    CHECK(gumi_semantic_signal_begin(
        &state, 1U, 2U, GUMI_SEMANTIC_SIGNAL_INTERPRETATION, &event
    ) == GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(gumi_semantic_signal_begin(
        &state, 2U, 2U, GUMI_SEMANTIC_SIGNAL_INTERPRETATION, &event
    ) == GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_STATE);
    tests_run += 1U;
}

static void test_time_regression_is_transactional(void)
{
    gumi_semantic_signal_tracker state;
    gumi_semantic_signal_event event;

    CHECK(gumi_semantic_signal_init(&state) == GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(gumi_semantic_signal_begin(
        &state, 10U, 3U, GUMI_SEMANTIC_SIGNAL_INTERPRETATION, &event
    ) == GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(gumi_semantic_signal_end(&state, 9U, &event) ==
        GUMI_SEMANTIC_SIGNAL_STATUS_TIME_REGRESSION);
    CHECK(state.active);
    CHECK(state.active_signal_id == 1U);
    tests_run += 1U;
}

static void test_wrong_recording_cannot_close_marker(void)
{
    gumi_semantic_signal_tracker state;
    gumi_semantic_signal_event event;

    CHECK(gumi_semantic_signal_init(&state) == GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(gumi_semantic_signal_begin(
        &state, 10U, 3U, GUMI_SEMANTIC_SIGNAL_INTERPRETATION, &event
    ) == GUMI_SEMANTIC_SIGNAL_STATUS_OK);
    CHECK(gumi_semantic_signal_recording_closed(&state, 12U, 4U, &event) ==
        GUMI_SEMANTIC_SIGNAL_STATUS_INVALID_STATE);
    CHECK(state.active);
    tests_run += 1U;
}

int main(void)
{
    test_marker_is_correlated_to_recording_and_time();
    test_recording_boundary_interrupts_open_marker();
    test_marker_cannot_exist_without_recording();
    test_duplicate_begin_and_end_are_rejected();
    test_time_regression_is_transactional();
    test_wrong_recording_cannot_close_marker();
    printf("PASS: %u portable semantic-signal tests\n", tests_run);
    return EXIT_SUCCESS;
}
