# Omi CV1 stock OTA inspection: v3.0.20

Status: installed-release and baseline-candidate artifacts decoded and signatures verified; v3.0.20
exact-source build compared; owned-device slot state still pending.

This report is about the official release bundle, not yet about the exact images installed on the
project's sealed pendant.

## Artifact identity

| Field | Observed value |
| --- | --- |
| Release | [`Omi_CV1_v3.0.20`](https://github.com/BasedHardware/omi/releases/tag/Omi_CV1_v3.0.20) |
| Source commit | `aa1133cd17139aa09cbe4883cdf51f15094b9916` |
| Bundle | [`Omi_CV1_OTA_v3.0.20.zip`](https://github.com/BasedHardware/omi/releases/download/Omi_CV1_v3.0.20/Omi_CV1_OTA_v3.0.20.zip) |
| Bundle bytes | `424502` |
| Bundle SHA-256 | `dfc7ea6986d9b02fe899a38afc6c9bf6fabb9cff669244fbf20a3d7abeda59da` |
| Manifest time | `2026-07-12T07:48:27Z` |
| Inspection tool | MCUboot `imgtool` 2.4.0 |

The checked-in machine-readable observation is
[`protocols/ota/v1/stock-v3.0.20.json`](../../protocols/ota/v1/stock-v3.0.20.json).

## Installed-release v3.0.12 oracle

The owned unit reports `3.0.12`, so its matching official release was inspected separately before any
MCU Manager request. This is the comparison oracle for the pending image-state read, not an assertion
that the sealed unit contains those bytes until its returned hashes match.

| Field | Published v3.0.12 value |
| --- | --- |
| Release | [`Omi_CV1_v3.0.12`](https://github.com/BasedHardware/omi/releases/tag/Omi_CV1_v3.0.12), commit `85159556eac753a088c5efd1b419a5a867508e27` |
| Bundle | `Omi_CV1_OTA_v3.0.12.zip`, `404954` bytes |
| Bundle SHA-256 | `821ce06d73f8bb3695de70dce0880a00597dd71175a843d08e577d775125ab4e` |
| Application image `0` MCUboot hash | `0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36` |
| Network image `1` MCUboot hash | `267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089` |
| MCUboot header version | `0.0.0+0` for both images |
| Signature result | Both outer RSA-2048 signatures and the network image's inner ECDSA P-256 signature verified |

The complete file/header/TLV/NSIB evidence is
[`protocols/ota/v1/stock-v3.0.12.json`](../../protocols/ota/v1/stock-v3.0.12.json).
The official release ZIP SHA-256 was checked independently against GitHub's published asset digest.
No release binary or signing key is stored in this repository.

## Package layout

| Image | Manifest mapping | Load address | Total bytes | File SHA-256 |
| --- | --- | --- | --- | --- |
| Application core | image `0`, primary slot `1`, secondary slot `2` | `0x00010200` | `248180` | `b080abc08bbd6566500c78c27724c8319300711b3f7bb2b0823f6ca594194d2b` |
| Network core | image `1`, primary slot `3`, secondary slot `4` | `0x01008800` | `175092` | `348dacd8e7ae6c99358c550754eb76444de7a9df74e4605052435f45bda5d82c` |

Both images have:

- MCUboot magic `0x96f3b83d`;
- a `0x200`-byte header;
- header version `0.0.0+0`;
- no protected TLV area and therefore no image security-counter TLV;
- the same key hash, `fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994`;
- a SHA-256 digest TLV; and
- an RSA-2048 signature TLV.

The application digest is
`76653d113bc50a8e1736cf4b59cfcaa37331798d3d606f1ebe848d59a3687c07`; the network-core digest is
`cb906e94327939a868ff35991ada433437248bf2c2339ea454f64a2e7080dc3f`.

`imgtool verify` validated both signatures against the exact
[`root-rsa-2048.pem`](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/bootloader/mcuboot/root-rsa-2048.pem)
file from the release commit. That file is an RSA private key committed in the public upstream; its
file SHA-256 is `1fc912d30251b821f251e127d4daf7ba9338dd5c04e5af100abfb5b7c7d4c022`.
Gumi records the fingerprint but does not copy the key.

## Nested network-core trust

The network image has a second trust layer inside the outer MCUboot envelope: a 176-byte Nordic Secure
Immutable Boot validation record. Its observed fields are:

| Field | Published value |
| --- | --- |
| File offset | `174580` |
| Firmware address | `0x01008800` |
| Firmware SHA-256 | `553ec794aa16d30c871a1f028a613da8e13eccb526006ddf8b5c5251d36ada7c` |
| Embedded raw P-256 public-key SHA-256 | `c4bd988a114092a4578b4157e33fbc4b7f64df616470f4de339f930ead9ea3f9` |
| Signature | 64-byte ECDSA P-256, independently verified |

The clean exact-source build reproduced the network firmware hash but not this public key. Upstream
leaves `SB_CONFIG_SECURE_BOOT_SIGNING_KEY_FILE` empty, causing NCS to generate a new debug NSIB key for
each pristine build. The local public-key SHA-256 was
`b12c84df6fe0297c7959db02377df0878578250b26521ce243a7bde5391567b9`.

This is a material compatibility boundary: stock B0n checks an image's embedded public key against its
provisioned trusted hash. Gumi must not upload a locally generated network image to the sealed unit.
The first canary updates only application image `0` and leaves network image `1` untouched. See
[build-reproduction.md](build-reproduction.md).

## Boot-policy consequence

The release's
[`mcuboot.conf`](https://github.com/BasedHardware/omi/blob/aa1133cd17139aa09cbe4883cdf51f15094b9916/omi/firmware/omi/sysbuild/mcuboot.conf)
enables overwrite-only upgrade and software downgrade prevention. NCS 2.9.0 pins MCUboot
`v2.1.0-ncs3`, and that loader rejects a secondary image when its version compares **lower** than the
primary image (`rc < 0`). Equal versions are not rejected by that check. This is an inference from the
[exact loader source](https://github.com/nrfconnect/sdk-mcuboot/blob/v2.1.0-ncs3/boot/bootutil/src/loader.c#L1168-L1223),
and it still needs proof against the owned unit.

This creates a strict compatibility-phase rule:

1. Keep both first Gumi canary MCUboot headers exactly `0.0.0+0`.
2. Put the unambiguous Gumi revision in the application-level Device Information/capability response,
   not in the boot header.
3. Prove that the official `0.0.0+0` package can replace the canary before changing functional capture
   behavior.
4. Do not raise the boot version while the stock bootloader is the only recovery path. A higher accepted
   version could make every published stock recovery image a prohibited downgrade.
5. Introduce project-owned release ordering through a signed Gumi manifest/updater. A security counter
   cannot be retrofitted into the stock bootloader merely by adding a TLV to an application image.

Nordic's Android Device Manager also documents that nRF5340 multi-image upgrades support
`CONFIRM_ONLY`, not test-and-revert. The canary therefore needs a deliberately tiny change and a proven
forward recovery package; automatic rollback is not available.

## Reproduction result

The observation and clean build were made in isolated temporary environments:

```text
sha256(Omi_CV1_OTA_v3.0.20.zip)
unzip -> manifest.json, omi.signed.bin, ipc_radio.bin
imgtool dumpinfo <each image>
imgtool verify -k <pinned upstream compatibility key> <each image>
pinned container + NCS 2.9.0 -> pristine exact-SHA sysbuild
compare normalized manifest, payload digests, nested network validation, and compiled configuration
```

The application payload and network firmware bytes reproduced exactly. Randomized outer signatures,
manifest timestamps, and the auto-generated network NSIB key prevent whole-file equality. The full
comparison and exact artifact hashes are in [build-reproduction.md](build-reproduction.md). No image
has been uploaded to the owned device.
