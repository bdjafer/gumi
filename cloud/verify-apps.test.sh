#!/bin/sh
set -eu

test_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
verifier="$test_dir/verify-apps.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/gumi-cloud-verifier-test.XXXXXX")
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

run_verifier() {
    env \
        GUMI_CLOUD_APPS_DIR="$apps_dir" \
        GUMI_CLOUD_VERIFY_TEST_LOG="$run_log" \
        PATH="$bin_dir:$PATH" \
        sh "$verifier"
}

expect_failure() {
    expected=$1
    if run_verifier > "$test_root/negative.out" 2>&1; then
        fail "cloud verifier accepted: $expected"
    fi
    grep -F "$expected" "$test_root/negative.out" >/dev/null || {
        cat "$test_root/negative.out" >&2
        fail "cloud verifier did not report: $expected"
    }
}

mkdir -p "$bin_dir"
for manager in npm pnpm; do
    cat > "$bin_dir/$manager" <<'EOF'
#!/bin/sh
printf '%s|%s|%s\n' "${0##*/}" "$PWD" "$*" >> "${GUMI_CLOUD_VERIFY_TEST_LOG:?}"
EOF
    chmod +x "$bin_dir/$manager"
done

write_package npm-app \
    '{"private":true,"scripts":{"verify":"node --test"}}'
write_package pnpm-app \
    '{"private":true,"packageManager":"pnpm@11.13.1","scripts":{"verify":"pnpm test"},"dependencies":{"example":"1.0.0"}}'
: > "$apps_dir/pnpm-app/pnpm-lock.yaml"

run_verifier
grep -F "npm|$apps_dir/npm-app|run verify" "$run_log" >/dev/null ||
    fail 'dependency-free npm application was not verified'
grep -F "pnpm|$apps_dir/pnpm-app|run verify" "$run_log" >/dev/null ||
    fail 'locked pnpm application was not verified'

write_package npm-app '{"private":true,"scripts":{}}'
expect_failure 'scripts.verify is required'

write_package npm-app \
    '{"private":true,"scripts":{"verify":"node --test"},"dependencies":{"example":"1.0.0"}}'
expect_failure 'dependency-bearing applications require a checked-in lockfile'

write_package npm-app \
    '{"private":true,"scripts":{"verify":"node --test"}}'
rm "$apps_dir/npm-app/app.json"
expect_failure 'application manifest is required'
write_manifest npm-app

: > "$apps_dir/npm-app/app.json"
expect_failure 'invalid JSON'
write_manifest npm-app

sed 's/"appId": "npm-app"/"appId": "wrong-app"/' \
    "$apps_dir/npm-app/app.json" > "$test_root/wrong-manifest.json"
cp "$test_root/wrong-manifest.json" "$apps_dir/npm-app/app.json"
expect_failure 'appId must match its directory name'
write_manifest npm-app

sed 's/"dependencies": \[\]/"dependencies": [{"appId":"pnpm-app","contractId":"missing-v1","purpose":"test"}]/' \
    "$apps_dir/npm-app/app.json" > "$test_root/missing-contract.json"
cp "$test_root/missing-contract.json" "$apps_dir/npm-app/app.json"
expect_failure 'references missing contract'
write_manifest npm-app

sed 's/"sharedDeployment": false/"sharedDeployment": true/' \
    "$apps_dir/npm-app/app.json" > "$test_root/unsafe-tenancy.json"
cp "$test_root/unsafe-tenancy.json" "$apps_dir/npm-app/app.json"
expect_failure 'does not match the dedicated-deployment isolation contract'
write_manifest npm-app

sed 's/"dependencies": \[\]/"dependencies": [{"appId":"pnpm-app","contractId":"pnpm-app-v1","purpose":"test"}]/' \
    "$apps_dir/npm-app/app.json" > "$test_root/npm-cycle.json"
cp "$test_root/npm-cycle.json" "$apps_dir/npm-app/app.json"
sed 's/"dependencies": \[\]/"dependencies": [{"appId":"npm-app","contractId":"npm-app-v1","purpose":"test"}]/' \
    "$apps_dir/pnpm-app/app.json" > "$test_root/pnpm-cycle.json"
cp "$test_root/pnpm-cycle.json" "$apps_dir/pnpm-app/app.json"
expect_failure 'application dependency cycle'
write_manifest npm-app
write_manifest pnpm-app

: > "$apps_dir/pnpm-app/package-lock.json"
expect_failure 'ambiguous package-manager locks'

rm -rf -- "$apps_dir/npm-app" "$apps_dir/pnpm-app"
expect_failure 'no applications discovered'

echo 'Cloud application verifier probes passed.'
