#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "usage: $0 /path/to/BasedHardware-omi /new/materialized/worktree [profile]" >&2
    exit 64
fi

source_repo=$1
destination=$2
profile=${3:-canary-0001}
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
firmware_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
expected_commit='85159556eac753a088c5efd1b419a5a867508e27'

case "$profile" in
    canary-0001)
        patch_file="$firmware_dir/patches/0001-canary-identity.patch"
        patch_hash='afbcb090bcb5f3b4d74b4a95acf9752253cdb6c31fa6407904e4b6c416248c43'
        ;;
    kernel-link-probe-0001)
        patch_file="$firmware_dir/patches/1001-kernel-link-probe.patch"
        patch_hash='395a8ed71be04449981a453997fa028a82c03b70ac05b56a20c82cf3ad80993f'
        ;;
    mic-port-link-probe-0001)
        patch_file="$firmware_dir/patches/1002-zephyr-mic-port-link-probe.patch"
        patch_hash='ae7d5ce35bdc4fd6cf00e69964811d5f580a8fa6744d563dc9ce37433cfad997'
        ;;
    codec-port-link-probe-0001)
        patch_file="$firmware_dir/patches/1003-zephyr-codec-port-link-probe.patch"
        patch_hash='44279967c984ed4106e34b211729de22580419470711bf2629f0c2a8d4afc597'
        ;;
    *)
        echo "unknown firmware materialization profile: $profile" >&2
        exit 64
        ;;
esac

[ -d "$source_repo/.git" ] || {
    echo "source is not a Git checkout: $source_repo" >&2
    exit 1
}
[ ! -e "$destination" ] || {
    echo "destination already exists: $destination" >&2
    exit 1
}
[ -f "$patch_file" ] || {
    echo "firmware overlay is missing: $patch_file" >&2
    exit 1
}
actual_patch_hash=$(shasum -a 256 "$patch_file" | awk '{ print $1 }')
[ "$actual_patch_hash" = "$patch_hash" ] || {
    echo "firmware overlay does not match its pinned hash" >&2
    exit 1
}

actual_commit=$(git -C "$source_repo" rev-parse "$expected_commit^{commit}")
[ "$actual_commit" = "$expected_commit" ] || {
    echo "exact Omi v3.0.12 commit is unavailable" >&2
    exit 1
}

verify_sha256() {
    relative_path=$1
    expected_hash=$2
    actual_hash=$(git -C "$source_repo" show "$expected_commit:$relative_path" | shasum -a 256 | awk '{ print $1 }')
    [ "$actual_hash" = "$expected_hash" ] || {
        echo "$relative_path does not match the pinned upstream bytes" >&2
        exit 1
    }
}

verify_sha256 omi/firmware/omi/omi.conf \
    72838438b5999cd60c391f541f43cbc8d97ae1d403a913bba29b10c32ed04f65
verify_sha256 omi/firmware/omi/src/main.c \
    cb9108a9a7acc0141db8c11fda433bbef3727a008a60eaf475a6b3b5c27f0715
verify_sha256 omi/firmware/omi/CMakeLists.txt \
    f8ab8f8f9a0113964966c3d195ef03329957d30ffe16e3ec96a9be26db24fafe
verify_sha256 omi/firmware/bootloader/mcuboot/root-rsa-2048.pem \
    1fc912d30251b821f251e127d4daf7ba9338dd5c04e5af100abfb5b7c7d4c022

git -C "$source_repo" worktree add --detach "$destination" "$expected_commit"
git -C "$destination" apply --check "$patch_file"
git -C "$destination" apply "$patch_file"

if [ "$profile" = kernel-link-probe-0001 ] || \
   [ "$profile" = mic-port-link-probe-0001 ] || \
   [ "$profile" = codec-port-link-probe-0001 ]; then
    kernel_source="$firmware_dir/gumi"
    kernel_destination="$destination/omi/firmware/omi/src/gumi"
    mkdir -p "$kernel_destination/include/gumi"
    cp "$kernel_source/include/gumi/button.h" "$kernel_destination/include/gumi/button.h"
    cp "$kernel_source/include/gumi/capture.h" "$kernel_destination/include/gumi/capture.h"
    cp "$kernel_source/include/gumi/feedback.h" "$kernel_destination/include/gumi/feedback.h"
    cp "$kernel_source/src/button.c" "$kernel_destination/button.c"
    cp "$kernel_source/src/capture.c" "$kernel_destination/capture.c"
    cp "$kernel_source/src/feedback.c" "$kernel_destination/feedback.c"
fi
if [ "$profile" = mic-port-link-probe-0001 ] || \
   [ "$profile" = codec-port-link-probe-0001 ]; then
    mkdir -p "$kernel_destination/zephyr/omi-v3012"
    cp \
        "$kernel_source/zephyr/omi-v3012/include/gumi/omi_v3012_mic.h" \
        "$kernel_destination/include/gumi/omi_v3012_mic.h"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/mic_port.c" \
        "$kernel_destination/zephyr/omi-v3012/mic_port.c"
fi
if [ "$profile" = codec-port-link-probe-0001 ]; then
    cp \
        "$kernel_source/zephyr/omi-v3012/include/gumi/omi_v3012_codec.h" \
        "$kernel_destination/include/gumi/omi_v3012_codec.h"
    cp \
        "$kernel_source/zephyr/omi-v3012/src/codec_port.c" \
        "$kernel_destination/zephyr/omi-v3012/codec_port.c"
fi

if [ "$profile" = canary-0001 ]; then
    shasum -a 256 "$destination/omi/firmware/omi/omi.conf" | \
        grep '^68b75bb56d703bf31c1dd050a96c4b14c0b5e9afb189a9df0ecd6554f770b095  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/main.c" | \
        grep '^90c6bf1250e41b165c0146d50f63ee0a5d4fb02287962f901c28df2729dc2732  ' >/dev/null
    expected_status=' M omi/firmware/omi/omi.conf
 M omi/firmware/omi/src/main.c'
else
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/include/gumi/button.h" | \
        grep '^3d687a7aa02b652d4ea21d4269f36504df017fb6e4d9670a84039502b041c633  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/include/gumi/capture.h" | \
        grep '^a703d085133c886fb46679451f743d1353431bb42548d9d03e09002bdbed7dfd  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/include/gumi/feedback.h" | \
        grep '^c79b7f224d7651c1b92a46dd321f11c2cf20707302f7fe990a0ffa8687ce7e98  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/button.c" | \
        grep '^61c389380dc16056a1bad9cb341bc488fd3ffebbfd675faba916014a4e939262  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/capture.c" | \
        grep '^625d9ea850575886000fa7f2138e079962324820be6a966ed10624d0439f5a2a  ' >/dev/null
    shasum -a 256 "$destination/omi/firmware/omi/src/gumi/feedback.c" | \
        grep '^f0fffc6d20a658d377150f1e55fe63f2f5c9d5b3e7dcd81f11b1936610b66f0c  ' >/dev/null
    if [ "$profile" = kernel-link-probe-0001 ]; then
        shasum -a 256 "$destination/omi/firmware/omi/CMakeLists.txt" | \
            grep '^a47fb0ff45363f65573b1da8994a7320d3571a7ddea5ff3735e855b054153e66  ' >/dev/null
        shasum -a 256 "$destination/omi/firmware/omi/omi.conf" | \
            grep '^aadfdeee6f376255693d8cf2c68e0459fa5b9a0311df3bed3dfc46cf7c31586e  ' >/dev/null
        grep -F 'CONFIG_BT_DIS_SW_REV_STR="gumi-kernel-link-probe-0001"' \
            "$destination/omi/firmware/omi/omi.conf" >/dev/null
        expected_status=' M omi/firmware/omi/CMakeLists.txt
 M omi/firmware/omi/omi.conf
?? omi/firmware/omi/src/gumi/button.c
?? omi/firmware/omi/src/gumi/capture.c
?? omi/firmware/omi/src/gumi/feedback.c
?? omi/firmware/omi/src/gumi/include/gumi/button.h
?? omi/firmware/omi/src/gumi/include/gumi/capture.h
?? omi/firmware/omi/src/gumi/include/gumi/feedback.h'
    elif [ "$profile" = mic-port-link-probe-0001 ]; then
        shasum -a 256 "$destination/omi/firmware/omi/CMakeLists.txt" | \
            grep '^1facfeaefc365a10e7bc1d57129eeae86fd21924f02cd17ea2e7896d5fb24249  ' >/dev/null
        shasum -a 256 "$destination/omi/firmware/omi/omi.conf" | \
            grep '^4981597a50cbe1fa85f1afd2f966a352d3f03e4687478c408c579396b21924c0  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h" | \
            grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c" | \
            grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
        grep -F 'CONFIG_BT_DIS_SW_REV_STR="gumi-mic-port-probe-0001"' \
            "$destination/omi/firmware/omi/omi.conf" >/dev/null
        expected_status=' M omi/firmware/omi/CMakeLists.txt
 M omi/firmware/omi/omi.conf
?? omi/firmware/omi/src/gumi/button.c
?? omi/firmware/omi/src/gumi/capture.c
?? omi/firmware/omi/src/gumi/feedback.c
?? omi/firmware/omi/src/gumi/include/gumi/button.h
?? omi/firmware/omi/src/gumi/include/gumi/capture.h
?? omi/firmware/omi/src/gumi/include/gumi/feedback.h
?? omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h
?? omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c'
    else
        shasum -a 256 "$destination/omi/firmware/omi/CMakeLists.txt" | \
            grep '^f1be6de1ae45329d21eb07caab68be0cf0b1930e7bafb67b357c2209d9fb7112  ' >/dev/null
        shasum -a 256 "$destination/omi/firmware/omi/omi.conf" | \
            grep '^2ca433e204cf4293a2d7eeab77bd7a4058020b0313190c6909a35ba9f916609f  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_mic.h" | \
            grep '^922b054be612aae87539c273da97eb3d4e2c00d616598cdb398176d03fd7a09b  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/zephyr/omi-v3012/mic_port.c" | \
            grep '^0a1eb9f8e9748724f6b13071e19645803ef02730f3f93faa1fa21461fb70eafc  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_codec.h" | \
            grep '^364880210dcf280c1717bc179300f752b937f39516743ffeefad9e2d73c46aee  ' >/dev/null
        shasum -a 256 \
            "$destination/omi/firmware/omi/src/gumi/zephyr/omi-v3012/codec_port.c" | \
            grep '^e9df0036d3efd9ed771311b24da30c9647b06e1011b70c7cb75af67e43d8fa20  ' >/dev/null
        grep -F 'CONFIG_BT_DIS_SW_REV_STR="gumi-codec-port-probe-0001"' \
            "$destination/omi/firmware/omi/omi.conf" >/dev/null
        expected_status=' M omi/firmware/omi/CMakeLists.txt
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
    fi
fi
actual_status=$(git -C "$destination" status --short --untracked-files=all -- omi/firmware)
[ "$actual_status" = "$expected_status" ] || {
    echo "materialized worktree does not contain exactly the $profile overlay" >&2
    printf 'expected:\n%s\nactual:\n%s\n' "$expected_status" "$actual_status" >&2
    exit 1
}

echo "materialized=$destination"
echo "upstream_commit=$expected_commit"
echo "overlay=$profile"
if [ "$profile" = kernel-link-probe-0001 ] || \
   [ "$profile" = mic-port-link-probe-0001 ] || \
   [ "$profile" = codec-port-link-probe-0001 ]; then
    echo "physical_use_forbidden=true"
fi
