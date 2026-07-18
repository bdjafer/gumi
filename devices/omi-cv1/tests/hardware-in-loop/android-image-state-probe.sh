#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../../../.." && pwd)
adb="$repo_dir/local/toolchains/android-sdk/platform-tools/adb"
apk="$repo_dir/edge/shell/android/build/outputs/apk/debug/android-debug.apk"
component='dev.gumi.shell/dev.gumi.edge.shell.android.MainActivity'
log_tag='GumiFirmwareProbe:I'

die() {
    echo "error: $*" >&2
    exit 1
}

require_one_phone() {
    [ -x "$adb" ] || die "workspace adb is missing; follow docs/development/bootstrap.md"
    device_count=$(
        "$adb" devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }'
    )
    [ "$device_count" -eq 1 ] || die \
        "expected exactly one authorized Android phone; reconnect, unlock, and accept USB debugging"
}

sha256() {
    shasum -a 256 "$1" | awk '{ print $1 }'
}

prepare() {
    require_one_phone
    "$repo_dir/gumiw" \
        verifyArchitecture \
        :devices:omi-cv1:edge-driver:allTests \
        :edge:platforms:android:testDebugUnitTest \
        :edge:shell:android:lintDebug \
        :edge:shell:android:assembleDebug
    [ -f "$apk" ] || die "debug APK was not produced"

    apk_hash=$(sha256 "$apk")
    echo "Built Gumi diagnostic APK: sha256=$apk_hash"
    "$adb" install -r "$apk"
    "$adb" shell am force-stop dev.gumi.shell
    "$adb" shell am start -W -n "$component"

    echo
    echo "The app is ready. It will not run MCU Manager automatically."
    echo "1. Keep the phone unlocked and hold the Omi close to it."
    echo "2. On the Omi card, tap: Review MCU image-state read."
    echo "3. Read the disclosed transient operations."
    echo "4. Tap: Run disclosed image-state read."
    echo "5. When the slot list and v3.0.12 oracle appear, run:"
    echo "   devices/omi-cv1/tests/hardware-in-loop/android-image-state-probe.sh capture"
}

capture() {
    require_one_phone
    process_id=$("$adb" shell pidof -s dev.gumi.shell | tr -d '\r')
    [ -n "$process_id" ] || die "Gumi is not running; use the prepare mode first"

    timestamp=$(date -u +%Y%m%dT%H%M%SZ)
    evidence_dir="$repo_dir/local/hardware-in-loop/omi-cv1/$timestamp"
    mkdir -p "$evidence_dir"

    "$adb" logcat -d -v threadtime --pid="$process_id" -s "$log_tag" '*:S' \
        > "$evidence_dir/image-state.log"
    "$adb" exec-out screencap -p > "$evidence_dir/image-state.png"

    if ! rg -q 'MCU image-state semantic read complete' "$evidence_dir/image-state.log"; then
        die "no completed image-state read is present in the process-local log; leave Gumi open and retry capture"
    fi

    log_hash=$(sha256 "$evidence_dir/image-state.log")
    screenshot_hash=$(sha256 "$evidence_dir/image-state.png")
    echo "Captured redacted process-local evidence under $evidence_dir"
    echo "image-state.log sha256=$log_hash"
    echo "image-state.png sha256=$screenshot_hash"
    echo "No Bluetooth address, audio, owner identity, or full-device logcat was collected."
}

case "${1:-}" in
    prepare)
        prepare
        ;;
    capture)
        capture
        ;;
    *)
        echo "usage: $0 {prepare|capture}" >&2
        exit 2
        ;;
esac
