#!/bin/sh
# gumi-shell-test: explicit-arguments
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 /path/to/pinned/ncs-v2.9.0-workspace" >&2
    exit 64
fi

ncs_workspace=$(CDPATH='' cd -- "$1" && pwd)
firmware_root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)

[ -d "$ncs_workspace/.west" ] || {
    echo "NCS workspace has no .west metadata: $ncs_workspace" >&2
    exit 1
}
[ "$(git -C "$ncs_workspace/nrf" rev-parse HEAD)" = \
    7787b264984022cda64d9629278942053e6462a5 ] || {
    echo "NCS nrf checkout is not the pinned v2.9.0 revision" >&2
    exit 1
}
[ "$(git -C "$ncs_workspace/zephyr" rev-parse HEAD)" = \
    1f8f3dc291420c70cd39e77a5cdc954561d4a08f ] || {
    echo "Zephyr checkout is not the pinned v2.9.0 revision" >&2
    exit 1
}

compiler=${CC:-cc}
"$compiler" \
    -std=c11 \
    -Wall \
    -Wextra \
    -Wpedantic \
    -Wconversion \
    -Wshadow \
    -Wstrict-prototypes \
    -Werror \
    -fsyntax-only \
    -I "$firmware_root/include" \
    -I "$firmware_root/zephyr/omi-v3012/include" \
    -I "$ncs_workspace/modules/crypto/mbedtls/include" \
    "$firmware_root/zephyr/omi-v3012/src/crypto_port.c"

echo "PASS: crypto port matches the pinned PSA C API headers"
echo "warning=API syntax only; no Nordic backend or target code was executed"
