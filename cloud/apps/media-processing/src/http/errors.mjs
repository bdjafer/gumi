import { ProcessingError } from '../core/error.mjs'

export const PUBLISHED_PROBLEM_CODES = Object.freeze([
  'INVALID_REQUEST',
  'AUTHENTICATION_REQUIRED',
  'CONTROL_SCOPE_MISMATCH',
  'WORKER_SCOPE_MISMATCH',
  'CALLBACK_SCOPE_MISMATCH',
  'PROCESSING_JOB_NOT_FOUND',
  'INPUT_MANIFEST_NOT_FOUND',
  'ATTEMPT_NOT_FOUND',
  'RESULT_NOT_FOUND',
  'TRANSCRIPT_PAGE_NOT_FOUND',
  'REQUEST_ID_CONFLICT',
  'INPUT_MANIFEST_DIGEST_MISMATCH',
  'GENERATION_CONFLICT',
  'LEASE_REVISION_CONFLICT',
  'JOB_NOT_CLAIMABLE',
  'JOB_NOT_RETRYABLE',
  'RETRY_NOT_READY',
  'JOB_TERMINAL',
  'JOB_CANCELED',
  'ATTEMPT_SUPERSEDED',
  'ATTEMPT_LEASE_EXPIRED',
  'ATTEMPT_LIMIT_REACHED',
  'ATTEMPT_COMPLETION_CONFLICT',
  'OUTCOME_UNKNOWN_ACK_REQUIRED',
  'OUTCOME_UNKNOWN_REQUIRES_RECONCILIATION',
  'COMPLETION_IN_PROGRESS',
  'PROVIDER_PROVENANCE_CONFLICT',
  'RESULT_PROVENANCE_CONFLICT',
  'RESULT_DIGEST_MISMATCH',
  'ARTIFACT_INTEGRITY_MISMATCH',
  'ARTIFACT_TOO_LARGE',
  'INPUT_MANIFEST_UNAVAILABLE',
  'ARTIFACT_STAGE_NOT_FOUND',
  'DURABILITY_UNAVAILABLE',
  'RATE_LIMITED',
])
const PUBLISHED_CODES = new Set(PUBLISHED_PROBLEM_CODES)

const TITLES = new Map([
  ['INVALID_REQUEST', 'Invalid request'],
  ['AUTHENTICATION_REQUIRED', 'Authentication required'],
  ['CONTROL_SCOPE_MISMATCH', 'Control scope mismatch'],
  ['WORKER_SCOPE_MISMATCH', 'Worker scope mismatch'],
  ['CALLBACK_SCOPE_MISMATCH', 'Callback scope mismatch'],
  ['DURABILITY_UNAVAILABLE', 'Durability unavailable'],
  ['RATE_LIMITED', 'Rate limited'],
])

export class HttpAdapterError extends Error {
  constructor(code, status, message, {
    allowedMethods = undefined,
    challengeRealm = undefined,
    cause = undefined,
  } = {}) {
    super(message, { cause })
    this.name = 'HttpAdapterError'
    this.code = code
    this.status = status
    if (allowedMethods !== undefined) this.allowedMethods = structuredClone(allowedMethods)
    if (challengeRealm !== undefined) this.challengeRealm = challengeRealm
  }
}

export class RateLimitedError extends HttpAdapterError {
  constructor(retryAfterSeconds, cause = undefined) {
    if (!Number.isSafeInteger(retryAfterSeconds) || retryAfterSeconds < 1 || retryAfterSeconds > 86_400) {
      throw new TypeError('retryAfterSeconds must be an integer in 1..86400')
    }
    super('RATE_LIMITED', 429, 'The scoped principal is temporarily rate limited.', { cause })
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

export function invalidHttpRequest(message, status = 400, options = undefined) {
  return new HttpAdapterError('INVALID_REQUEST', status, message, options)
}

export function bearerChallenge(realm) {
  return `Bearer realm="${realm}", charset="UTF-8"`
}

function stableTitle(code) {
  const known = TITLES.get(code)
  if (known) return known
  return code
    .toLowerCase()
    .split('_')
    .map((part, index) => (index === 0 ? part[0].toUpperCase() + part.slice(1) : part))
    .join(' ')
    .slice(0, 200)
}

function problemType(code) {
  return `https://gumi.astrale.ai/problems/media-processing/v1/${code.toLowerCase().replaceAll('_', '-')}`
}

function adapterResponseHeaders(error) {
  if (error instanceof RateLimitedError) {
    return { 'retry-after': String(error.retryAfterSeconds) }
  }
  if (!(error instanceof HttpAdapterError)) return undefined
  if (
    error.code === 'AUTHENTICATION_REQUIRED' &&
    error.status === 401 &&
    typeof error.challengeRealm === 'string' &&
    /^[a-z0-9](?:[a-z0-9-]{0,126}[a-z0-9])?$/.test(error.challengeRealm)
  ) {
    return { 'www-authenticate': bearerChallenge(error.challengeRealm) }
  }
  if (
    error.code === 'INVALID_REQUEST' &&
    error.status === 405 &&
    Array.isArray(error.allowedMethods) &&
    error.allowedMethods.length > 0 &&
    error.allowedMethods.length <= 2 &&
    error.allowedMethods.every((method) => method === 'GET' || method === 'POST') &&
    new Set(error.allowedMethods).size === error.allowedMethods.length
  ) {
    return { allow: [...error.allowedMethods].sort().join(', ') }
  }
  return undefined
}

export function asProblem(error, traceId) {
  const validRateLimit =
    error instanceof RateLimitedError &&
    error.code === 'RATE_LIMITED' &&
    error.status === 429 &&
    Number.isSafeInteger(error.retryAfterSeconds) &&
    error.retryAfterSeconds >= 1 &&
    error.retryAfterSeconds <= 86_400
  const malformedRateLimit =
    error instanceof RateLimitedError ? !validRateLimit : error?.code === 'RATE_LIMITED'
  const recognizedType = error instanceof HttpAdapterError || error instanceof ProcessingError
  const recognized = !malformedRateLimit && recognizedType && PUBLISHED_CODES.has(error.code)
  const code = recognized ? error.code : 'DURABILITY_UNAVAILABLE'
  const status = recognized && Number.isInteger(error.status) && error.status >= 400 && error.status <= 599
    ? error.status
    : 503
  const body = {
    type: problemType(code),
    title: stableTitle(code),
    status,
    code,
    traceId,
  }
  if (validRateLimit) body.retryAfterSeconds = String(error.retryAfterSeconds)
  const headers = recognized ? adapterResponseHeaders(error) : undefined
  return { status, code, body, headers }
}
