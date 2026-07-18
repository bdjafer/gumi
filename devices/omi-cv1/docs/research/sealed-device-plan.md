# Omi CV1 sealed-device firmware plan

Constraint: use the owned retail Omi CV1 without opening it and without purchasing a J-Link or SWD
fixture unless evidence proves there is no safe alternative.

## Current verdict

There are two different firmware paths:

- **Direct programming**: the official CV1 documentation requires a J-Link and special SWD cable.
- **Application/network OTA**: published CV1 releases and the upstream build guide use MCUboot plus the
  standard MCU Manager BLE protocol. The Omi application uses `mcumgr_flutter` for CV1 and marks it as
  non-legacy DFU.

The OTA path is the primary Gumi development path. It requires only the pendant, a BLE-capable Android
phone, and a reproducible application build. It cannot replace the existing MCUboot or network B0n
trust roots.

The official v3.0.20 package has now been decoded and both signatures verified against the release
commit's compatibility key. Both application and network-core images use MCUboot version `0.0.0+0`,
have no protected TLV/security counter, and share the same exposed upstream RSA key. The pinned
bootloader configuration nevertheless enables software downgrade prevention. See
[stock-ota-inspection.md](stock-ota-inspection.md).

The official v3.0.12 package matching the owned unit has also been decoded and verified. Its two
published MCUboot TLV hashes form the exact oracle for the pending semantic image-state read; no claim
is made about the installed bytes until the device returns matching hashes.

The exact release build has also been reproduced. Its application payload matches the published image
exactly, but its clean network build embeds a newly generated NSIB public key rather than the published
network key. The first canary is therefore application-core-only; no locally generated network image or
full-flash HEX is qualified for the sealed device. See [build-reproduction.md](build-reproduction.md).

Sources:

- [official flash documentation](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/docs/doc/get_started/Flash_device.mdx)
- [upstream build and OTA guide](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/omi/firmware/BUILD_AND_OTA_FLASH.md)
- [owned-unit release oracle: Omi CV1 v3.0.12](https://github.com/BasedHardware/omi/releases/tag/Omi_CV1_v3.0.12)
- [Omi CV1 v3.0.20 release](https://github.com/BasedHardware/omi/releases/tag/Omi_CV1_v3.0.20)
- [Omi app MCU Manager implementation](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/app/lib/pages/home/firmware_mixin.dart)

## Safety rules

1. Do not upload anything during the read-only baseline.
2. Record the current firmware and hardware revisions before updating the unit.
3. Preserve the exact official OTA artifact and its SHA-256 before building a canary.
4. Inspect the current and candidate MCUboot image headers, slot list, security counter, and upgrade mode.
5. Never guess a version/security counter merely to bypass downgrade protection.
6. During the stock-bootloader compatibility phase, keep the application canary boot header exactly
   `0.0.0+0`.
   A higher accepted version could lock out every published stock recovery image.
7. The first custom image changes one reversible visible behavior and keeps audio/storage protocols intact.
8. Stop after the first rejection or anomalous reboot; do not repeatedly push variants at the device.
9. Do not promise automatic rollback: the inspected configuration uses overwrite/upgrade-only settings.

## Stage 0: read-only device inventory

Follow the exact [Android read-only probe](android-read-only-probe.md) with Gumi. Nordic nRF Connect for
Mobile and nRF Connect Device Manager remain optional independent comparison tools, not prerequisites.
The reconnect/install/capture sequence is automated by the
[image-state handoff](../../../../docs/development/omi-image-state-handoff.md), except for the deliberately
manual owner tap that approves the disclosed semantic read.

Collect and save:

- advertised local name, identifier/address, manufacturer data, service UUIDs, and RSSI;
- Device Information Service values;
- complete GATT service/characteristic/property table;
- connection, MTU, PHY, pairing, and bonding observations;
- MCU Manager image slot listing and hashes, if the read operation is permitted;
- battery/charging values and current offline storage status;
- button events and LED/haptic behavior without modifying settings; and
- behavior across disconnect, phone lock, app termination, and pendant reboot.

Record the redacted result in [device-inventory.md](device-inventory.md); sensitive raw exports stay in
the ignored `local/` directory.

Exit gate: the inventory is sufficient to identify the exact firmware family, image numbers, and an
application-only OTA path that does not write the network image. No write is performed.

## Stage 1: reproduce the official build

Start from the qualified release tag, not moving `main`:

```text
Omi_CV1_v3.0.20
aa1133cd17139aa09cbe4883cdf51f15094b9916
```

Reuse the upstream NCS 2.9.0 Docker/CI build scripts. Compare the generated package with the published
package structurally:

- manifest format and image indexes;
- board and SoC identifiers;
- partition/load addresses;
- application and network-core image sizes;
- MCUboot header and trailer data; and
- signing-key fingerprint.

The published package's expected values live in the checked-in
[stock OTA evidence](../../protocols/ota/v1/stock-v3.0.20.json). Build reproduction must
also confirm that the source configuration actually produces `CONFIG_BOOT_UPGRADE_ONLY`,
`CONFIG_MCUBOOT_DOWNGRADE_PREVENTION`, two updateable images, and the same partition map.

Stage 1 result: **passed for the application core**. The application payload digest is identical to the
published image and the compiled partition/update policy matches. The network firmware bytes also
match, but its generated NSIB public key does not. Full details are in
[build-reproduction.md](build-reproduction.md).

The network-key mismatch is a hard negative gate: never upload the clean-build `ipc_radio.bin`, full
`dfu_application.zip`, or merged HEX files to the sealed unit. Reuse the installed network core.

## Stage 2: official OTA rehearsal

Only if the unit is not already on the pinned stable version, and only after a separate explicit
go/no-go review:

1. Upload the unmodified official OTA through Nordic Device Manager.
2. Verify the expected restart and reported firmware version.
3. Re-run the read-only inventory and confirm audio, button, storage, and charging behavior.

This is the only planned stage that may write the vendor's published network image. It validates the
vendor update path independently of custom source. Skip it when the unit already reports v3.0.20; it is
not required merely to rehearse Gumi transport.

Nordic's maintained Android documentation notes that nRF5340 multi-core devices support confirm-only
upgrade mode rather than test-and-revert. The canary must therefore not depend on automatic reversion.

## Stage 3: Gumi canary OTA

The canary must:

- update application image `0` only and leave network image `1` untouched;
- retain the upstream partition layout, MCUboot settings, GATT protocol, and storage format;
- retain application MCUboot header version `0.0.0+0` and add no security-counter TLV;
- expose an unambiguous Gumi development revision through the application-level Device Information and
  capability response, not through the MCUboot header;
- add one visible boot indication; and
- add no capture-mode or storage migration yet.

Upload the application image using a qualified Android MCU Manager adapter that proves it does not
write image `1`. If a stock client insists on processing both images, stop and implement the narrow
app-only updater rather than passing it a locally generated network image.

Nordic Android Device Manager `2.8.0` exposes separate APIs for a single image and an explicit
multi-image `ImageSet`, so this boundary can reuse the maintained transport/state machine rather than
forking MCU Manager. Gumi's adapter will call only the single-image path for the canary and verify image
`1` before and after.

Verify:

- upload and image validation complete;
- the device boots once and reconnects;
- the Gumi revision and canary indication are visible;
- existing live audio and offline storage still work; and
- the device accepts the exact official v3.0.20 **application image** as a subsequent equal-version
  recovery update while the network image remains unchanged.

After proving recovery, reinstall the canary once and re-run the same checks. Do not proceed to capture
semantics if the application-only round trip `stock -> canary -> stock -> canary` is not repeatable.

Exit gate: we can reproduce, upload application image `0`, boot, reconnect, prove image `1` was not
written, and perform an equal-version forward recovery without direct hardware access.

## Stage 4: first functional firmware

After the canary gate:

- add explicit capture state with microphone-off Idle;
- add double-tap capture toggle and hold-to-talk events;
- preserve the existing audio and ring wire formats initially;
- add a versioned Gumi capability descriptor;
- require an authenticated control relationship before commands or audio access; and
- wrap subsequent OTA payloads in a Gumi-owned inner signature before the firmware writes them.

The compatibility signature remains necessary while the stock bootloader is installed. The stock
network controller and its B0n trust remain unchanged. Generic update access should be removed once the
Gumi updater is qualified.

## If OTA is rejected

Do not open the device immediately. Capture the exact MCU Manager error and slot state, then evaluate in
this order:

1. mismatch between unit firmware family and CV1 source/tag;
2. boot-header version/downgrade-prevention rejection;
3. wrong application image mapping or a client attempting to write image `1`;
4. MCUboot public-key mismatch between source and shipped unit;
5. an official developer unlock or signed-development image from Based Hardware; and
6. only then, a non-destructive external SWD cable or service procedure.

M1 application/backend work can continue against stock firmware while that investigation runs; custom
button semantics cannot be called complete until the device firmware path is proven.
