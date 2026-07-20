#!/bin/sh
set -eu

if [ "$#" -ne 4 ]; then
    echo "usage: $0 release.json omi.signed.bin /materialized/omi /ncs-v2.9.0-west-workspace" >&2
    exit 64
fi

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
release_json=$(CDPATH='' cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")
application=$(CDPATH='' cd -- "$(dirname -- "$2")" && pwd)/$(basename -- "$2")
source_repo=$(CDPATH='' cd -- "$3" && pwd)
ncs_workspace=$(CDPATH='' cd -- "$4" && pwd)
toolchain_image='ghcr.io/nrfconnect/sdk-nrf-toolchain:v2.9.0@sha256:7e9b61475ca05b8517079bedc8645479101fdaa17de2d0fa06a1633288112db2'
compatibility_key="$source_repo/omi/firmware/bootloader/mcuboot/root-rsa-2048.pem"

[ -f "$release_json" ] || { echo "release manifest is missing: $release_json" >&2; exit 1; }
[ -s "$application" ] || { echo "application artifact is missing: $application" >&2; exit 1; }
[ -f "$compatibility_key" ] || { echo "compatibility key is missing: $compatibility_key" >&2; exit 1; }
[ -d "$ncs_workspace/.west" ] || { echo "NCS workspace has no .west metadata" >&2; exit 1; }
[ "$(git -C "$ncs_workspace/bootloader/mcuboot" rev-parse HEAD)" = \
    12e5ee106034972b0f1074d6f2261b2b39d1501b ]
shasum -a 256 "$compatibility_key" | \
    grep '^1fc912d30251b821f251e127d4daf7ba9338dd5c04e5af100abfb5b7c7d4c022  ' >/dev/null

node "$script_dir/verify-application-release.mjs" "$release_json" "$application"

docker run --rm --pull never --network none --platform linux/amd64 \
    --mount "type=bind,src=$application,dst=/qualification/omi.signed.bin,readonly" \
    --mount "type=bind,src=$compatibility_key,dst=/qualification/root-rsa-2048.pem,readonly" \
    --mount "type=bind,src=$ncs_workspace,dst=/ncs,readonly" \
    --workdir /ncs \
    "$toolchain_image" \
    "/ncs/bootloader/mcuboot/scripts/imgtool.py verify -k /qualification/root-rsa-2048.pem /qualification/omi.signed.bin"

echo "PASS: exact application manifest and compatibility signature are qualified offline"
echo "artifact_conveys_physical_authorization=false"
