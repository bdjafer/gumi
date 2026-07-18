# Omi CV1 Android read-only probe

Goal: identify the exact sealed pendant and its current boot/GATT state without writing, resetting,
pairing again, clearing storage, or uploading firmware.

## Tools

Install these two official Nordic applications from Google Play:

1. [nRF Connect for Mobile](https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp)
   for advertisements, GATT discovery, safe standard reads, and logs. Nordic's
   [documentation repository](https://github.com/nordicsemi/Android-nRF-Connect) describes it as the
   BLE inspection tool; its app source is not public.
2. [nRF Connect Device Manager](https://play.google.com/store/apps/details?id=no.nordicsemi.android.nrfconnectdevicemanager)
   for an MCU Manager image-state read. Its
   [source and library](https://github.com/nordicsemi/Android-nRF-Connect-Device-Manager) are Apache-2.0
   and are also our leading Android OTA reuse candidate.

Do not use the standalone legacy `nRF Device Firmware Update` application for this probe. CV1 uses
MCU Manager, not legacy Nordic Secure DFU.

## Hard stop list

During this session, do **not** invoke any action named or equivalent to:

- Update, Upload, Test, Confirm, Reset, Erase, Factory reset, Execute shell, Write, or Enable setting;
- Clear storage, Advance ring, Stop sync, or change time;
- Forget/unpair the device; or
- retry a failed mutation with a different image or option.

Reading image state, discovering services, reading Device Information/Battery, and saving local logs are
the only permitted device operations.

## Preparation

1. Charge the pendant and phone above 50% and keep both on external power if convenient.
2. In Android settings, grant both Nordic apps the requested Nearby Devices/Bluetooth permission.
   Location permission may be required on older Android versions.
3. Fully disconnect and force-stop the stock Omi app so it cannot compete for the single BLE connection.
   Do not clear its data and do not remove an existing bond.
4. Create a private evidence directory such as `local/omi-cv1/2026-07-18/`. `local/` is git-ignored.
5. Note the Android phone model, Android version, and Nordic app versions in a text file there.

## Pass A: advertisement and GATT inventory

Use **nRF Connect for Mobile**.

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
5. Read only these known-safe standard characteristics if present:
   - Device Information Service `0x180A`: manufacturer, model, serial, hardware, firmware, and software
     revision strings;
   - Battery Service `0x180F`: battery level.
6. Locate, but do not write to, these expected storage UUIDs:
   - service `30295780-4301-eabd-2904-2849adfeae43`;
   - control/write+notify `30295781-4301-eabd-2904-2849adfeae43`;
   - status/read+notify `30295782-4301-eabd-2904-2849adfeae43`.
7. A read of the 16-byte storage status characteristic is permitted. Save its raw hex; it should decode
   as four little-endian `u32` values: used bytes, unread packets, free bytes, and RTC-valid.
8. Record the negotiated MTU and PHY if the log exposes them. Do not request experimental MTU/PHY
   changes in this baseline.
9. Disconnect cleanly before opening the second Nordic app.

Redact stable Bluetooth addresses, serial numbers, and account-related values before moving any summary
into tracked documentation. Keep the unredacted export only under `local/`.

## Pass B: MCU Manager image-state inventory

Use **nRF Connect Device Manager**.

1. Scan for and connect to the same pendant.
2. Open the image/firmware manager and perform only its refresh/read-image-state operation. Selecting a
   ZIP file or starting an update is outside this probe.
3. Capture the complete image list. For every row/slot record:
   - image number;
   - slot number;
   - version;
   - hash;
   - bootable, pending, confirmed, active, and permanent flags; and
   - any split status or read error.
4. Record whether both image `0` (application core) and image `1` (network core) are visible.
5. Disconnect. Do not use the Reset, Basic, Filesystem, Settings, Shell, Logs, or update actions.

Expected stock release evidence is two active images with boot version `0.0.0+0`, but the hashes may
differ from the v3.0.20 bundle if the pendant runs another release. The observation wins over the
expectation.

## Pass C: observation-only behavior

With both Nordic apps disconnected:

1. Record the boot LED/haptic sequence after one normal power cycle only if you already know the safe
   retail power gesture.
2. Observe single tap, double tap, and hold behavior without changing any mobile setting.
3. Note indicator timing and whether either Nordic app reconnects automatically.

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
