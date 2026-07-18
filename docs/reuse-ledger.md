# Reuse ledger

Rule: reuse code when its boundary, license, quality, and lifecycle fit Gumi. Do not preserve a bad
boundary merely because code already exists.

## Adopt as the starting point

| Asset | Decision | What it avoids | Conditions |
| --- | --- | --- | --- |
| Omi CV1 firmware at `Omi_CV1_v3.0.20` | Fork/import the qualified firmware baseline | Rewriting board support, PDM, BLE services, Opus, storage, power, button, haptic, and dual-core build | MIT provenance; qualify every retained subsystem; do not start from moving `main` |
| Zephyr + Nordic nRF Connect SDK 2.9.0 | Retain the upstream platform initially | RTOS, drivers, Bluetooth controller/host, device tree, power primitives | Pin exact SDK/toolchain/container; review Nordic license terms per module |
| MCUboot + MCU Manager/SMP | Retain OTA format and transport | Custom bootloader/update protocol | Stock root-key limitation remains; use Gumi inner signing until root can be replaced |
| Kotlin Multiplatform + coroutines/Flow | Use for the M1 edge SDK/runtime and portable Omi driver logic | Separate Android and Raspberry Pi cores; new event/concurrency primitives | Target Android and Linux/JVM first; pin exact versions only after the dual-target bootstrap |
| Native Kotlin + Jetpack Compose | Use for the Android shell | Flutter bridge and duplicated lifecycle ownership | UI consumes shell projections/use cases; BLE, durability, and cloud correctness stay outside UI |
| [Nordic Android BLE Library `2.11.0`](https://github.com/NordicSemiconductor/Android-BLE-Library) | Use 2.x behind the Android BLE transport port | Handwritten GATT queue, retries, MTU/PHY, timeout, packet merge/split, Flow wrappers | Qualify Omi throughput/reconnect behavior; do not adopt Nordic's early-stage replacement Kotlin BLE library yet |
| [Nordic Android Device Manager `3.3.1`](https://github.com/NordicSemiconductor/Android-nRF-Connect-Device-Manager) | Use behind the Android firmware-update port | BLE SMP framing, image state/list commands, upload windowing, confirm/reset state machine | Call the single-image `start(image, settings)` path for image `0`, use `CONFIRM_ONLY`, compare image `1` before/after, and do not pass a multi-image ZIP to the canary |
| Upstream firmware CI scripts and release manifest shape | Adapt into Gumi CI | Reconstructing a complex multi-image sysbuild/package pipeline | Remove publishing assumptions and upstream secrets; produce reproducible artifacts and SBOM/provenance |
| libopus and existing Omi audio frame format | Retain for M1 | Codec implementation and immediate device/app incompatibility | Validate bitrate, quality, loss behavior, licensing, and two-channel experiments |
| Omi ring protocol plus unit fixtures | Extract/port with device-owned [`ring/v1` fixtures](../devices/omi-cv1/protocols/ring/v1/README.md) | Reverse-engineering offline record framing, sequence resume, boundary bugs | Keep wire compatibility first; Omi firmware and its edge driver must consume the same fixtures |
| Selected Omi Android lifecycle behavior | Port as tests and small helpers, not as the transport architecture | Rediscovering Companion API version handling, stale-GATT rejection, reconnect classifications, and useful diagnostics | The five BLE/service files are 2,284 lines and mix raw GATT, Flutter/Pigeon, singleton state, backend writers, and SharedPreferences; Nordic owns generic mechanics |
| Omi Dart device/ring behavior | Use as extraction input and protocol oracle | Rediscovering UUIDs, codec/storage behavior, and upstream edge cases | Port pure behavior against Gumi fixtures; the 997-line Omi connector is application-coupled and its generic `DeviceTransport` is only 28 lines |
| Astrale kernel client/domain SDK, identity, grants, delegation | Use as cloud control/semantic substrate | New identity/authorization/graph platform | Reuse existing domains only when they own the vocabulary; declare dependencies and explicit authorization |

Omi source is [MIT licensed](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/LICENSE).

## Adopt conditionally

| Candidate | Current evidence | Decision gate |
| --- | --- | --- |
| [SQLDelight](https://cashapp.github.io/sqldelight/) + SQLite WAL | Typesafe schema/migration support across Android, JVM, and Multiplatform | Forced-process-death durability, Android/Linux drivers, encryption/key rotation, migration recovery, packaging/license |
| Omi's native background audio writers | Contains hard-won mobile lifecycle logic | Extract only after separating durable spool semantics from Omi backend uploads and validating OS-policy behavior |
| Flutter mobile shell / `mcumgr_flutter` | Reuses more Omi Dart/UI integration | Not selected for M1; reconsider only for a later shell host, never as owner of runtime invariants |
| Dart portable edge core | Can compile AOT for Linux ARM64 and reuse some protocol syntax | Not selected for M1; reconsider only if a future Dart host creates measured net value |
| Rust portable edge core | Strong native/FFI and constrained-host story | Not selected for M1; reconsider for a non-JVM host, isolation requirement, or measured resource/performance problem |
| Omi iOS code and Nordic iOS Device Manager | Upstream implementations and maintained updater exist | iOS is not in M1; audit if it becomes a committed edge host |
| SQLCipher or platform file encryption | Mature at-rest protection options | Verify packaging, licensing, key rotation, crash recovery, and mobile background constraints |
| CBOR/zcbor for new device control messages | Already used by MCU Manager in the firmware ecosystem | Compare with fixed binary layouts; require shared schema/golden fixtures and bounded decoding |
| Existing Astrale AI Gateway catalog | It describes model capabilities including audio input but currently exposes no transcription callable | Reuse catalog/provider routing if a real callable lands; do not model transcription as delivered merely from catalog data |

## Do not reuse as architecture

- The complete Omi mobile application or its UI/business/backend state model.
- The Omi backend's user, memory, conversation, and subscription semantics.
- Brand-switch factories as the Gumi device abstraction.
- Legacy Nordic Secure DFU for CV1; CV1 uses MCU Manager.
- Raw high-volume audio transfer through Astrale graph properties or ordinary JSON call payloads.
- Public upstream signing material as Gumi's security authority.
- A higher MCUboot version on the sealed compatibility canary; it can lock out the equal-version stock
  recovery bundle under downgrade prevention.
- A clean-build Omi network image on the sealed unit. Upstream generates a new NSIB key when no network
  signing key is configured; the first canary is application image `0` only.
- Documentation claims that conflict with source, release artifacts, or the owned unit.

## Extraction requirements

Every imported or ported unit records:

- upstream repository, commit/tag, path, and license;
- whether it is copied, modified, generated, or behaviorally reimplemented;
- retained upstream tests and new Gumi tests;
- local owner and update policy; and
- deviations that make a future upstream sync non-mechanical.

The preferred proof of reuse is executable: byte fixtures, protocol conformance tests, hardware-in-loop
tests, and reproducible builds—not a prose claim that two implementations are similar.

Android is the first extraction and hardware-in-loop target. The initial order is edge SDK/runtime
bootstrap, Nordic BLE port, CV1 driver/ring fixtures, Companion/foreground lifecycle, and MCU Manager
OTA. The source audit and rationale are recorded in
[Decision 0001](decisions/0001-android-edge-stack.md).
