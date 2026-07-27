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
grep -q '^Recovery file SHA-256:   d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc$' "$test_root/success.out" ||
    fail 'success handoff does not pin the recovery-only file hash'
grep -q '^Self-test file SHA-256:    8f0d0fc35c4d3c56e8f94d528a0b56c0d4af7b44b7bdad2b2fa4f2963e2e3d0e$' "$test_root/success.out" ||
    fail 'success handoff does not pin the capture self-test file hash'
grep -q '^Legacy v0001 file SHA-256:  838c767f0d273767d422da751f4c2bc16bf1b27f35452833f992baf486c1ba45$' "$test_root/success.out" ||
    fail 'success handoff does not pin the installed legacy functional image'
grep -q '^Provisioner file SHA-256:   e53c58a3ef72773af501a9ae05ceb5ce715b6d8153923ef52609dc5ec1dfa38b$' "$test_root/success.out" ||
    fail 'success handoff does not pin the hardened recording-root provisioner'
grep -q '^Failed v0003 source MCUboot: 0cd7ddea779427f25ff2b3341f1242e0e4611f1245b02fde81361c0d2cbcbecd$' "$test_root/success.out" ||
    fail 'success handoff does not pin the exact failed functional source'
grep -q '^Failed v0004 source MCUboot: 1a47d68c09d664fdf4e0d6549ebe8f8e7ccf08d00f7a93f01a54dc21d0a075d3$' "$test_root/success.out" ||
    fail 'success handoff does not pin the exact failed v0004 source'
grep -q '^Failed v0005 source MCUboot: 55663e5f90ce3e7b6e9373f8ff6432c215c68668e6759b9111cc815a31c54961$' "$test_root/success.out" ||
    fail 'success handoff does not pin the exact lockup-prone v0005 source'
grep -q '^Functional v0006 SHA-256:    eb811b62fffbf5c5f3ca1684b29073f6cf9cb49ae8e38d5201944c6c36d640a0$' "$test_root/success.out" ||
    fail 'success handoff does not pin the dual-core-reset-repaired functional target'
grep -q '^OTA-safe reclaimer file:       59fb753783c51140f4ee22c9d18dcaaf40bcb240e69c93a3cb2e967999fb1960$' "$test_root/success.out" ||
    fail 'success handoff does not pin the exact OTA-safe legacy-storage reclaimer'
grep -q '^OTA-safe reclaimer MCUboot:    8fc16e21b238dd4907abb6a4512db1002c4acf75cc079f8fa1ea49e467b412a2$' "$test_root/success.out" ||
    fail 'success handoff does not pin the exact OTA-safe reclaimer MCUboot hash'
grep -q '^Functional v0007 SHA-256:     a0d292117b0f2455fc342a2ad39e2b9ce02054e096689018184065df91933b25$' "$test_root/success.out" ||
    fail 'success handoff does not pin the post-reclaim functional target'
grep -q '^Functional v0007 MCUboot:     407df7c1f97b480f45d445d4045b5a124af2d431130a3f07b77b07726301d1e0$' "$test_root/success.out" ||
    fail 'success handoff does not pin the post-reclaim functional MCUboot hash'
grep -q '^Stock v3\.0\.7 app MCUboot:   ab6364926c7df7371a013dfbcf1e3f73f9386b8500f5cbe4153c2883b798877e$' "$test_root/success.out" ||
    fail 'success handoff does not pin the exact stock-v3.0.7 source app'
grep -q '^Stock v3\.0\.12 app file:     877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db$' "$test_root/success.out" ||
    fail 'success handoff does not pin the official stock-v3.0.12 app file'
grep -q '^Stock v3\.0\.12 net file:     0e1d067444ca78dd4ea64cb355bc167c1155b84f49b0c7028112c9930602d2a5$' "$test_root/success.out" ||
    fail 'success handoff does not pin the official stock-v3.0.12 network file'
grep -q '^Stock v3\.0\.12 net MCUboot:  267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089$' "$test_root/success.out" ||
    fail 'success handoff does not pin the official stock-v3.0.12 network target'
grep -q '^4\. Choose Official stock v3\.0\.7 -> v3\.0\.12 (app + network)\.$' "$test_root/success.out" ||
    fail 'success handoff does not select the sealed-stock dual-core normalization'
grep -q '^6\. Review the warning-only device battery' "$test_root/success.out" ||
    fail 'success handoff does not disclose the warning-only Omi battery reading'
grep -q '^7\. Do not authorize or upload yet;' "$test_root/success.out" ||
    fail 'success handoff does not stop before owner authorization'
grep -q '^4\. Choose Provisioner / functional v0006 -> OTA-safe reclaimer v0002\.$' "$test_root/success.out" ||
    fail 'success handoff does not select the OTA-safe legacy-storage reclaimer'
grep -q '^6\. Confirm the source MCUboot hash is functional v0006 above and the target file hash is the exact OTA-safe reclaimer\.$' "$test_root/success.out" ||
    fail 'success handoff does not pin the current reclaimer transition'
grep -q '^After proven reclaim success, functional v0007 remains a separate fresh authorization\.$' "$test_root/success.out" ||
    fail 'success handoff does not preserve the independent functional-v0007 authorization gate'
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

if run_prepare FAKE_BATTERY_LEVEL=59 > "$test_root/battery.out" 2> "$test_root/battery.err"; then
    fail 'low-battery preparation unexpectedly succeeded'
fi
grep -q 'charge it to at least 60%' "$test_root/battery.err" ||
    fail 'low-battery rejection is unclear'

run_prepare FAKE_BATTERY_LEVEL=60 > "$test_root/battery-floor.out"
grep -q '^Phone: motorola edge 60 fusion · API 36 · battery 60%$' "$test_root/battery-floor.out" ||
    fail 'exact qualified phone battery floor did not pass'

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
