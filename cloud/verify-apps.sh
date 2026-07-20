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
    if [ ! -f "$package_file" ]; then
        echo "$app_dir: every cloud application must own package.json and a verify entrypoint" >&2
        exit 1
    fi
    if [ ! -f "$app_dir/README.md" ]; then
        echo "$app_dir: every cloud application must document its boundary in README.md" >&2
        exit 1
    fi
    # The JavaScript program is intentionally single-quoted so the shell cannot expand template
    # literals or interpolation syntax before Node receives it.
    # shellcheck disable=SC2016
    package_facts=$(node -e '
      const fs = require("node:fs")
      const file = process.argv[1]
      const value = JSON.parse(fs.readFileSync(file, "utf8"))
      if (!value.scripts || typeof value.scripts.verify !== "string" || value.scripts.verify.trim() === "") {
        console.error(`${file}: scripts.verify is required`)
        process.exit(1)
      }
      const manager = typeof value.packageManager === "string"
        ? value.packageManager.split("@", 1)[0]
        : ""
      if (manager && manager !== "npm" && manager !== "pnpm") {
        console.error(`${file}: unsupported packageManager ${manager}`)
        process.exit(1)
      }
      const dependencyFields = ["dependencies", "devDependencies", "optionalDependencies", "peerDependencies"]
      const dependencyCount = dependencyFields.reduce(
        (count, field) => count + Object.keys(value[field] || {}).length,
        0,
      )
      process.stdout.write(`${manager}|${dependencyCount}`)
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
    if [ "$has_pnpm_lock" -eq 0 ] && [ "$has_npm_lock" -eq 0 ] && [ "$dependency_count" -ne 0 ]; then
        echo "$app_dir: dependency-bearing applications require a checked-in lockfile" >&2
        exit 1
    fi

    echo "Verifying $app_dir"
    if [ "$has_pnpm_lock" -eq 1 ]; then
        (cd "$app_dir" && pnpm run verify)
    else
        (cd "$app_dir" && npm run verify)
    fi
done

if [ "$found" -ne 1 ]; then
    echo "$apps_dir: no applications discovered" >&2
    exit 1
fi
