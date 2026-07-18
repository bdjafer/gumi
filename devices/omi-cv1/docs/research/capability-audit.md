# Omi CV1 capability audit

Status: device-owned source and release audit complete; physical-unit measurements pending.

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
- qualified firmware baseline candidate: tag
  [`Omi_CV1_v3.0.20`](https://github.com/BasedHardware/omi/releases/tag/Omi_CV1_v3.0.20), commit
  `aa1133cd17139aa09cbe4883cdf51f15094b9916`;
- official OTA artifact: `Omi_CV1_OTA_v3.0.20.zip`, SHA-256
  `dfc7ea6986d9b02fe899a38afc6c9bf6fabb9cff669244fbf20a3d7abeda59da`.

The open source repository is MIT licensed. Nordic SDK modules and other dependencies retain their own
licenses.

## Capability matrix

| Capability | Component | Source/release reality | Bench work | M1 disposition |
| --- | --- | --- | --- | --- |
| Compute | nRF5340 dual-core Cortex-M33 | Application and network cores are built as a multi-image NCS 2.9.0 system | Read model, hardware revision, firmware revision, and image slots | Reuse the existing board and sysbuild definitions |
| Audio input | Two T5838 PDM microphones | 16 kHz, 16-bit interleaved capture; firmware averages both channels to mono and encodes Opus | Capture each channel separately; measure noise, clipping, channel identity, and current draw | Reuse PDM and Opus paths; preserve stereo during experiments |
| Acoustic activity | T5838 AAD support | Current configuration combines software VAD with hardware acoustic activity detection | Measure wake latency, false activation, and silence current | Optional power mechanism, never a substitute for explicit recording state |
| Button | One tactile button | Current firmware emits single/double-tap events; long press powers off | Measure debounce and gesture reliability while worn | Double-tap toggles capture; hold-to-talk reserved; power-off mapping remains an explicit decision |
| Feedback | RGB LEDs and vibration motor | LED, haptic, battery, and charging services exist | Verify visibility in daylight, haptic strength, and indicator timing | Mandatory truthful capture and fault indication |
| Motion | LSM6DS3TR-C six-axis IMU | Physical component is present. The pristine release build compiles I2C and the LSM6DSL driver, but disables the application accelerometer capability, its GATT service, and configured sampling | Identify device address and validate FIFO, interrupt, tap, and power modes | Latent capability; not on the critical audio path |
| BLE | nRF5340 radio | Active GATT data plane, 2M/Coded PHY configuration, large MTU, one connection, OTA, live audio, and offline sync | Export the exact GATT table, negotiated MTU/PHY, throughput, reconnection, range, and background behavior | Primary device-to-edge transport for M1 |
| Wi-Fi | nRF7002 companion | Present in board definitions; no qualified current application-level path to depend on; v3.0.17 release notes explicitly disabled it for improved BLE sync | Only probe after BLE M1 is stable | Out of M1 critical path |
| Offline media | NAND/SD-style storage plus firmware ring | Current v3.0.20 ring record is 444 bytes: 4-byte timestamp plus 440-byte packed Opus payload. Documentation, part description, and firmware usable ceiling disagree | Read JEDEC/device identity if exposed; measure real capacity, write endurance proxy, overwrite behavior, and power-loss recovery | Reuse the ring protocol initially; do not promise offline duration yet |
| OTA staging | External SPI NOR and MCUboot partitions | Published OTA is a signed two-image MCU Manager package. Both v3.0.20 images are `0.0.0+0` and verify against the exposed outer RSA key. A clean build reproduces the app payload but auto-generates a different inner NSIB key for the network image | Read installed image slots/hashes without uploading; then perform an application-only equal-version canary/recovery round trip | Reuse MCUboot/MCUmgr for image `0`; retain `0.0.0+0`; do not write a locally generated image `1` |
| Power | 150 mAh LiPo, charger, buck, protection | Battery and charging telemetry are exposed | Profile Idle, Recording, VoiceTurn, Syncing, and Updating separately | Battery budget becomes a release gate |
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

1. Both official v3.0.20 signatures have been verified against that exact upstream key.
2. A locally built image using the upstream build should plausibly be accepted by a stock unit over OTA.
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

The staged firmware procedure lives in [sealed-device-plan.md](sealed-device-plan.md); the immediate
phone session is [android-read-only-probe.md](android-read-only-probe.md).
