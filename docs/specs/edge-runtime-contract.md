# Edge runtime and plugin contract

Status: design contract for the first executable edge workspace. API names are provisional; ownership,
dependency direction, and invariants are governing.

## Purpose

The edge is the always-on local authority between physical devices and remote cloud applications. The
same runtime must be composable into an Android shell and a Raspberry Pi host without importing Omi,
Android UI, or Astrale semantics into its core.

This contract defines four distinct roles:

1. `edge/sdk` publishes capability, transport, driver, identity, and test interfaces.
2. `edge/runtime` owns state machines, policy, durability, coordination, and cloud-facing ports.
3. `devices/<device>/edge-driver` translates one physical product into SDK capabilities.
4. `edge/shell` composes concrete modules and exposes commands/projections to a person or local program.

## Module dependency shape

```text
edge/sdk/core
├── capability-audio
├── capability-capture
├── capability-storage
├── capability-power
├── capability-feedback
├── capability-update
└── transport-ble
          ▲
          │ implements
edge/platforms/android ─────────────────────────────┐
edge/platforms/linux                                │
                                                   │
devices/omi-cv1/edge-driver ──implements SDK────────┤
                                                   v
                                      edge/runtime ports + coordinators
                                                   ▲
edge/adapters/cloud/* ──implements cloud ports──────┤
                                                   │
edge/shell/android or edge/shell/linux ──composes───┘
```

The names under `edge/sdk` describe independently publishable modules, not directories that must be
created immediately. M1 may begin with fewer Gradle modules if import checks preserve the same graph.

Forbidden dependencies:

- `edge/sdk` imports no runtime, device, Android, database, network client, or Astrale package.
- `edge/runtime` imports no concrete device, Android, BLE implementation, UI, database driver, cloud
  provider, or Astrale client.
- a device driver imports the SDK and its own protocols, never the shell or cloud adapters.
- a platform adapter imports Android/Linux SDKs and implements edge ports, never Omi business behavior.
- the shell may import concrete modules only in its composition root.

## Open-world capability model

A device is a negotiated set of versioned capabilities, not an implementation of a growing brand-based
`Device` superclass.

Every capability has:

- a stable namespaced key, such as `gumi.audio-input`;
- a semantic major version and additive minor version;
- a descriptor containing limits, formats, feature flags, and required transport conditions;
- a typed handle implementing that capability's operations/events; and
- a compatibility rule for unknown optional and unknown required versions.

The SDK registry resolves a typed handle by capability key. New capability packages may publish new keys
without editing a central device union. The runtime orchestrates capabilities it understands and exposes
unknown descriptors for diagnostics; it never guesses their semantics.

M1 capability surfaces:

| Capability | Operations | Events/projections |
| --- | --- | --- |
| `AudioInput.v1` | negotiate format, open/close bounded frame stream | frame, format change, discontinuity, overflow |
| `CaptureControl.v1` | enter/leave Recording or VoiceTurn with command ID | acquired state, refusal, fault, transition evidence |
| `ButtonGesture.v1` | configure supported mapping where allowed | tap/double-tap/press/release/hold with device time |
| `LocalMediaStore.v1` | status, read sequence range, advance durable cursor | capacity, read/write sequence, dropped count, invalid clock |
| `VisualIndicator.v1` | non-privacy feedback requests only | actual indicator state/fault |
| `Haptic.v1` | bounded named patterns | completion/refusal/fault |
| `PowerStatus.v1` | read status, subscribe | battery, charging, thermal/power warning |
| `FirmwareUpdate.v1` | inspect slots, prepare/upload/confirm/reboot/validate | image/slot state and update progress |

Privacy indication is firmware-owned and follows actual microphone acquisition. A remote
`VisualIndicator` operation cannot turn that indication off or falsely claim capture is inactive.

## Driver lifecycle

The conceptual driver SPI has four stages:

1. **Discover:** a host transport emits an `EndpointCandidate` with ephemeral transport evidence.
2. **Match:** registered driver providers return `NoMatch`, `Possible`, or `Exact` plus evidence. Matching
   has no side effects.
3. **Open:** the selected provider receives a transport session and produces a `DeviceSession` after
   protocol and security negotiation.
4. **Close:** cancellation releases notifications, GATT state, buffers, and secrets deterministically.

A session exposes:

- provisional transport identity and provisioned stable device identity separately;
- the negotiated capability set;
- typed capability handles;
- a bounded flow of lifecycle, diagnostic, and capability events; and
- explicit close/cancel semantics.

Bluetooth address, advertisement name, or a cloud node ID is not automatically the stable device
identity. Provisioning binds transport evidence to a project identity and owner; address rotation does
not create a new semantic device.

Driver registration is static at the Android/Linux composition root for M1. Dynamic code loading is not
required to prove modularity and would enlarge the supply-chain/security boundary prematurely.

## Transport ports

Device transports are SDK extensions rather than methods on a universal device interface.

The BLE central port provides:

- filtered discovery and companion-associated endpoint resolution;
- one cancellable connection session;
- negotiated security, MTU, PHY, and connection parameters;
- service discovery and characteristic metadata;
- serialized read, write, descriptor, and notification operations with timeouts;
- typed connection/error events without leaking `BluetoothGatt` objects; and
- explicit disconnect and close.

The port deals in UUIDs, immutable bytes, deadlines, and transport errors. Android objects remain inside
`edge/platforms/android`; BlueZ/D-Bus objects will remain inside `edge/platforms/linux`.

Only one owner may issue ATT operations for a BLE session. The platform adapter owns the GATT operation
queue; device drivers must not build a competing queue. Firmware update obtains an exclusive device
lease, closes the capture session cleanly, and uses the updater transport until post-reboot validation
returns ownership.

## Runtime ownership

`edge/runtime` is organized around coordinators and ports, not vendor managers:

| Runtime owner | Invariant |
| --- | --- |
| Device supervisor | one serialized lifecycle owner per provisioned device |
| Capability registry | descriptor/handle versions agree and required capabilities are present |
| Capture coordinator | requested and acquired states are distinct; transitions are idempotent |
| Transfer coordinator | device cursor never advances beyond the last durable local copy |
| Spool coordinator | chunks, sequence checkpoints, manifests, and retention change atomically |
| Cloud sync coordinator | retries preserve stable identities and exact durable acknowledgements |
| VoiceTurn coordinator | realtime priority is bounded and the turn remains locally traceable |
| Update coordinator | capture/update exclusion, power gates, image policy, reboot and validation |
| Policy coordinator | deterministic local privacy, retention, connectivity, and actuator rules |
| Diagnostics coordinator | correlated state/effect evidence without media or secrets in logs |

Each device supervisor processes commands and lifecycle events through one logical mailbox. Platform
callbacks may arrive concurrently, but they become ordered inputs before changing runtime state. Slow
media I/O runs outside the mailbox and returns a correlated completion; it cannot block unrelated link
or safety events.

Every command carries a stable command ID, caller context, deadline, and cancellation policy. Retrying a
command either returns the prior terminal result or reconciles actual device state; it must not blindly
repeat a destructive effect.

## Durable media protocol

The local durability boundary is explicit:

```text
device bytes
  -> validate framing/sequence
  -> write encrypted chunk
  -> flush according to durability policy
  -> transactionally record chunk digest + sequence checkpoint
  -> only then acknowledge/advance the device durable cursor
  -> upload with stable chunk identity
  -> record exact cloud durable acknowledgement
  -> prune local bytes only when retention policy permits
```

Rules:

- When the device is the last durable copy, an edge memory buffer is not an acknowledgement boundary.
- Disk full, key unavailable, corrupt metadata, or failed flush stops device advancement and surfaces a
  fault.
- Live notifications and offline-ring reads converge on one capture/session sequence model. If stock
  firmware lacks an identity, the compatibility adapter records that uncertainty rather than pretending
  exact equivalence.
- Duplicate bytes with the same sequence and digest are harmless; the same identity with another digest
  is a corruption/security fault.
- Discontinuities, device overwrites, and clock uncertainty are first-class records.
- Media payloads are encrypted chunk files/objects; the metadata database stores indexes, digests,
  checkpoints, policy, and state—not giant audio blobs.
- VoiceTurn may receive scheduling/upload priority but cannot bypass local durability and provenance.

## Platform ports

The runtime publishes ports for effects that vary by host:

- monotonic and wall clocks;
- cryptographically secure random/ID generation;
- secure key creation, wrapping, lookup, rotation, and deletion;
- metadata transactions and encrypted blob storage;
- network reachability and metering;
- foreground/background execution leases;
- device discovery transports;
- power/thermal/storage pressure; and
- structured telemetry export.

Android implements these with platform facilities such as Keystore, companion association, foreground
services, and app-private storage. Linux implements the same semantics through its own adapters. A port
must describe the guarantee Gumi needs, not merely rename an Android class.

## Cloud ports

The runtime depends on narrow application-facing ports:

| Port | Publisher/implementation |
| --- | --- |
| Edge identity/session | Astrale-backed cloud app API / generated edge adapter |
| Media ingest | `cloud/apps/media-ingest/api` / `edge/adapters/cloud/media-ingest` |
| Recording semantics | owning Astrale-backed app / generated edge adapter |
| Realtime voice | `cloud/apps/realtime-gateway/api` / edge realtime adapter |
| Telemetry | selected observability app/standard / edge telemetry adapter |

Provider OpenAPI/realtime definitions remain canonical under the publishing cloud app. Edge adapters map
generated clients and wire errors into runtime port types. The runtime never imports a cloud SDK or
assumes that an accepted HTTP request is a durable media acknowledgement.

## Shell application boundary

`edge/shell/application` exposes a host-neutral control surface. Initial use cases include:

- discover/associate/provision/revoke device;
- inspect device, capability, connection, power, storage, and firmware state;
- start/stop recording and start/cancel VoiceTurn;
- inspect backlog, sync, recording, transcript, conversation, and action projections;
- request firmware update/recovery through a guarded workflow;
- change explicit local policy; and
- export redacted diagnostics.

The shell consumes immutable projections and terminal/progress results. It does not receive raw GATT
objects, parse Omi packets, own retry counters, write spool checkpoints, mint upload credentials, or
mutate Astrale state directly.

On Android the UI may call this surface in-process while the connected-device service keeps the runtime
host alive. On a Raspberry Pi the same surface may be exposed over a local authenticated RPC/Unix socket.
Transporting the shell API does not change its semantics.

## Omi CV1 driver boundary

`devices/omi-cv1/edge-driver` owns:

- advertisement/service matching and stock/Gumi protocol-version detection;
- UUIDs, feature bitmap, codecs, GATT layout, ring messages, byte order, and storage edge cases;
- mapping Omi button, battery, feedback, audio, capture, storage, and slot state into SDK capabilities;
- device-side challenge/session protocol and Omi-specific provisioning evidence;
- Omi firmware compatibility/version policy; and
- Omi protocol simulator behavior and golden-fixture conformance.

It must not own:

- Android process/service policy;
- general GATT queuing or reconnect implementation;
- user retention and capture policy;
- edge durable acknowledgement or cloud sessions;
- transcript/conversation/action semantics; or
- generic UI state.

The updater is split deliberately: Nordic MCU Manager mechanics live behind the Android updater
transport, Omi image/slot/recovery policy lives in the Omi driver, and update exclusivity/durable state
lives in the runtime coordinator.

## Errors and observability

Ports return typed failures with at least category, stable code, retryability, operation/correlation ID,
and redacted evidence. Expected transport loss is data, not an unstructured exception that escapes into
the UI.

Required categories include permission, unavailable, timeout, disconnected, incompatible, unauthorized,
replayed, corrupt, resource-exhausted, cancelled, rejected-policy, and internal fault.

Logs and traces may include project-scoped opaque identifiers, state transitions, sequence ranges,
sizes, digests, latency, and error codes. They never contain raw audio, BLE/application keys, credentials,
stable public Bluetooth addresses, or full provider payloads.

## Conformance strategy

The first workspace supplies:

- SDK contract tests reusable by every device driver and platform adapter;
- fake clock, random, secure-store, metadata, blob, BLE, and cloud ports;
- deterministic scheduler/process-restart fixtures for runtime state machines;
- Omi golden GATT/ring fixtures and corrupted/truncated variants;
- an Omi simulator capable of disconnect, reorder, duplicate, overwrite, disk-full, and reboot cases;
- Android instrumentation tests for service/process/permission/association behavior; and
- a Linux/JVM executable running the same simulated capture and restart scenario.

Hardware-in-loop tests remain under `devices/omi-cv1/tests/hardware-in-loop`. Simulator and contract
tests make failures reproducible; they do not close physical gates.

## First executable witness

Before UI design or cloud provider integration, the first vertical must:

1. register a simulated Omi provider at the shell composition root;
2. discover and open it through the BLE transport port;
3. negotiate AudioInput and LocalMediaStore capabilities;
4. receive live and ring fixture bytes;
5. commit encrypted test chunks and sequence metadata;
6. terminate and recreate the runtime process;
7. resume from the exact durable checkpoint without duplication; and
8. expose the resulting device/capture/backlog projection through the shell application surface.

The same scenario runs on Android and Linux/JVM. The real Omi replaces the simulator only after the
read-only physical gate permits it.
