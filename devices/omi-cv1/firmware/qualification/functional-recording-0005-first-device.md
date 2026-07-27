# Functional recording 0005 — SD host PM repair and first recording qualification

Status: physical recording lifecycle passed; 12-second reset failed and v0005 is superseded by v0006.

Do not install or re-run v0005. This file preserves the exact historical procedure and evidence that
led to the v0006 dual-core reset repair.

Before the recorded procedure, the second owned sealed Omi CV1 was normalized to the official v3.0.12
application/network family, had a terminally verified non-exportable MEXT recording root, and ran exact
functional-recording-0004. Its exact 40-byte Gumi v1 status was coherent and fail-closed:

- application MCUboot hash
  `1a47d68c09d664fdf4e0d6549ebe8f8e7ccf08d00f7a93f01a54dc21d0a075d3`;
- microphone `VERIFIED_OFF`;
- recording key `READY`;
- recording storage `FAULTED`;
- free bytes `0`;
- codec `CLOSED`; and
- errno `-88` (`ENOSYS`).

The exact Zephyr SPI-SD host has no device-PM callback. Its advisory
`PM_DEVICE_ACTION_RESUME` therefore returns `-ENOSYS` even though the disk remains usable. V0005
normalizes only success, `-EALREADY`, and `-ENOSYS` before continuing to disk initialization.
`-ENOTSUP` and every real device, mount, capacity, key, codec, or recording failure remain fatal.
No retry, formatter, key write, media export, remote capture, network-image update, or unbounded
allocation path was added.

## Exact target

| Property | Value |
|---|---|
| File | `local/firmware/omi-cv1/functional-recording-0005/omi.signed.bin` |
| Size | `221428` bytes |
| File SHA-256 | `26ec3d961d1342440a53034c27591df04ca1e2de637f463e48063e86b1b26f27` |
| MCUboot image hash | `55663e5f90ce3e7b6e9373f8ff6432c215c68668e6759b9111cc815a31c54961` |
| Software revision | `gumi-functional-recording-0005` |
| Image index | application `0` only |

The expected unchanged stock network image hash is
`267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089`. The owned unit may expose no
image-1 row. Never upload the locally generated `ipc_radio.bin`, `dfu_application.zip`, a HEX, or an
unsigned application.

The exact recovery return is recovery-only-0001:

- file SHA-256 `d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc`;
- MCUboot hash `065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57`.

## Offline gate

Before touching the Omi:

```sh
node devices/omi-cv1/firmware/scripts/verify-application-release.mjs \
  devices/omi-cv1/firmware/releases/functional-recording-0005.json \
  local/firmware/omi-cv1/functional-recording-0005/omi.signed.bin
./gumiw verifyArchitecture :devices:omi-cv1:application-updater:android:check
```

The APK audit must contain exactly recovery-only, capture self-test, recording-root provisioner,
functional v0005, official stock application, and official stock network assets. Neither the release
JSON nor the APK conveys physical authorization.

## 1. Directly repair v0004

1. Install and launch the verified Flash Lab APK on the qualified Motorola phone.
2. Keep the Omi close, awake, and charger-connected. While capture is idle, hold the Omi button
   continuously for five seconds to admit maintenance; release before the 12-second cold reset.
3. Complete the three physical attestations, scan once, and select the single exact Gumi Omi CV1.
4. Choose **Provisioner / v0003 / v0004 repair → functional recording v0005**.
5. Run a fresh disclosed preflight. It must bind the process-local endpoint, exact active v0004
   hash, `Gumi` manufacturer, microphone-off/capture-idle/update-admitted status, and unchanged
   network observation policy.
6. Review must show release
   `omi-cv1-v3012-functional-recording-0004-to-functional-recording-0005`, target image `0`, source
   MCUboot hash `1a47d68c09d664fdf4e0d6549ebe8f8e7ccf08d00f7a93f01a54dc21d0a075d3`,
   target file SHA-256
   `26ec3d961d1342440a53034c27591df04ca1e2de637f463e48063e86b1b26f27`, and target MCUboot hash
   `55663e5f90ce3e7b6e9373f8ff6432c215c68668e6759b9111cc815a31c54961`.
7. Obtain one fresh authorization naming the exact target file SHA-256, then execute once.
8. Never repeat an ambiguous upload or reset. If the terminal response is lost, scan fresh and use
   only exact image-state evidence to choose validation or recovery.

## 2. Prove repaired boot

After reboot, scan and select the rediscovered Omi, then validate post-reboot. Success requires:

- active, bootable, confirmed, non-pending application image hash
  `55663e5f90ce3e7b6e9373f8ff6432c215c68668e6759b9111cc815a31c54961`;
- software revision `gumi-functional-recording-0005`;
- exact 40-byte status and immutable 16-byte capabilities;
- the empty Omi-family identity service and no recovery, provisioner, self-test, or stock functional
  characteristic surface;
- capture `IDLE`, microphone `VERIFIED_OFF`, key `READY`, recording storage `READY`, codec `CLOSED`,
  privacy off, zero error, nonzero free bytes, and no active recording ID.

Any other coherent status is evidence, not success. Preserve the raw 40 bytes and stop before capture
if key, storage, or codec is faulted, microphone truth is unknown, or privacy is asserted.

## 3. Prove one local recording

Use only non-sensitive speech.

1. From proven `READY`, unplug the charger and double-tap once.
2. Continuous red must appear before microphone activity.
3. Recheck status. It must report capture `RECORDING`, microphone `ACQUIRED`, storage and codec
   active, privacy asserted, a nonzero recording ID, base audio permitted, and voice audio refused.
4. Speak for at least five seconds, then double-tap once to stop.
5. Red must turn off. Recheck must return to `READY` with microphone `VERIFIED_OFF`, codec `CLOSED`,
   no active recording ID, zero error, and recording storage ready or committed.

A release failure, persistent red, unknown microphone truth, or faulted status is terminal
fail-closed evidence. Do not retry capture.

## 4. Prove reset and recovery escapes

While capture is idle and red is off:

1. Hold continuously for five seconds and observe maintenance admission.
2. Continue holding to 12 seconds; the device must cold-reset rather than remain indefinitely blue.
3. Re-scan and re-prove exact v0005 `READY`.
4. If recovery return is required, repeat the five-second admission, release before 12 seconds, and
   run a fresh separately authorized v0005 → recovery-only transition.

## Physical result

The authorized v0004 → v0005 transition completed and exact v0005 booted `READY`: microphone
`VERIFIED_OFF`, storage `READY`, key `READY`, codec `CLOSED`, and errno `0`. A double-tap produced
continuous red and exact `RECORDING` state with microphone, storage, and codec active. The stop
double-tap removed red, returned to `READY`, committed the recording, and reduced free bytes from
`502530048` to `497614848`, with errno still `0`.

The 12-second escape then failed its acceptance criterion. V0005 called the application-core
`sys_reboot(SYS_REBOOT_COLD)` while the nRF5340 BLE network core remained powered. The sealed device
subsequently exposed no LED, button response, or BLE advertisement; charger insertion did not restore
it while the battery remained powered. The source and exact-board behavior support a stale
network-controller startup loop. A true battery power loss is required before attempting the v0006
repair.

## Acceptance boundary

V0005 is not accepted: its recording path passed, but its reset/reachability gate failed. Image `1`
was never intentionally written and remained unobserved. Continue only with
[`functional-recording-0006-first-device.md`](functional-recording-0006-first-device.md).
