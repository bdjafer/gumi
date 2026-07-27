# Functional recording 0006 — dual-core reset repair qualification

Status: exact artifact and updater offline-qualified; physical v0005 recovery and v0006 repair pending.

The sealed Omi currently contains exact functional-recording-0005 application hash
`55663e5f90ce3e7b6e9373f8ff6432c215c68668e6759b9111cc815a31c54961`. Its recording lifecycle passed,
but its 12-second application-core reset left no LED, button response, or BLE advertisement. Because
the button is only a GPIO input and charger insertion preserves battery power, the sealed product has
no known external full-reset path. Leave it off the charger and untouched until the battery reaches a
true power loss.

V0006 preserves the exact v0005 capture/storage/crypto behavior. Its only target-specific repair is a
dedicated nRF5340 reset port:

- before `bt_enable()`, force the network core off, wait, release it, and wait;
- before every application cold reset, force the network core off and then reset the application core;
- keep the locally built network firmware quarantined and upload application image `0` only.

## Exact target

| Property | Value |
|---|---|
| File | `local/firmware/omi-cv1/functional-recording-0006/omi.signed.bin` |
| Size | `221576` bytes |
| File SHA-256 | `eb811b62fffbf5c5f3ca1684b29073f6cf9cb49ae8e38d5201944c6c36d640a0` |
| MCUboot image hash | `3a35a3324393e563e36b2748f0b1f842ce5db52834cd0023334d9c9ac3071aa1` |
| Software revision | `gumi-functional-recording-0006` |
| Image index | application `0` only |

The expected unchanged stock network image hash is
`267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089`. The unit may expose no image-1
row. Never upload the generated `ipc_radio.bin`, `dfu_application.zip`, a HEX, or an unsigned image.

The separately packaged recovery-only-0001 return remains:

- file SHA-256 `d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc`;
- MCUboot hash `065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57`.

## Offline gate

Before touching the Omi:

```sh
node devices/omi-cv1/firmware/scripts/verify-application-release.mjs \
  devices/omi-cv1/firmware/releases/functional-recording-0006.json \
  local/firmware/omi-cv1/functional-recording-0006/omi.signed.bin
devices/omi-cv1/firmware/gumi/reset-port.test.sh
./gumiw verifyArchitecture :devices:omi-cv1:application-updater:android:check
```

The qualified phone must contain Flash Lab version
`0.10.0-functional-recording-v0006-dual-core-reset-repair`. Its exact APK audit packages v0006 as the
only functional target and keeps v0005 solely as source metadata. Neither these bytes nor this runbook
conveys physical authorization.

## 1. Recover exact v0005 without writing

1. Keep the unresponsive Omi off the charger and do not press its button until its battery has truly
   depleted. Do not guess success from elapsed time alone.
2. Insert it on the charger once. Do not hold the button. Allow a bounded startup dwell and scan.
3. If it does not advertise, remove it again: the battery did not reach a true power loss. Do not
   attempt an update, random holds, or repeated charger cycling.
4. If it advertises, complete the three physical attestations, select the one exact Gumi Omi, and
   inspect its fresh state. Do not accept a name, BLE address, or RSSI as image identity.
5. Require exact active/bootable/confirmed/non-pending v0005 hash
   `55663e5f90ce3e7b6e9373f8ff6432c215c68668e6759b9111cc815a31c54961`, `Gumi` manufacturer, exact
   functional topology, capture idle, microphone verified off, key and storage ready, codec closed,
   privacy off, and errno `0`.

Any other result is recovery evidence, not authority to upload v0006.

## 2. Prepare the one v0005 → v0006 repair

1. While capture is idle, hold the button continuously for five seconds to admit maintenance, then
   release well before 12 seconds. Do not invoke v0005's failed reset path again.
2. In Flash Lab choose **Provisioner / v0003-v0005 repair → functional recording v0006**.
3. Run the fresh disclosed preflight and leave its review visible.
4. Require release
   `omi-cv1-v3012-functional-recording-0005-to-functional-recording-0006`, source hash
   `55663e5f90ce3e7b6e9373f8ff6432c215c68668e6759b9111cc815a31c54961`, target image `0`, target file
   SHA-256 `eb811b62fffbf5c5f3ca1684b29073f6cf9cb49ae8e38d5201944c6c36d640a0`, and target image hash
   `3a35a3324393e563e36b2748f0b1f842ce5db52834cd0023334d9c9ac3071aa1`.
5. Obtain one fresh authorization naming the exact v0006 file SHA-256. Execute once.
6. Never retry an ambiguous upload or reset. If the terminal response is lost, scan fresh and use only
   exact image-state evidence to decide validation or recovery.

## 3. Prove repaired boot and recording

After the updater-requested reboot, scan and validate:

- exact active/bootable/confirmed/non-pending v0006 image hash;
- software revision `gumi-functional-recording-0006`;
- exact status/capability sizes and GATT topology;
- capture `IDLE`, microphone `VERIFIED_OFF`, key and storage `READY`, codec `CLOSED`, privacy off,
  errno `0`, nonzero free bytes, and no active recording ID.

Then, using non-sensitive speech, prove one
`READY → continuous-red RECORDING → durable stop → READY` lifecycle. Continuous red must precede
microphone acquisition. Stop must release microphone and codec, remove red, retain zero error, and
show a committed recording or the corresponding durable free-space change.

## 4. Prove the repaired reset

1. From exact v0006 `READY`, hold continuously for five seconds and observe maintenance admission.
2. Continue the same hold to 12 seconds, then release.
3. Require a bounded reboot followed by a fresh BLE advertisement without battery depletion or charger
   intervention.
4. Re-scan and re-prove exact v0006 `READY`.
5. If any stage becomes unresponsive, stop. Do not repeat the reset or upload; preserve the exact last
   visible state.

A recovery-only return, if wanted, is a separate five-second admission, fresh preflight, and fresh
authorization naming the recovery file hash.

## Acceptance boundary

V0006 closes this repair only after exact v0005 recovery, one authorized application-image-0 update,
exact v0006 boot, one recording lifecycle, the 12-second dual-core reset, and post-reset exact v0006
reachability all pass. The network image remains unmodified and unobserved. Live streaming, export,
transcription, remote capture, VoiceTurn, independently measured microphone-off truth, and
interruption recovery remain separate milestones.
