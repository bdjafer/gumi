# Gumi M1 system contract

Status: draft 0.1. Governing design boundary for the first complete milestone.

## Mission

On an owned Omi CV1, a person can explicitly start and stop a durable recording, or hold the button for
a realtime voice turn. A local Gumi runtime transfers the audio safely through intermittent connectivity.
Astrale records its meaning and authority, transcription produces a traceable conversation, and realtime
AI actions return an observable result to the edge shell.

## M1 is complete only when

1. The custom firmware is running on the sealed consumer unit.
2. Idle is measurably microphone-off; the UI and physical indicator agree with actual capture state.
3. Double-tap recording and hold-to-talk work without a phone foreground requirement where the mobile OS
   permits background BLE operation.
4. Live and stored audio survive disconnect, process death, phone reboot, and cloud interruption without
   silent loss or duplication.
5. A recording reaches object storage, becomes an authorized Astrale Recording, produces a traceable
   Transcript, and participates in a Conversation.
6. A VoiceTurn reaches the realtime AI path, records action provenance, and returns a result.
7. Unauthorized clients cannot operate the device, read media, mint upload sessions, or access the
   resulting Astrale subtree.
8. Firmware update, failure detection, recovery, deletion, retention, and audit paths are exercised.

## System boundaries

| Plane | Owns | Must not own |
| --- | --- | --- |
| Device | sampling, button interpretation, local capture truth, codec, bounded buffering, physical feedback, watchdog | cloud policy, long-term semantic records, remote motor safety decisions |
| Edge runtime | device drivers, capability negotiation, connection lifecycle, encrypted spool/WAL, transfer acknowledgement, local commands/policies, cloud sessions | UI rendering, vendor-specific business semantics, durable cloud truth |
| Edge shell | user interaction, composition, platform permissions, and diagnostics presentation | protocol parsing, sync correctness, recording lifecycle invariants |
| Media data plane | authenticated byte ingest, sequence/hash verification, object assembly, object storage, realtime fan-out | user-facing semantic ownership or broad Astrale authority |
| Astrale | identity, authorization, device/capture/conversation/action semantics, orchestration, audit references | raw audio chunks, secrets, high-frequency packet state, hard realtime control |
| Processing workers | transcription, diarization, derived media, AI provider calls | becoming the source of truth for recording ownership or access |

## Capability model

A physical endpoint exposes a negotiated descriptor containing concrete versioned capabilities. A device
is not required to implement a lowest-common-denominator `Device` interface.

M1 capability vocabulary:

- `AudioInput.v1`: formats, channels, rates, frame duration, live/offline support;
- `CaptureControl.v1`: supported capture modes and state notifications;
- `ButtonGesture.v1`: gesture identifiers and timing constraints;
- `VisualIndicator.v1` and `Haptic.v1`;
- `LocalMediaStore.v1`: capacity, unread range, dropped count, resume/advance semantics;
- `PowerStatus.v1`: battery and charging state;
- `FirmwareUpdate.v1`: images, slots, versions, signer, recovery abilities; and
- `Motion6D.v1`, initially advertised only after bench qualification.

Descriptors include protocol version, optional capability versions, limits, and feature flags. Unknown
capabilities are ignored; unknown required versions fail explicitly. Dynamic plugin loading is not
required for M1: modularity is provided by stable ports, capability descriptors, and independently
testable adapters.

## Orthogonal device state

Recording, connectivity, update, and power are separate state machines. They must not be collapsed into
one enum because the device can sync while recording.

### Capture state

```text
BaseRecording: Idle --double tap--> Recording --double tap--> Idle
VoiceTurn:      Inactive --hold--> Active --release--> Inactive

Idle + VoiceTurn Active ---------> Idle on release
Recording + VoiceTurn Active ----> the same Recording on release
any acquired state --fatal capture error--> microphone release + Fault report
```

- `Idle`: PDM capture and encoding are stopped. Acoustic AAD is separately reported if enabled.
- `Recording`: audio is framed for live delivery and committed to the local device ring as configured.
- `VoiceTurn`: low-latency audio is prioritized for the realtime route, remains recoverable locally, and
  overlays rather than replaces a Recording that was already active.
- Entering/leaving a state is acknowledged only after hardware acquisition/release succeeds.

Stock behavior is version-dependent: official v3.0.12 source assigns a single tap to shutdown and uses a
one-second long press as a notification, while candidate v3.0.20 source assigns a three-second hold to
shutdown. Neither mapping is the Gumi contract. M1 reserves Normal-mode hold for VoiceTurn and moves
normal power-off to an authenticated control workflow. A separate emergency gesture/reset policy is an
unresolved hardware/product decision and must not share an ambiguous threshold with speech. Exact
proposed timing, physical outputs, arbitration, and accessibility semantics are governed by the
[device human-I/O contract](device-human-io-contract.md).

Normal wake from Off is local and charger-independent: a qualified button press cannot require pairing,
an active link, edge/cloud reachability, or charger connection. It enters microphone-off Booting and
never resumes capture. This is not stock behavior we can assume: the owned v3.0.12 unit was observed off,
did not wake from owner button attempts, and recovered on charger insertion even though source intends
the active-low button GPIO as a system-off wake source. The transition trigger, exact press, electrical
path, and root cause remain unqualified.

### Link state

`Disconnected -> Connecting -> Ready -> Degraded -> Disconnected`

Sync is an activity on a Ready/Degraded link, not a capture mode. Reconnection re-negotiates capabilities
and resumes from durable sequence checkpoints.

### Update state

`Normal -> Preparing -> Uploading -> Verifying -> Rebooting -> Validating -> Normal|RecoveryRequired`

No capture command is accepted during a firmware commit/reboot window. Media already committed to the
device or edge spool remains recoverable.

## Local runtime shape

The edge runtime is a portable core plus platform adapters.

Portable responsibilities:

- capability and device registry;
- firmware and protocol version negotiation;
- capture/link/update state machines;
- frame and ring-record parsing;
- sequence, acknowledgement, retry, and deduplication logic;
- encrypted spool metadata and object assembly;
- cloud-session and command state;
- deterministic policy evaluation; and
- structured diagnostics.

Platform adapters:

- Android GATT, Companion Device Manager, and foreground service;
- Linux BlueZ for the Raspberry Pi host;
- Android Keystore and Linux secret storage;
- local database and file encryption;
- network reachability/background scheduling; and
- native Android shell bindings or a headless Linux RPC.

Android is the only mobile delivery lane in M1. Linux remains a portability/conformance target for the
same edge SDK and runtime; an iOS shell is a possible later host, not an M1 commitment.

M1 uses the Kotlin-first direction recorded in
[Decision 0001](../decisions/0001-android-edge-stack.md): common SDK/runtime/driver logic targets
Android and Linux/JVM, the Android shell is native, and maintained Nordic BLE/MCU Manager libraries sit
behind edge ports. Tool versions are bootstrap-pinned; [Decision 0002](../decisions/0002-durable-spool-storage.md)
records the locally executable Android storage candidate, and
[Decision 0004](../decisions/0004-android-companion-association.md) governs the still-unimplemented
Companion API 29–37 boundary. Rust or another native core is reconsidered only from measured
host/performance constraints, not speculation.

The governing module and port ownership is the
[edge runtime and plugin contract](edge-runtime-contract.md).

### Current implementation truth

The portable runtime, spool/upload coordinators, host-neutral shell application, Omi simulator/driver,
and Linux witness execute locally. Android has an unexported, non-sticky `connectedDevice` service and
application-process `RuntimeHost` scaffold, plus a separate Keystore/encrypted-SQLite/immutable-file
spool candidate. The edge also has a one-attempt OkHttp chunk adapter, and media ingest has a real local
Node HTTP boundary.

A separate portable operational slice now executes offline. `OperationalDeviceRuntime` acquires one
provisioned binding, reconciled storage lease, transport lease, ephemeral endpoint, negotiated device
session, and power observation in that order, then releases them in reverse. The Android operational
storage adapter exposes the encrypted store through that port, and `OperationalShellBridge` publishes
generation-fenced, per-axis-fresh link/power/storage truth while leaving capture unverified and
rejecting capture commands. It publishes backlog counts only when the runtime marks them as durably
device-attributed.

That adapter deliberately represents one process-global M1 spool whose recovered backlog belongs to
the edge host, not to an individual `DeviceId`. The first operational witness is constrained to one
explicitly started device. A local scalable graph now composes one foreground lease, one spool owner,
and a `DeviceId`-keyed runtime registry/command router; its concurrency, identity fencing, reverse
cleanup, and process teardown execute in unit/host tests. Per-device runtimes cannot close the global
spool, and `EDGE_HOST` backlog is zeroed and marked unavailable at the per-device shell boundary. The
production application still has no provisioned runtime factory and does not instantiate this graph.

On an API 36 ARM64 emulator, the original five storage primitive cases and the shell suite's 3/3
Intent/framework cases pass. Two newer storage cancellation/operational-lease cases compile but have
not run on Android. Those pieces are still not one Android product graph.
The Activity has not composed the host-neutral shell application, real operational Omi lease, encrypted
recovery, and cloud adapter. Companion association, durable binding/user-stop restoration,
Motorola/OEM qualification, process-death/reboot, deployed stores, real credentials, live Astrale
installation, and physical end-to-end behavior remain open. This section records implementation status
only; it does not weaken the completion criteria above.

The stock driver currently returns no provisioned Gumi `deviceId` and exposes no `CaptureControl` or
capture-state observation. No operational owner yet connects live notifications to mux state, a durable
source checkpoint, or spool advancement. The Android product graph also lacks composed recovery/event
ingress, production binding/endpoint resolution, provisioning/cloud-auth wiring, a shell freshness
scheduler and `INTERNET`; user-stop is not durable. Until those boundaries exist,
the service can qualify explicit foreground ownership with capture unverified and zero audio
subscription, but cannot qualify Recording, upload, or automatic presence recovery.

## Media transfer contract

Existing Omi audio/ring framing is reused for the first firmware vertical. Gumi adds an envelope at the
edge-to-cloud boundary with at least:

- capture session ID;
- stream ID;
- monotonically increasing sequence range;
- source/device time plus edge receive time;
- codec/configuration identifier;
- payload length and digest;
- discontinuity, retransmission, and final flags; and
- protocol version.

Rules:

1. The edge spool persists a chunk before acknowledging device advancement when the device is the last
   durable copy.
2. Cloud acknowledgement identifies the exact durable sequence range, never merely “request accepted.”
3. Retries use stable session/stream/sequence identities and are idempotent.
4. The ingest service validates size, order, digest, authorization, and capture-session state.
5. Finalization writes an immutable media manifest before announcing a Recording to Astrale.
6. Raw chunks and high-frequency transfer counters remain outside the Astrale graph.

The local HTTP candidates already exercise the exact chunk boundary: media ingest separates control and
data credentials, streams bounded JSON/binary bodies, validates canonical routes/content/digests, and
emits typed problem responses; the edge caps a chunk at 1 MiB before authorization/network, uses a
dedicated no-hook/no-retry OkHttp client, and accepts only strict string-shaped exact ACKs. Production
credential verification/revocation, databases/object storage, rate limiting, TLS ingress, retention,
deployment, and Android composition remain required before these are data-plane evidence.

Serialization over BLE remains compatible with the current firmware during the first vertical. Any new
control encoding requires code generation or shared golden fixtures; a handwritten protocol on both
sides is not acceptable. The initial offline-storage oracle is the checked-in
[`omi.ring/v1` fixture set](../../devices/omi-cv1/protocols/ring/v1/README.md).

## Cloud/Astrale flow

```text
CaptureSession.begin
  -> short-lived scoped ingest session
  -> edge uploads sequenced chunks to media ingest
  -> ingest verifies and assembles immutable object + manifest
  -> Recording.finalize records semantic result in Astrale
  -> Recording.transcribe invokes provider through an integration port
  -> Transcript and segments converge in graph state
  -> Conversation relates recordings, transcripts, and voice turns
```

Astrale functions authorize session creation and semantic transitions. The binary ingest endpoint is a
separate remote service with a narrow credential; it does not accept general graph authority. Every
public/provider callback authenticates its upstream before acting as its function identity. Provider
credentials stay in deployment secrets/dependencies, never graph properties.

Current Astrale `step.run` is an inline replay-shaped boundary, not durable exactly-once execution. M1
therefore uses provider idempotency keys, explicit state transitions, a durable job/queue where needed,
and graph convergence; it does not assume workflow durability that does not exist.

## Initial Astrale vocabulary

The first Astrale-backed cloud app may own one Gumi domain with bounded modules. It lives as an app
under `cloud/apps`, not beneath a separate technology-level `astrale/` tree. Split it into more apps or
domains only when vocabulary, authority, or release ownership genuinely diverge.

| Context | Classes | Core relationships/behavior |
| --- | --- | --- |
| Fleet | `Device`, `DeviceProvisioning`, `FirmwareRelease` | Device owns revoke/status/update intent; provisioning links a device identity to its owner/control planes |
| Capture | `CaptureSession`, `Recording` | Session begins/stops; finalized Recording relates to session and capturing device; object handle is opaque external data, never a signed URL |
| Conversation | `Conversation`, `Transcript`, `TranscriptSegment`, `Speaker` | Transcript belongs to a Recording; segments relate to speakers; Conversation groups meaningful material |
| Assistant | `VoiceTurn`, `ActionInvocation` | VoiceTurn owns its lifecycle; an invocation records requested action, authorization, outcome, and provenance |

Candidate typed edges include `captured_by`, `produced_recording`, `transcribes`, `contains_recording`,
`contains_turn`, `spoken_by`, and `triggered_action`. Astrale node relationships are edges, not hidden
string IDs. External object/provider identifiers remain opaque properties where no graph node exists.

Before schema implementation, each callable will have a method-ownership table stating receiver, changed
invariant, caller gate, function grants, and external effects. Every callable declares `authorize`; a
successful write using composed function authority is not evidence the caller held permission.

## Security and privacy invariants

- Device ownership, proximity, button input, and a lit indicator are not substitutes for participant
  consent. Gumi starts no capture until an explicit local action and an applicable consent policy permit
  it; deployments remain responsible for the rules in their jurisdiction and context.
- Physical indication is firmware-owned, guards every interval in which the microphone is acquiring,
  acquired, releasing, or not proven off, and never reflects requested state alone.
- Idle and Recording are distinguishable without opening the mobile app.
- Device, edge host, user, ingest session, and Astrale function are separate identities.
- Pairing is explicit; lost devices and hosts can be revoked.
- BLE discovery/connection is not authorization. Device commands and media require a cryptographically
  authenticated application session; the stock plain-permission GATT surface is a temporary canary-only
  compatibility exception.
- Media is encrypted in transit, in the edge spool, and at rest.
- Upload credentials are short-lived and scoped to one capture/session/sequence policy.
- No provider receives more media or authority than the requested processing operation needs.
- Media retention and derived transcript retention are explicit and independently auditable.
- Delete semantics cover media object, manifests, graph semantics, derived artifacts, and provider copies
  where supported.
- Logs contain identifiers and state transitions, not raw audio or credentials.
- Cloud unavailability cannot silently change local capture truth or physical safety behavior.
- Media already present on a newly adopted device is quarantined. Gumi does not read, transcribe,
  upload, advance, or clear it until the owner explicitly chooses an import or deletion workflow that
  states the consequence and preserves recovery evidence.

## Failure and recovery matrix

| Failure | Required behavior |
| --- | --- |
| BLE disconnect during live capture | Continue bounded device recording; edge reconnects and resumes from durable sequence |
| Mobile process suspended/killed | Native adapter retains only platform-permitted work; device ring remains durable; next process resumes |
| Edge disk full | Stop acknowledging new durable progress, surface physical/UI fault, preserve already committed ranges |
| Cloud offline | Continue encrypted local spooling within policy; expose backlog and capacity |
| Duplicate/reordered upload | Ingest deduplicates by stable sequence identity and rejects inconsistent payload hashes |
| Device ring overwrite | Increment dropped counter, create explicit discontinuity, never fabricate continuity |
| Clock invalid/jump | Preserve device and receive clocks, mark uncertainty, reconcile without rewriting original evidence |
| Transcription provider timeout | Recording remains complete; transcription is retryable with stable provider idempotency/provenance |
| Astrale write fails after object commit | Reconcile from immutable manifest using stable Recording identity |
| OTA interruption | Existing bootable image remains; update state is recoverable and media state is not migrated early |
| New firmware boots but fails validation | Enter `RecoveryRequired`; use the proven equal-version stock application image while leaving network image `1` untouched; never loop updates or raise the boot version to escape |
| Revoked/lost device | Reject new ingest/control sessions while preserving authorized historical records |

## M1 delivery sequence

The detailed evidence and exit criteria for these gates are maintained in the
[Gumi roadmap](../roadmap.md).

1. **Evidence gate**: sealed-device inventory, source/release matrix, power/storage/transport measurements.
2. **Firmware access gate**: reproducible application build, Android image-`0` updater, official OTA
   rehearsal if needed, custom canary, unchanged network image, and known-good recovery path.
3. **Protocol gate**: versioned capabilities, capture gestures/state, sequence/resume fixtures shared by
   firmware and edge tests.
4. **Edge gate**: mobile BLE adapters, encrypted spool, live/offline transfer, restart/reconnect tests.
5. **Data-plane gate**: scoped ingest, immutable object manifest, idempotent finalization, observability.
6. **Astrale gate**: authorized domain semantics, transcription convergence, conversation views.
7. **Voice gate**: hold-to-talk realtime path, action authorization/provenance, result return.
8. **Qualification gate**: privacy, security, power, loss/recovery, deletion, OTA, and complete physical
   device-to-Astrale acceptance suite.

## Decision queue

| Decision | State / next evidence |
| --- | --- |
| Exact hardware/firmware identity | Model, hardware 5.0, firmware 3.0.12, GATT profile, and exact published application image observed; stock MCU Manager exposed no network/secondary-slot truth, so OTA identity remains partial |
| Android/Kotlin/Gradle pins | Resolved for the current bootstrap and recorded in Decision 0001; re-open only from measured incompatibility |
| Existing stock media disposition | Quarantined; explicit import/delete consent flow required before any ring read, advance, or clear |
| Metadata database and spool encryption | Direct encrypted-snapshot SQLite plus Keystore/HMAC and immutable payload candidate passes API 36 emulator instrumentation; repeat on the Motorola and run forced-death/reboot/migration/key-loss tests, then design durable rotation before real audio |
| Android Companion association/presence | Decision 0004 fixes explicit chooser/filter and API 29–37 branches; first run the next owned-phone process-local rotation preflight, then either select the explicit foreground fallback on `CHANGED` or implement the chooser and separately prove association resolution across process recreation before composing durable binding, user-stop/exit policy, or automatic presence |
| Capture consent/retention and Idle AAD defaults | Product/privacy policy plus physical battery and microphone-off measurements before capture firmware |
| Emergency power/reset and normal wake | Controlled Off transition, charger-independent button wake, post-wake microphone-off/no-resume truth, and emergency recovery evidence before hold-to-talk owns long press |
| Object store, region, realtime transport, and AI providers | API contracts and representative quality/latency/privacy/cost evaluation before implementation |
