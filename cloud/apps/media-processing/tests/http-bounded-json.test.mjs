import assert from 'node:assert/strict'
import test from 'node:test'

import { encodeBoundedJson } from '../src/http/bounded-json.mjs'

test('bounded HTTP JSON encoding preserves exact standard bytes for publisher schema values', () => {
  const value = {
    ascii: 'quote" slash\\ controls\b\t\n\f\r\u0000',
    unicode: 'Gumi é 🫧',
    loneSurrogate: '\ud800',
    values: [null, true, false, 0, -1, { nested: 'ok' }],
  }
  const expected = Buffer.from(JSON.stringify(value), 'utf8')
  assert.deepEqual(encodeBoundedJson(value, expected.length), expected)
  assert.throws(() => encodeBoundedJson(value, expected.length - 1), /exceeds its HTTP boundary/)
})

test('bounded HTTP JSON rejects executable serialization hooks before invoking them', () => {
  let calls = 0
  const value = { safe: true }
  Object.defineProperty(value, 'toJSON', {
    value: () => {
      calls += 1
      return 'secret'.repeat(1_000_000)
    },
  })
  assert.throws(() => encodeBoundedJson(value, 1_024), /invalid JSON projection/)
  assert.equal(calls, 0)
})

test('bounded HTTP JSON rejects cycles, sparse/accessor arrays, unsafe numbers, and excessive depth', () => {
  const cyclic = {}
  cyclic.self = cyclic
  assert.throws(() => encodeBoundedJson(cyclic, 1_024), /invalid JSON projection/)

  const sparse = Array(2)
  sparse[1] = 'value'
  assert.throws(() => encodeBoundedJson(sparse, 1_024), /invalid JSON projection/)

  const accessor = []
  Object.defineProperty(accessor, 0, { enumerable: true, get: () => 'value' })
  accessor.length = 1
  assert.throws(() => encodeBoundedJson(accessor, 1_024), /invalid JSON projection/)
  assert.throws(() => encodeBoundedJson({ value: Number.MAX_SAFE_INTEGER + 1 }, 1_024), /invalid JSON projection/)

  let deep = null
  for (let index = 0; index < 66; index += 1) deep = { deep }
  assert.throws(() => encodeBoundedJson(deep, 8_192), /invalid JSON projection/)
})
