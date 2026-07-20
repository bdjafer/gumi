#!/bin/sh
# gumi-shell-test: explicit-arguments
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "usage: $0 /exact/BasedHardware-omi /ncs-v2.9.0-west-workspace [build-name]" >&2
    exit 64
fi

upstream_repo=$(CDPATH='' cd -- "$1" && pwd)
ncs_workspace=$(CDPATH='' cd -- "$2" && pwd)
build_name=${3:-build-gumi-codec-port-emulator-0001}
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
firmware_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
test_app="$script_dir/zephyr/omi-v3012/tests/codec-port-emulator"
port_dir="$script_dir/zephyr/omi-v3012"
expected_commit='85159556eac753a088c5efd1b419a5a867508e27'
expected_opus_tree='7d93cb197c90baa60870baed2484580222fbbe6c'
toolchain_image='ghcr.io/nrfconnect/sdk-nrf-toolchain:v2.9.0@sha256:7e9b61475ca05b8517079bedc8645479101fdaa17de2d0fa06a1633288112db2'

case "$build_name" in
    '' | */* | .* | *[!A-Za-z0-9._-]*)
        echo "build directory name must be one safe path component" >&2
        exit 1
        ;;
esac

[ "$(git -C "$upstream_repo" rev-parse HEAD)" = "$expected_commit" ] || {
    echo "upstream checkout is not the pinned Omi v3.0.12 commit" >&2
    exit 1
}
[ "$(git -C "$upstream_repo" rev-parse HEAD:omi/firmware/omi/src/lib/core/lib/opus-1.2.1)" = \
  "$expected_opus_tree" ] || {
    echo "upstream Opus tree does not match the pinned Omi source" >&2
    exit 1
}
[ -z "$(git -C "$upstream_repo" status --short --untracked-files=all -- \
    omi/firmware/omi/src/lib/core/config.h \
    omi/firmware/omi/src/lib/core/lib/opus-1.2.1)" ] || {
    echo "upstream Opus inputs are dirty" >&2
    exit 1
}
shasum -a 256 "$port_dir/include/gumi/omi_v3012_codec.h" | \
    grep '^364880210dcf280c1717bc179300f752b937f39516743ffeefad9e2d73c46aee  ' >/dev/null
shasum -a 256 "$port_dir/src/codec_port.c" | \
    grep '^e9df0036d3efd9ed771311b24da30c9647b06e1011b70c7cb75af67e43d8fa20  ' >/dev/null
[ -d "$ncs_workspace/.west" ] || {
    echo "NCS workspace has no .west metadata" >&2
    exit 1
}
[ "$(git -C "$ncs_workspace/nrf" rev-parse HEAD)" = 7787b264984022cda64d9629278942053e6462a5 ]
[ "$(git -C "$ncs_workspace/zephyr" rev-parse HEAD)" = 1f8f3dc291420c70cd39e77a5cdc954561d4a08f ]
[ ! -e "$ncs_workspace/$build_name" ] || {
    echo "build output already exists: $ncs_workspace/$build_name" >&2
    exit 1
}

docker run --rm --pull never --network none --platform linux/amd64 \
    --mount "type=bind,src=$firmware_dir,dst=/gumi-firmware,readonly" \
    --mount "type=bind,src=$upstream_repo,dst=/omi/source,readonly" \
    --mount "type=bind,src=$ncs_workspace,dst=/ncs" \
    --workdir /ncs \
    "$toolchain_image" \
    "set -eu
git config --global --add safe.directory '*'
west zephyr-export
west build -b mps2/an521/cpu0 /gumi-firmware/gumi/zephyr/omi-v3012/tests/codec-port-emulator \\
  -d '$build_name' --pristine always -- \\
  -DGUMI_CODEC_PORT_DIR=/gumi-firmware/gumi/zephyr/omi-v3012 \\
  -DOMI_OPUS_DIR=/omi/source/omi/firmware/omi/src/lib/core/lib/opus-1.2.1
west build -d '$build_name' -t run"

echo "codec_port_emulator_result=pass"
echo "physical_device_contacted=false"
