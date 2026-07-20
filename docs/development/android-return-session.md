# Android return session

This is the short orchestration sheet for owned-Android sessions. The device-diagnostic portion was
executed on 2026-07-20; the procedure remains here for repeatability. Local unit/simulation success is
not substituted for a physical result.

## 2026-07-20 result

- The disclosure-sheet instrumentation test passed on the Motorola and the owner physically confirmed
  the scrollable modal/pinned action. The broader seven-case Android storage suite is not recorded as
  completed by this device session.
- The stock-address preflight ran process-locally. Its allowlisted verdicts remain diagnostic only and
  did not establish physical identity, ownership, association, or cross-process address stability.
- Driver negotiation completed against `Based Hardware` / `Omi CV 1` / `omi-stock/3.0.12`, exposing
  audio, button, haptic, and power capabilities on an MTU-23, LE-2M/2M, `NOT_BONDED` session.
- The read-only image-state probe returned the exact published application hash and no network row, so
  the corrected result is `APPLICATION_MATCH_NETWORK_UNOBSERVED`. This remains a hard stop for generic
  OTA; Decision 0005 later allowed only the exact two-transition application flash lab.
- The bounded quiet-room audio probe qualified 532 Opus frames over 9,999 ms at MTU 498, LE-2M/2M,
  `NOT_BONDED`, with zero sequence gaps/discontinuities and no retained audio/content digest.
- The first audio setup attempt timed out because the Omi was physically off. Owner button attempts did
  not wake it; charger insertion recovered it and the one retry succeeded. The trigger, timing, exact
  attempted press grammar, and root cause remain unknown. No firmware write occurred.

## Before connecting

- Charge the phone and Omi above 50%.
- Keep the Omi sealed. No programmer, debug probe, pendant opening, pairing reset, bootloader unlock,
  firmware upload, erase, or stock-ring read is part of this session.
- Use one USB-connected, unlocked Android phone with USB debugging approved. Disconnect other Android
  devices/emulators and disable Bluetooth on any other host that has used this Omi.
- Keep the stock Omi app force-stopped without clearing its data or bond.

## Phase A — real Android framework primitives, no Omi required

From the repository root, confirm the workspace-pinned `adb` lists exactly one usable handset, then
run the two connected suites:

```sh
local/toolchains/android-sdk/platform-tools/adb devices -l
./gumiw :edge:platforms:android:connectedDebugAndroidTest --console=plain
./gumiw :edge:shell:android:connectedDebugAndroidTest --console=plain
```

The original five storage primitive cases pass on an API 36 ARM64 emulator. Two subsequently added
cancellation-after-ownership and operational-lease exclusivity cases compile but have not run on any
Android target; this command now executes all seven. Existing results are local Android-framework
evidence, not owned-handset/OEM evidence. This target-only witness uses an instrumentation-specific private storage
directory and Keystore alias prefix. It exercises the Android Keystore, encrypted SQLite/WAL ledger,
immutable no-backup payload files, file/directory flushes, restart recovery, and fail-closed key-loss
behavior. The test cleans its own test data and aliases. Compilation alone is only prepared evidence;
the storage command is the first owned-handset/OEM execution evidence for those primitives.

The shell command runs seven cases on the owned handset: the original three Intent/framework cases
plus four presentation-only Compose cases. They check stable UUID retry identity, malformed typed-extra
rejection, multi-device focus, fail-safe truth rendering, disabled controls, and accessibility semantics;
they do not start a real Omi session or prove foreground-service survival.

If it fails, preserve the exact Gradle/instrumentation output. Do not reinterpret a build success as a
storage pass. The separate content-free Omi diagnostic may still proceed because it neither composes
the operational spool nor retains audio, but Gate 2 durability remains open.

## Phase B — install the exact diagnostic APK

Follow the full guardrails in
[the Omi image-state handoff](omi-image-state-handoff.md), beginning with:

```sh
devices/omi-cv1/tests/hardware-in-loop/android-image-state-probe.sh prepare
```

`prepare` runs the targeted fail-closed Android/Omi gate (`verifyArchitecture`, SDK/runtime/driver/
simulator tests, Android platform and shell unit tests, shell lint, and debug APK assembly). It does not
run unrelated cloud apps or substitute a full workspace gate for this physical procedure. It then
installs the exact debug APK, binds its hash and build inputs to one live Gumi process, and launches the
foreground diagnostic. It does not connect to the Omi or perform an MCU Manager request automatically.

## Phase C — owner-tapped Omi evidence

First run the equality-only stock-address diagnostic before any connection action:

1. grant Nearby Devices, start a fresh scan, wait for exactly one Omi card, then tap **Stop**;
2. tap **Capture single-candidate baseline** exactly once;
3. tap **Start scan**, wait for exactly one Omi card, tap **Stop**, and record only the displayed
   `SAME`, `CHANGED`, or `INCONCLUSIVE` fresh-scan verdict;
4. repeat that stop → physical change → fresh scan → exactly-one-card → stop sequence for (a) an
   out-of-range disappearance and return, (b) one ordinary power cycle only if its safe external
   control is already known, and (c) Android Bluetooth off then on; and
5. stop on multiple candidates, any pairing prompt, or a lost baseline.

This path performs no connect, write, bond/pair, firmware, audio, or runtime action. The baseline and
comparison are process-local scan-address observations, never identity or ownership. Activity/process
replacement clears the baseline; it cannot prove stability across recreation. That question belongs
to the later user-mediated Companion association test. Do not collect Bluetooth settings, logcat, a
raw address, or an endpoint reference; a note may contain only the named scenario and allowlisted
verdict. Because this probe never opens GATT, its “disconnect” leg is only scan disappearance/return;
it issues no connect or disconnect command. The detailed handoff contains the exact no-guess
power-cycle and Bluetooth sequence.

Then perform the existing connected diagnostics in order, using only the buttons and capture commands
prescribed by the detailed handoff:

1. observe exactly one freshly scanned Omi card;
2. **Connect + negotiate driver**, then `capture-driver`;
3. review and run the disclosed read-only MCU image-state request, then `capture-firmware`;
4. continue to the bounded read-only audio witness only if the oracle says
   `MATCHES_PUBLISHED_V3012` or `APPLICATION_MATCH_NETWORK_UNOBSERVED`; neither status by itself
   authorizes firmware mutation; and
5. optionally run the disclosed ten-second metadata-only audio witness in a controlled quiet room,
   then `capture-audio`.

The audio continuation intentionally activates the stock pendant microphone for ten seconds. It is
optional and must be skipped if consent/quiet-room conditions cannot be maintained. It records no
payload or content digest.

## Phase D — stock human-I/O observation only

This is source-versus-owned-unit inventory, not Gumi v1 conformance. Run it only after every connected
diagnostic has released its BLE session, with Gumi disconnected and no private conversation nearby:

1. after one ordinary power cycle whose safe retail control is already known, record the visible boot
   color/order/duration and whether any haptic occurs;
2. observe one approximately 100 ms single tap; after returning to the same stock boot state, observe
   two approximately 100 ms taps separated by 200 ms; after returning again, observe one 1,200 ms hold;
   stop the current gesture as soon as the unit powers off, as v3.0.12 source predicts for a tap;
3. for each gesture, record only `no visible response`, the logical color sequence, approximate duration,
   haptic count/approximate duration, and whether the device disconnected or powered off; and
4. do not write the haptic characteristic, change LED dimming, subscribe to audio, read stored media,
   repeat a surprising gesture, or infer microphone state from an LED.

These observations can confirm or contradict stock-source behavior. They cannot qualify the proposed
30/350/500/2,000 ms grammar, privacy floor, two-package RGB mapping, haptic distinguishability, or
PDM-to-indicator ordering; those require custom firmware plus a separately reviewed HIL procedure.

The 2026-07-20 charger-only recovery observation is already a contradiction worth preserving: pinned
v3.0.12 source intends its active-low button GPIO to wake from system-off, while the owned sealed unit
did not wake from owner button attempts. Do not repeat or reinterpret that uncontrolled observation as
a gesture matrix. A later power qualification must control the transition into Off, the press waveform
and duration, battery/charger state, indication, and post-wake microphone truth.

## Stop conditions

Stop the relevant phase on multiple visible Omi candidates, any bond prompt, stale/recreated Activity,
firmware `MISMATCH`/`TRANSITIONAL`/`INCOMPLETE`, a second timeout/transport failure, insufficient audio
coverage, or any evidence-binding failure. Do not compensate with repeated taps, a firmware operation,
ring cursor movement, data clearing, or a different app.

## Operational runtime is not part of this prepared diagnostic

The visible runtime controls are a fail-closed service scaffold, not a recording path. A portable
one-device operational runtime, process-global Android storage adapter, and generation-fenced shell
bridge now pass offline tests, but the current production factory still reports association/recovery
unavailable and does not compose them. The stock Omi driver also has no provisioned Gumi `deviceId`,
`CaptureControl`, or capture-state observation, and no live-media owner connects BLE notifications to
mux/checkpoint/spool recovery. Production binding/endpoint resolution, cloud auth/dependency wiring,
`INTERNET`, durable user-stop restoration, and a shell freshness scheduler remain absent.

Do not use **Start runtime** to claim recording, audio durability, upload, or background continuity in
this session. Once the staged composition in the roadmap lands, its first separate handset witness is:

1. user-mediated foreground selection, or Companion association only after the process-local preflight
   permits implementation and the separate association-resolution gate passes;
2. encrypted storage opens `Ready` before BLE;
3. explicit start obtains the visible foreground lease;
4. exactly one Omi session reports real link and power while capture stays unverified;
5. zero audio notification subscription and no ring/cursor operation; and
6. explicit stop closes the session and service cleanly.

If the stock unbonded address rotates, skip Companion presence and retain explicit foreground selection.
Recording stays disabled until a media owner, capture truth, durable checkpoint policy, and custom
firmware/security gates exist. Upload is qualified separately with already-durable synthetic chunks and
real scoped cloud authorization, never by opportunistically forwarding diagnostic audio.

That separate first witness is constrained to one explicitly started device and one process-global
spool; its recovered backlog is edge-host-global. The local process-owner primitive now provides one
foreground-execution lease, one spool owner, and a `DeviceId`-keyed runtime registry/command router.
Before production composition or more-device claims, it still needs durable binding/user-stop state,
endpoint resolution, an explicit runtime factory, and physical process/OEM evidence. One foreground
host or one spool must not be instantiated per device.

The operational `connectedDevice` service, portable operational runtime, shell bridge, and encrypted
store adapter are locally executable, but they are deliberately outside this diagnostic session. No
process-kill/reboot/OEM survival claim closes
until Companion association, durable binding/user-stop policy, portable-shell/Omi/storage/cloud
composition, and real device leases exist and the named handset matrix is run.

## What a successful session closes

- Phase A: one physical Android Keystore/SQLite/filesystem witness plus shell Intent/framework cases,
  not foreground-service, forced-death/reboot, or OEM-lifecycle qualification.
- Address diagnostic: only whether Android's stock scan-address mapping compared `SAME`, `CHANGED`,
  or `INCONCLUSIVE` within one Activity/process; never physical identity, ownership, or cross-process
  address stability.
- Driver capture: real portable driver negotiation against the owned unit.
- Firmware capture: exact installed application truth plus explicitly unobserved network/secondary state,
  without a firmware mutation.
- Optional audio capture: bounded stock live-notification shape/cadence metadata, not a recording
  product path; completed once on 2026-07-20 while `NOT_BONDED`.

It does not close custom firmware, background recording, cloud deployment, transcription, Astrale live
installation, privacy measurement, or M1 end-to-end acceptance.
