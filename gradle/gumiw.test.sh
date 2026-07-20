#!/bin/sh
set -eu

if [ "${0##*/}" = gradlew ]; then
    printf 'JAVA_HOME=%s\n' "${JAVA_HOME:-}"
    printf 'ANDROID_SDK_ROOT=%s\n' "${ANDROID_SDK_ROOT:-}"
    printf 'GRADLE_USER_HOME=%s\n' "${GRADLE_USER_HOME:-}"
    printf 'ANDROID_USER_HOME=%s\n' "${ANDROID_USER_HOME:-}"
    printf 'ARGS=%s\n' "$*"
    exit 0
fi

test_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH='' cd -- "$test_dir/.." && pwd)
test_root=$(mktemp -d "${TMPDIR:-/tmp}/gumi-launcher-test.XXXXXX")
test_root=$(CDPATH='' cd -- "$test_root" && pwd)

cleanup() {
    rm -rf -- "$test_root"
}
trap cleanup EXIT HUP INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

make_case() {
    case_name=$1
    case_root="$test_root/$case_name"
    mkdir -p "$case_root"
    cp "$repository_root/gumiw" "$case_root/gumiw"
    cp "$test_dir/${0##*/}" "$case_root/gradlew"
    chmod +x "$case_root/gumiw" "$case_root/gradlew"
    printf '%s\n' "$case_root"
}

external_case=$(make_case external)
external_jdk="$test_root/external-jdk"
external_sdk="$test_root/external-sdk"
external_gradle="$test_root/external-gradle"
external_android_user="$test_root/external-android-user"
mkdir -p "$external_jdk/bin" "$external_sdk" "$external_gradle" "$external_android_user"
ln -s /bin/sh "$external_jdk/bin/java"

JAVA_HOME="$external_jdk" \
ANDROID_SDK_ROOT="$external_sdk" \
GRADLE_USER_HOME="$external_gradle" \
ANDROID_USER_HOME="$external_android_user" \
    "$external_case/gumiw" verifyWorkspace > "$test_root/external.out"
grep -F "JAVA_HOME=$external_jdk" "$test_root/external.out" >/dev/null ||
    fail 'explicit JAVA_HOME was not preserved'
grep -F "ANDROID_SDK_ROOT=$external_sdk" "$test_root/external.out" >/dev/null ||
    fail 'explicit ANDROID_SDK_ROOT was not preserved'
grep -F "GRADLE_USER_HOME=$external_gradle" "$test_root/external.out" >/dev/null ||
    fail 'explicit GRADLE_USER_HOME was not preserved'
grep -F 'ARGS=verifyWorkspace' "$test_root/external.out" >/dev/null ||
    fail 'launcher arguments were not forwarded'

workspace_case=$(make_case workspace)
workspace_jdk="$workspace_case/local/toolchains/jdk-17.0.19+10/Contents/Home"
workspace_sdk="$workspace_case/local/toolchains/android-sdk"
mkdir -p "$workspace_jdk/bin" "$workspace_sdk"
ln -s /bin/sh "$workspace_jdk/bin/java"
JAVA_HOME="$external_jdk" ANDROID_SDK_ROOT="$external_sdk" \
    "$workspace_case/gumiw" projects > "$test_root/workspace.out"
grep -F "JAVA_HOME=$workspace_jdk" "$test_root/workspace.out" >/dev/null ||
    fail 'pinned workspace JDK did not take precedence'
grep -F "ANDROID_SDK_ROOT=$workspace_sdk" "$test_root/workspace.out" >/dev/null ||
    fail 'pinned workspace Android SDK did not take precedence'

android_home_case=$(make_case android-home)
ANDROID_HOME="$external_sdk" JAVA_HOME="$external_jdk" \
    env -u ANDROID_SDK_ROOT "$android_home_case/gumiw" tasks > "$test_root/android-home.out"
grep -F "ANDROID_SDK_ROOT=$external_sdk" "$test_root/android-home.out" >/dev/null ||
    fail 'ANDROID_HOME fallback was not normalized to ANDROID_SDK_ROOT'

missing_case=$(make_case missing)
if JAVA_HOME="$external_jdk" env -u ANDROID_HOME -u ANDROID_SDK_ROOT \
    "$missing_case/gumiw" tasks > "$test_root/missing.out" 2> "$test_root/missing.err"; then
    fail 'launcher accepted a missing Android SDK'
fi
grep -F 'No usable Android SDK found' "$test_root/missing.err" >/dev/null ||
    fail 'missing Android SDK failure was not actionable'

echo 'Gumi launcher portability probes passed.'
