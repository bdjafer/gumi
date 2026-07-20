#!/bin/sh
set -eu
umask 077

mode=${0##*/}

if [ "$mode" = fake-adb ]; then
    command_name=${1:-}
    shift || true
    case "$command_name" in
        devices)
            printf 'List of devices attached\nTESTPHONE\tdevice\n'
            ;;
        install)
            printf 'Success\n'
            ;;
        shell)
            case "${1:-}" in
                getprop)
                    case "${2:-}" in
                        ro.product.manufacturer) printf 'Gumi Test Vendor\n' ;;
                        ro.product.model) printf 'HIL Test Phone\n' ;;
                        ro.build.version.sdk) printf '35\n' ;;
                        ro.build.version.release) printf '15\n' ;;
                        ro.build.version.security_patch) printf '2026-07-01\n' ;;
                        *) exit 64 ;;
                    esac
                    ;;
                dumpsys)
                    case "${2:-}" in
                        battery) printf '  level: 80\n  scale: 100\n' ;;
                        power) printf 'mWakefulness=Awake\n' ;;
                        window) printf 'mShowingLockscreen=false\n' ;;
                        trust) printf 'deviceLocked=0\n' ;;
                        activity)
                            if [ "${FAKE_GUMI_FOREGROUND:-1}" = 1 ]; then
                                printf 'mResumedActivity: ActivityRecord{1 u0 dev.gumi.shell/dev.gumi.edge.shell.android.MainActivity}\n'
                            else
                                printf 'mResumedActivity: ActivityRecord{1 u0 example.other/.MainActivity}\n'
                            fi
                            ;;
                        *) exit 64 ;;
                    esac
                    ;;
                pidof) printf '123\n' ;;
                pm) printf 'package:/data/app/dev.gumi.shell/base.apk\n' ;;
                am) printf 'Status: ok\n' ;;
                settings)
                    [ "${2:-}:${3:-}:${4:-}" = 'get:global:zen_mode' ] || exit 64
                    printf '%s\n' "${FAKE_DND_MODE:-1}"
                    ;;
                *) exit 64 ;;
            esac
            ;;
        exec-out)
            case "${1:-}:${2:-}" in
                cat:/proc/123/stat)
                    printf '123 (dev.gumi.shell) S 1 1 1 0 -1 4194560 1 0 0 0 1 1 0 0 20 0 1 0 4242 0\n'
                    ;;
                cat:/data/app/dev.gumi.shell/base.apk) printf 'fake-apk\n' ;;
                screencap:-p)
                    if [ "${FAKE_GUMI_BAD_SCREENSHOT:-0}" != 1 ]; then
                        printf '\211PNG\r\n\032\nfake-screen'
                    fi
                    ;;
                *) exit 64 ;;
            esac
            ;;
        logcat)
            printf '07-19 08:00:00.000 123 123 I GumiDriverProbe: Operational driver negotiation attempt started: attempt=17\n'
            printf '07-19 08:00:01.000 123 123 I GumiDriverProbe: Operational driver negotiation complete: attempt=17, driver=test\n'
            ;;
        *) exit 64 ;;
    esac
    exit 0
fi

if [ "$mode" = gumiw ]; then
    fake_repo=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
    fake_apk="$fake_repo/edge/shell/android/build/outputs/apk/debug/android-debug.apk"
    mkdir -p "$(dirname -- "$fake_apk")"
    printf 'fake-apk\n' > "$fake_apk"
    exit 0
fi

test_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
self_path="$test_dir/${0##*/}"
probe_script="$test_dir/android-image-state-probe.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/gumi-hil-script-test.XXXXXX")

cleanup() {
    rm -rf -- "$test_root"
}
trap cleanup EXIT HUP INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

run_probe() {
    env \
        GUMI_HIL_TEST_MODE=1 \
        GUMI_HIL_REPO_DIR="$test_root" \
        GUMI_HIL_ADB="$test_root/fake-adb" \
        GUMI_HIL_FIXED_UTC=20260719T080000Z \
        GUMI_HIL_FIXED_RUN_ID=0123456789abcdef0123456789abcdef \
        "$@" \
        "$probe_script" "${probe_mode:?probe_mode is required}"
}

mkdir -p "$test_root/gradle/wrapper"
ln -s "$self_path" "$test_root/fake-adb"
ln -s "$self_path" "$test_root/gumiw"
printf 'rootProject.name = "hil-script-test"\n' > "$test_root/settings.gradle.kts"
printf 'plugins {}\n' > "$test_root/build.gradle.kts"
printf '#!/bin/sh\nexit 0\n' > "$test_root/gradlew"
printf 'build/\nlocal/\n' > "$test_root/.gitignore"
chmod +x "$test_root/fake-adb" "$test_root/gumiw" "$test_root/gradlew"

git -C "$test_root" init -q
git -C "$test_root" config user.name 'Gumi HIL Test'
git -C "$test_root" config user.email 'hil-test@example.invalid'
git -C "$test_root" add .
git -C "$test_root" commit -qm 'fixture'

probe_mode=prepare
run_probe > "$test_root/prepare.out"

apk="$test_root/edge/shell/android/build/outputs/apk/debug/android-debug.apk"
[ -s "$apk.build-inputs.sha256" ] || fail 'prepare did not publish build inputs'
[ -s "$apk.hil-prepare.manifest" ] || fail 'prepare did not publish run state'
grep -q '^hil_run_id=0123456789abcdef0123456789abcdef$' "$apk.hil-prepare.manifest" ||
    fail 'run correlation is missing from prepared state'
grep -q '^prepared_repo_head_base=[0-9a-f][0-9a-f]*$' "$apk.hil-prepare.manifest" ||
    fail 'preparation-time HEAD is missing'
grep -q '^android_model=HIL Test Phone$' "$apk.hil-prepare.manifest" ||
    fail 'allowlisted host facts are missing'
for required_handoff in \
    'Capture single-candidate baseline' \
    'unchanged fresh-scan control' \
    'disappearance/return leg' \
    'ordinary external power cycle' \
    'Bluetooth off/on leg' \
    'Never record an address or endpoint'
do
    grep -q "$required_handoff" "$test_root/prepare.out" ||
        fail "prepare handoff is missing: $required_handoff"
done
baseline_line=$(grep -n 'Capture single-candidate baseline' "$test_root/prepare.out" | cut -d: -f1)
connection_line=$(grep -n 'Connect + negotiate driver' "$test_root/prepare.out" | cut -d: -f1)
[ "$baseline_line" -lt "$connection_line" ] || fail 'prepare handoff does not put address baseline first'

evidence_root="$test_root/local/hardware-in-loop/omi-cv1"
probe_mode=capture-driver
if run_probe FAKE_GUMI_BAD_SCREENSHOT=1 > "$test_root/bad-screen.out" 2> "$test_root/bad-screen.err"; then
    fail 'empty screenshot capture unexpectedly succeeded'
fi
[ "$(find "$evidence_root" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l | tr -d ' ')" -eq 0 ] ||
    fail 'failed capture left a directory behind'

if run_probe FAKE_GUMI_FOREGROUND=0 > "$test_root/background.out" 2> "$test_root/background.err"; then
    fail 'background Gumi capture unexpectedly succeeded'
fi
[ "$(find "$evidence_root" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l | tr -d ' ')" -eq 0 ] ||
    fail 'foreground rejection left a directory behind'

if run_probe FAKE_DND_MODE=0 > "$test_root/dnd.out" 2> "$test_root/dnd.err"; then
    fail 'capture with Do Not Disturb off unexpectedly succeeded'
fi
grep -q 'Do Not Disturb is off' "$test_root/dnd.err" ||
    fail 'Do Not Disturb rejection did not explain the privacy gate'
[ "$(find "$evidence_root" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l | tr -d ' ')" -eq 0 ] ||
    fail 'Do Not Disturb rejection left a directory behind'

run_probe > "$test_root/capture-one.out"
run_probe > "$test_root/capture-two.out"

published_count=$(
    find "$evidence_root" -mindepth 1 -maxdepth 1 -type d ! -name '.capture-*' |
        wc -l | tr -d ' '
)
[ "$published_count" -eq 2 ] || fail 'collision-safe repeated captures did not publish two directories'
[ -z "$(find "$evidence_root" -mindepth 1 -maxdepth 1 -type d -name '.capture-*' -print)" ] ||
    fail 'successful capture left a temporary directory behind'

first_manifest=$(find "$evidence_root" -name driver-negotiation.manifest | LC_ALL=C sort | head -n 1)
[ -s "$first_manifest" ] || fail 'published manifest is empty'
published_dir=$(dirname -- "$first_manifest")
[ -s "$published_dir/driver-negotiation.log" ] || fail 'published log is empty'
[ -s "$published_dir/driver-negotiation.png" ] || fail 'published screenshot is empty'
[ -s "$published_dir/android-build-inputs.sha256" ] || fail 'published build inputs are empty'
[ -s "$published_dir/android-prepare.manifest" ] || fail 'published prepared state is empty'
grep -q '^app_process_matches_prepared_run=true$' "$first_manifest" ||
    fail 'process/run binding is missing'
grep -q '^gumi_foreground_verified_before_and_after=true$' "$first_manifest" ||
    fail 'foreground binding is missing'
grep -q '^do_not_disturb_verified_before_and_after=true$' "$first_manifest" ||
    fail 'Do Not Disturb binding is missing'
grep -q '^android_serial_collected=false$' "$first_manifest" ||
    fail 'stable-identifier exclusion is missing'

echo 'PASS: Android HIL script preparation and transactional capture'
