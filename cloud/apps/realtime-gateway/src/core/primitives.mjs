import { createHash } from 'node:crypto'

import { EMPTY_SEQUENCE_DIGEST } from './constants.mjs'
import { GatewayError } from './error.mjs'

export const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
export const SHA256_DIGEST = /^sha256:[0-9a-f]{64}$/
export const SAFE_OPAQUE = /^[A-Za-z0-9][A-Za-z0-9._:/-]*$/

export function sha256(bytes) {
  return `sha256:${createHash('sha256').update(bytes).digest('hex')}`
}

export function stableDigest(value) {
  return sha256(Buffer.from(JSON.stringify(value), 'utf8'))
}

export function nextSequenceDigest(previousDigest, sequence, payloadDigest) {
  if (!SHA256_DIGEST.test(previousDigest) || !SHA256_DIGEST.test(payloadDigest)) {
    throw new GatewayError('INVALID_DIGEST', 'sequence digest input is invalid')
  }
  const sequenceBytes = Buffer.alloc(4)
  sequenceBytes.writeUInt32BE(sequence)
  return sha256(
    Buffer.concat([
      Buffer.from(previousDigest.slice(7), 'hex'),
      sequenceBytes,
      Buffer.from(payloadDigest.slice(7), 'hex'),
    ]),
  )
}

export function scopeFingerprint(authority) {
  return stableDigest({
    deploymentBindingId: authority.deploymentBindingId,
    deviceId: authority.deviceId,
    edgeHostId: authority.edgeHostId,
    admissionId: authority.admissionId,
    sessionId: authority.sessionId,
  })
}

export function providerIdempotencyKey(authority, turnId, retryId) {
  return stableDigest({
    application: 'realtime-gateway',
    version: 1,
    scope: scopeFingerprint(authority),
    turnId,
    retryId,
  })
}

export function initialTurnProgress() {
  return {
    nextSequence: 0,
    receivedBytes: 0,
    sequenceDigest: EMPTY_SEQUENCE_DIGEST,
    chunkDigests: [],
  }
}

export function byteLength(value) {
  return Buffer.byteLength(value, 'utf8')
}
