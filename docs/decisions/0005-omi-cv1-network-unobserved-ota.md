# Decision 0005: Omi CV1 application-only OTA with an unobserved network core

Status: accepted for the one owned Omi CV1 development unit on 2026-07-20. No firmware mutation had
occurred when this decision was recorded.

## Context

The owned unit reports one active, confirmed, bootable application image with the exact published
v3.0.12 MCUboot hash. It reports no network-core image or secondary-slot rows. The same unit delivered a
qualified 10-second BLE audio witness with 532 Opus frames, zero gaps, and zero discontinuities, proving
that its stock application/network BLE path is operational without proving the network image bytes.

The pinned v3.0.12 build has two updateable images. Its Zephyr image-state encoder omits a slot when it
cannot read a valid MCUboot header. Stock SMP provides no separate command that can recover the missing
network-primary hash. Requiring that hash indefinitely would make the sealed-device OTA path unusable
and leave SWD/J-Link or vendor intervention as the only practical alternatives.

## Decision

Permit `APPLICATION_MATCH_NETWORK_UNOBSERVED` as the source and intermediate state for exactly these
two transitions:

- published stock v3.0.12 application to qualified identity-only canary-0001; and
- qualified identity-only canary-0001 to the exact published stock v3.0.12 application.

Complete absence of image-`1` rows is the only relaxed observation. If any network row is visible, it
must contain the exact published active network hash and no pending or populated secondary state.
Application identity, version, endpoint, staged hash, confirmation state, and post-reboot target remain
exact gates.

Network immutability is protected by the write boundary, not claimed as a physical hash observation:

- the APK packages only two exact signed application images;
- the updater calls the explicit image-number-`0` API;
- no multi-image, image-`1`, ZIP, network BIN, HEX, erase, test, filesystem, settings, or shell surface
  exists;
- repository verification audits both the source API boundary and packaged APK contents; and
- every state read rejects visible contradictory network evidence.

## Execution result

On 2026-07-20, the owner separately authorized and completed both permitted transitions. Fresh
post-reboot reads observed the exact canary application hash and then the exact recovered-stock
application hash, each active, bootable, confirmed, and not pending. Canary identity/indicator/GATT/
audio and recovered-stock driver/GATT/audio checks passed. Image `1` remained wholly unobserved in
every state read, so the result is recorded as one successful application-only forward/recovery cycle,
not as an observed network-hash match or generic OTA qualification.

## Accepted risk

This decision cannot prove the installed network bytes or an invisible network-secondary state. It
also cannot eliminate a bootloader, library, transport, or undocumented-device defect that writes
outside the intended image-`0` slot. The stock upgrade mode is overwrite-style and provides no promised
automatic rollback. If the canary stops booting or advertising SMP, wireless recovery may be impossible
and SWD/J-Link may still be required.

The risk is accepted only for the owned development unit because the canary is behavior-neutral, comes
from the byte-reproduced stock application lineage, retains the exact partition map and equal MCUboot
version, preserves all protocols, and has an exact stock application recovery image packaged through
the same closed path.

## Operational consequences

- No background, unattended, fleet, or production OTA may inherit this exception.
- Each direction requires a fresh preflight and distinct one-shot owner authorization.
- Any rejection, anomalous reboot, endpoint change, or failed post-reboot validation is terminal for
  that session; do not improvise a retry.
- Reports must say `network unobserved, protected by image-0-only construction`, never `network hash
  verified` or `network unchanged by observation`.
- The exception must be removed or redesigned when Gumi owns a bootloader/update protocol capable of
  authenticated component manifests and complete slot-state evidence.

## Rejected alternatives

- Treating the absent image-`1` row as the published hash.
- Uploading an official or locally built multi-image ZIP to discover the state by mutation.
- Packaging a network image “for recovery.” The locally reproduced network image contains a different
  NSIB key and is quarantined.
- Repeated canary variants until one boots.
- Opening the device or buying SWD hardware before testing the bounded application-only route.
