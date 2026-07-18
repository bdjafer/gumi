# Omi CV1 GATT profile: owned stock v3.0.12

[profile.json](profile.json) records the redacted service/characteristic tree observed on the owned
sealed pendant and correlates its version-specific fields with official tag `Omi_CV1_v3.0.12` at
commit `85159556eac753a088c5efd1b419a5a867508e27`.

The inventory was collected on 2026-07-18 by Gumi's Android read-only port. It connected without a
bond, discovered GATT, attempted seven explicitly allowlisted characteristics, successfully read six,
confirmed the Software Revision characteristic was absent, and disconnected. It did not write a
characteristic or descriptor, enable a notification, pair, change MTU/PHY, advance storage, or invoke
MCU Manager.

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

Primary source correlation:

- [`omi.conf`](https://github.com/BasedHardware/omi/blob/85159556eac753a088c5efd1b419a5a867508e27/omi/firmware/omi/omi.conf)
- [`storage.c`](https://github.com/BasedHardware/omi/blob/85159556eac753a088c5efd1b419a5a867508e27/omi/firmware/omi/src/lib/core/storage.c)
- [`transport.c`](https://github.com/BasedHardware/omi/blob/85159556eac753a088c5efd1b419a5a867508e27/omi/firmware/omi/src/lib/core/transport.c)
