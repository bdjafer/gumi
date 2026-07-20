#!/bin/sh
set -eu

if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
    echo "usage: $0 /materialized/omi /ncs-v2.9.0-west-workspace build-directory-name [profile]" >&2
    exit 64
fi

source_repo=$(CDPATH='' cd -- "$1" && pwd)
ncs_workspace=$(CDPATH='' cd -- "$2" && pwd)
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
firmware_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
build_name=$3
profile=${4:-canary-0001}
expected_commit='85159556eac753a088c5efd1b419a5a867508e27'
toolchain_image='ghcr.io/nrfconnect/sdk-nrf-toolchain:v2.9.0@sha256:7e9b61475ca05b8517079bedc8645479101fdaa17de2d0fa06a1633288112db2'

case "$build_name" in
    '' | */* | .* | *[!A-Za-z0-9._-]*)
        echo "build directory name must be one safe path component" >&2
        exit 1
        ;;
esac

[ "$(git -C "$source_repo" rev-parse HEAD)" = "$expected_commit" ] || {
    echo "materialized source is not based on the pinned Omi commit" >&2
    exit 1
}
case "$profile" in
    canary-0001)
        expected_sw_revision='gumi-canary-0001'
        expected_source_status=' M omi/firmware/omi/omi.conf
 M omi/firmware/omi/src/main.c'
        ;;
    kernel-link-probe-0001)
        expected_sw_revision='gumi-kernel-link-probe-0001'
        expected_source_status=' M omi/firmware/omi/CMakeLists.txt
 M omi/firmware/omi/omi.conf
?? omi/firmware/omi/src/gumi/button.c
?? omi/firmware/omi/src/gumi/capture.c
?? omi/firmware/omi/src/gumi/feedback.c
?? omi/firmware/omi/src/gumi/include/gumi/button.h
?? omi/firmware/omi/src/gumi/include/gumi/capture.h
?? omi/firmware/omi/src/gumi/include/gumi/feedback.h'
        ;;
    mic-port-link-probe-0001)
        expected_sw_revision='gumi-mic-port-probe-0001'
        expected_source_status=' M omi/firmware/omi/CMakeLists.txt
 M omi/firmware/omi/omi.conf
?? omi/firmware/omi/src/gumi/button.c
?? omi/firmware/omi/src/gumi/capture.c
?? omi/firmware/omi/src/gumi/feedback.c
?? omi/firmware/omi/src/gumi/include/gumi/button.h
?? omi/firmware/omi/src/gumi/include/gumi/capture.h
?? omi/firmware/omi/src/gumi/include/gumi/feedback.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c'
        ;;
    codec-port-link-probe-0001)
        expected_sw_revision='gumi-codec-port-probe-0001'
        expected_source_status=' M omi/firmware/omi/CMakeLists.txt
 M omi/firmware/omi/omi.conf
?? omi/firmware/omi/src/gumi/button.c
?? omi/firmware/omi/src/gumi/capture.c
?? omi/firmware/omi/src/gumi/feedback.c
?? omi/firmware/omi/src/gumi/include/gumi/button.h
?? omi/firmware/omi/src/gumi/include/gumi/capture.h
?? omi/firmware/omi/src/gumi/include/gumi/feedback.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_codec.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/codec_port.c
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c'
        ;;
    *)
        echo "unknown firmware build profile: $profile" >&2
        exit 64
        ;;
esac
actual_source_status=$(git -C "$source_repo" status --short --untracked-files=all -- omi/firmware)
[ "$actual_source_status" = "$expected_source_status" ] || {
    echo "materialized source contains changes outside the exact $profile overlay" >&2
    exit 1
}
git -C "$source_repo" diff --check -- omi/firmware
if [ "$profile" = canary-0001 ]; then
    shasum -a 256 "$source_repo/omi/firmware/omi/omi.conf" | \
        grep '^68b75bb56d703bf31c1dd050a96c4b14c0b5e9afb189a9df0ecd6554f770b095  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/main.c" | \
        grep '^90c6bf1250e41b165c0146d50f63ee0a5d4fb02287962f901c28df2729dc2732  ' >/dev/null
    grep -F 'Gumi canary identity only' "$source_repo/omi/firmware/omi/src/main.c" >/dev/null
else
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/button.h" | \
        grep '^3d687a7aa02b652d4ea21d4269f36504df017fb6e4d9670a84039502b041c633  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/capture.h" | \
        grep '^a703d085133c886fb46679451f743d1353431bb42548d9d03e09002bdbed7dfd  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/include/gumi/feedback.h" | \
        grep '^c79b7f224d7651c1b92a46dd321f11c2cf20707302f7fe990a0ffa8687ce7e98  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/button.c" | \
        grep '^61c389380dc16056a1bad9cb341bc488fd3ffebbfd675faba916014a4e939262  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/capture.c" | \
        grep '^625d9ea850575886000fa7f2138e079962324820be6a966ed10624d0439f5a2a  ' >/dev/null
    shasum -a 256 "$source_repo/omi/firmware/omi/src/gumi/feedback.c" | \
        grep '^f0fffc6d20a658d377150f1e55fe63f2f5c9d5b3e7dcd81f11b1936610b66f0c  ' >/dev/null
    if [ "$profile" = kernel-link-probe-0001 ]; then
        shasum -a 256 "$source_repo/omi/firmware/omi/CMakeLists.txt" | \
            grep '^a47fb0ff45363f65573b1da8994a7320d3571a7ddea5ff3735e855b054153e66  ' >/dev/null
        shasum -a 256 "$source_repo/omi/firmware/omi/omi.conf" | \
            grep '^aadfdeee6f376255693d8cf2c68e0459fa5b9a0311df3bed3dfc46cf7c31586e  ' >/dev/null
    elif [ "$profile" = mic-port-link-probe-0001 ]; then
        shasum -a 256 "$source_repo/omi/firmware/omi/CMakeLists.txt" | \
            grep '^1facfeaefc365a10e7bc1d57129eeae86fd21924f02cd17ea2e7896d5fb24249  ' >/dev/null
        shasum -a 256 "$source_repo/omi/firmware/omi/omi.conf" | \
            grep '^4981597a50cbe1fa85f1afd2f966a352d3f03e4687478c408c579396b21924c0  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h" | \
            grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c" | \
            grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
    else
        shasum -a 256 "$source_repo/omi/firmware/omi/CMakeLists.txt" | \
            grep '^f1be6de1ae45329d21eb07caab68be0cf0b1930e7bafb67b357c2209d9fb7112  ' >/dev/null
        shasum -a 256 "$source_repo/omi/firmware/omi/omi.conf" | \
            grep '^2ca433e204cf4293a2d7eeab77bd7a4058020b0313190c6909a35ba9f916609f  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h" | \
            grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c" | \
            grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_codec.h" | \
            grep '^364880210dcf280c1717bc179300f752b937f39516743ffeefad9e2d73c46aee  ' >/dev/null
        shasum -a 256 \
            "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/codec_port.c" | \
            grep '^e9df0036d3efd9ed771311b24da30c9647b06e1011b70c7cb75af67e43d8fa20  ' >/dev/null
    fi
fi
shasum -a 256 "$source_repo/omi/firmware/bootloader/mcuboot/root-rsa-2048.pem" | \
    grep '^1fc912d30251b821f251e127d4daf7ba9338dd5c04e5af100abfb5b7c7d4c022  ' >/dev/null
grep -F "CONFIG_BT_DIS_SW_REV_STR=\"$expected_sw_revision\"" \
    "$source_repo/omi/firmware/omi/omi.conf" >/dev/null

[ -d "$ncs_workspace/.west" ] || {
    echo "NCS workspace has no .west metadata: $ncs_workspace" >&2
    exit 1
}
[ "$(git -C "$ncs_workspace/nrf" rev-parse HEAD)" = 7787b264984022cda64d9629278942053e6462a5 ]
[ "$(git -C "$ncs_workspace/zephyr" rev-parse HEAD)" = 1f8f3dc291420c70cd39e77a5cdc954561d4a08f ]
[ "$(git -C "$ncs_workspace/bootloader/mcuboot" rev-parse HEAD)" = 12e5ee106034972b0f1074d6f2261b2b39d1501b ]
[ ! -e "$ncs_workspace/$build_name" ] || {
    echo "build output already exists: $ncs_workspace/$build_name" >&2
    exit 1
}

docker run --rm --pull never --network none --platform linux/amd64 \
    --mount "type=bind,src=$source_repo,dst=/omi/source,readonly" \
    --mount "type=bind,src=$ncs_workspace,dst=/ncs" \
    --workdir /ncs \
    "$toolchain_image" \
    "set -eu
git config --global --add safe.directory '*'
west zephyr-export
cp /omi/source/omi/firmware/omi/omi.conf '/ncs/gumi-$profile-prj.conf'
west build -b omi/nrf5340/cpuapp /omi/source/omi/firmware/omi --sysbuild \\
  -d '$build_name' --pristine always -- \\
  -DBOARD_ROOT=/omi/source/omi/firmware \\
  -DCONF_FILE='/ncs/gumi-$profile-prj.conf'
artifact_wait=0
while [ ! -s '$build_name/omi/zephyr/zephyr.signed.bin' ] && [ \"\$artifact_wait\" -lt 10 ]; do
  artifact_wait=\$((artifact_wait + 1))
  sleep 1
done
test -s '$build_name/omi/zephyr/zephyr.signed.bin'
echo 'verified=signed_application'
if [ '$profile' = kernel-link-probe-0001 ] || \
   [ '$profile' = mic-port-link-probe-0001 ] || \
   [ '$profile' = codec-port-link-probe-0001 ]; then
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/button.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/capture.c.obj'
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/feedback.c.obj'
  echo 'verified=gumi_kernel_objects'
fi
if [ '$profile' = mic-port-link-probe-0001 ] || \
   [ '$profile' = codec-port-link-probe-0001 ]; then
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/mic_port.c.obj'
  echo 'verified=gumi_mic_port_object'
fi
if [ '$profile' = codec-port-link-probe-0001 ]; then
  test -s '$build_name/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/codec_port.c.obj'
  echo 'verified=gumi_codec_port_object'
fi
echo 'build_result=pass'"

docker run --rm --pull never --network none --platform linux/amd64 \
    --env PYTHONDONTWRITEBYTECODE=1 \
    --mount "type=bind,src=$source_repo,dst=/omi/source,readonly" \
    --mount "type=bind,src=$ncs_workspace,dst=/ncs,readonly" \
    --mount "type=bind,src=$firmware_dir,dst=/gumi-firmware,readonly" \
    --workdir /ncs \
    "$toolchain_image" \
    "sh /gumi-firmware/scripts/verify-build-output.sh \\
      '/ncs/$build_name' /omi/source '$profile'"

application="$ncs_workspace/$build_name/omi/zephyr/zephyr.signed.bin"
[ -s "$application" ] || {
    echo "container reported success but the host application artifact is missing" >&2
    exit 1
}
echo "application=$application"
echo "profile=$profile"
if [ "$profile" = kernel-link-probe-0001 ] || \
   [ "$profile" = mic-port-link-probe-0001 ] || \
   [ "$profile" = codec-port-link-probe-0001 ]; then
    echo "physical_use_forbidden=true"
fi
echo "warning=network and complete OTA artifacts are quarantined and must not be uploaded"
