# Omi CV1 upstream policy

## Pins

| Purpose | Ref |
| --- | --- |
| Installed-release recovery/canary basis | `Omi_CV1_v3.0.12` / `85159556eac753a088c5efd1b419a5a867508e27` |
| Later migration/reference candidate | `Omi_CV1_v3.0.20` / `aa1133cd17139aa09cbe4883cdf51f15094b9916` |
| Research snapshot | `main` / `1c19526cacb8a6100e8060b203c02963882281cf` |
| Published v3.0.12 OTA SHA-256 | `821ce06d73f8bb3695de70dce0880a00597dd71175a843d08e577d775125ab4e` |
| Published v3.0.20 OTA SHA-256 | `dfc7ea6986d9b02fe899a38afc6c9bf6fabb9cff669244fbf20a3d7abeda59da` |
| Published image key hash | `fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994` |
| Published MCUboot versions | application `0.0.0+0`, network core `0.0.0+0` |
| Published v3.0.12 network NSIB public-key SHA-256 | `fbe5bac7a49e0f37680b05f3b5a6078d772277de0a5b5963f7f51df677ef9c89` |
| Published v3.0.20 network NSIB public-key SHA-256 | `c4bd988a114092a4578b4157e33fbc4b7f64df616470f4de339f930ead9ea3f9` |
| Upstream license | MIT, copyright Based Hardware Contributors |

Upstream: <https://github.com/BasedHardware/omi>

## Import shape

Do not mirror the complete Omi monorepo and do not make it a Gumi submodule.

Planned imports:

1. `omi/firmware` becomes a history-preserving subtree at `devices/omi-cv1/firmware` after the repository
   baseline is reviewed. The installed v3.0.12 application is reproducible in the release-prescribed
   NCS v2.9.0 toolchain; the earlier wrong-SDK result remains quarantined. The v3.0.20 application is
   separate later-migration evidence. Same-release recovery and single-image-0 transport remain sealed-
   device gates. Network builds require an explicit NSIB key policy and are not qualified for the unit.
2. Selected pure protocol files/tests and native BLE adapter code are extracted into Gumi-owned packages
   with per-path provenance. They are not imported as the entire Omi application subtree.
3. Hardware CAD/BOM/schematic files remain pinned external research references until Gumi modifies the
   hardware. If mirrored later, use checksums and Git LFS where appropriate.

A subtree keeps firmware, device protocols, and the edge driver atomic while preserving useful upstream
history. A separate firmware repository can be reconsidered only when firmware has an
independent team, release cadence, or access policy.

## Upstream update procedure

For each candidate upstream update:

1. fetch the exact upstream commit and record it;
2. inspect changes only in imported paths plus their build dependencies;
3. classify security, protocol, storage-format, partition, and hardware changes;
4. merge into a dedicated update branch;
5. run firmware build, protocol fixtures, simulator tests, and hardware-in-loop qualification;
6. update this pin and the reuse ledger; and
7. publish no firmware until the sealed device and recovery path pass.

Never auto-merge upstream firmware or signing changes.

## Local divergence policy

- Prefer configuration and small boundary refactors before replacing working drivers.
- Preserve upstream wire compatibility through the first Gumi vertical.
- Mark storage or protocol migrations explicitly and support interrupted migration.
- Project-owned keys and secrets are never committed.
- An upstream private key may be used only as a temporary compatibility signer for the stock bootloader,
  never as Gumi's authority or long-term release secret.
- Compatibility-phase application images keep boot header `0.0.0+0`; the Gumi release identity is
  carried separately until a project-owned recovery/version policy is proven.
- The sealed-device canary updates application image `0` only. Never substitute a clean-build network
  image for the installed image `1`: upstream auto-generates its NSIB key when no key file is configured.
- Retain the upstream MIT notice in all substantial copied portions.
