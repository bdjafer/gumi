# Omi CV1 ring protocol fixtures v1

These device-owned fixtures turn the retained Omi v3.0.20 offline-storage wire behavior into a
language-neutral compatibility contract. Firmware and every Omi edge driver must consume the same
cases regardless of implementation language or host platform.

The behavior is grounded in the pinned upstream
[`storage.c`](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/omi/src/lib/core/storage.c),
[`transport.c`](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/omi/src/lib/core/transport.c),
[`ring_protocol.dart`](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/app/lib/services/devices/ring_protocol.dart),
and its
[`ring_protocol_test.dart`](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/app/test/unit/ring_protocol_test.dart).
The synthetic bytes in [fixtures.json](fixtures.json) encode protocol facts and expected behavior; no
upstream implementation is copied here.

## Byte recipe grammar

A fixture byte input is either a direct lower-case hex string or `parts` evaluated from left to right.
The only recipe operations currently used are:

- `{"hex": "aabb"}`: literal bytes;
- `{"u8": 3}`: one unsigned byte; and
- `{"repeat": {"hex": "00", "count": 440}}`: repeat the literal byte sequence.

All multi-byte wire fields are big-endian except the 16-byte status read, whose four `u32` fields are
little-endian. Unsigned 64-bit expected values are strings so JSON consumers do not lose precision.

## Conformance rule

An implementation passes only if it:

- matches every exact encoder byte string;
- rejects/truncates at the stated boundaries;
- reassembles records independently of BLE notification boundaries; and
- never advances the device read sequence until the corresponding records have a later durable copy.

The final durability rule is an edge-runtime invariant and is not performed by the pure codec fixture.
