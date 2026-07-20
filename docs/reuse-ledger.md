# Reuse ledger

Rule: reuse code when its boundary, license, quality, and lifecycle fit Gumi. Do not preserve a bad
boundary merely because code already exists.

## Adopt as the starting point

| Asset | Decision | What it avoids | Conditions |
| --- | --- | --- | --- |
| Omi CV1 firmware at `Omi_CV1_v3.0.20` | Fork/import the qualified firmware baseline | Rewriting board support, PDM, BLE services, Opus, storage, power, button, haptic, and dual-core build | MIT provenance; qualify every retained subsystem; do not start from moving `main` |
| Zephyr + Nordic nRF Connect SDK 2.9.0 | Retain the upstream platform initially | RTOS, drivers, Bluetooth controller/host, device tree, power primitives | Pin exact SDK/toolchain/container; review Nordic license terms per module |
| MCUboot + MCU Manager/SMP | Retain OTA format and transport | Custom bootloader/update protocol | Stock root-key limitation remains; use Gumi inner signing until root can be replaced |
| Kotlin Multiplatform `2.4.10` + coroutines/Flow `1.11.0` | Use for the M1 edge SDK/runtime, portable operational owner/bridge, and Omi driver logic | Separate Android and Raspberry Pi cores; new event/concurrency primitives | Android and Linux/JVM bootstrap plus the one-device operational runtime/shell bridge are proven locally; preserve common-source and import checks as real ports land |
| Native Kotlin + Jetpack Compose BOM `2026.06.01` | Use for the Android shell | Flutter bridge and duplicated lifecycle ownership | Diagnostic APK and service scaffold build; the operational bridge remains uncomposed, and UI must continue consuming projections/use cases while BLE, durability, and cloud correctness stay outside UI |
| [Nordic Android BLE Library `2.11.0`](https://github.com/NordicSemiconductor/Android-BLE-Library) | Use 2.x behind the Android BLE transport port | Handwritten GATT queue, retries, MTU/PHY, timeout, packet merge/split, Flow wrappers | Qualify Omi throughput/reconnect behavior; do not adopt Nordic's early-stage replacement Kotlin BLE library yet |
| [Nordic Android Device Manager `2.8.0`](https://github.com/NordicSemiconductor/Android-nRF-Connect-Device-Manager/tree/559724446b113f46fc60324df4dfd1160faa2a02) | Use behind separate Android image-inspection and guarded firmware-update ports | BLE SMP framing, image state/list commands, upload windowing, confirm/reset state machine | The diagnostic port exposes only image-list and discloses transient CCCD/request writes. The dedicated flash lab calls only the explicit single-image path for image `0`, packages two exact application binaries, rechecks state immediately before upload, retains reset disconnect as outcome-unknown, and never accepts a multi-image ZIP. Under [Decision 0005](decisions/0005-omi-cv1-network-unobserved-ota.md), complete absence of image `1` remains explicit; any visible network evidence must match exact stock |
| Upstream firmware CI scripts and release manifest shape | Adapt into Gumi CI | Reconstructing a complex multi-image sysbuild/package pipeline | Remove publishing assumptions and upstream secrets; produce reproducible artifacts and SBOM/provenance |
| libopus and existing Omi audio frame format | Retain for M1; decode the stock 3-byte BLE envelope at the Omi driver boundary | Codec implementation and immediate device/app incompatibility | Source proves ID 21, 16 kHz mono/20 ms, 160-byte maximum and envelope shape; the bounded owned-unit witness qualified 532 frames over 9,999 ms at MTU 498 with no gaps, while bitrate/quality/loss, playback, and two-channel experiments remain gates |
| Ogg Opus container libraries | Keep the current portable profile small; use independent libraries/tools as conformance oracles rather than importing a host-specific muxer into `edge/runtime` | Re-learning Ogg/Opus rules without surrendering deterministic sequence/page identities and restart state | AndroidX Media3 [`OggMuxer`](https://developer.android.com/reference/androidx/media3/muxer/OggMuxer) is Android-specific and [VorbisJava](https://github.com/Gagravarr/VorbisJava) is JVM-specific; the candidate pure-Kotlin muxer must remain byte-golden, independently parsed, bounded, and replaceable if packet grouping or metadata grows |
| Omi ring protocol plus unit fixtures | Ported into `devices/omi-cv1/edge-driver` with the device-owned [`ring/v1` fixtures](../devices/omi-cv1/protocols/ring/v1/README.md) | Reverse-engineering offline record framing, sequence resume, boundary bugs | All 14 current cases pass; retain pinned provenance and make future firmware and every edge driver consume the same fixtures |
| Selected Omi Android lifecycle behavior | Port as tests and small helpers, not as the transport architecture | Rediscovering Companion API version handling, stale-GATT rejection, reconnect classifications, and useful diagnostics | The five BLE/service files are 2,284 lines and mix raw GATT, Flutter/Pigeon, singleton state, backend writers, and SharedPreferences; [Decision 0004](decisions/0004-android-companion-association.md) owns Gumi's exact chooser/API branches and forbids treating platform association as identity |
| Omi Dart device/ring behavior | Use as extraction input and protocol oracle | Rediscovering UUIDs, codec/storage behavior, and upstream edge cases | Port pure behavior against Gumi fixtures; the 997-line Omi connector is application-coupled and its generic `DeviceTransport` is only 28 lines |
| Astrale kernel client/domain SDK, identity, grants, delegation | Use as cloud control/semantic substrate | New identity/authorization/graph platform | Reuse existing domains only when they own the vocabulary; declare dependencies and explicit authorization |
| Official `create-astrale-domain@0.2.13` scaffold | Used for `cloud/apps/gumi` at `gumi.astrale.ai` | Hand-assembling deploy, schema, simulation, client, and adapter plumbing | Keep the app self-contained; every callable still needs a caller gate and every effect a stable step |
| OpenAPI 3.1 + JSON Schema 2020-12 + `openapi-typescript` `7.13.0` | Use for cloud application-owned HTTP contracts and generated TypeScript consumers | Hand-maintained duplicate DTOs and a root contracts grab bag | Publisher schemas stay app-local; generated consumers prove freshness, and executable boundary tests still enforce semantics that structural types cannot express |
| [OkHttp `5.3.0`](https://github.com/square/okhttp) + coroutines and MockWebServer | Used behind `edge/adapters/cloud/media-ingest` for Android/JVM HTTP and loopback conformance | A handwritten HTTP/TLS stack, callback-to-coroutine bridge, and brittle fake request recorder | The local candidate uses a dedicated client with no inherited hooks, redirects, cookies, cache, authenticators, or transparent retry; Gumi still owns the 1 MiB preflight cap, canonical descriptor, strict string ACKs, exact problem content type, credentials, and retry fences. Android product composition and production TLS remain open |
| Android Keystore + SQLite WAL + app-private immutable files | Used by the Android spool implementation candidate behind portable ports | A new crypto primitive, database abstraction in the runtime, or plaintext media/metadata persistence | AES-GCM envelope and separate HMAC locator/revision keys, encrypted strict snapshot, no-backup payloads, exclusive open, startup reconciliation, and the operational storage adapter pass local tests. The original five primitive cases pass on an API 36 emulator; two newer cancellation/operational-lease cases are compile-only. M1 has one process-global spool with edge-host-global backlog; Motorola/OEM, death/reboot, migration, durable rotation, device attribution, and rollback-scope qualification remain open |
| `astrale-ai/lab` `prod` at `89dd56d4fa3a10915d56862314300666e005dee5` | Mine protocol/provider experiments only | Rediscovering 16 kHz mono PCM, Silero VAD, SRT, and ElevenLabs webhook shapes | Do not port its public `uid` ingest, time-derived IDs, GCS-as-queue, fail-open webhook, post-response goroutine, or broad IAM boundary |

Omi source is [MIT licensed](https://github.com/BasedHardware/omi/blob/1c19526cacb8a6100e8060b203c02963882281cf/LICENSE).

## Adopt conditionally

| Candidate | Current evidence | Decision gate |
| --- | --- | --- |
| [Room KMP](https://developer.android.com/kotlin/multiplatform/room) + the current [SQLCipher Android binding](https://github.com/sqlcipher/sqlcipher-android) | Not selected by the current Android candidate; direct SQLite stores only an already-encrypted snapshot | Reconsider only if a migration/packaging/performance study shows clear value and the full forced-death, key, migration, native-library, and attribution burden passes [Decision 0002](decisions/0002-durable-spool-storage.md) |
| [SQLDelight](https://cashapp.github.io/sqldelight/) + SQLite WAL | Typesafe SQL and migration support across Android, JVM, and Multiplatform without coupling the runtime port to Room | Adopt only if qualified encrypted Android/Linux drivers reduce total complexity; forced-death durability, key rotation, migration recovery, packaging/license |
| Omi's native background audio writers | Contains hard-won mobile lifecycle logic | Extract only after separating durable spool semantics from Omi backend uploads and validating OS-policy behavior |
| Flutter mobile shell / `mcumgr_flutter` | Reuses more Omi Dart/UI integration | Not selected for M1; reconsider only for a later shell host, never as owner of runtime invariants |
| Dart portable edge core | Can compile AOT for Linux ARM64 and reuse some protocol syntax | Not selected for M1; reconsider only if a future Dart host creates measured net value |
| Rust portable edge core | Strong native/FFI and constrained-host story | Not selected for M1; reconsider for a non-JVM host, isolation requirement, or measured resource/performance problem |
| Omi iOS code and Nordic iOS Device Manager | Upstream implementations and maintained updater exist | iOS is not in M1; audit if it becomes a committed edge host |
| Durable multi-version Android spool key rotation | The envelope reader is versioned, but production writing is fixed to key version 1 and exposes no rotation operation | Adopt only after a persisted active-key policy and interruption-safe protocol prove old reads, new writes, recovery, and rollback behavior under [Decision 0002](decisions/0002-durable-spool-storage.md) |
| CBOR/zcbor for new device control messages | Already used by MCU Manager in the firmware ecosystem | Compare with fixed binary layouts; require shared schema/golden fixtures and bounded decoding |
| Existing Astrale AI Gateway catalog | It describes model capabilities including audio input but currently exposes no transcription callable | Reuse catalog/provider routing if a real callable lands; do not model transcription as delivered merely from catalog data |

## Do not reuse as architecture

- The complete Omi mobile application or its UI/business/backend state model.
- The three legacy `astrale-ai/lab` Omi Go services as Gumi's cloud architecture or wire contract.
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

Android is the first extraction and hardware-in-loop target. The edge SDK/runtime, one-device
operational runtime, generation-fenced shell bridge, Nordic BLE port, CV1 driver/ring fixtures,
foreground-service scaffold, process-global encrypted-spool adapter, and media-ingest chunk adapter now
exist locally. Android product composition still requires one process owner with one foreground lease,
one spool, and a `DeviceId`-keyed runtime registry/router; production binding/endpoint resolution,
durable stop, a refresh scheduler, Companion association, physical qualification, capture/media/cloud
ownership, and MCU Manager OTA mutation remain gates. The source audit and rationale are recorded in
[Decision 0001](decisions/0001-android-edge-stack.md).
