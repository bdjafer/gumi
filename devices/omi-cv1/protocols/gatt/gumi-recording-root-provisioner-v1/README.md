# Gumi Omi CV1 recording-root provisioner GATT v1

This profile is the status-only boundary for the one-shot recording-root provisioner. It is
firmware-declared and exact-board link-qualified, but has not yet been observed on the owned device.
It exposes no capture, media, key, digest, or arbitrary key-slot read surface.

The provisioner advertises as `Gumi`, retains the empty Omi-family discriminator, and exposes one
12-byte read/notify status characteristic:

- service: `47554d49-0010-4f4d-492d-435631000001`;
- status: `47554d49-0010-4f4d-492d-435631000002`.

A successful terminal status must prove transport ready, microphone verified off, MEXT present,
domain-separated derivation verified, post-terminal MCU Manager mutation admitted, and zero error.
`PROVISIONED` additionally reports that this boot attempted the write. Before that terminal
admission, both image upload and remote reset are denied even though the recovery transport is
available.

MEXT spans two irreversible hardware slots. The application feeds its hardware-backed watchdog
immediately before the write, but it cannot make the hardware primitive power-loss atomic. Physical
qualification therefore requires stable charger power and no button, cable, or power disturbance
from upload through the freshly read terminal status.

The machine-readable contract is [profile.json](profile.json).
