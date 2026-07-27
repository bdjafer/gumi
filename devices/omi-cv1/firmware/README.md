# Omi CV1 firmware workspace

Gumi customizes the exact application source installed on the owned sealed unit without vendoring a
moving Omi monorepo snapshot or importing unrelated mobile/backend code.

## Current shape

- [`upstream.lock.json`](upstream.lock.json) pins the Omi release, source commit, source-file digests,
  NCS/Zephyr/MCUboot revisions, and immutable Nordic toolchain image.
- [`patches/0001-canary-identity.patch`](patches/0001-canary-identity.patch) is the first deliberately
  tiny application-only delta.
- [`patches/0002-recovery-only.patch`](patches/0002-recovery-only.patch) links only the recovery
  supervisor, PDM-off adapter, BLE/SMP transport, and recovery entry point.
- [`patches/0003-capture-port-selftest.patch`](patches/0003-capture-port-selftest.patch) links the
  bounded, media-free privacy/PDM/Opus hardware qualification stage.
- [`patches/0004-functional-recording.patch`](patches/0004-functional-recording.patch) links the
  first complete device-local encrypted recording composition.
- [`patches/0005-recording-root-provisioner.patch`](patches/0005-recording-root-provisioner.patch)
  links the one-shot MEXT-only provisioning composition.
- [`patches/0006-functional-recording-v0005.patch`](patches/0006-functional-recording-v0005.patch)
  adds the exact SPI-SD power-management compatibility policy proven necessary by v0004.
- [`patches/0007-functional-recording-v0006.patch`](patches/0007-functional-recording-v0006.patch)
  adds the nRF5340 dual-core boot/reset boundary proven necessary by v0005.
- [`patches/0008-legacy-storage-reclaimer.patch`](patches/0008-legacy-storage-reclaimer.patch)
  adds the one-path, exact-type, exact-size legacy recording reclaimer.
- [`patches/0009-functional-recording-v0007.patch`](patches/0009-functional-recording-v0007.patch)
  refreshes capacity and reports full storage before attempting directory creation.
- [`scripts/materialize.sh`](scripts/materialize.sh) creates a detached exact-source worktree and
  applies the overlay after checking every pin.
- [`scripts/build-application.sh`](scripts/build-application.sh) builds that materialized application
  in the canonical NCS v2.9.0 container while using an external NCS workspace.
- [`releases/canary-0001.json`](releases/canary-0001.json) pins the one exact signed application output
  used for the completed compatibility round trip.
- [`releases/recovery-only-0001.json`](releases/recovery-only-0001.json) pins the current exact signed
  recovery-only application and its fail-closed evidence contract.
- [`releases/capture-port-selftest-0001.json`](releases/capture-port-selftest-0001.json) pins the
  exact signed capture-port qualification application, its source lineage, and its recovery return.
- [`releases/functional-recording-0001.json`](releases/functional-recording-0001.json) pins the
  historical first local-recording image retained only for exact rescue evidence.
- [`releases/recording-root-provisioner-0001.json`](releases/recording-root-provisioner-0001.json)
  pins the watchdog-hardened, status-only MEXT provisioning image.
- [`releases/functional-recording-0003.json`](releases/functional-recording-0003.json) pins the
  historical watchdog/reset-hardened image whose physical boot exposed the allocator-stub failure.
- [`releases/functional-recording-0004.json`](releases/functional-recording-0004.json) pins the
  historical allocator-repaired application whose physical boot exposed the SPI-SD PM boundary.
- [`releases/functional-recording-0005.json`](releases/functional-recording-0005.json) pins the
  storage-PM-repaired application whose physical recording lifecycle passed before its reset lockup.
- [`releases/functional-recording-0006.json`](releases/functional-recording-0006.json) pins the
  physically installed dual-core-reset repair and recovery return.
- [`releases/legacy-storage-reclaimer-0001.json`](releases/legacy-storage-reclaimer-0001.json) preserves
  the superseded image and the physical byte-zero OTA failure that makes SWD its only recovery.
- [`releases/legacy-storage-reclaimer-0002.json`](releases/legacy-storage-reclaimer-0002.json) pins
  the OTA-handoff-repaired one-path reclaimer and its status-only evidence contract.
- [`releases/functional-recording-0007.json`](releases/functional-recording-0007.json) pins the
  current post-reclaim functional candidate.
- [`scripts/qualify-application.sh`](scripts/qualify-application.sh) independently checks that manifest
  and its RSA-PSS signature in a network-disabled, digest-pinned Nordic container.

This overlay is an interim development form. Before Gumi firmware becomes an independently released
product, import `omi/firmware` as a history-preserving subtree under this device capsule and retain the
upstream MIT notice. The patch-based lane keeps the current dirty monorepo safe and makes the exact
first delta reviewable before that import.

## Canary 0001 scope

The canary intentionally changes only observable identity:

1. it adds Device Information software revision `gumi-canary-0001`; and
2. it replaces the stock single blue boot pulse with three short magenta pulses.

It does **not** change microphone startup, audio, storage, button behavior, GATT permissions, partition
layout, MCUboot version, update policy, network firmware, or signing compatibility. This is not the
target Gumi human-I/O firmware; it exists solely to prove application-image-`0` update, boot,
reconnection, identification, and stock recovery.

No locally generated `ipc_radio.bin`, complete OTA ZIP, merged HEX, or network-core artifact is a
device candidate. The historical exact canary application is `228724` bytes, has file SHA-256
`65e4ae91637e6aa576f6d3f8286db33bc9bd36ece8ece1aaa6b6aa5c5b204f6d`, and has MCUboot image hash
`d3b3c74c10c4fa110763ae5523d0aeaa20b9f815b8674167486be6ff6f8052ce`. Its partition map is identical
to the canonical stock build. Nordic `imgtool 2.1.0` validates its signature with the pinned upstream
compatibility key. These facts qualify bytes offline; they do not authorize physical use.

## Recovery-only 0001 scope

Recovery-only is the first custom behavioral image, but deliberately not the functional Gumi firmware.
It starts BLE/SMP first, configures the PDM peripheral without issuing a microphone start trigger, proves
the microphone off, and then remains in explicit safe mode. Its read/notify status characteristic
`32fcb4a7-660b-4c26-a887-3baf0166246c` under service
`796e0485-8f9d-4063-af3b-f5596fced74a` must settle to `01070123`:

```text
[schema=1, phase=safe-mode(7), reason=explicit-safe-mode(1),
 flags=recovery-transport|microphone-off|overwrite-only]
```

The image advertises the stock-family audio service only as an empty identity service. It links no stock
audio characteristic, codec, storage, battery, button, haptic, filesystem, or functional transport.
Its MCU Manager upload hook rejects every image number except application image `0`; the destructive
settings-storage erase group and full multi-image package remain absent/quarantined.

The exact signed file is `106936` bytes with SHA-256
`d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc` and MCUboot image hash
`065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57`. The canonical partition map is
unchanged and independent `imgtool` verification passes in the pinned, network-disabled container.
These facts still do not authorize a physical update.

This is a logical recovery application, not a cryptographically immutable recovery root. Stock MCUboot
uses overwrite-only upgrades, the upstream stock-compatible signing private key is public, and BLE/SMP
is intentionally unpaired so a sealed unit remains reachable. That combination is acceptable only for
controlled development; production requires a Gumi-owned trust root and authenticated maintenance path.

## Capture-port self-test 0001 scope

This diagnostic stage qualifies only the hardware privacy/PDM/Opus lifecycle before product capture
firmware is attempted. A phone arm grants one 15-second lease; a continuous two-second physical button
hold is still required before red is asserted and the microphone can start. One attempt counts and
discards PCM and Opus output for three seconds, then releases PDM and drains the codec before red can be
removed. It has no media transport, filesystem, offline storage, stock functional service, haptic path,
or automatic capture. A microphone-release failure holds red, reports unknown microphone truth, and
locks re-arm until recovery-only is restored.

The exact signed file is `178100` bytes with SHA-256
`8f0d0fc35c4d3c56e8f94d528a0b56c0d4af7b44b7bdad2b2fa4f2963e2e3d0e` and MCUboot image hash
`e97fd653a66dce18a7dbd071bc9b21c4fdae4e229de5d245e76ed213c2ca4862`. Its portable supervisor passes
14 cases, the current bundled-Opus port passes its pinned QEMU lifecycle gate, the exact target
source/object/map/config/partition audit passes, and `imgtool` validates the signature. These are
offline qualification facts. The image was exercised on the owned device and returned to recovery,
but the session did not preserve a sufficiently exact three-pass closeout receipt to promote
repeatability as proved hardware evidence.

## Recording-root provisioner 0001 scope

The provisioner is the only image authorized to write recording key material. It starts the
application-image-0 recovery transport first, proves the microphone off, initializes PSA, and writes
one random 32-byte root only when `HUK_KEYSLOT_MEXT` is empty. It never touches MKEK, never links the
all-slot random writer, zeroizes both the transient root and verification derivative, and has no
capture, codec, filesystem, media, or key-export surface.

Its status-only GATT service `47554d49-0010-4f4d-492d-435631000001` exposes one 12-byte
read/notify characteristic. A terminal state proves only recovery transport, microphone-off, whether
this boot attempted a write, MEXT presence, and a successful domain-separated derivation. A
15-second task watchdog backed by the nRF hardware WDT cold-resets a stalled provisioning path.

The exact signed application is `113428` bytes with file SHA-256
`e53c58a3ef72773af501a9ae05ceb5ce715b6d8153923ef52609dc5ec1dfa38b` and MCUboot image hash
`8a8eef711fd72707905bda05e08ddebc0de2a986d3c1d21b7469c92e7d32b12e`. The exact-board
source/object/map/config/watchdog/MEXT-only gates and independent RSA-PSS signature qualification
pass. MCU Manager upload and remote reset remain denied until a verified terminal state. Physical
use still requires a fresh recovery-only preflight, stable charger power, and explicit disclosure
that the two-slot MEXT write is irreversible and cannot be made power-loss atomic in software.

## Functional recording 0006 and 0007 scope

These are the current functional Gumi lineage. A double-tap toggles local recording; one tap requests
a bounded status indication. An ordinary hold is refused because this version has no authenticated
realtime VoiceTurn route. The capture authority asserts continuous red before microphone acquisition,
opens a fresh Opus session, encrypts each durable journal record with AES-256-GCM under a key derived
from an existing device HUK, and releases the microphone and codec before red may be removed.

Boot recovery scans retained `.GMR` and `.PRT` files without formatting, renaming, truncating, or
deleting them. A committed `.GMR` must authenticate through its exact end; a `.PRT` contributes only
its authenticated prefix. Missing key material, malformed records, storage failure, microphone
uncertainty, or a terminal pipeline-barrier failure remains fail-closed.

The read-only functional GATT service is
`47554d49-0001-4f4d-492d-435631000001`. Its 40-byte status and immutable 16-byte capability descriptor
let the Android lab distinguish recording-ready, recording-active, transition, recovery-maintenance,
and authentic fail-closed states. This version intentionally has no live media transport, remote
capture command, media export, or destructive storage API.

The sealed-unit v0003 boot proved this topology but failed closed while importing the MEXT-derived key:
Nordic's Mbed TLS integration had selected runtime allocation without a backing allocator, so
`psa_import_key` deterministically returned `PSA_ERROR_INSUFFICIENT_MEMORY` (`-ENOMEM`). V0004 enables
Nordic's fixed-buffer Mbed TLS allocator with a bounded 4096-byte heap. Its physical boot then proved
the key `READY` and exposed the next fail-closed boundary: the exact Zephyr SPI-SD driver has no PM
callback, so its advisory resume returns `-ENOSYS` before otherwise valid disk initialization.
V0005 normalizes only success, already-active, and no-PM-implementation resume results. `-ENOTSUP` and
all actual device, mount, capacity, key, codec, and recording failures remain fatal. It adds no retry,
formatter, key writer, or unbounded allocation path.

V0005 physically passed READY → continuous-red recording → durable stop → READY with zero firmware
error. Its 12-second application-core reset then left the sealed unit with no LED, button response, or
BLE advertisement while the separately powered nRF5340 network core remained alive. V0006 force-cycles
the network core before `bt_enable()` and forces it off before every application-core cold reset. It
does not change the network firmware image, recording behavior, storage format, or cryptographic root.

The exact signed v0006 application is `221576` bytes with file SHA-256
`eb811b62fffbf5c5f3ca1684b29073f6cf9cb49ae8e38d5201944c6c36d640a0` and MCUboot image hash
`3a35a3324393e563e36b2748f0b1f842ce5db52834cd0023334d9c9ac3071aa1`. The source/object/map/config,
partition, application-only update hook, retained-record authentication, forbidden formatter/HUK-write,
exact SD-PM policy, reset-port call order, network-core boot cold-start, whole-device reboot, and
RSA-PSS signature gates pass in the pinned network-disabled toolchain.

The owned v0006 unit subsequently proved a separate full-volume failure: stock legacy file
`/SD:/audio/a01.txt` occupied exactly 505,118,720 bytes and left zero free bytes. V0007 preserves
v0006 behavior but refreshes capacity before directory creation and returns `-ENOSPC` immediately
when full. Its exact signed application is `221592` bytes with file SHA-256
`a0d292117b0f2455fc342a2ad39e2b9ce02054e096689018184065df91933b25` and MCUboot image hash
`407df7c1f97b480f45d445d4045b5a124af2d431130a3f07b77b07726301d1e0`.

## Legacy storage reclaimer 0002 scope

This one-purpose maintenance image mounts FAT without formatting and inspects only
`/SD:/audio/a01.txt`. It unlinks that path only when it is a regular file exactly 505,118,720 bytes
long. Missing target is accepted only when at least 4 MiB is already free; wrong type, wrong size,
mount/stat/unlink error, or a failed postcondition is terminal and performs no alternate deletion.
There is no directory traversal, recursion, wildcard, formatter, media reader, capture, or key write.

The status-only service is `47554d49-0011-4f4d-492d-435631000001` with one 40-byte
read/notify characteristic `47554d49-0011-4f4d-492d-435631000002`. The exact signed image is
`114448` bytes with file SHA-256
`59fb753783c51140f4ee22c9d18dcaaf40bcb240e69c93a3cb2e967999fb1960` and MCUboot image hash
`8fc16e21b238dd4907abb6a4512db1002c4acf75cc079f8fa1ea49e467b412a2`. Its exact-board build,
source/object/map/config audit, host policy tests, manifest verification, and independent signature
qualification pass offline. Physical use still requires a fresh preflight and exact-hash authorization.

V0001 successfully reclaimed the exact file but then powered down the SD peer on the SPI3 bus shared
with MCUboot's external secondary slot. A dedicated slot erase returned success only because the slot
was already empty; the first real trailer erase for both functional v0007 and recovery returned MCU
Manager `FLASH_WRITE_FAILED` at byte zero. Its active image stayed confirmed and slot 1 stayed absent,
but it has no write surface that can restore the rail. Flash Lab now recognizes v0001 only as an
OTA-stranded incident state and rejects every BLE mutation from it; recovery of that installed unit
requires SWD. V0002 preserves unmount and SD-device suspend while keeping SD power asserted, matching
the stock handoff and preventing an unpowered peer on shared SPI3.

Recording-root presence must be physically proven by the provisioner before Flash Lab offers this
transition. While capture is idle, a continuous five-second button hold admits application-image-0
maintenance; remote reset is subject to the same physical admission. Continuing the same hold to
12 seconds runs the bounded whole-device reset port, and a 15-second task watchdog backed by the nRF
hardware WDT is armed before device-I/O initialization and resets a stalled main loop. The original
v0005 procedure and physical evidence are preserved in
[`qualification/functional-recording-0005-first-device.md`](qualification/functional-recording-0005-first-device.md).

## Materialize

Given an existing clone containing the exact upstream commit:

```sh
devices/omi-cv1/firmware/scripts/materialize.sh \
  /path/to/BasedHardware-omi \
  /private/tmp/gumi-omi-v3012-recovery-only-0001 \
  recovery-only-0001
```

The destination must not already exist. Materialization changes no tracked Gumi file and performs no
device or network operation.

## Build

Prepare an NCS v2.9.0 west workspace separately, then run:

```sh
devices/omi-cv1/firmware/scripts/build-application.sh \
  /private/tmp/gumi-omi-v3012-recovery-only-0001 \
  /path/to/ncs-v2.9.0-west-workspace \
  build-gumi-recovery-only-0001 \
  recovery-only-0001
```

The script verifies the immutable inputs, builds the complete sysbuild because that is how upstream
assembles the signed application, runs the application signature/configuration/partition gates, and
prints only application artifact sizes and hashes. It runs with Docker networking disabled. Treat
every network/full-package output as quarantined build byproduct.

Qualify the exact signed result separately:

```sh
devices/omi-cv1/firmware/scripts/qualify-application.sh \
  devices/omi-cv1/firmware/releases/recovery-only-0001.json \
  /path/to/build-gumi-recovery-only-0001/omi/zephyr/zephyr.signed.bin \
  /private/tmp/gumi-omi-v3012-recovery-only-0001 \
  /path/to/ncs-v2.9.0-west-workspace
```

RSA-PSS signing uses a random salt. A rebuild can reproduce the source lineage and deterministic
content but will normally have a different whole-file hash. It is not this release candidate until a
new exact manifest is reviewed and qualified.

For a physical session, preserve the qualified bytes outside Git under these ignored paths:

```text
local/firmware/omi-cv1/canary-0001/omi.signed.bin
local/firmware/omi-cv1/recovery-only-0001/omi.signed.bin
local/firmware/omi-cv1/capture-port-selftest-0001/omi.signed.bin
local/firmware/omi-cv1/recording-root-provisioner-0001/omi.signed.bin
local/firmware/omi-cv1/functional-recording-0006/omi.signed.bin
local/firmware/omi-cv1/legacy-storage-reclaimer-0002/omi.signed.bin
local/firmware/omi-cv1/functional-recording-0007/omi.signed.bin
local/firmware/omi-cv1/stock-v3.0.12/omi.signed.bin
local/firmware/omi-cv1/stock-v3.0.12/Omi_CV1_OTA_v3.0.12.zip
```

The current dedicated flash lab packages exactly recovery-only-0001, capture-port-selftest-0001,
recording-root-provisioner-0001, functional-recording-0006, legacy-storage-reclaimer-0002,
functional-recording-0007, and exact stock after build-time SHA-256/size verification. It shows the
exact file SHA-256, source application identity, network observation policy, and transition direction
before minting its short-lived, one-shot authorization.

Materialization and build do not authorize an upload. Every physical mutation requires a fresh
read-only preflight and its own explicit owner go/no-go. Decision 0005 accepts
`APPLICATION_MATCH_NETWORK_UNOBSERVED` only for explicitly pinned application-only transitions; it
never turns the absent network row into a verified hash.

## Functional firmware development

[`gumi/`](gumi/) contains the device-owned, host-testable firmware kernel. Recovery-only links its
supervisor plus the minimum Omi PDM-off and BLE ports; every other slice remains outside that image.
Passing a host test alone does not make a signed artifact or authorize an update. Current independently
gated slices are:

- allocation-free button debounce and physical gesture recognition: 16 host cases;
- capture/privacy/durability supervision with transition-correlated callbacks: 21 host cases;
- single-writer logical RGB arbitration: 7 host cases;
- recovery-first boot/safe-mode supervision: 11 host cases;
- encrypted recording-journal framing and authenticated-prefix recovery: 16 host cases;
- transactional recording-store lifecycle and failure handling: 11 host cases;
- an Omi v3.0.12 PDM adapter with a stop/join/drain/reconfigure barrier;
- a fresh-session bundled-Opus adapter with bounded PCM and exact close accounting; and
- a narrow Nordic PSA AES-256-GCM adapter that performs no key provisioning.

The microphone, codec, MEXT-derived-key, FATFS writer/recovery scanner, privacy output, button,
supervisor, and read-only functional transport now compile and link together in the exact Omi target.
The composition excludes stock capture/storage behavior, filesystem formatting, automatic HUK writes,
image `1`, media export, and unauthenticated remote capture. Its remaining gate is physical, not
another source slice: prove terminal MEXT provisioning and the exact idle → recording → durable stop lifecycle
on the owned sealed unit, then demonstrate the device-local recovery return.
