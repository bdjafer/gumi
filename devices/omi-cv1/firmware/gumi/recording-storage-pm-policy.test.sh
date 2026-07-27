#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/gumi-storage-pm-policy.XXXXXX")
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM

${CC:-cc} \
    -std=c11 \
    -Wall \
    -Wextra \
    -Werror \
    -pedantic \
    -I"$script_dir/zephyr/omi-v3012/src" \
    "$script_dir/zephyr/omi-v3012/src/recording_storage_pm_policy.c" \
    "$script_dir/tests/recording_storage_pm_policy_test.c" \
    -o "$build_dir/recording-storage-pm-policy-test"

"$build_dir/recording-storage-pm-policy-test"
