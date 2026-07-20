# Omi CV1 build reproduction: v3.0.20

Status: exact release source builds successfully; application payload reproduced; locally generated
network-core trust material is not compatible evidence for the sealed unit.

No physical device was contacted, updated, rebooted, or otherwise modified during this work.

## Verdict

The upstream v3.0.20 build proves the pinned toolchain and an **application-core-only** development
path:

- the exact release commit builds successfully in the pinned Zephyr CI container;
- the generated package has the same manifest structure, image indexes, slots, load addresses, image
  sizes, partition map, boot versions, algorithms, and outer key hash as the published package;
- the application header and payload are byte-for-byte identical to the published application image,
  producing the same MCUboot digest; and
- both locally generated outer images pass `imgtool verify` with the upstream compatibility key.

The complete ZIP is deliberately **not** qualified for upload. The network-core build uses a newly
generated Nordic Secure Immutable Boot (NSIB) ECDSA key because upstream leaves
`SB_CONFIG_SECURE_BOOT_SIGNING_KEY_FILE` empty. Its embedded public key differs from the one in the
published network image. A shipped B0 network bootloader checks that key against a provisioned trusted
hash, so a clean local network build should be treated as incompatible until proven otherwise.

M1 does not need a custom network controller. All planned capture, button, storage, BLE GATT, privacy,
and update-policy behavior lives on the application core. However, the owned unit reports v3.0.12, so
this v3.0.20 reproduction is not the basis for its first canary. First reproduce the exact v3.0.12
application, qualify a byte-different v3.0.12 canary, and establish exact-stock recovery from that
observable canary state. The canary leaves the network core untouched and uploads image `0` only.

The installed v3.0.12 application has since been reproduced in its prescribed NCS v2.9.0 toolchain.
The passing result and the quarantined earlier wrong-SDK attempt are documented separately in
[build-reproduction-v3.0.12.md](build-reproduction-v3.0.12.md).

## Pinned inputs

| Input | Pin |
| --- | --- |
| Omi source | `aa1133cd17139aa09cbe4883cdf51f15094b9916` |
| Container | `ghcr.io/zephyrproject-rtos/ci:v0.26.13@sha256:b0ac6334d1926cd0971a0a444f7adc6dd020e88ee3ce865aa070b6475a3ac4eb` |
| NCS manifest | `v2.9.0`, `7787b264984022cda64d9629278942053e6462a5` |
| Zephyr | `v3.7.99-ncs2`, `1f8f3dc291420c70cd39e77a5cdc954561d4a08f` |
| MCUboot | `v2.1.0-ncs3`, `12e5ee106034972b0f1074d6f2261b2b39d1501b` |
| Zephyr SDK | `0.16.8` |
| Upstream build script SHA-256 | `8b40a63ede4eeb3ccbed5178964c4e09766bf661cad5768648aeb97fdfe0393b` |
| `omi.conf` SHA-256 | `39937ecf6042710eeaf17bb5d537f1e19cdaa1469214ac692f9db35daa26eb1f` |

The source checkout and build were isolated under `/tmp`; the NCS download was not copied into this
repository.

## Build invocation

After checking out the release SHA and verifying it with `git rev-parse HEAD`, the upstream script was
run without source edits:

```sh
docker run --rm \
  -v <exact-release-checkout>/omi/firmware:/omi/firmware \
  -e CMAKE_PREFIX_PATH=/opt/toolchains \
  ghcr.io/zephyrproject-rtos/ci:v0.26.13@sha256:b0ac6334d1926cd0971a0a444f7adc6dd020e88ee3ce865aa070b6475a3ac4eb \
  bash /omi/firmware/scripts/ci/build-cv1.sh
```

The script performs a pristine sysbuild for `omi/nrf5340/cpuapp` and produces the application image,
network image, MCUboot, B0n, partition metadata, OTA bundle, and full-flash HEX files.

## Artifact comparison

| Property | Published v3.0.20 | Exact-source local build | Result |
| --- | --- | --- | --- |
| OTA ZIP bytes | `424502` | `424502` | Match |
| OTA ZIP SHA-256 | `dfc7ea6986d9b02fe899a38afc6c9bf6fabb9cff669244fbf20a3d7abeda59da` | `b965fe0f984c4ad09951bc15e71090affc26f763c600a66a4ba31f7b1c80a186` | Expected difference |
| Application bytes | `248180` | `248180` | Match |
| Application MCUboot digest | `76653d113bc50a8e1736cf4b59cfcaa37331798d3d606f1ebe848d59a3687c07` | Same | Exact payload match |
| Network bytes | `175092` | `175092` | Match |
| Network inner firmware hash | `553ec794aa16d30c871a1f028a613da8e13eccb526006ddf8b5c5251d36ada7c` | Same | Exact firmware match |
| Network NSIB public-key SHA-256 | `c4bd988a114092a4578b4157e33fbc4b7f64df616470f4de339f930ead9ea3f9` | `b12c84df6fe0297c7959db02377df0878578250b26521ce243a7bde5391567b9` | **Trust mismatch** |
| Normalized manifest | Published values | Same after removing build/modification times | Match |

The locally generated observation hashes were:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `dfu_application.zip` | `424502` | `b965fe0f984c4ad09951bc15e71090affc26f763c600a66a4ba31f7b1c80a186` |
| `omi.signed.bin` | `248180` | `97f1c03bcc8723f0e26bc293010579c586a9ae46823162da656b461e160b2dbc` |
| `ipc_radio.bin` | `175092` | `979ceadce6a40341f4478562253f43fc0270b367560571f8d35961bb41b2d55e` |
| `merged.hex` | `827000` | `65ddef7e70b12430dc9c47009a30a14dbdaa6e52340e12a1a3f568bb4e36ad9c` |
| `merged_CPUNET.hex` | `533278` | `03aea421cf53b9f22c26d86ac8f22ea85b2a7a530a158fdaa54bb7930df9d1e7` |

These local hashes are evidence for this run, not stable release identifiers. The generated ZIP embeds
timestamps and both signing paths are nondeterministic.

### Why the application files are not byte-identical

The application files match through offset `247923`, including the complete MCUboot header, payload,
SHA-256 TLV, and key-hash TLV. Only the final 256-byte RSA signature differs. MCUboot uses RSA-PSS with
a random salt, so two valid signatures over the same digest are normally different. Both signatures
verify against the same compatibility key.

### Why the network files differ

The first `174628` bytes are identical. The remaining 128 bytes of the inner NSIB validation record are
the 64-byte public key and 64-byte ECDSA signature. The source build generated a new private key at
`build/GENERATED_NON_SECURE_SIGN_KEY_PRIVATE.pem`, so both fields differ. The published and locally
generated inner ECDSA signatures were independently verified against their respective embedded public
keys.

The different inner validation record changes the network image's outer MCUboot digest; its outer
RSA-PSS signature then differs as well. This is not harmless timestamp drift: the embedded public key
is an actual trust-boundary mismatch.

## Compiled policy evidence

The pristine build confirms:

- `CONFIG_BOOT_UPGRADE_ONLY=y`;
- `CONFIG_MCUBOOT_DOWNGRADE_PREVENTION=y`;
- `CONFIG_BOOT_VALIDATE_SLOT0=y`;
- RSA-2048 MCUboot signatures and two updateable images;
- application slot `0x00010000..0x00100000` and external secondary slot
  `0x00000000..0x000f0000`;
- network secondary slot `0x000f0000..0x00130000`;
- `CONFIG_MCUMGR_TRANSPORT_BT_PERM_RW=y`, while its encrypted and authenticated permission options are
  disabled;
- one BLE connection and application L2CAP TX MTU `498`;
- offline storage, button, haptic, software VAD, and T5838 hardware AAD enabled;
- application-level accelerometer and speaker capabilities disabled; and
- Wi-Fi disabled in this release.

The generated application configuration does compile I2C and the LSM6DSL driver, despite disabling the
application-level accelerometer capability. The driver presence must not be mistaken for a released
motion GATT/API surface.

## Qualification decision

Stage 1 passes as v3.0.20 toolchain and application-core evidence. It does not qualify a cross-release
mutation of the installed v3.0.12 unit. The process stops after read-only inspection and offline updater
review unless the owner provides a new explicit go/no-go. The future sequence is governed by
[sealed-device-plan.md](sealed-device-plan.md):

1. complete the Android read-only inventory;
2. confirm the installed application/network image rows and the SMP image-number behavior;
3. prepare and review an Android updater that targets image `0` without writing image `1`, without
   invoking it against the pendant;
4. only after the first explicit go/no-go, install the exact byte-different Gumi v3.0.12 canary and
   verify the canary application hash plus the unchanged network hash;
5. only after a subsequent recovery go/no-go, install the exact official v3.0.12 application from the
   canary source state and verify both published image hashes;
6. under a later separate approval, repeat the canary transition; and
7. proceed to functional capture changes only after the application-only round trip is repeatable.

An official-stock-to-identical-stock operation is not used as qualification because the maintained
high-level updater skips an already-active hash and lower-level confirmation is hash-addressed.

Treat any complete v3.0.12 to v3.0.20 vendor migration as a separate experiment with its own explicit
go/no-go; it is not part of the first canary qualification.

Do not upload the locally generated `ipc_radio.bin`, `dfu_application.zip`, `merged.hex`, or
`merged_CPUNET.hex` to the sealed consumer unit.
