#!/bin/sh
# gumi-shell-test: explicit-arguments
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 /path/to/flash-lab.apk" >&2
    exit 64
fi

apk=$1
[ -f "$apk" ] || {
    echo "flash-lab APK is missing: $apk" >&2
    exit 1
}

asset_list=$(unzip -Z1 "$apk" | awk '/^assets\/firmware\//' | LC_ALL=C sort)
expected_assets='assets/firmware/capture-port-selftest-0001-application-image-0.bin
assets/firmware/functional-recording-0006-application-image-0.bin
assets/firmware/functional-recording-0007-application-image-0.bin
assets/firmware/legacy-storage-reclaimer-0002-application-image-0.bin
assets/firmware/recording-root-provisioner-0001-application-image-0.bin
assets/firmware/recovery-only-0001-application-image-0.bin
assets/firmware/stock-v3.0.12-application-image-0.bin
assets/firmware/stock-v3.0.12-network-image-1.bin'
if [ "$asset_list" != "$expected_assets" ]; then
    echo "flash-lab APK contains an unexpected firmware asset set" >&2
    printf 'expected:\n%s\nactual:\n%s\n' "$expected_assets" "$asset_list" >&2
    exit 1
fi

recovery_only_sha=$(
    unzip -p "$apk" assets/firmware/recovery-only-0001-application-image-0.bin |
        shasum -a 256 | awk '{ print $1 }'
)
stock_sha=$(
    unzip -p "$apk" assets/firmware/stock-v3.0.12-application-image-0.bin |
        shasum -a 256 | awk '{ print $1 }'
)
stock_network_sha=$(
    unzip -p "$apk" assets/firmware/stock-v3.0.12-network-image-1.bin |
        shasum -a 256 | awk '{ print $1 }'
)
capture_selftest_sha=$(
    unzip -p "$apk" assets/firmware/capture-port-selftest-0001-application-image-0.bin |
        shasum -a 256 | awk '{ print $1 }'
)
functional_recording_0006_sha=$(
    unzip -p "$apk" assets/firmware/functional-recording-0006-application-image-0.bin |
        shasum -a 256 | awk '{ print $1 }'
)
functional_recording_0007_sha=$(
    unzip -p "$apk" assets/firmware/functional-recording-0007-application-image-0.bin |
        shasum -a 256 | awk '{ print $1 }'
)
legacy_storage_reclaimer_sha=$(
    unzip -p "$apk" assets/firmware/legacy-storage-reclaimer-0002-application-image-0.bin |
        shasum -a 256 | awk '{ print $1 }'
)
recording_root_provisioner_sha=$(
    unzip -p "$apk" assets/firmware/recording-root-provisioner-0001-application-image-0.bin |
        shasum -a 256 | awk '{ print $1 }'
)
[ "$recovery_only_sha" = d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc ] || {
    echo "packaged recovery-only SHA-256 mismatch" >&2
    exit 1
}
[ "$stock_sha" = 877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db ] || {
    echo "packaged stock recovery SHA-256 mismatch" >&2
    exit 1
}
[ "$stock_network_sha" = 0e1d067444ca78dd4ea64cb355bc167c1155b84f49b0c7028112c9930602d2a5 ] || {
    echo "packaged stock normalization network SHA-256 mismatch" >&2
    exit 1
}
[ "$capture_selftest_sha" = 8f0d0fc35c4d3c56e8f94d528a0b56c0d4af7b44b7bdad2b2fa4f2963e2e3d0e ] || {
    echo "packaged capture-port self-test SHA-256 mismatch" >&2
    exit 1
}
[ "$recording_root_provisioner_sha" = e53c58a3ef72773af501a9ae05ceb5ce715b6d8153923ef52609dc5ec1dfa38b ] || {
    echo "packaged recording-root provisioner SHA-256 mismatch" >&2
    exit 1
}
[ "$legacy_storage_reclaimer_sha" = 59fb753783c51140f4ee22c9d18dcaaf40bcb240e69c93a3cb2e967999fb1960 ] || {
    echo "packaged legacy-storage-reclaimer SHA-256 mismatch" >&2
    exit 1
}
[ "$functional_recording_0006_sha" = eb811b62fffbf5c5f3ca1684b29073f6cf9cb49ae8e38d5201944c6c36d640a0 ] || {
    echo "packaged functional-recording v0006 SHA-256 mismatch" >&2
    exit 1
}
[ "$functional_recording_0007_sha" = a0d292117b0f2455fc342a2ad39e2b9ce02054e096689018184065df91933b25 ] || {
    echo "packaged functional-recording v0007 SHA-256 mismatch" >&2
    exit 1
}

android_sdk=${ANDROID_SDK_ROOT:-}
aapt="$android_sdk/build-tools/36.0.0/aapt"
[ -x "$aapt" ] || {
    echo "Android aapt is unavailable at the pinned SDK path: $aapt" >&2
    exit 1
}

badging=$($aapt dump badging "$apk")
permissions=$($aapt dump permissions "$apk")
printf '%s\n' "$badging" | grep -F "package: name='dev.gumi.omicv1.flashlab'" >/dev/null
printf '%s\n' "$badging" | grep -F "versionCode='13'" >/dev/null
printf '%s\n' "$badging" |
    grep -F "versionName='0.13.0-ota-handoff-repair'" >/dev/null
printf '%s\n' "$badging" | grep -F "targetSdkVersion:'36'" >/dev/null
printf '%s\n' "$permissions" | grep -F "android.permission.BLUETOOTH_SCAN" >/dev/null
printf '%s\n' "$permissions" | grep -F "android.permission.BLUETOOTH_CONNECT" >/dev/null

if printf '%s\n' "$permissions" | rg -q \
    'android\.permission\.(INTERNET|REQUEST_INSTALL_PACKAGES|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE)'; then
    echo "flash-lab APK contains a forbidden permission" >&2
    exit 1
fi

if unzip -Z1 "$apk" | rg -qi 'ipc_radio|dfu_application|\.zip$|\.hex$'; then
    echo "flash-lab APK contains a forbidden package, ZIP, HEX, or unreviewed network artifact" >&2
    exit 1
fi

echo "PASS: exact reviewed image-0 plus stock-normalization image-1 APK boundary"
