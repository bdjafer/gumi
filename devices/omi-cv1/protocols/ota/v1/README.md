# Omi CV1 OTA protocol evidence v1

This directory records machine-readable facts about qualified OTA artifacts. It stores metadata and
digests, not firmware binaries or signing keys.

Consumers must verify the ZIP digest before trusting its extracted observations. A Gumi release check
will eventually decode each MCUboot image itself and compare:

- image/slot mapping and load address;
- header magic, size, flags, and version;
- protected and unprotected TLVs;
- image digest, key hash, and signature algorithm; and
- nested NSIB validation metadata for the nRF5340 network image; and
- the complete compatibility-signature verification result.

The observations serve two distinct compatibility decisions:

- [stock-v3.0.12.json](stock-v3.0.12.json) is the installed-release oracle for the owned pendant. Its
  MCUboot TLV digests are the hashes expected from the pending semantic image-state read.
- [stock-v3.0.20.json](stock-v3.0.20.json) is a later migration/reference oracle. It is not the first
  stock-recovery basis for the installed v3.0.12 unit, and migration from that release is not yet
  proven.

Neither file is a Gumi release manifest, and the public upstream key is not a Gumi trust authority.
Each published network image's embedded NSIB public-key fingerprint is also release-specific evidence:
a clean upstream build generates a different key and must not be treated as sealed-device-compatible.

## Application reproduction check

Compare a trusted official application image with a local build using:

```sh
node devices/omi-cv1/protocols/ota/v1/compare-mcuboot-application.mjs \
  /path/to/official/omi.signed.bin /path/to/local/omi.signed.bin
```

The dependency-free checker validates MCUboot bounds and the encoded SHA-256 digest, requires the
key-hash and final 256-byte RSA-2048 PSS TLVs, and compares every byte through the RSA TLV header. Only
the randomized RSA-PSS signature value may differ. It does not make an untrusted input official or
replace signature verification against a separately pinned compatibility key.
