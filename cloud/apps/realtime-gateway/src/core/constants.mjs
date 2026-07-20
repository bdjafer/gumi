export const PROTOCOL_VERSION = 'gumi.realtime.v1'
export const CREDENTIAL_AUDIENCE = 'gumi.realtime-gateway'
export const VOICE_TURN_SCOPE = 'realtime-gateway:voice-turn'

export const LIMITS = Object.freeze({
  maxControlFrameBytes: 16 * 1024,
  audioEnvelopeBytes: 72,
  maxAudioPayloadBytes: 16 * 1024,
  maxAudioFrameBytes: 72 + 16 * 1024,
  maxAudioBytesPerTurn: 16 * 1024 * 1024,
  maxChunksPerTurn: 8_192,
  maxResultTextBytes: 4 * 1024,
  maxProviderTraceLength: 160,
  maxPendingMessages: 8,
  maxSessions: 1_024,
  maxSessionTtlMs: 5 * 60 * 1_000,
  helloTimeoutMs: 5_000,
})

export const EMPTY_SEQUENCE_DIGEST =
  'sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'

export const TRANSITIONAL_TURN_STATES = new Set([
  'opening-provider',
  'sending-audio',
  'finishing',
  'cancelling',
  'reconciling',
])

export const TERMINAL_TURN_STATES = new Set(['completed', 'cancelled', 'failed'])
