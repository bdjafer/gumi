# Gumi Omi CV1 firmware kernel

This directory owns portable, allocation-free firmware behavior that can be tested on the host before
it is wired into the pinned Omi CV1 / Zephyr application. It is product source, not a copy of the
upstream firmware and not a flash candidate by itself.

## Button kernel

[`include/gumi/button.h`](include/gumi/button.h) and [`src/button.c`](src/button.c) implement two pure
layers from `gumi.omi-cv1-human-io/v1`:

1. a stable-level debouncer that converts raw GPIO levels into accepted edges; and
2. a physical gesture recognizer for single tap, double tap, hold, and operation-bound maintenance
   confirmation.

The recognizer intentionally does not start a microphone, choose recording versus voice-turn actions,
or trust a cached network state. Those decisions belong to the next device-local capture lifecycle,
which must evaluate live privacy, durability, and authenticated-admission state when it consumes a
gesture. This keeps an input parser from becoming a capture authority.

The API uses caller-owned fixed-size state and event batches, has no heap or operating-system
dependency, rejects timestamp overflow, and applies every input transactionally. Accepted edges at a
timestamp must be delivered before gesture deadlines at that timestamp; that rule makes a release at
exactly 500 ms win over hold commitment and makes a second press at exactly 350 ms suppress the
pending single tap.

Run the host gate with:

```sh
devices/omi-cv1/firmware/gumi/button-kernel.test.sh
```

The gate compiles with strict C11 warnings, runs boundary and invalid-input tests, and checks the public
timing constants directly against the device-owned JSON contract. It performs no network or device
operation.

## Capture supervisor

[`include/gumi/capture.h`](include/gumi/capture.h) and [`src/capture.c`](src/capture.c) implement the
device-local capture authority. The supervisor boots microphone-off, sequences privacy assertion before
microphone acquisition, withholds base and realtime audio gates until every required effect commits,
preserves a base recording through a VoiceTurn overlay, stops at a durable boundary, and forces audio
closed immediately if the privacy output fails.

Every asynchronous action and completion carries a monotonically allocated transition ID. A late
callback from an expired or cancelled attempt is rejected transactionally, so it cannot reopen an audio
gate or change microphone truth. VoiceTurn admission is authenticated, token-bound, and checked again
at commitment with a strict monotonic expiry.

Run its independent host gate with:

```sh
devices/omi-cv1/firmware/gumi/capture-kernel.test.sh
```

[`include/gumi/feedback.h`](include/gumi/feedback.h) and [`src/feedback.c`](src/feedback.c) form the
single-writer logical RGB arbiter. Privacy recording/unknown/VoiceTurn owns the highest tier and cannot
be blended with a lower pattern. A privacy-driver failure selects no misleading fallback color and
locks charging, maintenance, warning, and requested-status candidates out. The contract's unresolved
low-power/charging tie remains an explicit decision error instead of acquiring an accidental order.

```sh
devices/omi-cv1/firmware/gumi/feedback-kernel.test.sh
```

## Encrypted recording journal

[`include/gumi/recording_journal.h`](include/gumi/recording_journal.h) and
[`src/recording_journal.c`](src/recording_journal.c) define the allocation-free binary journal between
the Opus codec and the future Zephyr SD writer. The journal never accepts raw Opus bytes as directly
storable data: it first produces a unique AES-256-GCM nonce and canonical authenticated data, and only
advances after the platform crypto port returns ciphertext and a tag. Recovery advances its valid
prefix only after the caller has authenticated and decrypted the inspected record.

The format has exact per-session/per-packet identity, sequence and sample accounting, bounded payloads,
an encrypted commit summary, CRC32C torn-write detection, and explicit recovery of an authenticated but
uncommitted prefix. CRCs are structural checks, not security. The test-only authenticated transform is
not used by firmware.

```sh
devices/omi-cv1/firmware/gumi/recording-journal.test.sh
```

The full format and the fail-closed FATFS lifecycle are specified in
[`docs/recording-journal-v1.md`](docs/recording-journal-v1.md). The PSA Crypto and single-writer Zephyr
SD adapters remain independent target gates.

The narrow PSA adapter can be checked against the exact pinned NCS headers without building or touching
a device:

```sh
devices/omi-cv1/firmware/gumi/crypto-port-api.test.sh /path/to/ncs-v2.9.0
```

That is an API syntax gate only. A target link, AES-GCM known-answer test, persistent/derived-key reboot
test, and real recording recovery remain required.

## Integration boundary

Do not add this source to the identity-only `canary-0001` patch. The first functional overlay will be a
separate reviewed stage and must still prove repeatable microphone stop/start, codec/ring-buffer reset,
durable-boundary behavior, privacy-indicator arbitration, and boot-with-microphone-off before it can be
considered for an update manifest.
