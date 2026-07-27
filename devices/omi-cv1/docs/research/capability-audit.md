# Omi CV1 capability audit

Status: source/release audit complete; owned-unit identity, advertisement, GATT, operational-driver,
application image identity, and bounded live-audio metadata measured. The network image is not exposed
by stock MCU Manager; behavior, power, and storage semantics remain partially observed.

The exact component/pin/storage/power breakdown is maintained in
[component-map.md](component-map.md); this document is the decision-oriented capability matrix.

## Evidence baseline

This audit deliberately distinguishes four evidence levels:

- **Component**: present in the current BOM or schematic.
- **Source**: implemented in the inspected source tree.
- **Release**: present in a published CV1 firmware artifact or release flow.
- **Bench**: observed on the exact physical device owned by the project.

No source-level observation is promoted to `Bench` without measuring the sealed unit.

Pinned upstream references:

- research snapshot: BasedHardware/omi `main` at
  [`1c19526cacb8a6100e8060b203c02963882281cf`](https://github.com/BasedHardware/omi/tree/1c19526cacb8a6100e8060b203c02963882281cf);
- installed-release recovery/canary basis: tag
  [`Omi_CV1_v3.0.12`](https://github.com/BasedHardware/omi/releases/tag/Omi_CV1_v3.0.12), commit
  `85159556eac753a088c5efd1b419a5a867508e27`, official OTA SHA-256
  `821ce06d73f8bb3695de70dce0880a00597dd71175a843d08e577d775125ab4e`;
- later migration/reference candidate: tag
  [`Omi_CV1_v3.0.20`](https://github.com/BasedHardware/omi/releases/tag/Omi_CV1_v3.0.20), commit
  `aa1133cd17139aa09cbe4883cdf51f15094b9916`, official OTA SHA-256
  `dfc7ea6986d9b02fe899a38afc6c9bf6fabb9cff669244fbf20a3d7abeda59da`.

The owned unit currently reports firmware `3.0.12`, hardware `5.0`, and the exact
[observed v3.0.12 GATT profile](../../protocols/gatt/v3.0.12/README.md). This is the bench baseline;
v3.0.12 is also the first application-only canary/recovery basis. v3.0.20 remains a later migration
candidate, not a claim about installed state and not a prerequisite for the first canary.

The open source repository is MIT licensed. Nordic SDK modules and other dependencies retain their own
licenses.

## Capability matrix

| Capability | Component | Source/release reality | Bench work | M1 disposition |
| --- | --- | --- | --- | --- |
| Compute | nRF5340 dual-core Cortex-M33 | Application and network cores are built as a multi-image NCS system | Model `Omi CV 1`, hardware `5.0`, firmware `3.0.12`; application image hash exactly matches the release, while the network row is not exposed | Reuse the existing board and sysbuild definitions |
| Audio input | Two T5838 PDM microphones | 16 kHz, 16-bit interleaved capture; firmware averages both channels to mono and encodes Opus | Recovered-stock witness delivered 536 mono Opus frames over 9,994 ms with no sequence gaps/discontinuities, payload 62–110 bytes, and no retained content. Per-microphone identity, acoustic quality, clipping, and current remain unmeasured | Reuse PDM and Opus paths; preserve stereo during experiments |
| Acoustic activity | T5838 AAD support | Current configuration combines software VAD with hardware acoustic activity detection | Measure wake latency, false activation, and silence current | Optional power mechanism, never a substitute for explicit recording state |
| Button | One tactile button | Official v3.0.12 source assigns single tap to shutdown and a one-second long press to notification; it configures the active-low button as the intended system-off wake source. v3.0.20 keeps tap notifications but assigns a three-second hold to shutdown | The owned unit did not wake from owner button attempts while off; charger insertion recovered it. The off trigger, exact attempted press grammar, and root cause are unknown. Gesture/timing/false-positive measurement remains open | Replace both stock mappings with the proposed versioned [human-I/O contract](../../protocols/human-io/v1/README.md): double-tap toggles Recording, hold/release controls VoiceTurn, Normal mode has no power gesture, and a qualified press wakes from Off without a charger |
| Feedback | RGB LEDs and vibration motor | Source drives one logical RGB and a simple haptic, but stock colors overlap link, charging, RTC, boot, and faults. The observed v3.0.12 GATT surface exposes haptic write and LED-dimming settings; it is not an authenticated privacy contract | Verify physical package mapping, daylight/dark visibility, haptic strength, named-pattern distinction, and timing against PDM acquisition/release | One firmware-owned arbiter; continuous non-overridable privacy guard plus accessible fault/status patterns |
| Motion | LSM6DS3TR-C six-axis IMU | Physical component is present. The pristine release build compiles I2C and the LSM6DSL driver, but disables the application accelerometer capability, its GATT service, and configured sampling | Identify device address and validate FIFO, interrupt, tap, and power modes | Latent capability; not on the critical audio path |
| BLE | nRF5340 radio | Active GATT data plane, 2M/Coded PHY configuration, one connection, OTA, live audio, and offline sync. Current upstream Android requests ATT MTU 512 after discovery | Recovered stock exposes 11 services/21 characteristics and negotiated the Gumi driver at MTU 23/2M/2M; bounded audio qualified at MTU 498/2M/2M and `NOT_BONDED`, with 536 frames over 9,994 ms and no gaps. Reconnect/background behavior remains pending | Primary device-to-edge transport for M1 |
| Wi-Fi | nRF7002 companion | Present in board definitions; no qualified current application-level path to depend on; v3.0.17 release notes explicitly disabled it for improved BLE sync | Only probe after BLE M1 is stable | Out of M1 critical path |
| Offline media | NAND/SD-style storage plus firmware ring | v3.0.12 exposes two file-size words; v3.0.20 changes status to four words and uses 444-byte ring records. Documentation, part description, and firmware usable ceiling disagree | Owned unit returned file sizes 505,118,720 and 0 bytes; semantics, capacity, endurance, overwrite, and recovery remain unqualified | Version the legacy/new storage contracts; do not promise offline duration yet |
| OTA staging | External SPI NOR and MCUboot partitions | Published v3.0.12 and v3.0.20 OTAs are signed two-image MCU Manager packages with `0.0.0+0` images. Canonical clean builds reproduce each application payload, but auto-generate a different inner NSIB key for each network image | A Decision-0005-bounded stock -> identity-canary -> stock application-only cycle passed fresh source/staged/confirmed/post-reboot checks. The exact application hashes were observable; the network row and secondary-slot truth remained unobserved, so generic or multi-image OTA remains unauthorized | Reuse MCUboot/MCUmgr only through the explicit image-`0` boundary; retain `0.0.0+0`; never write a locally generated image `1` |
| Power | 150 mAh LiPo, charger, buck, protection | Battery and charging telemetry are exposed; v3.0.12 source intends the active-low button to wake system-off | The owned stock unit was found off, did not wake from button attempts, and recovered on charger insertion. The off trigger, elapsed time, wake electrical path, and root cause remain unknown. Profile Idle, Recording, VoiceTurn, Syncing, and Updating separately | Charger-independent button wake, post-wake microphone-off truth, and battery budgets are release gates |
| Debug | SWD/reset/test signals in the board design | Official direct-flash documentation calls for a J-Link and special cable | No opening and no debug probe in the current project constraint | OTA-first; direct programming is a recovery/security contingency only |
| Output audio | No speaker found in the consumer BOM/mainboard path | Generic speaker code is disabled and is not evidence of a CV1 speaker | Confirm acoustically without disassembly | Not a CV1 capability |
| USB/NFC | SoC/legacy code possibilities | No qualified consumer USB data or NFC product path identified | None for M1 | Do not advertise as available |

Primary sources:

- [CV1 documentation](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/docs/doc/hardware/OmiConsumer.mdx)
- [consumer BOM](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/omi/hardware/consumer/bom/omi-bom.csv)
- [mainboard schematic](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/omi/hardware/consumer/electrical/mainboard/schematic.pdf)
- [firmware configuration](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/omi/firmware/omi/omi.conf)
- [microphone implementation](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/omi/firmware/omi/src/mic.c)
- [storage implementation](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/omi/firmware/omi/src/sd_card.c)
- [GATT implementation](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/omi/firmware/omi/src/lib/core/transport.c)
- [current Android audio connection owner](https://github.com/BasedHardware/omi/blob/ae38a649c086b08814de45e3bbe189c107f60318/app/android/app/src/main/kotlin/com/friend/ios/ble/OmiBleForegroundService.kt)

## Current firmware behavior worth preserving

- Zephyr/NCS board target `omi/nrf5340/cpuapp` with a separately updated network core.
- Opus framing used by the existing app and offline ring.
- Live BLE notifications and simultaneous recording/offline sync.
- Time synchronization and resumable ring reads with explicit read/write sequence numbers.
- Battery, charging, device information, settings, button, haptic, feature, and storage surfaces.
- MCUboot multi-image OTA through the standard MCU Manager protocol.
- Hardware AAD/VAD power savings, provided they are subordinate to explicit privacy state.

## Current behavior that must not become Gumi's contract

- Microphone capture starting automatically at boot.
- Version-dependent stock shutdown gestures and stock RGB meanings being treated as stable human-I/O.
- Charger insertion being required for normal wake, or pairing/link/edge/cloud availability being treated
  as a power prerequisite.
- An unbonded stock haptic write being treated as trusted command acknowledgement.
- Two physical microphones being irreversibly mixed before we have measured their value.
- Ad-hoc GATT permissions that do not require an authenticated/encrypted client.
- A boot signing private key committed in a public upstream repository.
- Firmware documentation that still names the legacy XIAO/nRF52840 target.
- Documentation-derived storage size or battery duration without bench verification.
- Wi-Fi presence in the BOM being treated as a working end-to-end data path.

## Security finding: functional ownership versus root ownership

The stock CV1 release uses MCUboot and accepts a two-image MCU Manager OTA package. The upstream build
configuration signs with `omi/firmware/bootloader/mcuboot/root-rsa-2048.pem`, and that private key is
present in the public repository.

Consequences:

1. The inspected official v3.0.12 and v3.0.20 outer signatures have been verified against that exact
   upstream key.
2. A compatibility-signed application image whose exact toolchain, payload, header, and TLVs pass the
   release gates should plausibly be accepted by a stock unit over OTA. This does not apply to a failed
   reproduction or any locally generated network image.
3. This must be proven with a canary; release verification is not proof of the bootloader installed on
   the owned unit.
4. OTA updates the application and network-core images, not the MCUboot trust root.
5. A sealed-device Gumi build can therefore become functionally custom while the hardware boot root
   still trusts the publicly exposed upstream key.
6. Gumi can add an inner project-owned signature and remove generic unauthenticated update entry points,
   but replacing the hardware trust root may ultimately require the direct SWD path.

The network core has an additional NSIB/B0n trust boundary. Exact-source builds generate a fresh debug
ECDSA key because upstream does not pin `SB_CONFIG_SECURE_BOOT_SIGNING_KEY_FILE`; the resulting network
image is not a safe OTA candidate for the sealed unit. This does not block M1: the desired behavior is
on the application core, so the network controller remains stock. See
[build-reproduction.md](build-reproduction.md).

There is also a recovery trap: the bootloader enables software downgrade prevention, while every
inspected stock image is `0.0.0+0`. The compatibility canary must keep the same boot version so the
published recovery package is not made permanently older than the installed image. Details and exact
digests are in [stock-ota-inspection.md](stock-ota-inspection.md).

This distinction is a release exception to track, not a reason to block the firmware/application work.

## Bench questions

The first read-only session must answer:

- What model, hardware revision, firmware revision, and advertised name does this exact unit report?
- Does the device expose the standard SMP service and allow an image-slot listing?
- What services, characteristics, properties, and security requirements are actually exposed?
- Is the current unit bonded, merely paired, or completely unauthenticated?
- What MTU and PHY are negotiated by the Android shell, and later by a Linux host?
- Does Idle mean PDM stopped, AAD armed, or audio still being encoded?
- How much storage is reported, and what are the current ring read/write/dropped counters?
- What survives device reboot, phone reboot, and application termination?
- Does the installed v3.0.12 unit physically follow its source-level single-tap shutdown and one-second
  long-notification behavior?
- What are the real debounce/double/hold boundaries, false-gesture rate while worn, RGB package mapping,
  minimum visible privacy brightness, and distinguishable haptic durations?

The staged firmware procedure lives in [sealed-device-plan.md](sealed-device-plan.md); the immediate
phone session is [android-read-only-probe.md](android-read-only-probe.md).
