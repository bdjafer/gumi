# Omi CV1 owned-device inventory

Status: advertisement and read-only connected GATT inventory captured; MCU image state and behavioral
probes remain pending.

Collection procedure: [android-read-only-probe.md](android-read-only-probe.md). Published-release
comparison oracle: [stock-ota-inspection.md](stock-ota-inspection.md).

Do not place owner identity, account credentials, raw audio, stable Bluetooth addresses, or secrets in
this tracked document. Store sensitive raw exports under the ignored `local/` directory and record only
redacted evidence or cryptographic hashes here.

## Edge handset baseline

| Field | Observation | Evidence/date |
| --- | --- | --- |
| Phone | Motorola edge 60 fusion, ARM64 | Authorized ADB property read, 2026-07-18 |
| Android | Android 16, API 36 | Authorized ADB property read, 2026-07-18 |
| Security patch | 2026-05-01 | Authorized ADB property read, 2026-07-18 |
| Gumi diagnostic shell | Installed as `dev.gumi.shell`; version-aware read-only GATT build cold-started in 1,028 ms | ADB install/activity result, 2026-07-18 |
| Stable USB/build identifiers | Redacted from tracked evidence | Raw session only |
| Nearby Devices consent | `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` granted by the owner | Android package permission state, 2026-07-18 |

## Physical identity

| Field | Observation | Evidence/date |
| --- | --- | --- |
| Product/form factor | Retail Omi consumer pendant | Owner confirmation, 2026-07-18 |
| Opened or modified | No | Owner confirmation, 2026-07-18 |
| First edge-shell platform | Android | Owner confirmation, 2026-07-18 |
| Model number | `Omi CV 1` | Device Information read, 2026-07-18 |
| Hardware revision | `5.0` | Device Information read, 2026-07-18 |
| Firmware revision | `3.0.12` | Device Information read; matches official tag `Omi_CV1_v3.0.12`, 2026-07-18 |
| Manufacturer string | `Based Hardware` | Device Information read, 2026-07-18 |
| Software revision | Characteristic `0x2A28` absent, matching the v3.0.12 configuration | Live GATT tree and official source, 2026-07-18 |
| Advertised local name | `Omi` | Gumi read-only scan, 2026-07-18 |

## Read-only BLE inventory

| Field | Observation | Evidence/date |
| --- | --- | --- |
| Advertisement service UUIDs | Device Information `0000180a-0000-1000-8000-00805f9b34fb`; Omi audio `19b10000-e8f2-537e-4f6c-d104768a1214` | Live Gumi screen and redacted app log, 2026-07-18 |
| Connectable / RSSI | Connectable; RSSI varied from -98 to -77 dBm and materially affected first-connect reliability | Live Gumi screen and redacted app log, 2026-07-18 |
| Manufacturer/service data | No manufacturer data or service-data payload advertised | Redacted Gumi scan result, 2026-07-18 |
| GATT profile | 11 services, 21 characteristics; [redacted observed profile](../../protocols/gatt/v3.0.12/profile.json), SHA-256 `dc67af3167f06f5b777f41cfe0f541277a73a74da7e22be23e5f96130fe2e0d6` | Two repeatable read-only inspections, 2026-07-18 |
| Local evidence | Final redacted screenshot SHA-256 `73d19dc6d0a641fde26eafc1fb16fc74f5041067be3dc239ca41fd79b4c969f2`; raw files remain ignored | `local/gumi-gatt-final.png`, 2026-07-18 |
| Pairing required to connect | No pairing prompt; link remained `NOT_BONDED` through discovery and reads | Gumi/Nordic adapter observation, 2026-07-18 |
| Encryption required for audio/control | Pending | |
| Negotiated MTU on Android | 23 with no explicit MTU request | Gumi/Nordic adapter observation, 2026-07-18 |
| Negotiated PHY | LE 2M transmit / LE 2M receive | Android GATT PHY read, 2026-07-18 |
| SMP/MCU Manager service visible | Yes: service `8d53dc1d-1db7-4cd3-868b-8a527460aa84`, characteristic `da2e7828-fbce-4e01-ae9e-261174997c48` (`write without response`, `notify`) | Live GATT tree, 2026-07-18 |
| Image-slot list readable | Pending | |
| Active image hashes | Pending | |
| Connection behavior | First weak-signal attempt connected and selected 2M, then timed out during discovery; two close-range retries completed | Redacted Android/Gumi logs, 2026-07-18 |

## Storage and time

| Field | Observation | Evidence/date |
| --- | --- | --- |
| Storage status shape | v3.0.12 eight-byte payload: two little-endian file sizes, not v3.0.20 used/unread/free/RTC status | Live read plus official v3.0.12 `storage.c`, 2026-07-18 |
| Reported file sizes | File 1: `505,118,720` bytes; file 2: `0` bytes; identical across two reads | Gumi version-aware decoder, 2026-07-18 |
| Existing-media disposition | Quarantined as potentially sensitive and semantically unknown; no content read, transcription, upload, cursor advance, or clear is authorized | Gumi M1 privacy boundary, 2026-07-19 |
| Ring read sequence | Pending | |
| Ring write sequence | Pending | |
| Dropped packet count | Pending | |
| Ring record size | Expected 444, bench pending | Source/release audit |
| RTC-valid state | Not exposed by the v3.0.12 eight-byte storage payload | Live/source version comparison, 2026-07-18 |

## Behavioral probes

| Probe | Observation | Evidence/date |
| --- | --- | --- |
| Boot indication | Pending | |
| Single tap | Pending | |
| Double tap | Pending | |
| Three-second hold | Pending | |
| Audio notifications while connected | Pending | |
| Offline recording while disconnected | Pending | |
| Reconnect after phone lock | Pending | |
| Reconnect after app termination | Pending | |
| Pendant reboot persistence | Pending | |
| Charging telemetry/indication | Pending | |
| Battery read | `27%`, then `47%`; treat as volatile/unqualified until power testing explains the jump | Two allowlisted reads, 2026-07-18 |

## OTA readiness decision

The canary remains blocked until all of these are answered:

- [x] exact owned firmware is CV1 v3.0.12 and correlates with official tag commit
  `85159556eac753a088c5efd1b419a5a867508e27`;
- [x] official v3.0.12 bundle, extracted files, MCUboot headers/TLVs, outer signatures, and nested
  network-core validation are verified and recorded as the installed-release comparison oracle;
- [ ] migration/recovery from installed v3.0.12 to the pinned v3.0.20 baseline is qualified;
- [ ] SMP service and current image state are read successfully;
- [x] official v3.0.20 recovery artifact URL, SHA-256, and extracted file digests are recorded;
- [x] published v3.0.20 headers, TLVs, absence of security counters, and signatures are inspected;
- [x] exact-source application payload is reproduced and the clean-build network NSIB key mismatch is
  recorded;
- [ ] installed image headers/hashes are consistent with the owned unit's reported release;
- [ ] update mode and image-number selection are confirmed with an Android client that can write image
  `0` without writing image `1`; and
- [ ] application-only equal-version stock recovery and post-update audio/storage/reconnection checks
  are ready.
