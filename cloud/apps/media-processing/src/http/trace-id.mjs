import { randomBytes } from 'node:crypto'

import { isUuidV7 } from './headers.mjs'

export function newUuidV7() {
  const bytes = randomBytes(16)
  bytes.writeUIntBE(Date.now(), 0, 6)
  bytes[6] = (bytes[6] & 0x0f) | 0x70
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function assertTraceIdFactory(factory) {
  if (typeof factory !== 'function') throw new TypeError('traceIdFactory must be a function')
}

export function nextTraceId(factory) {
  const traceId = factory()
  if (!isUuidV7(traceId)) throw new TypeError('traceIdFactory must return a lowercase UUIDv7')
  return traceId
}
