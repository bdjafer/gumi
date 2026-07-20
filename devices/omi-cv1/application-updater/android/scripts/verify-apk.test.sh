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

asset_list=$(unzip -Z1 "$apk" | awk '/^assets\/firmware\//')
expected_assets='assets/firmware/canary-0001-application-image-0.bin
assets/firmware/stock-v3.0.12-application-image-0.bin'
if [ "$asset_list" != "$expected_assets" ]; then
    echo "flash-lab APK contains an unexpected firmware asset set" >&2
    printf 'expected:\n%s\nactual:\n%s\n' "$expected_assets" "$asset_list" >&2
    exit 1
fi

canary_sha=$(
    unzip -p "$apk" assets/firmware/canary-0001-application-image-0.bin |
        shasum -a 256 | awk '{ print $1 }'
)
stock_sha=$(
    unzip -p "$apk" assets/firmware/stock-v3.0.12-application-image-0.bin |
        shasum -a 256 | awk '{ print $1 }'
)
[ "$canary_sha" = 65e4ae91637e6aa576f6d3f8286db33bc9bd36ece8ece1aaa6b6aa5c5b204f6d ] || {
    echo "packaged canary SHA-256 mismatch" >&2
    exit 1
}
[ "$stock_sha" = 877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db ] || {
    echo "packaged stock recovery SHA-256 mismatch" >&2
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
printf '%s\n' "$badging" | grep -F "targetSdkVersion:'36'" >/dev/null
printf '%s\n' "$permissions" | grep -F "android.permission.BLUETOOTH_SCAN" >/dev/null
printf '%s\n' "$permissions" | grep -F "android.permission.BLUETOOTH_CONNECT" >/dev/null

if printf '%s\n' "$permissions" | rg -q \
    'android\.permission\.(INTERNET|REQUEST_INSTALL_PACKAGES|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE)'; then
    echo "flash-lab APK contains a forbidden permission" >&2
    exit 1
fi

if unzip -Z1 "$apk" | rg -qi 'ipc_radio|dfu_application|network[^/]*\.bin|\.zip$|\.hex$'; then
    echo "flash-lab APK contains a forbidden network, package, ZIP, or HEX artifact" >&2
    exit 1
fi

echo "PASS: exact application-only flash-lab APK boundary"
