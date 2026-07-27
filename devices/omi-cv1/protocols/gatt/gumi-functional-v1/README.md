# Gumi Omi CV1 functional GATT v1

This is the device/edge boundary for the first functional Gumi firmware. It is
firmware-declared and exact-board link-qualified, but not yet observed on the
owned device.

The firmware advertises as `Gumi`, retains the Omi family discriminator service
`19b10000-e8f2-537e-4f6c-d104768a1214`, and puts the Gumi functional service in
the scan response:

- service: `47554d49-0001-4f4d-492d-435631000001`;
- status: `47554d49-0002-4f4d-492d-435631000001`, read and notify; and
- capabilities: `47554d49-0003-4f4d-492d-435631000001`, read.

There is deliberately no remote capture-command or media-stream characteristic
in v1. Double tap is the only recording toggle. Hold requests a VoiceTurn, but
the firmware refuses it because no authenticated real-time route exists yet.
The edge driver must not infer remote control or live audio support from the
presence of the functional service.

## Status snapshot

Status is an exact 40-byte little-endian snapshot. Bytes 32 through 39 are
reserved and must be zero. Unknown versions are not decoded as v1.

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 1 | status version, `1` |
| 1 | 1 | capture phase |
| 2 | 1 | microphone truth |
| 3 | 1 | supervisor storage state |
| 4 | 1 | recording-key truth |
| 5 | 1 | recording-storage truth |
| 6 | 1 | codec truth |
| 7 | 1 | flags |
| 8 | 8 | active recording ID, or zero |
| 16 | 8 | currently observed free bytes |
| 24 | 4 | first latched negative errno as signed `int32`, or zero |
| 28 | 4 | wrapping publication generation |
| 32 | 8 | reserved zero |

The generation is continuity evidence, not durable event identity. A client
reads a full snapshot after connecting, subscribes if it wants updates, and
treats any disconnect as a need to read fresh state.

The Omi CV1 edge driver now negotiates this exact topology as the device-neutral
`gumi.capture-state` v1 capability. The operational runtime accepts the initial
read and notifications as versioned device-reported observations. A disconnect
immediately removes their freshness while conservatively retaining the last
observation, so a last-known recording becomes “may still be recording” rather
than verified off. This is read-only state support, not capture-control support.

The operational flag is not equivalent to “recording.” Audio permission comes
only from the base- or voice-audio flags, and the privacy flag reports the
firmware's logical privacy guard. A fault flag or nonzero error requires the
shell to show degraded state even if a previous operational bit was observed.

## Capabilities

The 16-byte immutable descriptor advertises descriptor version `1`, interface
versions for audio input, capture control, button gesture, visual indicator,
haptic, local media store, and firmware update, followed by a feature bitset.
For this firmware the only feature bits are:

- bit 0: local recording; and
- bit 1: read-only state.

Live media, remote capture control, media export, deletion, and real-time
VoiceTurn are absent. They require new explicit characteristics and a revised
capability contract rather than reinterpretation of reserved bytes.

## Update boundary

MCU Manager is a separate standard transport. Image 0 upload is admitted only
when capture is locally proven idle with the microphone off and either the
button was held continuously for two seconds during boot or it was held for
five seconds at runtime. Runtime confirmation makes maintenance exclusive until
reboot, including on a fail-closed boot where the HUK or storage is unavailable.
Image 1 is not admitted. Merely connecting, subscribing, or writing an MCU
Manager packet does not authorize an update.

The machine-readable contract is [profile.json](profile.json).
