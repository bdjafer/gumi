# Gumi roadmap

Status: working execution roadmap, 2026-07-18. M1 is the first complete product milestone, not a reduced
demo milestone.

## M1 outcome

M1 delivers the entire owned Omi CV1 → Android edge → cloud applications → Astrale → Android loop:

- explicit, physically truthful recording and hold-to-talk firmware behavior;
- reliable live and offline audio transfer through Android background/process failure;
- encrypted local durability and resumable cloud ingest;
- authorized recordings, transcripts, conversations, voice turns, and actions in Astrale-backed apps;
- realtime AI interaction with an observable result returned to the edge shell; and
- qualified OTA recovery, privacy, deletion, security, power, and fault behavior.

The detailed behavioral contract remains [the M1 system contract](specs/m1-system-contract.md). This
roadmap governs order, proof, and workstream convergence.

## Current position

| Area | Current evidence | State | Next proof |
| --- | --- | --- | --- |
| Repository boundary | `devices / edge / cloud` ownership and dependency law specified | Ready | Enforce it in the first executable workspace |
| Omi hardware | Source, BOM, board, firmware, GATT, storage, and release audit | Source-proven | Read the exact owned unit over Android |
| Firmware build | Stock v3.0.20 application payload reproduced | Build-proven | App-image-only stock recovery and visible canary on the unit |
| Network-core safety | Clean-build NSIB key mismatch identified | Known constraint | Prove image `1` remains byte/hash unchanged during every canary |
| Android lifecycle | Current Omi implementation and current Android guidance audited | Direction selected | Build the Gumi diagnostic shell and exercise process/background cases |
| Edge runtime | Boundaries specified, no executable package yet | Ready to bootstrap | Same deterministic core tests on Android and Linux/JVM |
| Cloud data plane | M1 invariants specified, provider/runtime unselected | Design pending | Contract plus local ingest/object-manifest vertical |
| Astrale semantics | Initial vocabulary and authority rules specified | Design pending | Method-ownership and authorization table, then simulation/live proof |
| Physical acceptance | No writes or device changes performed | Blocked on owned unit session | Complete the read-only Android handoff |

## Execution rules

1. A gate closes only with the named artifact or real-system observation. Source plausibility is not
   physical proof; local green is not deployed proof.
2. Build vertical witnesses early, but never redefine M1 downward around them. Each witness retires a
   risk on the way to the complete milestone.
3. Work may proceed behind simulators while a physical or provider gate is pending. Simulator success
   never substitutes for the corresponding hardware or live acceptance run.
4. Contracts live with their publisher. Every consumer proves compatibility without copying the
   contract into a global directory.
5. Prefer maintained libraries at infrastructure boundaries; Gumi owns orchestration, product
   semantics, security policy, and the device/cloud invariants unique to the system.
6. Every destructive device or cloud operation needs an explicit recovery/rollback proof before it is
   exercised on durable user data.

## Workstreams

| Workstream | Owns | First M1 deliverable |
| --- | --- | --- |
| Device | Omi firmware, protocols, device driver, OTA compatibility, HIL | Recoverable custom capture state machine |
| Edge | SDK, runtime, Android platform, shell, durable spool, cloud adapters | Process-death-safe local recording and ring drain |
| Cloud data | Media ingest, object assembly, manifests, processing jobs, realtime gateway | Idempotent resumable recording ingest |
| Astrale apps | Identity, authorization, fleet/capture/conversation/action semantics | Authorized Recording finalization and projections |
| AI integrations | Transcription, diarization, realtime model, tool/action invocation | Traceable transcript and VoiceTurn result |
| Qualification | Threat models, observability, privacy, recovery, performance, power | Reproducible physical-to-Astrale acceptance suite |

These are parallel workstreams, not repository layers. They converge at the gates below.

## Dependency spine

```text
owned-unit read-only truth
        │
        ├──> app-image-only recovery ──> visible canary ──> custom capture/security firmware
        │
        └──> Android stock-driver qualification ────────────────┐
                                                                 v
simulator + protocol fixtures ──> edge runtime + durable spool ──> local capture witness
                                                                 │
cloud ingest contract ──> resumable object/manifest data plane <──┘
        │
        v
Astrale recording semantics ──> transcription/conversation ──> realtime voice/actions
        │
        v
privacy + recovery + fault + power + security qualification ──> M1 complete
```

The physical probe and firmware-access work are on the critical path. Edge simulation, cloud contract
design, and Astrale modeling can advance in parallel.

## M1 gates

### Gate 0 — owned-unit truth

Purpose: replace source assumptions with facts about the sealed consumer unit.

Required evidence:

- advertised identity and Device Information values;
- redacted GATT inventory compared with the checked source profile;
- negotiated MTU/PHY and connection-security observations;
- storage status and ring counters without advancing or clearing them;
- MCU Manager image `0` and image `1` slot/hash/flag inventory;
- current gesture, indication, reconnect, and idle/live-audio observations; and
- an initial battery/thermal baseline sufficient to detect gross regressions.

Exit artifact: updated owned-device inventory plus redacted/raw evidence separation. The prescribed
procedure is the [Android read-only probe](../devices/omi-cv1/docs/research/android-read-only-probe.md).

### Gate 1 — sealed-device recovery and application control

Purpose: prove that Gumi can change application behavior without opening the pendant or replacing the
network trust root.

Required evidence:

- Android updater lists both image families and selects application image `0` explicitly;
- equal-version stock application recovery rehearsal succeeds;
- network image `1` hashes and flags are identical before and after;
- one reversible, visible custom canary boots and reports the expected build identity;
- rejected/failed/interrupted upload behavior is recorded without retry loops;
- stock application recovery after the custom canary succeeds; and
- no clean-built network image or full multi-image ZIP touches the unit.

Exit artifact: a repeatable HIL canary/recovery runbook and automated pre/post slot assertions. If this
gate fails safely, firmware and updater analysis continue; opening the unit is not the automatic next
step.

### Gate 2 — executable edge spine

Purpose: prove the substrate-independent edge boundary before cloud complexity hides local failures.

Required evidence:

- `edge/sdk`, `edge/runtime`, Android platform adapter, Android shell, and Omi edge driver compile as
  separate modules with enforced imports;
- the same runtime state-machine and spool tests run on Android and Linux/JVM;
- a deterministic Omi simulator consumes the checked GATT/ring fixtures;
- the Android shell associates with the stock unit, exposes diagnostics, and receives live audio;
- offline ring data drains into the local spool and advances only after durable commit;
- reconnect, application process death, and phone reboot resume without silent loss or duplicate
  semantic records; and
- no UI or Android object appears in the runtime or device capability API.

Exit artifact: a local-only recording witness on stock firmware with byte/sequence accounting.

### Gate 3 — trusted explicit capture firmware

Purpose: replace stock always-on behavior and stock unauthenticated application commands with Gumi
capture truth.

Required evidence:

- `Idle`, `Recording`, and `VoiceTurn` are separate measured states;
- double-tap toggles durable recording and hold/release controls VoiceTurn;
- PDM acquisition/encoding is stopped in Idle unless an explicitly selected AAD policy says otherwise;
- LED/haptic indication follows acquired hardware state, not requested state;
- device identity, Android edge identity, challenge-response, session encryption, replay rejection, and
  revocation behavior pass negative tests;
- offline capture remains bounded and reports drops/discontinuities truthfully;
- emergency power/reset behavior no longer conflicts with hold-to-talk; and
- authenticated application-only OTA and stock-compatible recovery remain functional.

Exit artifact: recoverable custom firmware plus an Omi-driver capability descriptor and HIL suite.

### Gate 4 — durable cloud recording

Purpose: establish a secure data plane that remains correct under intermittent connectivity.

Required evidence:

- an Astrale-authorized, short-lived ingest session is scoped to one device/capture/sequence policy;
- chunks have stable identities, sequence ranges, content digests, codec metadata, and idempotent retry;
- cloud acknowledgement names the exact durably committed range;
- finalization produces an immutable object plus media manifest before semantic publication;
- inconsistent duplicate payloads, gaps, oversize data, expired credentials, and foreign-device uploads
  fail explicitly;
- Android offline backlog, process death, network loss, and resumed upload pass fault tests; and
- media bytes and high-frequency transfer state remain outside the Astrale graph.

Exit artifact: an owned-device recording that survives induced failures and becomes one immutable cloud
media object without loss or semantic duplication.

### Gate 5 — Astrale semantics and transcription

Purpose: turn durable media into authorized, traceable meaning.

Required evidence:

- cloud app/domain ownership is explicit for device, capture, recording, transcript, and conversation
  invariants;
- every callable has receiver ownership, caller authorization, function authority, and external effects
  documented and simulated;
- `Recording.finalize` converges idempotently from the immutable manifest;
- transcription jobs use stable identities, retry safely, and record provider/model/version provenance;
- Transcript and segments relate to the correct Recording and Conversation through graph edges;
- provider callbacks authenticate their source and receive no broad graph authority; and
- live views expose recording/transcription progress and authorized history to the Android shell.

Exit artifact: a real recording produces a traceable transcript and conversation visible through the
edge shell.

### Gate 6 — realtime voice and authorized action

Purpose: make hold-to-talk an end-to-end low-latency interaction rather than a second upload mode.

Required evidence:

- press, first captured frame, edge receipt, cloud receipt, model response, action, and shell result have
  correlated latency telemetry;
- realtime transport has bounded buffering, cancellation, reconnect, and a recoverable local record;
- VoiceTurn and ActionInvocation identities are stable across retry;
- every action is authorized for the user/device/context and records requested input, grants, effects,
  outcome, and provenance;
- destructive or safety-relevant actions require stronger confirmation/policy; and
- model/provider failure produces an explicit recoverable result instead of disappearing.

Exit artifact: hold the pendant button, speak, execute an authorized action, and observe its result and
audit trail on Android.

### Gate 7 — M1 qualification

Purpose: prove the first product milestone under realistic failure and adversarial conditions.

Required evidence:

- the complete failure/recovery matrix in the M1 contract passes;
- BLE/media/device/cloud authorization negative tests pass;
- idle privacy and physical indication are independently verified;
- deletion and retention cover local spool, object/chunks, manifests, graph state, derived artifacts,
  logs, and provider copies where supported;
- battery, thermal, storage, BLE throughput, reconnect latency, transcription latency, and realtime
  latency have measured budgets and regression thresholds;
- firmware update/recovery, lost-device revocation, lost-phone recovery, and credential rotation pass;
- observability diagnoses an induced loss, gap, duplicate, provider failure, and stuck job without raw
  audio or credentials in logs; and
- the final acceptance run uses the owned device, Android build, deployed cloud apps, and live Astrale
  instance—not mocks or local-only substitutes.

Exit artifact: signed/versioned release evidence for the complete M1 path.

## Immediate execution tranche

Work that should proceed now, in dependency order:

1. Complete the owned-unit read-only probe and record Gate 0 evidence.
2. Establish the baseline commit before importing upstream history.
3. Import the pinned Omi firmware subtree at `devices/omi-cv1/firmware` and reproduce the application
   build through a repository-owned command.
4. Bootstrap the Kotlin-first edge workspace and enforce the dependency boundaries.
5. Implement the Omi protocol/simulator module from existing golden fixtures before connecting real BLE.
6. Build an Android diagnostic shell that can associate, inspect, connect, and stream read-only data.
7. Wrap Nordic Device Manager behind the updater port and execute Gate 1 with explicit user checkpoints.
8. Specify the provider-owned media-ingest API and immutable manifest concurrently with local edge work.
9. Model Astrale method ownership and authorization before installing any live semantic app.

Steps 3–6 and 8–9 can advance while the physical probe is waiting; Gate 1 cannot.

Environment note, 2026-07-18: the current workspace host has no detected JDK, Gradle, Android SDK,
`adb`, or Android Studio installation. The edge bootstrap therefore begins by installing a pinned JDK
17/Android toolchain and generating a verified Gradle wrapper; an uncompiled scaffold does not count as
the Gate 2 witness.

## Decision schedule

| Decision | Must be made before | Evidence required |
| --- | --- | --- |
| Exact Android/Kotlin/Gradle pins | Edge workspace bootstrap | Android + Linux/JVM compile/test spike |
| Metadata database and spool encryption | First durable local audio | Crash recovery, key rotation, packaging/license spike |
| Idle AAD policy and retention defaults | Custom capture firmware | Battery/privacy/product measurements |
| Emergency physical reset/power gesture | Hold-to-talk firmware | Gesture reliability and recovery analysis |
| Object store and upload transport | Gate 4 implementation | Region, cost, resumability, integrity, operations comparison |
| Transcription/diarization provider | Gate 5 implementation | Accuracy, latency, language, privacy, retention, price evaluation |
| Realtime model/transport | Gate 6 implementation | Streaming latency, cancellation, tool authorization, regional support |

Provider decisions are intentionally delayed until their contracts and evaluation corpus exist. Core
device and edge ownership is not delayed by them.

## Risk register

| Risk | Containment |
| --- | --- |
| Sealed unit rejects custom application | Equal-version app-only rehearsal, one-change canary, stock recovery, stop on first anomaly |
| Network image trust mismatch bricks recovery | Never upload image `1`; pre/post slot assertions; no multi-image ZIP in canary code path |
| Android/OEM kills background work | Companion association, `CompanionDeviceService`, connected-device foreground service, process-death HIL matrix |
| Handwritten BLE lifecycle becomes a permanent subsystem | Nordic library behind a port; retain Omi behaviors as tests/oracles |
| Edge abstraction becomes lowest-common-denominator | Versioned typed capabilities and optional extension keys; no brand base class |
| Spool acknowledges before durability | Single durable-copy invariant and crash/fault injection around every acknowledgement boundary |
| Cloud/Astrale authority leaks into byte plane | Narrow ingest credentials and app-owned authorization; no general graph token at ingest |
| Always-on capture violates user expectation | Hardware-state-derived indication, Idle measurement, explicit capture commands, auditable policy |
| Provider lock-in or silent model drift | Integration ports, evaluation corpus, provider/model/version provenance, replayable jobs |

## Beyond M1

These horizons express the ambition without fixing implementations before M1 evidence exists:

- **M2 — multi-device edge:** multiple concurrent sensors and outputs, smart glasses/ring/watch/speaker
  plugins, capability composition, time alignment, cross-device scenes, and fleet onboarding.
- **M3 — alternate edge substrates:** Raspberry Pi production parity, headless shell, local inference,
  disconnected operation, LAN peripherals, and migration of ownership between edge hosts.
- **M4 — safe actuation:** motors and physical outputs with local interlocks, bounded commands, leases,
  dead-man behavior, emergency stop, simulation, and cloud-independent safety policy.
- **M5 — ambient personal fabric:** multi-user and multi-space policy, sensor fusion, local semantic memory,
  privacy zones, selective synchronization, and independently replaceable cloud/Astrale substrates.
- **M6 — Gumi ecosystem:** signed device drivers and edge apps, compatibility certification, digital
  twins/HIL farms, fleet-scale observability, and third-party hardware without weakening local authority.

Each later milestone must preserve the first-principles chain: physical I/O is owned locally, the edge is
the realtime authority and durable bridge, and cloud/Astrale performs remote semantics and orchestration.
