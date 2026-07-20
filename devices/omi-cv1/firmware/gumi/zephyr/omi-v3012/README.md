# Omi CV1 v3.0.12 Zephyr ports

This directory contains hardware-bound adapters for the pinned Omi CV1 v3.0.12 / NCS v2.9.0
substrate. They are separate from the portable policy kernels and are not a firmware candidate.

`mic_port.c` replaces the unsafe stock stop/start shape at the adapter boundary:

- initialization configures PDM but leaves it verified off;
- every acquisition creates a fresh thread on caller-owned static storage;
- release gates callbacks first, issues DMIC STOP, joins the thread, drains completed slab buffers,
  then reconfigures PDM as an explicit asynchronous-stop barrier;
- a failed join, STOP, or barrier leaves microphone truth `UNKNOWN`; and
- fault callbacks may only enqueue supervisor work, avoiding self-join from the mic thread.

`codec_port.c` creates a fresh bundled-Opus encoder and fixed PCM ring for every nonzero session token.
It rejects stale tokens, faults rather than silently dropping on exhaustion, assigns a monotonic packet
sequence, and joins/purges the worker before a successful close returns.

`crypto_port.c` is the narrow Nordic PSA Crypto adapter for the encrypted recording journal. It borrows
an already-authorized AES-256/GCM key handle, validates its attributes once per session, uses the plan's
nonce and authenticated data verbatim, requires exact output sizes, and zeroes expected output spans on
failure. It performs no HUK/KMU writes, persistent-key creation, rotation, or destruction. Those are a
separate provisioning boundary because writing the nRF5340 hardware unique-key slots is not an ordinary
runtime operation.

The microphone and historical codec compile/link overlays are not behavioral firmware. The current
codec revision, journal, crypto port, storage writer, invocation, button composition, privacy-output
composition, and physical use remain prohibited until their independent current-source gates exist.
