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

export function parseContentLength(request) {
  if (headerValues(request, 'transfer-encoding').length > 0 && headerValues(request, 'content-length').length > 0) {
    throw invalidHttpRequest('Content-Length and Transfer-Encoding cannot be combined.')
  }
  const value = singleHeader(request, 'content-length')
  if (value === undefined) return undefined
  if (!CANONICAL_U64.test(value)) throw invalidHttpRequest('Content-Length must be a canonical unsigned integer.')
  const parsed = BigInt(value)
  if (parsed > BigInt(Number.MAX_SAFE_INTEGER)) throw invalidHttpRequest('Content-Length exceeds this runtime.')
  return Number(parsed)
}

export function requireJsonMediaType(request) {
  const value = singleHeader(request, 'content-type', { required: true })
  const normalized = value.trim().toLowerCase()
  if (normalized !== 'application/json' && normalized !== 'application/json; charset=utf-8') {
    throw invalidHttpRequest('Content-Type must be application/json.')
  }
  const contentEncoding = singleHeader(request, 'content-encoding')
  if (contentEncoding !== undefined && contentEncoding.trim().toLowerCase() !== 'identity') {
    throw invalidHttpRequest('Compressed request bodies are not accepted at the media-processing boundary.')
  }
}

export function parseCorrelationId(request) {
  const value = singleHeader(request, 'x-correlation-id')
  if (value === undefined) return undefined
  if (!UUID_V7.test(value)) throw invalidHttpRequest('X-Correlation-ID must be a lowercase UUIDv7.')
  return value
}

export function assertNoRequestBody(request) {
  if (headerValues(request, 'transfer-encoding').length > 0) {
    request.resume()
    throw invalidHttpRequest('This operation does not accept a request body.')
  }
  const contentLength = parseContentLength(request)
  if (contentLength !== undefined && contentLength !== 0) {
    request.resume()
    throw invalidHttpRequest('This operation does not accept a request body.')
  }
  request.resume()
}

export function isUuidV7(value) {
  return typeof value === 'string' && UUID_V7.test(value)
}
