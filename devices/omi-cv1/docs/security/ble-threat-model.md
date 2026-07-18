# Omi CV1 stock-firmware BLE threat model

Status: source-confirmed exposure analysis; physical-unit confirmation pending.

## Verdict

The stock v3.0.20 application is a useful compatibility base, not an acceptable Gumi security boundary.
Its custom GATT attributes use ordinary `READ`/`WRITE` permissions rather than encrypted or authenticated
permissions, and application code does not request link security after connection. Enabling Bluetooth's
Security Manager in configuration is not the same as requiring a secure link.

The exact expected surface is in the
[source-declared GATT profile](../../protocols/gatt/v3.0.20/README.md). The Android probe must
confirm behavior on the owned unit before this is called a bench-proven vulnerability.

## Assets and nearby attacker

An attacker is any ordinary BLE central within radio range. They need no Omi account, cloud access, or
physical opening. The at-risk assets are:

- live microphone audio;
- offline conversation audio and its timestamps;
- ring read position and availability;
- microphone/indicator/time settings;
- device availability and the single BLE connection slot; and
- firmware execution integrity.

## Concrete stock attack paths

| Path | Source-level mechanism | Consequence |
| --- | --- | --- |
| Live eavesdropping | Subscribe to `19b10001-...` audio notifications; characteristic and CCC do not require encryption/authentication | Nearby client receives raw Opus packets while the microphone runs |
| Stored-audio exfiltration | Write ring info/read commands to `30295781-...`, then receive its data notifications | Nearby client can request unread ring records |
| Stored-audio destruction | Write ring advance or clear commands to the same unprotected control characteristic | Read cursor can be moved or ring state cleared, causing data loss |
| Timestamp corruption | Write a four-byte epoch to `19b10031-...` | Future recording evidence is assigned attacker-chosen time |
| Settings/actuator abuse | Write mic gain, LED dim ratio, or haptic enum | Capture quality/indication changes or nuisance vibration |
| Connection denial | Occupy the firmware's single allowed BLE connection | Legitimate Android edge shell cannot connect/sync |
| Malicious application firmware | Reach MCU Manager OTA and upload application image `0` signed by the publicly committed upstream private key | If the shipped management service and boot key match the inspected release, arbitrary compatible application behavior can become trusted by stock MCUboot |

The last path is the highest-impact inference and gets a dedicated physical test only after the
read-only inventory and canary lab are approved. We do not probe it by uploading an attacker image.

This path does not automatically extend to a custom network image. The network core has an inner
NSIB/B0n ECDSA trust boundary, and clean upstream builds generate a different debug key from the one in
the published network artifact. The application-core exposure remains enough for full microphone,
storage, GATT, and update-control compromise, but image `1` must be analyzed separately. See
[build-reproduction.md](../research/build-reproduction.md).

The upstream build guide's generic statement that MCU Manager uses encrypted BLE transport is not
accepted as evidence. Attribute permissions and connection-security calls are the relevant source
boundary, and the physical session is the final truth.

## M1 prototype transition

The owned prototype has no trusted display/keyboard and no known private factory credential. “Just
Works” pairing alone cannot establish strong man-in-the-middle resistance. The first functional Gumi
firmware therefore uses a deliberately narrow transition design:

1. Generate a non-exportable Android edge-shell key in Android Keystore.
2. Pin that public key, a Gumi update-verification public key, and a unique prototype identity into the
   per-device firmware build before the first functional OTA.
3. Require signed challenge-response plus an ephemeral session-key agreement before any command,
   audio subscription, ring operation, or updater action.
4. Encrypt/authenticate media and control messages at the application layer even when BLE link
   encryption is also active.
5. Remove or gate stock settings/storage/audio characteristics after the compatibility canary; do not
   expose parallel insecure and secure versions indefinitely.
6. Remove the generic MCU Manager entry point after an authenticated Gumi updater has proven it can
   write the same slots and validate a Gumi-owned inner signature.
7. Keep the stock compatibility signature only as an outer bootloader requirement and record it as a
   release exception.

Pinning one Android key is appropriate for the single owned prototype, not a production fleet design.
Production requires factory-provisioned unique device identity, revocable ownership transfer, and an
out-of-band or physically verified enrollment ceremony.

## Required authorization split

- **Observe public discovery:** minimal non-sensitive version/capability data only.
- **Pair/provision:** physical-presence gated and rate limited.
- **Capture control:** current owner/edge-shell authority.
- **Live/offline media:** separate scoped session key and explicit capture/session identity.
- **Destructive storage operations:** durable-copy proof plus stronger local authorization.
- **Firmware update:** signed release policy, battery/power gates, exclusive update state, audit record.
- **Emergency recovery:** intentionally entered physical mode; never ambient BLE availability.

Device authorization is independent from Astrale authorization. A valid BLE session cannot mint a cloud
ingest session unless Astrale also authorizes that device/edge identity; a valid Astrale token cannot
bypass local physical/device policy.

## Exit criteria for the stock exception

- Android probe confirms the exact current GATT and image state.
- Compatibility canary/recovery round trip passes.
- Authenticated application protocol has negative tests for replay, wrong key, stale session, and
  connection takeover.
- Raw audio/storage/settings are inaccessible before authentication.
- Generic OTA is inaccessible in Normal mode.
- Lost Android host and lost pendant revocation paths are exercised.
- Hardware-root ownership remains explicitly unresolved until the bootloader key is replaced.
