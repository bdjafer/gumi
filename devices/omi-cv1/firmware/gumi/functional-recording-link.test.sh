#!/bin/sh
# gumi-shell-test: explicit-arguments
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "usage: $0 /exact/BasedHardware-omi /ncs-v2.9.0-west-workspace [build-name]" >&2
    exit 64
fi

upstream_repo=$(CDPATH='' cd -- "$1" && pwd)
ncs_workspace=$(CDPATH='' cd -- "$2" && pwd)
build_name=${3:-build-gumi-functional-recording-link-0001}
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
firmware_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
opus_dir="/omi/source/omi/firmware/omi/src/lib/core/lib/opus-1.2.1"
expected_commit='85159556eac753a088c5efd1b419a5a867508e27'
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
[ -z "$(git -C "$upstream_repo" status --short --untracked-files=all -- \
    omi/firmware/boards omi/firmware/omi/src/lib/core/lib/opus-1.2.1)" ] || {
    echo "upstream Omi board or Opus inputs are dirty" >&2
    exit 1
}
[ -d "$ncs_workspace/.west" ] || {
    echo "NCS workspace has no .west metadata" >&2
    exit 1
}
[ "$(git -C "$ncs_workspace/nrf" rev-parse HEAD)" = \
    7787b264984022cda64d9629278942053e6462a5 ]
[ "$(git -C "$ncs_workspace/zephyr" rev-parse HEAD)" = \
    1f8f3dc291420c70cd39e77a5cdc954561d4a08f ]
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
export OMI_OPUS_DIR='$opus_dir'
west build -b omi/nrf5340/cpuapp \
  /gumi-firmware/gumi/zephyr/omi-v3012/tests/functional-recording-link \
  -d '$build_name' --pristine always --sysbuild -- \
  -DBOARD_ROOT=/omi/source/omi/firmware \
  -DGUMI_FIRMWARE_DIR=/gumi-firmware/gumi \
  -DOMI_OPUS_DIR='$opus_dir' \
  -Dfunctional-recording-link_GUMI_FIRMWARE_DIR=/gumi-firmware/gumi \
  -Dfunctional-recording-link_OMI_OPUS_DIR='$opus_dir'
app_build='$build_name/functional-recording-link'
test -s \"\$app_build/zephyr/zephyr.elf\"
test -s \"\$app_build/CMakeFiles/app.dir/gumi-firmware/gumi/zephyr/omi-v3012/src/functional_main.c.obj\"
test -s \"\$app_build/CMakeFiles/app.dir/gumi-firmware/gumi/zephyr/omi-v3012/src/functional_transport.c.obj\"
test -s \"\$app_build/CMakeFiles/app.dir/gumi-firmware/gumi/zephyr/omi-v3012/src/recording_storage_pm_policy.c.obj\"
test -s \"\$app_build/CMakeFiles/app.dir/gumi-firmware/gumi/zephyr/omi-v3012/src/recording_storage_port.c.obj\"
grep -F 'CONFIG_FAT_FILESYSTEM_ELM=y' \"\$app_build/zephyr/.config\" >/dev/null
grep -F '# CONFIG_FILE_SYSTEM_MKFS is not set' \"\$app_build/zephyr/.config\" >/dev/null
grep -F '# CONFIG_FS_FATFS_MOUNT_MKFS is not set' \"\$app_build/zephyr/.config\" >/dev/null
grep -F 'CONFIG_TASK_WDT=y' \"\$app_build/zephyr/.config\" >/dev/null
grep -F 'CONFIG_TASK_WDT_HW_FALLBACK=y' \"\$app_build/zephyr/.config\" >/dev/null
grep -F 'CONFIG_MBEDTLS_ENABLE_HEAP=y' \"\$app_build/zephyr/.config\" >/dev/null
grep -F 'CONFIG_MBEDTLS_HEAP_SIZE=4096' \"\$app_build/zephyr/.config\" >/dev/null
grep -F 'CONFIG_MCUMGR_GRP_OS_RESET_HOOK=y' \"\$app_build/zephyr/.config\" >/dev/null
if grep -Eq '^CONFIG_HW_UNIQUE_KEY_(WRITE_ON_CRYPTO_INIT|RANDOM)=y$' \
  \"\$app_build/zephyr/.config\"; then
  echo 'forbidden HUK write capability is enabled' >&2
  exit 1
fi
grep -F 'gumi_omi_v3012_functional_transport_start' \"\$app_build/zephyr/zephyr.map\" >/dev/null
grep -F 'gumi_omi_v3012_recording_storage_finalize' \"\$app_build/zephyr/zephyr.map\" >/dev/null
grep -F 'gumi_omi_v3012_recording_storage_normalize_resume_result' \"\$app_build/zephyr/zephyr.map\" >/dev/null
grep -F 'gumi_omi_v3012_codec_close' \"\$app_build/zephyr/zephyr.map\" >/dev/null
grep -F 'admit_physically_confirmed_reset' \"\$app_build/zephyr/zephyr.map\" >/dev/null
grep -F 'task_wdt_feed' \"\$app_build/zephyr/zephyr.map\" >/dev/null
grep -F 'nrfx_wdt_enable' \"\$app_build/zephyr/zephyr.map\" >/dev/null
grep -F 'mbedtls_heap_init' \"\$app_build/zephyr/zephyr.map\" >/dev/null
grep -F 'mbedtls_memory_buffer_alloc_init' \"\$app_build/zephyr/zephyr.map\" >/dev/null
grep -F 'mbedtls_platform_set_calloc_free' \"\$app_build/zephyr/zephyr.map\" >/dev/null
if grep -F 'hw_unique_key_write_random' \"\$app_build/zephyr/zephyr.map\" >/dev/null; then
  echo 'forbidden random HUK provisioning function reached the link' >&2
  exit 1
fi
if grep -F 'fs_mkfs' \"\$app_build/zephyr/zephyr.map\" >/dev/null; then
  echo 'forbidden filesystem formatter reached the link' >&2
  exit 1
fi"

echo "functional_recording_link_result=pass"
echo "physical_device_contacted=false"
echo "flash_candidate_produced=false"
