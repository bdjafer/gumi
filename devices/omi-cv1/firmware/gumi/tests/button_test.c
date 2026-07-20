#include "gumi/button.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned int tests_run;

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #condition); \
        exit(EXIT_FAILURE); \
    } \
} while (0)

#define CHECK_STATUS(expression, expected) do { \
    gumi_button_status actual_status = (expression); \
    CHECK(actual_status == (expected)); \
} while (0)

static gumi_button_gesture normal_gesture(void)
{
    gumi_button_gesture state;
    const gumi_button_gesture_config config = {
        .context = GUMI_BUTTON_CONTEXT_NORMAL,
        .confirmation_operation_token = 0U,
        .confirmation_lease_expires_at_ms = UINT64_C(0),
    };

    CHECK_STATUS(gumi_button_gesture_init(&state, &config), GUMI_BUTTON_STATUS_OK);
    return state;
}

static gumi_button_gesture contextual_gesture(uint64_t lease_expires_at_ms)
{
    gumi_button_gesture state;
    const gumi_button_gesture_config config = {
        .context = GUMI_BUTTON_CONTEXT_AWAITING_CONFIRMATION,
        .confirmation_operation_token = UINT32_C(0x47554d49),
        .confirmation_lease_expires_at_ms = lease_expires_at_ms,
    };

    CHECK_STATUS(gumi_button_gesture_init(&state, &config), GUMI_BUTTON_STATUS_OK);
    return state;
}

static void expect_empty(const gumi_button_event_batch *events)
{
    CHECK(events->count == 0U);
}

static void expect_event(
    const gumi_button_event_batch *events,
    size_t index,
    uint64_t at_ms,
    gumi_button_event_type type
)
{
    CHECK(events->count > index);
    CHECK(events->events[index].at_ms == at_ms);
    CHECK(events->events[index].type == type);
}

static void test_debounce_collapses_switch_bounce(void)
{
    gumi_button_debouncer state;
    gumi_button_edge_result edge;

    CHECK_STATUS(
        gumi_button_debouncer_init(
            &state,
            GUMI_BUTTON_DEBOUNCE_MS,
            GUMI_BUTTON_LEVEL_RELEASED
        ),
        GUMI_BUTTON_STATUS_OK
    );
    CHECK_STATUS(
        gumi_button_debouncer_on_raw_level(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &edge),
        GUMI_BUTTON_STATUS_OK
    );
    CHECK(!edge.emitted);
    CHECK_STATUS(
        gumi_button_debouncer_on_raw_level(&state, 10U, GUMI_BUTTON_LEVEL_RELEASED, &edge),
        GUMI_BUTTON_STATUS_OK
    );
    CHECK(!edge.emitted);
    CHECK_STATUS(
        gumi_button_debouncer_on_raw_level(&state, 18U, GUMI_BUTTON_LEVEL_PRESSED, &edge),
        GUMI_BUTTON_STATUS_OK
    );
    CHECK(!edge.emitted);
    CHECK_STATUS(
        gumi_button_debouncer_on_raw_level(&state, 100U, GUMI_BUTTON_LEVEL_RELEASED, &edge),
        GUMI_BUTTON_STATUS_OK
    );
    CHECK(edge.emitted && edge.at_ms == 48U && edge.level == GUMI_BUTTON_LEVEL_PRESSED);
    CHECK_STATUS(gumi_button_debouncer_advance_to(&state, 130U, &edge), GUMI_BUTTON_STATUS_OK);
    CHECK(edge.emitted && edge.at_ms == 130U && edge.level == GUMI_BUTTON_LEVEL_RELEASED);
    tests_run += 1U;
}

static void test_debounce_exact_deadline_precedes_new_raw_level(void)
{
    gumi_button_debouncer state;
    gumi_button_edge_result edge;

    CHECK_STATUS(
        gumi_button_debouncer_init(&state, 30U, GUMI_BUTTON_LEVEL_RELEASED),
        GUMI_BUTTON_STATUS_OK
    );
    CHECK_STATUS(
        gumi_button_debouncer_on_raw_level(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &edge),
        GUMI_BUTTON_STATUS_OK
    );
    CHECK_STATUS(
        gumi_button_debouncer_on_raw_level(&state, 30U, GUMI_BUTTON_LEVEL_RELEASED, &edge),
        GUMI_BUTTON_STATUS_OK
    );
    CHECK(edge.emitted && edge.at_ms == 30U && edge.level == GUMI_BUTTON_LEVEL_PRESSED);
    CHECK_STATUS(gumi_button_debouncer_advance_to(&state, 60U, &edge), GUMI_BUTTON_STATUS_OK);
    CHECK(edge.emitted && edge.at_ms == 60U && edge.level == GUMI_BUTTON_LEVEL_RELEASED);
    tests_run += 1U;
}

static void test_single_tap_commits_after_double_window(void)
{
    gumi_button_gesture state = normal_gesture();
    gumi_button_event_batch events;

    CHECK_STATUS(
        gumi_button_gesture_accept_edge(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &events),
        GUMI_BUTTON_STATUS_OK
    );
    expect_empty(&events);
    CHECK_STATUS(
        gumi_button_gesture_accept_edge(&state, 100U, GUMI_BUTTON_LEVEL_RELEASED, &events),
        GUMI_BUTTON_STATUS_OK
    );
    expect_empty(&events);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 449U, &events), GUMI_BUTTON_STATUS_OK);
    expect_empty(&events);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 450U, &events), GUMI_BUTTON_STATUS_OK);
    CHECK(events.count == 1U);
    expect_event(&events, 0U, 450U, GUMI_BUTTON_EVENT_SINGLE_TAP);
    tests_run += 1U;
}

static void test_double_tap_window_is_inclusive(void)
{
    gumi_button_gesture state = normal_gesture();
    gumi_button_event_batch events;

    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 100U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 450U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    expect_empty(&events);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 450U, &events), 0);
    expect_empty(&events);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 550U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    CHECK(events.count == 1U);
    expect_event(&events, 0U, 550U, GUMI_BUTTON_EVENT_DOUBLE_TAP);
    tests_run += 1U;
}

static void test_second_press_after_window_does_not_retroactively_double(void)
{
    gumi_button_gesture state = normal_gesture();
    gumi_button_event_batch events;

    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 100U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 451U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK(events.count == 1U);
    expect_event(&events, 0U, 450U, GUMI_BUTTON_EVENT_SINGLE_TAP);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 551U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    expect_empty(&events);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 901U, &events), 0);
    CHECK(events.count == 1U);
    expect_event(&events, 0U, 901U, GUMI_BUTTON_EVENT_SINGLE_TAP);
    tests_run += 1U;
}

static void test_release_at_exact_hold_deadline_wins(void)
{
    gumi_button_gesture state = normal_gesture();
    gumi_button_event_batch events;

    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 500U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    expect_empty(&events);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 500U, &events), 0);
    expect_empty(&events);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 850U, &events), 0);
    CHECK(events.count == 1U);
    expect_event(&events, 0U, 850U, GUMI_BUTTON_EVENT_SINGLE_TAP);
    tests_run += 1U;
}

static void test_hold_commits_and_releases(void)
{
    gumi_button_gesture state = normal_gesture();
    gumi_button_event_batch events;

    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 499U, &events), 0);
    expect_empty(&events);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 500U, &events), 0);
    CHECK(events.count == 1U);
    expect_event(&events, 0U, 500U, GUMI_BUTTON_EVENT_HOLD_COMMITTED);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 900U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    CHECK(events.count == 1U);
    expect_event(&events, 0U, 900U, GUMI_BUTTON_EVENT_HOLD_RELEASED);
    tests_run += 1U;
}

static void test_tap_then_held_second_press_discards_tap(void)
{
    gumi_button_gesture state = normal_gesture();
    gumi_button_event_batch events;

    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 100U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 300U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 800U, &events), 0);
    CHECK(events.count == 1U);
    expect_event(&events, 0U, 800U, GUMI_BUTTON_EVENT_HOLD_COMMITTED);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 1000U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    CHECK(events.count == 1U);
    expect_event(&events, 0U, 1000U, GUMI_BUTTON_EVENT_HOLD_RELEASED);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 2000U, &events), 0);
    expect_empty(&events);
    tests_run += 1U;
}

static void test_unadvanced_hold_and_release_are_reported_in_order(void)
{
    gumi_button_gesture state = normal_gesture();
    gumi_button_event_batch events;

    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 501U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    CHECK(events.count == 2U);
    expect_event(&events, 0U, 500U, GUMI_BUTTON_EVENT_HOLD_COMMITTED);
    expect_event(&events, 1U, 501U, GUMI_BUTTON_EVENT_HOLD_RELEASED);
    tests_run += 1U;
}

static void test_maintenance_ignores_double_and_hold_but_keeps_single_status_input(void)
{
    gumi_button_gesture state;
    gumi_button_event_batch events;
    const gumi_button_gesture_config config = {
        .context = GUMI_BUTTON_CONTEXT_MAINTENANCE_EXCLUSIVE,
        .confirmation_operation_token = 0U,
        .confirmation_lease_expires_at_ms = 0U,
    };

    CHECK_STATUS(gumi_button_gesture_init(&state, &config), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 80U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 200U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 280U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    expect_empty(&events);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 1000U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 1500U, &events), 0);
    expect_empty(&events);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 1700U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    expect_empty(&events);

    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 2000U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 2080U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 2430U, &events), 0);
    CHECK(events.count == 1U);
    expect_event(&events, 0U, 2430U, GUMI_BUTTON_EVENT_SINGLE_TAP);
    tests_run += 1U;
}

static void test_contextual_confirmation_is_strictly_inside_lease(void)
{
    gumi_button_event_batch events;
    gumi_button_gesture accepted = contextual_gesture(3001U);
    gumi_button_gesture exact_expiry = contextual_gesture(3000U);

    CHECK_STATUS(gumi_button_gesture_accept_edge(&accepted, 1000U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_advance_to(&accepted, 3000U, &events), 0);
    CHECK(events.count == 1U);
    expect_event(&events, 0U, 3000U, GUMI_BUTTON_EVENT_PHYSICAL_CONFIRMATION);
    CHECK(events.events[0].confirmation_operation_token == UINT32_C(0x47554d49));

    CHECK_STATUS(gumi_button_gesture_accept_edge(&exact_expiry, 1000U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_advance_to(&exact_expiry, 3000U, &events), 0);
    expect_empty(&events);
    tests_run += 1U;
}

static void test_contextual_release_at_exact_deadline_wins(void)
{
    gumi_button_gesture state = contextual_gesture(5000U);
    gumi_button_event_batch events;

    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 2000U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    expect_empty(&events);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 2000U, &events), 0);
    expect_empty(&events);
    tests_run += 1U;
}

static void test_invalid_edges_do_not_advance_state(void)
{
    gumi_button_gesture state = normal_gesture();
    gumi_button_gesture before;
    gumi_button_event_batch events;

    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    before = state;
    CHECK_STATUS(
        gumi_button_gesture_accept_edge(&state, 600U, GUMI_BUTTON_LEVEL_PRESSED, &events),
        GUMI_BUTTON_STATUS_EDGE_ORDER
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 500U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 850U, &events), 0);
    expect_event(&events, 0U, 850U, GUMI_BUTTON_EVENT_SINGLE_TAP);
    tests_run += 1U;
}

static void test_timestamp_precedence_is_enforced(void)
{
    gumi_button_gesture state = normal_gesture();
    gumi_button_gesture before;
    gumi_button_event_batch events;

    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 0U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    CHECK_STATUS(gumi_button_gesture_advance_to(&state, 500U, &events), 0);
    before = state;
    CHECK_STATUS(
        gumi_button_gesture_accept_edge(&state, 500U, GUMI_BUTTON_LEVEL_RELEASED, &events),
        GUMI_BUTTON_STATUS_TIMESTAMP_PRECEDENCE
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 501U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);
    expect_event(&events, 0U, 501U, GUMI_BUTTON_EVENT_HOLD_RELEASED);
    tests_run += 1U;
}

static void test_stale_time_and_overflow_fail_transactionally(void)
{
    gumi_button_gesture state = normal_gesture();
    gumi_button_gesture before;
    gumi_button_event_batch events;

    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 100U, GUMI_BUTTON_LEVEL_PRESSED, &events), 0);
    before = state;
    CHECK_STATUS(
        gumi_button_gesture_accept_edge(&state, 99U, GUMI_BUTTON_LEVEL_RELEASED, &events),
        GUMI_BUTTON_STATUS_TIME_REGRESSION
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    CHECK_STATUS(gumi_button_gesture_accept_edge(&state, 600U, GUMI_BUTTON_LEVEL_RELEASED, &events), 0);

    state = normal_gesture();
    before = state;
    CHECK_STATUS(
        gumi_button_gesture_accept_edge(
            &state,
            UINT64_MAX - GUMI_BUTTON_HOLD_MS + UINT64_C(1),
            GUMI_BUTTON_LEVEL_PRESSED,
            &events
        ),
        GUMI_BUTTON_STATUS_TIME_OVERFLOW
    );
    CHECK(memcmp(&state, &before, sizeof(state)) == 0);
    tests_run += 1U;
}

static void test_confirmation_configuration_is_operation_bound(void)
{
    gumi_button_gesture state;
    const gumi_button_gesture_config missing_token = {
        .context = GUMI_BUTTON_CONTEXT_AWAITING_CONFIRMATION,
        .confirmation_operation_token = 0U,
        .confirmation_lease_expires_at_ms = 15000U,
    };
    const gumi_button_gesture_config leaked_token = {
        .context = GUMI_BUTTON_CONTEXT_NORMAL,
        .confirmation_operation_token = 42U,
        .confirmation_lease_expires_at_ms = 0U,
    };

    CHECK_STATUS(
        gumi_button_gesture_init(&state, &missing_token),
        GUMI_BUTTON_STATUS_INVALID_CONFIGURATION
    );
    CHECK_STATUS(
        gumi_button_gesture_init(&state, &leaked_token),
        GUMI_BUTTON_STATUS_INVALID_CONFIGURATION
    );
    tests_run += 1U;
}

int main(void)
{
    test_debounce_collapses_switch_bounce();
    test_debounce_exact_deadline_precedes_new_raw_level();
    test_single_tap_commits_after_double_window();
    test_double_tap_window_is_inclusive();
    test_second_press_after_window_does_not_retroactively_double();
    test_release_at_exact_hold_deadline_wins();
    test_hold_commits_and_releases();
    test_tap_then_held_second_press_discards_tap();
    test_unadvanced_hold_and_release_are_reported_in_order();
    test_maintenance_ignores_double_and_hold_but_keeps_single_status_input();
    test_contextual_confirmation_is_strictly_inside_lease();
    test_contextual_release_at_exact_deadline_wins();
    test_invalid_edges_do_not_advance_state();
    test_timestamp_precedence_is_enforced();
    test_stale_time_and_overflow_fail_transactionally();
    test_confirmation_configuration_is_operation_bound();

    printf("PASS: %u portable button-kernel tests\n", tests_run);
    return EXIT_SUCCESS;
}
