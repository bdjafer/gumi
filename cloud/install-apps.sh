#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
apps_dir=${GUMI_CLOUD_APPS_DIR:-"$script_dir/apps"}
found=0

node "$script_dir/catalog/verify-manifests.mjs" "$apps_dir"

for app_dir in "$apps_dir"/*; do
    [ -d "$app_dir" ] || continue
    found=1
    package_file="$app_dir/package.json"
    [ -f "$package_file" ] || {
        echo "$app_dir: package.json is required before dependency installation" >&2
        exit 1
    }

    # Keep the program opaque to the shell; Node owns JSON parsing and template interpolation.
    # shellcheck disable=SC2016
    package_facts=$(node -e '
      const fs = require("node:fs")
      const file = process.argv[1]
      const value = JSON.parse(fs.readFileSync(file, "utf8"))
      const manager = typeof value.packageManager === "string"
        ? value.packageManager.split("@", 1)[0]
        : ""
      if (manager && manager !== "npm" && manager !== "pnpm") {
        console.error(`${file}: unsupported packageManager ${manager}`)
        process.exit(1)
      }
      const fields = ["dependencies", "devDependencies", "optionalDependencies", "peerDependencies"]
      const count = fields.reduce((sum, field) => sum + Object.keys(value[field] || {}).length, 0)
      process.stdout.write(`${manager}|${count}`)
    ' "$package_file")

    declared_manager=${package_facts%%|*}
    dependency_count=${package_facts#*|}
    has_pnpm_lock=0
    has_npm_lock=0
    [ ! -f "$app_dir/pnpm-lock.yaml" ] || has_pnpm_lock=1
    [ ! -f "$app_dir/package-lock.json" ] || has_npm_lock=1

    if [ "$has_pnpm_lock" -eq 1 ] && [ "$has_npm_lock" -eq 1 ]; then
        echo "$app_dir: ambiguous package-manager locks" >&2
        exit 1
    fi
    if [ "$declared_manager" = pnpm ] && [ "$has_pnpm_lock" -ne 1 ]; then
        echo "$app_dir: packageManager declares pnpm but pnpm-lock.yaml is missing" >&2
        exit 1
    fi
    if [ "$declared_manager" = npm ] && [ "$has_pnpm_lock" -eq 1 ]; then
        echo "$app_dir: packageManager declares npm but pnpm-lock.yaml is present" >&2
        exit 1
    fi

    if [ "$has_pnpm_lock" -eq 1 ]; then
        echo "Installing $app_dir with pnpm"
        (cd "$app_dir" && pnpm install --frozen-lockfile)
    elif [ "$has_npm_lock" -eq 1 ]; then
        echo "Installing $app_dir with npm"
        (cd "$app_dir" && npm ci)
    elif [ "$dependency_count" -ne 0 ]; then
        echo "$app_dir: dependency-bearing applications require a checked-in lockfile" >&2
        exit 1
    else
        echo "Skipping dependency-free $app_dir"
    fi
done

[ "$found" -eq 1 ] || {
    echo "$apps_dir: no applications discovered" >&2
    exit 1
}
