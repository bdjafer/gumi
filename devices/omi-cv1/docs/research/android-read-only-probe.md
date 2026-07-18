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
   [v3.0.20 source profile](../../protocols/gatt/v3.0.20/README.md).
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

Use Gumi's two-step `Review MCU image-state read` flow. Merely opening the review card performs no BLE
operation.

1. Scan for the same pendant and tap `Review MCU image-state read`.
2. Before approval, verify the card discloses the exact transient operations:
   - connect and discover the SMP service;
   - request ATT MTU 23, preserving the Pass A baseline;
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

- active application image `0`, slot `0`, version `0.0.0+0`, hash
  `0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36`;
- active network image `1`, slot `0`, version `0.0.0+0`, hash
  `267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089`; and
- no pending slot.

The observation still wins over the expectation. Stop before OTA if the app reports `MISMATCH`,
`TRANSITIONAL`, or `INCOMPLETE`; do not reinterpret the result to force a match.

## Pass C: observation-only behavior

With Gumi disconnected:

1. Record the boot LED/haptic sequence after one normal power cycle only if you already know the safe
   retail power gesture.
2. Observe single tap, double tap, and hold behavior without changing any mobile setting.
3. Note indicator timing; automatic reconnect is tested later under the owned connected-device service.

Do not deliberately create or play back private audio for this inventory. Audio-path testing gets its
own consented qualification session.

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
errors or surprising behavior:
```

Stop and preserve the exact log if image-state reading fails. A failure is diagnostic evidence, not
permission to reset, re-pair, or flash.
