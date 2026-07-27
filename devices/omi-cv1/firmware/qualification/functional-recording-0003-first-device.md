# Functional recording 0003 — first sealed-device qualification

Status: superseded by
[`functional-recording-0004-first-device.md`](functional-recording-0004-first-device.md). The owned
unit booted exact v0003 and reported authentic fail-closed recording-key `FAULTED`, microphone
`VERIFIED_OFF`, and errno `-12`; no recording-ready state was reached. Investigation traced this to
Mbed TLS runtime allocation being selected without a backing allocator. Do not install v0003 again.

Historical status: offline-qualified chain; the owned sealed Omi CV1 did not complete the full
acceptance chain.
The current physical image/state must be re-established by a fresh image-state and mode-specific GATT
read after the pendant becomes responsive; LED color alone is not image identity.

This procedure recovers the currently installed legacy functional-recording-0001 image, provisions
one non-exportable recording root, installs watchdog-hardened functional-recording-0003, and proves
the first local encrypted recording lifecycle. It updates application image 0 only and requires a
fresh owner authorization for every mutation.

## Exact artifacts

| Role | File SHA-256 | MCUboot image hash |
|---|---|---|
| Legacy v0001 source, already installed | `838c767f0d273767d422da751f4c2bc16bf1b27f35452833f992baf486c1ba45` | `045918a8cc1ceb4be74dd486e9da7b14123daacbb9b26e0d6404b6617048c820` |
| Recovery-only target | `d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc` | `065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57` |
| Recording-root provisioner | `e53c58a3ef72773af501a9ae05ceb5ce715b6d8153923ef52609dc5ec1dfa38b` | `8a8eef711fd72707905bda05e08ddebc0de2a986d3c1d21b7469c92e7d32b12e` |
| Functional recording v0003 | `3fda1c98da2bcd747e435b464feda563415949f6e0615193db25f1b658f3af1e` | `0cd7ddea779427f25ff2b3341f1242e0e4611f1245b02fde81361c0d2cbcbecd` |

The expected unchanged stock network image hash is
`267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089`.
The owned unit may expose no image-1 row; no network, ZIP, HEX, unsigned application, or multi-image
artifact may be uploaded.

## Offline closeout

From the repository root:

```sh
./gumiw :devices:omi-cv1:application-updater:android:check
```

The APK audit must pass with exactly five application-image-0 assets. Its current qualified output is:

```text
devices/omi-cv1/application-updater/android/build/outputs/apk/debug/android-debug.apk
SHA-256 0be418138233aee9eabfe30313922bdcb45c6185a50705fd8fe24482563719e8
```

Rebuilding may change the APK hash. If it does, use only the newly audited local APK and record its
fresh hash; firmware file hashes above must remain exact.

## Resume from the current unresolved state

Do not restart this document from an assumed legacy image. When the Omi is responsive:

1. connect and unlock the qualified phone, then run
   `devices/omi-cv1/application-updater/android/scripts/prepare-physical-phone.sh`;
2. keep the Omi close, scan once, select the one observed candidate, and run a fresh disclosed
   image-state preflight plus the offered mode-specific status read;
3. branch only on the exact observed application hash and coherent GATT topology:
   - legacy v0001 continues at section 1;
   - recovery-only continues at section 2;
   - a terminal provisioner continues at section 3;
   - functional v0003 `READY` continues at section 4;
4. if functional v0003 is visibly blue and unresponsive, try its continuous 12-second physical reset
   escape once. If it does not reset, stop interacting and allow a complete battery drain before one
   charger boot and another fresh read; and
5. do not upload, authorize recovery, provision a key, or repeat an ambiguous operation until the
   fresh read identifies the exact source state.

## 1. Recover legacy functional-recording-0001

The current constant-blue state is software lockup in the legacy image, not evidence of a dead
device. Because v0001 lacks a reset escape, allow its battery to drain. After the LED is fully off,
connect the charger once and let it boot.

1. Connect and unlock the qualified Motorola phone over USB, then run:

   ```sh
   devices/omi-cv1/application-updater/android/scripts/prepare-physical-phone.sh
   ```

2. Keep the Omi awake, next to the phone, and charged. Grant Nearby Devices and truthfully complete
   all safety attestations.
3. Hold the Omi button continuously for five seconds while it is idle. The blue update indication
   admits recovery until reboot.
4. Scan, select the Omi, and choose **Stock/self-test/provisioner/functional → recovery-only**.
5. Run a fresh preflight. It must identify legacy application hash
   `045918a8cc1ceb4be74dd486e9da7b14123daacbb9b26e0d6404b6617048c820`,
   prove update-admitted plus microphone-off status, and disclose
   `INCOMPLETE_FLASH_BLOCK_RESCUE`.
6. Authorize recovery file SHA-256
   `d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc`
   once.
7. After reset, scan and validate. Success requires active recovery MCUboot hash
   `065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57`
   and exact recovery status `01070123`.

Do not repeat an ambiguous update. Preserve the screen and read fresh image state first.

## 2. Provision the recording root once

This phase is irreversible. It writes a random 32-byte root to `HUK_KEYSLOT_MEXT` only if MEXT is
empty. The root cannot be read back, exported, or undone.

1. From validated recovery, open a separately reviewed next transition.
2. Leave the Omi on a stable charger for the entire provisioning transition. Do not disconnect,
   move, press, or power-cycle it until terminal status is visible.
3. Scan and select the Omi, choose **Recovery-only → provision recording root**, and run preflight.
4. Confirm the exact provisioner file SHA-256
   `e53c58a3ef72773af501a9ae05ceb5ce715b6d8153923ef52609dc5ec1dfa38b`
   and the irreversible-write disclosure.
5. Give a new one-time authorization and perform the single image-0 update.
6. After reboot, scan and validate. The status must be terminal `PROVISIONED` or
   `ALREADY_PRESENT`, with:

   - recovery transport ready;
   - microphone verified off;
   - MEXT present;
   - domain-separated derivation verified;
   - post-terminal MCU Manager mutation admitted;
   - zero error; and
   - exactly one 12-byte status characteristic on the provisioner service.

No root, derivative, or digest is evidence. The provisioner has a 15-second task watchdog plus
hardware fallback and denies remote upload/reset until the terminal state is verified. The Nordic
MEXT primitive writes two hardware slots and cannot be made power-loss atomic in application
software. A reset or power interruption during that one-shot path is therefore an outcome-unknown
incident: do not retry or install functional firmware; preserve the device and inspect fresh terminal
status first.

## 3. Install functional-recording-0003

1. From validated provisioning, open a separately reviewed next transition.
2. Scan and select the Omi, choose **Provisioner → functional recording v0003**, and run preflight.
   Flash Lab must re-prove the terminal provisioner status before showing a review.
3. Confirm functional file SHA-256
   `3fda1c98da2bcd747e435b464feda563415949f6e0615193db25f1b658f3af1e`
   and MCUboot hash
   `0cd7ddea779427f25ff2b3341f1242e0e4611f1245b02fde81361c0d2cbcbecd`.
4. Give a new one-time authorization and perform the single image-0 update.
5. After reboot, scan and validate. The exact 40-byte status, immutable 16-byte capabilities, empty
   Omi-family identity service, and absence of recovery/self-test/stock functional services must
   pass together.
6. Continue only when the card reports **READY** with key `READY`, microphone `VERIFIED_OFF`,
   recording storage ready, codec `CLOSED`, healthy free space, and zero error.

Any fail-closed, transitioning, malformed, or wrong-image state is not functional qualification.

## 4. Prove local recording

Use only non-sensitive test speech. This firmware intentionally has no media export path yet.

1. Unplug the charger.
2. From **READY**, double-tap once.
3. Continuous red must appear before microphone activity.
4. Refresh image and functional status. It must report **RECORDING** with phase `BASE_ACTIVE`,
   microphone `ACQUIRED`, storage `ACTIVE`, codec `ACTIVE`, nonzero recording ID, privacy asserted,
   base audio permitted, and voice audio refused.
5. Speak non-sensitive test audio for at least five seconds.
6. Double-tap once to stop. Red must turn off.
7. Refresh again. It must return to **READY** with microphone `VERIFIED_OFF`, codec `CLOSED`, no
   active recording ID, and recording storage ready or `COMMITTED`.

A release failure, persistent red, faulted status, or unknown microphone truth is terminal
fail-closed evidence. Do not retry capture.

## 5. Prove the sealed-device reset and recovery escapes

While capture is idle and red is off:

1. Hold continuously for five seconds. Blue update admission should appear.
2. Keep holding to 12 seconds. The device must cold-reset instead of remaining indefinitely blue.
3. Re-scan and re-prove exact functional v0003 state.
4. If returning to recovery, repeat the five-second admission, release before 12 seconds, open a
   separately authorized recovery-only transition, and let Flash Lab prove update-admitted,
   microphone-off, and capture-idle evidence before upload.

Functional v0003 also has a 15-second task watchdog with an nRF hardware fallback. Neither reset path
is a substitute for fresh post-reboot image and GATT validation.

## Acceptance boundary

Qualification passes only when recovery rescue, terminal provisioning, functional **READY**, the
idle → red/recording → durable stop → idle lifecycle, and the 12-second cold-reset escape are all
observed on the owned sealed unit. Image 1 must remain untouched, and every mutation must have its own
fresh exact-file authorization.

Live streaming, mobile media transfer, transcription, remote capture control, VoiceTurn, and audio
content verification remain later milestones.
