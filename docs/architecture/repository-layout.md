# Repository and dependency layout

Status: proposed M1 monorepo boundary. Directory names become real only when their first executable
artifact lands.

Gumi has three product substrates: physical devices, the local edge, and the cloud. Those boundaries
remain stable when a phone becomes a Raspberry Pi, Omi becomes a different sensor or actuator, or a
cloud application changes runtime.

The repository stays a monorepo while one team needs atomic device-to-cloud changes and one end-to-end
acceptance gate. A monorepo does not imply one runtime, one deployment, or unrestricted dependencies.

## Source roots

```text
gumi/
├── devices/                       # Integrations for physical products
│   └── omi-cv1/
│       ├── firmware/              # History-preserving Omi firmware subtree
│       ├── protocols/             # Omi GATT, ring, OTA schemas and golden fixtures
│       ├── edge-driver/           # Omi plugin implementing the public edge device SDK
│       ├── hardware/              # CAD/BOM only if Gumi starts owning hardware changes
│       ├── tests/
│       │   ├── compatibility/     # Firmware/driver protocol compatibility
│       │   └── hardware-in-loop/  # Tests requiring an owned physical unit
│       ├── docs/                  # Omi-specific research, security, and runbooks
│       └── UPSTREAM.md            # Provenance, pins, and update procedure
├── edge/                          # Software running on the always-on local computer
│   ├── sdk/                       # Stable capability and plugin interfaces
│   ├── runtime/                   # Portable state machines, spool, policy, sessions
│   ├── adapters/
│   │   └── cloud/                 # Cloud API clients implementing runtime ports
│   ├── platforms/
│   │   ├── android/               # GATT transport, lifecycle, Keystore, persistence
│   │   └── linux/                 # BlueZ, systemd, secret store, persistence
│   ├── shell/                     # Portable applicative shell and host distributions
│   │   ├── application/           # Host-neutral use cases, projections, control API
│   │   ├── android/               # First UI/composition root
│   │   └── linux/                 # Raspberry Pi UI or headless composition root
│   ├── services/                  # Future local daemons/apps not hosted by the shell
│   └── tests/                     # Runtime, platform, restart, and edge E2E suites
├── cloud/                         # Independently deployable remote applications
│   ├── apps/
│   │   ├── media-ingest/
│   │   ├── media-processing/
│   │   └── realtime-gateway/
│   ├── packages/                  # Deliberate cloud-only shared libraries
│   ├── platform/                  # Shared deployment foundations when they exist
│   └── tests/                     # Cross-application cloud acceptance suites
└── docs/                          # Only system-wide decisions, specifications, and runbooks
```

Only `devices`, `edge`, and `cloud` are product source roots. Repository metadata such as `docs`, CI,
and workspace configuration may remain at the root, but deployable code, firmware, infrastructure, and
tests belong to one of the three substrates.

Do not create the full tree as placeholders. Each directory appears with an owner, build target, test,
or executable artifact.

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
- SQLite WAL semantics behind the edge persistence port, if the storage spike passes;
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

These rules become executable package/import checks once implementation languages and workspaces land.

## Test ownership

Tests live with the narrowest owner capable of diagnosing a failure:

- protocol vectors and firmware/driver compatibility under `devices/<device>/tests`;
- physical qualification under `devices/<device>/tests/hardware-in-loop`;
- portable runtime and platform contract suites under `edge/tests`;
- app-local tests inside each `cloud/apps/<app>`;
- cloud cross-app acceptance under `cloud/tests`; and
- the M1 physical-to-cloud scenario under the initiating `edge/shell` acceptance suite, referencing the
  device and cloud fixtures rather than copying them.

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
