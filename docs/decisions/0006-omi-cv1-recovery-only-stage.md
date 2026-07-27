# Decision 0006: Omi CV1 recovery-only application stage

Status: **proposed and offline-qualified on 2026-07-20; no physical authorization conveyed**.

## Context

The owned sealed Omi CV1 completed one exact stock-to-identity-canary-to-stock compatibility cycle under
[Decision 0005](0005-omi-cv1-network-unobserved-ota.md). That proves the bounded image-0 transport and
exact stock recovery once, but stock firmware still starts capture by default and does not provide a
minimal safe base for functional Gumi development.

Recovery-only-0001 is built from the same pinned v3.0.12 application lineage. It retains the canonical
partition map and equal MCUboot version while linking only a recovery supervisor, PDM-off proof,
BLE/SMP image-0 path, Device Information, and read-only recovery status. Exact release evidence is in
[`recovery-only-0001.json`](../../devices/omi-cv1/firmware/releases/recovery-only-0001.json).

## Proposed decision

For the single owned development unit, permit a separately authorized exact-stock-to-recovery-only-0001
application transition, and retain the reverse exact-stock transition as an explicit recovery action.
Complete absence of image-`1` rows may use the same bounded evidence interpretation as Decision 0005:
image `1` remains **unobserved**, never reported as hash-verified. Any visible network row must match the
published active network hash with no pending or populated secondary state.

Recovery-only is the default stable outcome. The lab must not automatically restore stock after a
successful validation.

## Required construction

- APK packages exactly recovery-only-0001 and official stock application image `0`.
- Every upload call names image `0`; recovery firmware independently rejects every other image number.
- No network BIN, multi-image ZIP, merged HEX, generic picker, erase, shell, filesystem, or automatic
  retry route exists.
- The settings partition is preserved but not loaded; the destructive settings-storage erase handler is
  absent.
- Stock capture transport, audio characteristic, codec, storage, battery, button, haptic, and other
  functional services are absent.
- Exact source, target, staged, confirmed, and post-reboot hashes remain mandatory.

## Required physical acceptance

Post-reboot success requires both MCUboot and independent GATT evidence on the same process-local
endpoint:

- active recovery image hash
  `065be47c212f3c233bd683c780a5b1dfb583f7dbac932029d15d1fd1cf692d57`;
- status `01070123` (recovery transport ready, microphone verified off, capture denied, overwrite-only
  policy observed);
- empty stock-family identity service but no stock audio characteristic;
- no stock functional Omi services; and
- continuous reachable recovery advertising during an off-charger observation of at least ten minutes,
  followed by a fresh combined image-state and GATT evidence read. The Flash Lab enforces the minimum
  interval with its monotonic clock.

Any mismatch is terminal for the session. Exact-stock recovery requires a new review and its own
authorization; it is not an automatic response to ambiguous state.

## Accepted only if explicitly authorized

This proposal does not itself accept the physical risk or authorize an upload. The owner action must
name exact file SHA-256
`d29a5292eeeff01bc0c2984ef0a852706392f2eb1d888ea3c8daa221881e75cc` after a fresh stock preflight.

## Residual risk

- Stock upgrade policy is overwrite-only with no promised automatic rollback.
- The upstream stock-compatible signing private key is public; recovery-only is not cryptographically
  immutable.
- BLE/SMP is deliberately unpaired at this development stage, so nearby attackers remain in scope.
- Image `1`, invisible secondary state, interruption recovery, repeated upgrade behavior, bootloader
  recovery, and long-duration power behavior are unproved.
- Recovery-only-0001 does not yet connect reset-cause evidence or an independently supervised hardware
  watchdog to the portable recovery state machine.
- Without SWD/J-Link, failure before BLE/SMP becomes reachable may be unrecoverable wirelessly.

These constraints forbid fleet, unattended, background, or production use. Production promotion
requires a Gumi-owned trust root, authenticated maintenance, complete component state evidence, and a
recovery mechanism independent of the mutable application.
