# Gumi roadmap

Status: working execution roadmap, 2026-07-20. M1 is the first complete product milestone, not a reduced
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
roadmap governs order, proof, and workstream convergence. The
[M1 evidence matrix](qualification/m1-evidence-matrix.md) assigns stable requirement IDs and keeps
source/local, hardware, deployed, and end-to-end proof distinct.

## Current position

| Area | Current evidence | State | Next proof |
| --- | --- | --- | --- |
| Repository boundary | `devices / edge / cloud` ownership and dependency law specified | Enforced for landed Kotlin modules | Extend checks as platform/cloud modules land |
| Omi hardware | Owned unit identified as stock v3.0.12/hardware 5.0; advertisement, 11-service/21-characteristic GATT profile, portable-driver negotiation, exact application hash, and bounded stock audio metadata captured. MCU Manager exposed no network row. The observed Off state did not wake from button attempts and recovered on charger insertion | Application/audio read-only proven; network/secondary and power behavior partial | Resolve stock behavior/storage/reconnect and controlled power/wake baselines; keep OTA blocked until network/secondary state and app-only mutation isolation are proved |
| Omi human I/O | Proposed CV1 mapping plus pure reference oracle; the exact 20-case corpus now drives the recognizer, lifecycle/fault/arbitration coordinator, portable control plane, and device-neutral physical-output truth in local simulator and shell suites; all 13 logical indicator and 10 haptic definitions are contract-checked | Local cross-layer reference path executable; custom-firmware and physical proof open | Bind the contract to custom firmware and the real driver/runtime shell, then collect reviewed HIL timing/LED/haptic evidence |
| Firmware build | Official v3.0.12 bundle verified; exact-source application reproduced; owned application hash matched; identity-only canary qualified offline. Isolated image-0 updater/recovery logic passes 27 tests and rechecks full slot truth immediately before upload. Network/secondary state remain unobserved | Application artifact/build/physical identity and updater logic proven offline; physical mutation blocked | Preserve the behavior-neutral canary; resolve full slot truth and independently review authorization/post-reboot binding before any explicit owner go/no-go |
| Network-core safety | Clean-build NSIB key mismatch identified | Known constraint | Prove image `1` remains byte/hash unchanged during every canary |
| Android lifecycle | Compose diagnostics plus a local application-owned `RuntimeHost`, unexported/non-sticky `connectedDevice` service, redacted notification, explicit Activity controls, one foreground-execution owner, one process-global spool owner, and a `DeviceId`-keyed runtime registry/router; 3/3 Intent/framework instrumentation cases pass on an API 36 ARM64 emulator. The operational runtime/storage/shell bridge executes offline, while the production factory intentionally lacks binding/endpoint-backed association/recovery. Driver, image-state, disclosure UI, and bounded audio diagnostics have now run physically | Local service/framework and process-owner candidates / bounded device diagnostics physical | Complete the named Motorola storage/service/process/OEM matrix separately; keep diagnostic audio outside product recording; implement durable binding/user-stop and the explicit M1 runtime only after device identity/capture truth gates |
| Edge runtime | Typed capability/supervisor/spool/upload core, portable `RuntimeHost`, one-device `OperationalDeviceRuntime`, `DeviceId` runtime registry/router, process-global spool owner, one-host Android operational graph, generation-fenced `OperationalShellBridge`, host-neutral shell/control presentation, exact-profile Omi simulator, Linux negotiated-driver witness, Android encrypted spool adapter, and one-attempt OkHttp chunk adapter pass local suites; `EDGE_HOST` backlog is explicitly withheld from per-device shell truth; the original five Android storage primitive cases pass on an API 36 ARM64 emulator, while two newer operational/cancellation cases are compile-only | Offline/local-framework candidates, not the provisioned Android product graph | Instantiate one explicit M1 runtime from durable binding/endpoint ports, run all seven storage cases on the Motorola, then prove forced-death/reboot without claiming capture or upload |
| Cloud data plane | Publisher-owned OpenAPI/JSON Schema, provider-neutral core, adversarial fixtures, exact Ogg/Opus assembly, and a dependency-free Node HTTP boundary for all six operations with local auth/body/routing/problem/log tests | Contract/core/HTTP locally executable | Implement real verifier/revocation, durable metadata/object stores, limiter, retention, TLS ingress and deployment; freeze only after crash/abuse/end-to-end qualification |
| Cloud processing | Provider-neutral job/attempt/lease core, explicit outcome-unknown recovery, scoped callback boundary, immutable transcript artifact/provenance, bounded digest-bound content pages, and crash suite | Contract/core executable | Select/evaluate provider and production auth/store/worker adapters, then deploy one digest-bound job |
| Astrale semantics | Official `gumi.astrale.ai` scaffold; generated publisher types; caller-scoped Device/CaptureSession/Recording authority; immutable object facts; one-shot terminal receipts; Recording-bound, page-resumable Transcript publication; authorized Conversation membership; and a bounded Conversation target view/client with provenance and integrity checks pass local adversarial simulations | Local executable, including the Conversation read surface | Add provisioning/tenant identity, install live, and run authorized/adversarial multi-recording witnesses against the installed domain |
| Physical acceptance | Advertisement/GATT/allowlisted reads, driver negotiation, corrected image-state, and 10-second content-free audio metadata captured. Application matches v3.0.12; network/secondary state is unobserved. Stock Off-state button wake failed in an uncontrolled observation and charger insertion recovered it | Gate 0 partial | Controlled gesture/indication/reconnect/storage/power matrix, including deterministic Off transition, charger-independent button wake, and post-wake microphone truth |
| Companion association | Official API 29–37 chooser/presence plan fixes the exact Omi filter and keeps platform evidence separate from identity; no adapter exists | Design only | Run the next owned-phone process-local rotation preflight; on `CHANGED`, use the foreground fallback, otherwise implement Decision 0004 and separately prove association resolution across process recreation before presence |

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

1. **Done:** bootstrap the Kotlin-first edge workspace, enforce dependency and firmware-mutation
   boundaries, and prove Android/JVM builds.
2. **Physical diagnostic completed with a bounded result:** the deterministic
   [image-state handoff](development/omi-image-state-handoff.md) matched the installed application to
   official v3.0.12, explicitly classified the absent network row as
   `APPLICATION_MATCH_NETWORK_UNOBSERVED`, and qualified one content-free 10-second audio metadata run.
   This closes neither full image identity nor OTA authorization.
3. Complete the remaining Gate 0 gesture, indication, reconnect, storage, and controlled power/wake
   observations before any OTA action. Treat charger-only recovery as a stock qualification gap; do not
   infer pairing or silently make a charger part of Gumi's normal wake contract.
4. Establish the baseline commit before importing upstream history.
5. Import the pinned Omi firmware subtree at `devices/omi-cv1/firmware` and reproduce the application
   build through a repository-owned command.
6. **Offline v3.0.12 witness done:** the portable Omi ring codec passes all 14 golden cases; the
   deterministic simulator exactly matches the observed 11-service/21-characteristic profile, drives
   negotiated typed handles, and injects split/duplicate/reorder/disconnect/reboot/failure scripts.
   The v3.0.20 simulator remains gated on its first required behavior.
7. **Bounded physical diagnostic done:** the Android Compose shell is installed on the owned phone; the
   read-only scanner/GATT inspector, driver negotiation, disclosure modal, application image-state, and
   content-free live-audio metadata witness succeeded on the pendant; the network image and secondary
   slots remain unobserved; the
   operational Nordic central and explicit driver-negotiation probe pass tests proving three reads,
   zero writes/subscriptions, and guaranteed close. Its one process-scoped diagnostic lease stays held
   through cancellation cleanup, and a new scan invalidates prior card/review/firmware/audio authority.
   Preserve the transactional bundles and repeat only after a relevant APK/firmware change or a named
   qualification need. The operational service scaffold is already local, but it remains deliberately
   unable to recover or connect; do not mix it into this foreground diagnostic gate.
8. **Human-I/O reference owners complete / end-to-end and physical proof open:** the proposed CV1
   grammar has a pure deterministic oracle. It loads the exact 20-case corpus, proves the one debounce
   trace plus all 12 recognizer-owned traces with exact ordered equality, and executes all seven
   capture-fault, lifecycle-projection, and output-arbitration cases through their owning model. The
   model's 13 logical indicator definitions and 10 switched-ERM patterns are checked exactly against
   `contract.json`. Gesture-to-effect completion is not yet one end-to-end firmware simulation, and
   none of this is stock/custom firmware or physical gesture/output proof.
9. **Portable host and Android service scaffold done / product composition open:** `RuntimeHost`
   serializes host commands and effects, establishes foreground before recovery, replays duplicate
   command identities, preserves explicit user-stop suppression, and conservatively cleans up
   cancellation, stale completion, and outcome-unknown cases in common JVM/Android-host tests. The
   Android shell now adds an application
   owner, unexported/non-sticky `connectedDevice` service, provisional foreground promotion, exact
   endpoint release, coalesced stops, UUID retry identities, notification actions, and explicit Activity
   controls. Its production ports intentionally refuse association/recovery; Companion association,
   durable binding/user-stop restoration, portable-shell/Omi/spool/cloud composition, named
   device/update/sync leases and the Motorola lifecycle matrix remain open. The shell Intent suite has
   executed 3/3 on an API 36 ARM64 emulator; that does not prove service survival or OEM behavior.
   Separately, the portable `OperationalDeviceRuntime` now proves ordered binding, storage, transport,
   endpoint, negotiation and power acquisition plus reverse cleanup; the Android operational-storage
   adapter and `OperationalShellBridge` pass focused local suites. Capture remains unverified and the
   Android factory does not compose these pieces.
10. **Dedicated application-only flash lab ready offline / physical owner gate pending:** the separate
   Android APK pins only stock-to-canary and canary-to-stock, packages exactly those two application
   binaries, exposes no network/multi-image/generic updater, rechecks source state immediately before
   upload, verifies staged/confirmed state, and treats reset disconnect as outcome-unknown pending a
   fresh read. Its tests cover the complete dry-run, source drift, cancellation, endpoint substitution,
   and post-reboot validation. Under Decision 0005, a wholly absent network row is accepted only for
   these two transitions; any visible network evidence must match exact stock. Scoped lint, 37 unit
   tests, input-artifact verification, and final-APK permission/asset audit pass. The APK is not part of
   the product shell and no physical mutation is authorized until the fresh owner-reviewed preflight.
11. **Contract/core/HTTP and edge chunk candidate done locally:** the provider-owned media-ingest API
   and core pass the contract/failure suites for exact ACKs, renewal, conflict/gap/finalization sets,
   canonical digests, and byte-exact Ogg/Opus assembly. Its Node HTTP adapter covers all six routes with
   strict credential classes, bounded streaming, canonical routing, exact content metadata, typed
   problems, disconnect handling, and allowlisted logs. The portable edge coordinator proves deterministic durable
   selection, verified payload reads, pre-attempt fencing, one-call serialization, exact normalized ACK
   application, and non-blind unknown/rejected recovery; the OkHttp adapter adds a 1 MiB preflight cap,
   dedicated no-hook/no-retry client, strict string ACK validation, and exact problem content type.
   Keep these local candidates behind ports; production credentials/revocation, stores, limiter,
   deployment, Android composition, and the end-to-end authorization path remain unproved.
12. **Android storage candidate emulator-proven / handset pending:** Keystore AES-GCM/HMAC, an
   encrypted SQLite/WAL snapshot, immutable no-backup payload files, exclusive opening, and startup
   reconciliation implement the portable ports. The original five instrumentation cases pass on an
   API 36 ARM64 emulator, including real Keystore, SQLite WAL/FULL, flush/atomic rename,
   reopen/exclusive ownership, missing-row/payload, key loss, and plaintext-scan cases. Two newer
   cancellation/operational-lease cases compile but await Android execution. The adapter deliberately exposes
   one process-global M1 spool and an edge-host-global backlog; it is not a per-device store. Motorola/OEM
   execution, forced-death, reboot, migration, durable rotation, rollback scope, and operational
   composition remain gates.
13. **Local slice done:** Device, CaptureSession close, and manifest-backed Recording ownership, caller
    gates, generated publisher projection, capture/device/edge-host validation, fixed-path terminal
    receipts, and concurrent retry convergence are executable in `gumi.astrale.ai`. Provisioning,
    tenant/global identity, and live install simulation remain required before deployment.
14. **Processing-to-semantics vertical done locally:** `media-processing` owns digest-bound jobs,
    bounded attempt/lease generations, explicit outcome-unknown retry, provider-scoped callbacks,
    immutable transcript artifacts/provenance, and bounded no-store content pages. `gumi.astrale.ai`
    consumes generated types through a caller/Recording-bound reader, imports exact pages behind atomic
    receipts, keeps provider text inert, resumes after an outage, and validates complete ready replay
    without another provider read. Select providers and production adapters only after the evaluation
    and authorization gates; local simulations do not claim a real transcription, deployment, or live
    Astrale installation.
15. **Conversation read vertical and cloud operating model done locally:** authorized Conversation
    creation/membership, exact Recording/Transcript provenance, and a bounded target view/client reject
    substitution and integrity contradictions while rendering transcript text inert. The cloud app
    catalog also makes deployment-unit ownership explicit. These are local witnesses only: provisioning,
    live install, durable adapters, and deployed multi-recording evidence remain open.

### Next honest Android product slice

Do not turn the local service, operational runtime, storage, shell bridge, driver, and HTTP candidates
into a recording/upload claim by connecting them directly. The stock Omi session currently has
`deviceId = null`, exposes no
`CaptureControl`, and provides no capture-state observation. No operational live-media owner yet binds
notifications to a mux snapshot, durable source checkpoint, or spool advancement. Android also lacks
the composed product recovery/event ingress, durable provisioning binding and endpoint resolver,
cloud-auth composition, required product dependency edges, and `INTERNET` permission. `USER_STOPPED`
is process-local only, and no production scheduler currently ages quiet shell projections.

The implementation order is:

1. **Done as an unprovisioned local composition primitive:** one process owner now combines one
   foreground-execution lease, one spool owner, and a `DeviceId`-keyed runtime registry/command router,
   without an automatic start source. The production `Application` still uses fail-closed recovery and
   does not instantiate this graph;
2. instantiate exactly one explicit M1 device and bridge its `OperationalDeviceRuntime` to
   `DefaultShellApplication`; do not instantiate one Android foreground host per device;
3. open the process-global encrypted spool to `Ready` before BLE. Until capture metadata is durably
   device-bound, expose its recovered backlog only as edge-host-global, never per-device truth;
4. run Decision 0004's process-local rotation preflight; on `CHANGED`, retain explicit foreground
   selection, otherwise implement the chooser/association adapter and require a separate cross-process
   association-resolution handset gate before claiming Companion presence; add the durable
   provisioned-device binding in either branch;
5. add the production shell freshness scheduler and persist user-stop/exit policy before enabling any
   presence-driven start;
6. qualify upload independently with synthetic already-durable chunks, real scoped auth, and no device
   capture dependency;
7. add the live media/mux/checkpoint owner only after capture truth and cursor policy exist.

The first owned-handset operational witness is deliberately smaller than recording: a person explicitly
selects/associates the device according to the chosen foreground/Companion branch, storage reports `Ready`, an explicit
start obtains the visible foreground lease, exactly one Omi BLE session reports real link/power facts,
capture remains unverified, zero audio notification is subscribed, and explicit stop closes the device
and service cleanly. It reads no ring content, advances no cursor, records/uploads no audio, and starts
no automatic presence loop. If any condition is missing, the witness fails closed rather than shrinking
its claim.

Steps 4–14 can advance while the physical probe is waiting; Gate 1 cannot.

Environment note, 2026-07-19: the host originally had no JDK, Gradle, Android SDK, `adb`, or Android
Studio. A checksum-verified workspace-local JDK 17/Android toolchain and Gradle wrapper are now installed;
the exact pins and handset handoff are recorded in [the bootstrap runbook](development/bootstrap.md).
This closes the build bootstrap risk. Local encrypted-storage/service/HTTP candidates also exist, and
the two Android instrumentation suites run on an API 36 emulator, but their owned-handset execution and
product composition remain open; Gate 2's physical BLE/audio, process restart, and owned-device evidence
are not closed.

## Decision schedule

| Decision | Must be made before | Evidence required |
| --- | --- | --- |
| Exact Android/Kotlin/Gradle pins (resolved for current bootstrap) | Re-open only on measured incompatibility | Android + Linux/JVM compile/test gate remains green |
| Metadata database and spool encryption | First durable local audio | Repeat the landed [Decision 0002](decisions/0002-durable-spool-storage.md) candidate on the Motorola, then run forced death/reboot, migrations, key loss, durable rotation design, and rollback-scope witnesses |
| Android runtime-host lifecycle | First background BLE capture | Complete [Decision 0003](decisions/0003-android-runtime-host-lifecycle.md): real device/storage/cloud composition, durable restart/user-stop policy, process-wide lease, and OEM lifecycle witnesses |
| Android Companion association/presence | Any automatic presence start | Execute [Decision 0004](decisions/0004-android-companion-association.md): process-local stock rotation preflight, explicit chooser, API 29–37 adapter, post-implementation association resolution across process recreation, generation fencing, durable binding, and user-stop/exit negatives |
| Existing stock-media disposition | First ring-content read | Explicit owner choice, consent scope, import/delete recovery semantics |
| Capture/participant consent policy | First real recording outside a controlled test | Applicable product/legal policy, physical indication, revocation/stop behavior |
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
| Existing stock ring contains sensitive media | Quarantine it; no read, transcription, upload, advance, or clear before an explicit owner workflow |
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
