import { createHash } from 'node:crypto'

import { fail, invalid } from './error.mjs'

export const U64_MAX = 18_446_744_073_709_551_615n
export const U64_EXCLUSIVE_MAX = U64_MAX + 1n

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const SHA256 = /^sha256:[0-9a-f]{64}$/
const CODEC_CONFIGURATION = /^[a-z0-9][a-z0-9._-]{0,127}$/
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/

export function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(',')}}`
  }
  return JSON.stringify(value)
}

export function valueDigest(value) {
  return byteDigest(Buffer.from(canonicalJson(value), 'utf8'))
}

export function byteDigest(bytes) {
  return `sha256:${createHash('sha256').update(bytes).digest('hex')}`
}

export function exactObject(value, required, optional = [], path = '$') {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) invalid(path, 'expected an object')
  const allowed = new Set([...required, ...optional])
  for (const key of required) {
    if (!Object.hasOwn(value, key)) invalid(path, `missing ${key}`)
  }
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) invalid(path, `unexpected property ${key}`)
  }
  return value
}

export function string(value, path, { min = 0, max = Number.POSITIVE_INFINITY } = {}) {
  if (typeof value !== 'string') invalid(path, 'expected a string')
  if (value.length < min || value.length > max) invalid(path, `length must be ${min}..${max}`)
  return value
}

export function literal(value, expected, path) {
  if (value !== expected) invalid(path, `expected ${JSON.stringify(expected)}`)
  return value
}

export function boolean(value, path) {
  if (typeof value !== 'boolean') invalid(path, 'expected a boolean')
  return value
}

export function integer(value, path, minimum, maximum) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    invalid(path, `expected an integer in ${minimum}..${maximum}`)
  }
  return value
}

export function uuidV7(value, path) {
  if (typeof value !== 'string' || !UUID_V7.test(value)) invalid(path, 'expected a lowercase UUIDv7')
  return value
}

export function sha256(value, path) {
  if (typeof value !== 'string' || !SHA256.test(value)) invalid(path, 'expected a lowercase sha256 digest')
  return value
}

export function codecConfigurationId(value, path) {
  if (typeof value !== 'string' || !CODEC_CONFIGURATION.test(value)) invalid(path, 'invalid codec configuration ID')
  return value
}

export function u64(value, path, { positive = false, exclusiveBoundary = false } = {}) {
  if (typeof value !== 'string' || !/^(?:0|[1-9][0-9]{0,19})$/.test(value)) {
    invalid(path, 'expected a canonical decimal unsigned integer')
  }
  const parsed = BigInt(value)
  const maximum = exclusiveBoundary ? U64_EXCLUSIVE_MAX : U64_MAX
  if (parsed > maximum || (positive && parsed === 0n)) invalid(path, 'unsigned integer is outside its declared bounds')
  return parsed
}

export function timestamp(value, path) {
  const match = typeof value === 'string' ? RFC3339.exec(value) : null
  if (!match || !isValidRfc3339Calendar(match) || !Number.isFinite(Date.parse(value))) {
    invalid(path, 'expected an RFC 3339 timestamp with an explicit offset')
  }
  return value
}

function isValidRfc3339Calendar(match) {
  const [date, timeAndOffset] = match[0].split('T')
  const [yearText, monthText, dayText] = date.split('-')
  const time = timeAndOffset.slice(0, 8)
  const [hourText, minuteText, secondText] = time.split(':')
  const year = Number(yearText)
  const month = Number(monthText)
  const day = Number(dayText)
  const hour = Number(hourText)
  const minute = Number(minuteText)
  const second = Number(secondText)
  if (month < 1 || month > 12 || hour > 23 || minute > 59 || second > 59) return false
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = month === 2 ? (leap ? 29 : 28) : [4, 6, 9, 11].includes(month) ? 30 : 31
  return day >= 1 && day <= days
}

export function utcTimestamp(value) {
  return new Date(value).toISOString().replace(/\.000Z$/, 'Z')
}

export function addSeconds(timestampValue, seconds) {
  return utcTimestamp(new Date(timestampValue).getTime() + seconds * 1000)
}

export function minTimestamp(left, right) {
  return Date.parse(left) <= Date.parse(right) ? left : right
}

export function assertDigest(bytes, declaredDigest) {
  const computedDigest = byteDigest(bytes)
  if (computedDigest !== declaredDigest) {
    fail(
      'CONTENT_DIGEST_MISMATCH',
      422,
      'The exact request body does not match its declared SHA-256 digest.',
      { declaredDigest, computedDigest },
    )
  }
}

export function clone(value) {
  return structuredClone(value)
}
