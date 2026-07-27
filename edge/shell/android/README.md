# Android edge shell

This module contains two deliberately separate compositions:

- foreground-only, owner-triggered Omi diagnostic probes used for hardware evidence; and
- the application-process operational host under `runtime/`.

Diagnostics never acquire or mutate the operational `RuntimeHost`. The operational service never
calls a diagnostic controller.

The bounded audio diagnostic accepts only an exact published stock v3.0.12 image or the exact
behavior-neutral `gumi-canary-0001` application hash, with the network image either exact stock or
explicitly unobserved. Every other, pending, or endpoint-inconsistent state remains locked out.

The current `MainActivity` is therefore an evidence console, not the M1 product control plane. It must
not paint Recording, microphone-off, or device-output colors from discovery, service presence, or a
requested command. The host-neutral `edge/shell/application` presentation is the future UI input: it
pairs every capture state with text, a semantic icon, an accessible announcement, and advisory action
availability. Android may render that projection only after the production process owner publishes
fresh, generation-coherent device evidence. Omi-specific RGB timings and colors remain in the device
capsule and are never imported as generic shell truth.

The host-neutral `PortableControlPlane` façade now also supplies deterministic multi-device focus, the
one-active-capture product workflow, attachment/fault presentation, and an optional
`ShellDeviceOutputTruthPort`. Stock Omi composition must leave that output port unavailable: the app may
show the person's observation checklist, but it cannot convert a requested pattern, successful GATT
write, or visual bench observation into current-session machine truth. A future Android/custom-firmware
adapter may publish that evidence only after protocol and HIL qualification.

## Product control-plane surface

`product/PortableControlPlaneSurface.kt` is the reusable native Compose renderer for exactly one
immutable `PortableControlPlanePresentation`. It is intentionally not mounted by `MainActivity` yet.
It accepts only two effect callbacks: select one stable `DeviceId`, or request one already-projected
`ShellControlAction` for that device. It does not collect runtime state, construct command identities or
admission leases, inspect BLE, parse Omi packets, or translate device light colors.

The surface keeps fleet and per-device facts visibly separate:

- the six-state fleet microphone workflow, including admission reservation and collision risk;
- deterministic multi-device focus without hiding active or uncertain peers;
- capture truth, requested capture, attachment, physical-output evidence, and faults;
- local storage, transfer/backlog, power, maintenance, and software-update axes; and
- Recording, VoiceTurn, safety-stop, pairing, update, confirmation, and shutdown action availability.

Every disabled control renders the stable portable reason code plus explanatory text. Safety states use
the projection's polite/assertive live-region priority, selected devices use tab semantics, headings and
controls remain native accessibility nodes, and controls have at least a 48 dp target. Color is always
secondary to a text label and a portable semantic glyph. The UI is static under reduced-motion settings;
there is no state-signifying animation.

Deterministic debug previews cover all microphones verified off, a Recording continuing while cloud is
offline with a local backlog, active-plus-uncertain collision risk at 1.5x font scale, and no managed
devices. JVM tests prove that stale/unavailable storage and backlog values cannot be rendered as current.
Compose instrumentation verifies assertive collision semantics, exact stable-device selection, explicit
disabled reasons, and the one-active Recording/VoiceTurn controls. Fixtures exist only in the debug
source set and are never a production state source.

The concrete integration gate remains deliberately open. A production composition must first publish a
real `PortableControlPlane.presentation` from the process-owned runtime graph, with durable binding,
endpoint/runtime factories, a freshness scheduler, and generation-coherent capture/storage/link facts.
The Activity (or a separate product Activity) can then collect that flow with lifecycle awareness and
route selection to `PortableControlPlane.select`. The host-neutral
`PortableControlPlaneActionAdapter` now builds fully identified intent-specific envelopes, retains exact
retries, and refuses VoiceTurn, update, or physical confirmation until the matching typed qualification
is supplied. Android still has to provide UUID identities and the reviewed qualification workflows.
Until the real graph exists, the surface stays unmounted and the diagnostic evidence console remains the
launcher UI; debug fixtures must never bridge that gap.

The discovery screen also contains a stock Omi address-stability diagnostic. The owner may capture
one baseline only when exactly one current Omi candidate is visible. The baseline is held only by the
current Activity/controller; every later scan generation can expose only `SAME`, `CHANGED`, or
`INCONCLUSIVE`. Android's raw BLE address and Gumi's random endpoint reference remain inside the
Android adapter and are never rendered, logged, or persisted. Zero, multiple, stale, or unresolvable
candidates are `INCONCLUSIVE`. This is release-planning transport evidence only—not identity,
ownership, bonding, association, provisioning, or capture authority—and it performs no connection,
write, pairing, firmware, audio, or operational-runtime action.

## Operational host boundary

`GumiRuntimeApplication` owns one process-scoped portable `RuntimeHost`. The visible Activity exposes
explicit **Start runtime**, **Request stop**, and exact-identity retry controls plus the current host,
transport, recovery, restart-policy, and redacted failure projections. Start requests the complete BLE
and notification permission set before it launches. There is deliberately no automatic launch source.

`GumiRuntimeService` is an unexported, non-sticky `connectedDevice` foreground service and accepts
only explicit typed intents. After policy/capacity admission, a start delivery synchronously posts a
redacted provisional foreground notification before any asynchronous portable work. The matching
portable operation must claim that exact
provisional lease before recovery runs. A definitive platform denial is rejected synchronously so an
unpromoted service is not left against Android's foreground deadline. Commands use process-independent
UUID identities, and an outcome-unknown launcher result retains the exact prepared command for retry.

Stops bypass ordinary start-capacity pressure and coalesce behind one cleanup barrier. Every stop keeps
its command identity, so a later explicit user stop still establishes `USER_STOPPED` even if an internal
prerequisite-loss stop arrived first. The Android service calls `stopSelfResult` only after the portable
host is stopped, recovery is clean, and the exact foreground endpoint proves release. A failed or
outcome-unknown release keeps the service attached for explicit reconciliation instead of destroying
the endpoint underneath cleanup.

The notification exposes **Open Gumi** by tapping its body and **Request stop** as an action. Its text
contains only connection, capture-verification, and backlog-availability categories. It never includes
media, transcripts, credentials, BLE addresses, or a user/device label. Service presence is never
rendered as proof of recording state. Android 12+ requests immediate foreground-notification display.
If notification preparation or exact-endpoint refresh fails after entry, the owner issues a serialized
`PREREQUISITE_LOST` stop before service settlement.

Application-owner shutdown rejects new deliveries, settles an `OWNER_SHUTDOWN` stop while Android
adapters are still live, waits for already accepted deliveries, and only then calls forced
`RuntimeHost.close()`. Unexpected service destruction detaches the platform endpoint first, so a held
foreground lease becomes outcome-unknown instead of falsely released.

The manifest intentionally declares only the general and `connectedDevice` foreground-service
permissions. It does not request handset `RECORD_AUDIO` or declare the `microphone` service type;
Omi audio is remote BLE data. Cloud backup and device-to-device transfer are disabled for every app
storage domain until an encrypted, identity-aware migration policy exists.

Current Android platform references:

- <https://developer.android.com/develop/background-work/services/fgs/launch>
- <https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device>
- <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- <https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping>

## Deliberately unavailable offline

This service is a safe composition scaffold, not a qualified background Omi runtime. The production
factory currently reports association as unknown and recovery as unavailable. Therefore it cannot
open an operational transport or claim background capture continuity. Companion association/presence,
the durable encrypted recovery graph, a real device lease, process-exit reconciliation, ongoing
permission/channel-revocation qualification, and physical/OEM lifecycle qualification remain required.

Durable `USER_STOPPED` restoration and `ApplicationExitInfo.REASON_USER_REQUESTED` reconciliation are a
hard gate before adding any companion-presence receiver, worker, alarm, boot receiver, or other automatic
start source. A process-local stop latch is not authority to restart after Android Task Manager stop or
force-stop. No such source is declared by this build.

Local JVM tests cover stop pressure/coalescing, a user stop following an internal stop, failed foreground
release, synchronous bootstrap denial, exact-endpoint refresh, visibility-loss stop, invalid-delivery
recording, and redacted notification policy. Device-side Intent tests cover UUID retry identity and a
wrong-typed extra. On 2026-07-19, the original three instrumentation cases passed on a clean API 36 ARM64
emulator. Four additional product-surface Compose cases compile locally but have not yet run on an
Android target. That is Android-framework evidence, not a handset run or physical/OEM lifecycle proof.

Run the local evidence with:

```sh
./gumiw :edge:shell:android:check :edge:shell:android:assembleDebugAndroidTest

# With exactly one attached Android target:
./gumiw :edge:shell:android:connectedDebugAndroidTest --console=plain
```
