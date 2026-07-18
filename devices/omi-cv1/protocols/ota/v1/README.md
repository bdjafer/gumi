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

The stock observation in [stock-v3.0.20.json](stock-v3.0.20.json) is a recovery oracle. It is not a
Gumi release manifest and the public upstream key is not a Gumi trust authority. The published network
image's embedded NSIB public-key fingerprint is also an oracle: a clean upstream build generates a
different key and must not be treated as sealed-device-compatible.
