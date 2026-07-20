# Omi CV1 GATT profile: owned stock v3.0.12

[profile.json](profile.json) records the redacted service/characteristic tree observed on the owned
sealed pendant and correlates its version-specific fields with official tag `Omi_CV1_v3.0.12` at
commit `85159556eac753a088c5efd1b419a5a867508e27`.

The inventory was collected on 2026-07-18 by Gumi's Android read-only port. It connected without a
bond, discovered GATT, attempted seven explicitly allowlisted characteristics, successfully read six,
confirmed the Software Revision characteristic was absent, and disconnected. It did not write a
characteristic or descriptor, enable a notification, pair, change MTU/PHY, advance storage, or invoke
MCU Manager.

A separate owner-disclosed 2026-07-20 witness then enabled only the live-audio notification after exact
application-image qualification. It received 532 Opus frames over 9,999 ms at MTU 498 and LE 2M/2M
while Android still reported `NOT_BONDED`, with zero sequence gaps/discontinuities. The transactional
capture retained aggregate framing/cadence only—no audio bytes, content digest, stored-ring data,
Bluetooth address, or endpoint ID. This follow-on result does not retroactively widen the original
allowlisted inventory or exercise any stock control/storage/update write.

This profile is materially different from the source-declared [v3.0.20 profile](../v3.0.20/README.md):

- Device Information reports firmware `3.0.12` and hardware `5.0`; Software Revision `0x2A28` is
  absent, matching the tag's configuration.
- The settings service has LED-dimming and microphone-gain characteristics but no charging-status
  characteristic.
- The v3.0.20 time-sync service is absent.
- Storage status is eight bytes: two native little-endian `uint32_t` file sizes. The newer 16-byte
  used/unread/free/RTC contract does not apply.

Raw screenshots and the storage payload remain under ignored `local/`. The final redacted screenshot
has SHA-256 `73d19dc6d0a641fde26eafc1fb16fc74f5041067be3dc239ca41fd79b4c969f2`.

The tag source also defines the live-audio notification envelope; this part is source-correlated and
has not yet been observed on the owned unit. Each notification is a three-byte header followed by an
Opus fragment: a wrapping `uint16_t` little-endian notification sequence, then a `uint8_t` fragment
index. The counter increments per BLE fragment, is not reset on disconnect, and can skip after a
failed send; it is evidence of continuity, not a durable recording identity. With a sufficiently
large negotiated ATT MTU, the 160-byte encoder maximum fits in one
notification and the fragment index is zero. Gumi requests MTU 512, requires the actual negotiated MTU
to be at least 166, requires codec characteristic value `21`, strips the envelope, expands sequence
rollover, and rejects fragmentation rather than mislabeling a partial fragment as raw Opus. The
10-second owned-unit witness remains the qualification gate for those source assumptions.

The stock v3.0.12 source initially assigns its internal `current_mtu` to the maximum of the observed
value and configured local TX MTU 498 before exchange. That optimistic internal value is not negotiated
link evidence. Gumi relies on Android's MTU callback and fails closed below 166.

Primary source correlation:

- [`omi.conf`](https://github.com/BasedHardware/omi/blob/85159556eac753a088c5efd1b419a5a867508e27/omi/firmware/omi/omi.conf)
- [`storage.c`](https://github.com/BasedHardware/omi/blob/85159556eac753a088c5efd1b419a5a867508e27/omi/firmware/omi/src/lib/core/storage.c)
- [`transport.c`](https://github.com/BasedHardware/omi/blob/85159556eac753a088c5efd1b419a5a867508e27/omi/firmware/omi/src/lib/core/transport.c)
- [`config.h`](https://github.com/BasedHardware/omi/blob/85159556eac753a088c5efd1b419a5a867508e27/omi/firmware/omi/src/lib/core/config.h)
