#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
    echo "usage: $0 /path/to/canary-application.bin /path/to/stock-application.bin" >&2
    exit 64
fi

canary=$1
stock=$2

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
    "$canary" \
    228724 \
    65e4ae91637e6aa576f6d3f8286db33bc9bd36ece8ece1aaa6b6aa5c5b204f6d \
    canary_application_image_0
verify \
    "$stock" \
    228632 \
    877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db \
    stock_recovery_application_image_0
