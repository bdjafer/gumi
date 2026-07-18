# Decision 0001: Android-first edge technology spine

Status: accepted direction for M1; exact tool versions and persistence implementation remain gated by
the bootstrap spike. Evidence refreshed 2026-07-18.

## Decision

Use a Kotlin-first edge spine:

- native Kotlin Android application with Jetpack Compose for `edge/shell/android`;
- Kotlin Multiplatform common modules for `edge/sdk`, `edge/runtime`, and portable Omi driver logic,
  targeting Android and Linux/JVM in M1;
- Kotlin coroutines and `Flow` for asynchronous ports and state observation;
- Nordic Android BLE Library 2.x behind the Android BLE transport port;
- Nordic Android nRF Connect Device Manager behind the firmware-update port; and
- a Linux/JVM target for the Raspberry Pi portability witness, with BlueZ remaining a later platform
  adapter.

The M1 Raspberry Pi target is JVM on Linux ARM64, not Kotlin/Native. This keeps the same runtime code
without introducing JNI/FFI into the Android critical path. Architecture ports, capability contracts,
and conformance tests—not a particular binary format—provide substrate independence.

Exact Kotlin, Android Gradle Plugin, Gradle, Compose, Nordic, and coroutine versions are pinned only
after a minimal Android + Linux/JVM workspace compiles and tests together.

## Why this direction

### Android lifecycle is native regardless of UI toolkit

Long-running BLE notification and sync work depends on Android's companion-device and service lifecycle.
Current Android guidance supports `CompanionDeviceService` and a `connectedDevice` foreground service
for long-running BLE work. Those APIs, permissions, process-restart cases, and OEM behavior must be
owned and tested natively.

Primary references:

- [Android companion-device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)
- [Android background BLE guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background)

### Android and server/JVM are stable Kotlin targets

Android and server/desktop JVM are stable Kotlin Multiplatform platforms. A common target gives the
runtime an enforceable platform-neutral source set while keeping Android and Raspberry Pi on mature
runtimes. Linux/JVM is enough to prove the replaceable edge host required by M1; native targets can be
added only when a real host requires them.

Primary reference: [Kotlin Multiplatform platform stability](https://kotlinlang.org/docs/multiplatform/supported-platforms.html).

### Maintained BLE mechanics already exist

[Nordic Android BLE Library](https://github.com/NordicSemiconductor/Android-BLE-Library) 2.11.0 provides
connection/retry, discovery, bonding, a serialized operation queue, long-packet handling, MTU/PHY,
timeouts, errors, coroutines, and notification `Flow`s. Its announced replacement Kotlin BLE library is
still described by Nordic as early-stage and not recommended for production, so M1 stays on maintained
2.x behind a replaceable port.

[Nordic Android Device Manager](https://github.com/NordicSemiconductor/Android-nRF-Connect-Device-Manager)
3.3.1 already implements MCU Manager image listing and upload for Zephyr/NCS. Gumi uses its single-image
application path and never hands the canary updater an unqualified multi-image bundle.

## Omi reuse audit

The pinned Omi v3.0.20 application contains useful recent Android behavior, but not a reusable Gumi
edge architecture:

| Unit at `aa1133cd…` | Size | Assessment |
| --- | ---: | --- |
| Five native Android BLE/service files | 2,284 lines | Behavior oracle and selective extraction only |
| `OmiBleManager.kt` | 769 lines | Raw `BluetoothGatt`, manual command queue, Flutter/Pigeon callbacks, battery persistence |
| `OmiBleForegroundService.kt` | 944 lines | Reconnect knowledge mixed with Omi batch writers, backend modes, diagnostics, and singleton state |
| `OmiDeviceConnection` | 997 Dart lines | Protocol knowledge mixed with application models, notifications, logging, and legacy formats |
| `DeviceTransport` | 28 Dart lines | Too small/generic to justify adopting Dart as the runtime boundary |
| `ring_protocol.dart` | 183 lines | Pure behavior worth porting against the checked language-neutral fixtures |

Retain or port with provenance:

- Omi UUIDs, codec values, storage/ring behavior, and edge cases;
- Android Companion Device API version handling;
- reconnect status classifications, stale-GATT rejection, and useful diagnostics;
- process/service cases as black-box acceptance scenarios; and
- pure ring parsing semantics and upstream unit cases.

Do not copy as architecture:

- the global BLE singleton;
- Flutter/Pigeon callbacks inside the transport;
- the foreground service as owner of cloud writers and product policy;
- SharedPreferences as the durable transfer ledger;
- Omi backend modes and notification semantics; or
- a brand-switch connector hierarchy.

Pinned source references:

- [Omi Android GATT manager](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/app/android/app/src/main/kotlin/com/friend/ios/ble/OmiBleManager.kt)
- [Omi Android foreground service](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/app/android/app/src/main/kotlin/com/friend/ios/ble/OmiBleForegroundService.kt)
- [Omi ring protocol parser](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/app/lib/services/devices/ring_protocol.dart)

## Alternatives

### Flutter/Dart shell and runtime

Not selected for M1. It maximizes superficial reuse of Omi UI/Dart files, but the critical Android BLE
lifecycle is already native Kotlin and the portable Omi abstractions are coupled to its application.
It also makes the headless Linux service a separate product concern. Flutter remains a possible future
shell adapter; it does not own runtime correctness.

### Rust portable runtime

Not selected for M1. Current audio is already compressed and no measured CPU, memory-safety, or native
host constraint justifies an FFI boundary through Android service lifecycle, persistence, and thousands
of small device events. Reconsider Rust if profiling, non-JVM edge targets, untrusted plugin isolation,
or hard resource budgets make it materially better.

### Kotlin/JVM without Multiplatform source sets

Viable fallback if the current Android KMP plugin blocks the bootstrap. A pure Kotlin/JVM core consumed
by Android and Linux/JVM still satisfies M1, provided forbidden-platform API checks preserve the port
boundary. The outcome matters more than forcing KMP tooling.

## Persistence is a separate decision

[SQLDelight](https://cashapp.github.io/sqldelight/) is the leading metadata-store candidate because it
supports Android, JVM, and Multiplatform while verifying schema and migrations. It is not accepted until
the spike proves:

- transaction and WAL behavior under forced process death;
- Android/Linux driver compatibility;
- encryption and key-rotation design;
- migration rollback/recovery;
- bounded metadata growth; and
- media bytes remain encrypted chunk files rather than database blobs.

## Bootstrap acceptance gate

This decision becomes fully pinned only when one small workspace proves all of the following:

1. `edge/sdk` and `edge/runtime` compile and run identical tests on Android and Linux/JVM.
2. A fake device plugin is registered without the runtime importing its package.
3. The Android BLE adapter exposes scan/connect/read/write/notify/MTU through a port with no Android type
   crossing into the Omi driver.
4. The Omi ring fixtures pass from the portable driver module.
5. The Android shell renders runtime projections and invokes commands without owning protocol state.
6. A Linux/JVM executable runs the same simulated capture, spool, restart, and resume scenario.
7. Dependency/import checks fail intentionally introduced Android, Omi, and cloud leaks.

## Revisit triggers

Reopen this decision if:

- Linux/JVM cannot meet measured Raspberry Pi power, memory, startup, or deployment constraints;
- a required edge substrate cannot host the JVM;
- iOS becomes a committed milestone and common-source compatibility is inadequate;
- BLE library behavior cannot satisfy Omi throughput/reconnect tests;
- profiling identifies a runtime hot path that warrants native code; or
- Kotlin tooling prevents deterministic multi-target builds for more than the time-boxed bootstrap.
