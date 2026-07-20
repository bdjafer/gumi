# Omi CV1 owned-device inventory

Status: advertisement, connected GATT, operational-driver, MCU image-state, and bounded live-audio
metadata inventories captured. The installed application image exactly matches published v3.0.12;
stock MCU Manager did not expose the network-core image. Stock behavior, power, and storage semantics
remain only partially observed.

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
| Encryption required for audio/control | The bounded stock audio stream succeeded while Android reported `NOT_BONDED`; link encryption was not independently measured and no control write was exercised | Scoped audio witness, 2026-07-20 |
| Operational driver | `gumi.device.omi-cv1`, protocol `omi-stock/3.0.12`; declared audio, button, haptic, and power capabilities | Scoped `GumiDriverProbe` witness, attempt 2, 2026-07-20 |
| Negotiated MTU on Android | 23 with no explicit request for driver/image-state baselines; 498 after the audio path requested 512 | Gumi/Nordic adapter observations, 2026-07-20 |
| Negotiated PHY | LE 2M transmit / LE 2M receive | Android GATT PHY read, reconfirmed 2026-07-20 |
| SMP/MCU Manager service visible | Yes: service `8d53dc1d-1db7-4cd3-868b-8a527460aa84`, characteristic `da2e7828-fbce-4e01-ae9e-261174997c48` (`write without response`, `notify`) | Live GATT tree, 2026-07-18 |
| Image-slot list readable | Yes; response exposed one active application row and no network-core row | Scoped `GumiFirmwareProbe` witness, attempt 1, 2026-07-20 |
| Application image `0` | Slot `0`, wire version `0.0.0`, bootable/confirmed/active, no pending flag; hash exactly `0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36` | Matches published v3.0.12 MCUboot hash; scoped witness, 2026-07-20 |
| Network image `1` | Not exposed by the stock image-state response; identity and secondary-slot state remain unobserved | Scoped witness plus pinned NCS v2.9.0 source audit, 2026-07-20 |
| Image-state interpretation | `APPLICATION_MATCH_NETWORK_UNOBSERVED`; Zephyr emits `0.0.0` when the MCUboot build number is zero, equivalent to the artifact header `0.0.0+0` | Pinned Zephyr `v3.7.99-ncs2` `img_mgmt_ver_str`, 2026-07-20 |
| Connection behavior | First weak-signal attempt connected and selected 2M, then timed out during discovery; two close-range retries completed | Redacted Android/Gumi logs, 2026-07-18 |

## Bounded stock live-audio metadata

The owner-disclosed quiet-room probe activated the stock live microphone for ten seconds only after the
application-image gate passed. It retained aggregate packet metadata, not audio bytes or a content
digest.

| Field | Observation | Evidence/date |
| --- | --- | --- |
| Result | `AUDIO_METADATA_QUALIFIED`; stream, device session, and BLE transport all closed before publication | Scoped `GumiAudioProbe` witness, attempt 2, 2026-07-20 |
| Format | Opus, 16 kHz, mono, raw Opus packet; one 20 ms frame / 960 decoded samples at 48 kHz; observed configuration `23` | Redacted metadata log, 2026-07-20 |
| Coverage | 532 frames, 49,134 aggregate payload bytes, payload bounds 71–120 bytes, receive span 9,999 ms | Redacted metadata log, 2026-07-20 |
| Continuity | Sequence 0–531, zero gaps, zero discontinuities; maximum receive interarrival 172 ms | Redacted metadata log, 2026-07-20 |
| BLE session | ATT MTU 498, LE 2M transmit/receive, `NOT_BONDED` | Redacted metadata log, 2026-07-20 |
| Data minimization | No audio payload, content digest, content log, full logcat, Bluetooth address/endpoint, Android serial/ID/fingerprint, file, database, upload, or stored-ring read was collected | Capture manifest, 2026-07-20 |
| Evidence | Log SHA-256 `41e0eb10c3faf616b6cb066cfc8aea490fdc703811185d923cf6cfe2f63559c2`; screenshot SHA-256 `d31fd13a678c216049d82293f44521fa7d386f228255593912add31b6f725d96`; manifest SHA-256 `cf0e90fbec1166e73ba1ed7f51362d4d46f5335cae7a820a06e96af74ad9ffd2` | Ignored transactional HIL bundle, 2026-07-20 |

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
| Approximately 1.2-second hold (installed v3.0.12) | Pending | |
| Audio notifications while connected | Pending | |
| Offline recording while disconnected | Pending | |
| Reconnect after phone lock | Pending | |
| Reconnect after app termination | Pending | |
| Pendant reboot persistence | Pending | |
| Observed off-state wake | While the stock unit was off, owner taps/presses did not wake it; charger insertion restored operation. The shutdown trigger, elapsed time, exact attempted press grammar, charger electrical path, and root cause were not observed | Owner observation surrounding audio attempts 1 and 2, 2026-07-20 |
| Pairing dependency for current audio path | Not required: the successful live-audio witness remained `NOT_BONDED` | Scoped audio witness, 2026-07-20 |
| Charging telemetry/indication | Charger insertion recovered the observed off state; indication and telemetry semantics remain pending | Owner observation, 2026-07-20 |
| Battery read | `27%`, then `47%`; treat as volatile/unqualified until power testing explains the jump | Two allowlisted reads, 2026-07-18 |

## OTA readiness decision

The canary remains blocked until all of these required items are answered:

- [x] exact owned firmware is CV1 v3.0.12 and correlates with official tag commit
  `85159556eac753a088c5efd1b419a5a867508e27`;
- [x] official v3.0.12 bundle, extracted files, MCUboot headers/TLVs, outer signatures, and nested
  network-core validation are verified and recorded as the installed-release comparison oracle;
- [x] SMP service and current application image state are read successfully;
- [x] the exact-source v3.0.12 application payload is reproduced in its prescribed NCS v2.9.0
  toolchain and matches the installed-release oracle;
- [x] installed application hash is byte-identical to published v3.0.12;
- [ ] installed network hash and both images' secondary-slot state are observable and consistent; this
  remains open evidence even though Decision 0005 permits one bounded application-only experiment;
- [x] update mode and image-number selection are constrained by an audited Android flash lab that can
  request only image `0`, packages no image-`1` bytes, and fails on visible contradictory network state;
  and
- [x] exact application-only equal-version stock recovery plus post-update identity/audio/reconnection
  procedures are packaged and ready offline; physical execution remains separately owner-gated.

The optional full v3.0.12 to v3.0.20 migration is a separate later qualification and is not a canary
prerequisite. Its artifact/header/signature inspection and exact-source application reproduction are
already recorded as reference evidence, including the clean-build network NSIB key mismatch.

`APPLICATION_MATCH_NETWORK_UNOBSERVED` remains incomplete physical evidence. Under Decision 0005 it is
sufficient only for the exact owner-reviewed stock-to-canary and canary-to-stock application paths;
generic, multi-image, fleet, unattended, erase, settings, shell, or network firmware action remains
unauthorized.
