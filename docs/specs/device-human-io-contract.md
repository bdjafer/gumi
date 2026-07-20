# Device human-I/O contract

Status: proposed `gumi.device-human-io/v1`. The semantic rules and default timings in this document are
design inputs, not claims about the owned device. They become qualified only after simulator, firmware,
and hardware-in-loop evidence passes.

The machine-readable Omi CV1 mapping and deterministic examples live under
[`devices/omi-cv1/protocols/human-io/v1`](../../devices/omi-cv1/protocols/human-io/v1/README.md).

## Purpose and authority

This contract defines what a person may do through a device's physical controls, what every physical
and shell output means, and how those meanings survive failure. It is substrate-independent: another
device may map the same semantic events to a screen, speaker, switch, or other accessible output. Exact
Omi CV1 button, RGB, and haptic mappings remain in its device capsule.

Human I/O does not own capture or cloud truth:

- device firmware owns button interpretation, actual microphone state, the non-overridable privacy
  guard, bounded local recording, and physical output arbitration;
- the edge runtime owns requested-versus-acquired coordination, freshness, local durability, policy,
  and the shell projection;
- the shell renders immutable projections and sends correlated commands; and
- cloud reachability and processing status never redefine actual device capture state.

The device's privacy output is a guard around hardware acquisition, not a reflection of an application
request. A general visual-feedback command cannot suppress, dim below the qualified floor, or imitate
an inactive privacy state.

## Evidence boundary

Three different facts must not be collapsed:

1. **Stock source evidence.** Official Omi tag `Omi_CV1_v3.0.12` at commit
   `85159556eac753a088c5efd1b419a5a867508e27` starts the microphone at boot and assigns a detected single
   tap to shutdown; its one-second long press is a notification. It configures the active-low button
   GPIO as the intended system-off wake source. Candidate tag `Omi_CV1_v3.0.20` at
   `aa1133cd17139aa09cbe4883cdf51f15094b9916` also starts the microphone at boot but moves shutdown to a
   three-second hold. Both use overlapping LED meanings for connection, charging, time, boot, and faults.
2. **Owned-unit bench evidence.** The sealed unit reports firmware `3.0.12`, hardware `5.0`, and exposes
   the observed button, haptic, battery, settings, audio, and storage GATT surfaces on an unbonded link.
   A bounded audio metadata witness also succeeded while `NOT_BONDED`. When the unit was later observed
   off, owner button attempts did not wake it and charger insertion recovered it; the off trigger,
   elapsed time, exact attempted press grammar, electrical path, and root cause are unknown. Gesture
   timing, emitted feedback, optical visibility, haptic strength, and capture behavior have not yet
   been measured.
3. **Gumi proposed behavior.** The grammar, patterns, arbitration, lifecycle, and UI semantics below are
   the target for custom firmware and the edge shell. They do not describe stock behavior.

Primary stock source references:

- [v3.0.12 button](https://github.com/BasedHardware/omi/blob/85159556eac753a088c5efd1b419a5a867508e27/omi/firmware/omi/src/lib/core/button.c)
  and [main](https://github.com/BasedHardware/omi/blob/85159556eac753a088c5efd1b419a5a867508e27/omi/firmware/omi/src/main.c);
- [v3.0.20 button](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/omi/src/lib/core/button.c),
  [main](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/omi/src/main.c),
  and [feedback](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/omi/src/feedback.c); and
- the [owned-unit v3.0.12 profile](../../devices/omi-cv1/protocols/gatt/v3.0.12/README.md).

## Orthogonal state

A single status enum cannot represent a device that records while disconnected or adds a realtime turn
over an existing durable recording. The governing projection has these independent axes:

```text
Power:          Off | Booting | Operational | ShuttingDown
MicTruth:       VerifiedOff | Acquiring | Acquired | Releasing | Unknown
BaseRecording:  Inactive | Active(sessionId)
VoiceTurn:      Inactive | Starting | Active(turnId) | Ending | Failed
Link:           Disconnected | Connecting | Authenticating | Ready | Degraded
Maintenance:    Normal | Pairing | Updating | Validating | RecoveryRequired
Storage:        Healthy | Low | Full | Corrupt
Fault:          None | Warning | Recoverable | FatalPrivacy
```

`VoiceTurn` overlays `BaseRecording`. Releasing a VoiceTurn that began during Recording returns to that
same Recording; it does not create a new recording or lose the base session identity.

Every shell projection carries its authority, observation time, freshness, and pending command ID.
Useful authority labels are `DEVICE_REPORTED`, `EDGE_INFERRED`, and `CLOUD_REPORTED`. Only fresh device
evidence may produce a positive “microphone off” claim.

## Privacy and capture invariants

1. `MicTruth != VerifiedOff` requires the physical privacy guard to be continuously active. The sole
   exception is a detected guard-drive failure: that enters `FatalPrivacy`, suppresses further audio
   delivery, and forces microphone release immediately while the shell continues to treat the mic as
   active/unknown. A failed light is never reinterpreted as evidence that the microphone is off.
2. Firmware asserts the privacy guard before asking hardware to acquire PDM and releases it only after
   PDM release is verified. The shell acknowledges entry or exit only after both operations complete.
3. If the privacy output cannot be driven, microphone acquisition is rejected. If that failure occurs
   while acquired, firmware stops capture and keeps the state `Unknown` until release is proven.
4. No stored, transmitted, encoded, or callback-visible audio frame may precede capture acquisition.
5. Idle after boot, reboot, watchdog recovery, or OTA is always microphone-off. An interrupted session
   is closed with a discontinuity; it never silently resumes.
6. Recording works without cloud reachability while qualified local durable capacity exists.
7. VoiceTurn requires an authenticated, unexpired realtime-admission lease. Refusal leaves microphone
   and BaseRecording unchanged. A link failure after admission does not discard the locally durable turn.
8. Update, validation, recovery, pairing confirmation, and shutdown confirmation are exclusive
   maintenance states and require `MicTruth == VerifiedOff`.
9. A wearer gesture and visible privacy light are not participant consent. The applicable consent policy
   remains a separate admission gate.
10. An armed acoustic activity detector is reported independently as `AcousticDetectorArmed`; it does
    not imply a PCM stream or retained audio.

## Default one-button grammar

These timings are the proposed CV1 `gumi.device-human-io/v1` defaults. The semantic event names are
portable; another device may publish a different qualified mapping.

| Rule | Default | Semantic consequence |
| --- | --- | --- |
| Debounce | logical level stable for 30 ms | accept one edge |
| Short press | release before the 500 ms hold deadline | create a tap candidate |
| Double window | second accepted press no later than 350 ms after first accepted release | await second release |
| Double tap | both presses release before their hold deadlines | toggle BaseRecording on second release |
| Single tap | no second press by the 350 ms deadline | repeat current status; never capture |
| Hold | continuously pressed for 500 ms | request VoiceTurn |
| Hold release | accepted release after VoiceTurn commitment | end VoiceTurn |
| Exact-deadline tie | release and hold deadline have the same logical time | release wins; do not capture |

If a second press remains down until its hold deadline, Hold wins and the earlier tap is discarded. No
microphone is acquired while the recognizer is deciding tap versus hold. `VOICE_READY` is emitted only
after the privacy guard, microphone, local durability, and realtime route are acquired; the person begins
speaking after its haptic.

At one logical timestamp, accepted physical edges are processed before gesture timers. Therefore a
second press exactly at the inclusive 350 ms boundary suppresses the single-tap timer, and a release
exactly at 500 ms suppresses the Hold timer. Ordering is deterministic rather than scheduler-dependent.

### Context mapping

| Context | Single | Double | Hold |
| --- | --- | --- | --- |
| Idle, Normal | status repeat | start Recording | start VoiceTurn when admitted |
| Recording, Normal | status repeat | stop Recording | start VoiceTurn overlay |
| Awaiting explicit physical confirmation | no state change | no state change | confirm after 2,000 ms |
| Updating, Validating, RecoveryRequired | repeat maintenance/fault signal | ignored | ignored unless a separately disclosed recovery step owns it |
| FatalPrivacy | repeat fault signal | ignored | ignored |
| Off | first qualified press wakes hardware | not interpreted during boot | not interpreted during boot |

There is no Normal-mode power-off or reset hold. Normal shutdown is an authenticated shell workflow. A
physical confirmation may use a 2,000 ms hold only after a visible, 15-second `AwaitingConfirmation`
lease disables capture gestures. An emergency reset that works without the edge remains a hardware and
qualification requirement; it must not be hidden behind the VoiceTurn hold threshold.

A qualified button press is the normal Off-state wake source. Wake cannot depend on a bond, pairing, an
active BLE link, edge reachability, cloud reachability, or charger connection. Charger insertion may be
an additional recovery source, never a normal-use prerequisite. The first post-wake state is Booting
with microphone truth verified off, and no previous Recording or VoiceTurn resumes automatically.

Physical stop is local and does not depend on edge or cloud reachability. Concurrent physical and remote
commands are serialized by the device supervisor; a stop or safety transition takes precedence over a
start, and retries return the prior correlated result.

## Physical signal vocabulary

The logical RGB values below are named channel targets, not calibrated optical measurements. PWM level,
color accuracy, visibility, both physical package behavior, and haptic distinguishability remain HIL
gates. Pattern timing and meaning are normative once implemented.

### Haptic patterns

The CV1 has a simple switched ERM motor. Patterns therefore use generous on/off intervals rather than
precision waveform assumptions.

| Name | Pattern |
| --- | --- |
| `READY` | on 80 ms |
| `VOICE_READY` | on 80 ms |
| `RECORDING_STARTED` | on 80, off 70, on 80 ms |
| `RECORDING_STOPPED` | on 220 ms |
| `REFUSED` | three on 80 ms pulses with 70 ms gaps |
| `WARNING` | two on 200 ms pulses with 100 ms gap |
| `FAULT` | three on 200 ms pulses with 100 ms gaps |
| `VOICE_RESULT_OK` | on 80, off 100, on 220 ms |
| `VOICE_RESULT_FAILED` | two on 220 ms pulses with 120 ms gap |
| `SHUTDOWN_COMMITTED` | on 300 ms |

Haptic is never the only indication. Named patterns are authenticated, bounded, and rate-limited; custom
firmware does not expose arbitrary motor duration as an application control.

### Indicator patterns

| Name | Pattern and meaning |
| --- | --- |
| `PRIVACY_RECORDING` | red continuously at or above the qualified privacy floor; microphone active |
| `PRIVACY_VOICE_TURN` | same continuous red base plus blue modulation at 2 Hz; microphone active for VoiceTurn |
| `PRIVACY_UNKNOWN` | continuous red; treat microphone as active or unknown |
| `BOOTING` | two blue 200 ms pulses with a 200 ms gap; microphone off |
| `PAIRING` | blue 250 ms on/off, maximum 60 seconds; microphone off |
| `UPDATING` | blue two-second breathing cycle; microphone off |
| `VALIDATING` | two blue 150 ms pulses followed by 1,500 ms off; microphone off |
| `RECOVERY_REQUIRED` | two amber 300 ms pulses followed by 2,400 ms off; microphone off |
| `RECOVERABLE_FAULT` | three amber 150 ms pulses followed by 2,000 ms off; microphone off |
| `CHARGING` | one green 200 ms pulse every four seconds; idle-only overlay |
| `LOW_POWER` | one amber 200 ms pulse every ten seconds; idle-only overlay |
| `READY_LINK_STATUS` | one blue 300 ms pulse in response to an Idle single tap |
| `DISCONNECTED_STATUS` | one amber 300 ms pulse in response to an Idle single tap |

The privacy floor is a calibrated firmware constant. General brightness preferences may raise it but may
not lower it. No pattern exceeds 2 Hz, and reduced-motion shell rendering uses discrete state changes
instead of animated equivalents.

### Arbitration

One firmware-owned, non-blocking feedback arbiter is the only RGB and haptic writer. Priority is:

```text
privacy unknown/acquired
  > fatal safety
  > recovery/update/pairing
  > recoverable warning
  > power/charging
  > requested status/link feedback
```

No lower-priority color is blended into a privacy output. `PRIVACY_VOICE_TURN` is the sole allowed
modulation and never removes the red base. A fault during capture is conveyed through haptic and the shell
until the microphone is released; it cannot blink the privacy light dark.

The current v1 proposal intentionally leaves one same-tier choice unresolved: simultaneous `LOW_POWER`
and `CHARGING` eligibility has no declared winner. A consumer must report that ambiguity rather than
depending on iteration order; the qualified power policy must resolve it in a reviewed protocol revision.
If the privacy-output driver itself has failed after the microphone is proven off, no trustworthy visual
fatal signal exists on the CV1. The arbiter locks out lower visual patterns; the named fault haptic and
shell warning remain, but neither is misrepresented as proof that the privacy light works.

## State/output matrix

| Semantic state | Device truth and output | Shell wording and behavior |
| --- | --- | --- |
| Off | microphone off; all outputs off | `Device off` |
| Booting | microphone off; `BOOTING`; `READY` only after required self-tests | `Starting device` |
| Ready Idle | microphone verified off; no capture light; idle overlays only | `Microphone off — device confirmed` with age |
| Acquiring | privacy guard continuously red; no success haptic yet | `Starting capture…` |
| Recording | local durable route active; `PRIVACY_RECORDING`; `RECORDING_STARTED` on entry | `Recording locally`; upload/backlog is separate |
| Hold candidate | no change to microphone, recording, or feedback | do not render `Listening` |
| VoiceTurn from Idle | locally durable turn; `PRIVACY_VOICE_TURN`; `VOICE_READY` after acquisition | `Listening for this voice turn` |
| VoiceTurn over Recording | BaseRecording retained; VoiceTurn output; release returns to Recording | `Recording + voice turn` |
| Releasing | privacy output remains until verified off | `Stopping — treat as recording` |
| Link lost while Recording | Recording and privacy output continue; no repeated disruptive haptic | `Device disconnected; recording may continue locally` |
| Voice admission refused | microphone and BaseRecording unchanged; `REFUSED`; fault/status pulse only if Idle | exact refusal reason |
| MicTruth Unknown | `PRIVACY_UNKNOWN` if output remains controllable; `FAULT` | `Microphone state unknown — treat as recording` |
| Local durability lost | stop at a durable boundary; privacy remains until release, then fault pattern | `Recording stopped: local durability unavailable` |
| Pairing confirmation | microphone off; `PAIRING`; 2,000 ms contextual hold | explicit physical-confirmation screen |
| Updating | microphone off; update pattern; capture gestures disabled | separate prepare/upload/verify/reboot/validate stages |
| RecoveryRequired | microphone off; recovery pattern and warning haptic | `Recovery required`; show only qualified recovery path |
| ShuttingDown | microphone released and ring flushed before haptic and lights-off | `Shutting down safely` |

Critical-power thresholds remain a versioned power-policy input because the CV1 percentage is an
estimate, not a coulomb counter. The v3.0.20 source's 3,500 mV shutdown is evidence about stock source,
not a qualified Gumi warning budget. Gumi must warn and reach a durable stop before its qualified
shutdown threshold.

## Shell semantics and accessibility

Connection, capture, device storage, edge backlog, cloud processing, power, and update are displayed as
separate fields. `Connected`, `cloud online`, or `command accepted` never means Recording.

Canonical capture labels are:

- `Microphone off — confirmed by <device> <age> ago`;
- `Starting capture…`;
- `Recording locally`;
- `Recording — uploading`;
- `Recording — cloud offline, saved locally`;
- `Listening for this voice turn`;
- `Stopping — microphone may still be active`; and
- `Microphone state unknown — check the device privacy light`.

A disconnect immediately makes a previously Idle device unknown because a local button gesture can
change its state. A last-known Recording remains “may still be recording.” The shell must never carry a
stale Idle value across disconnect as a positive privacy claim.

For multiple devices, the shell aggregates conservatively: any active mic wins; otherwise any unknown or
stale mic wins; “all microphones off” is shown only when every managed device has fresh verified-off
evidence. Cloud processing cannot change this aggregate.

Accessibility requirements:

- every color has a text label, icon, and temporal or haptic distinction;
- TalkBack announces semantic transitions, not packet-level progress;
- controls meet a 48 dp minimum and support dynamic type and high contrast;
- reduced-motion mode replaces breathing animation without changing meaning;
- safety and privacy outputs remain available when optional haptics are disabled; and
- a blind pendant gesture never confirms a destructive or safety-relevant AI action. The shell presents
  the exact action and stronger confirmation.

## Lifecycle rules

- Device boot, watchdog recovery, and OTA boot enter microphone-off Booting and never resume capture.
- A qualified local button press wakes from Off without pairing, link, edge, cloud, or charger; wake
  enters microphone-off Booting and never resumes capture.
- Phone process death does not change device capture truth; a recreated shell rehydrates the runtime
  projection instead of guessing.
- Device disconnect does not stop Recording while durable device capacity remains.
- Cloud loss changes upload/realtime status, never local capture truth.
- A storage Full/Corrupt transition refuses new capture and safely stops active capture when no qualified
  durable route remains.
- Update cannot start until capture is released, media state is durable, power policy passes, and the
  exclusive update lease is held.
- Expected update reboot is rendered as Rebooting/Validating, not as an ordinary lost-device error.
- A failed post-boot validation enters RecoveryRequired without capture or automatic retry loops.
- Shutdown releases microphone and flushes media before physical confirmation of completion.

## Conformance

The device-owned [`fixtures.json`](../../devices/omi-cv1/protocols/human-io/v1/fixtures.json) is the
initial deterministic oracle. Firmware, the CV1 simulator, driver mapping, runtime projection, and shell
tests consume the same cases or an equivalent generated corpus.

The local simulator now assigns every case to an executable reference owner: one debounce case, the
recognizer-owned event slice of all 12 gesture-bearing cases, and all seven capture-fault,
lifecycle-projection, and output-arbitration cases. It also checks all 13 logical indicator definitions
and all 10 switched-ERM definitions exactly against the machine-readable contract. One composition-only
reference path now joins the existing recognizer, lifecycle owner, and arbiter for all 12 gesture-bearing
cases: accepted debounced edges produce the exact ordered gesture/command trace, explicit effect
completions advance capture truth, and the same run checks indicator intervals, haptics, terminal state,
audio-admission boundaries, and privacy continuity. The raw-bounce case remains a separate debouncer
proof because its fixture intentionally specifies accepted edges rather than a later gesture trace.

At minimum, conformance proves:

- all debounce, double-window, hold, and exact-deadline boundaries;
- single/double tap emit no VoiceTurn frame;
- no VoiceTurn frame precedes commitment;
- release always ends VoiceTurn locally;
- VoiceTurn-over-Recording returns to the same Recording;
- every interval where microphone may be active has a continuous privacy guard;
- output failure rejects or stops capture;
- maintenance states exclude capture;
- disconnect and stale evidence never render verified Idle;
- restart never resumes microphone acquisition;
- Off-state button wake works without a charger or connectivity dependency and preserves microphone-off
  truth; and
- every state/output row has a golden semantic and physical trace.

Physical qualification additionally measures debounce while worn, false gestures, LED package mapping,
minimum privacy visibility in daylight and darkness, haptic distinguishability, power impact, and output
timing against actual PDM acquisition/release. It must also drive a controlled transition to Off and
prove qualified-button wake without a charger plus post-wake microphone-off/no-resume truth. Until those
pass, this remains a proposed contract.
