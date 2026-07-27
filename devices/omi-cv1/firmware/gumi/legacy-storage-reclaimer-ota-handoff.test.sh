#!/bin/sh
set -eu

firmware_root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
v1_port="$firmware_root/zephyr/omi-v3012/src/legacy_storage_reclaimer_port.c"
v2_port="$firmware_root/zephyr/omi-v3012/src/legacy_storage_reclaimer_port_v0002.c"
v2_config="$firmware_root/zephyr/omi-v3012/legacy-storage-reclaimer-0002.conf"
repository_root=$(CDPATH='' cd -- "$firmware_root/../../../.." && pwd)

grep -F 'cleanup_error = gpio_pin_set_dt(&gumi_sd_enable, 0);' \
    "$v1_port" >/dev/null
grep -F 'cleanup_error = gpio_pin_set_dt(&gumi_sd_enable, 1);' \
    "$v2_port" >/dev/null

if grep -F 'gpio_pin_set_dt(&gumi_sd_enable, 0)' "$v2_port" >/dev/null; then
    echo "v0002 must not power down the SD regulator before MCUboot handoff" >&2
    exit 1
fi

grep -F 'The SD card and MCUboot secondary SPI NOR share SPI3.' \
    "$v2_port" >/dev/null
grep -F 'PM_DEVICE_ACTION_SUSPEND' "$v2_port" >/dev/null
grep -F 'CONFIG_BT_DIS_SW_REV_STR="gumi-legacy-storage-reclaimer-0002"' \
    "$v2_config" >/dev/null
grep -F 'CONFIG_FILE_SYSTEM_MKFS=n' "$v2_config" >/dev/null

grep -F 'legacy-storage-reclaimer-0002)' \
    "$repository_root/devices/omi-cv1/firmware/scripts/materialize.sh" >/dev/null
grep -F 'legacy-storage-reclaimer-0002)' \
    "$repository_root/devices/omi-cv1/firmware/scripts/build-application.sh" >/dev/null
grep -F 'legacy-storage-reclaimer-0002)' \
    "$repository_root/devices/omi-cv1/firmware/scripts/verify-build-output.sh" >/dev/null

echo "legacy storage reclaimer OTA handoff contract: pass"
