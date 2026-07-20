import { invalidHttpRequest } from './errors.mjs'

const CANONICAL_U64 = /^(0|[1-9][0-9]{0,19})$/
const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

export function headerValues(request, name) {
  const expected = name.toLowerCase()
  const values = []
  for (let index = 0; index < request.rawHeaders.length; index += 2) {
    if (request.rawHeaders[index].toLowerCase() === expected) values.push(request.rawHeaders[index + 1])
  }
  return values
}

export function singleHeader(request, name, { required = false } = {}) {
  const values = headerValues(request, name)
  if (values.length === 0) {
    if (required) throw invalidHttpRequest(`${name} is required.`)
    return undefined
  }
  if (values.length !== 1) throw invalidHttpRequest(`${name} must occur exactly once.`)
  return values[0]
}

export function parseCanonicalU64Header(value, name, { positive = false } = {}) {
  if (!CANONICAL_U64.test(value)) throw invalidHttpRequest(`${name} must be a canonical unsigned decimal integer.`)
  const parsed = BigInt(value)
  if (parsed > 18_446_744_073_709_551_615n || (positive && parsed === 0n)) {
    throw invalidHttpRequest(`${name} is outside its unsigned 64-bit range.`)
  }
  return parsed
}

export function parseContentLength(request, { required = false } = {}) {
  if (headerValues(request, 'transfer-encoding').length > 0 && headerValues(request, 'content-length').length > 0) {
    throw invalidHttpRequest('Content-Length and Transfer-Encoding cannot be combined.')
  }
  const value = singleHeader(request, 'content-length', { required })
  if (value === undefined) return undefined
  const parsed = parseCanonicalU64Header(value, 'Content-Length')
  if (parsed > BigInt(Number.MAX_SAFE_INTEGER)) throw invalidHttpRequest('Content-Length exceeds this runtime.')
  return Number(parsed)
}

export function requireMediaType(request, expected, { allowUtf8 = false } = {}) {
  const value = singleHeader(request, 'content-type', { required: true })
  const normalized = value.trim().toLowerCase()
  const accepted = normalized === expected || (allowUtf8 && normalized === `${expected}; charset=utf-8`)
  if (!accepted) throw invalidHttpRequest(`Content-Type must be ${expected}.`)
  const contentEncoding = singleHeader(request, 'content-encoding')
  if (contentEncoding !== undefined && contentEncoding.trim().toLowerCase() !== 'identity') {
    throw invalidHttpRequest('Compressed request bodies are not accepted at the media-ingest boundary.')
  }
}

export function parseCorrelationId(request) {
  const value = singleHeader(request, 'x-correlation-id')
  if (value === undefined) return undefined
  if (!UUID_V7.test(value)) throw invalidHttpRequest('X-Correlation-ID must be a lowercase UUIDv7.')
  return value
}

export function parseContentDigest(value) {
  const match = /^sha-256=:([A-Za-z0-9+/]{43}=):$/.exec(value)
  if (!match) throw invalidHttpRequest('Content-Digest must contain exactly one canonical sha-256 byte sequence.')
  const bytes = Buffer.from(match[1], 'base64')
  if (bytes.length !== 32 || bytes.toString('base64') !== match[1]) {
    throw invalidHttpRequest('Content-Digest contains a non-canonical SHA-256 value.')
  }
  return `sha256:${bytes.toString('hex')}`
}

export function isUuidV7(value) {
  return UUID_V7.test(value)
}
