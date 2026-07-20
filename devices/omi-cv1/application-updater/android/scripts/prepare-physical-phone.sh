#!/bin/sh
set -eu
umask 077

if [ -n "${GUMI_FLASH_LAB_REPO_DIR:-}" ]; then
    repo_dir=$GUMI_FLASH_LAB_REPO_DIR
else
    repo_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/../../../../.." && pwd)
fi

adb=${GUMI_FLASH_LAB_ADB:-"$repo_dir/local/toolchains/android-sdk/platform-tools/adb"}
gumiw=${GUMI_FLASH_LAB_GUMIW:-"$repo_dir/gumiw"}
apk="$repo_dir/devices/omi-cv1/application-updater/android/build/outputs/apk/debug/android-debug.apk"
package_name='dev.gumi.omicv1.flashlab'
component="$package_name/dev.gumi.devices.omicv1.updater.android.OmiCv1FlashLabActivity"
qualified_model='motorola edge 60 fusion'
qualified_sdk=36
canary_file_sha256='65e4ae91637e6aa576f6d3f8286db33bc9bd36ece8ece1aaa6b6aa5c5b204f6d'
canary_mcuboot_sha256='d3b3c74c10c4fa110763ae5523d0aeaa20b9f815b8674167486be6ff6f8052ce'

die() {
    echo "error: $*" >&2
    exit 1
}

sha256() {
    shasum -a 256 "$1" | awk '{ print $1 }'
}

read_property() {
    property_name=$1
    property_value=$("$adb" -s "$phone_serial" shell getprop "$property_name" | tr -d '\r\n')
    [ -n "$property_value" ] || die "Android property $property_name is unavailable"
    [ "${#property_value}" -le 128 ] || die "Android property $property_name is unexpectedly long"
    printf '%s\n' "$property_value"
}

require_awake_and_unlocked() {
    power_state=$("$adb" -s "$phone_serial" shell dumpsys power | tr -d '\r')
    printf '%s\n' "$power_state" | grep -E \
        'mWakefulness=Awake|Wakefulness:[[:space:]]*Awake|Display Power: state=ON' >/dev/null ||
        die "qualified phone screen is not awake; unlock it and retry"

    window_policy=$("$adb" -s "$phone_serial" shell dumpsys window policy | tr -d '\r')
    if printf '%s\n' "$window_policy" | grep -E \
        'mShowingLockscreen=true|mKeyguardShowing=true|isStatusBarKeyguard=true|mDreamingLockscreen=true|^[[:space:]]*showing=true' >/dev/null; then
        die "qualified phone is locked; unlock it and retry"
    fi
    if ! printf '%s\n' "$window_policy" | grep -E \
        'mShowingLockscreen=false|mKeyguardShowing=false|isStatusBarKeyguard=false|mDreamingLockscreen=false|^[[:space:]]*showing=false' >/dev/null; then
        trust_state=$("$adb" -s "$phone_serial" shell dumpsys trust | tr -d '\r')
        printf '%s\n' "$trust_state" | grep -E \
            'deviceLocked=0|Device locked:[[:space:]]*false' >/dev/null ||
            die "unable to prove that the qualified phone is unlocked"
    fi
}

[ -x "$adb" ] || die "workspace adb is missing; run the Android bootstrap first"
[ -x "$gumiw" ] || die "Gumi Gradle wrapper is unavailable"

physical_serials=$(
    "$adb" devices -l |
        awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ && $0 ~ /usb:/ { print $1 }'
)
physical_count=$(printf '%s\n' "$physical_serials" | awk 'NF { count++ } END { print count + 0 }')
[ "$physical_count" -eq 1 ] || die \
    "expected exactly one authorized USB Android phone; emulators are ignored"
phone_serial=$physical_serials
printf '%s\n' "$phone_serial" | grep -Eq '^[A-Za-z0-9._:-]+$' ||
    die "Android transport identifier is malformed"

phone_model=$(read_property ro.product.model)
phone_sdk=$(read_property ro.build.version.sdk)
[ "$phone_model" = "$qualified_model" ] || die \
    "this first-flash lab is qualified only for $qualified_model"
[ "$phone_sdk" = "$qualified_sdk" ] || die \
    "this first-flash lab is qualified only for Android API $qualified_sdk"

battery_dump=$("$adb" -s "$phone_serial" shell dumpsys battery | tr -d '\r')
battery_level=$(printf '%s\n' "$battery_dump" | awk '/^[[:space:]]*level:/{ print $2; exit }')
battery_scale=$(printf '%s\n' "$battery_dump" | awk '/^[[:space:]]*scale:/{ print $2; exit }')
case "$battery_level:$battery_scale" in
    *[!0-9:]*) die "qualified phone battery level is unavailable" ;;
    :* | *:) die "qualified phone battery level is unavailable" ;;
esac
[ "$battery_scale" -gt 0 ] || die "qualified phone battery scale is invalid"
battery_percent=$((battery_level * 100 / battery_scale))
[ "$battery_percent" -ge 80 ] || die \
    "qualified phone battery is ${battery_percent}%; charge it to at least 80%"
require_awake_and_unlocked

echo "Verifying the closed application-image-0 APK before installing it on the qualified phone."
"$gumiw" verifyArchitecture :devices:omi-cv1:application-updater:android:check
[ -s "$apk" ] || die "verified flash-lab APK is missing"
local_apk_sha256=$(sha256 "$apk")

echo "Phone-side effect: install or replace $package_name, preserving its app data."
echo "This step does not connect to the Omi and cannot write firmware."
"$adb" -s "$phone_serial" install -r "$apk"

installed_apk_path=$(
    "$adb" -s "$phone_serial" shell pm path "$package_name" | tr -d '\r' |
        sed -n 's/^package:\(.*\/base\.apk\)$/\1/p' | head -n 1
)
[ -n "$installed_apk_path" ] || die "installed flash-lab base APK path is unavailable"
installed_apk_sha256=$(
    "$adb" -s "$phone_serial" exec-out cat "$installed_apk_path" |
        shasum -a 256 | awk '{ print $1 }'
)
[ "$installed_apk_sha256" = "$local_apk_sha256" ] || die \
    "installed flash-lab APK does not match the verified local APK"

"$adb" -s "$phone_serial" shell am force-stop "$package_name"
"$adb" -s "$phone_serial" shell am start -W -n "$component"
activity_state=$("$adb" -s "$phone_serial" shell dumpsys activity activities | tr -d '\r')
resumed_activity=$(
    printf '%s\n' "$activity_state" |
        grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' || true
)
printf '%s\n' "$resumed_activity" | grep -F "$component" >/dev/null ||
    die "flash lab did not become the foreground activity"

echo
echo "READY: qualified phone prepared; zero Omi connections and zero firmware bytes written."
echo "Phone: $phone_model · API $phone_sdk · battery ${battery_percent}%"
echo "Installed APK SHA-256: $installed_apk_sha256"
echo "Canary file SHA-256:   $canary_file_sha256"
echo "Canary MCUboot SHA-256: $canary_mcuboot_sha256"
echo
echo "On the phone, stop after the following read-only gate:"
echo "1. Grant Nearby Devices."
echo "2. Truthfully complete the four physical-preflight attestations."
echo "3. Scan and select the single exact Omi CV1 advertisement."
echo "4. Run the fresh disclosed preflight and leave its review visible."
echo "5. Do not authorize or upload yet; report the displayed source/target review."
