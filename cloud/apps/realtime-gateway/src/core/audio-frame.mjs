import { LIMITS } from './constants.mjs'
import { GatewayError } from './error.mjs'
import { SHA256_DIGEST, UUID_V7, sha256 } from './primitives.mjs'

const MAGIC = Buffer.from('GRT1', 'ascii')

function uuidToBytes(uuid) {
  if (!UUID_V7.test(uuid)) throw new GatewayError('INVALID_IDENTITY', 'audio frame identity must be UUIDv7')
  return Buffer.from(uuid.replaceAll('-', ''), 'hex')
}

function bytesToUuid(bytes) {
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function encodeAudioFrame({ turnId, retryId, sequence, payload, contentDigest = undefined }) {
  const body = Buffer.from(payload)
  if (body.length === 0 || body.length > LIMITS.maxAudioPayloadBytes) {
    throw new GatewayError('AUDIO_FRAME_SIZE_INVALID', 'audio payload is empty or exceeds the per-frame limit')
  }
  if (!Number.isSafeInteger(sequence) || sequence < 0 || sequence > 0xffff_ffff) {
    throw new GatewayError('SEQUENCE_INVALID', 'audio sequence is outside uint32')
  }
  const digest = contentDigest ?? sha256(body)
  if (!SHA256_DIGEST.test(digest)) throw new GatewayError('INVALID_DIGEST', 'audio digest is invalid')

  const frame = Buffer.alloc(LIMITS.audioEnvelopeBytes + body.length)
  MAGIC.copy(frame, 0)
  uuidToBytes(turnId).copy(frame, 4)
  uuidToBytes(retryId).copy(frame, 20)
  frame.writeUInt32BE(sequence, 36)
  Buffer.from(digest.slice(7), 'hex').copy(frame, 40)
  body.copy(frame, LIMITS.audioEnvelopeBytes)
  return frame
}

export function decodeAudioFrame(frame) {
  const bytes = Buffer.from(frame)
  if (bytes.length <= LIMITS.audioEnvelopeBytes || bytes.length > LIMITS.maxAudioFrameBytes) {
    throw new GatewayError('AUDIO_FRAME_SIZE_INVALID', 'binary audio frame is empty or exceeds the bounded envelope')
  }
  if (!bytes.subarray(0, 4).equals(MAGIC)) {
    throw new GatewayError('AUDIO_FRAME_MAGIC_INVALID', 'binary frame is not a Gumi realtime v1 audio frame', {
      closeCode: 1002,
    })
  }
  const payload = bytes.subarray(LIMITS.audioEnvelopeBytes)
  const contentDigest = `sha256:${bytes.subarray(40, 72).toString('hex')}`
  if (sha256(payload) !== contentDigest) {
    throw new GatewayError('AUDIO_CONTENT_DIGEST_MISMATCH', 'audio frame digest does not match its payload')
  }
  return {
    turnId: bytesToUuid(bytes.subarray(4, 20)),
    retryId: bytesToUuid(bytes.subarray(20, 36)),
    sequence: bytes.readUInt32BE(36),
    contentDigest,
    payload,
  }
}
