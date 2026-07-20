import { createHash } from 'node:crypto'

import { invalid } from './error.mjs'

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const SHA256 = /^sha256:[0-9a-f]{64}$/
const U64 = /^(0|[1-9][0-9]{0,19})$/
const LANGUAGE_TAG = /^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/

export function clone(value) {
  return value === undefined ? undefined : structuredClone(value)
}

export function canonicalJson(value) {
  if (value === null || typeof value === 'boolean' || typeof value === 'string') return JSON.stringify(value)
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) throw new TypeError('Canonical JSON only accepts safe integers')
    return JSON.stringify(value)
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  if (value && typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(',')}}`
  }
  throw new TypeError('Canonical JSON cannot encode this value')
}

export function valueDigest(value) {
  return `sha256:${createHash('sha256').update(canonicalJson(value), 'utf8').digest('hex')}`
}

export function byteDigest(bytes) {
  return `sha256:${createHash('sha256').update(bytes).digest('hex')}`
}

export function uuidV7(value, label = 'ID') {
  if (typeof value !== 'string' || !UUID_V7.test(value)) invalid(`${label} must be a lowercase UUIDv7`)
  return value
}

export function digest(value, label = 'digest') {
  if (typeof value !== 'string' || !SHA256.test(value)) invalid(`${label} must be lowercase sha256:<64 hex>`)
  return value
}

export function u64(value, label = 'value') {
  if (typeof value !== 'string' || !U64.test(value)) invalid(`${label} must be a canonical unsigned 64-bit decimal string`)
  const parsed = BigInt(value)
  if (parsed > 18_446_744_073_709_551_615n) invalid(`${label} exceeds unsigned 64-bit range`)
  return parsed
}

export function positiveU64(value, label = 'value') {
  const parsed = u64(value, label)
  if (parsed === 0n) invalid(`${label} must be positive`)
  return parsed
}

export function codePointLength(value) {
  return [...value].length
}

export function opaque(value, label, { min = 1, max = 512 } = {}) {
  if (
    typeof value !== 'string' ||
    codePointLength(value) < min ||
    codePointLength(value) > max ||
    /[\u0000-\u001f\u007f]/.test(value)
  ) {
    invalid(`${label} must be an opaque string between ${min} and ${max} characters`)
  }
  return value
}

export function slug(value, label) {
  if (typeof value !== 'string' || !/^[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?$/.test(value)) {
    invalid(`${label} must be a lowercase stable slug`)
  }
  return value
}

export function languageTag(value, label = 'language.tag') {
  if (typeof value !== 'string' || !LANGUAGE_TAG.test(value)) invalid(`${label} must be a bounded BCP 47 language tag`)
  return value
}

export function utcTimestamp(value, label = 'timestamp') {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)) {
    invalid(`${label} must be an RFC 3339 UTC timestamp`)
  }
  const millis = Date.parse(value)
  if (!Number.isFinite(millis) || new Date(millis).toISOString().replace('.000Z', 'Z') !== value) {
    invalid(`${label} must be a canonical UTC timestamp`)
  }
  return value
}

export function addSeconds(timestamp, seconds) {
  return new Date(Date.parse(timestamp) + seconds * 1000).toISOString().replace('.000Z', 'Z')
}

export function before(left, right) {
  return Date.parse(left) < Date.parse(right)
}
