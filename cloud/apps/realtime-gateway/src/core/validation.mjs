import {
  CREDENTIAL_AUDIENCE,
  LIMITS,
  PROTOCOL_VERSION,
  VOICE_TURN_SCOPE,
} from './constants.mjs'
import { GatewayError } from './error.mjs'
import { byteLength, SAFE_OPAQUE, SHA256_DIGEST, UUID_V7 } from './primitives.mjs'

function object(value, context) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new GatewayError('FRAME_INVALID', `${context} must be an object`, { closeCode: 1002 })
  }
  return value
}

function exactKeys(value, expected, context) {
  const actual = Object.keys(value).sort()
  const wanted = [...expected].sort()
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new GatewayError('FRAME_INVALID', `${context} contains unsupported or missing fields`, {
      closeCode: 1002,
    })
  }
}

function string(value, { context, min = 1, max, pattern = undefined }) {
  if (typeof value !== 'string' || value.length < min || value.length > max || (pattern && !pattern.test(value))) {
    throw new GatewayError('FRAME_INVALID', `${context} is invalid`, { closeCode: 1002 })
  }
  return value
}

function uuid(value, context) {
  return string(value, { context, max: 36, pattern: UUID_V7 })
}

function digest(value, context) {
  return string(value, { context, max: 71, pattern: SHA256_DIGEST })
}

function integer(value, { context, min, max }) {
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new GatewayError('FRAME_INVALID', `${context} is outside its integer bounds`, { closeCode: 1002 })
  }
  return value
}

function timestamp(value, context) {
  string(value, { context, max: 32 })
  const parsed = Date.parse(value)
  if (!Number.isFinite(parsed) || new Date(parsed).toISOString() !== value) {
    throw new GatewayError('FRAME_INVALID', `${context} must be a canonical ISO timestamp`, { closeCode: 1002 })
  }
  return value
}

export function validateAuthority(raw, now) {
  const value = object(raw, 'voice-turn authority')
  exactKeys(
    value,
    [
      'credentialKind',
      'scope',
      'audience',
      'principalId',
      'deploymentBindingId',
      'deviceId',
      'edgeHostId',
      'admissionId',
      'sessionId',
      'expiresAt',
      'issuerKeyRevision',
    ],
    'voice-turn authority',
  )
  if (value.credentialKind !== 'voice-turn') {
    throw new GatewayError('AUTHORITY_KIND_MISMATCH', 'credential is not a voice-turn admission')
  }
  if (value.scope !== VOICE_TURN_SCOPE || value.audience !== CREDENTIAL_AUDIENCE) {
    throw new GatewayError('AUTHORITY_SCOPE_MISMATCH', 'credential scope or audience is not accepted')
  }
  const authority = {
    credentialKind: value.credentialKind,
    scope: value.scope,
    audience: value.audience,
    principalId: uuid(value.principalId, 'principalId'),
    deploymentBindingId: uuid(value.deploymentBindingId, 'deploymentBindingId'),
    deviceId: uuid(value.deviceId, 'deviceId'),
    edgeHostId: uuid(value.edgeHostId, 'edgeHostId'),
    admissionId: uuid(value.admissionId, 'admissionId'),
    sessionId: uuid(value.sessionId, 'sessionId'),
    expiresAt: timestamp(value.expiresAt, 'expiresAt'),
    issuerKeyRevision: string(value.issuerKeyRevision, {
      context: 'issuerKeyRevision',
      max: 64,
      pattern: SAFE_OPAQUE,
    }),
  }
  if (authority.principalId !== authority.edgeHostId) {
    throw new GatewayError('AUTHORITY_PRINCIPAL_MISMATCH', 'credential principal must be the scoped edge host')
  }
  const expiresAtMs = Date.parse(authority.expiresAt)
  if (expiresAtMs <= now.getTime()) throw new GatewayError('AUTHORITY_EXPIRED', 'voice-turn admission expired')
  if (expiresAtMs - now.getTime() > LIMITS.maxSessionTtlMs) {
    throw new GatewayError('AUTHORITY_TTL_EXCEEDED', 'voice-turn admission exceeds the session TTL limit')
  }
  return authority
}

function validateBaseFrame(value, type, fields) {
  object(value, type)
  exactKeys(value, ['type', 'requestId', ...fields], type)
  if (value.type !== type) throw new GatewayError('FRAME_INVALID', `expected ${type}`, { closeCode: 1002 })
  uuid(value.requestId, `${type}.requestId`)
  return value
}

function validateResume(raw) {
  if (raw === null) return null
  const value = object(raw, 'client.hello.resume')
  exactKeys(value, ['turnId', 'retryId', 'nextSequence'], 'client.hello.resume')
  return {
    turnId: uuid(value.turnId, 'resume.turnId'),
    retryId: uuid(value.retryId, 'resume.retryId'),
    nextSequence: integer(value.nextSequence, {
      context: 'resume.nextSequence',
      min: 0,
      max: LIMITS.maxChunksPerTurn,
    }),
  }
}

function validateAudioDescriptor(raw) {
  const value = object(raw, 'turn.start.audio')
  exactKeys(value, ['contentType', 'codec', 'sampleRateHz', 'channels', 'frameDurationMs'], 'turn.start.audio')
  if (value.contentType !== 'audio/opus' || value.codec !== 'opus' || value.channels !== 1) {
    throw new GatewayError('AUDIO_PROFILE_UNSUPPORTED', 'only bounded mono Opus is supported in realtime v1')
  }
  if (![16_000, 48_000].includes(value.sampleRateHz) || ![10, 20, 40, 60].includes(value.frameDurationMs)) {
    throw new GatewayError('AUDIO_PROFILE_UNSUPPORTED', 'Opus sample rate or frame duration is unsupported')
  }
  return {
    contentType: value.contentType,
    codec: value.codec,
    sampleRateHz: value.sampleRateHz,
    channels: value.channels,
    frameDurationMs: value.frameDurationMs,
  }
}

export function parseClientControlFrame(text) {
  if (typeof text !== 'string' || byteLength(text) > LIMITS.maxControlFrameBytes) {
    throw new GatewayError('CONTROL_FRAME_TOO_LARGE', 'control frame exceeds the byte limit', {
      closeCode: 1009,
    })
  }
  let raw
  try {
    raw = JSON.parse(text)
  } catch {
    throw new GatewayError('CONTROL_JSON_INVALID', 'control frame is not valid JSON', { closeCode: 1002 })
  }
  object(raw, 'control frame')
  switch (raw.type) {
    case 'client.hello': {
      const value = validateBaseFrame(raw, 'client.hello', ['protocol', 'sessionId', 'connectionId', 'resume'])
      if (value.protocol !== PROTOCOL_VERSION) {
        throw new GatewayError('PROTOCOL_VERSION_UNSUPPORTED', 'client protocol version is unsupported', {
          closeCode: 1002,
        })
      }
      return {
        type: value.type,
        requestId: value.requestId,
        protocol: value.protocol,
        sessionId: uuid(value.sessionId, 'client.hello.sessionId'),
        connectionId: uuid(value.connectionId, 'client.hello.connectionId'),
        resume: validateResume(value.resume),
      }
    }
    case 'turn.start': {
      const value = validateBaseFrame(raw, 'turn.start', [
        'turnId',
        'retryId',
        'admissionId',
        'correlationId',
        'audio',
      ])
      return {
        type: value.type,
        requestId: value.requestId,
        turnId: uuid(value.turnId, 'turn.start.turnId'),
        retryId: uuid(value.retryId, 'turn.start.retryId'),
        admissionId: uuid(value.admissionId, 'turn.start.admissionId'),
        correlationId: uuid(value.correlationId, 'turn.start.correlationId'),
        audio: validateAudioDescriptor(value.audio),
      }
    }
    case 'turn.end': {
      const value = validateBaseFrame(raw, 'turn.end', [
        'turnId',
        'retryId',
        'finalSequence',
        'sequenceDigest',
      ])
      return {
        type: value.type,
        requestId: value.requestId,
        turnId: uuid(value.turnId, 'turn.end.turnId'),
        retryId: uuid(value.retryId, 'turn.end.retryId'),
        finalSequence: integer(value.finalSequence, {
          context: 'turn.end.finalSequence',
          min: -1,
          max: LIMITS.maxChunksPerTurn - 1,
        }),
        sequenceDigest: digest(value.sequenceDigest, 'turn.end.sequenceDigest'),
      }
    }
    case 'turn.cancel': {
      const value = validateBaseFrame(raw, 'turn.cancel', ['turnId', 'retryId', 'reason'])
      if (!['user-release', 'client-shutdown', 'policy-revoked'].includes(value.reason)) {
        throw new GatewayError('CANCEL_REASON_UNSUPPORTED', 'cancellation reason is unsupported')
      }
      return {
        type: value.type,
        requestId: value.requestId,
        turnId: uuid(value.turnId, 'turn.cancel.turnId'),
        retryId: uuid(value.retryId, 'turn.cancel.retryId'),
        reason: value.reason,
      }
    }
    case 'turn.status': {
      const value = validateBaseFrame(raw, 'turn.status', ['turnId', 'retryId'])
      return {
        type: value.type,
        requestId: value.requestId,
        turnId: uuid(value.turnId, 'turn.status.turnId'),
        retryId: uuid(value.retryId, 'turn.status.retryId'),
      }
    }
    case 'turn.reconcile': {
      const value = validateBaseFrame(raw, 'turn.reconcile', ['turnId', 'retryId'])
      return {
        type: value.type,
        requestId: value.requestId,
        turnId: uuid(value.turnId, 'turn.reconcile.turnId'),
        retryId: uuid(value.retryId, 'turn.reconcile.retryId'),
      }
    }
    default:
      throw new GatewayError('CONTROL_TYPE_UNSUPPORTED', 'control frame type is unsupported', {
        closeCode: 1002,
      })
  }
}

export function validateProviderTrace(raw) {
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new GatewayError('PROVIDER_TRACE_INVALID', 'provider trace must be an exact object')
  }
  const value = raw
  const keys = Object.keys(value).sort()
  if (keys.length !== 2 || keys[0] !== 'attemptId' || keys[1] !== 'traceId') {
    throw new GatewayError('PROVIDER_TRACE_INVALID', 'provider trace contains unsupported or missing fields')
  }
  return {
    traceId: string(value.traceId, {
      context: 'providerTrace.traceId',
      max: LIMITS.maxProviderTraceLength,
      pattern: SAFE_OPAQUE,
    }),
    attemptId: string(value.attemptId, {
      context: 'providerTrace.attemptId',
      max: LIMITS.maxProviderTraceLength,
      pattern: SAFE_OPAQUE,
    }),
  }
}

export function validateDisplayResult(raw) {
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new GatewayError('PROVIDER_RESULT_INVALID', 'provider result must be an exact object')
  }
  const value = raw
  const keys = Object.keys(value).sort()
  if (keys.length !== 3 || keys[0] !== 'kind' || keys[1] !== 'text' || keys[2] !== 'trust') {
    throw new GatewayError('PROVIDER_RESULT_INVALID', 'provider result contains unsupported or missing fields')
  }
  if (value.kind !== 'display-text' || value.trust !== 'untrusted-provider-content') {
    throw new GatewayError('PROVIDER_RESULT_INVALID', 'provider result is not a bounded inert display result')
  }
  if (typeof value.text !== 'string' || byteLength(value.text) > LIMITS.maxResultTextBytes) {
    throw new GatewayError('PROVIDER_RESULT_INVALID', 'provider display result exceeds its byte limit')
  }
  return { kind: value.kind, text: value.text, trust: value.trust }
}

export function validateReconcileResult(raw) {
  const value = object(raw, 'provider reconcile result')
  if (!['pending', 'completed', 'cancelled', 'failed', 'not-found'].includes(value.outcome)) {
    throw new GatewayError('PROVIDER_RECONCILE_INVALID', 'provider reconcile outcome is invalid')
  }
  if (value.outcome === 'completed') {
    exactKeys(value, ['outcome', 'providerTrace', 'result'], 'provider reconcile result')
    return {
      outcome: value.outcome,
      providerTrace: validateProviderTrace(value.providerTrace),
      result: validateDisplayResult(value.result),
    }
  }
  if (value.outcome === 'failed') {
    exactKeys(value, ['outcome', 'code', 'retryable', 'providerTrace'], 'provider reconcile result')
    return {
      outcome: value.outcome,
      code: string(value.code, { context: 'provider failure code', max: 80, pattern: SAFE_OPAQUE }),
      retryable: value.retryable === true,
      providerTrace: value.providerTrace === null ? null : validateProviderTrace(value.providerTrace),
    }
  }
  if (value.outcome === 'cancelled') {
    exactKeys(value, ['outcome', 'providerTrace'], 'provider reconcile result')
    return {
      outcome: value.outcome,
      providerTrace: value.providerTrace === null ? null : validateProviderTrace(value.providerTrace),
    }
  }
  exactKeys(value, ['outcome'], 'provider reconcile result')
  return { outcome: value.outcome }
}
