#!/bin/sh
set -eu

firmware_root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
build_directory=$(mktemp -d "${TMPDIR:-/tmp}/gumi-button-kernel.XXXXXX")

cleanup() {
    case "$build_directory" in
        "${TMPDIR:-/tmp}"/gumi-button-kernel.*) rm -rf -- "$build_directory" ;;
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
    "$firmware_root/src/button.c" \
    "$firmware_root/tests/button_test.c" \
    -o "$build_directory/button-kernel-test"

"$build_directory/button-kernel-test"
node --test "$firmware_root/tests/contract-sync.test.mjs"
