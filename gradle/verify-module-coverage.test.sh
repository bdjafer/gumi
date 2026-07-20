#!/bin/sh
set -eu

test_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
verifier="$test_dir/verify-module-coverage.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/gumi-module-coverage-test.XXXXXX")

cleanup() {
    rm -rf -- "$test_root"
}
trap cleanup EXIT HUP INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

run_verifier() {
    GUMI_MODULE_ROOT="$test_root" sh "$verifier" "$@"
}

expect_failure() {
    expected=$1
    shift
    if run_verifier "$@" > "$test_root/negative.out" 2>&1; then
        fail "module verifier accepted: $expected"
    fi
    grep -F "$expected" "$test_root/negative.out" >/dev/null || {
        cat "$test_root/negative.out" >&2
        fail "module verifier did not report: $expected"
    }
}

mkdir -p "$test_root/devices" "$test_root/cloud" "$test_root/edge/one"
: > "$test_root/edge/one/build.gradle.kts"
run_verifier edge/one
run_verifier edge edge/one

mkdir -p "$test_root/edge/two"
: > "$test_root/edge/two/build.gradle.kts"
expect_failure 'edge/two: Gradle build directory is missing from settings.gradle.kts' edge/one

expect_failure 'edge/missing: configured leaf project has no build.gradle(.kts)' \
    edge/one edge/two edge/missing

: > "$test_root/edge/one/build.gradle"
expect_failure 'Directories with multiple Gradle build files:' edge/one edge/two

echo 'Gradle module coverage verifier probes passed.'
