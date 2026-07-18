# Omi CV1 owned-device inventory

Status: awaiting read-only collection from the sealed unit.

Collection procedure: [android-read-only-probe.md](android-read-only-probe.md). Published-release
comparison oracle: [stock-ota-inspection.md](stock-ota-inspection.md).

Do not place owner identity, account credentials, raw audio, stable Bluetooth addresses, or secrets in
this tracked document. Store sensitive raw exports under the ignored `local/` directory and record only
redacted evidence or cryptographic hashes here.

## Physical identity

| Field | Observation | Evidence/date |
| --- | --- | --- |
| Product/form factor | Retail Omi consumer pendant | Owner confirmation, 2026-07-18 |
| Opened or modified | No | Owner confirmation, 2026-07-18 |
| First edge-shell platform | Android | Owner confirmation, 2026-07-18 |
| Model number | Pending | |
| Hardware revision | Pending | |
| Firmware revision | Pending | |
| Manufacturer string | Pending | |
| Advertised local name | Pending | |

## Read-only BLE inventory

| Field | Observation | Evidence/date |
| --- | --- | --- |
| Advertisement service UUIDs | Pending | |
| Manufacturer/service data | Pending, redact stable IDs | |
| GATT export hash | Pending | |
| Pairing required to connect | Pending | |
| Encryption required for audio/control | Pending | |
| Negotiated MTU on Android | Pending; first qualification lane | |
| Negotiated PHY | Pending | |
| SMP/MCU Manager service visible | Pending | |
| Image-slot list readable | Pending | |
| Active image hashes | Pending | |

## Storage and time

| Field | Observation | Evidence/date |
| --- | --- | --- |
| Reported used/free bytes | Pending | |
| Ring read sequence | Pending | |
| Ring write sequence | Pending | |
| Dropped packet count | Pending | |
| Ring record size | Expected 444, bench pending | Source/release audit |
| RTC-valid state | Pending | |

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

## OTA readiness decision

The canary remains blocked until all of these are answered:

- [ ] exact firmware family is CV1 and compatible with the pinned source/tag;
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
