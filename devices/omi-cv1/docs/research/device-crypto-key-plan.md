# Omi CV1 device recording-key plan

Status: source-backed design for the pinned Omi v3.0.12 / NCS v2.9.0 substrate. No key state has been
read or changed on the owned consumer unit.

## Confirmed substrate

The Omi CV1 application core is an nRF5340 with CryptoCell CC312. The pinned NCS tree contains Nordic's
PSA Crypto AES-GCM sample and enables the required surface with:

```text
CONFIG_NRF_SECURITY=y
CONFIG_MBEDTLS_PSA_CRYPTO_C=y
CONFIG_PSA_WANT_GENERATE_RANDOM=y
CONFIG_PSA_WANT_KEY_TYPE_AES=y
CONFIG_PSA_WANT_ALG_GCM=y
```

The pinned CC3XX driver selects accelerated AES-128, AES-192, and AES-256 GCM on CC312. Gumi therefore
uses the existing PSA one-shot AEAD API and CSPRNG; it does not implement AES, GCM, or an entropy source.
The stock Omi application and bootloader configs do not enable `HW_UNIQUE_KEY`, `NRF_SECURITY`, PSA
Crypto, or trusted storage, so source inspection provides no evidence that the consumer unit already
has a usable HUK/KMU recording-key state.

## Boundary and unresolved choice

[`crypto_port.c`](../../firmware/gumi/zephyr/omi-v3012/src/crypto_port.c) intentionally accepts a
pre-authorized AES-256/GCM PSA key handle. It initializes PSA, gets CSPRNG bytes, validates key
attributes, and encrypts/authenticates journal records. It cannot create, persist, rotate, destroy, or
select the key.

The final provider should derive a versioned recording key from a device root secret, because a
volatile random key makes reboot recovery impossible and a plaintext key in the SD or ordinary app
settings defeats at-rest protection. Nordic's HUK library can derive key bytes from the nRF5340 KDR,
but its sample also writes random HUK material when none exists. The library documents such a write as
one-time until mass erase. Gumi must never copy that sample's automatic write behavior.

The next key gate is therefore:

1. compile a read-only probe that reports only whether the relevant HUK state is present, never key
   bytes and never a derived-key digest;
2. run that probe only after the existing application-only recovery route is qualified and the user
   gives a fresh go/no-go;
3. if present, prove deterministic labeled derivation of a 256-bit volatile PSA AES/GCM handle across
   reboot without logging or exporting the key;
4. if absent, stop and separately decide between explicit one-time HUK provisioning and a weaker
   persistent-key design; and
5. qualify recovery, rotation, stock rollback, firmware update, and key-loss behavior before real audio
   is written.

The derivation label must be domain-separated and versioned, for example
`gumi/omi-cv1/recording/aes-gcm/v1`. The journal header stores only a nonsecret logical key version. A
rotation keeps old versions available for authenticated recovery until retention proves that no file
references them. Deleting a key is therefore destructive media deletion and requires the same explicit
maintenance authority.

## Physical implications

This investigation requires no enclosure opening, debug probe, or custom hardware. A future read-only
application image and later functional image can use the already identified BLE MCUboot image-0 update
path. It is still a firmware mutation and remains prohibited until the phone is reconnected, stock
recovery is rechecked, and the user approves that exact image.
