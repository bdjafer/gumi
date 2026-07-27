# Portable shell application

`edge/shell/application` is the platform-neutral control-plane product boundary. Android and Linux
render it and supply adapters; neither host redefines capture, durability, privacy, or command truth.

The executable surface has three layers:

- `ShellApplication` publishes generation-safe per-device runtime projections and routes idempotent
  `ShellCommand` envelopes to the current runtime owner;
- `ShellDeviceOutputTruthPort` optionally publishes current-session physical-output evidence without
  leaking a device's LED, haptic, GATT, or firmware types; and
- `PortableControlPlane` combines those ports into deterministic fleet focus, one-active-capture
  workflow, attachment, fault, privacy-output, accessibility, and advisory action presentations.

The product projector is deliberately conservative:

- no output report means **unverified**, never “light off”;
- only fresh `DEVICE_REPORTED` evidence from the current physical connection session can confirm a
  visible output;
- any active plus uncertain microphone, or more than one confirmed active microphone, is a collision
  risk;
- a second capture start is hidden/refused while the fleet is not fully verified off;
- the first pre-publication start owns a product admission reservation, closing the race in which two
  devices could both observe the same all-off snapshot;
- a VoiceTurn may overlay the sole fresh base recording on the same device;
- physical-output failure or contradiction blocks acquisition but never removes a safety stop; and
- every enabled action still requires concrete runtime/device revalidation.

An admission reservation is not acquired-state evidence. It changes the fleet wording to “Starting
capture — awaiting device evidence,” keeps a local safety stop, admits an exact retry of the same
idempotent envelope, and blocks a different acquisition. Only an explicit refusal or rejection clears
it immediately. Acceptance, cancellation, failure, or outcome-unknown retains it until the runtime
projection leaves verified-off and later proves a verified stop. If the target disappears, the fleet
remains collision risk instead of silently freeing another start.

`DefaultPortableControlPlane` owns no process resources. Its injected `CoroutineScope` is host-owned;
cancelling that scope stops projection collection. Android may render the resulting immutable
`StateFlow`, but the Activity, Service, and notification remain adapters rather than application-core
owners.

`PortableControlPlaneActionAdapter` closes the reusable product callback boundary. A host supplies
collision-resistant command/correlation identities and an epoch clock; the adapter maps every
`ShellControlAction` to its semantic intent and preserves the exact envelope for outcome-unknown retry.
VoiceTurn, firmware update, and physical confirmation cannot dispatch without their matching typed
qualification. Android and a future headless or Raspberry Pi shell therefore share command semantics
without importing Compose, BLE, or device-specific firmware types.

Stock Omi firmware does not currently provide qualified physical-output telemetry, so its composition
must use `UnavailableShellDeviceOutputTruthPort`. A future custom-firmware adapter may map the reviewed
portable human-I/O meanings into this port after session binding and HIL qualification. It must not map
a requested LED pattern or a successful write into observed physical truth.

Run the common contract on both supported host compilers:

```sh
./gumiw :edge:shell:application:allTests
```

The governing product contract is
[`docs/specs/portable-control-plane-contract.md`](../../../docs/specs/portable-control-plane-contract.md).
