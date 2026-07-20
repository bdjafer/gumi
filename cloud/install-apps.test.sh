#!/bin/sh
set -eu

test_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
installer="$test_dir/install-apps.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/gumi-cloud-installer-test.XXXXXX")
test_root=$(CDPATH='' cd -- "$test_root" && pwd)
apps_dir="$test_root/apps"
bin_dir="$test_root/bin"
run_log="$test_root/runs.log"

cleanup() {
    rm -rf -- "$test_root"
}
trap cleanup EXIT HUP INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

write_package() {
    app=$1
    body=$2
    mkdir -p "$apps_dir/$app"
    printf '# Test app\n' > "$apps_dir/$app/README.md"
    printf '%s\n' "$body" > "$apps_dir/$app/package.json"
    write_manifest "$app"
}

write_manifest() {
    app=$1
    cat > "$apps_dir/$app/app.json" <<EOF
{
  "\$schema": "../../catalog/application-manifest.schema.json",
  "schemaVersion": 1,
  "appId": "$app",
  "kind": "http-service",
  "owner": "test-owner",
  "summary": "Test application",
  "durableAuthority": ["Test state"],
  "dataClasses": ["operational-metadata"],
  "tenancy": {
    "mode": "dedicated-deployment",
    "sharedDeployment": false,
    "scopeSource": "deployment-binding"
  },
  "contracts": [
    {
      "id": "$app-v1",
      "protocol": "http-openapi",
      "version": "v1",
      "path": "README.md"
    }
  ],
  "dependencies": [],
  "deployment": { "status": "local-only", "target": null, "regions": [] },
  "retention": { "status": "unselected", "class": "test-data", "policyId": null },
  "runbook": "README.md"
}
EOF
}

run_installer() {
    env \
        GUMI_CLOUD_APPS_DIR="$apps_dir" \
        GUMI_CLOUD_INSTALL_TEST_LOG="$run_log" \
        PATH="$bin_dir:$PATH" \
        sh "$installer"
}

expect_failure() {
    expected=$1
    if run_installer > "$test_root/negative.out" 2>&1; then
        fail "cloud installer accepted: $expected"
    fi
    grep -F "$expected" "$test_root/negative.out" >/dev/null || {
        cat "$test_root/negative.out" >&2
        fail "cloud installer did not report: $expected"
    }
}

mkdir -p "$bin_dir"
for manager in npm pnpm; do
    executable="$bin_dir/$manager"
    # The generated fixture must expand its own process environment, not this test's environment.
    # shellcheck disable=SC2016
    printf '%s\n' '#!/bin/sh' \
        'printf '\''%s|%s|%s\n'\'' "${0##*/}" "$PWD" "$*" >> "${GUMI_CLOUD_INSTALL_TEST_LOG:?}"' \
        > "$executable"
    chmod +x "$executable"
done

write_package pnpm-app \
    '{"packageManager":"pnpm@11.13.1","dependencies":{"one":"1.0.0"}}'
: > "$apps_dir/pnpm-app/pnpm-lock.yaml"
write_package npm-app '{"dependencies":{"two":"1.0.0"}}'
: > "$apps_dir/npm-app/package-lock.json"
write_package zero-app '{"private":true}'

run_installer
grep -F "pnpm|$apps_dir/pnpm-app|install --frozen-lockfile" "$run_log" >/dev/null ||
    fail 'pnpm lock did not select frozen pnpm install'
grep -F "npm|$apps_dir/npm-app|ci" "$run_log" >/dev/null ||
    fail 'npm lock did not select npm ci'

write_package zero-app '{"dependencies":{"missing":"1.0.0"}}'
expect_failure 'dependency-bearing applications require a checked-in lockfile'

write_package zero-app '{"private":true}'
: > "$apps_dir/pnpm-app/package-lock.json"
expect_failure 'ambiguous package-manager locks'

echo 'Cloud dependency installer probes passed.'
