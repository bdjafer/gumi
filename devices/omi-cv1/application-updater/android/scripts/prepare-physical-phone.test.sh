#!/bin/sh
set -eu
umask 077

mode=${0##*/}

if [ "$mode" = fake-adb ]; then
    printf '%s\n' "$*" >> "${FAKE_ADB_LOG:?}"
    if [ "${1:-}" = -s ]; then
        [ "${2:-}" = TESTPHONE ] || exit 64
        shift 2
    fi
    command_name=${1:-}
    shift || true
    case "$command_name" in
        devices)
            printf 'List of devices attached\n'
            case "${FAKE_DEVICE_MODE:-phone}" in
                phone) printf 'emulator-5554\tdevice product:sdk model:sdk transport_id:1\nTESTPHONE\tdevice usb:1-1 product:scout model:motorola_edge_60_fusion transport_id:2\n' ;;
                emulator) printf 'emulator-5554\tdevice product:sdk model:sdk transport_id:1\n' ;;
                two-phones) printf 'TESTPHONE\tdevice usb:1-1 product:scout model:one\nOTHERPHONE\tdevice usb:1-2 product:scout model:two\n' ;;
                *) exit 64 ;;
            esac
            ;;
        install)
            printf 'Success\n'
            ;;
        shell)
            case "${1:-}" in
                getprop)
                    case "${2:-}" in
                        ro.product.model) printf '%s\n' "${FAKE_PHONE_MODEL:-motorola edge 60 fusion}" ;;
                        ro.build.version.sdk) printf '36\n' ;;
                        *) exit 64 ;;
                    esac
                    ;;
                dumpsys)
                    case "${2:-}:${3:-}" in
                        battery:) printf '  level: %s\n  scale: 100\n' "${FAKE_BATTERY_LEVEL:-90}" ;;
                        power:) printf 'mWakefulness=Awake\n' ;;
                        window:policy) printf 'mShowingLockscreen=false\n' ;;
                        trust:) printf 'deviceLocked=0\n' ;;
                        activity:activities)
                            printf 'mResumedActivity: ActivityRecord{1 u0 dev.gumi.omicv1.flashlab/dev.gumi.devices.omicv1.updater.android.OmiCv1FlashLabActivity}\n'
                            ;;
                        *) exit 64 ;;
                    esac
                    ;;
                pm)
                    [ "${2:-}" = path ] || exit 64
                    printf 'package:/data/app/dev.gumi.omicv1.flashlab/base.apk\n'
                    ;;
                am) printf 'Status: ok\n' ;;
                *) exit 64 ;;
            esac
            ;;
        exec-out)
            [ "${1:-}:${2:-}" = 'cat:/data/app/dev.gumi.omicv1.flashlab/base.apk' ] || exit 64
            if [ "${FAKE_INSTALLED_HASH_MISMATCH:-0}" = 1 ]; then
                printf 'wrong-apk\n'
            else
                printf 'fake-apk\n'
            fi
            ;;
        *) exit 64 ;;
    esac
    exit 0
fi

if [ "$mode" = fake-gumiw ]; then
    mkdir -p "${FAKE_REPO_DIR:?}/devices/omi-cv1/application-updater/android/build/outputs/apk/debug"
    printf 'fake-apk\n' > "${FAKE_REPO_DIR}/devices/omi-cv1/application-updater/android/build/outputs/apk/debug/android-debug.apk"
    exit 0
fi

test_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
self_path="$test_dir/${0##*/}"
prepare_script="$test_dir/prepare-physical-phone.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/gumi-flash-lab-phone-test.XXXXXX")

cleanup() {
    rm -rf -- "$test_root"
}
trap cleanup EXIT HUP INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

run_prepare() {
    env \
        GUMI_FLASH_LAB_REPO_DIR="$test_root" \
        GUMI_FLASH_LAB_ADB="$test_root/fake-adb" \
        GUMI_FLASH_LAB_GUMIW="$test_root/fake-gumiw" \
        FAKE_REPO_DIR="$test_root" \
        FAKE_ADB_LOG="$test_root/adb.log" \
        "$@" \
        "$prepare_script"
}

ln -s "$self_path" "$test_root/fake-adb"
ln -s "$self_path" "$test_root/fake-gumiw"
chmod +x "$test_root/fake-adb" "$test_root/fake-gumiw"

run_prepare > "$test_root/success.out"
grep -q '^READY: qualified phone prepared; zero Omi connections and zero firmware bytes written\.$' "$test_root/success.out" ||
    fail 'success handoff does not state the zero-write boundary'
grep -q '^Canary file SHA-256:   65e4ae91637e6aa576f6d3f8286db33bc9bd36ece8ece1aaa6b6aa5c5b204f6d$' "$test_root/success.out" ||
    fail 'success handoff does not pin the canary file hash'
grep -q '^5\. Do not authorize or upload yet;' "$test_root/success.out" ||
    fail 'success handoff does not stop before owner authorization'
grep -q -- '-s TESTPHONE install -r ' "$test_root/adb.log" ||
    fail 'qualified APK was not installed on the physical phone transport'
grep -q -- '-s TESTPHONE shell am start -W -n dev.gumi.omicv1.flashlab/' "$test_root/adb.log" ||
    fail 'flash lab was not launched'
if grep -Ei 'mcumgr|imageUpload|image upload|image confirm|image reset' "$test_root/adb.log" >/dev/null; then
    fail 'phone preparation attempted a firmware operation'
fi

if run_prepare FAKE_DEVICE_MODE=emulator > "$test_root/emulator.out" 2> "$test_root/emulator.err"; then
    fail 'emulator-only preparation unexpectedly succeeded'
fi
grep -q 'exactly one authorized USB Android phone' "$test_root/emulator.err" ||
    fail 'emulator-only rejection is unclear'

if run_prepare FAKE_DEVICE_MODE=two-phones > "$test_root/two.out" 2> "$test_root/two.err"; then
    fail 'ambiguous two-phone preparation unexpectedly succeeded'
fi
grep -q 'exactly one authorized USB Android phone' "$test_root/two.err" ||
    fail 'two-phone rejection is unclear'

if run_prepare FAKE_BATTERY_LEVEL=79 > "$test_root/battery.out" 2> "$test_root/battery.err"; then
    fail 'low-battery preparation unexpectedly succeeded'
fi
grep -q 'charge it to at least 80%' "$test_root/battery.err" ||
    fail 'low-battery rejection is unclear'

if run_prepare FAKE_PHONE_MODEL='different phone' > "$test_root/model.out" 2> "$test_root/model.err"; then
    fail 'unqualified-phone preparation unexpectedly succeeded'
fi
grep -q 'qualified only for motorola edge 60 fusion' "$test_root/model.err" ||
    fail 'unqualified-phone rejection is unclear'

: > "$test_root/adb.log"
if run_prepare FAKE_INSTALLED_HASH_MISMATCH=1 > "$test_root/hash.out" 2> "$test_root/hash.err"; then
    fail 'installed APK hash mismatch unexpectedly succeeded'
fi
grep -q 'installed flash-lab APK does not match' "$test_root/hash.err" ||
    fail 'installed APK mismatch rejection is unclear'
if grep -q 'shell am start' "$test_root/adb.log"; then
    fail 'APK hash mismatch still launched the flash lab'
fi

echo 'PASS: physical-phone preparation stays APK-only and stops before owner authorization'
