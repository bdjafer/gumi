# Omi CV1 v3.0.12 Zephyr ports

This directory contains hardware-bound adapters and explicit compositions for the pinned Omi CV1
v3.0.12 / NCS v2.9.0 substrate. Individual ports are not firmware candidates; the separately
manifested recovery, capture self-test, and functional profiles link reviewed subsets.

`mic_port.c` replaces the unsafe stock stop/start shape at the adapter boundary:

- initialization configures PDM but leaves it verified off;
- every acquisition creates a fresh thread on caller-owned static storage;
- release gates callbacks first, issues DMIC STOP, joins the thread, drains completed slab buffers,
  then reconfigures PDM as an explicit asynchronous-stop barrier;
- a failed join, STOP, or barrier leaves microphone truth `UNKNOWN`; and
- fault callbacks may only enqueue supervisor work, avoiding self-join from the mic thread.

`codec_port.c` creates a fresh bundled-Opus encoder and fixed PCM ring for every nonzero session token.
It rejects stale tokens, faults rather than silently dropping on exhaustion, assigns a monotonic packet
sequence, and joins/purges the worker before a successful close returns.

Its independent emulator gate builds the exact pinned upstream Opus tree and current codec port in the
digest-pinned, network-disabled NCS container, then runs the lifecycle suite on a single-core QEMU target
with the required Arm DSP instruction set:

```sh
devices/omi-cv1/firmware/gumi/codec-port-emulator.test.sh \
  /exact/BasedHardware-omi /exact/ncs-v2.9.0-west-workspace
```

The runner is bounded because this QEMU board remains idle rather than powering off after Ztest success;
the gate requires the exact suite-pass and project-success markers and rejects fatal/failure markers.

`crypto_port.c` is the narrow Nordic PSA Crypto adapter for the encrypted recording journal. It borrows
an already-authorized AES-256/GCM key handle, validates its attributes once per session, uses the plan's
nonce and authenticated data verbatim, requires exact output sizes, and zeroes expected output spans on
failure. It performs no HUK/KMU writes, persistent-key creation, rotation, or destruction. Those are a
separate provisioning boundary because writing the nRF5340 hardware unique-key slots is not an ordinary
runtime operation.

The microphone and historical compile/link overlays are not behavioral firmware. The codec emulator
proves lifecycle behavior only; journal-to-codec integration, crypto target execution, storage writer,
invocation, button composition, privacy-output composition, and physical use remain prohibited until
their independent current-source gates exist.

## Capture-port self-test

`capture-port-selftest-0001` is the first hardware-bound diagnostic composition. It is deliberately
smaller than product firmware: a phone may write exactly `0x01` to arm a 15-second lease, but only a
continuous two-second physical button hold starts a three-second PDM/Opus exercise. Red is asserted
before microphone acquisition. PCM and Opus output are counted and discarded in memory; there is no
audio characteristic, filesystem, offline storage, stock functional transport, haptic path, or
automatic capture.

The lab service is `f80a6e60-3b3f-4e8a-93e4-5f5e2c527001`; its 32-byte read/notify status is
`f80a6e61-3b3f-4e8a-93e4-5f5e2c527001`, and its one-byte arm characteristic is
`f80a6e62-3b3f-4e8a-93e4-5f5e2c527001`. The stock Omi-family service remains present only as an empty
discriminator for discovery. Exact application-image-0 MCU Manager recovery remains available.

Materialize and build only from the pinned upstream mirror and NCS workspace:

```sh
devices/omi-cv1/firmware/scripts/materialize.sh \
  local/upstream/omi /private/tmp/gumi-capture-selftest capture-port-selftest-0001
devices/omi-cv1/firmware/scripts/build-application.sh \
  /private/tmp/gumi-capture-selftest/omi local/toolchains/ncs-v2.9.0 \
  gumi-capture-selftest-0001-build capture-port-selftest-0001
```

The exact verifier marks every resulting image `offline-unqualified` and
`physical_use_forbidden=true`. A signed build receipt is not permission to upload it; physical use
requires a new owner authorization naming the exact file SHA-256, followed by three consecutive
on-device passes and a separately reviewed recovery-only return.

## Functional recording

`functional-recording-0007` is the current device-local product composition. It combines the portable
capture authority with the Omi button, continuous-red privacy output, PDM adapter, fresh-session Opus
codec, MEXT-derived AES-GCM key, non-formatting FATFS recording store, authenticated boot scanner, and
read-only functional GATT status.

V0004 enabled Nordic's bounded
Mbed TLS runtime allocator (`CONFIG_MBEDTLS_ENABLE_HEAP=y`, 4096 bytes), fixing the physically observed
v0003 `psa_import_key` `-ENOMEM` failure caused by the integration's default null allocator. Its
physical boot then exposed the exact SPI-SD host's missing PM callback as advisory `-ENOSYS` before
mount. V0005 adds a pure policy that accepts only success, already-active, and no-PM-implementation
resume outcomes; `-ENOTSUP` and real storage failures remain fatal.

After v0005 physically completed one durable recording lifecycle, its 12-second application-core reset
left BLE unreachable because the nRF5340 network core was not reset. V0006 force-cycles that core before
`bt_enable()` and forces it off before the application cold reset. The network firmware bytes are
unchanged. An actual-source fake-HAL test and the exact-board link verify the required call order.
V0007 additionally refreshes FAT capacity before directory creation and returns `-ENOSPC` without
issuing mkdir when the volume is full.

The separate `legacy-storage-reclaimer-0002` composition is not functional firmware. It keeps the
microphone off, mounts without formatting, and may unlink only `/SD:/audio/a01.txt` when stat proves
it is a regular file exactly 505,118,720 bytes long. Its status-only service records observed type,
size, free bytes, mutation admission, unlink result, and postcondition. No wildcard, recursion,
formatting, alternate path, media read, capture, or key write is linked. Unlike superseded v0001,
v0002 leaves SD power asserted after unmount/suspend so the external MCUboot secondary on shared SPI3
remains writable for the separately authorized successor.

Double-tap toggles an encrypted local recording. One tap requests a status indication. Hold is refused
because v1 has no authenticated realtime route. There is no live-media characteristic, remote capture
write, export/delete API, stock capture/storage service, automatic HUK provisioning, or filesystem
format path.

The functional service is `47554d49-0001-4f4d-492d-435631000001`; status
`47554d49-0002-4f4d-492d-435631000001` is exactly 40 bytes and capabilities
`47554d49-0003-4f4d-492d-435631000001` is exactly 16 bytes. Android requests MTU 43 or greater before
reading status so one evidence record cannot be torn across ATT reads.

Application-image-0 recovery remains physically gated. A two-second hold during boot admits
maintenance, and a continuous five-second hold while capture is idle admits it at runtime and makes it
exclusive until reboot. Continuing the hold to 12 seconds performs the bounded dual-core reset. A
15-second task watchdog with an nRF hardware fallback covers main-loop stalls. All paths keep the
microphone off.
The current signed image is an
offline-qualified candidate only; exact HUK/readiness and recording lifecycle still require the
first-device procedure under [`../../../qualification/`](../../../qualification/).
