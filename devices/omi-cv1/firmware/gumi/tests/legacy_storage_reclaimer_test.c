#include "gumi/legacy_storage_reclaimer.h"

#include <stdio.h>
#include <stdlib.h>

static unsigned int tests_run;

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #condition); \
        exit(EXIT_FAILURE); \
    } \
} while (0)

static const gumi_legacy_storage_reclaimer_policy policy = {
    .expected_target_size_bytes = UINT64_C(505118720),
    .minimum_free_bytes = UINT64_C(4194304),
};

static void test_exact_regular_file_is_the_only_delete_plan(void)
{
    const gumi_legacy_storage_reclaimer_observation observation = {
        .target_present = true,
        .target_regular_file = true,
        .target_size_bytes = UINT64_C(505118720),
        .free_bytes = UINT64_C(0),
    };
    gumi_legacy_storage_reclaimer_decision decision =
        gumi_legacy_storage_reclaimer_decide(&policy, &observation);

    CHECK(
        decision.action ==
        GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_DELETE_EXACT_TARGET
    );
    CHECK(decision.reason == GUMI_LEGACY_STORAGE_RECLAIMER_REASON_NONE);
    tests_run += 1U;
}

static void test_wrong_size_and_non_file_are_refused(void)
{
    gumi_legacy_storage_reclaimer_observation observation = {
        .target_present = true,
        .target_regular_file = true,
        .target_size_bytes = UINT64_C(505118719),
        .free_bytes = UINT64_C(0),
    };
    gumi_legacy_storage_reclaimer_decision decision =
        gumi_legacy_storage_reclaimer_decide(&policy, &observation);

    CHECK(decision.action == GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_REFUSE);
    CHECK(
        decision.reason ==
        GUMI_LEGACY_STORAGE_RECLAIMER_REASON_TARGET_SIZE_MISMATCH
    );

    observation.target_size_bytes = policy.expected_target_size_bytes;
    observation.target_regular_file = false;
    decision = gumi_legacy_storage_reclaimer_decide(&policy, &observation);
    CHECK(decision.action == GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_REFUSE);
    CHECK(
        decision.reason ==
        GUMI_LEGACY_STORAGE_RECLAIMER_REASON_TARGET_NOT_REGULAR_FILE
    );
    tests_run += 1U;
}

static void test_absent_target_is_safe_only_when_space_is_already_available(void)
{
    gumi_legacy_storage_reclaimer_observation observation = {
        .target_present = false,
        .target_regular_file = false,
        .target_size_bytes = UINT64_C(0),
        .free_bytes = policy.minimum_free_bytes,
    };
    gumi_legacy_storage_reclaimer_decision decision =
        gumi_legacy_storage_reclaimer_decide(&policy, &observation);

    CHECK(decision.action == GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_NONE);
    CHECK(
        decision.reason ==
        GUMI_LEGACY_STORAGE_RECLAIMER_REASON_ALREADY_ABSENT
    );

    observation.free_bytes = policy.minimum_free_bytes - UINT64_C(1);
    decision = gumi_legacy_storage_reclaimer_decide(&policy, &observation);
    CHECK(decision.action == GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_REFUSE);
    CHECK(
        decision.reason ==
        GUMI_LEGACY_STORAGE_RECLAIMER_REASON_INSUFFICIENT_SPACE_WITHOUT_TARGET
    );
    tests_run += 1U;
}

static void test_invalid_policy_never_authorizes_deletion(void)
{
    const gumi_legacy_storage_reclaimer_policy invalid = {0};
    const gumi_legacy_storage_reclaimer_observation observation = {
        .target_present = true,
        .target_regular_file = true,
        .target_size_bytes = UINT64_C(505118720),
        .free_bytes = UINT64_C(0),
    };
    gumi_legacy_storage_reclaimer_decision decision =
        gumi_legacy_storage_reclaimer_decide(&invalid, &observation);

    CHECK(decision.action == GUMI_LEGACY_STORAGE_RECLAIMER_ACTION_REFUSE);
    tests_run += 1U;
}

int main(void)
{
    test_exact_regular_file_is_the_only_delete_plan();
    test_wrong_size_and_non_file_are_refused();
    test_absent_target_is_safe_only_when_space_is_already_available();
    test_invalid_policy_never_authorizes_deletion();
    printf("legacy storage reclaimer tests: %u passed\n", tests_run);
    return EXIT_SUCCESS;
}
