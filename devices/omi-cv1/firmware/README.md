# Omi CV1 firmware workspace

Gumi customizes the exact application source installed on the owned sealed unit without vendoring a
moving Omi monorepo snapshot or importing unrelated mobile/backend code.

## Current shape

- [`upstream.lock.json`](upstream.lock.json) pins the Omi release, source commit, source-file digests,
  NCS/Zephyr/MCUboot revisions, and immutable Nordic toolchain image.
- [`patches/0001-canary-identity.patch`](patches/0001-canary-identity.patch) is the first deliberately
  tiny application-only delta.
- [`scripts/materialize.sh`](scripts/materialize.sh) creates a detached exact-source worktree and
  applies the overlay after checking every pin.
- [`scripts/build-application.sh`](scripts/build-application.sh) builds that materialized application
  in the canonical NCS v2.9.0 container while using an external NCS workspace.
- [`releases/canary-0001.json`](releases/canary-0001.json) pins the one exact signed application output
  qualified for a future owner-reviewed transition.
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
device candidate. The current exact signed application candidate is `228724` bytes, has file SHA-256
`65e4ae91637e6aa576f6d3f8286db33bc9bd36ece8ece1aaa6b6aa5c5b204f6d`, and has MCUboot image hash
`d3b3c74c10c4fa110763ae5523d0aeaa20b9f815b8674167486be6ff6f8052ce`. Its partition map is identical
to the canonical stock build. Nordic `imgtool 2.1.0` validates its signature with the pinned upstream
compatibility key. These facts qualify bytes offline; they do not authorize physical use.

## Materialize

Given an existing clone containing the exact upstream commit:

```sh
devices/omi-cv1/firmware/scripts/materialize.sh \
  /path/to/BasedHardware-omi \
  /private/tmp/gumi-omi-v3012-canary-0001
```

The destination must not already exist. Materialization changes no tracked Gumi file and performs no
device or network operation.

## Build

Prepare an NCS v2.9.0 west workspace separately, then run:

```sh
devices/omi-cv1/firmware/scripts/build-application.sh \
  /private/tmp/gumi-omi-v3012-canary-0001 \
  /path/to/ncs-v2.9.0-west-workspace \
  build-gumi-canary-0001
```

The script verifies the immutable inputs, builds the complete sysbuild because that is how upstream
assembles the signed application, runs the application signature/configuration/partition gates, and
prints only application artifact sizes and hashes. It runs with Docker networking disabled. Treat
every network/full-package output as quarantined build byproduct.

Qualify the exact signed result separately:

```sh
devices/omi-cv1/firmware/scripts/qualify-application.sh \
  devices/omi-cv1/firmware/releases/canary-0001.json \
  /path/to/build-gumi-canary-0001/omi/zephyr/zephyr.signed.bin \
  /private/tmp/gumi-omi-v3012-canary-0001 \
  /path/to/ncs-v2.9.0-west-workspace
```

RSA-PSS signing uses a random salt. A rebuild can reproduce the source lineage and deterministic
content but will normally have a different whole-file hash. It is not this release candidate until a
new exact manifest is reviewed and qualified.

For a physical session, preserve the qualified bytes outside Git under these ignored paths:

```text
local/firmware/omi-cv1/canary-0001/omi.signed.bin
local/firmware/omi-cv1/stock-v3.0.12/omi.signed.bin
local/firmware/omi-cv1/stock-v3.0.12/Omi_CV1_OTA_v3.0.12.zip
```

The dedicated flash lab packages only these two application binaries after build-time SHA-256/size
verification. It shows the exact file SHA-256, source application identity, network observation policy,
and transition direction before minting its short-lived, one-shot authorization.

Materialization and build do not authorize an upload. Every physical mutation requires a fresh
read-only preflight and its own explicit owner go/no-go. Decision 0005 accepts
`APPLICATION_MATCH_NETWORK_UNOBSERVED` only for the pinned stock-to-canary and canary-to-stock
application transitions; it never turns the absent network row into a verified hash.

## Functional firmware development

[`gumi/`](gumi/) contains the device-owned, host-testable firmware kernel. It is deliberately separate
from the identity-only canary overlay: passing its host tests does not make it a signed artifact or
authorize an update. Its current independently gated slices are:

- allocation-free button debounce and physical gesture recognition: 16 host cases;
- capture/privacy/durability supervision with transition-correlated callbacks: 19 host cases;
- single-writer logical RGB arbitration: 7 host cases;
- encrypted recording-journal framing and authenticated-prefix recovery: 16 host cases;
- an Omi v3.0.12 PDM adapter with a stop/join/drain/reconfigure barrier;
- a fresh-session bundled-Opus adapter with bounded PCM and exact close accounting; and
- a narrow Nordic PSA AES-256-GCM adapter that performs no key provisioning.

The historical microphone port compiled in an exact target overlay. A historical codec-port revision
also compiled, linked, signed, and passed the application artifact gates, but that probe is explicitly
superseded because the current codec source added stricter accounting and self-join checks afterward.
The current codec target rebuild and QEMU test, plus the new journal/crypto target gate, remain pending.
No probe invokes these ports, no probe is a behavioral candidate, and none is authorized for physical
use.

The next functional device slice is the fixed-allocation Zephyr SD writer and recovery scanner. It will
reuse the existing Omi SPI SD hardware plus Zephyr/FATFS, but it will not reuse stock behavior that can
format on mount failure, ignore short writes, lose a partial tail, or expose destructive storage
commands over an unencrypted GATT characteristic. Key-state discovery, key provisioning, privacy-output
composition, full lifecycle invocation, and physical qualification remain separate explicit gates.
