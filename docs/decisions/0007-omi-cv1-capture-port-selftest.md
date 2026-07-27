# Decision 0007: Omi CV1 capture-port self-test stage

Status: **implemented and offline-qualified; physical qualification pending; no physical
authorization conveyed**.

## Context

Recovery-only-0001 has booted and remained reachable on the owned sealed unit with microphone-off
status `01070123`. The portable button, capture, feedback, recording-journal, microphone, and Opus
components execute in isolation, but the owned hardware has never run Gumi's repeatable PDM/codec
start-stop path. Moving directly from recovery-only to user Recording would combine unqualified
microphone release, codec draining, privacy output, durable storage, authenticated control, and media
transport in one irreversible hardware experiment.

## Decision

Before functional Recording, use one narrowly scoped `capture-port-selftest-0001` application image to
qualify the hardware-bound privacy, microphone, and Opus lifecycle. It is a diagnostic firmware stage,
not `Recording`, `VoiceTurn`, a product control protocol, or consent evidence.

The phone explicitly arms a 15-second confirmation lease over a non-sensitive lab characteristic. A
continuous two-second device-button hold inside that lease starts one bounded attempt. Each attempt:

1. asserts a continuous red diagnostic privacy output;
2. opens a fresh Opus encoder session;
3. starts PDM through the Gumi microphone port;
4. counts and discards PCM and Opus packets for a bounded three-second interval;
5. gates new PCM, verifies PDM release, drains/closes Opus, and records exact counters;
6. removes red only after microphone-off is proven; and
7. reports pass or an explicit safe/unknown failure without exposing audio bytes.

Safe pass and safe failure may be re-armed to prove repeatability. An unknown microphone-release result
is terminal: red stays continuously asserted, capture cannot restart, and only the recovery updater may
be used.

## Construction boundary

- No audio or storage characteristic exists and no PCM/Opus byte leaves the application.
- No filesystem is mounted, formatted, read, written, renamed, or deleted.
- No product capture command, automatic capture, double-tap Recording, VoiceTurn, or remote actuator is
  exposed.
- Boot and every non-running state configure PDM without `DMIC_TRIGGER_START` and report verified-off
  truth only from the microphone port.
- Only exact application image `0` is accepted by MCU Manager. Image `1`, settings erase, generic file
  access, shell, and automatic retry remain absent.
- The lab status wire contains only phase, failure, flags, attempt number, PCM block/sample counts, Opus
  packet count, and discarded-sample count.
- A failed privacy output prevents PDM acquisition. A failed PDM release never turns red off.

## Required offline acceptance

- Pure transition tests cover arming, lease expiry, confirmation, successful sequencing, stale
  completion, codec/microphone/privacy failures, counter thresholds, repeatability, and terminal
  microphone-unknown lockout.
- The exact Omi v3.0.12/NCS v2.9.0 build links only the reviewed self-test, button, microphone, codec,
  RGB privacy output, status, Device Information, and constrained image-0 recovery sources. Haptic is
  absent.
- The bundled upstream Opus encoder runs through the existing QEMU lifecycle gate.
- Build-map and artifact audits prove absence of stock capture, storage, settings, audio transport,
  destructive management, network image, and automatic microphone-start symbols.
- The updater APK packages only the exact self-test target and an exact recovery-only return target for
  this stage.

The final exact offline target build passed these gates on 2026-07-22. Its signed application-image-0
file is 178,100 bytes with SHA-256
`8f0d0fc35c4d3c56e8f94d528a0b56c0d4af7b44b7bdad2b2fa4f2963e2e3d0e`; the MCUboot image digest is
`e97fd653a66dce18a7dbd071bc9b21c4fdae4e229de5d245e76ed213c2ca4862`. This receipt does not authorize
or claim physical execution.

## Required physical acceptance

With only non-sensitive test sound present:

- boot reports microphone verified off and no red privacy output;
- arming alone never starts PDM;
- short, double, late, and released-before-deadline gestures never start the attempt;
- the accepted two-second hold produces continuous red before PDM begins;
- at least two seconds of PCM and Opus activity are counted without exporting bytes;
- normal completion returns to verified-off, closes the codec, removes red, and reports zero terminal
  codec error;
- at least three consecutive attempts pass without reboot, stale callback, dropped lifecycle truth, or
  residual red/microphone activity; and
- exact recovery-only restoration remains separately reviewable and succeeds if the self-test reports
  any unexpected state.

Independent electrical/acoustic microphone-off and calibrated LED visibility remain later HIL gates;
software counters and visible red are not promoted beyond what they prove.

## Residual risk

The stock-compatible outer signing key and development MCU Manager path remain public and unpaired.
Image `1`, secondary-slot truth, bootloader recovery, power-loss interruption, and hardware-root trust
remain unproved. Without SWD/J-Link, failure before BLE/SMP is reachable may still be unrecoverable.
This stage is restricted to the single owned unit in controlled physical proximity.
