# Omi CV1 Android application flash lab

This module builds the isolated `dev.gumi.omicv1.flashlab` Android application. It is a local,
device-specific owner tool, not part of the Gumi control-plane shell or portable edge runtime.

Its detailed Omi phases now project onto the device-neutral `FirmwareMaintenanceStage` vocabulary from
`edge/sdk`. The SDK also provides a pure exact-artifact maintenance reducer for safety review,
preflight, digest-bound authorization, monotonic transfer, restart, validation, completion, and
outcome-unknown failure. Flash Lab retains its stricter Omi-only controller and closed artifact source;
future product UI can reuse the portable lifecycle without depending on this Android application.

## Closed write surface

- No production module depends on this module and every implementation type remains `internal`.
- The current path closes recovery-only → recording-root-provisioner-0001 →
  functional-recording-0006 → legacy-storage-reclaimer-0002 → functional-recording-0007, retains
  the exact v0003/v0004/v0005 repair evidence, and keeps every catalogued functional-to-recovery,
  stock, and capture-self-test return path.
- Superseded reclaimer v0001 remains catalogued only as a recognized OTA-stranded incident hash.
  Preflight rejects every BLE update/reset from it with an explicit SWD-required result before loading
  any target artifact.
- The build verifies and packages exactly seven signed application-image-`0` binaries plus the one
  official v3.0.12 network-image-`1` binary extracted from Based Hardware's pinned OTA archive.
- The APK audit rejects OTA ZIPs, HEX files, Internet/storage/install permissions, and every firmware
  asset outside that exact eight-file set.
- Normal Gumi transitions retain Nordic MCU Manager's explicit
  `imageUpload(bytes, 0, callback)` call and cannot route bytes to image `1`.
- One separately typed normalization adapter uses Nordic's `ImageSet` with exactly the official
  v3.0.12 application at image `0` and network core at image `1`. It is reachable only from the exact
  official v3.0.7 application hash plus Device Information revision `3.0.7`; it uses confirm-only
  overwrite mode, performs no settings erase, and requests one reset after both images.
- Generic firmware selection, arbitrary image numbers, file pickers, arbitrary erase, test-boot,
  shell, Internet, and unbounded retry APIs remain absent. One separately typed repair may erase only
  inactive application image `0` slot `1`, only while the exact confirmed reclaimer remains active,
  with a separate one-shot owner authorization and a post-erase proof that the active hash is
  unchanged and slot `1` is absent. Resume accepts only the two exact v3.0.12 secondary hashes.
- A prepared plan pins the process-local endpoint, exact source application state, target file hash,
  target MCUboot hash, compatibility key, version, and network evidence policy.
- A one-shot two-minute owner capability is consumed before transport opens; execution immediately
  re-reads source state, Omi identity, firmware revision, and device battery before sending the first
  byte.
- Flash Lab independently reads and displays the Omi Battery Service. Low or unavailable battery is
  warning-only and never blocks flashing. The charger-connected owner attestation remains explicit.
- Staged and confirmed state must show exactly the planned inactive application image. Confirmation
  and reset never run after a rejected staged state.
- A reset response or reset-time disconnect yields only `AWAITING_POST_REBOOT_VALIDATION`. A fresh
  state read must prove the target application active before the lab reports validation. Recovery
  validation additionally requires exact status `01070123`, microphone-off/capture-denied flags, the
  stock audio characteristic absent, and the remaining stock functional services absent.
- A validated recovery endpoint exposes a read-only image-state plus GATT recheck action, allowing the
  same evidence to be repeated after the bounded off-charger observation without preparing or
  authorizing another update. The app enforces at least ten minutes from initial recovery validation
  before it permits that recheck.
- Failure and cancellation have no in-app retry route. Cancellation stops upload, releases transport,
  and never confirms or resets.
- A validated capture self-test endpoint exposes only its 32-byte media-free status and one-byte arm
  write. One phone arm creates a 15-second lease; capture still requires a continuous two-second
  device-button hold. The lab counts three consecutive safe passes, resets the count on a safe failure,
  and stops on microphone-unknown evidence.
- A validated functional endpoint requires the exact 40-byte status, immutable 16-byte capability
  descriptor, empty Omi-family identity service, and absence of recovery, self-test, and stock
  functional services. A missing hardware-root key is reported as authentic fail-closed diagnostics,
  never as recording-ready. Functional firmware may return only to recovery-only through a new
  preflight and authorization.
- A validated provisioner endpoint requires its recovery transport, empty family-identity service,
  exact one-characteristic status-only surface, and a terminal `PROVISIONED` or `ALREADY_PRESENT`
  state proving microphone-off, MEXT presence, and domain-separated derivation. Flash Lab never reads
  a root, derivative, or digest. It re-proves this state before offering functional-recording-0006.
- A validated legacy-storage reclaimer endpoint exposes one exact 40-byte status characteristic. It
  can authorize functional-recording-0007 only after proving that `/SD:/audio/a01.txt` was either
  absent or an exact 505,118,720-byte regular file that was unlinked, and that at least 4 MiB is free.
  Wrong size/type, early mutation, malformed topology, or insufficient postcondition evidence blocks
  v0007. A terminal refusal remains eligible only for a separately authorized recovery-only return.

## Network-core evidence policy

The owned stock v3.0.12 unit exposes no MCU Manager image-`1` row. Pinned Zephyr omits a row whose
MCUboot header cannot be read, so the exact network hash and secondary state cannot be recovered through
this stock SMP surface.

For the current pinned application transitions only, complete absence of every image-`1` row is accepted.
If any image-`1` row becomes visible at any preflight, staged, confirmed, or post-reboot read, the lab
requires the exact published active hash and rejects pending or populated secondary state. This does
not claim that the network hash was physically re-observed; it relies on a closed image-`0` write path,
the absence of network bytes, and the already qualified BLE/audio behavior. The accepted residual risk
is recorded in [Decision 0005](../../../../docs/decisions/0005-omi-cv1-network-unobserved-ota.md).

The v3.0.7 normalization route is different: its source application and network release hashes differ
from v3.0.12, so an application-only Gumi transition is rejected. The route uploads and confirms both
exact official v3.0.12 images using Nordic's multi-image flow. Before reset, Nordic validates and
confirms each target hash; after reset, Flash Lab requires the exact v3.0.12 application hash and
Device Information revision. Image `1` may again be unobserved after reboot because that limitation is
part of the stock SMP surface.

## Owner flow

The lab requires:

1. phone battery at least 60%;
2. owner attestations for force-stopped official app, charger connected, and no-rollback risk,
   followed by an independent read-only Omi identity and warning-only battery reading;
3. an exact Omi advertisement selected from a process-local address directory;
4. a fresh semantic image-state preflight;
5. explicit acknowledgement of the full target file SHA-256, both exact official SHA-256 values for
   stock normalization, or the exact active hash plus inactive application slot `1` for the bounded
   erase repair;
6. either one image-`0` update, the single closed v3.0.7 → v3.0.12 official dual-image normalization,
   or one inactive application-slot erase that uploads, confirms, and resets nothing;
   and
7. a post-reboot image-state read against the selected candidate; a changed BLE endpoint remains
   untrusted until the complete target proof succeeds;
8. a mode-specific GATT topology/status proof;
9. for recovery-only, at least ten minutes off charger before the combined read-only image/GATT
   recheck; or
10. for capture self-test, three explicit arms and three continuous two-second physical holds with
    non-sensitive test sound, followed by a separately authorized recovery-only return; or
11. for recording-root provisioning, an explicit irreversible-write disclosure, terminal
    status-only proof, and a separate authorization before the functional transition; or
12. for legacy-storage reclaim, an explicit permanent-deletion disclosure naming the one exact path,
    expected type and expected byte size, followed by a terminal exact status proof and a separate
    authorization before functional v0007; or
13. for functional recording, an exact status/capability read followed by a bounded local
    recording qualification and a separately authorized recovery-only return if any readiness
    precondition is missing.

After recovery-only validation, the lab may be stopped with recovery left installed. Capture self-test,
recording-root provisioning, functional recording, and exact-stock recovery each require a new selected
intent, preflight, review, and one-shot authorization; none starts automatically.

## Build and verify

```sh
./gumiw :devices:omi-cv1:application-updater:android:check
```

The scoped check runs unit/lint gates, verifies the seven application inputs and one network input,
builds the APK, and audits
the packaged permission and firmware-asset boundary. The debug APK is emitted at:

```text
devices/omi-cv1/application-updater/android/build/outputs/apk/debug/android-debug.apk
```

Building or installing the APK conveys no authorization to upload. Every physical mutation requires a
fresh preflight and an explicit owner action naming that transition's exact file SHA-256.

## Owned-unit qualification

On 2026-07-20, the isolated lab completed one separately authorized stock -> canary -> stock
application cycle on the sealed consumer unit:

- canary file SHA-256
  `65e4ae91637e6aa576f6d3f8286db33bc9bd36ece8ece1aaa6b6aa5c5b204f6d` booted as application hash
  `d3b3c74c10c4fa110763ae5523d0aeaa20b9f815b8674167486be6ff6f8052ce`;
- a distinct authorization for stock file SHA-256
  `877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db` restored application hash
  `0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36`;
- each target was freshly observed after reboot as active, bootable, confirmed, and not pending; and
- canary identity/indicator/GATT/audio and recovered-stock driver/GATT/audio checks passed.

Image `1` remained wholly unobserved. This result proves the closed application path and exact stock
recovery once; it does not prove network-core immutability, interruption recovery, or repeatability.

## Current recovery, provisioning, and functional qualification candidates

The APK now packages recovery-only-0001 file SHA-256
`d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc` (MCUboot image hash
`065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57`), capture-port-selftest-0001
file SHA-256 `8f0d0fc35c4d3c56e8f94d528a0b56c0d4af7b44b7bdad2b2fa4f2963e2e3d0e` (MCUboot image hash
`e97fd653a66dce18a7dbd071bc9b21c4fdae4e229de5d245e76ed213c2ca4862`),
recording-root-provisioner-0001 file SHA-256
`e53c58a3ef72773af501a9ae05ceb5ce715b6d8153923ef52609dc5ec1dfa38b`
(MCUboot image hash
   `8a8eef711fd72707905bda05e08ddebc0de2a986d3c1d21b7469c92e7d32b12e`),
   functional-recording-0006 file SHA-256
   `eb811b62fffbf5c5f3ca1684b29073f6cf9cb49ae8e38d5201944c6c36d640a0`
   (MCUboot image hash
   `3a35a3324393e563e36b2748f0b1f842ce5db52834cd0023334d9c9ac3071aa1`),
   legacy-storage-reclaimer-0002 file SHA-256
   `59fb753783c51140f4ee22c9d18dcaaf40bcb240e69c93a3cb2e967999fb1960`
   (MCUboot image hash
   `8fc16e21b238dd4907abb6a4512db1002c4acf75cc079f8fa1ea49e467b412a2`),
   functional-recording-0007 file SHA-256
   `a0d292117b0f2455fc342a2ad39e2b9ce02054e096689018184065df91933b25`
   (MCUboot image hash
   `407df7c1f97b480f45d445d4045b5a124af2d431130a3f07b77b07726301d1e0`),
plus exact stock recovery and the official v3.0.12 network-core binary
(`0e1d067444ca78dd4ea64cb355bc167c1155b84f49b0c7028112c9930602d2a5`,
MCUboot image hash
`267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089`).
It packages no canary or arbitrary network image. On 2026-07-22, the owner authorized the exact recovery image;
the sealed unit booted it active/bootable/confirmed/not-pending and repeated the exact image, status
`01070123`, software revision, and fail-closed GATT topology proof after more than ten minutes off
charger. Image `1` remained unobserved.

On 2026-07-25, a second sealed consumer unit was read without mutation. Its Device Information surface
reported manufacturer `Based Hardware`, model `Omi CV 1`, firmware `3.0.7`, hardware
`Based Hardware Omi`, and battery `21%`. Its active application MCUboot hash
`ab6364926c7df7371a013dfbcf1e3f73f9386b8500f5cbe4153c2883b798877e`
exactly matches the application embedded in Based Hardware's official v3.0.7 OTA archive. The archive's
network hash is
`f8fc9da4ad429d3ac91e5ba12595a330b61d8f2e8cd4fb969be9349680937649`,
which differs from v3.0.12. No upload was attempted. This evidence created the explicit dual-image
normalization gate; physical qualification remains pending until the charger-connected and
no-rollback attestations are active and the warning-only battery reading is disclosed.

The updater now handles firmware-induced BLE address rotation without accepting a candidate on its
advertisement alone: it validates the exact expected MCUboot target against the candidate endpoint,
adds the recovery status/topology proof for recovery-only, and only then commits the new process-local
endpoint. Wrong-target tests preserve the previous binding and stop the flow. This rebind is locally
qualified and still awaits a future authorized physical transition to exercise it end to end.

The capture self-test image has passed the pinned offline target build, exact source/object/map/config
audit, host transition tests, and MCUboot signature verification. It was exercised on the owned unit,
but the session did not preserve a sufficiently exact visible three-pass closeout receipt to promote
the hardware row. Its Android status decoder enforces the firmware's 32-byte schema, exact phase/flag
relationships, minimum PCM/Opus evidence, zero dropped samples/error for pass, empty Omi-family
identity service, absent stock functional services, and recovery transport presence.

The provisioner and functional images have passed pinned exact-board builds, source/object/map/config
audits, MCUboot signature verification, and Android transition/protocol tests. Both include a
15-second task watchdog with an nRF hardware fallback. The provisioner writes only MEXT when empty,
zeroizes transient material, and exposes only non-secret status. Functional v0005 added Nordic's
bounded Mbed TLS allocator and normalized only the SPI-SD driver's missing PM callback; it then passed
the owned-unit READY → continuous-red recording → durable stop → READY lifecycle. Its 12-second
application-core reset subsequently left the still-powered BLE network core unreachable on the sealed
unit. Functional v0006 preserves the recording behavior and cold-cycles the network core before
`bt_enable`, then forces that core off before every application cold reset. The exact-board build,
reset-port call-order test, source/object/map/config audit, MCUboot signature verification, v0005 →
v0006 transition, and v0006 recovery return pass offline. The current owned unit then exposed an
independent full-volume condition caused by stock legacy file `/SD:/audio/a01.txt` at exactly
505,118,720 bytes. The bounded reclaimer and functional v0007 both pass exact-board build, manifest,
signature, protocol, and APK-boundary qualification offline. V0007 reports `-ENOSPC` before attempting
directory creation when the refreshed free-space reading is zero. The exact reclaimer physically
deleted the one 505,118,720-byte legacy file and proved 505,118,720 bytes free. Two separately
authorized v0007 uploads then stopped at `0/221592` bytes with MCU Manager image-group result
`IMG_MGMT_ERR_FLASH_WRITE_FAILED`; fresh state after each attempt still showed only the exact
reclaimer active, bootable, and confirmed. The bounded inactive-slot erase repair is locally
qualified and still requires its own physical authorization before v0007 can be retried.

The sealed-device recovery return does not depend on an inaccessible reset
line: while capture is idle, a continuous five-second button hold makes update
maintenance exclusive until reboot. The lab requires the resulting
`updateAdmitted` status before a functional-to-recovery upload. The two-second
boot hold remains an equivalent fallback.

Recovery-only keeps BLE/SMP reachable without a bond, so the sealed unit remains serviceable. That is
deliberately a development boundary, not production security: the upstream stock-compatible private
signing key is public, and this application is overwriteable under stock MCUboot. Treat it as a logical
safe/recovery application and use it only in controlled physical proximity.

## Prepare the qualified physical phone

With the owned Motorola connected over USB, unlocked, and charged to at least 60%, run:

```sh
devices/omi-cv1/application-updater/android/scripts/prepare-physical-phone.sh
```

The script ignores emulators, requires exactly one physical USB phone, binds the first-flash lab to
the qualified `motorola edge 60 fusion` on API 36, reruns the closed APK audit, verifies that the
installed APK bytes match the local artifact, and launches the lab. It performs no Omi connection or
firmware command. Its handoff ends after the fresh disclosed image-state review and explicitly before
one-shot owner authorization.
