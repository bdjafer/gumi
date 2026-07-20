#!/bin/sh
set -eu

if [ "$#" -ne 3 ]; then
    echo "usage: $0 /ncs/build-directory /materialized/omi profile" >&2
    exit 64
fi

build_directory=$1
source_repo=$2
profile=$3

case "$profile" in
    canary-0001)
        expected_sw_revision='gumi-canary-0001'
        ;;
    kernel-link-probe-0001)
        expected_sw_revision='gumi-kernel-link-probe-0001'
        ;;
    mic-port-link-probe-0001)
        expected_sw_revision='gumi-mic-port-probe-0001'
        ;;
    codec-port-link-probe-0001)
        expected_sw_revision='gumi-codec-port-probe-0001'
        ;;
    *)
        echo "unknown firmware verification profile: $profile" >&2
        exit 64
        ;;
esac

require_file() {
    file=$1
    label=$2
    if [ ! -s "$file" ]; then
        echo "verification failed: missing or empty $label: $file" >&2
        exit 1
    fi
    echo "verified=$label"
}

require_line() {
    file=$1
    line=$2
    label=$3
    if ! grep -Fqx "$line" "$file"; then
        echo "verification failed: $label does not contain the exact required value" >&2
        exit 1
    fi
    echo "verified=$label"
}

require_contains() {
    file=$1
    text=$2
    label=$3
    if ! grep -Fq "$text" "$file"; then
        echo "verification failed: $label is absent" >&2
        exit 1
    fi
    echo "verified=$label"
}

require_sha256() {
    file=$1
    expected=$2
    label=$3
    actual=$(sha256sum "$file" | awk '{ print $1 }')
    if [ "$actual" != "$expected" ]; then
        echo "verification failed: $label SHA-256 mismatch" >&2
        echo "expected_sha256=$expected" >&2
        echo "actual_sha256=$actual" >&2
        exit 1
    fi
    echo "verified=$label"
}

application_config="$build_directory/omi/zephyr/.config"
mcuboot_config="$build_directory/mcuboot/zephyr/.config"
application="$build_directory/omi/zephyr/zephyr.signed.bin"

require_file "$application" signed_application
require_file "$application_config" application_configuration
require_file "$mcuboot_config" mcuboot_configuration
require_file "$build_directory/partitions.yml" partition_map

require_line "$application_config" 'CONFIG_BT_DIS_FW_REV_STR="3.0.12"' firmware_revision
require_line "$application_config" "CONFIG_BT_DIS_SW_REV_STR=\"$expected_sw_revision\"" software_revision
require_line "$application_config" 'CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION="0.0.0+0"' mcuboot_image_version
require_line "$application_config" 'CONFIG_MCUMGR_GRP_IMG_UPDATABLE_IMAGE_NUMBER=2' update_image_count
require_line "$application_config" 'CONFIG_TOOLCHAIN_ZEPHYR_0_17=y' zephyr_sdk
require_line "$mcuboot_config" 'CONFIG_BOOT_UPGRADE_ONLY=y' upgrade_only_policy
require_line "$mcuboot_config" 'CONFIG_MCUBOOT_DOWNGRADE_PREVENTION=y' downgrade_prevention
require_sha256 \
    "$build_directory/partitions.yml" \
    b455f45133912e7d5e2d27a0cb40c018620bd101d164b14d8e805e0c1a0f30f9 \
    canonical_partition_map

if [ "$profile" = kernel-link-probe-0001 ] || \
   [ "$profile" = mic-port-link-probe-0001 ] || \
   [ "$profile" = codec-port-link-probe-0001 ]; then
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/button.c.obj" \
        gumi_button_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/capture.c.obj" \
        gumi_capture_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/feedback.c.obj" \
        gumi_feedback_object
    require_file "$build_directory/omi/zephyr/zephyr.map" linker_map
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_button_debouncer_init' \
        gumi_button_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_capture_supervisor_init' \
        gumi_capture_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_feedback_decide' \
        gumi_feedback_link_input
fi
if [ "$profile" = mic-port-link-probe-0001 ] || \
   [ "$profile" = codec-port-link-probe-0001 ]; then
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/mic_port.c.obj" \
        gumi_mic_port_object
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_mic_init' \
        gumi_mic_port_link_input
fi
if [ "$profile" = codec-port-link-probe-0001 ]; then
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/codec_port.c.obj" \
        gumi_codec_port_object
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_codec_init' \
        gumi_codec_port_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        'opus_encoder_init' \
        gumi_codec_opus_link_input
fi

echo "verifying=mcuboot_signature"
/ncs/bootloader/mcuboot/scripts/imgtool.py verify \
    -k "$source_repo/omi/firmware/bootloader/mcuboot/root-rsa-2048.pem" \
    "$application"

wc -c "$build_directory/omi/zephyr/zephyr.bin" "$application"
sha256sum "$build_directory/omi/zephyr/zephyr.bin" "$application"
echo "verification_result=pass"
if [ "$profile" = kernel-link-probe-0001 ] || \
   [ "$profile" = mic-port-link-probe-0001 ] || \
   [ "$profile" = codec-port-link-probe-0001 ]; then
    echo "behavioral_candidate=false"
    echo "physical_use_forbidden=true"
fi
