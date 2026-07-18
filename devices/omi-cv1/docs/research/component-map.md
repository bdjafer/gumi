# Omi CV1 hardware component and wiring map

Status: BOM, board definition, and production-source map; exact population and behavior on the owned
sealed unit remain a bench question.

This document separates a chip's theoretical capability from what the CV1 board wires and what the
v3.0.20 application actually enables. A powerful component on the BOM is not automatically a usable
Gumi capability.

## Compute, clocks, and memory

| Part | Board/source connection | Current usable reality |
| --- | --- | --- |
| Nordic `nRF5340-CLAA` U1 | Dual-core application/network SoC; 32 MHz and 32.768 kHz crystals; Zephyr board targets `nrf5340/cpuapp` and `cpunet` | Primary compute/radio. App-core internal flash is partitioned into 64 KiB MCUboot plus a 960 KiB primary image region; network core has boot, primary, secondary, and storage partitions |
| Puya `P25Q16SH-UXH-IR` U12 | 16 Mbit SPI NOR on SPI3 CS1, up to 10 MHz in the board definition | 2 MiB OTA staging: 960 KiB application secondary, 256 KiB network secondary, and 832 KiB remaining external-flash partition |
| `CSNP4GCR01-DPW` U7 | SD NAND on SPI3 CS0, up to 24 MHz, separately power-gated | Offline raw audio ring. The BOM's `4G` part naming is ambiguous without a reliable datasheet/device read; firmware discovers sector count at runtime, so no tracked capacity/duration claim is yet valid |
| Internal RTC | nRF RTC enabled; phone can synchronize epoch time | Ring records have a four-byte epoch timestamp or zero when RTC is invalid; no independent GNSS/network time source |

The exact partition source is
[`pm_static.yml`](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/boards/omi/pm_static.yml).
The SD ring reserves 64 metadata sectors, writes 32-sector batches, stores 36 records per batch, and
derives capacity from the device-reported 512-byte sector count. Each logical record is 444 bytes, while
the batch layout consumes about 455 bytes per record before device-level overhead.

## Physical inputs

| Input | Wiring | v3.0.20 behavior | Gumi opportunity/limit |
| --- | --- | --- | --- |
| Two TDK/InvenSense `T5838` microphones | Shared PDM clock P1.01 and stereo data P1.00; 1.8 V rail enable P1.04; threshold P1.05; acoustic wake P1.02 | Captures interleaved 16 kHz, 16-bit, two-channel blocks, then averages L/R to mono. Encodes 20 ms Opus CELT frames at target 32 kbit/s VBR, complexity 3, no DTX/FEC | Preserve channels for measurement before deciding mono; explicit Idle must stop PDM. Hardware acoustic activity can wake from low-power silence but must not redefine recording consent |
| ST `LSM6DS3TR-C` U5 | I2C address `0x6a`, IRQ P1.13, switched supply enable P1.12 | The pristine release build compiles I2C, the LSM6DSL driver, and timestamp helper, but disables `CONFIG_OMI_ENABLE_ACCELEROMETER`, the motion GATT service, and configured sampling | Latent 3-axis accelerometer + 3-axis gyroscope. Tap/FIFO/orientation remain unqualified and out of M1's critical path |
| Tactile button K2 | P0.26, pull-up, active-low interrupt | Source recognizes tap gestures; current long hold owns power-off | Only physical general-purpose user input. Gesture thresholds/conflicts need hardware-in-loop qualification |
| Battery voltage and charge state | SAADC AIN0 through switched divider; charger status P0.07 | Battery percentage is a voltage-profile estimate with median/EMA filtering, not a coulomb counter; charge is a binary GPIO state | Expose voltage, estimated percent, charge state, and uncertainty separately |

There is no identified ambient-light, proximity, temperature, barometer, magnetometer, camera, touch
surface, GPS/GNSS, cellular, UWB, or NFC sensor path on the current consumer board. The IMU may expose
temperature internally, but the production configuration disables its temperature option and it is not
an ambient-temperature instrument.

## Physical outputs

| Output | Wiring/component | Control reality |
| --- | --- | --- |
| RGB indication | BOM lists two `MHPA0606RGBDT` packages; one three-channel PWM mapping uses P0.20/P0.21/P0.22 | Color and dimming are controllable, but independent control of both physical packages is not shown by the board definition; treat them as one logical indicator until measured |
| Haptic | `LBM0525A4123F`, 3 V, 85 mA, nominal 10,000 rpm; GPIO P0.25 | Simple switched vibration, not a precision waveform/LRA driver |
| Power latch/control | GPIO P0.05 active-low plus charger/protection/power ICs | Firmware can initiate shutdown; reset/recovery behavior must be measured |

No speaker or general motor/actuator connector is identified. Generic speaker code is disabled and does
not make audio output a CV1 feature.

## Radios and wired access

| Interface | Physical/source reality | M1 decision |
| --- | --- | --- |
| Bluetooth LE | nRF5340 network core, HCI IPC, source requests large MTU and 2M/Coded PHY behavior; one active connection | Primary CV1 link. Actual services, security, MTU, PHY, range, and background throughput come from the Android probe/bench |
| Wi-Fi 6 | Nordic `nRF7002-CEAA-R7`, QSPI at 24 MHz, 2.4/5 GHz board includes, radio coexistence lines and shared RF path | Real hardware, but current release history disabled the application Wi-Fi path to improve BLE sync. Do not put Wi-Fi on M1's critical path |
| UART | P0.03 TX, P0.02 RX at 115200 in device tree | Development console signal exists in source; inaccessible under the sealed/no-fixture constraint unless exposed through external contacts |
| Pogo/test contacts | Six mainboard pogo pins plus two charger contacts in the BOM | Likely power/debug/production access, but exact accessible mapping is not claimed without schematic/fixture proof |
| Charger dock | Contact ring, pogo contacts, magnets, charger board | Power only in the qualified product path; no USB data path established |

Production config disables USB and leaves NFC future options commented. There is no Ethernet or wired
host interface for the sealed M1 path.

## Power tree

- 3.7 V, 150 mAh single-cell LiPo.
- TI `BQ25101` linear charger and `GLF73910` battery protection.
- TI `TPS628438` 600 mA buck, 3.3 V and 1.8 V LDOs, and two `TPS22916` load switches.
- 10 kOhm NTC component in the BOM; exposed thermal protection/telemetry behavior is not yet confirmed.
- Firmware hard-shuts down below a measured 3500 mV threshold and uses separate empirical
  charge/discharge voltage-to-percent tables.

There is no identified fuel-gauge IC, secure element, TPM, or user-replaceable battery. Power budgets
must be measured for true Idle, AAD-armed Idle, Recording, VoiceTurn, BLE sync, Wi-Fi experiments, and
OTA separately.

## Security-relevant hardware reality

The nRF5340 has silicon security mechanisms, but the product-level chain matters more than the feature
list:

- MCUboot validates both update images with an RSA key embedded by the installed bootloader.
- The corresponding private key is public in upstream source.
- The network image is additionally checked by B0n/NSIB against a provisioned ECDSA public-key hash;
  clean builds auto-generate a different key and are not network-OTA-compatible evidence.
- OTA does not replace that bootloader/root.
- No separate secure element is identified in the BOM.
- Pairing/encryption permissions on the current GATT surface remain a physical-probe question.

Therefore the sealed unit can plausibly run a functionally custom **application image** while retaining
the stock network image, but it cannot be described as having a Gumi-owned hardware root of trust. See
[stock-ota-inspection.md](stock-ota-inspection.md) and
[build-reproduction.md](build-reproduction.md).

## Primary project sources

- [consumer BOM](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/hardware/consumer/bom/omi-bom.csv)
- [CPU application board definition](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/boards/omi/omi_nrf5340_cpuapp.dts)
- [pin control](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/boards/omi/omi-pinctrl.dtsi)
- [network-core board definition](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/boards/omi/omi_nrf5340_cpunet.dts)
- [production configuration](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/omi/omi.conf)
- [microphone and AAD implementation](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/omi/src/mic.c)
- [codec configuration](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/omi/src/lib/core/config.h)
- [battery implementation](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/omi/src/battery.c)
- [raw storage ring](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/omi/src/sd_card.c)
