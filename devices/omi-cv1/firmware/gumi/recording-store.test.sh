#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
binary=$(mktemp "${TMPDIR:-/tmp}/gumi-recording-store-test.XXXXXX")
trap 'rm -f "$binary"' EXIT HUP INT TERM

cc -std=c11 -Wall -Wextra -Werror -pedantic \
    -I"$script_dir/include" \
    "$script_dir/src/recording_journal.c" \
    "$script_dir/src/recording_store.c" \
    "$script_dir/tests/recording_store_test.c" \
    -o "$binary"
"$binary"
