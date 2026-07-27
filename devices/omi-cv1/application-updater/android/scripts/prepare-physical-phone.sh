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
minimum_phone_battery_percent=60
recovery_file_sha256='d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc'
recovery_mcuboot_sha256='065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57'
capture_selftest_file_sha256='8f0d0fc35c4d3c56e8f94d528a0b56c0d4af7b44b7bdad2b2fa4f2963e2e3d0e'
capture_selftest_mcuboot_sha256='e97fd653a66dce18a7dbd071bc9b21c4fdae4e229de5d245e76ed213c2ca4862'
legacy_functional_file_sha256='838c767f0d273767d422da751f4c2bc16bf1b27f35452833f992baf486c1ba45'
legacy_functional_mcuboot_sha256='045918a8cc1ceb4be74dd486e9da7b14123daacbb9b26e0d6404b6617048c820'
provisioner_file_sha256='e53c58a3ef72773af501a9ae05ceb5ce715b6d8153923ef52609dc5ec1dfa38b'
provisioner_mcuboot_sha256='8a8eef711fd72707905bda05e08ddebc0de2a986d3c1d21b7469c92e7d32b12e'
functional_v0003_file_sha256='3fda1c98da2bcd747e435b464feda563415949f6e0615193db25f1b658f3af1e'
functional_v0003_mcuboot_sha256='0cd7ddea779427f25ff2b3341f1242e0e4611f1245b02fde81361c0d2cbcbecd'
functional_v0004_file_sha256='382a04633bf83329fb8ef3ded1ecbfb01ba6b53e8af1b2473e7f1795355ba7d2'
functional_v0004_mcuboot_sha256='1a47d68c09d664fdf4e0d6549ebe8f8e7ccf08d00f7a93f01a54dc21d0a075d3'
functional_v0005_file_sha256='26ec3d961d1342440a53034c27591df04ca1e2de637f463e48063e86b1b26f27'
functional_v0005_mcuboot_sha256='55663e5f90ce3e7b6e9373f8ff6432c215c68668e6759b9111cc815a31c54961'
functional_v0006_file_sha256='eb811b62fffbf5c5f3ca1684b29073f6cf9cb49ae8e38d5201944c6c36d640a0'
functional_v0006_mcuboot_sha256='3a35a3324393e563e36b2748f0b1f842ce5db52834cd0023334d9c9ac3071aa1'
legacy_storage_reclaimer_file_sha256='59fb753783c51140f4ee22c9d18dcaaf40bcb240e69c93a3cb2e967999fb1960'
legacy_storage_reclaimer_mcuboot_sha256='8fc16e21b238dd4907abb6a4512db1002c4acf75cc079f8fa1ea49e467b412a2'
functional_v0007_file_sha256='a0d292117b0f2455fc342a2ad39e2b9ce02054e096689018184065df91933b25'
functional_v0007_mcuboot_sha256='407df7c1f97b480f45d445d4045b5a124af2d431130a3f07b77b07726301d1e0'
stock_v3007_app_mcuboot_sha256='ab6364926c7df7371a013dfbcf1e3f73f9386b8500f5cbe4153c2883b798877e'
stock_v3012_app_file_sha256='877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db'
stock_v3012_app_mcuboot_sha256='0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36'
stock_v3012_network_file_sha256='0e1d067444ca78dd4ea64cb355bc167c1155b84f49b0c7028112c9930602d2a5'
stock_v3012_network_mcuboot_sha256='267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089'

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
[ "$battery_percent" -ge "$minimum_phone_battery_percent" ] || die \
    "qualified phone battery is ${battery_percent}%; charge it to at least ${minimum_phone_battery_percent}%"
require_awake_and_unlocked

echo "Verifying the closed, exact-artifact firmware APK before installing it on the qualified phone."
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
echo "Recovery file SHA-256:   $recovery_file_sha256"
echo "Recovery MCUboot SHA-256: $recovery_mcuboot_sha256"
echo "Self-test file SHA-256:    $capture_selftest_file_sha256"
echo "Self-test MCUboot SHA-256: $capture_selftest_mcuboot_sha256"
echo "Legacy v0001 file SHA-256:  $legacy_functional_file_sha256"
echo "Legacy v0001 MCUboot hash:  $legacy_functional_mcuboot_sha256"
echo "Provisioner file SHA-256:   $provisioner_file_sha256"
echo "Provisioner MCUboot hash:   $provisioner_mcuboot_sha256"
echo "Failed v0003 source file:    $functional_v0003_file_sha256"
echo "Failed v0003 source MCUboot: $functional_v0003_mcuboot_sha256"
echo "Failed v0004 source file:    $functional_v0004_file_sha256"
echo "Failed v0004 source MCUboot: $functional_v0004_mcuboot_sha256"
echo "Failed v0005 source file:    $functional_v0005_file_sha256"
echo "Failed v0005 source MCUboot: $functional_v0005_mcuboot_sha256"
echo "Functional v0006 SHA-256:    $functional_v0006_file_sha256"
echo "Functional v0006 MCUboot:    $functional_v0006_mcuboot_sha256"
echo "OTA-safe reclaimer file:       $legacy_storage_reclaimer_file_sha256"
echo "OTA-safe reclaimer MCUboot:    $legacy_storage_reclaimer_mcuboot_sha256"
echo "Functional v0007 SHA-256:     $functional_v0007_file_sha256"
echo "Functional v0007 MCUboot:     $functional_v0007_mcuboot_sha256"
echo "Stock v3.0.7 app MCUboot:   $stock_v3007_app_mcuboot_sha256"
echo "Stock v3.0.12 app file:     $stock_v3012_app_file_sha256"
echo "Stock v3.0.12 app MCUboot:  $stock_v3012_app_mcuboot_sha256"
echo "Stock v3.0.12 net file:     $stock_v3012_network_file_sha256"
echo "Stock v3.0.12 net MCUboot:  $stock_v3012_network_mcuboot_sha256"
echo
echo "For the current exact functional-v0006 storage-reclaim transition, stop at this authorization gate:"
echo "1. Grant Nearby Devices and complete the physical-preflight attestations."
echo "2. Hold the Omi button continuously for five seconds; release before the 12-second reset."
echo "3. Scan and select the single exact Gumi Omi CV1 advertisement."
echo "4. Choose Provisioner / functional v0006 -> OTA-safe reclaimer v0002."
echo "5. Run the fresh disclosed preflight and leave its review visible."
echo "6. Confirm the source MCUboot hash is functional v0006 above and the target file hash is the exact OTA-safe reclaimer."
echo "7. Do not authorize or upload yet; report that the review is visible."
echo "After proven reclaim success, functional v0007 remains a separate fresh authorization."
echo
echo "For a sealed stock-v3.0.7 Omi, stop after this read-only normalization gate:"
echo "1. Grant Nearby Devices."
echo "2. Complete the three physical-preflight attestations; Omi battery is machine-read warning-only."
echo "3. Scan and select the single exact Omi CV1 advertisement."
echo "4. Choose Official stock v3.0.7 -> v3.0.12 (app + network)."
echo "5. Run the fresh disclosed preflight and leave its review visible."
echo "6. Review the warning-only device battery and confirm the source app MCUboot hash is the exact v3.0.7 hash above."
echo "7. Do not authorize or upload yet; report the displayed app + network source/target review."
