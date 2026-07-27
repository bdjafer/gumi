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

## Interaction policy and semantic signals

[`include/gumi/interaction_policy.h`](include/gumi/interaction_policy.h) and
[`src/interaction_policy.c`](src/interaction_policy.c) are the policy layer between semantic button
events and capture-coordinator intents. They own no GPIO, microphone, privacy output, storage, BLE, or
cloud effect, and they retain no shadow recording state. Every evaluation consumes current
coordinator-owned facts.

Two versioned profiles prove that the same input and capture kernels can support different products:
the manual profile maps double tap to a live-state Recording toggle and hold to a VoiceAction; the
continuous profile never toggles Recording and maps hold to a recording-correlated interpretation
marker. [`semantic_signal.h`](include/gumi/semantic_signal.h) tracks that marker as data with exact
signal, recording, and time identity and interrupts it at the recording boundary.

```sh
devices/omi-cv1/firmware/gumi/interaction-policy.test.sh
```

`functional-recording-0003` remains frozen and does not link these vNext modules. Its embedded manual
mapping is behavior-equivalent but structurally older. Shipping a profile through target firmware
requires a new manifest, signed artifact, source/link audit, and physical qualification; a policy
profile is never a runtime switch hidden in reserved bytes.

## Recovery supervisor

[`include/gumi/recovery.h`](include/gumi/recovery.h) and [`src/recovery.c`](src/recovery.c) implement the
portable recovery-first boot state machine. Recovery transport is always requested before any other
action, capture admission defaults false, and explicit/persisted/watchdog safe-mode evidence prevents
functional-service enablement. Runtime faults revoke capture synchronously before platform quiescence;
transition IDs reject stale asynchronous completions.

The four-byte read-only wire status is `[schema, phase, reason, flags]`. Flags report recovery transport,
microphone-off proof, functional readiness/capture admission, and the observed overwrite-only boot
policy. The recovery-only-0001 steady value is `01070123`.

```sh
devices/omi-cv1/firmware/gumi/recovery-kernel.test.sh
```

The strict C11 plus ASan/UBSan host gate runs 11 transition, fail-closed, stale-completion, and wire
encoding cases. The Omi-specific adapter under [`zephyr/omi-v3012/`](zephyr/omi-v3012/) starts only
BLE/SMP, Device Information, the empty stock-family identity service, and Gumi recovery status. It
configures PDM without `DMIC_TRIGGER_START`, rejects image numbers other than application image `0`,
and links no functional stock source. Exact target-build and signed-image qualification remain separate
gates from this portable test.

## Capture-port self-test supervisor

[`include/gumi/capture_selftest.h`](include/gumi/capture_selftest.h) and
[`src/capture_selftest.c`](src/capture_selftest.c) define the diagnostic stage between recovery-only and
product capture. A phone arm creates one exact 15-second lease; a separately proven two-second device
hold sequences privacy red, a fresh Opus encoder, PDM acquisition, a bounded three-second exercise,
verified PDM release, codec drain, and privacy removal. Only counters and lifecycle truth are retained.
No audio byte, storage operation, Recording command, or VoiceTurn exists in this kernel.

Microphone-release uncertainty is terminal and keeps privacy asserted. Every platform effect carries a
transaction ID, stale completions are rejected transactionally, and safe failures clean up in the fixed
microphone then codec then privacy order. Run the allocation-free host gate with:

```sh
devices/omi-cv1/firmware/gumi/capture-selftest-kernel.test.sh
```

This is not a functional firmware acceptance result. The target adapter, exact linked-source audit,
signed image, phone probe, and controlled physical qualification in
[`docs/decisions/0007-omi-cv1-capture-port-selftest.md`](../../../../../docs/decisions/0007-omi-cv1-capture-port-selftest.md)
remain separate gates.

## Functional composition boundary

`functional-recording-0007` is the current reviewed overlay that composes these kernels with the
Omi-specific microphone, codec, privacy, MEXT-derived-key, non-formatting FATFS, and read-only GATT
ports. It retains the frozen v0003 behavior and v0004's bounded 4096-byte Mbed TLS allocator, then
normalizes only the exact SPI-SD driver's no-PM-callback `-ENOSYS` result before mount. Unsupported
states and real storage failures remain fail-closed. It proves source and local lifecycle boundaries
without changing the identity-only canary or recovery-only graphs. V0006 additionally owns one narrow
nRF5340 reset port: the application force-cycles the network core before BLE startup and forces that
core off before an application cold reset. This prevents the app-only reset/stale-controller lockup
physically observed after v0005 otherwise completed recording successfully.
V0007 refreshes FAT capacity before attempting the recording-directory create and reports `-ENOSPC`
directly when the volume is full. The separate legacy-storage-reclaimer kernel admits deletion only
for the compiled exact path, regular-file type, and 505,118,720-byte size; its four-case host gate
proves exact deletion, already-absent success with sufficient free space, wrong-size refusal, and
wrong-type refusal.

The signed functional release is still a physical candidate, not hardware proof. Its first-device gate
must first establish terminal status-only MEXT provisioning, then boot-with-microphone-off, repeatable
codec/microphone lifecycle, continuous privacy indication, authenticated durable stop, exact read-only
state, the five-second maintenance admission, and the 12-second reset escape. Live transport, export,
remote capture, and VoiceTurn remain outside this version.
