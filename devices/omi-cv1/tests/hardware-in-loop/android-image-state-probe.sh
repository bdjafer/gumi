#!/bin/sh
set -eu
umask 077

if [ "${GUMI_HIL_TEST_MODE:-0}" = 1 ]; then
    repo_dir=${GUMI_HIL_REPO_DIR:?GUMI_HIL_REPO_DIR is required in test mode}
    adb=${GUMI_HIL_ADB:?GUMI_HIL_ADB is required in test mode}
else
    repo_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/../../../.." && pwd)
    adb="$repo_dir/local/toolchains/android-sdk/platform-tools/adb"
fi
apk="$repo_dir/edge/shell/android/build/outputs/apk/debug/android-debug.apk"
prepared_build_inputs="$apk.build-inputs.sha256"
prepared_state="$apk.hil-prepare.manifest"
package_name='dev.gumi.shell'
component="$package_name/dev.gumi.edge.shell.android.MainActivity"
evidence_root="$repo_dir/local/hardware-in-loop/omi-cv1"

temporary_build_inputs=''
temporary_prepare_state=''
capture_tmp_dir=''

cleanup_transients() {
    if [ -n "$temporary_build_inputs" ] && [ -f "$temporary_build_inputs" ]; then
        rm -f -- "$temporary_build_inputs"
    fi
    if [ -n "$temporary_prepare_state" ] && [ -f "$temporary_prepare_state" ]; then
        rm -f -- "$temporary_prepare_state"
    fi
    if [ -n "$capture_tmp_dir" ] && [ -d "$capture_tmp_dir" ]; then
        rm -rf -- "$capture_tmp_dir"
    fi
}

trap cleanup_transients EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

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

utc_now() {
    if [ "${GUMI_HIL_TEST_MODE:-0}" = 1 ] && [ -n "${GUMI_HIL_FIXED_UTC:-}" ]; then
        timestamp=$GUMI_HIL_FIXED_UTC
    else
        timestamp=$(date -u +%Y%m%dT%H%M%SZ)
    fi
    printf '%s\n' "$timestamp" | grep -Eq '^[0-9]{8}T[0-9]{6}Z$' ||
        die "UTC timestamp is malformed"
    printf '%s\n' "$timestamp"
}

new_run_id() {
    if [ "${GUMI_HIL_TEST_MODE:-0}" = 1 ] && [ -n "${GUMI_HIL_FIXED_RUN_ID:-}" ]; then
        run_id=$GUMI_HIL_FIXED_RUN_ID
    else
        run_id=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
    fi
    printf '%s\n' "$run_id" | grep -Eq '^[0-9a-f]{32}$' ||
        die "HIL run correlation ID is malformed"
    printf '%s\n' "$run_id"
}

sha256() {
    shasum -a 256 "$1" | awk '{ print $1 }'
}

read_android_property() {
    property_name=$1
    property_value=$("$adb" shell getprop "$property_name" | tr -d '\r\n')
    [ -n "$property_value" ] || die "Android property $property_name is unavailable"
    [ "${#property_value}" -le 128 ] || die "Android property $property_name is unexpectedly long"
    printf '%s\n' "$property_value"
}

load_android_host_facts() {
    android_manufacturer=$(read_android_property ro.product.manufacturer)
    android_model=$(read_android_property ro.product.model)
    android_sdk=$(read_android_property ro.build.version.sdk)
    android_release=$(read_android_property ro.build.version.release)
    android_security_patch=$(read_android_property ro.build.version.security_patch)
    case "$android_sdk" in
        '' | *[!0-9]*) die "Android SDK level is not numeric" ;;
    esac
}

read_phone_battery_percent() {
    battery_dump=$("$adb" shell dumpsys battery | tr -d '\r')
    battery_level=$(printf '%s\n' "$battery_dump" | awk '/^[[:space:]]*level:/{ print $2; exit }')
    battery_scale=$(printf '%s\n' "$battery_dump" | awk '/^[[:space:]]*scale:/{ print $2; exit }')
    case "$battery_level:$battery_scale" in
        *[!0-9:]*) die "Android battery level is unavailable" ;;
        :* | *:) die "Android battery level is unavailable" ;;
    esac
    [ "$battery_scale" -gt 0 ] || die "Android battery scale is invalid"
    battery_percent=$((battery_level * 100 / battery_scale))
    [ "$battery_percent" -ge 0 ] && [ "$battery_percent" -le 100 ] ||
        die "Android battery percentage is outside 0..100"
    printf '%s\n' "$battery_percent"
}

require_prepare_phone() {
    load_android_host_facts
    [ "$android_sdk" -ge 29 ] || die "Gumi requires Android 10 / API 29 or newer"
    android_battery_percent=$(read_phone_battery_percent)
    [ "$android_battery_percent" -ge 50 ] || die \
        "Android battery is ${android_battery_percent}%; charge it to at least 50% before HIL"
}

read_app_process_identity() {
    process_id=$("$adb" shell pidof -s "$package_name" | tr -d '\r\n')
    case "$process_id" in
        '' | *[!0-9]*) die "Gumi is not running; use the prepare mode first" ;;
    esac
    process_start_ticks=$(
        "$adb" exec-out cat "/proc/$process_id/stat" | tr -d '\r' | awk '{ print $22 }'
    )
    case "$process_start_ticks" in
        '' | *[!0-9]*) die "Gumi process start identity is unavailable; run prepare again" ;;
    esac
}

manifest_value() {
    manifest_path=$1
    manifest_key=$2
    manifest_count=$(grep -c "^${manifest_key}=" "$manifest_path" || true)
    [ "$manifest_count" -eq 1 ] || die \
        "prepared state must contain exactly one $manifest_key entry"
    sed -n "s/^${manifest_key}=//p" "$manifest_path"
}

require_gumi_foreground_unlocked() {
    power_state=$("$adb" shell dumpsys power | tr -d '\r')
    printf '%s\n' "$power_state" | grep -E \
        'mWakefulness=Awake|Wakefulness:[[:space:]]*Awake|Display Power: state=ON' >/dev/null ||
        die "phone screen is not confirmed awake; unlock it and leave Gumi visible"

    window_policy=$("$adb" shell dumpsys window policy | tr -d '\r')
    if printf '%s\n' "$window_policy" | grep -E \
        'mShowingLockscreen=true|mKeyguardShowing=true|isStatusBarKeyguard=true|mDreamingLockscreen=true|^[[:space:]]*showing=true' >/dev/null; then
        die "phone is locked; unlock it and leave Gumi visible"
    fi
    if ! printf '%s\n' "$window_policy" | grep -E \
        'mShowingLockscreen=false|mKeyguardShowing=false|isStatusBarKeyguard=false|mDreamingLockscreen=false|^[[:space:]]*showing=false' >/dev/null; then
        trust_state=$("$adb" shell dumpsys trust | tr -d '\r')
        printf '%s\n' "$trust_state" | grep -E 'deviceLocked=0|Device locked:[[:space:]]*false' >/dev/null ||
            die "unable to confirm that the phone is unlocked; leave it unlocked and retry"
    fi

    activity_state=$("$adb" shell dumpsys activity activities | tr -d '\r')
    resumed_activity=$(
        printf '%s\n' "$activity_state" |
            grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' || true
    )
    [ -n "$resumed_activity" ] || die "Android did not report a resumed activity"
    printf '%s\n' "$resumed_activity" | grep -F "$component" >/dev/null ||
        die "Gumi is not the foreground activity; reopen it and leave the result visible"
    if printf '%s\n' "$resumed_activity" | grep -Fv "$component" | grep '[^[:space:]]' >/dev/null; then
        die "another activity is also resumed; exit split-screen mode and leave only Gumi visible"
    fi
}

require_capture_privacy_state() {
    zen_mode=$("$adb" shell settings get global zen_mode | tr -d '\r\n')
    case "$zen_mode" in
        '' | *[!0-9]*) die "Android Do Not Disturb state is unavailable" ;;
    esac
    [ "$zen_mode" -gt 0 ] || die \
        "Do Not Disturb is off; enable it, dismiss notification content, and retry capture"
}

validate_png() {
    png_path=$1
    [ -s "$png_path" ] || die "captured screenshot is empty"
    png_signature=$(od -An -N8 -tx1 "$png_path" | tr -d ' \n')
    [ "$png_signature" = 89504e470d0a1a0a ] || die "captured screenshot is not a PNG"
}

installed_base_apk_sha256() {
    installed_apk_path=$(
        "$adb" shell pm path "$package_name" | tr -d '\r' | \
            sed -n 's/^package:\(.*\/base\.apk\)$/\1/p' | head -n 1
    )
    [ -n "$installed_apk_path" ] || die "installed Gumi base APK path is unavailable"
    "$adb" exec-out cat "$installed_apk_path" | shasum -a 256 | awk '{ print $1 }'
}

write_android_build_input_manifest() {
    output_path=$1
    (
        cd "$repo_dir"
        git ls-files --cached --others --exclude-standard -- \
            settings.gradle.kts build.gradle.kts gradle.properties gradle gumiw gradlew gradlew.bat \
            edge devices/omi-cv1 | LC_ALL=C sort | \
            while IFS= read -r input_path; do
                case "$input_path" in
                    .env* | */.env* | build/* | */build/* | local/* | */local/* | \
                        node_modules/* | */node_modules/* | .gradle/* | */.gradle/* | \
                        local.properties | */local.properties | keystore.properties | \
                        */keystore.properties | *.jks | *.keystore | *.p12 | *.pem)
                        continue
                        ;;
                esac
                [ -f "$input_path" ] || continue
                input_hash=$(shasum -a 256 "$input_path" | awk '{ print $1 }')
                printf '%s  %s\n' "$input_hash" "$input_path"
            done
    ) > "$output_path"
    [ -s "$output_path" ] || die "Android build-input manifest is empty"
}

repo_worktree_dirty() {
    worktree_status=$(git -C "$repo_dir" status --porcelain --untracked-files=normal) || die \
        "repository worktree status is unavailable"
    if [ -n "$worktree_status" ]; then
        echo true
    else
        echo false
    fi
}

prepare() {
    require_one_phone
    require_prepare_phone

    echo "Preflight verified: Android ${android_release} / API ${android_sdk}, phone battery ${android_battery_percent}%."
    echo "Before this continues, the owned Omi must be above 50%, the stock Omi app must be"
    echo "fully disconnected and force-stopped, and Bluetooth must be disabled on competing phones."
    echo "Do not clear stock-app data, unpair the Omi, or accept an unexpected pairing prompt."

    # A failed new preparation must never leave an older run eligible for capture.
    rm -f -- "$prepared_state" "$prepared_build_inputs"
    "$repo_dir/gumiw" \
        verifyArchitecture \
        :edge:sdk:allTests \
        :edge:runtime:allTests \
        :devices:omi-cv1:edge-driver:allTests \
        :devices:omi-cv1:simulator:allTests \
        :edge:platforms:android:testDebugUnitTest \
        :edge:shell:android:testDebugUnitTest \
        :edge:shell:android:lintDebug \
        :edge:shell:android:assembleDebug
    [ -s "$apk" ] || die "debug APK was not produced or is empty"

    temporary_build_inputs=$(mktemp "${prepared_build_inputs}.tmp.XXXXXX")
    write_android_build_input_manifest "$temporary_build_inputs"

    apk_hash=$(sha256 "$apk")
    build_inputs_hash=$(sha256 "$temporary_build_inputs")
    prepared_at_utc=$(utc_now)
    hil_run_id=$(new_run_id)
    prepared_repo_head=$(git -C "$repo_dir" rev-parse --verify HEAD) ||
        die "repository HEAD is unavailable"
    prepared_repo_dirty=$(repo_worktree_dirty)

    echo "Phone-side effect: install or replace $package_name with the freshly built debug APK."
    echo "Existing Gumi app data is preserved; Gumi is then force-stopped and relaunched."
    echo "This does not connect to or mutate the Omi."
    "$adb" install -r "$apk"
    installed_apk_hash=$(installed_base_apk_sha256)
    [ "$installed_apk_hash" = "$apk_hash" ] || die \
        "installed Gumi base APK does not match the local build artifact"
    echo "Built and verified installed Gumi diagnostic APK: sha256=$installed_apk_hash"
    echo "Bound Android build inputs: sha256=$build_inputs_hash"
    "$adb" shell am force-stop "$package_name"
    "$adb" shell am start -W -n "$component"
    require_gumi_foreground_unlocked
    read_app_process_identity

    temporary_prepare_state=$(mktemp "${prepared_state}.tmp.XXXXXX")
    {
        echo "schema_version=2"
        echo "hil_run_id=$hil_run_id"
        echo "prepared_at_utc=$prepared_at_utc"
        echo "package_name=$package_name"
        echo "app_process_id=$process_id"
        echo "app_process_start_ticks=$process_start_ticks"
        echo "android_manufacturer=$android_manufacturer"
        echo "android_model=$android_model"
        echo "android_sdk=$android_sdk"
        echo "android_release=$android_release"
        echo "android_security_patch=$android_security_patch"
        echo "android_battery_percent=$android_battery_percent"
        echo "local_build_artifact_sha256=$apk_hash"
        echo "installed_base_apk_sha256=$installed_apk_hash"
        echo "prepared_repo_head_base=$prepared_repo_head"
        echo "prepared_repo_worktree_dirty=$prepared_repo_dirty"
        echo "android_build_inputs_manifest_sha256=$build_inputs_hash"
        echo "android_serial_collected=false"
        echo "android_id_collected=false"
        echo "android_build_fingerprint_collected=false"
        echo "bluetooth_address_or_endpoint_id_collected=false"
    } > "$temporary_prepare_state"
    [ -s "$temporary_prepare_state" ] || die "prepared HIL state is empty"

    mv "$temporary_build_inputs" "$prepared_build_inputs"
    temporary_build_inputs=''
    mv "$temporary_prepare_state" "$prepared_state"
    temporary_prepare_state=''

    echo
    echo "The app is ready for HIL run $hil_run_id. It will not connect or run MCU Manager automatically."
    echo "1. Keep the phone unlocked and hold the Omi close to it."
    echo "2. Keep Do Not Disturb on and dismiss notification content before every evidence capture."
    echo "3. Grant Nearby Devices if requested; wait for one Omi card or tap Start scan."
    echo "4. If Android shows any pairing or bonding prompt, cancel it and stop this run."
    echo "5. Stop if multiple Omi cards are present; transport identity is not qualified yet."
    echo "6. Before any connection: with exactly one card visible, tap Stop, then tap:"
    echo "   Capture single-candidate baseline. Do this exactly once in the current Activity."
    echo "7. Run the unchanged fresh-scan control: Start scan, wait for one card, Stop, and"
    echo "   record only SAME, CHANGED, or INCONCLUSIVE. Never record an address or endpoint."
    echo "8. Run the disappearance/return leg with a stopped fresh scan on each side."
    echo "9. Run one ordinary external power cycle only if its safe non-reset control is known;"
    echo "   otherwise record INCONCLUSIVE. Never guess a long-press, reset, or dock sequence."
    echo "10. Run the Bluetooth off/on leg and return to this same Activity before a fresh scan."
    echo "    Stop on a lost baseline, multiple cards, or a pairing prompt. This diagnostic"
    echo "    never connects and cannot prove identity, ownership, or cross-process stability."
    echo "11. Start one more fresh scan, wait for one card, then tap: Connect + negotiate driver."
    echo "    This path performs no writes or subscriptions."
    echo "12. When negotiated capabilities appear, run:"
    echo "   devices/omi-cv1/tests/hardware-in-loop/android-image-state-probe.sh capture-driver"
    echo "13. On the same Omi card, tap: Review MCU image-state read."
    echo "    Read the dedicated scrollable owner-review sheet; opening it performs no BLE I/O."
    echo "14. Read the disclosed transient operations, then tap: Run disclosed image-state read."
    echo "15. When the slot list and v3.0.12 oracle appear, run:"
    echo "   devices/omi-cv1/tests/hardware-in-loop/android-image-state-probe.sh capture-firmware"
    echo "16. Only for MATCHES_PUBLISHED_V3012 or APPLICATION_MATCH_NETWORK_UNOBSERVED,"
    echo "    move to a controlled quiet room"
    echo "   with no speech/media/bystanders and tap: Review 10-second live-audio metadata probe."
    echo "17. Read the full disclosure, tap: Run disclosed 10-second metadata probe, then run:"
    echo "   devices/omi-cv1/tests/hardware-in-loop/android-image-state-probe.sh capture-audio"
    echo "Firmware reads time out and release transport at 60 seconds. If the UI itself"
    echo "remains stuck, press Home as a cancellation backup; reopen, scan fresh, and retry once."
}

capture_probe() {
    probe_mode=$1
    probe_name=$2
    log_tag=$3
    start_marker=$4
    completion_marker=$5
    require_one_phone
    [ -s "$apk" ] || die "diagnostic APK is missing or empty; use the prepare mode first"
    [ -s "$prepared_build_inputs" ] || die \
        "prepared build-input manifest is missing; use the prepare mode first"
    [ -s "$prepared_state" ] || die "prepared HIL state is missing; use the prepare mode first"

    hil_run_id=$(manifest_value "$prepared_state" hil_run_id)
    printf '%s\n' "$hil_run_id" | grep -Eq '^[0-9a-f]{32}$' ||
        die "prepared HIL run correlation ID is malformed"
    prepared_process_id=$(manifest_value "$prepared_state" app_process_id)
    prepared_process_start_ticks=$(manifest_value "$prepared_state" app_process_start_ticks)
    prepared_apk_hash=$(manifest_value "$prepared_state" local_build_artifact_sha256)
    prepared_installed_hash=$(manifest_value "$prepared_state" installed_base_apk_sha256)
    prepared_repo_head=$(manifest_value "$prepared_state" prepared_repo_head_base)
    prepared_repo_dirty=$(manifest_value "$prepared_state" prepared_repo_worktree_dirty)
    prepared_inputs_hash=$(manifest_value "$prepared_state" android_build_inputs_manifest_sha256)
    prepared_android_manufacturer=$(manifest_value "$prepared_state" android_manufacturer)
    prepared_android_model=$(manifest_value "$prepared_state" android_model)
    prepared_android_sdk=$(manifest_value "$prepared_state" android_sdk)
    prepared_android_release=$(manifest_value "$prepared_state" android_release)
    prepared_android_security_patch=$(manifest_value "$prepared_state" android_security_patch)

    read_app_process_identity
    capture_process_id=$process_id
    capture_process_start_ticks=$process_start_ticks
    [ "$capture_process_id" = "$prepared_process_id" ] &&
        [ "$capture_process_start_ticks" = "$prepared_process_start_ticks" ] ||
        die "Gumi process changed after prepare; run prepare again before capturing evidence"

    # Snapshot the process-scoped tag before slower APK/build-input provenance checks. Small Android
    # log buffers can otherwise evict the two allowlisted result lines even though the app remains
    # open. Nothing is published unless every later identity and privacy check also passes.
    mkdir -p "$evidence_root"
    capture_tmp_dir=$(mktemp -d "$evidence_root/.capture-${probe_mode}.XXXXXX")
    captured_at_utc=$(utc_now)
    require_capture_privacy_state
    require_gumi_foreground_unlocked
    "$adb" logcat -d -v threadtime --pid="$capture_process_id" -s "$log_tag" '*:S' \
        > "$capture_tmp_dir/$probe_name.log"
    "$adb" exec-out screencap -p > "$capture_tmp_dir/$probe_name.png"
    [ -s "$capture_tmp_dir/$probe_name.log" ] || die "captured Gumi log is empty"
    validate_png "$capture_tmp_dir/$probe_name.png"

    capture_repo_head=$(git -C "$repo_dir" rev-parse --verify HEAD) ||
        die "repository HEAD is unavailable"
    [ "$capture_repo_head" = "$prepared_repo_head" ] ||
        die "repository HEAD changed after prepare; run prepare again"
    capture_repo_dirty=$(repo_worktree_dirty)

    load_android_host_facts
    [ "$android_manufacturer" = "$prepared_android_manufacturer" ] &&
        [ "$android_model" = "$prepared_android_model" ] &&
        [ "$android_sdk" = "$prepared_android_sdk" ] &&
        [ "$android_release" = "$prepared_android_release" ] &&
        [ "$android_security_patch" = "$prepared_android_security_patch" ] ||
        die "Android host facts changed after prepare; run prepare again"
    android_battery_percent=$(read_phone_battery_percent)
    [ "$android_battery_percent" -ge 50 ] || die \
        "Android battery is ${android_battery_percent}%; charge it and run prepare again"

    local_apk_hash=$(sha256 "$apk")
    [ "$local_apk_hash" = "$prepared_apk_hash" ] ||
        die "local APK changed after prepare; run prepare again"
    installed_apk_hash=$(installed_base_apk_sha256)
    [ "$installed_apk_hash" = "$local_apk_hash" ] &&
        [ "$installed_apk_hash" = "$prepared_installed_hash" ] ||
        die "installed Gumi base APK no longer matches the prepared build"
    actual_prepared_inputs_hash=$(sha256 "$prepared_build_inputs")
    [ "$actual_prepared_inputs_hash" = "$prepared_inputs_hash" ] ||
        die "prepared build-input manifest changed; run prepare again"

    build_inputs_manifest="$capture_tmp_dir/android-build-inputs.sha256"
    write_android_build_input_manifest "$build_inputs_manifest"
    cmp -s "$prepared_build_inputs" "$build_inputs_manifest" || die \
        "Android build inputs changed after the APK was built; run prepare again"
    build_inputs_hash=$(sha256 "$build_inputs_manifest")
    cp "$prepared_state" "$capture_tmp_dir/android-prepare.manifest"
    [ -s "$capture_tmp_dir/android-prepare.manifest" ] || die "copied prepared state is empty"
    prepared_state_hash=$(sha256 "$capture_tmp_dir/android-prepare.manifest")

    require_capture_privacy_state
    require_gumi_foreground_unlocked
    read_app_process_identity
    [ "$process_id" = "$capture_process_id" ] &&
        [ "$process_start_ticks" = "$capture_process_start_ticks" ] ||
        die "Gumi process changed during evidence capture; no evidence was published"
    latest_start_id=$(
        sed -n "s/.*$start_marker: attempt=\([0-9][0-9]*\).*/\1/p" \
            "$capture_tmp_dir/$probe_name.log" | tail -n 1
    )
    latest_success_id=$(
        sed -n "s/.*$completion_marker: attempt=\([0-9][0-9]*\).*/\1/p" \
            "$capture_tmp_dir/$probe_name.log" | tail -n 1
    )
    [ -n "$latest_start_id" ] || die \
        "no started $probe_name attempt is present; leave Gumi open and retry capture"
    [ -n "$latest_success_id" ] || die \
        "no completed $probe_name attempt is present; leave Gumi open and retry capture"
    if [ "$latest_start_id" != "$latest_success_id" ]; then
        die "latest $probe_name attempt $latest_start_id has no matching success; do not capture older attempt $latest_success_id"
    fi

    log_hash=$(sha256 "$capture_tmp_dir/$probe_name.log")
    screenshot_hash=$(sha256 "$capture_tmp_dir/$probe_name.png")
    manifest="$capture_tmp_dir/$probe_name.manifest"
    {
        echo "schema_version=2"
        echo "hil_run_id=$hil_run_id"
        echo "probe_mode=$probe_mode"
        echo "attempt_id=$latest_success_id"
        echo "captured_at_utc=$captured_at_utc"
        echo "package_name=$package_name"
        echo "app_process_id=$capture_process_id"
        echo "app_process_start_ticks=$capture_process_start_ticks"
        echo "android_manufacturer=$android_manufacturer"
        echo "android_model=$android_model"
        echo "android_sdk=$android_sdk"
        echo "android_release=$android_release"
        echo "android_security_patch=$android_security_patch"
        echo "android_battery_percent=$android_battery_percent"
        echo "installed_base_apk_sha256=$installed_apk_hash"
        echo "local_build_artifact_sha256=$local_apk_hash"
        echo "installed_matches_local_build=true"
        echo "prepared_repo_head_base=$prepared_repo_head"
        echo "capture_repo_head=$capture_repo_head"
        echo "repo_head_matches_prepared=true"
        echo "prepared_repo_worktree_dirty=$prepared_repo_dirty"
        echo "capture_repo_worktree_dirty=$capture_repo_dirty"
        echo "android_build_inputs_manifest_sha256=$build_inputs_hash"
        echo "android_build_inputs_match_prepared_apk=true"
        echo "android_prepare_manifest_sha256=$prepared_state_hash"
        echo "app_process_matches_prepared_run=true"
        echo "gumi_foreground_verified_before_and_after=true"
        echo "phone_awake_unlocked_verified_before_and_after=true"
        echo "do_not_disturb_verified_before_and_after=true"
        echo "log_sha256=$log_hash"
        echo "screenshot_sha256=$screenshot_hash"
        echo "audio_payload_or_digest_collected=false"
        echo "audio_content_logged=false"
        echo "full_device_logcat_collected=false"
        echo "android_serial_collected=false"
        echo "android_id_collected=false"
        echo "android_build_fingerprint_collected=false"
        echo "bluetooth_address_or_endpoint_id_collected=false"
    } > "$manifest"
    [ -s "$manifest" ] || die "evidence manifest is empty"
    manifest_hash=$(sha256 "$manifest")

    capture_token=${capture_tmp_dir##*.}
    evidence_dir="$evidence_root/$captured_at_utc-$probe_mode-run-$hil_run_id-attempt-$latest_success_id-$capture_token"
    [ ! -e "$evidence_dir" ] || die "evidence destination collision; no evidence was published"
    mv "$capture_tmp_dir" "$evidence_dir"
    capture_tmp_dir=''

    echo "Captured redacted process-local evidence under $evidence_dir"
    echo "hil_run_id=$hil_run_id"
    echo "attempt_id=$latest_success_id"
    echo "$probe_name.log sha256=$log_hash"
    echo "$probe_name.png sha256=$screenshot_hash"
    echo "android-build-inputs.sha256 sha256=$build_inputs_hash"
    echo "android-prepare.manifest sha256=$prepared_state_hash"
    echo "$probe_name.manifest sha256=$manifest_hash"
    echo "No Android serial/ID/fingerprint, Bluetooth address/endpoint ID, audio content/digest, or full-device logcat was collected."
}

case "${1:-}" in
    prepare)
        prepare
        ;;
    capture-driver)
        capture_probe 'capture-driver' 'driver-negotiation' 'GumiDriverProbe:I' \
            'Operational driver negotiation attempt started' \
            'Operational driver negotiation complete'
        ;;
    capture-firmware | capture)
        capture_probe 'capture-firmware' 'image-state' 'GumiFirmwareProbe:I' \
            'MCU image-state semantic read attempt started' \
            'MCU image-state semantic read complete'
        ;;
    capture-audio)
        capture_probe 'capture-audio' 'audio-metadata' 'GumiAudioProbe:I' \
            'Stock live-audio metadata probe attempt started' \
            'Stock live-audio metadata probe complete'
        ;;
    *)
        echo "usage: $0 {prepare|capture-driver|capture-firmware|capture-audio}" >&2
        exit 2
        ;;
esac
