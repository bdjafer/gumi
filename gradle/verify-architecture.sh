#!/bin/sh
set -eu

violations=0

check_module() {
    module=$1
    forbidden_pattern=$2

    if rg --line-number --glob '*.kt' "$forbidden_pattern" "$module/src"; then
        violations=1
    fi
}

check_module \
    "edge/sdk" \
    '^import (android\.|androidx\.|dev\.gumi\.edge\.runtime\.|dev\.gumi\.devices\.|dev\.gumi\.cloud\.)'

check_module \
    "edge/runtime" \
    '^import (android\.|androidx\.|com\.nordicsemi\.|dev\.gumi\.devices\.|dev\.gumi\.cloud\.|dev\.gumi\.edge\.shell\.)'

check_module \
    "edge/platforms/android" \
    '^import (dev\.gumi\.devices\.|dev\.gumi\.cloud\.|dev\.gumi\.edge\.shell\.)'

check_module \
    "devices/omi-cv1/edge-driver" \
    '^import (android\.|androidx\.|dev\.gumi\.edge\.runtime\.|dev\.gumi\.edge\.shell\.|dev\.gumi\.cloud\.)'

# The diagnostic firmware adapter is intentionally incapable of device mutation. Keep Nordic's
# updater and broader management surfaces out of every Android source set, and keep ImageManager
# constrained to the one reviewed list-only adapter.
android_sources='edge/platforms/android/src edge/shell/android/src'
if rg --line-number --glob '*.kt' \
    '\b(FirmwareUpgradeManager|DefaultManager|BasicManager|FsManager|SettingsManager|ShellManager)\b|\.(upload|test|confirm|erase)\s*\(' \
    $android_sources; then
    violations=1
fi

allowed_image_manager='edge/platforms/android/src/main/kotlin/dev/gumi/edge/platforms/android/ble/AndroidMcuMgrImageStateInspector.kt'
image_manager_files=$(rg --files-with-matches --glob '*.kt' '\bImageManager\b' $android_sources || true)
for source_file in $image_manager_files; do
    if [ "$source_file" != "$allowed_image_manager" ]; then
        echo "$source_file: ImageManager is allowed only in the reviewed read-only adapter" >&2
        violations=1
    fi
done

if [ "$violations" -ne 0 ]; then
    echo "Architecture boundary violations found above." >&2
    exit 1
fi
