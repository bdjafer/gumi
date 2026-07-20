# Portable control-plane product contract

Status: executable local contract; physical-output reporting remains unqualified until a device and
host adapter pass hardware-in-loop evidence.

## Purpose

The shell must remain the same application when its host changes from an Android phone to a Raspberry
Pi. It may manage several attached devices while M1 operates one Omi capture. This contract defines the
small product projection and command port needed by either host; it does not define an Android screen,
a BLE protocol, or an Omi firmware packet.

The implementation is under [`edge/shell/application`](../../edge/shell/application/README.md). It
builds on the existing requested-versus-acquired capture reducer and generation-safe shell projection.

## Ports and authority

```text
device-specific runtime owner
  -> ShellApplication.projection: per-axis device/runtime truth
  -> ShellApplication.submit: idempotent command envelope, runtime-revalidated

optional device-output adapter
  -> ShellDeviceOutputTruthPort: current-session reported visible-output truth

PortableControlPlane
  -> immutable fleet/product presentation
  -> explicit selection preference
  -> advisory command admission, then ShellApplication.submit

Android / Linux host
  -> renders presentation and owns collection lifecycle
```

`ShellApplication` remains the command/effect boundary. `PortableControlPlane` may refuse an action that
its current presentation proves unsafe, but an enabled action is not authorization and is not evidence
of acquired hardware state. The concrete runtime and firmware revalidate every command.

`ShellDeviceOutputTruthPort` is optional. An empty map means no evidence. Its portable meanings describe
the currently reported visible output, not device-specific RGB values or animation timing. Only a fresh
`DEVICE_REPORTED` observation bound to the current connection-session generation can be presented as
confirmed. Edge-inferred desired output, a successful write, a cloud claim, a stale report, a report
from the prior connection, or a report received after disconnect remains unverified.

Android is a composition adapter. No Android, BLE, Omi, Compose, firmware, provider, or cloud type is
imported by the portable contract.

## Fleet workflow

The fleet projection has six exhaustive capture states:

| State | Exact meaning | Start policy |
| --- | --- | --- |
| `NO_MANAGED_DEVICES` | no managed capture projection | none |
| `ALL_VERIFIED_OFF` | every managed microphone has fresh device-confirmed Idle evidence | recording or VoiceTurn may be requested on one target |
| `STARTING` | one product admission is reserved but device acquisition evidence has not arrived | exact command replay and safety stop only; no second acquisition |
| `ONE_ACTIVE` | exactly one microphone is confirmed active and no other is uncertain | no second recording; VoiceTurn may overlay that same fresh base recording |
| `UNCERTAIN` | no confirmed active microphone, but at least one may be active | no acquisition until reconciled; local stop remains |
| `COLLISION_RISK` | multiple confirmed active microphones, or active plus uncertain | no acquisition; show every implicated device and retain every local stop |

The initial M1 product policy admits one active capture across the managed fleet. The model detects and
renders violations rather than assuming they cannot occur. A physical gesture, another shell process,
a reconnect, or faulty firmware can all produce a state the local UI did not request.

The product port serializes acquisition admission with a small reservation before dispatch. This closes
the race where starts for two devices both read the same all-off snapshot before either runtime publishes
a pending transition. The reservation is not capture truth: it renders `STARTING`, keeps the target's
safety stop available, permits only an exact replay of the same command, and blocks a different start.
An explicit refusal or rejection clears it. Acceptance, cancellation, generic failure, or
outcome-unknown keeps it until runtime evidence leaves verified-off and later returns to a fresh
verified stop. A generic `FAILED` result is not accepted as proof that hardware never crossed the
acquisition boundary. If the target disappears before reconciliation, the reservation becomes collision
risk rather than granting a new device admission.

Selection is presentation focus, not ownership. An explicit preference wins while that device remains
managed. Without one, the sole active device wins, then the first fresh attached device, then stable
device-ID order. Removing a preference or device restores deterministic focus. Selection never hides
another active, uncertain, or faulted device and never grants command authority.

## Product device projection

Each managed device card contains independent fields:

- stable device identity and display label;
- attachment: attached, attaching, detached, degraded, or unknown;
- requested-versus-acquired capture presentation and freshness;
- physical visible-output evidence;
- fault evidence;
- update, power, storage, sync/backlog, and maintenance axes from the shell projection; and
- an explicit availability decision for every shell action.

The shell never collapses connection, capture, local durability, upload, cloud processing, physical
output, and fault into one “online” or “recording” enum.

### Physical output cross-check

The portable output meanings are `NO_SIGNAL`, privacy recording/VoiceTurn/unknown, lifecycle and
maintenance status, warning/status, and unknown. A concrete device adapter owns the mapping. The Omi
CV1's exact RGB channels, pattern shapes, priority arbiter, haptic patterns, and calibration remain under
`devices/omi-cv1`.

The product projector cross-checks output against capture assurance:

| Capture assurance | Fresh reported output | Product result |
| --- | --- | --- |
| active or may-be-active | privacy recording, VoiceTurn, or unknown | confirmed privacy signal |
| active or may-be-active | no signal or non-privacy status | contradiction; treat as recording, elevate privacy fault |
| verified off | no signal or non-privacy status | confirmed status only; microphone-off proof still comes from capture evidence |
| verified off | privacy signal | contradiction; do not rewrite device-confirmed capture truth |
| any | output drive failed | critical privacy-output fault |
| any | missing, stale, inferred, wrong-session, disconnected, or unknown output | unverified |

Physical-output failure or contradiction blocks recording and VoiceTurn starts at the product port.
`StopRecording` remains available whenever capture may be active. A missing stock-firmware output report
does not by itself block capture—the runtime's qualified firmware/privacy guard remains the acquisition
authority—but the shell says the physical output is unverified.

## Command surface

The portable layer reuses `ShellCommand` and does not introduce a second command language. Every
command carries stable command, correlation, target-device, time, and intent fields. The product maps
each intent to one of the exhaustive `ShellControlAction` decisions before dispatch.

If an action is blocked, the product returns terminal `REJECTED` with stable code
`SHELL_PRODUCT_ACTION_BLOCKED` and redacted `reason`/`action` evidence; it does not call the underlying
port. If it is enabled, the exact envelope is passed unchanged to `ShellApplication.submit`. The
runtime's owner generation, terminal ledger, physical state machine, policy, and outcome-unknown rules
remain authoritative.

Exact replay of the command holding an acquisition reservation bypasses the advisory disabled state and
reaches the runtime ledger unchanged. A stop does not wait behind a suspended start. A different start
receives `CAPTURE_ADMISSION_RESERVED`; outcome-unknown never frees capacity for another microphone.

## Failure and accessibility semantics

Fault presentation distinguishes clear, warning, recoverable, privacy-critical, and unknown. Stale
`NONE` becomes unknown. A stale fatal privacy fault remains critical until a fresh device observation
proves it cleared. Output failure or contradiction elevates the product presentation even if a lagging
fault axis still says clear.

Every workflow, capture, attachment, output, and fault state has text and a portable icon key; safety
states use assertive live announcements. Color is secondary and comes only from the shell palette.
Platform UIs must preserve the label, icon meaning, announcement priority, dynamic type, and accessible
control size. They may not render Omi LED colors as the digital-shell truth.

## M1 qualification boundary

The common tests exhaust the six workflow states, selection priority, pre-publication reservation and
concurrent second-capture exclusion,
VoiceTurn overlay, stop availability, output freshness/authority/session binding, output contradictions,
drive failure, fault freshness, attachment states, malformed identities, exact replay, explicit
refusal/rejection versus ambiguous reservation retention, lifecycle reconciliation, and port dispatch
behavior.
Those are deterministic software claims.

Tomorrow's stock Omi/Android session can validate device discovery, current runtime projection, and the
observable stock indicator/gesture sequence from the return-session runbook. It cannot qualify
`ShellDeviceOutputTruthPort`, because observation by a person is not machine-bound telemetry and stock
firmware does not expose the proposed semantic report. Until custom firmware supplies and HIL validates
that report, the product composition must render physical output as unverified.
