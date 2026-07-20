#!/bin/sh
set -eu

violations=0
guarded_modules=''

check_module() {
    module=$1
    forbidden_pattern=$2

    if rg --line-number --glob '*.kt' "$forbidden_pattern" "$module/src"; then
        violations=1
    fi
}

check_project_dependencies() {
    build_file=$1
    allowed=$2
    module=${build_file%/build.gradle.kts}

    case " $guarded_modules " in
        *" $module "*)
            echo "$build_file: duplicate architecture dependency policy" >&2
            violations=1
            ;;
        *) guarded_modules="$guarded_modules $module" ;;
    esac

    project_call_count=$(
        rg --only-matching 'project[[:space:]]*\(' "$build_file" |
            wc -l | tr -d '[:space:]'
    )
    canonical_project_calls=$(
        rg --only-matching 'project\("[^"]+"\)' "$build_file" || true
    )
    canonical_project_call_count=$(
        printf '%s\n' "$canonical_project_calls" |
            sed '/^$/d' | wc -l | tr -d '[:space:]'
    )
    if [ "$project_call_count" -ne "$canonical_project_call_count" ]; then
        echo "$build_file: every project dependency must use canonical project(\":path\") syntax" >&2
        violations=1
    fi
    if rg --line-number '\bprojects\.' "$build_file"; then
        echo "$build_file: type-safe project accessors are not accepted by this boundary verifier" >&2
        violations=1
    fi

    dependencies=$(
        printf '%s\n' "$canonical_project_calls" |
            sed '/^$/d; s/^project("//; s/")$//'
    )
    for dependency in $dependencies; do
        case " $allowed " in
            *" $dependency "*) ;;
            *)
                echo "$build_file: forbidden project dependency $dependency" >&2
                violations=1
                ;;
        esac
    done
}

check_module \
    "edge/sdk" \
    '^import (android\.|androidx\.|dev\.gumi\.edge\.runtime\.|dev\.gumi\.devices\.|dev\.gumi\.cloud\.)'

check_module \
    "edge/runtime" \
    '^import (android\.|androidx\.|com\.nordicsemi\.|dev\.gumi\.devices\.|dev\.gumi\.cloud\.|dev\.gumi\.edge\.shell\.)'

check_module \
    "edge/adapters/cloud/media-ingest" \
    '^import (android\.|androidx\.|com\.nordicsemi\.|dev\.gumi\.devices\.|dev\.gumi\.cloud\.|dev\.gumi\.edge\.shell\.|dev\.gumi\.edge\.platforms\.)'

check_module \
    "edge/shell/application" \
    '^import (android\.|androidx\.|com\.nordicsemi\.|dev\.gumi\.devices\.|dev\.gumi\.cloud\.|dev\.gumi\.edge\.platforms\.)'

check_module \
    "edge/platforms/android" \
    '^import (dev\.gumi\.devices\.|dev\.gumi\.cloud\.|dev\.gumi\.edge\.shell\.)'

check_module \
    "devices/omi-cv1/edge-driver" \
    '^import (android\.|androidx\.|dev\.gumi\.edge\.runtime\.|dev\.gumi\.edge\.shell\.|dev\.gumi\.cloud\.)'

check_module \
    "devices/omi-cv1/application-updater/android" \
    '^import (dev\.gumi\.edge\.runtime\.|dev\.gumi\.edge\.shell\.|dev\.gumi\.cloud\.)'

check_module \
    "devices/omi-cv1/simulator" \
    '^import (android\.|androidx\.|dev\.gumi\.edge\.runtime\.|dev\.gumi\.edge\.shell\.|dev\.gumi\.cloud\.)'

# Source-import checks do not catch a dependency that is declared but not imported yet. Keep the
# portable layers' Gradle graph explicit and fail as soon as a forbidden project edge is introduced.
check_project_dependencies "edge/sdk/build.gradle.kts" ""
check_project_dependencies "edge/runtime/build.gradle.kts" ":edge:sdk"
check_project_dependencies \
    "edge/adapters/cloud/media-ingest/build.gradle.kts" \
    ":edge:runtime"
check_project_dependencies \
    "edge/shell/application/build.gradle.kts" \
    ":edge:sdk :edge:runtime"
check_project_dependencies \
    "edge/platforms/android/build.gradle.kts" \
    ":edge:sdk :edge:runtime"
check_project_dependencies "devices/omi-cv1/edge-driver/build.gradle.kts" ":edge:sdk"
check_project_dependencies \
    "devices/omi-cv1/application-updater/android/build.gradle.kts" \
    ":edge:sdk :devices:omi-cv1:edge-driver"
check_project_dependencies \
    "devices/omi-cv1/simulator/build.gradle.kts" \
    ":edge:sdk :devices:omi-cv1:edge-driver"
check_project_dependencies \
    "edge/shell/android/build.gradle.kts" \
    ":edge:runtime :edge:platforms:android :edge:shell:application :devices:omi-cv1:edge-driver"
check_project_dependencies \
    "edge/shell/linux/build.gradle.kts" \
    ":edge:sdk :edge:runtime :edge:shell:application :devices:omi-cv1:edge-driver :devices:omi-cv1:simulator"

# A new Gradle module is a new architecture policy decision. Discovery is fail-closed so adding a
# build file cannot silently bypass both dependency validation and the root workspace gate.
for build_file in $(
    find edge devices cloud \
        -type f \( -name build.gradle -o -name build.gradle.kts \) \
        ! -path '*/.gradle/*' \
        ! -path '*/build/*' \
        ! -path '*/node_modules/*' \
        -print | LC_ALL=C sort
); do
    module=${build_file%/*}
    case " $guarded_modules " in
        *" $module "*) ;;
        *)
            echo "$build_file: Gradle module has no architecture dependency policy" >&2
            violations=1
            ;;
    esac
done

for module in $guarded_modules; do
    if [ ! -f "$module/build.gradle.kts" ]; then
        echo "$module: architecture policy points to a missing build.gradle.kts" >&2
        violations=1
    fi
done

# The diagnostic firmware adapter is intentionally incapable of device mutation. Keep Nordic's
# updater and broader management surfaces out of the Android platform/shell modules. The sole
# mutating adapter lives in a separate, uncomposed device module and is constrained below.
android_platform_sources='edge/platforms/android/src'
android_shell_sources='edge/shell/android/src'
if rg --line-number --glob '*.kt' \
    '\b(FirmwareUpgradeManager|DefaultManager|BasicManager|FsManager|SettingsManager|ShellManager)\b|\.(upload|test|confirm|erase)\s*\(' \
    "$android_platform_sources" "$android_shell_sources"; then
    violations=1
fi

allowed_image_manager='edge/platforms/android/src/main/kotlin/dev/gumi/edge/platforms/android/ble/AndroidMcuMgrImageStateInspector.kt'
image_manager_files=$(
    rg --files-with-matches --glob '*.kt' '\bImageManager\b' \
        "$android_platform_sources" "$android_shell_sources" || true
)
for source_file in $image_manager_files; do
    if [ "$source_file" != "$allowed_image_manager" ]; then
        echo "$source_file: ImageManager is allowed only in the reviewed read-only adapter" >&2
        violations=1
    fi
done

updater_sources='devices/omi-cv1/application-updater/android/src/main'
allowed_updater='devices/omi-cv1/application-updater/android/src/main/kotlin/dev/gumi/devices/omicv1/updater/android/AndroidOmiCv1ApplicationImage0Session.kt'
allowed_updater_executor='devices/omi-cv1/application-updater/android/src/main/kotlin/dev/gumi/devices/omicv1/updater/android/OmiCv1ApplicationImage0UpdateExecutor.kt'
updater_management_files=$(
    rg --files-with-matches --glob '*.kt' \
        '\b(ImageManager|DefaultManager|FirmwareUpgradeManager)\b|\.(upload|test|confirm|erase|reset)\s*\(' \
        "$updater_sources" || true
)
for source_file in $updater_management_files; do
    case "$source_file" in
        "$allowed_updater" | "$allowed_updater_executor") ;;
        *)
            echo "$source_file: update mutation is allowed only in the reviewed image-0 adapter/executor" >&2
            violations=1
            ;;
    esac
done

if rg --line-number --glob '*.kt' \
    '\b(BasicManager|CrashManager|FsManager|LogManager|SUITManager|SettingsManager|ShellManager|StatsManager|ImageSet|TargetImage|FirmwareUpgradeManager)\b|\.(erase|test|slots|coreList|coreLoad|coreErase|coreDownload)\s*\(' \
    "$updater_sources"; then
    echo "$updater_sources: broader MCU Manager surfaces are forbidden in the image-0 updater" >&2
    violations=1
fi

if [ "$violations" -ne 0 ]; then
    echo "Architecture boundary violations found above." >&2
    exit 1
fi
