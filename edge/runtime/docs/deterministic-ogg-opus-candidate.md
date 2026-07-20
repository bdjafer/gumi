# Deterministic Ogg Opus candidate

Status: executable candidate, deliberately not connected to the production capture lifecycle.

The portable runtime accepts only `SequencedRawOpusPacket`: bytes that an upstream driver has already
proved are exactly one complete Opus packet, plus a trustworthy source sequence. It does not infer
packet boundaries from BLE notifications or guess Omi encoder pre-skip. Omi packet delimitation,
sequence recovery, and pre-skip therefore remain hardware-qualification inputs.

## M1 layout

The only layout is `gumi.ogg-opus.single-packet-page.v1`:

- page 0 contains `OpusHead`, has BOS, and has granule 0;
- page 1 contains the exact profile-fixed `OpusTags` packet (vendor `gumi`, zero comments, no
  trailing bytes) and has granule 0;
- every later page contains exactly one audio packet;
- for source sequence `s` and authorized first sequence `f`, audio page sequence is
  `2 + (s - f)`;
- a non-terminal audio granule is `(s - f + 1) * fixedFrameSamples48k`;
- only the final audio page has EOS; its caller-supplied trim is at most one final frame;
- serial number, pre-skip, input rate, channel count, fixed duration, first source sequence, and
  configuration identity are caller facts. M1 exposes no arbitrary tag metadata, and no time, random
  value, or host metadata is injected.

This is stricter than generic Ogg. It lets media-ingest derive page sequence and granule from the
session policy even when chunks arrive sparsely or out of order. Before a durable chunk ACK, the cloud
validator must bind the layout profile, serial, and pre-skip; check the exact first headers; derive the
page sequence/granule from the chunk range and fixed frame duration; and latch EOS as the terminal
range. Deferring those checks until final assembly creates a poison-on-ACK state: an individually
stored chunk can make finalization permanently impossible.

Every mux output contains complete pages and exactly one audio sequence value. The first output also
contains the two header pages. `OggOpusFragmentComposer` checks source/page adjacency, monotonic
granules, one logical-stream start, EOS only at the end, and caller byte/page bounds before copying.
It can therefore batch N contiguous audio pages into one bounded spool object or HTTP chunk without
remuxing. One packet per **Ogg page** does not mean one page per HTTP request. Headers occur only in
the stream's first batch and EOS only in its terminal batch.

## Bounds and recovery

One audio packet is retained so the final packet can carry EOS. Packet bytes are limited to the RFC
6716 single-frame maximum of 1,276 bytes; the M1 tags packet is fixed at 20 bytes. Per-audio-packet
container overhead is `27 + floor(packetBytes / 255) + 1` bytes. For packets up to 254 bytes this is
28 bytes, or 1,400 bytes/second at 20 ms per packet. This intentionally simple overhead must be
measured on real Omi traffic before the layout is promoted.

The versioned snapshot contains the complete config and its identity, next source/page sequence,
decoded 48 kHz granule, header-emission state, and only the pending packet's sequence, byte length,
and canonical SHA-256 replay binding. It never contains microphone payload bytes. The binding is
supplied by a trusted platform/runtime digest port using a standard crypto implementation;
`commonMain` deliberately neither implements SHA-256 nor claims a caller binding authenticates the
payload. A pending packet without that binding may be muxed live but cannot be snapshotted.

The snapshot's `nextSourceSequence` is the durable replay cursor: while a packet is pending it is that
packet's sequence, not the sequence after it. Restore is allowed only when a durable source supplies
the exact pending sequence again; restore checks its sequence, length, and trusted binding, then
re-derives every page/granule field. The device/source cursor cannot advance through that packet until
its page and following snapshot have been committed atomically. A non-replayable live source is
rejected explicitly: after process death the orchestrator must terminate the logical stream and
record a discontinuity instead of pretending it resumed. The snapshot itself is not durable; the
spool adapter still has to persist it, and the durable stream descriptor must bind the same profile,
serial, and pre-skip.

## Why the core is local

[AndroidX Media3 `OggMuxer`](https://developer.android.com/reference/androidx/media3/muxer/OggMuxer)
is a useful Android qualification oracle, but its public surface is `@UnstableApi`, consumes Java
`ByteBuffer`/Media3 types, exposes no process snapshot or fixed page-layout contract, and its Ogg
metadata method is unsupported. It therefore cannot be the Kotlin Multiplatform `commonMain` or the
durable deterministic authority. We should still compare candidate output with Media3 on Android and
exercise playback/parser compatibility.

[VorbisJava](https://github.com/Gagravarr/VorbisJava) is useful as an independent JVM Ogg parser/CRC
and metadata oracle. It is pure Java rather than Kotlin Multiplatform, and its own README says basic
Opus audio-frame support remains outstanding and `OpusFile` is read-oriented. It does not provide the
fixed, resumable Opus packet-to-page core required here. Keep it in offline conformance tooling, not
the portable runtime dependency graph.

Promotion still requires real Omi packet-boundary/pre-skip evidence, durable snapshot/spool wiring,
cloud pre-ACK layout validation, long-run overhead/rollover tests, and cross-checks with at least one
independent Ogg Opus implementation.
