# Omi CV1 recovery-first firmware

Status: **offline-qualified; physical qualification pending** (2026-07-20).

This stage replaces the behavior-preserving identity canary with the smallest application that can keep
a sealed consumer unit recoverable while proving that capture is unavailable. It is a prerequisite for
functional Gumi firmware, not the final recording/VoiceTurn implementation.

## Exact release

| Property | Qualified value |
|---|---|
| Release | `omi-cv1-v3012-stock-to-gumi-recovery-only-0001` |
| Application file | `local/firmware/omi-cv1/recovery-only-0001/omi.signed.bin` |
| Size | `106936` bytes |
| File SHA-256 | `d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc` |
| MCUboot image hash | `065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57` |
| MCUboot version | `0.0.0+0` |
| Compatibility key hash | `fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994` |
| Source stock application hash | `0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36` |
| Expected network hash if observable | `267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089` |
| Exact stock-recovery file SHA-256 | `877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db` |

The checked-in release contract is
[`recovery-only-0001.json`](../../firmware/releases/recovery-only-0001.json). The partition map is byte-for-byte
the canonical stock map. The complete build-output audit and independent `imgtool 2.1.0` verification
pass in the digest-pinned NCS v2.9.0 container with networking disabled.

## Boot and recovery invariant

The portable supervisor owns this order:

```text
Cold
  -> start BLE/SMP recovery transport
  -> configure PDM without starting capture
  -> verify microphone off
  -> explicit SafeMode forever
```

Capture admission is false from initialization and can never become true on this profile. Transport
failure yields `RECOVERY_UNAVAILABLE`; microphone-off verification failure also fails closed. Transition
IDs prevent late completions from moving an expired boot attempt forward.

The device exposes a four-byte GATT status on characteristic
`32fcb4a7-660b-4c26-a887-3baf0166246c`, service
`796e0485-8f9d-4063-af3b-f5596fced74a`:

```text
[schema, phase, reason, flags]
01 07 01 23
```

`0x23` means recovery transport ready, microphone verified off, and overwrite-only boot policy
observed. It explicitly excludes self-tests passed, functional services ready, and capture permitted.

## Linked and excluded surface

The exact target audit proves these components are linked:

- portable recovery supervisor;
- Omi v3.0.12 PDM configuration adapter;
- Zephyr Bluetooth, SMP/image-0 update, reset, Device Information, and recovery-status services;
- external-QSPI application secondary-slot support; and
- the application-image-0 upload admission hook and received-image integrity/size checks.

It also proves these surfaces absent:

- stock audio transport and audio characteristic;
- Opus codec and stock microphone-start source;
- storage/filesystem, battery, button, haptic, speaker, USB, and accelerometer behavior;
- the basic/destructive settings-storage erase management group;
- OS echo, bootloader-info, shell, file, and generic multi-image package paths; and
- every packaged network, ZIP, or HEX artifact.

The stock audio service UUID remains as an **empty identity service** so the existing exact Omi scanner
can find the device. Full driver negotiation therefore fails closed because its audio characteristic is
absent.

## Security boundary

This stage is deliberately serviceable, not production-secure:

- stock MCUboot is overwrite/upgrade-only and promises no automatic test/revert rollback;
- the stock-compatible RSA private key is public in the upstream repository, so application recovery is
  not cryptographically immutable;
- BLE/SMP remains unpaired to avoid making a sealed unit unreachable before Gumi identity/provisioning
  exists; and
- this exact profile hardcodes explicit safe mode and does not yet wire reset-cause evidence or an
  independently supervised hardware watchdog; and
- the recovery application rejects image `1`, but installed network identity remains unobserved when
  stock MCU Manager returns no network rows.

Use only in controlled physical proximity. A production Gumi device needs a Gumi-owned trust root,
authenticated maintenance authorization, anti-rollback policy, and an independently recoverable
bootloader path.

## Flash Lab qualification contract

The recovery Flash Lab APK contains exactly two signed application-image-0 files: recovery-only-0001 and
official stock v3.0.12. Starting from exact stock selects recovery-only; starting from exact recovery-only
selects stock. The old canary is no longer packaged or selectable.

After reset, the app does not accept the image hash alone. It reconnects to the same process-local
endpoint and requires all of the following before showing `VALIDATED`:

1. recovery-only MCUboot hash active, bootable, confirmed, and not pending;
2. no populated application secondary slot;
3. image `1` wholly unobserved, or exact published/stable if it becomes visible;
4. recovery service and empty stock-family identity service present;
5. status exactly `01070123`;
6. stock audio characteristic absent; and
7. stock battery/settings/features/storage and other functional Omi services absent.

Exact-stock recovery is never automatic. After validation, recovery-only may remain installed; opening
stock recovery starts a new preflight and requires a distinct exact-file authorization.

## Owned-unit recovery result, 2026-07-22

The owner authorized the exact recovery file SHA-256
`d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc`. Flash Lab uploaded only
application image `0`, confirmed it, and requested reset. The rebooted pendant advertised both the
empty stock-family identity service and recovery service.

An independent Gumi read established:

- software revision `gumi-recovery-only-0001`;
- recovery status exactly `01070123`: transport ready, microphone off, capture denied, and
  overwrite-only;
- six services and 13 characteristics, with the stock-family service empty and stock audio,
  battery, settings, features, and storage services absent; and
- active image `0`, slot `0`, hash
  `065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57`, bootable and confirmed,
  not pending, and with image `1` unobserved.

The first fresh recovery image-state diagnostic exposed a real client defect: forcing ATT MTU 23
raised `InsufficientMtuException`. Gumi now requests the already qualified 498-byte ceiling. A first
post-fix connection timed out while the scan reported `-100 dBm`; the procedure's single retry
completed in 2.8 seconds with the exact recovery hash and status
`GUMI_RECOVERY_APPLICATION_MATCH_NETWORK_UNOBSERVED`. The sealed retry evidence has log SHA-256
`4cc5d8960c72ea6d41f9c064ae4529a420a516a024c52dd898f3828bc79c59d6`, screenshot SHA-256
`e50015d2d4df9822e9d8c42f5621a7c266903a654caf6e01bfa699e2f60e9efa`, and manifest SHA-256
`bf62be41163d90c16e159ecffea746c6269f41ff81a644d6e4d75212ccc3b4c3`.

Flash Lab did not reach its combined `VALIDATED` state because the firmware transition changed
Android's process-local BLE endpoint. Its equality-only continuity guard correctly refused to bind the
new endpoint. The subsequent updater fix keeps a changed candidate untrusted, validates the exact
expected target hash against that endpoint, adds the recovery status/topology proof for recovery-only,
and commits the new process-local endpoint only after every check succeeds. A wrong target leaves the
old binding intact and stops the flow. This is locally qualified but was not retroactively exercised by
the already completed physical transition.

## Post-dwell result, 2026-07-22

After more than ten minutes off charger, a fresh scan still observed the recovery advertisement. A
fresh read-only GATT connection again returned software revision `gumi-recovery-only-0001`, six
services and 13 characteristics, status `01070123`, and the same empty/absent stock functional
topology. A fresh MCUboot read again returned exact active/bootable/confirmed/not-pending image-0 hash
`065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57`, with image `1` unobserved.

The provenance-bound post-dwell firmware bundle has log SHA-256
`c6abf6fe06d57c42f7c69f6fc902bf5bc3457a42a704f6239fc76afa62a0669d`, screenshot SHA-256
`66103b2690eb92b0f10b0d7a6cfd5212449001ebdd984795e1c337f88f96b896`, and manifest SHA-256
`822aa97f7830b5de5e566055dcb2be256d1ece7bf6d69198b805323036018aa1`.

## Remaining recovery work

1. Exercise the locally qualified exact-evidence endpoint rebind during a future separately authorized
   physical transition; do not mutate recovery-only merely to manufacture this test.
2. Only if recovery evidence fails or the owner explicitly wants to return to stock, run the
   separately reviewed exact-stock transition.

Stop after any unexpected hash, image-1 state, missing recovery service, status other than `01070123`,
functional service, disconnect loop, reset anomaly, or loss of advertising. Do not push another variant.
Without SWD/J-Link, charger insertion and the separately packaged exact-stock image are the only
qualified recovery aids.

The completed initial and post-dwell passes prove one stock-to-recovery boot and a reachable,
fail-closed application after more than ten minutes off charger on the owned unit. They do not prove
interruption recovery, repeated upgrades, network-core immutability, bootloader recovery,
long-duration battery behavior, an independent electrical/acoustic microphone-off measurement, or
production security.
