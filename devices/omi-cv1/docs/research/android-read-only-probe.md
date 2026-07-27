# Omi CV1 Android read-only probe

Goal: identify the exact sealed pendant and its current boot/GATT state without persistent device
mutation, pairing, clearing storage, resetting, or uploading firmware. Pass A performs only GATT reads.
Pass B is a semantic MCU Manager read whose request/response transport requires explicitly disclosed,
transient BLE writes.

## Tools

Use the repository-built Gumi Android diagnostic shell. Its scanner and GATT inspector are implemented
with Nordic Android BLE Library 2.11.0. Its separately bounded image-state inspector reuses
[Nordic Android Device Manager 2.8.0](https://github.com/NordicSemiconductor/Android-nRF-Connect-Device-Manager/tree/559724446b113f46fc60324df4dfd1160faa2a02).

Nordic's official nRF Connect for Mobile and nRF Connect Device Manager applications remain independent
comparison tools, not prerequisites for this run. Do not use the standalone legacy `nRF Device Firmware
Update` application: CV1 uses MCU Manager, not legacy Nordic Secure DFU.

## Hard stop list

During this session, do **not** invoke any action named or equivalent to:

- Update, Upload, Test, Confirm, Reset, Erase, Factory reset, Execute shell, a manual/arbitrary Write,
  or Enable setting;
- Clear storage, Advance ring, Stop sync, or change time;
- Forget/unpair the device; or
- retry a failed mutation with a different image or option.

Reading image state, discovering services, reading the explicit Device Information/Battery/storage
allowlist, and saving local redacted logs are the only permitted device operations. Pass B's two SMP
READ request packets and notification-descriptor enable are the sole transport-write exception; the
Gumi port exposes no generic write or updater operation.

## Preparation

1. Charge the pendant and phone above 50% and keep both on external power if convenient.
2. In Android settings, grant Gumi the requested Nearby Devices/Bluetooth permission. Location
   permission may be required on older Android versions.
3. Fully disconnect and force-stop the stock Omi app so it cannot compete for the single BLE connection.
   Do not clear its data and do not remove an existing bond.
4. Create a private evidence directory such as `local/omi-cv1/2026-07-18/`. `local/` is git-ignored.
5. Note the Android phone model, Android version, and Gumi APK digest in a text file there.

## Pass A: advertisement and GATT inventory

Use **Gumi** and its `Inspect read-only GATT` action.

1. Start a scan with the pendant nearby and stationary.
2. Identify the Omi by its local name and changing RSSI. Save a screenshot of the complete expanded
   advertisement.
3. Record, without committing the raw value:
   - local name;
   - service UUIDs;
   - manufacturer/service data shape and length;
   - connectable flag; and
   - RSSI at approximately one metre.
4. Connect once. Save/export the connection log and a screenshot of the complete discovered service
   tree, including characteristic UUIDs and properties.
   Compare the redacted export with the checked-in
   [installed v3.0.12 source profile](../../protocols/gatt/v3.0.12/README.md). Use the
   [v3.0.20 profile](../../protocols/gatt/v3.0.20/README.md) only as a separate later-migration delta,
   never as the owned-unit baseline.
5. Read only these known-safe characteristics if present:
   - Device Information Service `0x180A`: manufacturer, model, hardware, firmware, and software
     revision strings; serial is deliberately excluded from the tracked probe;
   - Battery Service `0x180F`: battery level.
6. Locate, but do not write to, these expected storage UUIDs:
   - service `30295780-4301-eabd-2904-2849adfeae43`;
   - control/write+notify `30295781-4301-eabd-2904-2849adfeae43`;
   - status/read+notify `30295782-4301-eabd-2904-2849adfeae43`.
7. A storage-status read is permitted. Decode by installed firmware contract: v3.0.12 returns eight
   bytes containing two little-endian `u32` file sizes; v3.0.20 returns 16 bytes containing used bytes,
   unread packets, free bytes, and RTC-valid. Preserve unknown lengths rather than guessing.
8. Record the negotiated MTU and PHY if the log exposes them. Do not request experimental MTU/PHY
   changes in this baseline.
9. Gumi disconnects in a non-cancellable cleanup block after the allowlist completes or fails.

Redact stable Bluetooth addresses, serial numbers, and account-related values before moving any summary
into tracked documentation. Keep the unredacted export only under `local/`.

## Pass B: MCU Manager image-state inventory

Use Gumi's two-step `Review MCU image-state read` flow. Merely opening the scrollable owner-review
sheet performs no BLE operation; its explicit run action remains separate from ordinary page content.

1. Scan for the same pendant and tap `Review MCU image-state read`.
2. Before approval, verify the card discloses the exact transient operations:
   - connect and discover the SMP service;
   - request ATT MTU 498, the already qualified ceiling of both recovered stock and recovery-only;
   - write the SMP CCCD to enable response notifications;
   - send MCU Manager parameters READ, group `0`, command `6`;
   - send MCUboot image-state READ, group `1`, command `0`; and
   - disconnect and release the transport.
3. Only after explicit human approval, tap `Run disclosed image-state read`. The narrow adapter exposes
   no upload, test, confirm, reset, erase, filesystem, settings, or shell API.
4. Capture the complete image list. For every row/slot record:
   - image number;
   - slot number;
   - version;
   - hash;
   - bootable, pending, confirmed, active, and permanent flags; and
   - any split status or read error.
5. Record whether both image `0` (application core) and image `1` (network core) are visible.
6. Confirm the app reports terminal success or error and releases the connection. Do not improvise a
   retry using another tool or action.

For the reported v3.0.12 release, the verified official comparison oracle is:

- active application image `0`, slot `0`, MCUboot header version `0.0.0+0` (Zephyr MCU Manager wire
  form `0.0.0`), hash
  `0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36`;
- active network image `1`, slot `0`, MCUboot header version `0.0.0+0` (wire form `0.0.0`), hash
  `267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089`; and
- no pending slot.

The observation still wins over the expectation. Stop before any generic OTA if the app reports
`MISMATCH`, `TRANSITIONAL`, or `INCOMPLETE`. `APPLICATION_MATCH_NETWORK_UNOBSERVED` remains incomplete
evidence and may enter only the two exact application transitions governed by
[Decision 0005](../../../../docs/decisions/0005-omi-cv1-network-unobserved-ota.md); it is never
reinterpreted as a network-hash match.

### Owned-unit result, 2026-07-20

The read completed once and released its transport. It returned one active application row:

- image `0`, slot `0`, wire version `0.0.0`;
- hash `0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36`;
- bootable, confirmed, and active; not pending; and
- no image `1` row.

The application hash is an exact published-v3.0.12 match. Two source facts correct the original
`MISMATCH` rendering: pinned Zephyr `v3.7.99-ncs2` omits a zero build number when encoding its version
string, and its state encoder silently omits a slot when `img_mgmt_read_info` cannot read a valid image
header. The app is built with two updateable images, but this response does not prove the installed
network image or either secondary-slot state. Gumi therefore reports
`APPLICATION_MATCH_NETWORK_UNOBSERVED`: exact application identity, explicitly incomplete network
identity.

The same result was re-established after the separately authorized identity-canary recovery. Flash
Lab first reached `VALIDATED` on the exact stock application hash, and an independent provenance-bound
Gumi attempt then returned one active/bootable/confirmed/not-pending application row with hash
`0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36`. Image `1` remained wholly
unobserved. The independent image-state log, screenshot, and manifest SHA-256 values are respectively
`6f1a8d315857f5624210aff7029358c0f2df163a4bdc7da3770a4827da30ad89`,
`9e9413fd2c40dfc90e4edefd6ba629eae7692aa502ffabca51390129a734161a`, and
`69a2e735e4191a2cf09d8ca00452c1727062f75762f1f9c05bf381b48527743c`.

## Pass C: observation-only behavior

With Gumi disconnected:

1. Record the boot LED/haptic sequence after one normal power cycle only if you already know the safe
   retail power gesture.
2. Observe single tap, double tap, and hold behavior without changing any mobile setting.
3. Note indicator timing; automatic reconnect is tested later under the owned connected-device service.

Do not deliberately create or play back private audio for this inventory. Audio-path testing gets its
own consented qualification session.

### Owned-unit power/wake observation, 2026-07-20

The first audio setup attempt timed out because the owner found the pendant physically off. Button
taps/presses did not wake it; charger insertion restored operation, after which the separately disclosed
audio attempt completed. This establishes only the observed recovery sequence. It does not establish
what put the unit into Off, how long that took, the exact attempted press grammar, the electrical wake
path, or a pairing dependency. In particular, the later successful audio session was `NOT_BONDED`.

Pinned v3.0.12 source intends P0.26, active-low with pull-up and a level-low interrupt, to restart the
device from system-off. The sealed-unit observation contradicts that intended user-visible result and
is therefore a stock power/wake qualification gap, not evidence that Gumi firmware was installed. At
the time of that observation, no firmware write had occurred.

## Bounded post-image audio metadata witness

After, and only after, Gumi reports either `MATCHES_PUBLISHED_V3012` or
`APPLICATION_MATCH_NETWORK_UNOBSERVED`, the separate owner-disclosed 10-second metadata witness may
request ATT MTU `512` before enabling the stock audio notification. This narrower read-only gate is
justified by the exact application hash: microphone capture, Opus encoding, and the notification
envelope live in those verified application bytes. The unobserved network core remains material for
OTA, but the metadata probe treats its BLE behavior as untrusted input and fails closed on MTU,
framing, bounds, continuity, disconnect, or cleanup anomalies.

The MTU request is not an experimental guess: the current upstream Android connection owner requests
`512` after service discovery and before declaring the device ready. The exact v3.0.12 firmware source
bounds an Opus packet at 160 bytes and prefixes each notification with a wrapping `u16` little-endian
sequence and `u8` fragment index. Gumi requires negotiated MTU at least 166, requires codec ID `21`,
strips and validates the envelope, and rejects fragmentation. It still records the actually negotiated
MTU as evidence; a request and source proof are not physical proof that Android and the pendant
accepted the value or delivered the expected boundaries.

Source audit: BasedHardware/omi `main` at
[`ae38a649c086b08814de45e3bbe189c107f60318`](https://github.com/BasedHardware/omi/blob/ae38a649c086b08814de45e3bbe189c107f60318/app/android/app/src/main/kotlin/com/friend/ios/ble/OmiBleForegroundService.kt),
2026-07-19 (`MTU_SIZE = 512`, requested after discovery). Pass A remains an MTU-23 inventory baseline.
The original recovered-stock Pass B also completed at MTU 23, but a fresh recovery-only connection
proved that Nordic Device Manager cannot carry the larger image-state response in that envelope and
raised `InsufficientMtuException`. The current Pass B therefore requests the already qualified 498-byte
ceiling; this is transient link negotiation, not persistent device mutation.

### Owned-unit audio result, 2026-07-20

Recovered-stock attempt 1 completed as `AUDIO_METADATA_QUALIFIED` and closed the stream, device
session, and BLE transport before publishing its result:

- 536 frames and 41,534 aggregate payload bytes over a 9,994 ms receive span;
- payload bounds 62–110 bytes, sequence 0–535, zero gaps, zero discontinuities, and maximum
  interarrival 144 ms;
- Opus / 16 kHz / mono / raw Opus packet, configuration `23`, one 20 ms frame, and 960 decoded samples
  at 48 kHz;
- negotiated MTU 498, LE 2M transmit/receive, and `NOT_BONDED`; and
- no audio payload, content digest, content log, full logcat, stored-ring read, Bluetooth address,
  endpoint ID, Android serial/ID/fingerprint, file/database persistence, or upload.

The transactional bundle's log SHA-256 is
`6377f6873dd446e485de09491008fa7a92ab53d2aded908c64b7db136a6b66dd`, screenshot SHA-256 is
`e698816db28e75c8fa5dbb8dfa72c82682f1a0e4b5b07b783e61b75d3e6c454e`, and manifest SHA-256 is
`fee30920c5dc3e081433840512d788ced54692b89978d3c8eb36964762453579`.

For a later `AUDIO_SETUP_TIMEOUT`, first establish whether the pendant is physically awake. Charger
insertion is the only recovery demonstrated on this particular stock unit. A timeout is not evidence
that pairing is required. Use at most the procedure's one allowed retry; if a fresh scan is required,
its new generation revokes the old firmware/audio authority and the image-state review must be repeated.

## Result to return

Update [device-inventory.md](device-inventory.md) with redacted values and evidence dates. The minimum
useful handoff is:

```text
phone / Android version:
advertised name:
model / hardware / firmware revisions:
service UUID list or redacted GATT-export hash:
negotiated MTU / PHY:
storage status raw hex:
image 0 slot rows:
image 1 slot rows:
bounded audio aggregate result:
off-state wake/recovery observation:
errors or surprising behavior:
```

Stop and preserve the exact log if image-state reading fails. A failure is diagnostic evidence, not
permission to reset, re-pair, or flash.
