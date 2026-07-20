# Omi CV1 build reproduction: v3.0.12

Status: **passed for the application core** in the release-prescribed Nordic NCS v2.9.0 toolchain.
Every deterministic application byte matches the published v3.0.12 image. Network-core and complete
multi-image artifacts remain disqualified from the sealed device.

No physical device was contacted, updated, rebooted, or otherwise modified during this work.

## Verdict

The exact release tag reproduces the official application when built in Nordic's immutable NCS v2.9.0
toolchain image. RSA-PSS intentionally uses a random salt, so the final 256 signature bytes and whole-file
hash differ; both signatures verify independently and all bytes before the signature value are identical.

| Property | Published v3.0.12 | Canonical clean build | Result |
| --- | ---: | ---: | --- |
| Application bytes | `228632` | `228632` | Match |
| MCUboot payload bytes | `227784` | `227784` | Match |
| MCUboot digest | `0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36` | Same | Match |
| Deterministic bytes | `228376` | `228376` | **Byte-for-byte match** |
| MCUboot version | `0.0.0+0` | `0.0.0+0` | Match |
| MCUboot key hash | `fc5701dc6135e1323847bdc40f04d2e5bee5833b23c29f93593d00018cfa9994` | Same | Match |
| RSA-PSS verification | Valid | Valid | Match |
| Complete file SHA-256 | `877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db` | `43e7db034ea55a5642dafd6c200994a7346319ca7ef1e96dcd84fb357f3c9616` | Expected signature-salt variance |

The checked-in dependency-free comparator validates the MCUboot structure, encoded payload digest,
key-hash TLV, RSA-2048/PSS TLV, and deterministic byte range:

```sh
node devices/omi-cv1/protocols/ota/v1/compare-mcuboot-application.mjs \
  /path/to/official/omi.signed.bin /path/to/local/omi.signed.bin
```

Canonical result:

```text
PASS: 228376 deterministic bytes match (header=512, payload=227784, file=228632); only the RSA-PSS signature bytes differ, as permitted.
```

## Pinned inputs and environment

| Input | Pin |
| --- | --- |
| Omi source | `Omi_CV1_v3.0.12` / `85159556eac753a088c5efd1b419a5a867508e27` |
| Omi configuration SHA-256 | `72838438b5999cd60c391f541f43cbc8d97ae1d403a913bba29b10c32ed04f65` |
| NCS manifest | `v2.9.0`, `7787b264984022cda64d9629278942053e6462a5` |
| Zephyr | `v3.7.99-ncs2`, `1f8f3dc291420c70cd39e77a5cdc954561d4a08f` |
| MCUboot | `v2.1.0-ncs3`, `12e5ee106034972b0f1074d6f2261b2b39d1501b` |
| Nordic toolchain image | `ghcr.io/nrfconnect/sdk-nrf-toolchain:v2.9.0@sha256:7e9b61475ca05b8517079bedc8645479101fdaa17de2d0fa06a1633288112db2` |
| Container platform | `linux/amd64` |
| Nordic toolchain bundle | `/opt/ncs/toolchains/b77d8c1312` |
| Zephyr SDK / compiler | `0.17.0` / Zephyr GCC `12.2.0` |

The tag's build guide prescribes `nrfutil toolchain-manager launch --ncs-version v2.9.0`. The immutable
Nordic image above provides the same NCS v2.9.0 toolchain family and records an auditable container
digest. The resulting application configuration contains `CONFIG_TOOLCHAIN_ZEPHYR_0_17=y`.

The generated bootloader configuration also confirms:

- `CONFIG_BOOT_UPGRADE_ONLY=y`;
- `CONFIG_MCUBOOT_DOWNGRADE_PREVENTION=y`;
- `CONFIG_UPDATEABLE_IMAGE_NUMBER=2`;
- application image number `0` and network-core image number `1`; and
- overwrite-only application updates with boot version `0.0.0+0`.

Primary environment references:

- [Omi v3.0.12 build and OTA guide](https://github.com/BasedHardware/omi/blob/Omi_CV1_v3.0.12/omi/firmware/BUILD_AND_OTA_FLASH.md)
- [NCS v2.9.0 tool versions](https://github.com/nrfconnect/sdk-nrf/blob/v2.9.0/scripts/tools-versions-linux.yml)
- [Nordic toolchain container documentation](https://github.com/nrfconnect/sdk-nrf/blob/v2.9.0/scripts/docker/README.rst)

## Root cause of the failed first attempt

The quarantined first attempt borrowed the later v3.0.20 Zephyr CI container, which contains Zephyr SDK
`0.16.8`. That is not the SDK prescribed by NCS v2.9.0, whose tool metadata selects `0.17.0`. It produced
an application payload 128 bytes larger than the official image. Rebuilding the same source and NCS
pins with SDK `0.17.0` removes the 128-byte difference and passes the complete deterministic comparison.

| Quarantined artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Wrong-toolchain `dfu_application.zip` | `405082` | `f2d2c96a383db7b95fc96901941f824656a4a525843f176d4547453d0b8f76c3` |
| Wrong-toolchain `omi.signed.bin` | `228760` | `aca1886823d56b2dfa60dac8adab499eb6e73ce7e6dbee95b8efc7e9ae166882` |

These hashes identify a failed experiment only and must never enter an updater allowlist.

## Artifact boundary

The official artifacts remain the recovery oracle:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Official release ZIP | `404954` | `821ce06d73f8bb3695de70dce0880a00597dd71175a843d08e577d775125ab4e` |
| Official `omi.signed.bin` | `228632` | `877990aabf267fb3f281803cfa3c2aec8f29a86bfa8fb4c05c79a024b07db9db` |

The canonical clean build produced a `404954`-byte ZIP with SHA-256
`a574279b6ec8baf6c624beb3bffc8fa0f7f5680b11439bd0d0889162d57394f1`. Its application is qualified as
a reproducible development lineage, but its network image is not compatible evidence:

| Network artifact | SHA-256 |
| --- | --- |
| Official v3.0.12 `ipc_radio.bin` | `0e1d067444ca78dd4ea64cb355bc167c1155b84f49b0c7028112c9930602d2a5` |
| Canonical clean-build `ipc_radio.bin` | `25bc69c95a08fff590196cd53016d5a90eb57e49432018b84a0c82c771615c7c` |

The clean network build embeds a newly generated NSIB key. Never upload that network image, the locally
generated complete ZIP, or merged HEX artifacts to the sealed device. The application-only path must
leave installed image `1` untouched.

## Qualification decision

The offline application-reproduction gate is complete. Canary `0001` has also been built and qualified
as one exact signed application artifact; its MCUboot image hash is
`d3b3c74c10c4fa110763ae5523d0aeaa20b9f815b8674167486be6ff6f8052ce`. This does **not** authorize a
firmware write. The process stops after the read-only inspection and offline updater review unless the
owner provides a new explicit go/no-go. The remaining sequence, governed by
[sealed-device-plan.md](sealed-device-plan.md), is:

1. read the owned unit's image state and require an exact v3.0.12 oracle match;
2. prepare and review a single-image updater that can target application image `0` without writing
   image `1`, without invoking it against the pendant;
3. only after an explicit go/no-go, install the exact byte-different canary application and verify its
   image hash, visible identity, and the unchanged network image after reboot; and
4. only after a subsequent explicit recovery go/no-go, install the exact official v3.0.12 application
   and verify both published image hashes after reboot.

An identical official-stock-to-stock upload is not a transport rehearsal: Nordic's high-level updater
skips an image whose hash is already active, while the lower-level confirm operation is hash-addressed.
The byte-different canary is therefore the first operation that can prove upload, selection, boot, and
recovery without guessing from an ambiguous same-hash result.
