import { IngestError } from '../core/error.mjs'

const SENSITIVE_DETAIL_KEYS = new Set([
  'accesstoken',
  'authorization',
  'bearertoken',
  'body',
  'cookie',
  'credential',
  'mediabytes',
  'payload',
  'raw',
  'secret',
  'signedurl',
  'token',
  'transcript',
])

const TITLES = new Map([
  ['AUTHENTICATION_REQUIRED', 'Authentication required'],
  ['INVALID_CONTROL_CREDENTIAL', 'Invalid control credential'],
  ['INVALID_INGEST_CREDENTIAL', 'Invalid ingest credential'],
  ['INGEST_CREDENTIAL_EXPIRED', 'Ingest credential expired'],
  ['CONTROL_SCOPE_MISMATCH', 'Control scope mismatch'],
  ['SESSION_SCOPE_MISMATCH', 'Session scope mismatch'],
  ['DEVICE_REVOKED', 'Device revoked'],
  ['CAPTURE_REVOKED', 'Capture revoked'],
  ['INVALID_REQUEST', 'Invalid request'],
  ['REQUEST_BODY_TOO_LARGE', 'Request body too large'],
  ['CHUNK_TOO_LARGE', 'Chunk too large'],
  ['CONTENT_LENGTH_MISMATCH', 'Content length mismatch'],
  ['DURABILITY_UNAVAILABLE', 'Durability unavailable'],
  ['RATE_LIMITED', 'Rate limited'],
])

export class HttpAdapterError extends Error {
  constructor(code, status, message, { headers = undefined, details = undefined, cause = undefined } = {}) {
    super(message, { cause })
    this.name = 'HttpAdapterError'
    this.code = code
    this.status = status
    if (headers !== undefined) this.headers = headers
    if (details !== undefined) this.details = details
  }
}

export class RateLimitedError extends HttpAdapterError {
  constructor(retryAfterSeconds, cause = undefined) {
    if (!Number.isSafeInteger(retryAfterSeconds) || retryAfterSeconds < 0 || retryAfterSeconds > 86_400) {
      throw new TypeError('retryAfterSeconds must be an integer in 0..86400')
    }
    super('RATE_LIMITED', 429, 'The scoped caller is temporarily rate limited.', { cause })
    this.name = 'RateLimitedError'
    this.retryAfterSeconds = retryAfterSeconds
  }
}

export class RequestCancelledError extends Error {
  constructor(reason = 'The HTTP request ended before a response could be delivered.') {
    super(reason)
    this.name = 'RequestCancelledError'
  }
}

export function invalidHttpRequest(message, options = undefined) {
  return new HttpAdapterError('INVALID_REQUEST', 400, message, options)
}

export function bearerChallenge(realm) {
  return `Bearer realm="${realm}", charset="UTF-8"`
}

function sanitizedDetails(value, depth = 0) {
  if (depth > 4 || value === undefined) return undefined
  if (value === null || typeof value === 'boolean' || typeof value === 'number') return value
  if (typeof value === 'string') return value.slice(0, 512)
  if (Array.isArray(value)) {
    return value.slice(0, 32).map((item) => sanitizedDetails(item, depth + 1))
  }
  if (typeof value !== 'object') return undefined

  const result = {}
  for (const [key, item] of Object.entries(value).slice(0, 32)) {
    const normalizedKey = key.toLowerCase().replaceAll(/[^a-z0-9]/g, '')
    if (SENSITIVE_DETAIL_KEYS.has(normalizedKey)) continue
    const sanitized = sanitizedDetails(item, depth + 1)
    if (sanitized !== undefined) result[key] = sanitized
  }
  return result
}

function stableTitle(code) {
  const known = TITLES.get(code)
  if (known) return known
  return code
    .toLowerCase()
    .split('_')
    .map((part, index) => (index === 0 ? part[0].toUpperCase() + part.slice(1) : part))
    .join(' ')
}

function problemType(code) {
  return `https://gumi.astrale.ai/problems/media-ingest/v1/${code.toLowerCase().replaceAll('_', '-')}`
}

export function asProblem(error, traceId) {
  const malformedRateLimit = error?.code === 'RATE_LIMITED' && !(error instanceof RateLimitedError)
  const known = !malformedRateLimit && (error instanceof HttpAdapterError || error instanceof IngestError)
  const code = known ? error.code : 'DURABILITY_UNAVAILABLE'
  const status = known && Number.isInteger(error.status) ? error.status : 503
  const detail = known
    ? String(error.message).slice(0, 2000)
    : 'The request could not complete at a trusted application boundary.'
  const details = known ? sanitizedDetails(error.details) : undefined
  const body = {
    type: problemType(code),
    title: stableTitle(code),
    status,
    code,
    detail,
    traceId,
  }
  if (error instanceof RateLimitedError) body.retryAfterSeconds = error.retryAfterSeconds
  if (details && Object.keys(details).length > 0) body.details = details
  const headers = error instanceof RateLimitedError
    ? { ...(error.headers ?? {}), 'retry-after': String(error.retryAfterSeconds) }
    : known
      ? error.headers
      : undefined
  return { status, code, body, headers }
}
