# Gumi

Gumi is an edge-to-cloud operating architecture for connecting physical devices to Astrale OS.

The first supported substrate is the Omi Consumer Version 1 pendant. The first edge host is a mobile
phone. Neither is the permanent architectural center: an Omi can be replaced by another sensor or
actuator, and the mobile host can be replaced by a Raspberry Pi or another local computer.

## First milestone

M1 is a complete, secure capture and voice-action system:

- custom Omi firmware with explicit recording and push-to-talk behavior;
- a reusable local edge runtime composed by an Android-first applicative shell;
- live and offline-resumable audio transfer;
- authenticated media ingest and object storage;
- Astrale-owned recording, transcript, conversation, and action semantics;
- transcription and realtime AI actions with results returned to the edge shell; and
- recovery, privacy, observability, and end-to-end qualification.

M1 is not a file-upload demo and not a thin reskin of the Omi application.

## Current phase

The repository now combines the governing specifications with an executable edge and semantic-cloud
spine. Typed capability sessions, the fail-safe capture reducer, and host-neutral shell projections run
on Android and JVM. A serialized device supervisor and transactional spool core now exercise acquired
capture truth, crash reconciliation, exact acknowledgements, gaps, replay, and quota pressure on both
targets. A deterministic Omi simulator exactly matches the checked v3.0.12 GATT inventory, injects
transport failures, and drives the real negotiated driver through the BLE port; the Linux witness opens
that path rather than a simulator-only branch. The Android Nordic transport and explicit zero-write
driver-negotiation probe build and pass offline tests.

The proposed Omi CV1 human-I/O mapping now has an executable pure reference oracle. Its JVM suite loads
the exact 20-case device-owned corpus, executes the one debounce case and all 12 recognizer-owned
gesture cases with complete ordered-trace equality, and executes all seven capture-fault,
lifecycle-projection, and output-arbitration cases through their owning model. The logical definitions
of all 13 indicator patterns and all 10 switched-motor patterns are checked exactly against the device
contract. This is protocol-design evidence only: stock/custom firmware behavior, physical timing,
indication visibility, and haptic distinguishability remain unproved.

The foreground Android diagnostics share one process-scoped BLE-operation lease across connected GATT,
driver, firmware-image, and bounded live-audio actions. Discovery is gate-guarded rather than leased;
connection actions stop scanning before acquiring the lease. The lease remains held through cancellation
cleanup, and a new scan generation drops old actionable cards, disclosure reviews, firmware
qualification, and the derived audio unlock before accepting fresh observations. These are local
controller/UI guarantees, not the operational background-host lifecycle or physical qualification.

The portable edge `RuntimeHost` now serializes host start/stop effects, checks
association/presence/permission before foreground execution, establishes foreground execution before
rehydration, replays duplicate command identities, preserves explicit user-stop policy, and treats
stale or outcome-unknown cleanup conservatively. The Android shell now wraps that host in one
application-process owner and an unexported, non-sticky `connectedDevice` foreground-service scaffold.
Its visible controls use stable UUID command identities, its notification exposes redacted Open/Stop
actions, and coalesced stops keep the service until cleanup and exact foreground release converge.

The next portable operational layer also executes offline: one `OperationalDeviceRuntime` proves
binding -> reconciled storage -> transport lease -> ephemeral endpoint -> negotiated Omi -> power
acquisition and exact reverse cleanup while capture remains unverified. The Android storage adapter
opens and reconciles the encrypted spool behind that port, and `OperationalShellBridge` projects
generation-fenced link, power, storage, backlog, and per-axis freshness into the reusable shell while
rejecting capture commands. These are local candidates, not an Android product graph.

For the first operational M1 witness, the supported cardinality is exactly one explicitly started
device using one process-global spool; its recovered backlog is edge-host-global, not device-scoped.
The scalable composition boundary must be one process owner with one foreground-execution lease, one
spool owner, and a `DeviceId`-keyed runtime registry/command router. The production Android factory
still deliberately reports association and recovery unavailable and has no durable binding/endpoint
resolver, durable user-stop restoration, shell freshness scheduler, real Omi composition, cloud sync,
Companion adapter, automatic restart source, handset service test, process-death/reboot, or OEM run.

The stock Omi driver currently resolves no provisioned Gumi `deviceId` and exposes no
`CaptureControl` or capture-state observation. There is also no operational live-media owner binding
BLE notifications to mux/checkpoint/spool recovery. Product recording and upload therefore remain
disabled even when the local service scaffold is visible; the next operational slice, once its
composition lands, is one explicit foreground connection with capture left unverified and zero audio
subscription.

The first Astrale fleet/capture domain slice builds with caller-authority, close-before-finalize, and
concurrent one-shot terminal simulations. It consumes generated types from the publisher-owned
media-ingest OpenAPI and validates capture, device, and edge-host binding before admitting an immutable
projection. That projection now includes the finalized primary object's exact content digest, byte
length, and Ogg/Opus content type, so downstream processing never has to trust an opaque handle alone.
Media-ingest v1 includes a dependency-free Node HTTP boundary for all six operations, with separate
control/data bearer checks, bounded streamed bodies, canonical routing, exact byte/digest validation,
RFC 7807 problems, and allowlisted logs. Its provider-neutral core still uses injected test ports
locally; no real verifier, revocation source, database, object store, limiter, TLS ingress, deployment,
or retention worker is production-qualified.

The portable edge upload seam now selects durable chunks deterministically, verifies payload reads,
fences an exact canonical-descriptor upload before one external attempt, and applies only a matching
normalized durable ACK. An Android storage candidate implements the runtime ports with Keystore
AES-GCM/HMAC keys, an encrypted SQLite/WAL snapshot, immutable no-backup payload files, exclusive
opening, and startup reconciliation. A portable OkHttp chunk adapter independently maps the canonical
descriptor, enforces the publisher's 1 MiB cap, uses a dedicated client with no inherited hooks or
transparent retry, and accepts only bounded, exact string-shaped ACKs or exact problem content types.
Their deterministic suites pass locally. On an API 36 ARM64 emulator, the original five Android
storage primitive cases and all 3/3 shell Intent/framework cases pass; two newer storage
cancellation/operational-lease cases compile but have not run on an Android target. That is local
framework evidence only: neither adapter is composed into the operational Android capture path,
and no Motorola/OEM storage qualification, real cloud durability, forced-death/reboot matrix, or
end-to-end upload is claimed.

Media-processing v1 is now a separate executable contract candidate for digest-bound transcription
jobs, bounded attempt leases, explicit outcome-unknown recovery, provider-scoped callbacks, immutable
transcript artifacts/provenance, and a four-segment no-store content page. The Gumi Astrale app consumes
generated publisher types through a Recording-bound reader and locally proves authorized, resumable,
fixed-receipt Transcript/segment publication, inert prompt-like text, exact ready replay, and foreign
artifact rejection. This remains local evidence: no transcription provider, production store/runtime,
deployment, live Astrale installation, or real media job exists yet.

The Android shell has already completed a read-only GATT inventory of the owned Omi. Physical proof of
the new operational session, audio streaming, encrypted production persistence/restart, firmware
mutation, deployed cloud, and live Astrale gates remains explicitly open:

- [Omi CV1 capability audit](devices/omi-cv1/docs/research/capability-audit.md)
- [Omi CV1 component and wiring map](devices/omi-cv1/docs/research/component-map.md)
- [Sealed-device firmware plan](devices/omi-cv1/docs/research/sealed-device-plan.md)
- [Stock v3.0.20 OTA inspection](devices/omi-cv1/docs/research/stock-ota-inspection.md)
- [Exact-source firmware build reproduction](devices/omi-cv1/docs/research/build-reproduction.md)
- [Android read-only probe](devices/omi-cv1/docs/research/android-read-only-probe.md)
- [Stock BLE threat model](devices/omi-cv1/docs/security/ble-threat-model.md)
- [Owned-device inventory](devices/omi-cv1/docs/research/device-inventory.md)
- [M1 system contract](docs/specs/m1-system-contract.md)
- [Device human-I/O contract](docs/specs/device-human-io-contract.md)
- [Gated execution roadmap](docs/roadmap.md)
- [M1 evidence and acceptance matrix](docs/qualification/m1-evidence-matrix.md)
- [Repository and dependency layout](docs/architecture/repository-layout.md)
- [Cloud application conventions](cloud/README.md)
- [Edge runtime and plugin contract](docs/specs/edge-runtime-contract.md)
- [Android-first edge technology decision](docs/decisions/0001-android-edge-stack.md)
- [Durable spool storage decision](docs/decisions/0002-durable-spool-storage.md)
- [Android runtime-host lifecycle decision](docs/decisions/0003-android-runtime-host-lifecycle.md)
- [Android companion association/presence decision](docs/decisions/0004-android-companion-association.md)
- [Verified edge development bootstrap](docs/development/bootstrap.md)
- [Next Android return session](docs/development/android-return-session.md)
- [Owned-phone morning image/audio handoff](docs/development/omi-image-state-handoff.md)
- [Reuse ledger](docs/reuse-ledger.md)
- [Omi upstream policy](devices/omi-cv1/UPSTREAM.md)
- [OTA protocol evidence](devices/omi-cv1/protocols/ota/v1/README.md)
- [Offline ring protocol fixtures](devices/omi-cv1/protocols/ring/v1/README.md)
- [Source-declared GATT profile](devices/omi-cv1/protocols/gatt/v3.0.20/README.md)
- [Owned-unit v3.0.12 GATT profile](devices/omi-cv1/protocols/gatt/v3.0.12/README.md)
- [Omi CV1 human-I/O fixtures](devices/omi-cv1/protocols/human-io/v1/README.md)
- [Gumi Astrale authority model](cloud/apps/gumi/docs/authority-model.md)
- [Media-ingest application contract](cloud/apps/media-ingest/README.md)
- [Media-processing application contract](cloud/apps/media-processing/README.md)

## Governing principles

1. Model devices as compositions of versioned capabilities, not as brand-specific base classes.
2. Keep hard realtime behavior, physical safety, and truthful privacy indication local.
3. Separate control and semantic state from high-volume binary media transfer.
4. Make every transfer resumable and every externally visible effect idempotent.
5. Reuse proven code and protocols when they fit the boundary; retain provenance and tests.
6. Treat source claims, release claims, and measurements from a physical unit as different evidence.
7. Never claim a capability is delivered until the real device-to-Astrale path has been exercised.
