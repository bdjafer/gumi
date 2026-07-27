#!/bin/sh
set -eu

if [ "$#" -ne 8 ]; then
    echo "usage: $0 /path/to/recovery-only.bin /path/to/capture-selftest.bin /path/to/provisioner.bin /path/to/reclaimer.bin /path/to/functional-v0006.bin /path/to/functional-v0007.bin /path/to/stock.bin /path/to/stock-ota.zip" >&2
    exit 64
fi

recovery_only=$1
capture_selftest=$2
recording_root_provisioner=$3
legacy_storage_reclaimer=$4
functional_v0006=$5
functional_v0007=$6
stock=$7
stock_ota=$8

verify() {
    file=$1
    expected_size=$2
    expected_sha=$3
    label=$4

    if [ ! -f "$file" ]; then
        echo "flash-lab verification failed: missing $label artifact: $file" >&2
        exit 1
    fi
    actual_size=$(wc -c < "$file" | tr -d ' ')
    if [ "$actual_size" != "$expected_size" ]; then
        echo "flash-lab verification failed: $label size mismatch" >&2
        exit 1
    fi
    actual_sha=$(shasum -a 256 "$file" | awk '{ print $1 }')
    if [ "$actual_sha" != "$expected_sha" ]; then
        echo "flash-lab verification failed: $label SHA-256 mismatch" >&2
        exit 1
    fi
    echo "verified=$label"
}

verify \
    "$recovery_only" \
    106936 \
    d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc \
    recovery_only_application_image_0
verify \
    "$capture_selftest" \
    178100 \
    8f0d0fc35c4d3c56e8f94d528a0b56c0d4af7b44b7bdad2b2fa4f2963e2e3d0e \
    capture_port_selftest_application_image_0
verify \
    "$recording_root_provisioner" \
    113428 \
    e53c58a3ef72773af501a9ae05ceb5ce715b6d8153923ef52609dc5ec1dfa38b \
    recording_root_provisioner_application_image_0
verify \
    "$legacy_storage_reclaimer" \
    114448 \
    59fb753783c51140f4ee22c9d18dcaaf40bcb240e69c93a3cb2e967999fb1960 \
    legacy_storage_reclaimer_application_image_0
verify \
    "$functional_v0006" \
    221576 \
    eb811b62fffbf5c5f3ca1684b29073f6cf9cb49ae8e38d5201944c6c36d640a0 \
    functional_recording_v0006_application_image_0
verify \
    "$functional_v0007" \
    221592 \
    a0d292117b0f2455fc342a2ad39e2b9ce02054e096689018184065df91933b25 \
    functional_recording_v0007_application_image_0
verify \
    "$stock" \
    228632 \
    877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db \
    stock_recovery_application_image_0

verify \
    "$stock_ota" \
    404954 \
    821ce06d73f8bb3695de70dce0880a00597dd71175a843d08e577d775125ab4e \
    official_stock_v3012_ota_archive

stock_ota_entries=$(unzip -Z1 "$stock_ota" | LC_ALL=C sort)
expected_stock_ota_entries='ipc_radio.bin
manifest.json
omi.signed.bin'
if [ "$stock_ota_entries" != "$expected_stock_ota_entries" ]; then
    echo "flash-lab verification failed: official stock OTA entry set mismatch" >&2
    exit 1
fi

stock_ota_application_sha=$(
    unzip -p "$stock_ota" omi.signed.bin | shasum -a 256 | awk '{ print $1 }'
)
[ "$stock_ota_application_sha" = 877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db ] || {
    echo "flash-lab verification failed: stock OTA application SHA-256 mismatch" >&2
    exit 1
}

stock_ota_network_size=$(unzip -p "$stock_ota" ipc_radio.bin | wc -c | tr -d ' ')
[ "$stock_ota_network_size" = 175092 ] || {
    echo "flash-lab verification failed: stock OTA network size mismatch" >&2
    exit 1
}
stock_ota_network_sha=$(
    unzip -p "$stock_ota" ipc_radio.bin | shasum -a 256 | awk '{ print $1 }'
)
[ "$stock_ota_network_sha" = 0e1d067444ca78dd4ea64cb355bc167c1155b84f49b0c7028112c9930602d2a5 ] || {
    echo "flash-lab verification failed: stock OTA network SHA-256 mismatch" >&2
    exit 1
}
echo "verified=stock_normalization_network_image_1"
