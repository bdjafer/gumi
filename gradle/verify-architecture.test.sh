#!/bin/sh
set -eu

test_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
verifier="$test_dir/verify-architecture.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/gumi-architecture-test.XXXXXX")

cleanup() {
    rm -rf -- "$test_root"
}
trap cleanup EXIT HUP INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

make_fixture() {
    mkdir -p "$test_root/cloud"
    while IFS= read -r module; do
        [ -n "$module" ] || continue
        mkdir -p "$test_root/$module/src/main"
        : > "$test_root/$module/build.gradle.kts"
    done <<'EOF'
edge/sdk
edge/runtime
edge/adapters/cloud/media-ingest
edge/shell/application
edge/platforms/android
edge/shell/android
edge/shell/linux
devices/omi-cv1/edge-driver
devices/omi-cv1/application-updater/android
devices/omi-cv1/simulator
EOF
}

verify_fixture() {
    (cd "$test_root" && sh "$verifier")
}

expect_failure() {
    expected=$1
    if verify_fixture > "$test_root/negative.out" 2>&1; then
        fail "architecture verifier accepted $expected"
    fi
    grep -F "$expected" "$test_root/negative.out" >/dev/null || {
        cat "$test_root/negative.out" >&2
        fail "architecture verifier did not report: $expected"
    }
}

make_fixture
verify_fixture

printf 'dependencies { implementation(project(":devices:omi-cv1:edge-driver")) }\n' \
    > "$test_root/edge/runtime/build.gradle.kts"
expect_failure 'forbidden project dependency :devices:omi-cv1:edge-driver'

: > "$test_root/edge/runtime/build.gradle.kts"
printf 'dependencies { implementation(project(path = ":edge:sdk")) }\n' \
    > "$test_root/edge/runtime/build.gradle.kts"
expect_failure 'every project dependency must use canonical project(":path") syntax'

: > "$test_root/edge/runtime/build.gradle.kts"
printf 'dependencies { implementation(projects.edge.sdk) }\n' \
    > "$test_root/edge/runtime/build.gradle.kts"
expect_failure 'type-safe project accessors are not accepted by this boundary verifier'

: > "$test_root/edge/runtime/build.gradle.kts"
printf 'dependencies { implementation(project(":edge:platforms:android")) }\n' \
    > "$test_root/devices/omi-cv1/application-updater/android/build.gradle.kts"
expect_failure 'forbidden project dependency :edge:platforms:android'

: > "$test_root/devices/omi-cv1/application-updater/android/build.gradle.kts"
updater_probe="$test_root/devices/omi-cv1/application-updater/android/src/main/DangerousUpdater.kt"
mkdir -p "$(dirname -- "$updater_probe")"
printf 'class DangerousUpdater { val manager: FirmwareUpgradeManager? = null }\n' > "$updater_probe"
expect_failure 'update mutation is allowed only in the reviewed image-0 adapter/executor'
rm -f -- "$updater_probe"

allowed_updater_probe="$test_root/devices/omi-cv1/application-updater/android/src/main/kotlin/dev/gumi/devices/omicv1/updater/android/AndroidOmiCv1ApplicationImage0Session.kt"
mkdir -p "$(dirname -- "$allowed_updater_probe")"
printf 'class AndroidOmiCv1ApplicationImage0Session { val manager: FsManager? = null }\n' \
    > "$allowed_updater_probe"
expect_failure 'broader MCU Manager surfaces are forbidden in the image-0 updater'
rm -f -- "$allowed_updater_probe"

mkdir -p "$test_root/edge/unreviewed/src"
: > "$test_root/edge/unreviewed/build.gradle.kts"
expect_failure 'Gradle module has no architecture dependency policy'

echo 'Architecture verifier negative probes passed.'
