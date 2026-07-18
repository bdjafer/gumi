# Gumi

Gumi is an edge-to-cloud operating architecture for connecting physical devices to Astrale OS.

The first supported substrate is the Omi Consumer Version 1 pendant. The first edge host is a mobile
phone. Neither is the permanent architectural center: an Omi can be replaced by another sensor or
actuator, and the mobile host can be replaced by a Raspberry Pi or another local computer.

## First milestone

M1 is a complete, secure capture and voice-action system:

- custom Omi firmware with explicit recording and push-to-talk behavior;
- a reusable local edge runtime composed by an Android-first applicative shell;
- live and offline-resumable audio transfer;
- authenticated media ingest and object storage;
- Astrale-owned recording, transcript, conversation, and action semantics;
- transcription and realtime AI actions with results returned to the edge shell; and
- recovery, privacy, observability, and end-to-end qualification.

M1 is not a file-upload demo and not a thin reskin of the Omi application.

## Current phase

The repository now combines the governing specifications with its first executable edge spine. The
portable SDK/runtime and Omi driver compile and test on Android and JVM, the checked ring fixtures run
against the Kotlin codec, a Linux witness executes, and the Android shell has discovered and completed
a read-only GATT inventory of the owned Omi. Streaming, durable capture, firmware mutation, cloud, and
live Astrale gates remain explicitly open:

- [Omi CV1 capability audit](devices/omi-cv1/docs/research/capability-audit.md)
- [Omi CV1 component and wiring map](devices/omi-cv1/docs/research/component-map.md)
- [Sealed-device firmware plan](devices/omi-cv1/docs/research/sealed-device-plan.md)
- [Stock v3.0.20 OTA inspection](devices/omi-cv1/docs/research/stock-ota-inspection.md)
- [Exact-source firmware build reproduction](devices/omi-cv1/docs/research/build-reproduction.md)
- [Android read-only probe](devices/omi-cv1/docs/research/android-read-only-probe.md)
- [Stock BLE threat model](devices/omi-cv1/docs/security/ble-threat-model.md)
- [Owned-device inventory](devices/omi-cv1/docs/research/device-inventory.md)
- [M1 system contract](docs/specs/m1-system-contract.md)
- [Gated execution roadmap](docs/roadmap.md)
- [Repository and dependency layout](docs/architecture/repository-layout.md)
- [Edge runtime and plugin contract](docs/specs/edge-runtime-contract.md)
- [Android-first edge technology decision](docs/decisions/0001-android-edge-stack.md)
- [Verified edge development bootstrap](docs/development/bootstrap.md)
- [Reuse ledger](docs/reuse-ledger.md)
- [Omi upstream policy](devices/omi-cv1/UPSTREAM.md)
- [OTA protocol evidence](devices/omi-cv1/protocols/ota/v1/README.md)
- [Offline ring protocol fixtures](devices/omi-cv1/protocols/ring/v1/README.md)
- [Source-declared GATT profile](devices/omi-cv1/protocols/gatt/v3.0.20/README.md)
- [Owned-unit v3.0.12 GATT profile](devices/omi-cv1/protocols/gatt/v3.0.12/README.md)

## Governing principles

1. Model devices as compositions of versioned capabilities, not as brand-specific base classes.
2. Keep hard realtime behavior, physical safety, and truthful privacy indication local.
3. Separate control and semantic state from high-volume binary media transfer.
4. Make every transfer resumable and every externally visible effect idempotent.
5. Reuse proven code and protocols when they fit the boundary; retain provenance and tests.
6. Treat source claims, release claims, and measurements from a physical unit as different evidence.
7. Never claim a capability is delivered until the real device-to-Astrale path has been exercised.
