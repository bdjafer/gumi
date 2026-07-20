# Omi CV1 image-state handoff

For the short ordered session checklist, including the separate Android encrypted-storage
instrumentation phase, start with [the Android return session](android-return-session.md). This document
remains the authoritative detailed Omi interaction and evidence procedure.

This is the authoritative repeatable procedure for the owned, sealed Omi CV1. Its driver, firmware, and
bounded-audio phases were executed on 2026-07-20; the preserved results are recorded below. No pendant
opening, pairing, firmware upload, bootloader unlock, reset, erase, or stock Omi app installation was
performed. The procedure did install or replace the Gumi debug app on the phone as disclosed below.

## What is already prepared

- The Gumi Android shell performs the observed read-only GATT inventory itself.
- A two-step MCU Manager flow uses a dedicated scrollable owner-review sheet with a pinned explicit
  action; the long disclosure no longer expands inside the diagnostic page.
- The firmware port exposes image-state inspection only. Upload, test, confirm, reset, erase, file,
  settings, and shell operations are absent and rejected by the architecture check.
- The official v3.0.12 artifact matching the pendant has been decoded and cryptographically verified.
- Offline tests cover Nordic response mapping, cancellation/release behavior, and the v3.0.12 oracle.
- The firmware controller enforces a 60-second response deadline. Timeout cancellation propagates to
  the inspector's transport-release path and surfaces `MCU_IMAGE_STATE_TIMEOUT` locally.
- A separate two-step live-audio metadata sheet remains locked until the exact published application
  image is verified as either `MATCHES_PUBLISHED_V3012` or
  `APPLICATION_MATCH_NETWORK_UNOBSERVED`. It uses only the negotiated `AudioInputV1` handle and the
  shared RFC 6716 packet inspector; it has no Omi-specific audio action or private Opus parser.
- Connected GATT inspection, driver negotiation, firmware image-state, and live-audio actions share one
  process-scoped diagnostic lease. BLE discovery itself is not a lease owner: scan start is blocked
  while that gate is busy, and each connection action stops scanning before trying its lease. A
  cancellation keeps the lease until controller and transport cleanup finish; a replacement Activity
  observes the same busy lease rather than opening a competing connection.
- Every scan start begins a new observation generation: prior cards and disclosure reviews are dropped,
  the completed firmware result is invalidated, and its process-local audio unlock is revoked before a
  freshly observed card becomes actionable.
- An equality-only stock-address panel may retain one explicitly tapped, single-candidate baseline in
  the current Activity/controller. Later scan generations expose only `SAME`, `CHANGED`, or
  `INCONCLUSIVE`; zero, multiple, stale, or unresolved candidates are `INCONCLUSIVE`. The Android BLE
  address and Gumi endpoint reference are never rendered, logged, persisted, or added to evidence.

The semantic image-state read does require temporary on-air writes: enabling SMP notifications and
sending MCU Manager READ requests. It requests ATT MTU 23 and disconnects immediately after the
response. No persistent Omi mutation is expected; the separate phone-side Gumi installation is
explicitly disclosed below.

## Morning procedure

1. Charge both the phone and Omi above 50%. The script verifies the phone threshold; keep the Omi in
   its charging dock until this procedure if its level is uncertain. Gumi requires Android 10 / API 29
   or newer.
2. If the stock Omi app is already installed, use it only if needed to confirm charge, then fully
   disconnect it and open **Android Settings → Apps → Omi → Force stop**. Do not clear its data and do
   not remove an existing bond. Disable Bluetooth on any other phone, tablet, or computer that has used
   this Omi: the stock pendant exposes one BLE connection slot.
3. Enable Do Not Disturb, dismiss visible notification content, exit split-screen mode, and lock the
   phone's current portrait orientation before evidence capture. Do not rotate it during the run: an
   Activity recreation intentionally loses process-local review/qualification state. Connect exactly
   one unlocked Android phone with its USB data cable, accept the USB-debugging prompt, and leave
   Bluetooth enabled. On Android 11 or older, also enable system Location for BLE scanning.
4. If Android shows a pairing or bonding prompt at any point, cancel it and stop. Pairing is neither
   requested nor qualified by this procedure.
5. Hold the Omi close to the phone and run from the repository root:

   ```sh
   devices/omi-cv1/tests/hardware-in-loop/android-image-state-probe.sh prepare
   ```

   `prepare` runs the targeted fail-closed Android/Omi gate: architecture checks, SDK/runtime/driver/
   simulator tests, Android platform and shell unit tests, shell lint, and debug APK assembly. It does
   not run unrelated cloud apps or claim the full workspace gate. It installs or replaces package
   `dev.gumi.shell` with `adb install -r`, preserves existing Gumi app data, then force-stops and
   relaunches Gumi. It records the exact installed APK before any evidence can be captured. It does not
   connect to the Omi and does not run MCU Manager automatically.

6. If Gumi shows **Grant Nearby Devices**, tap it and approve the Android prompt. Wait for the Omi
   card; if scanning is stopped, tap **Start scan**. A scan start deliberately removes any card and
   firmware/audio review inherited from the previous scan generation, so wait for the single Omi card
   to be observed again before tapping an action. Stop if more than one Omi card is present: this
   diagnostic has not yet qualified a strong physical-identity binding and RSSI is not identity.
7. Before any connection action, run the stock-address diagnostic once:

   1. with exactly one current Omi card visible, tap **Stop**, then tap
      **Capture single-candidate baseline** exactly once;
   2. tap **Start scan**, wait for exactly one card, tap **Stop**, and read the fresh-scan verdict;
      this unchanged control should ordinarily say `SAME`, but preserve any other result;
   3. for the disappearance/return leg, move the Omi out of scan range, tap **Start scan**, wait ten
      seconds, tap **Stop**, verify that no Omi card appeared and read the zero-candidate
      `INCONCLUSIVE` result. Return the Omi close to the phone, start one new scan, wait for exactly
      one card, stop, and read the new verdict. This is the only “disconnect” leg: the probe never
      opens GATT and issues no connect or disconnect command;
   4. for the power-cycle leg, tap **Stop**, perform exactly one ordinary external power cycle only if
      you already know the unit's normal non-reset control, wait for its normal boot indication, then
      start one new scan, wait for exactly one card, stop, and read the verdict. If the safe control is
      not already known, skip this leg and classify its outcome as `INCONCLUSIVE`; never guess a
      long-press, reset, erase, bootloader, or dock sequence;
   5. for the Android-radio leg, tap **Stop**, turn Bluetooth off once in Quick Settings, wait until
      Android shows it off, turn it on once, return to the same Gumi Activity without rotating or
      swiping it away, then start one new scan, wait for exactly one card, stop, and read the verdict.

   Every comparison is against the one in-memory baseline. Do not tap a connection, GATT, firmware,
   audio, or runtime control during this sequence. Stop on multiple cards or a pairing prompt. If the
   Activity or process is replaced, the baseline deliberately disappears: end that run rather than
   recapturing and joining the results. This probe cannot establish address stability across process
   recreation; a later, separately qualified Companion association/enumeration test owns that question.
   It is never evidence of physical identity, ownership, association, or capture authority. There is
   no HIL capture command for this probe; any manual note may contain only the scenario name and the
   displayed `SAME`, `CHANGED`, or `INCONCLUSIVE` verdict—never Bluetooth settings, logcat, an address,
   or an endpoint reference.
8. Tap **Start scan** once more and wait for exactly one fresh Omi card. On that card, tap
   **Connect + negotiate driver**. This path connects, discovers,
   reads Manufacturer/Model/Firmware plus exact codec ID `21` (Opus), projects the four typed stock
   capabilities, and disconnects. It performs no characteristic write, notification subscription,
   audio activation, or haptic action.
9. Leave the negotiated Manufacturer/Model/Driver/Protocol and capability result visible, with no
   error, and run:

   ```sh
   devices/omi-cv1/tests/hardware-in-loop/android-image-state-probe.sh capture-driver
   ```

10. On the same retained Omi card, tap **Review MCU image-state read**. A dedicated owner-review sheet
   opens; no BLE operation starts merely by opening it.
11. Verify that the sheet discloses SMP discovery, ATT MTU 23, the response-notification CCCD write,
   parameter READ `0/6`, image-state READ `1/0`, disconnect/release, no expected persistent mutation,
   and no upload/test/confirm/reset/erase/files/settings/shell operation. Then tap
   **Run disclosed image-state read** exactly once.
12. Leave the successful slot list and published-release oracle visible and run:

   ```sh
   devices/omi-cv1/tests/hardware-in-loop/android-image-state-probe.sh capture-firmware
   ```

The script never synthesizes a screen tap. Every connection action requires the owner's physical tap.
Each controller logs a process-monotonic attempt ID at start and success. Capture refuses an older
success when a newer attempt has started.

`prepare` creates a random, non-device `hil_run_id` and binds it to the launched Gumi process ID plus
Linux process-start ticks. Every later phase must use that same live process; a process restart requires
another `prepare`. It records only allowlisted host facts needed to reproduce BLE behavior: Android
manufacturer/model, SDK/API, release, security-patch level, and battery percentage. It never queries or
stores the Android serial, Android ID, build fingerprint, Bluetooth address, or Gumi endpoint ID.

The preparation manifest records preparation-time repository HEAD and dirty-worktree truth, local and
installed APK hashes, and the Android build-input manifest hash. Capture requires HEAD to remain equal,
records both preparation and capture dirty truth, re-hashes the installed base APK, and regenerates the
build-input manifest byte-for-byte. That manifest covers root settings/build/Gradle inputs, `gumiw`,
`gradlew`, `edge/`, and `devices/omi-cv1/`, including untracked source, while excluding `.env*`, local or
keystore credentials, build output, `local/`, `node_modules/`, and Gradle state. It contains paths and
hashes only, never environment contents or secrets. The installed APK hash remains the executable
identity; a dirty `prepared_repo_head_base` is only its base commit.

Each capture first creates a private hidden temporary directory under ignored
`local/hardware-in-loop/omi-cv1/`. It requires Do Not Disturb, foreground Gumi, and an awake/unlocked
phone, then snapshots only the scoped probe tag and Gumi screen before slower provenance hashing can
allow Android's small log buffer to evict the result. It next verifies the same process/run, APK, HEAD,
host facts, and build inputs, and re-verifies Do Not Disturb, foreground, awake/unlocked, and process
identity before publication. It requires a nonempty matching-tag log and valid nonempty PNG, then
writes a nonempty manifest. Only after every check passes does one atomic rename publish a
collision-safe, phase/attempt/run-qualified evidence directory. Failure or interruption removes the
temporary directory, so a partial or stale capture is never published. Each bundle contains the
redacted phase log, one screenshot, build-input manifest, copied preparation manifest, and capture
manifest.

The firmware read automatically cancels at 60 seconds, releases the MCU Manager transport, and shows
`MCU_IMAGE_STATE_TIMEOUT`. If the UI itself fails to surface that terminal state, do not tap again:
press Home once as a backup so `MainActivity.onStop` cancels the controller and releases its transport.
Reopen Gumi, tap **Start scan**, select a freshly observed single Omi, and retry that failed phase once.
Preserve a second timeout or transport failure and stop. Apply the same Home/onStop backup to a driver
connection whose UI remains in progress for 60 seconds.

## Expected installed-release result

| Image | Expected active slot | MCUboot version | Expected MCUboot hash |
| --- | --- | --- | --- |
| Application `0` | `0` | header `0.0.0+0`; wire `0.0.0` | `0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36` |
| Network `1` | `0` | header `0.0.0+0`; wire `0.0.0` | `267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089` |

The owned-unit read on 2026-07-20 returned the exact application row and no network row. The corrected
transactional capture's scoped log SHA-256 is
`c059032b70784e993ac847d144f1228dafd2ccbc37886cea70994fdd6097b2b0`, its captured screen SHA-256 is
`e5676ba4b2ab3d9e85c7c260c1d66d6c3a8dcc4bfa319d2f14a4fae516ddb909`, and its capture-manifest
SHA-256 is `521efa8b71fa29accface043c8af4ec7f708127c8200f928958ad7904515a54f`. No Android identifier,
Bluetooth address/endpoint, full logcat, or audio content was collected. Pinned Zephyr source proves
that build number zero is emitted as `0.0.0` and that unreadable slot headers are omitted from the
successful list, so the corrected result is `APPLICATION_MATCH_NETWORK_UNOBSERVED`, not `MISMATCH`.

`MATCHES_PUBLISHED_V3012` means both active, bootable images match the official release and no slot is
pending. It does not assert that `confirmed` or `permanent` must have a particular representation.

Stop without attempting generic OTA if the result is `MISMATCH`, `TRANSITIONAL`, or `INCOMPLETE`.
`APPLICATION_MATCH_NETWORK_UNOBSERVED` proves the exact application but not network or secondary-slot
identity; only the two exact, owner-reviewed application transitions in Decision 0005 may accept it.
For `ENDPOINT_EXPIRED`, scan and review the freshly observed endpoint again. For one transport failure,
move the Omi closer and retry once; preserve a second failure rather than looping requests. The same
one-retry ceiling applies to the 60-second safe-abort path above.

## Immediate continuation after application identity

The exact application match closes only the read-only audio prerequisite. Do not perform the audio
step below for `MISMATCH`, `TRANSITIONAL`, `INCOMPLETE`, a firmware transport error, or a result that
has not yet been captured with `capture-firmware`. `APPLICATION_MATCH_NETWORK_UNOBSERVED` permits the
bounded metadata witness and, separately, only Decision 0005's dedicated two-transition flash lab.

## Post-application-match 10-second live-audio metadata witness

This optional continuation is observation-only but it does activate the stock live microphone for ten
seconds. Perform it only on the owned unit, in a controlled quiet room with no sensitive speech,
playing media, conversations, or bystanders. Tell anyone who could enter that the microphone will be
active. If those conditions cannot be maintained, stop after the image-state capture.

1. Confirm that the visible image oracle says `MATCHES_PUBLISHED_V3012` or
   `APPLICATION_MATCH_NETWORK_UNOBSERVED`, and that `capture-firmware` completed for the current app
   process and APK.
2. On the same single Omi card, tap **Review 10-second live-audio metadata probe**. This first action
   only opens the disclosure; it does not connect or activate audio.

Gumi binds this unlock to the successful firmware probe's process-local ephemeral endpoint ID. It
never renders or persists that ID. A different card or a fresh endpoint ID stays locked; repeat the
image-state gate rather than assuming it is the same pendant.

3. Verify the panel discloses all of the following before proceeding:

   - connect, stock-driver service discovery, its negotiated identity reads, and an exact codec-ID
     `21` (Opus) read;
   - request ATT MTU 512, matching the current upstream Omi Android audio connection path;
   - validate and strip the stock `u16` little-endian sequence plus `u8` fragment-index envelope;
   - the audio CCCD write that enables live notifications;
   - transient in-memory observation for exactly 10 seconds;
   - hard limits of 1,000 frames and 1,276,000 payload bytes;
   - qualification floors of at least 450 frames and at least 8,500 ms from first to last receive,
     with no receive interarrival above 250 ms;
   - automatic subscription, audio-stream, device-session, and BLE-transport close;
   - no stored-audio/ring read, cursor advance, haptic action, firmware/configuration write, upload,
     file/database persistence, content logging, content digest, or background service.

4. Reconfirm that the room is quiet, then tap **Run disclosed 10-second metadata probe** exactly once.
   Do not speak, play audio, press the pendant, background the app, or lock the phone during the probe.
5. A qualified result must say `AUDIO_METADATA_QUALIFIED`, contain at least 450 frames over at least
   8,500 ms of receive span, and have maximum BLE receive interarrival at most 250 ms. It shows only
   format/framing, frame and byte counts, payload-size bounds (each must be 1–160 bytes), sequence
   range/gaps, discontinuity count, actual negotiated MTU (at least 166), PHY/bond facts, receive
   span/interarrival bounds, and one-frame 20 ms Opus TOC facts. It is published only after every
   stream/session/transport owner has closed.
6. Leave that result visible and run:

   ```sh
   devices/omi-cv1/tests/hardware-in-loop/android-image-state-probe.sh capture-audio
   ```

The audio capture mode reads only the process-local `GumiAudioProbe` tag and takes one screenshot. Its
manifest explicitly records that no audio payload, audio-content digest, content log, full-device
logcat, Bluetooth address, or endpoint ID was collected. The source sequence range is aggregate
continuity metadata, not a persisted BLE identity. The script rejects a stale success if a newer audio
attempt started and re-verifies every preparation binding before atomically publishing evidence.

Any empty or insufficient-coverage stream, receive starvation, packet-inspector rejection, frame/byte
bound, sequence gap, discontinuity, notification drop or event overflow, disconnect, setup/close
timeout, close failure, or unobserved terminal close is non-qualified and emits no success marker.
Preserve the visible code and stop; do not loop microphone activations. Press Home only to cancel a
genuinely stuck run, which triggers the same stream/device/transport cleanup and remains non-qualified.

### Owned-unit audio result and setup-timeout context

Attempt 2 on 2026-07-20 completed as `AUDIO_METADATA_QUALIFIED`: 532 frames, 49,134 aggregate payload
bytes, payload bounds 71–120 bytes, sequence 0–531, zero gaps/discontinuities, 9,999 ms receive span,
172 ms maximum interarrival, Opus/16 kHz/mono/raw-packet with one 20 ms frame, ATT MTU 498, LE 2M/2M,
and `NOT_BONDED`. The result was published only after every owner closed. The log SHA-256 is
`41e0eb10c3faf616b6cb066cfc8aea490fdc703811185d923cf6cfe2f63559c2`, screenshot SHA-256 is
`d31fd13a678c216049d82293f44521fa7d386f228255593912add31b6f725d96`, and manifest SHA-256 is
`cf0e90fbec1166e73ba1ed7f51362d4d46f5335cae7a820a06e96af74ad9ffd2`. The manifest records that no
audio bytes/digest/content log, full logcat, Bluetooth address/endpoint, Android identifiers, file,
database, upload, or stock-ring read was collected.

Attempt 1 produced `AUDIO_SETUP_TIMEOUT` because the pendant was physically off. The owner could not
wake it with button taps/presses and recovered it by charger insertion before the one successful retry.
This is an owner-observed stock-unit power/wake gap: the shutdown trigger, elapsed time, exact attempted
press grammar, root cause, and charger electrical path are unknown. It does not imply pairing is
required—the successful retry was `NOT_BONDED`—and no Gumi firmware was installed. For any repeat,
establish that the pendant is awake before consuming the single allowed audio attempt; a fresh scan
invalidates the prior image/audio authority and therefore requires a new image-state review.

After this bounded witness, the remaining non-destructive work is the broader stock-behavior matrix:
single/double/hold gestures, disconnect/reconnect, offline storage semantics, phone lock/app
termination, pendant reboot persistence, and a controlled power/wake baseline that resolves the
charger-only recovery observation. No custom or stock image is uploaded until those observations and
the application-only recovery procedure are qualified. Stored bytes remain quarantined: this workflow
never reads ring content, advances a cursor, or clears storage.
