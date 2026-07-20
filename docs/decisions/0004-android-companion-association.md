# Decision 0004: Android Companion association and presence

Status: proposed Android platform boundary and implementation plan; no Companion API code or handset
evidence exists yet. Evidence reviewed for API 29–37 on 2026-07-19.

## Decision

Use Android Companion Device APIs as user-mediated platform evidence for selecting and observing a
nearby Omi. Association and presence may unlock Android lifecycle behavior where the OS permits; they
never establish Gumi device identity, ownership, authorization, capture truth, or consent.

The association flow always presents the Android chooser. Its BLE filter is deliberately exact and
narrow:

- name pattern `^Omi$`;
- advertised audio service `19b10000-e8f2-537e-4f6c-d104768a1214`;
- `setSingleDevice(false)`, so matching candidates remain a user-visible list; and
- no device-profile, self-managed, force-confirmation, remote-AI, or extra-permission request.

The filter only reduces transport candidates. The chosen association remains provisional until a
separate durable Gumi binding relates the Android installation/edge-host identity, provisioned device
identity, owner/tenant authority, and device challenge/session evidence. RSSI, display name, BLE address,
association ID, and presence cannot replace that binding.

## Versioned Android boundary

One fakeable platform port owns every SDK branch. Portable runtime, Omi driver, and shell application
types contain no `CompanionDeviceManager`, `AssociationInfo`, `DevicePresenceEvent`, MAC address, or
Android version check.

| Android API | Association locator and removal | Presence boundary |
| --- | --- | --- |
| 29–30 | Legacy `associate` result and MAC locator | No Companion presence observation; explicit foreground scan/connect only. |
| 31–32 | Legacy `associate` result and MAC locator | MAC-based presence observation while the association remains valid. |
| 33–35 | `AssociationInfo.id` is the primary record; enumerate with `getMyAssociations()` and remove with `disassociate(id)` | Presence observation still uses the associated MAC address. |
| 36.0 | Association ID remains primary | `ObservingDevicePresenceRequest` by association ID plus `DevicePresenceEvent`; aggregate BLE and Bluetooth presence channels before publishing one fact. |
| 36.1 | Same as 36.0 | Handle `EVENT_ASSOCIATION_REMOVED` only when `SDK_INT_FULL >= BAKLAVA_1`; do not call a minor-version API from a major-only check. |
| 37 | Same association-ID boundary | Keep the 36.1 event model. Request a remote-AI flag only if a later, separately reviewed product path actually needs it; do not use self-managed presence for Omi. |

Association enumeration and callbacks are generation-fenced. Every new enumerate/associate/remove or
presence-observation session creates a generation; results and callbacks from an older generation are
dropped. The cache stores only the platform locator needed by that API branch plus the durable Gumi
binding reference. It never turns a cached address, last-seen event, or association record into fresh
presence or semantic identity.

## Lifecycle and capture policy

Association and presence are prerequisites, not commands:

- a chooser result never connects automatically before the durable Gumi binding is verified;
- a presence event may request runtime reconciliation only after durable binding, permissions, and
  restart policy all authorize it;
- no association, presence, reconnect, process launch, boot, or package-replacement event starts
  Recording or VoiceTurn;
- explicit user capture intent and fresh device acquisition remain required; and
- association removal ends the related Android background privilege and makes transport evidence
  unavailable, while historical semantic ownership and retained media follow their own policies.

Before declaring any automatic presence start source, Android composition must durably restore the
binding and `USER_STOPPED` policy and reconcile `ApplicationExitInfo.REASON_USER_REQUESTED`. A process-
local latch is insufficient. Force-stop/package stopped state is respected; Gumi does not schedule a
receiver, worker, alarm, push, or boot workaround to escape it.

## Stock Omi release gates

The next owned-phone preflight determines only whether Android's process-local scan-address mapping for
the stock, unbonded Omi reports `SAME`, `CHANGED`, or `INCONCLUSIVE` across a stopped/fresh scan,
disappearance and return, one safe ordinary pendant power cycle, and Bluetooth off/on. Activity or
process replacement intentionally destroys the equality-only baseline, so this preflight cannot and
must not claim cross-process stability. It records only the verdict; the address itself remains private.

- A `CHANGED` preflight result selects explicit foreground scan/connect for the stock device and skips
  Companion presence implementation for M1.
- Results with no observed `CHANGED` permit implementation of the fakeable explicit chooser/association
  adapter, but do not authorize background presence or capture continuity.
- After that adapter exists, a separate handset gate must prove that Android can enumerate and resolve
  the user-approved association across app process recreation, Bluetooth off/on, reboot, and removal.
  If that association-level gate fails or resolves ambiguously, M1 falls back to explicit foreground
  scan/connect. A later custom authenticated identity protocol may reopen the design.

Neither a `SAME` scan-address verdict nor an Android association becomes semantic device identity,
ownership, or capture authority.

This is a release gate, not permission to pair, bond, reset, mutate firmware, or weaken the chooser
filter during the read-only session.

## Acceptance evidence

The adapter is not qualified until fake and handset tests cover:

- zero, one, and multiple exact-filter candidates with a real user chooser;
- wrong name, wrong service, stale callback, and association-removed negatives;
- every API branch above, including 36.0 versus 36.1 minor-version dispatch;
- generation changes during association, enumeration, removal, and presence callbacks;
- stable-address and rotating-address stock behavior without logging the address;
- permission revocation, Bluetooth off/on, out-of-range, process death, reboot, package replacement,
  Android Task Manager stop, and force-stop;
- one process-wide transport owner with no duplicate GATT connection; and
- proof that no platform event starts capture or mints semantic identity.

Until those tests pass, the Android service production factory must continue reporting association or
recovery unavailable and automatic start sources must remain absent.

## Official Android references

- [Companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)
- [`CompanionDeviceManager`](https://developer.android.com/reference/android/companion/CompanionDeviceManager)
- [`CompanionDeviceManager.Callback`](https://developer.android.com/reference/android/companion/CompanionDeviceManager.Callback)
- [`AssociationInfo`](https://developer.android.com/reference/android/companion/AssociationInfo)
- [`CompanionDeviceService`](https://developer.android.com/reference/android/companion/CompanionDeviceService)
- [`DevicePresenceEvent`](https://developer.android.com/reference/android/companion/DevicePresenceEvent)
- [Minor-version checks with `Build.VERSION_CODES_FULL`](https://developer.android.com/reference/android/os/Build.VERSION_CODES_FULL)
- [Background BLE guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Connected-device foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Handling user-stopped foreground services](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)
- [Android 15 package stopped-state changes](https://developer.android.com/about/versions/15/behavior-changes-all#changes-package-stopped-state)

## Rejected shortcuts

- Silent `setSingleDevice(true)` selection because only one candidate happened to be nearby.
- Treating an association ID, MAC address, name, service advertisement, bond, or presence event as a
  Gumi `Device` identity.
- Using self-managed association/presence to manufacture lifecycle privilege.
- Starting capture from association, presence, reconnect, boot, or process recreation.
- One unversioned API path that silently falls back when a method is unavailable.
- Advertising background continuity when the stock address rotates or the association cannot resolve.
