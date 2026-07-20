# Repository and dependency layout

Status: governing M1 monorepo boundary. The inventory below marks current directories as **landed** and
future ownership slots as **planned**; a planned name is not evidence that code, tests, or a deployment
exists.

Gumi has three product substrates: physical devices, the local edge, and the cloud. Those boundaries
remain stable when a phone becomes a Raspberry Pi, Omi becomes a different sensor or actuator, or a
cloud application changes runtime.

The repository stays a monorepo while one team needs atomic device-to-cloud changes and one end-to-end
acceptance gate. A monorepo does not imply one runtime, one deployment, or unrestricted dependencies.

## Source roots

```text
gumi/
├── devices/                                      [landed]
│   └── omi-cv1/                                  [landed]
│       ├── protocols/                            [landed: GATT/ring/OTA/HIO fixtures]
│       ├── edge-driver/                          [landed]
│       ├── simulator/                            [landed]
│       ├── tests/hardware-in-loop/               [landed: guarded Android/Omi harness]
│       ├── docs/ and UPSTREAM.md                  [landed]
│       ├── firmware/                             [planned: history-preserving import]
│       ├── hardware/                             [planned only if Gumi owns hardware]
│       └── tests/compatibility/                  [planned if cross-module tests need it]
├── edge/                                         [landed]
│   ├── sdk/                                      [landed]
│   ├── runtime/                                  [landed]
│   ├── adapters/cloud/media-ingest/              [landed: chunk HTTP candidate]
│   ├── platforms/android/                        [landed: BLE and spool candidates]
│   ├── platforms/linux/                          [planned]
│   ├── shell/application/                        [landed: host-neutral control plane]
│   ├── shell/android/                            [landed: diagnostics and host scaffold]
│   ├── shell/linux/                              [landed: JVM witness]
│   ├── services/                                 [planned when an independent local app exists]
│   └── tests/                                    [planned only for genuinely cross-module edge tests]
├── cloud/                                        [landed]
│   ├── apps/gumi/                                [landed: local Astrale domain slice]
│   ├── apps/media-ingest/                        [landed: contract/core/HTTP candidate]
│   ├── apps/media-processing/                    [landed: contract/core candidate]
│   ├── apps/realtime-gateway/                    [planned]
│   ├── packages/ and platform/                   [planned after demonstrated reuse]
│   └── tests/                                    [planned for cross-app acceptance]
└── docs/                                         [landed: system-wide material only]
```

Only `devices`, `edge`, and `cloud` are product source roots. Repository metadata such as `docs`, CI,
and workspace configuration may remain at the root, but deployable code, firmware, infrastructure, and
tests belong to one of the three substrates.

Do not create the full tree as placeholders. Each directory appears with an owner, build target, test,
or executable artifact.

For the current M1 boundary, `cloud/apps/gumi` is one Astrale domain with internal fleet, capture,
conversation, and assistant contexts. Only fleet/capture exist today. Splitting those contexts into
separate domains before independent authority or release boundaries emerge would add distributed
coordination without improving ownership.

## Why the shell lives under edge

The shell is the applicative control plane that a person or another local program interacts with. It is
portable across Android and Linux, but it still executes on the edge substrate, so its logical home is
`edge/shell`, not a fourth product root.

The shell is a composition root and experience surface. It selects the runtime, host and cloud
adapters, and device plugins; invokes application use cases; exposes diagnostics; and renders state.
Capture, durable transfer, retry, and acknowledgement invariants remain in `edge/runtime`, so replacing
an Android UI with a Raspberry Pi shell cannot change correctness.

An edge application that should run independently of the shell belongs in `edge/services/<app>`. Do not
put local processes in `cloud/apps` merely because they communicate over a network.

## Device capsule

Every `devices/<device-id>` directory is a cohesive physical-product integration. It may contain:

- firmware owned or imported for that device;
- device-local wire protocols, storage formats, OTA manifests, and compatibility fixtures;
- an edge driver implementing `edge/sdk` capability ports;
- hardware sources if Gumi actually owns them;
- device-specific simulators, compatibility tests, HIL tests, research, and recovery runbooks; and
- upstream provenance and licensing.

Not every device needs firmware or hardware sources. A closed smart ring might contain only a driver,
protocol evidence, and qualification tests. Omi-specific formats never become global contracts just
because the first edge shell consumes them.

Atomic firmware/driver/protocol changes inside the capsule are intentional. The dependency direction is
not: the device's edge driver may implement `edge/sdk`, but `edge/runtime` must never import a concrete
device.

## Cloud application model

`cloud/apps/<app-id>` is the unit of remote behavior, authority, deployment, and ownership. The directory
may contain an Astrale domain, HTTP or realtime API, workers, provider integrations, deployment config,
and tests according to what that application needs. An Astrale domain is an application's implementation,
not a reason for a repository-wide `astrale/` layer.

The app directory remains self-contained and independently buildable. A flat, stable app ID avoids
organizing hundreds of apps by today's runtime or team. Add namespaces only when a real ownership or
tooling boundary requires them.

`cloud/packages` is not a miscellaneous helper drawer. Code moves there only after at least two cloud
apps need the same stable abstraction and neither app is its natural publisher.

## Contract ownership

There is no root `contracts/` directory. “Contract” describes a boundary, not an architectural owner:

| Boundary | Canonical home |
| --- | --- |
| Device firmware ↔ edge driver | `devices/<device>/protocols/` |
| Edge plugin capability API | `edge/sdk/` |
| Cloud app request/realtime API | `cloud/apps/<publisher>/api/` |
| Cloud app persisted schema or event | `cloud/apps/<publisher>/` |
| Reused cloud-only abstraction | `cloud/packages/<package>/` after demonstrated reuse |

The publisher owns the schema, compatibility policy, golden examples, and versioning. Consumers generate
clients or import a published package and keep consumer-side conformance tests near themselves. Do not
copy a schema into a second “shared” location.

Prefer established formats and generators:

- JSON Schema 2020-12 for persisted manifests and capability/control values;
- OpenAPI 3.1 for request/response APIs and generated clients;
- MCU Manager/SMP and MCUboot manifests for stock-compatible Omi OTA;
- Opus for M1 audio;
- SQLite WAL semantics behind the edge persistence port; the current Android candidate encrypts one
  strict metadata snapshot before SQLite and remains target-qualification evidence, not a universal
  database choice;
- OpenTelemetry/OTLP for telemetry; and
- golden binary fixtures for device formats without a suitable standard schema.

Do not add AsyncAPI, Protobuf, CloudEvents, or a broker for symmetry. Adopt one when the chosen transport
or interoperability requirement justifies it.

## Dependency law

```text
devices/<device>/firmware ──uses──> devices/<device>/protocols
devices/<device>/edge-driver ─────> devices/<device>/protocols
                    │
                    └──implements─> edge/sdk <──uses── edge/runtime
                                                     ▲
edge/platforms/<host> ─────────implements runtime ports
                                                     ▲
edge/shell or edge/services ──compose runtime + platform + selected device drivers

cloud/apps/<publisher>/api ──generates/publishes──> edge/adapters/cloud/<publisher>
edge/runtime ──calls through a port────────────────> edge/adapters/cloud/<publisher>
```

- `edge/sdk` and `edge/runtime` cannot import Android, Flutter UI, BlueZ, a concrete device, a concrete
  database, a cloud provider, or the Astrale client.
- Device firmware has no knowledge of edge UI or Astrale semantics. It exposes physical capabilities and
  truthful local state.
- Device drivers translate device-specific behavior into edge capabilities; they do not own capture
  policy or cloud sessions.
- Platform adapters implement OS facilities without redefining runtime state machines.
- The shell and local services are composition roots. They may select concrete adapters and drivers but
  do not reimplement their invariants.
- Cloud apps accept narrowly scoped identities appropriate to their behavior. Media ingest does not gain
  broad Astrale graph authority.
- Astrale-backed apps store semantic state and opaque media references, never BLE packet state or raw
  audio chunks.

For the landed Kotlin modules these rules are enforced by `./gumiw verifyArchitecture`. The check
rejects forbidden Kotlin imports and forbidden Gradle project-dependency edges. Extend the rule set
and its negative probes whenever a new platform, cloud, or device module lands. Cloud applications
additionally own app-local boundary tests because they are not Gradle projects.

## Test ownership

Tests live with the narrowest owner capable of diagnosing a failure:

- device protocol fixtures under `devices/<device>/protocols`, driver/simulator tests in those modules,
  and physical Omi/phone qualification under `devices/<device>/tests/hardware-in-loop`;
- portable runtime, adapter, platform, and shell tests beside their owning modules (`edge/runtime/src`,
  `edge/adapters/.../src`, `edge/platforms/.../src`, and `edge/shell/.../src`), which is the current
  layout; `edge/tests` is reserved for a future test that genuinely spans several edge modules;
- app-local contract, core, and HTTP tests inside each `cloud/apps/<app>`; and
- future cross-application cloud acceptance under `cloud/tests` only after such a suite exists.

The current guarded physical harness is Android/Omi-specific and therefore remains in the Omi capsule.
A later complete physical-to-cloud acceptance orchestrator belongs with the composition that initiates
that run and references publisher fixtures; no such suite is claimed today.

There is no root `tests/` grab bag.

## Upstream import boundary

- Import `omi/firmware` with history at `devices/omi-cv1/firmware` after the specification baseline is
  reviewed and committed. Application-image qualification and network-image qualification stay separate.
- Port selected Omi client behavior into `devices/omi-cv1/edge-driver` or its compatibility tests with
  per-file provenance; do not import the upstream mobile app wholesale.
- Keep hardware CAD/BOM externally pinned until Gumi changes it.
- Do not vendor NCS, Zephyr, Nordic mobile libraries, Opus, or ordinary package-manager dependencies.
  Pin them with their native lock/build mechanisms.

The governing details are in [the Omi upstream policy](../../devices/omi-cv1/UPSTREAM.md) and the
[reuse ledger](../reuse-ledger.md).

## Repository split criteria

A component moves to another repository only when one of these becomes real:

- a distinct access-control boundary, such as private firmware signing infrastructure;
- an independent release and ownership team;
- a materially different compliance or data-residency boundary; or
- repository size and tooling make partial checkout or isolated CI measurably inadequate.

If a split happens, `devices`, `edge`, and `cloud` remain the logical ownership model. Deployment
independence alone is not a reason to split source.
