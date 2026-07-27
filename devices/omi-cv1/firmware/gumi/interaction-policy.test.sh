#!/bin/sh
set -eu

firmware_root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
build_directory=$(mktemp -d "${TMPDIR:-/tmp}/gumi-interaction-policy.XXXXXX")

cleanup() {
    case "$build_directory" in
        "${TMPDIR:-/tmp}"/gumi-interaction-policy.*) rm -rf -- "$build_directory" ;;
        *) echo "Refusing to remove unexpected test directory: $build_directory" >&2 ;;
    esac
}
trap cleanup EXIT HUP INT TERM

compiler=${CC:-cc}
"$compiler" \
    -std=c11 \
    -O2 \
    -Wall \
    -Wextra \
    -Wpedantic \
    -Wconversion \
    -Wshadow \
    -Wstrict-prototypes \
    -Werror \
    -I "$firmware_root/include" \
    "$firmware_root/src/interaction_policy.c" \
    "$firmware_root/tests/interaction_policy_test.c" \
    -o "$build_directory/interaction-policy-test"

"$compiler" \
    -std=c11 \
    -O2 \
    -Wall \
    -Wextra \
    -Wpedantic \
    -Wconversion \
    -Wshadow \
    -Wstrict-prototypes \
    -Werror \
    -I "$firmware_root/include" \
    "$firmware_root/src/semantic_signal.c" \
    "$firmware_root/tests/semantic_signal_test.c" \
    -o "$build_directory/semantic-signal-test"

"$build_directory/interaction-policy-test"
"$build_directory/semantic-signal-test"
