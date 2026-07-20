#!/bin/sh
set -eu

default_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
repository_root=${GUMI_MODULE_ROOT:-"$default_root"}
[ "$#" -gt 0 ] || {
    echo "No configured Gradle modules were supplied." >&2
    exit 1
}

build_files=$(mktemp "${TMPDIR:-/tmp}/gumi-gradle-build-files.XXXXXX")
module_dirs=$(mktemp "${TMPDIR:-/tmp}/gumi-gradle-module-dirs.XXXXXX")

cleanup() {
    rm -f -- "$build_files" "$module_dirs"
}
trap cleanup EXIT HUP INT TERM

find \
    "$repository_root/devices" \
    "$repository_root/edge" \
    "$repository_root/cloud" \
    -type f \( -name build.gradle -o -name build.gradle.kts \) \
    ! -path '*/.gradle/*' \
    ! -path '*/build/*' \
    ! -path '*/node_modules/*' \
    -print | LC_ALL=C sort > "$build_files"

sed \
    -e "s|^$repository_root/||" \
    -e 's|/build\.gradle\.kts$||' \
    -e 's|/build\.gradle$||' \
    "$build_files" | LC_ALL=C sort > "$module_dirs"

violations=0
duplicate_dirs=$(uniq -d "$module_dirs")
if [ -n "$duplicate_dirs" ]; then
    printf 'Directories with multiple Gradle build files:\n%s\n' "$duplicate_dirs" >&2
    violations=1
fi

for discovered_module in $(uniq "$module_dirs"); do
    configured=0
    for configured_module in "$@"; do
        if [ "$configured_module" = "$discovered_module" ]; then
            configured=1
            break
        fi
    done
    if [ "$configured" -ne 1 ]; then
        echo "$discovered_module: Gradle build directory is missing from settings.gradle.kts" >&2
        violations=1
    fi
done

for configured_module in "$@"; do
    if ! grep -F -x "$configured_module" "$module_dirs" >/dev/null; then
        namespace_container=0
        for other_module in "$@"; do
            case "$other_module" in
                "$configured_module"/*)
                    namespace_container=1
                    break
                    ;;
            esac
        done
        if [ "$namespace_container" -ne 1 ]; then
            echo "$configured_module: configured leaf project has no build.gradle(.kts)" >&2
            violations=1
        fi
    fi
done

if [ "$violations" -ne 0 ]; then
    echo "Gradle module coverage is incomplete." >&2
    exit 1
fi
