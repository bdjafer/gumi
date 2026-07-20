#!/bin/sh
set -eu

repository_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
script_list=$(mktemp "${TMPDIR:-/tmp}/gumi-shell-scripts.XXXXXX")

cleanup() {
    rm -f -- "$script_list"
}
trap cleanup EXIT HUP INT TERM

find \
    "$repository_root/gradle" \
    "$repository_root/devices" \
    "$repository_root/edge" \
    "$repository_root/cloud" \
    -type f -name '*.sh' \
    ! -path '*/build/*' \
    ! -path '*/node_modules/*' \
    -print | LC_ALL=C sort > "$script_list"
printf '%s\n' "$repository_root/gumiw" >> "$script_list"

[ -s "$script_list" ] || {
    echo "No repository shell scripts were discovered." >&2
    exit 1
}

while IFS= read -r script; do
    sh -n "$script"
done < "$script_list"

self_contained_test_count=0
explicit_argument_test_count=0
while IFS= read -r script; do
    case "$script" in
        *.test.sh)
            if grep -q '^# gumi-shell-test: explicit-arguments$' "$script"; then
                explicit_argument_test_count=$((explicit_argument_test_count + 1))
                echo "Syntax-checked ${script#"$repository_root/"} (requires explicit inputs)"
            else
                self_contained_test_count=$((self_contained_test_count + 1))
                echo "Verifying ${script#"$repository_root/"}"
                sh "$script"
            fi
            ;;
    esac
done < "$script_list"

[ "$((self_contained_test_count + explicit_argument_test_count))" -gt 0 ] || {
    echo "No shell tests were discovered." >&2
    exit 1
}

echo "Verified $self_contained_test_count self-contained shell test file(s)."
echo "Syntax-checked $explicit_argument_test_count explicit-input shell test file(s)."
