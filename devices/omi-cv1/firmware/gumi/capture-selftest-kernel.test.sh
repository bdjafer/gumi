#!/bin/sh
set -eu

firmware_root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
build_directory=$(mktemp -d "${TMPDIR:-/tmp}/gumi-capture-selftest-kernel.XXXXXX")

cleanup() {
    case "$build_directory" in
        "${TMPDIR:-/tmp}"/gumi-capture-selftest-kernel.*) rm -rf -- "$build_directory" ;;
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
    -fsanitize=address,undefined \
    -fno-omit-frame-pointer \
    -I "$firmware_root/include" \
    "$firmware_root/src/capture_selftest.c" \
    "$firmware_root/tests/capture_selftest_test.c" \
    -o "$build_directory/capture-selftest-kernel-test"

"$build_directory/capture-selftest-kernel-test"
