import { HttpAdapterError } from './errors.mjs'

function responseBoundary(message = 'The application returned an invalid JSON projection.') {
  return new HttpAdapterError('DURABILITY_UNAVAILABLE', 503, message)
}

/**
 * Preflights the exact UTF-8 byte length of plain JSON before JSON.stringify
 * can allocate an over-limit intermediate string. The accepted subset matches
 * the publisher's schema values: plain objects, arrays, strings, booleans,
 * null, and safe integer numbers.
 */
export function encodeBoundedJson(value, maximumBytes) {
  if (!Number.isSafeInteger(maximumBytes) || maximumBytes < 1) {
    throw new TypeError('maximumBytes must be a positive safe integer')
  }
  let byteLength = 0
  let visitedNodes = 0
  const ancestors = new Set()

  const add = (count) => {
    byteLength += count
    if (byteLength > maximumBytes) {
      throw responseBoundary('The application response exceeds its HTTP boundary.')
    }
  }

  const countString = (text) => {
    add(2)
    for (let index = 0; index < text.length; index += 1) {
      const code = text.charCodeAt(index)
      if (code === 0x22 || code === 0x5c) {
        add(2)
      } else if (code <= 0x1f) {
        add([0x08, 0x09, 0x0a, 0x0c, 0x0d].includes(code) ? 2 : 6)
      } else if (code <= 0x7f) {
        add(1)
      } else if (code <= 0x7ff) {
        add(2)
      } else if (code >= 0xd800 && code <= 0xdbff) {
        const next = text.charCodeAt(index + 1)
        if (next >= 0xdc00 && next <= 0xdfff) {
          add(4)
          index += 1
        } else {
          add(6)
        }
      } else if (code >= 0xdc00 && code <= 0xdfff) {
        add(6)
      } else {
        add(3)
      }
    }
  }

  const visit = (current, depth) => {
    visitedNodes += 1
    if (visitedNodes > maximumBytes + 1 || depth > 64) throw responseBoundary()
    if (current === null) {
      add(4)
      return
    }
    if (typeof current === 'string') {
      countString(current)
      return
    }
    if (typeof current === 'boolean') {
      add(current ? 4 : 5)
      return
    }
    if (typeof current === 'number') {
      if (!Number.isSafeInteger(current)) throw responseBoundary()
      add(JSON.stringify(current).length)
      return
    }
    if (!current || typeof current !== 'object') throw responseBoundary()
    const prototype = Object.getPrototypeOf(current)
    if (Array.isArray(current) ? prototype !== Array.prototype : prototype !== Object.prototype && prototype !== null) {
      throw responseBoundary()
    }
    if (Object.getOwnPropertyDescriptor(current, 'toJSON') !== undefined) throw responseBoundary()
    if (ancestors.has(current)) throw responseBoundary()
    ancestors.add(current)
    try {
      if (Array.isArray(current)) {
        add(1)
        for (let index = 0; index < current.length; index += 1) {
          if (index > 0) add(1)
          const descriptor = Object.getOwnPropertyDescriptor(current, index)
          if (!descriptor || !Object.hasOwn(descriptor, 'value')) throw responseBoundary()
          visit(descriptor.value, depth + 1)
        }
        add(1)
        return
      }

      add(1)
      let index = 0
      for (const key in current) {
        if (!Object.hasOwn(current, key)) continue
        const descriptor = Object.getOwnPropertyDescriptor(current, key)
        if (!descriptor || !Object.hasOwn(descriptor, 'value')) throw responseBoundary()
        if (index > 0) add(1)
        countString(key)
        add(1)
        visit(descriptor.value, depth + 1)
        index += 1
      }
      add(1)
    } finally {
      ancestors.delete(current)
    }
  }

  visit(value, 0)
  let bytes
  try {
    bytes = Buffer.from(JSON.stringify(value), 'utf8')
  } catch (cause) {
    throw new HttpAdapterError('DURABILITY_UNAVAILABLE', 503, 'The application returned an invalid JSON projection.', {
      cause,
    })
  }
  if (bytes.length !== byteLength || bytes.length > maximumBytes) throw responseBoundary()
  return bytes
}
