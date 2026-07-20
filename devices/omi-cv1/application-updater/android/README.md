# Omi CV1 Android application flash lab

This module builds the isolated `dev.gumi.omicv1.flashlab` Android application. It is a local,
device-specific owner tool, not part of the Gumi control-plane shell or portable edge runtime.

## Closed write surface

- No production module depends on this module and every implementation type remains `internal`.
- The only transitions are exact stock-v3.0.12 to canary-0001 and exact canary-0001 to stock-v3.0.12.
- The build verifies and packages exactly two signed application-image-`0` binaries from ignored
  `local/firmware/omi-cv1/` paths.
- The APK audit rejects network images, OTA ZIPs, HEX files, Internet/storage/install permissions, and
  any firmware asset beyond those two exact binaries.
- The only upload call is Nordic MCU Manager's explicit `imageUpload(bytes, 0, callback)` overload.
- `ImageSet`, generic firmware selection, image `1`, erase, test, file, settings, shell, and automatic
  retry APIs are forbidden by the repository architecture gate.
- A prepared plan pins the process-local endpoint, exact source application state, target file hash,
  target MCUboot hash, compatibility key, version, and network evidence policy.
- A one-shot two-minute owner capability is consumed before transport opens; execution immediately
  re-reads source state before sending the first byte.
- Staged and confirmed state must show exactly the planned inactive application image. Confirmation
  and reset never run after a rejected staged state.
- A reset response or reset-time disconnect yields only `AWAITING_POST_REBOOT_VALIDATION`. A fresh
  state read must prove the target application active before the lab reports validation.
- Failure and cancellation have no in-app retry route. Cancellation stops upload, releases transport,
  and never confirms or resets.

## Network-core evidence policy

The owned stock v3.0.12 unit exposes no MCU Manager image-`1` row. Pinned Zephyr omits a row whose
MCUboot header cannot be read, so the exact network hash and secondary state cannot be recovered through
this stock SMP surface.

For the two pinned application transitions only, complete absence of every image-`1` row is accepted.
If any image-`1` row becomes visible at any preflight, staged, confirmed, or post-reboot read, the lab
requires the exact published active hash and rejects pending or populated secondary state. This does
not claim that the network hash was physically re-observed; it relies on a closed image-`0` write path,
the absence of network bytes, and the already qualified BLE/audio behavior. The accepted residual risk
is recorded in [Decision 0005](../../../../docs/decisions/0005-omi-cv1-network-unobserved-ota.md).

## Owner flow

The lab requires:

1. phone battery at least 80%;
2. owner attestations for Omi charge, force-stopped official app, charger availability, and no-rollback
   risk;
3. an exact Omi advertisement selected from a process-local address directory;
4. a fresh semantic image-state preflight;
5. explicit acknowledgement of the full target file SHA-256;
6. one image-`0` update; and
7. a same-endpoint post-reboot state read.

After canary validation, the lab requires owner acknowledgement of the three-magenta-pulse identity,
Device Information revision, GATT inventory, and bounded audio witness before enabling a separately
reviewed stock-recovery cycle.

## Build and verify

```sh
./gumiw :devices:omi-cv1:application-updater:android:check
```

The scoped check runs unit/lint gates, verifies both local input artifacts, builds the APK, and audits
the packaged permission and firmware-asset boundary. The debug APK is emitted at:

```text
devices/omi-cv1/application-updater/android/build/outputs/apk/debug/android-debug.apk
```

Building or installing the APK conveys no authorization to upload. The first physical mutation still
requires a fresh preflight and an explicit owner action naming the exact canary SHA-256.

## Prepare the qualified physical phone

With the owned Motorola connected over USB, unlocked, and charged to at least 80%, run:

```sh
devices/omi-cv1/application-updater/android/scripts/prepare-physical-phone.sh
```

The script ignores emulators, requires exactly one physical USB phone, binds the first-flash lab to
the qualified `motorola edge 60 fusion` on API 36, reruns the closed APK audit, verifies that the
installed APK bytes match the local artifact, and launches the lab. It performs no Omi connection or
firmware command. Its handoff ends after the fresh disclosed image-state review and explicitly before
one-shot owner authorization.
