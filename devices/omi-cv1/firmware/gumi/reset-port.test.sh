#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
port_dir="$script_dir/zephyr/omi-v3012/tests/reset-port"
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/gumi-reset-port.XXXXXX")
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM

cc \
    -std=c11 \
    -Wall \
    -Wextra \
    -Werror \
    -I"$port_dir/fakes" \
    -I"$script_dir/zephyr/omi-v3012/include" \
    "$script_dir/zephyr/omi-v3012/src/functional_reset_port.c" \
    "$port_dir/main.c" \
    -o "$build_dir/reset-port-test"

"$build_dir/reset-port-test"
