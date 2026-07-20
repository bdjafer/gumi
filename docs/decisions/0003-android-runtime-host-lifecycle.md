# Decision 0003: Android runtime host and connected-device lifecycle

Status: accepted implementation boundary. The portable `RuntimeHost` and an Android
`connectedDevice` service/notification scaffold are locally executable; Companion association,
durable restart/binding policy, real device/storage/cloud composition, and physical/OEM qualification
remain open. Diagnostic BLE actions remain foreground-only and separate from that service. Platform
guidance refreshed 2026-07-19.

## Decision

Run the operational Android edge runtime behind one application-owned runtime host. The host is
composed by `edge/shell/android`, uses Android companion-device presence and a `connectedDevice`
foreground service where the OS permits, and delegates all capture, durability, reconnect, and cloud
semantics to portable/runtime and platform ports.

“Always on” means the strongest honest Android behavior available to an installed, associated app:

- a user-mediated companion association for presence and permitted background starts;
- one visible `connectedDevice` foreground service while a long-lived BLE session or user-visible
  transfer needs process priority;
- bounded WorkManager jobs for deferrable reconciliation/upload work, not as the live BLE owner;
- durable device/edge state that survives process loss; and
- an explicit degraded/unknown projection whenever Android denies, stops, or kills execution.

It does not mean an unkillable process. A user force-stop, permission/association removal, OEM policy,
or platform restriction may prevent reconnection until the user reopens Gumi. The runtime must preserve
bytes and report that condition rather than implying continuity.

Current Android guidance supports either `CompanionDeviceService` presence or a foreground service with
the `connectedDevice` type for long-lived BLE notifications. Android 12+ restricts background foreground-
service starts, with companion-device permissions among the documented exemptions. Android 14+ requires
the specific service type and permission. Android 16 also counts long-running WorkManager workers against
job quota, so WorkManager is not our indefinite connection mechanism.

Primary references:

- [Android BLE background communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [`connectedDevice` foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
- [Long-running WorkManager workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

## Ownership and placement

| Owner | Responsibility | Must not own |
| --- | --- | --- |
| Android shell composition | service/receiver declarations, notification channels/actions, UI bind/unbind, association workflow, concrete dependency graph | capture reducer logic, GATT protocol parsing, spool checkpoints |
| Android platform adapters | companion/presence APIs, foreground execution lease, Bluetooth/permission/power observations, Android durable stores | Omi policy, user-facing semantic truth, cloud DTOs |
| Edge runtime | one device supervisor, session generation, reconnect policy, acquired truth, mux/spool/sync recovery | `Service`, `Context`, notification, WorkManager, companion API types |
| Omi driver | match/open and typed Omi capabilities | Android process policy or service restart |
| Shell application | immutable projections and commands | direct BLE/service/database mutation |

The Android `Service` is a composition root, not a new correctness layer. The activity binds to the
same host and observes projections. It never opens a competing GATT session. Diagnostic probes remain
separate, foreground-only, owner-triggered tools and cannot run while the operational host owns the
device lease.

## Identity and association

Companion association is useful OS policy state, not Gumi device authentication. A BLE address, name,
association record, and physical proximity remain provisional transport evidence. The exact Android
29–37 association/presence branches, chooser filter, and release gate are governed by
[Decision 0004](0004-android-companion-association.md).

Provisioning must separately bind:

```text
Android installation / edge-host identity
  + provisioned Gumi device identity
  + current transport evidence
  + owner / tenant authority
  + device challenge/session proof
```

Association removal revokes Android background privileges and triggers a Gumi reconciliation flow; it
does not silently delete historical recordings or redefine graph ownership. Address rotation updates
transport evidence without minting a new semantic Device.

## Host state and leases

The portable host projection exposes orthogonal facts rather than one `running` Boolean; Android
adapters must map platform observations without collapsing those axes:

```text
Association:  Unknown | Missing | Associated
Presence:     Unknown | Absent | Present
Permission:   Unknown | Denied | Granted
Execution:    Stopped | StartRequested | Foreground | StartDenied | StopRequested | OutcomeUnknown
Transport:    Disconnected | Connecting | Ready | Degraded
Recovery:     Clean | Rehydrating | ReconciliationRequired | Faulted
RestartPolicy: AutomaticAllowed | UserStopped
```

One process-wide owner serializes these leases:

- `DeviceTransportLease(deviceId, connectionGeneration)` prevents diagnostic/update/capture owners from
  opening competing BLE sessions.
- `ForegroundExecutionLease(reason, deadline?)` maps a runtime need to Android service/notification
  state without making service presence proof of device connectivity.
- `UpdateLease` excludes capture and ordinary transport ownership through post-reboot validation.
- `SyncLease` allows bounded network work and yields to storage/power/metering policy.

### Local implementation status

[`RuntimeHost.kt`](../../edge/runtime/src/commonMain/kotlin/dev/gumi/edge/runtime/host/RuntimeHost.kt)
now owns one bounded serialized command/effect mailbox and the host-neutral model/ports beside it. Its
deterministic common tests pass on JVM and Android host targets. They prove foreground acquisition
precedes recovery, prerequisite refusal happens before a foreground effect, duplicate command identities
replay one physical effect, close atomically rejects later admission while settling the prior waiter,
explicit user stop suppresses automatic restart until another explicit start, a stop racing foreground
acquisition waits for cancellation settlement and releases once, stale completions are conservatively
released, and recovery/cleanup uncertainty never becomes a false clean state.

This portable core calls prerequisite, foreground-execution, and recovery ports and imports no Android
type. The Android shell now supplies a process owner, `Service`, notification, exact foreground endpoint
bridge, and a locally tested operational graph. That graph gives one `RuntimeHost`/foreground bridge a
`DeviceId` registry, passes the same process-global storage owner into every runtime factory, routes
device commands without global effect serialization, and closes registry nodes before the spool and
forced host teardown. Companion presence, a durable host store/restart policy, WorkManager, provisioned
real-device factories, and the named real device/update/sync lease adapters remain absent. The combined
local code is therefore lifecycle-candidate evidence, not proof that capture survives process death,
reboot, lock state, OEM policy, or a real BLE reconnect.

`RuntimeHost.close()` deliberately models forced process-owner teardown: it closes admission atomically,
resists caller cancellation while settling the consumer/effect jobs, and preserves `OutcomeUnknown` if
foreground execution may remain. It does not call recovery cleanup or foreground release because those
adapters may already be unavailable. A graceful owner must first issue
`Stop(origin = OWNER_SHUTDOWN)` while its adapters are usable, await that result, and only then close the
host.

Separately, today's foreground diagnostics use one process-scoped lease across GATT, driver,
firmware-image, and audio effects, hold it through cancellation cleanup, and revoke prior card/review/
firmware-derived audio authority on a new scan. That protects the next owned-phone diagnostic run only. It is not
the durable operational `DeviceTransportLease`, does not survive process death, and cannot substitute
for the Android host composition above. BLE discovery itself is not leased: the UI gate-guards every
fresh scan start, and a connection action stops scanning before it attempts to acquire the shared lease.

The landed Android service scaffold is unexported and non-sticky. After admission it posts a redacted
provisional foreground notification synchronously, before asynchronous portable work; the matching
portable operation must claim that exact lease before recovery runs. A definitive bootstrap denial is
rejected synchronously. If notification preparation or exact-endpoint refresh fails after entry, the
process owner issues a serialized `PREREQUISITE_LOST` stop and keeps capture truth unknown. No automatic
launch source is declared.

Commands use process-independent UUID identities. Outcome-unknown launch retains the exact prepared
command for retry. Stops bypass start-capacity pressure and coalesce behind one cleanup barrier while
preserving each command identity, so a later explicit user stop still establishes `USER_STOPPED`.
The service calls `stopSelfResult` only after portable cleanup and exact foreground release converge;
failed or unknown release keeps the endpoint attached for reconciliation.

## No handset microphone permission

Omi audio arrives as BLE characteristic notifications. Gumi does not use the Android handset
microphone for this path and must not request `RECORD_AUDIO` or declare the `microphone` foreground-
service type merely because bytes contain remote-device audio. This avoids a false platform/privacy
claim and the while-in-use restrictions attached to the phone microphone. If a future feature genuinely
uses the handset microphone, it becomes a separate disclosed capability and lifecycle review.

## Notification and user stop

The foreground notification is part of the control plane and must be truthful and actionable:

- show the managed device and separate connection/capture/backlog facts;
- never render “microphone off” from service presence, cloud state, or a stale device observation;
- expose **Open Gumi** and a local **Request stop** action when capture may be active;
- keep **Request stop** pending until a fresh device release proves the microphone off;
- expose permission, association, execution-denied, storage, and recovery faults explicitly; and
- never include transcript text, raw media, credentials, BLE addresses, or sensitive device labels.

The local candidate currently renders only redacted connection, capture-verification, and backlog
categories, exposes **Open Gumi** and **Request stop**, and requests immediate display on Android 12+.
It does not yet have a provisioned human-safe device label or real capture/backlog authority. Service
presence never fills those unknowns with positive truth.

If the user stops Gumi through Android's active-app/foreground-service UI or force-stops the package,
the process must not attempt hidden restart loops. Device-local recording may continue if it was already
active and durable capacity remains; the next permitted launch reconciles it. Custom firmware boot and
watchdog recovery remain Idle/microphone-off, so a phone reboot never implicitly starts a capture.

The current user-stop latch is process-local. Durable `USER_STOPPED` restoration and
`ApplicationExitInfo.REASON_USER_REQUESTED` reconciliation are hard gates before adding a companion
presence receiver, worker, alarm, boot receiver, or any other automatic start source.

## Reconnect and recovery algorithm

1. Load durable host/spool metadata and start every capture projection as unverified.
2. Resolve associated presence and current permissions without opening duplicate transports.
3. Acquire the process-wide device lease and open through the registered driver.
4. Negotiate capabilities and a new connection-session generation.
5. Observe actual capture/storage/cursor state before rendering positive truth.
6. Reconcile pending commands, mux snapshot/source replay, local spool, and cloud status by stable IDs.
7. Advance a device cursor only through the portable durable permit.
8. Publish a fresh projection; release foreground execution when no long-lived device or transfer work
   remains.

Backoff is bounded, classified, and reset by relevant evidence. Permission denied, association removed,
authentication/revocation failure, incompatible firmware, and storage corruption do not enter blind
reconnect loops. Ordinary out-of-range disconnect uses jittered retry/presence observation and preserves
the last known capture as may-be-active.

## WorkManager boundary

Use WorkManager for finite, restartable work such as:

- reconcile cloud status after network returns;
- upload a bounded backlog segment under network/power constraints;
- clean orphaned payloads after durable reference comparison; and
- refresh non-urgent semantic projections.

Each worker invokes the same idempotent runtime use case, owns no BLE singleton, and checkpoints before
returning. A worker may request foreground execution only for a genuinely user-important bounded job.
Live notification ownership and indefinite reconnect stay with companion presence plus the directly
managed connected-device host.

## Acceptance matrix

The boundary is not qualified until the owned Motorola/Android build passes all rows with correlated,
redacted evidence:

| Scenario | Required outcome |
| --- | --- |
| Initial association and permission | user-mediated; wrong/multiple device cannot be silently selected |
| Activity foreground/background | one GATT owner; stream continues only under declared host lease |
| Screen lock/unlock | no duplicate stream, cursor, or semantic capture; projection freshness remains honest |
| OS process kill | device/ring and encrypted spool recover; no assumed Idle; no duplicate durable range |
| User swipes task | behavior matches declared service policy and remains visible |
| Android foreground-service stop / package force-stop | no restart loop; next user launch reconciles may-be-active device truth |
| Bluetooth off/on and out-of-range | typed fault/backoff, then one new connection generation and exact resume |
| Nearby permission revoked | immediate release/typed fault; no busy loop or stale verified-off claim |
| Companion association removed | background privilege ends; provisioning/ownership history is preserved |
| Phone reboot / package replacement | no capture auto-start; permitted recovery rehydrates exact durable checkpoints |
| Device reboot during recording | explicit discontinuity; custom firmware returns Idle and never silently resumes |
| Network offline/metered | local durability/backpressure policy wins; BLE truth is independent of cloud |
| Storage pressure/full/corrupt | source advancement stops; active capture reaches a safe durable stop or explicit fault |
| Update lease and expected reboot | ordinary host cannot reconnect concurrently; post-boot validation returns ownership |
| OEM battery modes over an overnight run | measured survival/recovery; any required user setting is disclosed, not assumed |

The suite must also prove that service/notification logs contain no media or credentials and that every
resource closes on cancellation, timeout, start denial, permission loss, and terminal transport failure.
Local JVM tests exercise stop pressure/coalescing, user stop after internal stop, foreground release
failure, prompt bootstrap denial, exact-endpoint refresh, notification-visibility loss, invalid delivery,
and redacted notification policy. The Android Intent/framework suite passes 3/3 on an API 36 ARM64
emulator, including UUID retry identity and malformed typed extras. Neither test class proves process
survival, a real foreground Omi session, user-stop restoration, or any Motorola/OEM row in this matrix.

## Implementation order

1. Keep the next owned-phone driver/image/audio probes foreground-only and separately leased.
2. **Done locally:** add portable `RuntimeHost` prerequisite/execution/recovery ports plus deterministic
   start/stop/recovery/cancellation tests on JVM and Android host targets.
3. **Done as a local scaffold:** add the unexported, non-sticky `connectedDevice` service,
   notification, Activity controls, application-process host owner, and graceful stop convergence.
   The generic operational process graph now also proves one foreground host plus one spool owner across
   a `DeviceId` runtime registry; the production `Application` intentionally does not instantiate it
   until a durable provisioned binding and endpoint resolver exist.
4. **Done as an uncomposed local candidate:** implement the encrypted Android spool ports and execute
   the original five API 36 emulator primitive cases. Two newer operational/cancellation cases are
   compile-only; run all seven on the owned handset and complete the crash matrix
   before any real stored-audio cursor acknowledgement.
5. Implement [Decision 0004](0004-android-companion-association.md), durable device binding, and the
   process-wide real Omi lease while keeping every operational start explicit.
6. Compose the process-owned runtime/event ingress and `DefaultShellApplication`; open encrypted storage
   to `Ready` before the first operational BLE session. The current Activity does not provide that graph.
7. Prove explicit start -> one foreground Omi link/power session -> capture unverified with zero audio
   subscription -> explicit clean stop. Stock `deviceId = null` and absent `CaptureControl` are hard
   blockers to recording, not values to synthesize in the shell.
8. Qualify upload independently with already-durable synthetic chunks and real scoped credentials before
   adding the live notification/mux/checkpoint owner.
9. Persist user-stop/exit policy before enabling Companion presence as an automatic start source.
10. Run the lifecycle matrix first against the Omi simulator, then the owned stock unit.
11. Only after trusted custom firmware exists, qualify autonomous explicit Recording/VoiceTurn behavior.

## Rejected shortcuts

- An Activity-owned BLE singleton presented as background support.
- A sticky service that assumes Android will always recreate it.
- WorkManager as an indefinite BLE connection or 20 ms packet-processing loop.
- Companion association treated as cryptographic device ownership.
- Starting a phone `microphone` service for remote BLE audio.
- Restarting after user stop/force-stop through alarm, broadcast, or push workarounds.
- Rendering a running notification as evidence that the pendant microphone is off or recording.
