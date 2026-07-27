#!/bin/sh
set -eu

if [ "$#" -ne 3 ]; then
    echo "usage: $0 /ncs/build-directory /materialized/omi profile" >&2
    exit 64
fi

build_directory=$1
source_repo=$2
profile=$3
functional_profile=false
provisioner_profile=false
reclaimer_profile=false

case "$profile" in
    canary-0001)
        expected_sw_revision='gumi-canary-0001'
        ;;
    recovery-only-0001)
        expected_sw_revision='gumi-recovery-only-0001'
        ;;
    capture-port-selftest-0001)
        expected_sw_revision='gumi-capture-port-selftest-0001'
        ;;
    functional-recording-0001)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0001'
        ;;
    functional-recording-0002)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0002'
        ;;
    functional-recording-0003)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0003'
        ;;
    functional-recording-0004)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0004'
        ;;
    functional-recording-0005)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0005'
        ;;
    functional-recording-0006)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0006'
        ;;
    functional-recording-0007)
        functional_profile=true
        expected_sw_revision='gumi-functional-recording-0007'
        ;;
    recording-root-provisioner-0001)
        provisioner_profile=true
        expected_sw_revision='gumi-recording-root-provisioner-0001'
        ;;
    legacy-storage-reclaimer-0001)
        reclaimer_profile=true
        expected_sw_revision='gumi-legacy-storage-reclaimer-0001'
        ;;
    legacy-storage-reclaimer-0002)
        reclaimer_profile=true
        expected_sw_revision='gumi-legacy-storage-reclaimer-0002'
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

require_not_contains() {
    file=$1
    text=$2
    label=$3
    if grep -Fq "$text" "$file"; then
        echo "verification failed: forbidden $label is present" >&2
        exit 1
    fi
    echo "verified_absent=$label"
}

require_binary_hex() {
    file=$1
    expected_hex=$2
    label=$3
    if ! od -An -tx1 -v "$file" | tr -d ' \n' | grep -Fq "$expected_hex"; then
        echo "verification failed: $label byte sequence is absent" >&2
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
if [ "$profile" = recovery-only-0001 ]; then
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/recovery.c.obj" \
        gumi_recovery_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/mic_port.c.obj" \
        gumi_recovery_mic_port_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recovery_port.c.obj" \
        gumi_recovery_transport_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recovery_only_main.c.obj" \
        gumi_recovery_main_object
    require_file "$build_directory/omi/zephyr/zephyr.map" linker_map
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_recovery_supervisor_init' \
        gumi_recovery_supervisor_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_recovery_transport_start' \
        gumi_recovery_transport_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.admit_application_image_only' \
        application_image_only_admission_hook
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_mic_init' \
        gumi_recovery_mic_off_link_input
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '/src/lib/core/transport.c.obj' \
        stock_functional_transport_object
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '/src/lib/core/codec.c.obj' \
        stock_codec_object
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '/src/mic.c.obj' \
        stock_microphone_object
    require_line "$application_config" 'CONFIG_AUDIO_DMIC=y' microphone_driver
    require_line "$application_config" 'CONFIG_AUDIO_DMIC_NRFX_PDM=y' pdm_driver
    require_line \
        "$application_config" \
        '# CONFIG_NCS_SAMPLE_MCUMGR_BT_OTA_DFU is not set' \
        no_destructive_dfu_convenience_bundle
    require_line "$application_config" 'CONFIG_MCUMGR=y' recovery_manager
    require_line "$application_config" 'CONFIG_MCUMGR_TRANSPORT_BT=y' recovery_dfu
    require_line "$application_config" 'CONFIG_MCUMGR_TRANSPORT_BT_REASSEMBLY=y' recovery_reassembly
    require_line "$application_config" 'CONFIG_MCUMGR_TRANSPORT_NETBUF_SIZE=2475' recovery_buffer
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_IMG=y' image_management_group
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_OS=y' reset_management_group
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_OS_MCUMGR_PARAMS=y' transport_parameters
    require_line "$application_config" '# CONFIG_BT_SMP is not set' no_pairing_dependency
    require_line "$application_config" 'CONFIG_MCUMGR_TRANSPORT_BT_PERM_RW=y' reachable_recovery_transport
    require_line "$application_config" 'CONFIG_MCUMGR_MGMT_NOTIFICATION_HOOKS=y' update_admission_hooks
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_IMG_UPLOAD_CHECK_HOOK=y' image_upload_check_hook
    require_line "$application_config" 'CONFIG_IMG_ENABLE_IMAGE_CHECK=y' uploaded_image_integrity_check
    require_line \
        "$application_config" \
        'CONFIG_MCUMGR_GRP_IMG_TOO_LARGE_SYSBUILD=y' \
        mcuboot_size_bound
    require_line "$application_config" 'CONFIG_SETTINGS=y' settings_partition_preserved
    require_line "$application_config" 'CONFIG_SETTINGS_NVS=y' canonical_settings_backend
    require_line "$application_config" '# CONFIG_SETTINGS_RUNTIME is not set' no_runtime_settings_access
    require_line "$application_config" '# CONFIG_MCUMGR_GRP_ZBASIC is not set' no_basic_management_group
    require_not_contains \
        "$application_config" \
        'CONFIG_MCUMGR_GRP_ZBASIC_STORAGE_ERASE=y' \
        destructive_storage_erase_configuration
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        'storage_erase_handler' \
        destructive_storage_erase_handler
    require_line "$application_config" '# CONFIG_MCUMGR_GRP_OS_ECHO is not set' no_os_echo
    require_line \
        "$application_config" \
        '# CONFIG_MCUMGR_GRP_OS_BOOTLOADER_INFO is not set' \
        no_bootloader_info_handler
    require_line "$application_config" '# CONFIG_BT_BAS is not set' no_battery_service
    require_line "$application_config" '# CONFIG_FILE_SYSTEM is not set' no_file_system
    require_line "$application_config" '# CONFIG_OMI_CODEC_OPUS is not set' no_codec
    require_line \
        "$application_config" \
        '# CONFIG_OMI_ENABLE_OFFLINE_STORAGE is not set' \
        no_offline_storage
    require_line "$application_config" '# CONFIG_OMI_ENABLE_BUTTON is not set' no_button
    require_line "$application_config" '# CONFIG_OMI_ENABLE_HAPTIC is not set' no_haptic
    require_binary_hex \
        "$application" \
        '4ad7ce6f59f53baf63409d8f85046e79' \
        gumi_recovery_service_uuid
    require_binary_hex \
        "$application" \
        '6c246601af3b87a8264c0b66a7b4fc32' \
        gumi_recovery_status_uuid
    require_binary_hex \
        "$application" \
        '14128a7604d16c4f7e53f2e80000b119' \
        stock_family_discriminator_uuid
fi
if [ "$provisioner_profile" = true ]; then
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/recovery.c.obj" \
        gumi_provisioner_recovery_object
    for object in \
        mic_port \
        recovery_port \
        recording_root_provisioner_port \
        recording_root_provisioner_main; do
        require_file \
            "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/$object.c.obj" \
            "gumi_${object}_object"
    done
    require_file "$build_directory/omi/zephyr/zephyr.map" linker_map
    for symbol in \
        gumi_recovery_supervisor_init \
        gumi_omi_v3012_recovery_transport_start \
        gumi_omi_v3012_mic_init \
        gumi_omi_v3012_recording_root_status_publish \
        gumi_omi_v3012_recording_root_mgmt_guard_start \
        gumi_omi_v3012_recording_root_mutation_admission_set \
        deny_mutation_until_terminal \
        psa_generate_random \
        hw_unique_key_is_written \
        hw_unique_key_write \
        hw_unique_key_derive_key \
        mbedtls_platform_zeroize \
        task_wdt_init \
        task_wdt_add \
        task_wdt_feed \
        nrfx_wdt_enable \
        sys_reboot; do
        require_contains \
            "$build_directory/omi/zephyr/zephyr.map" \
            "$symbol" \
            "recording_root_provisioner_$symbol"
    done
    require_contains \
        "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/recording_root_provisioner_main.c" \
        'HUK_KEYSLOT_MEXT' \
        mext_only_recording_root_slot
    require_not_contains \
        "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/recording_root_provisioner_main.c" \
        'HUK_KEYSLOT_MKEK' \
        mkek_slot_access
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        'hw_unique_key_write_random' \
        all_slot_random_huk_writer
    for stock_object in \
        '/src/main.c.obj' \
        '/src/mic.c.obj' \
        '/src/sd_card.c.obj' \
        '/src/settings.c.obj' \
        '/src/lib/core/button.c.obj' \
        '/src/lib/core/codec.c.obj' \
        '/src/lib/core/storage.c.obj' \
        '/src/lib/core/transport.c.obj'; do
        require_not_contains \
            "$build_directory/omi/zephyr/zephyr.map" \
            "$stock_object" \
            "stock_object_$stock_object"
    done
    require_line "$application_config" 'CONFIG_NRF_SECURITY=y' psa_crypto_provider
    require_line "$application_config" 'CONFIG_MBEDTLS_PSA_CRYPTO_C=y' psa_crypto
    require_line "$application_config" 'CONFIG_PSA_WANT_GENERATE_RANDOM=y' psa_random_generation
    require_line "$application_config" 'CONFIG_HW_UNIQUE_KEY=y' hardware_unique_key
    require_line \
        "$application_config" \
        '# CONFIG_HW_UNIQUE_KEY_WRITE_ON_CRYPTO_INIT is not set' \
        no_automatic_huk_write
    require_line \
        "$application_config" \
        '# CONFIG_HW_UNIQUE_KEY_RANDOM is not set' \
        no_all_slot_random_huk_write
    require_line "$application_config" 'CONFIG_AUDIO_DMIC=y' microphone_off_proof_driver
    require_line "$application_config" 'CONFIG_AUDIO_DMIC_NRFX_PDM=y' microphone_off_proof_pdm
    require_line "$application_config" 'CONFIG_WATCHDOG=y' watchdog_driver
    require_line "$application_config" 'CONFIG_WDT_NRFX=y' nrf5340_watchdog_driver
    require_line "$application_config" 'CONFIG_TASK_WDT=y' task_watchdog
    require_line "$application_config" 'CONFIG_TASK_WDT_CHANNELS=2' bounded_watchdog_channels
    require_line "$application_config" 'CONFIG_TASK_WDT_HW_FALLBACK=y' hardware_watchdog_fallback
    require_line "$application_config" 'CONFIG_TASK_WDT_MIN_TIMEOUT=1000' hardware_watchdog_feed_period
    require_line \
        "$application_config" \
        'CONFIG_TASK_WDT_HW_FALLBACK_DELAY=1000' \
        hardware_watchdog_reset_margin
    require_line \
        "$application_config" \
        'CONFIG_FLASH_AREA_CHECK_INTEGRITY_PSA=y' \
        initialized_psa_dfu_integrity
    require_line \
        "$application_config" \
        'CONFIG_MCUMGR_TRANSPORT_WORKQUEUE_STACK_SIZE=4096' \
        psa_dfu_workqueue_headroom
    require_line "$application_config" 'CONFIG_MCUMGR=y' recovery_manager
    require_line "$application_config" 'CONFIG_MCUMGR_TRANSPORT_BT=y' recovery_dfu
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_IMG=y' image_management_group
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_OS=y' reset_management_group
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_OS_RESET_HOOK=y' guarded_reset_hook
    require_line "$application_config" '# CONFIG_BT_SMP is not set' no_pairing_dependency
    require_line "$application_config" 'CONFIG_MCUMGR_TRANSPORT_BT_PERM_RW=y' reachable_recovery_transport
    require_line "$application_config" 'CONFIG_MCUMGR_MGMT_NOTIFICATION_HOOKS=y' update_admission_hooks
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_IMG_UPLOAD_CHECK_HOOK=y' image_upload_check_hook
    require_line "$application_config" '# CONFIG_MCUMGR_GRP_ZBASIC is not set' no_basic_group
    require_line "$application_config" '# CONFIG_FILE_SYSTEM is not set' no_file_system
    require_line "$application_config" '# CONFIG_OMI_CODEC_OPUS is not set' no_codec
    require_line \
        "$application_config" \
        '# CONFIG_OMI_ENABLE_OFFLINE_STORAGE is not set' \
        no_offline_storage
    require_line "$application_config" '# CONFIG_OMI_ENABLE_BUTTON is not set' no_button
    require_line "$application_config" '# CONFIG_OMI_ENABLE_HAPTIC is not set' no_haptic
    require_binary_hex \
        "$application" \
        '0100003156432d494d4f1000494d5547' \
        recording_root_provisioner_service_uuid
    require_binary_hex \
        "$application" \
        '0200003156432d494d4f1000494d5547' \
        recording_root_provisioner_status_uuid
    require_binary_hex \
        "$application" \
        '14128a7604d16c4f7e53f2e80000b119' \
        stock_family_discriminator_uuid
fi
if [ "$reclaimer_profile" = true ]; then
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/legacy_storage_reclaimer.c.obj" \
        gumi_legacy_storage_reclaimer_policy_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/recovery.c.obj" \
        gumi_reclaimer_recovery_object
    for object in \
        functional_reset_port \
        legacy_storage_reclaimer_main \
        legacy_storage_reclaimer_port \
        legacy_storage_reclaimer_transport \
        mic_port \
        recording_storage_pm_policy \
        recovery_port; do
        require_file \
            "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/$object.c.obj" \
            "gumi_${object}_object"
    done
    require_file "$build_directory/omi/zephyr/zephyr.map" linker_map
    for symbol in \
        gumi_legacy_storage_reclaimer_decide \
        gumi_omi_v3012_legacy_storage_reclaim \
        gumi_omi_v3012_legacy_reclaimer_status_publish \
        gumi_omi_v3012_legacy_reclaimer_mgmt_guard_start \
        gumi_omi_v3012_legacy_reclaimer_mutation_admission_set \
        gumi_omi_v3012_recovery_transport_start \
        gumi_omi_v3012_mic_init \
        gumi_omi_v3012_network_core_cold_start \
        gumi_omi_v3012_whole_device_reboot \
        fs_stat \
        fs_statvfs \
        fs_unlink \
        fs_unmount \
        deny_mutation_until_terminal \
        task_wdt_init \
        task_wdt_add \
        task_wdt_feed; do
        require_contains \
            "$build_directory/omi/zephyr/zephyr.map" \
            "$symbol" \
            "legacy_storage_reclaimer_$symbol"
    done
    require_contains \
        "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/legacy_storage_reclaimer_port.c" \
        '#define GUMI_LEGACY_STORAGE_TARGET "/SD:/audio/a01.txt"' \
        exact_compiled_legacy_target
    require_contains \
        "$source_repo/omi/firmware/omi/src/gumi/include/gumi/omi_v3012_legacy_storage_reclaimer.h" \
        'GUMI_OMI_V3012_LEGACY_TARGET_SIZE_BYTES UINT64_C(505118720)' \
        exact_qualified_legacy_target_size
    require_contains \
        "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/legacy_storage_reclaimer_port.c" \
        'FS_MOUNT_FLAG_NO_FORMAT | FS_MOUNT_FLAG_USE_DISK_ACCESS' \
        no_format_mount_contract
    for stock_object in \
        '/src/main.c.obj' \
        '/src/mic.c.obj' \
        '/src/sd_card.c.obj' \
        '/src/settings.c.obj' \
        '/src/lib/core/button.c.obj' \
        '/src/lib/core/codec.c.obj' \
        '/src/lib/core/storage.c.obj' \
        '/src/lib/core/transport.c.obj'; do
        require_not_contains \
            "$build_directory/omi/zephyr/zephyr.map" \
            "$stock_object" \
            "stock_object_$stock_object"
    done
    require_line "$application_config" 'CONFIG_FILE_SYSTEM=y' file_system
    require_line "$application_config" 'CONFIG_FAT_FILESYSTEM_ELM=y' fat_file_system
    require_line "$application_config" '# CONFIG_FILE_SYSTEM_MKFS is not set' no_generic_formatter
    require_line "$application_config" '# CONFIG_FS_FATFS_MKFS is not set' no_fat_formatter
    require_line "$application_config" '# CONFIG_FS_FATFS_MOUNT_MKFS is not set' no_mount_formatter
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        'fs_mkfs' \
        filesystem_formatter
    require_line "$application_config" 'CONFIG_AUDIO_DMIC=y' microphone_off_proof_driver
    require_line "$application_config" 'CONFIG_AUDIO_DMIC_NRFX_PDM=y' microphone_off_proof_pdm
    require_line "$application_config" 'CONFIG_WATCHDOG=y' watchdog_driver
    require_line "$application_config" 'CONFIG_TASK_WDT=y' task_watchdog
    require_line "$application_config" 'CONFIG_MCUMGR=y' recovery_manager
    require_line "$application_config" 'CONFIG_MCUMGR_TRANSPORT_BT=y' recovery_dfu
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_IMG=y' image_management_group
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_OS=y' reset_management_group
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_OS_RESET_HOOK=y' guarded_reset_hook
    require_line "$application_config" '# CONFIG_BT_SMP is not set' no_pairing_dependency
    require_line "$application_config" '# CONFIG_OMI_CODEC_OPUS is not set' no_codec
    require_line "$application_config" '# CONFIG_OMI_ENABLE_BUTTON is not set' no_button
    require_line "$application_config" '# CONFIG_OMI_ENABLE_HAPTIC is not set' no_haptic
    require_binary_hex \
        "$application" \
        '0100003156432d494d4f1100494d5547' \
        legacy_reclaimer_service_uuid
    require_binary_hex \
        "$application" \
        '0200003156432d494d4f1100494d5547' \
        legacy_reclaimer_status_uuid
    require_binary_hex \
        "$application" \
        '14128a7604d16c4f7e53f2e80000b119' \
        stock_family_discriminator_uuid
fi
if [ "$profile" = capture-port-selftest-0001 ]; then
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/button.c.obj" \
        gumi_button_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/capture_selftest.c.obj" \
        gumi_capture_selftest_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/mic_port.c.obj" \
        gumi_mic_port_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/codec_port.c.obj" \
        gumi_codec_port_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/capture_selftest_io.c.obj" \
        gumi_capture_selftest_io_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/capture_selftest_transport.c.obj" \
        gumi_capture_selftest_transport_object
    require_file \
        "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/capture_selftest_main.c.obj" \
        gumi_capture_selftest_main_object
    require_file "$build_directory/omi/zephyr/zephyr.map" linker_map
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_capture_selftest_init' \
        gumi_capture_selftest_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_button_gesture_advance_to' \
        gumi_button_confirmation_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_mic_acquire' \
        gumi_mic_acquire_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_mic_release' \
        gumi_mic_release_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_codec_open' \
        gumi_codec_open_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_codec_close' \
        gumi_codec_close_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        'opus_encoder_init' \
        bundled_opus_link_input
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.admit_application_image_only' \
        application_image_only_admission_hook
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '/src/lib/core/transport.c.obj' \
        stock_functional_transport_object
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '/src/lib/core/codec.c.obj' \
        stock_codec_object
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '/src/lib/core/button.c.obj' \
        stock_button_object
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '/src/mic.c.obj' \
        stock_microphone_object
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '/src/sd_card.c.obj' \
        stock_sd_object
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '/src/settings.c.obj' \
        stock_settings_object
    require_line "$application_config" 'CONFIG_AUDIO_DMIC=y' microphone_driver
    require_line "$application_config" 'CONFIG_AUDIO_DMIC_NRFX_PDM=y' pdm_driver
    require_line "$application_config" 'CONFIG_PWM=y' privacy_pwm_driver
    require_line "$application_config" 'CONFIG_OMI_CODEC_OPUS=y' opus_codec
    require_line "$application_config" '# CONFIG_FILE_SYSTEM is not set' no_file_system
    require_line \
        "$application_config" \
        '# CONFIG_OMI_ENABLE_OFFLINE_STORAGE is not set' \
        no_offline_storage
    require_line "$application_config" '# CONFIG_OMI_ENABLE_BUTTON is not set' no_stock_button
    require_line "$application_config" '# CONFIG_OMI_ENABLE_HAPTIC is not set' no_haptic
    require_line \
        "$application_config" \
        '# CONFIG_NCS_SAMPLE_MCUMGR_BT_OTA_DFU is not set' \
        no_destructive_dfu_convenience_bundle
    require_line "$application_config" 'CONFIG_MCUMGR=y' recovery_manager
    require_line "$application_config" 'CONFIG_MCUMGR_TRANSPORT_BT=y' recovery_dfu
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_IMG=y' image_management_group
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_OS=y' reset_management_group
    require_line "$application_config" '# CONFIG_BT_SMP is not set' no_pairing_dependency
    require_line "$application_config" 'CONFIG_MCUMGR_MGMT_NOTIFICATION_HOOKS=y' update_hooks
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_IMG_UPLOAD_CHECK_HOOK=y' image_zero_hook
    require_line "$application_config" '# CONFIG_MCUMGR_GRP_ZBASIC is not set' no_basic_group
    require_not_contains \
        "$application_config" \
        'CONFIG_MCUMGR_GRP_ZBASIC_STORAGE_ERASE=y' \
        destructive_storage_erase_configuration
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        'storage_erase_handler' \
        destructive_storage_erase_handler
    require_binary_hex \
        "$application" \
        '0170522c5e5fe4938a4e3f3b606e0af8' \
        capture_selftest_service_uuid
    require_binary_hex \
        "$application" \
        '0170522c5e5fe4938a4e3f3b616e0af8' \
        capture_selftest_status_uuid
    require_binary_hex \
        "$application" \
        '0170522c5e5fe4938a4e3f3b626e0af8' \
        capture_selftest_arm_uuid
    require_binary_hex \
        "$application" \
        '14128a7604d16c4f7e53f2e80000b119' \
        stock_family_discriminator_uuid
fi
if [ "$functional_profile" = true ]; then
    for object in \
        button \
        capture \
        feedback \
        recording_journal \
        recording_store; do
        require_file \
            "$build_directory/omi/CMakeFiles/app.dir/src/gumi/$object.c.obj" \
            "gumi_${object}_object"
    done
    for object in \
        codec_port \
        crypto_port \
        functional_io \
        functional_main \
        functional_transport \
        mic_port \
        recording_key_port; do
        require_file \
            "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/$object.c.obj" \
            "gumi_${object}_object"
    done
    if [ "$profile" = functional-recording-0007 ]; then
        require_file \
            "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recording_storage_port_v0007.c.obj" \
            gumi_recording_storage_port_v0007_object
        require_contains \
            "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/recording_storage_port_v0007.c" \
            'int error = refresh_free_bytes();' \
            pre_mkdir_free_space_refresh
        require_contains \
            "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/recording_storage_port_v0007.c" \
            '#define fs_mkdir gumi_v0007_capacity_checked_mkdir' \
            capacity_checked_mkdir_interposition
    else
        require_file \
            "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/recording_storage_port.c.obj" \
            gumi_recording_storage_port_object
    fi
    if [ "$profile" = functional-recording-0006 ] || \
       [ "$profile" = functional-recording-0007 ]; then
        require_file \
            "$build_directory/omi/CMakeFiles/app.dir/src/gumi/zephyr/omi-v3012/functional_reset_port.c.obj" \
            gumi_functional_reset_port_object
        require_contains \
            "$build_directory/omi/zephyr/zephyr.map" \
            '.text.gumi_omi_v3012_network_core_cold_start' \
            network_core_boot_cold_start
        require_contains \
            "$build_directory/omi/zephyr/zephyr.map" \
            '.text.gumi_omi_v3012_whole_device_reboot' \
            whole_device_reboot
        require_contains \
            "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/functional_reset_port.c" \
            'nrf_reset_network_force_off(NRF_RESET, force_off)' \
            network_core_force_off_primitive
    fi
    require_file "$build_directory/omi/zephyr/zephyr.map" linker_map
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_capture_supervisor_init' \
        functional_capture_supervisor
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_codec_close' \
        functional_codec_close_barrier
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_recording_storage_finalize' \
        functional_storage_finalize_barrier
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.bss.runtime_update_hold_tracking' \
        functional_runtime_recovery_hold_tracker
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.bss.runtime_update_hold_started_ms' \
        functional_runtime_recovery_hold_clock
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_crypto_unprotect' \
        retained_recording_authentication
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_omi_v3012_crypto_init' \
        independent_crypto_initialization
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_recording_journal_recovery_init' \
        retained_recording_header_validation
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_recording_journal_recovery_inspect_next' \
        retained_recording_structure_validation
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.gumi_recording_journal_recovery_accept_next' \
        retained_recording_authenticated_prefix
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        'fs_readdir' \
        retained_recording_directory_scan
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.admit_physically_confirmed_application_image' \
        physical_update_admission_hook
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        '.text.admit_physically_confirmed_reset' \
        physical_reset_admission_hook
    require_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        'opus_encoder_init' \
        bundled_opus_link_input
    for stock_object in \
        '/src/main.c.obj' \
        '/src/mic.c.obj' \
        '/src/sd_card.c.obj' \
        '/src/settings.c.obj' \
        '/src/lib/core/button.c.obj' \
        '/src/lib/core/codec.c.obj' \
        '/src/lib/core/storage.c.obj' \
        '/src/lib/core/transport.c.obj'; do
        require_not_contains \
            "$build_directory/omi/zephyr/zephyr.map" \
            "$stock_object" \
            "stock_object_$stock_object"
    done
    require_line "$application_config" 'CONFIG_AUDIO_DMIC=y' microphone_driver
    require_line "$application_config" 'CONFIG_AUDIO_DMIC_NRFX_PDM=y' pdm_driver
    require_line "$application_config" 'CONFIG_PWM=y' privacy_pwm_driver
    require_line "$application_config" 'CONFIG_OMI_CODEC_OPUS=y' opus_codec
    require_line "$application_config" 'CONFIG_FILE_SYSTEM=y' file_system
    require_line "$application_config" 'CONFIG_FAT_FILESYSTEM_ELM=y' fat_file_system
    require_line "$application_config" '# CONFIG_FILE_SYSTEM_MKFS is not set' no_generic_formatter
    require_line "$application_config" '# CONFIG_FS_FATFS_MKFS is not set' no_fat_formatter
    require_line "$application_config" '# CONFIG_FS_FATFS_MOUNT_MKFS is not set' no_mount_formatter
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        'fs_mkfs' \
        filesystem_formatter
    require_line "$application_config" 'CONFIG_HW_UNIQUE_KEY=y' existing_huk_support
    require_line \
        "$application_config" \
        '# CONFIG_HW_UNIQUE_KEY_WRITE_ON_CRYPTO_INIT is not set' \
        no_automatic_huk_write
    require_line \
        "$application_config" \
        '# CONFIG_HW_UNIQUE_KEY_RANDOM is not set' \
        no_random_huk_provisioning
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        'hw_unique_key_write_random' \
        huk_random_writer
    require_line \
        "$application_config" \
        '# CONFIG_NCS_SAMPLE_MCUMGR_BT_OTA_DFU is not set' \
        no_destructive_dfu_convenience_bundle
    require_line "$application_config" 'CONFIG_MCUMGR=y' recovery_manager
    require_line "$application_config" 'CONFIG_MCUMGR_TRANSPORT_BT=y' recovery_dfu
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_IMG=y' image_management_group
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_OS=y' reset_management_group
    if [ "$profile" = functional-recording-0003 ] || \
       [ "$profile" = functional-recording-0004 ] || \
       [ "$profile" = functional-recording-0005 ] || \
       [ "$profile" = functional-recording-0006 ] || \
       [ "$profile" = functional-recording-0007 ]; then
        require_line \
            "$application_config" \
            'CONFIG_MCUMGR_GRP_OS_RESET_HOOK=y' \
            guarded_reset_hook
    fi
    require_line "$application_config" '# CONFIG_BT_SMP is not set' no_pairing_dependency
    require_line "$application_config" 'CONFIG_MCUMGR_MGMT_NOTIFICATION_HOOKS=y' update_hooks
    require_line "$application_config" 'CONFIG_MCUMGR_GRP_IMG_UPLOAD_CHECK_HOOK=y' image_zero_hook
    require_line "$application_config" '# CONFIG_MCUMGR_GRP_ZBASIC is not set' no_basic_group
    if [ "$profile" = functional-recording-0002 ] || \
       [ "$profile" = functional-recording-0003 ] || \
       [ "$profile" = functional-recording-0004 ] || \
       [ "$profile" = functional-recording-0005 ] || \
       [ "$profile" = functional-recording-0006 ] || \
       [ "$profile" = functional-recording-0007 ]; then
        require_line \
            "$application_config" \
            'CONFIG_FLASH_AREA_CHECK_INTEGRITY_PSA=y' \
            initialized_psa_dfu_integrity
        require_line \
            "$application_config" \
            'CONFIG_MCUMGR_TRANSPORT_WORKQUEUE_STACK_SIZE=4096' \
            psa_dfu_workqueue_headroom
    fi
    if [ "$profile" = functional-recording-0003 ] || \
       [ "$profile" = functional-recording-0004 ] || \
       [ "$profile" = functional-recording-0005 ] || \
       [ "$profile" = functional-recording-0006 ] || \
       [ "$profile" = functional-recording-0007 ]; then
        for symbol in \
            task_wdt_init \
            task_wdt_add \
            task_wdt_feed \
            nrfx_wdt_enable \
            sys_reboot; do
            require_contains \
                "$build_directory/omi/zephyr/zephyr.map" \
                "$symbol" \
                "functional_lockup_recovery_$symbol"
        done
        require_contains \
            "$build_directory/omi/zephyr/zephyr.map" \
            '.bss.runtime_reset_hold_tracking' \
            functional_runtime_reset_hold_tracker
        require_contains \
            "$build_directory/omi/zephyr/zephyr.map" \
            '.bss.runtime_reset_hold_started_ms' \
            functional_runtime_reset_hold_clock
        require_line "$application_config" 'CONFIG_WATCHDOG=y' watchdog_driver
        require_line "$application_config" 'CONFIG_WDT_NRFX=y' nrf5340_watchdog_driver
        require_line "$application_config" 'CONFIG_TASK_WDT=y' task_watchdog
        require_line "$application_config" 'CONFIG_TASK_WDT_CHANNELS=2' bounded_watchdog_channels
        require_line "$application_config" 'CONFIG_TASK_WDT_HW_FALLBACK=y' hardware_watchdog_fallback
        require_line "$application_config" 'CONFIG_TASK_WDT_MIN_TIMEOUT=1000' hardware_watchdog_feed_period
        require_line \
            "$application_config" \
            'CONFIG_TASK_WDT_HW_FALLBACK_DELAY=1000' \
            hardware_watchdog_reset_margin
    fi
    if [ "$profile" = functional-recording-0004 ] || \
       [ "$profile" = functional-recording-0005 ] || \
       [ "$profile" = functional-recording-0006 ] || \
       [ "$profile" = functional-recording-0007 ]; then
        require_line \
            "$application_config" \
            'CONFIG_MBEDTLS_ENABLE_HEAP=y' \
            mbedtls_runtime_allocator
        require_line \
            "$application_config" \
            'CONFIG_MBEDTLS_HEAP_SIZE=4096' \
            bounded_mbedtls_heap
        for symbol in \
            mbedtls_heap_init \
            mbedtls_memory_buffer_alloc_init \
            mbedtls_platform_set_calloc_free; do
            require_contains \
                "$build_directory/omi/zephyr/zephyr.map" \
                "$symbol" \
                "mbedtls_allocator_$symbol"
        done
    fi
    if [ "$profile" = functional-recording-0005 ] || \
       [ "$profile" = functional-recording-0006 ] || \
       [ "$profile" = functional-recording-0007 ]; then
        require_contains \
            "$build_directory/omi/zephyr/zephyr.map" \
            'gumi_omi_v3012_recording_storage_normalize_resume_result' \
            storage_pm_resume_policy
        require_contains \
            "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/recording_storage_pm_policy.c" \
            'result == -EALREADY || result == -ENOSYS' \
            exact_nonfatal_storage_resume_outcomes
        require_not_contains \
            "$source_repo/omi/firmware/omi/src/gumi/zephyr/omi-v3012/recording_storage_pm_policy.c" \
            'result == -ENOTSUP' \
            unsupported_storage_resume_outcome
    fi
    require_not_contains \
        "$application_config" \
        'CONFIG_MCUMGR_GRP_ZBASIC_STORAGE_ERASE=y' \
        destructive_storage_erase_configuration
    require_not_contains \
        "$build_directory/omi/zephyr/zephyr.map" \
        'storage_erase_handler' \
        destructive_storage_erase_handler
    require_binary_hex \
        "$application" \
        '0100003156432d494d4f0100494d5547' \
        functional_service_uuid
    require_binary_hex \
        "$application" \
        '0100003156432d494d4f0200494d5547' \
        functional_status_uuid
    require_binary_hex \
        "$application" \
        '0100003156432d494d4f0300494d5547' \
        functional_capability_uuid
    require_binary_hex \
        "$application" \
        '14128a7604d16c4f7e53f2e80000b119' \
        stock_family_discriminator_uuid
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
if [ "$profile" = recovery-only-0001 ]; then
    echo "behavioral_candidate=offline-unqualified"
    echo "physical_use_forbidden=true"
fi
if [ "$profile" = capture-port-selftest-0001 ]; then
    echo "behavioral_candidate=offline-unqualified"
    echo "physical_use_forbidden=true"
fi
if [ "$functional_profile" = true ]; then
    echo "behavioral_candidate=offline-unqualified"
    echo "physical_use_forbidden=true"
fi
if [ "$provisioner_profile" = true ]; then
    echo "behavioral_candidate=offline-unqualified"
    echo "physical_use_forbidden=true"
fi
if [ "$reclaimer_profile" = true ]; then
    echo "behavioral_candidate=offline-unqualified"
    echo "destructive_scope=/SD:/audio/a01.txt"
    echo "format_capability=false"
    echo "physical_use_forbidden=true"
fi
