# Functional recording 0004 — allocator repair and first recording qualification

Status: v0003 → v0004 update and exact boot proven; allocator repaired; storage failed closed at the
next exact integration boundary.

Observed on 2026-07-25:

- v0004 became active, bootable, confirmed, and non-pending at MCUboot hash
  `1a47d68c09d664fdf4e0d6549ebe8f8e7ccf08d00f7a93f01a54dc21d0a075d3`;
- the exact Gumi v1 GATT topology and software revision were present;
- microphone remained `VERIFIED_OFF`, recording key became `READY`, and codec remained `CLOSED`;
- recording storage was `FAULTED`, free bytes were `0`, and errno was `-88` (`ENOSYS`);
- no recording attempt was admitted.

This closes v0004 as the allocator-repair witness, not as recording-ready firmware. The root cause and
direct successor procedure are recorded in
[`functional-recording-0005-first-device.md`](functional-recording-0005-first-device.md).

The second owned sealed Omi CV1 is already normalized to the official v3.0.12 application/network
family, has a terminally verified non-exportable MEXT recording root, and currently runs exact
functional-recording-0003. V0003's exact Gumi v1 GATT evidence is coherent and fail-closed:
microphone `VERIFIED_OFF`, recording key `FAULTED`, errno `-12`, MCUboot application hash
`0cd7ddea779427f25ff2b3341f1242e0e4611f1245b02fde81361c0d2cbcbecd`.

V0004 changes only the versioned configuration relative to v0003: it enables Nordic's fixed-buffer
Mbed TLS allocator with 4096 bytes. This repairs the deterministic null-allocation failure in
`psa_import_key`; it does not add key writes, formatting, retries, remote capture, media export, or
network-image updates.

## Exact target

| Property | Value |
|---|---|
| File | `local/firmware/omi-cv1/functional-recording-0004/omi.signed.bin` |
| Size | `221428` bytes |
| File SHA-256 | `382a04633bf83329fb8ef3ded1ecbfb01ba6b53e8af1b2473e7f1795355ba7d2` |
| MCUboot image hash | `1a47d68c09d664fdf4e0d6549ebe8f8e7ccf08d00f7a93f01a54dc21d0a075d3` |
| Software revision | `gumi-functional-recording-0004` |
| Image index | application `0` only |

The expected unchanged stock network image hash is
`267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089`. The owned unit may expose no
image-1 row. Never upload the local `ipc_radio.bin`, `dfu_application.zip`, a HEX, or an unsigned
application.

The recovery return remains exact recovery-only-0001:
file SHA-256 `d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc`,
MCUboot hash `065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57`.

## Offline gate

Before touching the device:

```sh
node devices/omi-cv1/firmware/scripts/verify-application-release.mjs \
  devices/omi-cv1/firmware/releases/functional-recording-0004.json \
  local/firmware/omi-cv1/functional-recording-0004/omi.signed.bin
./gumiw :devices:omi-cv1:application-updater:android:check
```

The APK audit must contain exactly recovery-only, capture self-test, recording-root provisioner,
functional v0004, official stock application, and official stock network assets. A fresh APK hash is
recorded after this gate. Neither the release JSON nor the APK conveys physical authorization.

## 1. Directly repair v0003

1. Connect and unlock the qualified Motorola phone, then run
   `devices/omi-cv1/application-updater/android/scripts/prepare-physical-phone.sh`.
2. Keep the Omi close to the phone and awake. While its microphone remains off, hold the Omi button
   continuously for five seconds until maintenance is admitted; release before the 12-second reset.
3. In Flash Lab, scan once, select the observed Omi, and choose
   **Provisioner / v0003 repair → functional recording v0004**.
4. Run a fresh disclosed preflight. It must bind the process-local endpoint, exact active v0003 hash,
   `Gumi` manufacturer, microphone-off/capture-idle/update-admitted functional evidence, and the
   unchanged network observation policy.
5. Review must show release
   `omi-cv1-v3012-functional-recording-0003-to-functional-recording-0004`, target image `0`, and the
   exact v0004 file and MCUboot hashes above.
6. Give one fresh authorization naming exact file SHA-256
   `382a04633bf83329fb8ef3ded1ecbfb01ba6b53e8af1b2473e7f1795355ba7d2`, then execute once.
7. Do not repeat an ambiguous upload or reset. If the phone loses the terminal response, scan fresh
   and use only exact image-state evidence to decide whether validation or recovery is next.

## 2. Prove repaired boot

After reboot, scan and select the rediscovered Omi, then validate post-reboot. Success requires:

- active, bootable, confirmed, non-pending application image hash
  `1a47d68c09d664fdf4e0d6549ebe8f8e7ccf08d00f7a93f01a54dc21d0a075d3`;
- software revision `gumi-functional-recording-0004`;
- exact 40-byte status and immutable 16-byte capabilities;
- one empty Omi-family identity service and no recovery, provisioner, self-test, or stock functional
  characteristic surface;
- capture `IDLE`, microphone `VERIFIED_OFF`, key `READY`, storage ready, codec `CLOSED`, privacy off,
  zero error, and no active recording ID.

Any other coherent status is evidence, not success. Preserve its raw 40 bytes and stop before capture
if key/storage/codec is faulted, microphone truth is unknown, or privacy is asserted unexpectedly.

## 3. Prove one local recording

Use only non-sensitive speech.

1. From proven `READY`, unplug the charger and double-tap once.
2. Continuous red must appear before microphone activity.
3. Recheck functional status. It must report capture `RECORDING`, microphone `ACQUIRED`, storage and
   codec active, privacy asserted, a nonzero recording ID, base audio permitted, and voice audio
   refused.
4. Speak for at least five seconds, then double-tap once to stop.
5. Red must turn off. Recheck must return to `READY` with microphone `VERIFIED_OFF`, codec `CLOSED`,
   no active recording ID, zero error, and recording storage ready or committed.

A release failure, persistent red, unknown microphone truth, or faulted status is terminal
fail-closed evidence. Do not retry capture.

## 4. Prove reset and recovery escapes

While capture is idle and red is off:

1. Hold continuously for five seconds and observe maintenance admission.
2. Continue holding to 12 seconds; the device must cold-reset rather than remain indefinitely blue.
3. Re-scan and re-prove exact v0004 `READY`.
4. If a recovery return is required, repeat the five-second admission, release before 12 seconds, and
   run a fresh separately authorized v0004 → recovery-only transition.

## Acceptance boundary

V0004 is physically qualified only after exact repair, `READY`, one
`READY → continuous-red RECORDING → durable stop → READY` lifecycle, the 12-second reset escape, and
a reviewed recovery path are all observed. Image `1` remains untouched. Live streaming, media export,
transcription, remote capture, VoiceTurn, and independent microphone-off measurement remain separate
milestones.
