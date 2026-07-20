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

The official v3.0.12 package matching the owned unit has also been decoded and verified. The physical
semantic read now proves that installed application image `0` has the exact published hash. Stock MCU
Manager exposed no network image `1` row, so network identity and secondary-slot state remain
unobserved. Pinned-source review shows that this stock state surface may omit an unreadable network
header and offers no separate safe command for recovering it. The bounded residual-risk decision is
[Decision 0005](../../../../docs/decisions/0005-omi-cv1-network-unobserved-ota.md).

The exact v3.0.12 application has now been reproduced in Nordic's prescribed NCS v2.9.0 toolchain: all
`228376` deterministic bytes match the published application and only the randomized RSA-PSS signature
value differs. The exact v3.0.20 application was independently reproduced as later-migration evidence.
Both clean builds generate a network image with a non-published NSIB key. The recovery artifact and
canary therefore use the v3.0.12 application lineage and leave the installed network image
untouched. No locally generated network image, complete OTA ZIP, or full-flash HEX is qualified for the
sealed device. See [build-reproduction-v3.0.12.md](build-reproduction-v3.0.12.md) and
[build-reproduction.md](build-reproduction.md).

The identity-only canary `0001` is now built from that exact lineage and qualified offline as one exact
signed application artifact. Its partition map matches the canonical stock build, its MCUboot version
remains `0.0.0+0`, and its signature verifies with the pinned compatibility key. Its release metadata
is [canary-0001.json](../../firmware/releases/canary-0001.json). The build and qualification contacted
no physical device and grant no permission to upload it.

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
10. Keep the identity-only canary behavior-neutral. Charger-independent button wake and microphone-off
    post-wake truth belong to the first functional firmware after canary/recovery, with their own HIL
    gate; they are not a reason to add behavior to the transport canary.

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

Current result: `APPLICATION_MATCH_NETWORK_UNOBSERVED`. It is sufficient to identify the exact
application firmware and to qualify bounded, owner-disclosed read-only diagnostics. It may enter only
the dedicated two-transition application flash lab under Decision 0005; it remains insufficient for a
generic, multi-image, unattended, production, or fleet update.
No firmware, storage, configuration, cursor, or other persistent device mutation was performed; the
disclosed MCU Manager read used only the transient request-characteristic and notification-CCCD writes
required by SMP.

The bounded follow-on audio metadata witness qualified 532 Opus frames over 9,999 ms at ATT MTU 498,
LE 2M/2M and `NOT_BONDED`, with zero gaps/discontinuities and no retained audio/content digest. Its first
setup attempt exposed a separate stock power gap: the unit was physically off, owner button attempts did
not wake it, and charger insertion recovered it. The off trigger, elapsed time, exact attempted press
grammar, electrical path, and root cause remain unknown. This does not widen the mutation gate and does
not imply pairing is required.

Exit gate for the development-unit mutation is: exact application identity, stable visible state, a
qualified target and recovery artifact, and an audited application-only OTA path that packages no
network bytes and cannot request image `1`. Any visible network evidence must match the published image;
complete absence stays explicit rather than being reinterpreted as a hash.

## Stage 1: reproduce the official build

Start from the release installed on the owned unit, not moving `main`:

```text
Omi_CV1_v3.0.12
85159556eac753a088c5efd1b419a5a867508e27
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
[stock OTA evidence](../../protocols/ota/v1/stock-v3.0.12.json). Build reproduction must
also confirm that the source configuration actually produces `CONFIG_BOOT_UPGRADE_ONLY`,
`CONFIG_MCUBOOT_DOWNGRADE_PREVENTION`, two updateable images, and the same partition map.

Stage 1 result: **passed for the v3.0.12 application core**. The canonical build matches the official
header, payload, digest and key-hash TLVs byte-for-byte; both independently valid files differ only in
the final randomized RSA-PSS signature value. The earlier SDK `0.16.8` mismatch is quarantined, and the
passing build uses the NCS v2.9.0-prescribed SDK `0.17.0`. Full evidence is in
[build-reproduction-v3.0.12.md](build-reproduction-v3.0.12.md). The v3.0.20 application reproduction
remains separate later-migration evidence in [build-reproduction.md](build-reproduction.md).

The network-key mismatch is a hard negative gate: never upload the clean-build `ipc_radio.bin`, full
`dfu_application.zip`, or merged HEX files to the sealed unit. Reuse the installed network core.

## Stage 2: physical preflight and non-ambiguous first transition

An identical official v3.0.12 application cannot qualify the transport from a stock v3.0.12 source.
Nordic Android Device Manager's high-level single-image path skips upload when the same MCUboot hash is
already active, and the lower-level confirm command addresses an image by that same hash. A final stock
hash therefore cannot prove whether bytes were transferred, selected, or booted. Do not perform a
stock-to-stock write merely to create the appearance of a recovery rehearsal.

Before any physical mutation:

1. require the read-only image-state oracle to report either `MATCHES_PUBLISHED_V3012` or the exact
   `APPLICATION_MATCH_NETWORK_UNOBSERVED` shape allowed by Decision 0005;
2. require no visible pending image or populated secondary slot; if image `1` is visible, require its
   exact published active hash and empty visible secondary state;
3. verify the unmodified official v3.0.12 ZIP/application against
   [stock-v3.0.12.json](../../protocols/ota/v1/stock-v3.0.12.json);
4. qualify the exact canary artifact against
   [canary-0001.json](../../firmware/releases/canary-0001.json), including `imgtool` signature
   verification;
5. record the application hash, explicit network-observation status, phone/Omi battery state,
   process-local endpoint binding, and recovery artifact location; and
6. stop for an explicit owner go/no-go that names the exact canary file SHA-256.

The dedicated flash-lab APK is now composed outside the product shell. Its scoped check verifies the
two exact application assets, transaction state machine, source-drift/cancellation/reboot negatives,
lint, permissions, and packaged absence of network/ZIP/HEX artifacts. The owned unit uses
confirm-only/overwrite-style upgrade policy, so the canary does not depend on automatic test-and-revert.
Composition and a passing build still do not authorize a physical write; the final owner action remains
separate and names the exact canary file SHA-256.

## Stage 3: Gumi canary OTA

The canary must:

- update application image `0` only and leave network image `1` untouched;
- retain the upstream partition layout, MCUboot settings, GATT protocol, and storage format;
- retain application MCUboot header version `0.0.0+0` and add no security-counter TLV;
- expose the unambiguous `gumi-canary-0001` software revision through Device Information, not through
  the MCUboot header;
- add one visible boot indication; and
- add no capture-mode or storage migration yet.

Upload the exact application image using the isolated Android MCU Manager adapter, whose only upload
call names image index `0`. The offline candidate now checks the exact full source state both when the
plan is prepared and again immediately before upload, accepts `0.0.0` only as Zephyr's exact wire form
of header `0.0.0+0`, then checks the staged canary hash before confirmation, the confirmed canary hash
before reset. Complete absence of image `1` is accepted only for these pinned transitions; any visible
network row must retain the exact stock active hash with no pending or populated secondary state. If any
other state differs, stop; never substitute a multi-image package.

Nordic Android Device Manager `2.8.0` exposes separate APIs for a single image and an explicit
multi-image `ImageSet`, so this boundary can reuse the maintained transport/state machine rather than
forking MCU Manager. Gumi's isolated adapter calls only the explicit single-image-`0` path; repository
checks forbid `ImageSet` and audit the final APK's two exact application assets. A reset-time disconnect
is recorded as response-unobserved and can advance
only to `AWAITING_POST_REBOOT_VALIDATION`; it is never treated as proof that the target booted.

For the first explicit canary approval, verify:

- the upload produces canary hash
  `d3b3c74c10c4fa110763ae5523d0aeaa20b9f815b8674167486be6ff6f8052ce` only in application secondary
  slot `1` before confirmation;
- confirmation exposes no image-`1` pending state and the device boots once and reconnects;
- application image `0` becomes the canary hash; record network image `1` as unobserved, or require
  `267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089` if it becomes visible;
- Device Information reports `3.0.12` / `gumi-canary-0001` and boot shows three short magenta pulses;
  and
- existing live audio, button, offline storage, battery, and charging behavior still work.

Then stop for a distinct recovery go/no-go. Recovery uses only the exact official v3.0.12 application
(`877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db`) from the exact canary source
state. After reboot, require the published application hash, apply the same explicit network-evidence
policy, and re-run the behavior checks. The different source and target hashes make this recovery observable even though both MCUboot
versions are `0.0.0+0`.

After proving recovery, a later separately approved canary reinstall can run the same checks. Do not
proceed to capture semantics if the application-only round trip
`stock v3.0.12 -> canary v3.0.12 -> stock v3.0.12 -> canary v3.0.12` is not repeatable.

Exit gate: we can reproduce, upload application image `0`, boot, reconnect, show no contradictory
image-`1` evidence while preserving a construction that cannot request it, and perform an equal-version
forward recovery without direct hardware access.

## Stage 4: first functional firmware

After the canary gate:

- add explicit capture state with microphone-off Idle;
- replace stock Normal-mode shutdown gestures and prove a qualified button press wakes from Off without
  pairing, link, edge, cloud, or charger dependency;
- enter microphone-off Booting after every wake and never resume a prior capture automatically;
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
