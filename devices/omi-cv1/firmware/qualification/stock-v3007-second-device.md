# Omi CV1 second consumer unit: stock v3.0.7 source evidence

Date: 2026-07-25  
Scope: sealed consumer device, read-only BLE/GATT/MCU Manager inspection  
Mutation result: **none**

## Physical evidence

The selected process-local BLE endpoint produced:

| Surface | Observation |
| --- | --- |
| Manufacturer | `Based Hardware` |
| Model | `Omi CV 1` |
| Firmware revision | `3.0.7` |
| Hardware revision | `Based Hardware Omi` |
| Battery | `21%` |
| GATT inventory | 8 services, 16 characteristics |
| Link | MTU 23, LE 2M TX/RX, not bonded |
| MCU Manager protocol | `mcumgr-smp` |
| Split status | `0` |
| Active application | image 0, slot 0, `0.0.0`, bootable/active/confirmed |
| Active application hash | `ab6364926c7df7371a013dfbcf1e3f73f9386b8500f5cbe4153c2883b798877e` |
| Image 1 | wholly unobserved |

The original owner checklist had been ticked for an Omi battery of at least 80%, but the device's
read-only Battery Service reported 21%. No upload, confirmation, reset, erase, subscription, or
characteristic write occurred during that inspection. The owner then explicitly selected a lab
tradeoff in which the machine reading remains visible but is warning-only and never blocks flashing.

## Official artifact correlation

The official Based Hardware
[`Omi_CV1_v3.0.7` release](https://github.com/BasedHardware/omi/releases/tag/Omi_CV1_v3.0.7)
publishes `Omi_CV1_OTA_v3.0.7.zip`.

| Artifact | File SHA-256 | MCUboot image hash |
| --- | --- | --- |
| Official v3.0.7 OTA archive | `3deca9b558fe152705ef674d02f8343ffc13aa40541f8e3fb85fddbf36b520ac` | n/a |
| `omi.signed.bin` | `58a355ed2e348ffe4944fd9de889c294b012251458e7b20c2d5e88017e9c6b55` | `ab6364926c7df7371a013dfbcf1e3f73f9386b8500f5cbe4153c2883b798877e` |
| `ipc_radio.bin` | `f0bed1869b653e36b60858aad59f26dbec2c392a27ae542566fe535dedb2f8c2` | `f8fc9da4ad429d3ac91e5ba12595a330b61d8f2e8cd4fb969be9349680937649` |

The physical application's exact MCUboot hash therefore matches the official v3.0.7 OTA application.
The Device Information revision independently agrees.

The official Based Hardware
[`Omi_CV1_v3.0.12` release](https://github.com/BasedHardware/omi/releases/tag/Omi_CV1_v3.0.12)
contains:

| Artifact | File SHA-256 | MCUboot image hash |
| --- | --- | --- |
| Official v3.0.12 OTA archive | `821ce06d73f8bb3695de70dce0880a00597dd71175a843d08e577d775125ab4e` | n/a |
| `omi.signed.bin` | `877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db` | `0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36` |
| `ipc_radio.bin` | `0e1d067444ca78dd4ea64cb355bc167c1155b84f49b0c7028112c9930602d2a5` | `267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089` |

The v3.0.7 and v3.0.12 network hashes differ. An application-only jump from this unit to a Gumi image
built on the v3.0.12 baseline is therefore rejected.

## Qualified next gate

Flash Lab now contains a separate typed normalization transaction:

1. require the exact active v3.0.7 application hash and Device Information revision;
2. re-read and disclose the warning-only device battery, with a separate charger-connected attestation;
3. inspect both packaged v3.0.12 MCUboot binaries in memory;
4. disclose and authorize both exact file and image hashes;
5. use Nordic Device Manager's multi-image `CONFIRM_ONLY` flow for application image 0 and network
   image 1, without erasing application settings;
6. request one reset only after Nordic's validation/upload/confirm sequence completes; and
7. accept success only after a fresh active v3.0.12 application hash and Device Information revision.

Only after that proof may the existing separately authorized v3.0.12 stock → recovery-only → recording
root provisioner → functional-recording sequence begin.

Physical dual-image normalization is **not yet qualified**. The charger-connected and no-rollback
attestations must be active; the Battery Service reading remains visible but non-blocking.
