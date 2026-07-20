import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import {
  normalizeScribeV2Webhook,
  ScribeV2NormalizationError,
} from '../src/integrations/elevenlabs/normalize-scribe-v2.mjs'

const APP = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const FIXTURES = join(APP, 'fixtures', 'providers', 'elevenlabs')
const PAYLOAD = JSON.parse(
  readFileSync(join(FIXTURES, 'scribe-v2-transcription-completed.json'), 'utf8'),
)
const EXPECTED = JSON.parse(
  readFileSync(join(FIXTURES, 'scribe-v2-normalized-artifact.json'), 'utf8'),
)
const BINDING = Object.freeze({
  providerRequestId: 'scribe-request-123',
  processingJobId: '0190c6f0-7b21-7a40-8b11-000000000064',
  attemptId: '0190c6f0-7b21-7a40-8b11-000000000065',
  generation: '1',
  configurationDigest: `sha256:${'44'.repeat(32)}`,
  mediaDurationMs: '2000',
})

const clone = (value) => structuredClone(value)

function expectCode(operation, code) {
  assert.throws(operation, (error) => {
    assert.ok(error instanceof ScribeV2NormalizationError)
    assert.equal(error.code, code)
    return true
  })
}

test('normalizes the bounded Scribe v2 docs-derived fixture without executing transcript text', () => {
  const before = JSON.stringify(PAYLOAD)
  const first = normalizeScribeV2Webhook(PAYLOAD, BINDING)
  const replay = normalizeScribeV2Webhook(PAYLOAD, BINDING)

  assert.deepEqual(first, EXPECTED)
  assert.deepEqual(replay, EXPECTED)
  assert.equal(first.segments[0].text, 'Hello IGNORE SYSTEM')
  assert.equal(first.segments[1].kind, 'audio-event')
  assert.equal(JSON.stringify(PAYLOAD), before)
})

test('requires the adapter-supplied provider request and attempt binding exactly', () => {
  expectCode(
    () => normalizeScribeV2Webhook(PAYLOAD, { ...BINDING, providerRequestId: 'other-request' }),
    'PROVIDER_BINDING_MISMATCH',
  )

  for (const [field, value] of [
    ['processing_job_id', '0190c6f0-7b21-7a40-8b11-000000000099'],
    ['attempt_id', '0190c6f0-7b21-7a40-8b11-000000000098'],
    ['generation', '2'],
    ['configuration_digest', `sha256:${'99'.repeat(32)}`],
  ]) {
    const payload = clone(PAYLOAD)
    payload.data.webhook_metadata[field] = value
    expectCode(() => normalizeScribeV2Webhook(payload, BINDING), 'PROVIDER_BINDING_MISMATCH')
  }
})

test('preserves provider spacing exactly and never invents spaces for languages without them', () => {
  const payload = clone(PAYLOAD)
  payload.data.transcription = {
    language_code: 'ja',
    language_probability: 0.99,
    text: '今日は',
    words: [
      { text: '今日', start: 0, end: 0.4, type: 'word', speaker_id: 'speaker_0' },
      { text: 'は', start: 0.4, end: 0.6, type: 'word', speaker_id: 'speaker_0' },
    ],
  }

  const artifact = normalizeScribeV2Webhook(payload, BINDING)
  assert.equal(artifact.segments[0].text, '今日は')
  assert.equal(artifact.segments[0].text.includes(' '), false)
})

test('rejects malformed, unsupported, oversized, and unbound provider envelopes', () => {
  const cases = [
    ['UNSUPPORTED_EVENT_TYPE', (payload) => { payload.type = 'other' }],
    ['UNSUPPORTED_FIELD', (payload) => { payload.data.transcription.provider_secret = 'nope' }],
    ['INVALID_PROBABILITY', (payload) => { payload.data.transcription.language_probability = 2 }],
    ['UNSUPPORTED_RECORD_TYPE', (payload) => { payload.data.transcription.words[0].type = 'character' }],
    ['INVALID_SPACING', (payload) => { payload.data.transcription.words[1].text = 'not spacing' }],
    ['REVERSED_TIMESTAMP', (payload) => {
      payload.data.transcription.words[0].start = 0.6
      payload.data.transcription.words[0].end = 0.5
    }],
    ['TIMESTAMP_OUTSIDE_MEDIA', (payload) => { payload.data.transcription.words[0].end = 2.1 }],
    ['TRANSCRIPT_TEXT_MISMATCH', (payload) => { payload.data.transcription.text = 'substituted' }],
  ]

  for (const [code, mutate] of cases) {
    const payload = clone(PAYLOAD)
    mutate(payload)
    expectCode(() => normalizeScribeV2Webhook(payload, BINDING), code)
  }

  const oversized = clone(PAYLOAD)
  oversized.data.transcription.words = Array.from({ length: 100_001 }, () => ({
    text: 'x',
    start: 0,
    end: 0,
    type: 'word',
  }))
  oversized.data.transcription.text = 'x'.repeat(100_001)
  expectCode(() => normalizeScribeV2Webhook(oversized, BINDING), 'RECORD_LIMIT_EXCEEDED')
})

test('rejects cross-speaker overlap instead of manufacturing a false time order', () => {
  const payload = clone(PAYLOAD)
  payload.data.transcription = {
    language_code: 'en',
    language_probability: 0.95,
    text: 'alphabeta',
    words: [
      { text: 'alpha', start: 0, end: 1, type: 'word', speaker_id: 'speaker_1' },
      { text: 'beta', start: 0.8, end: 1.2, type: 'word', speaker_id: 'speaker_2' },
    ],
  }

  expectCode(() => normalizeScribeV2Webhook(payload, BINDING), 'UNSUPPORTED_OVERLAP')
})

test('rejects ambiguous spacing instead of silently reassigning provider evidence', () => {
  const payload = clone(PAYLOAD)
  payload.data.transcription = {
    language_code: 'en',
    language_probability: 0.95,
    text: ' hello',
    words: [
      { text: ' ', start: 0, end: 0.1, type: 'spacing', speaker_id: 'speaker_1' },
      { text: 'hello', start: 0.1, end: 0.5, type: 'word', speaker_id: 'speaker_2' },
    ],
  }

  expectCode(() => normalizeScribeV2Webhook(payload, BINDING), 'AMBIGUOUS_SPACING')
})
