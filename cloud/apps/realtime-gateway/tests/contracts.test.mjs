import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import { decodeAudioFrame, encodeAudioFrame } from '../src/core/audio-frame.mjs'
import { LIMITS, PROTOCOL_VERSION } from '../src/core/constants.mjs'
import { EMPTY_SEQUENCE_DIGEST } from '../src/core/constants.mjs'
import { parseClientControlFrame } from '../src/core/validation.mjs'

const APP = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const API = join(APP, 'api', 'v1')
const ID = (suffix) => `0190c6f0-7b21-7a40-8b11-${suffix.padStart(12, '0')}`

function json(path) {
  return JSON.parse(readFileSync(path, 'utf8'))
}

function propertyNames(value, result = new Set()) {
  if (Array.isArray(value)) {
    value.forEach((entry) => propertyNames(entry, result))
  } else if (value && typeof value === 'object') {
    if (value.properties) Object.keys(value.properties).forEach((key) => result.add(key))
    Object.values(value).forEach((entry) => propertyNames(entry, result))
  }
  return result
}

function objectSchemasWithoutClosure(value, path = '$', result = []) {
  if (Array.isArray(value)) {
    value.forEach((entry, index) => objectSchemasWithoutClosure(entry, `${path}[${index}]`, result))
  } else if (value && typeof value === 'object') {
    if (value.type === 'object' && value.additionalProperties !== false) result.push(path)
    Object.entries(value).forEach(([key, entry]) => objectSchemasWithoutClosure(entry, `${path}.${key}`, result))
  }
  return result
}

test('application manifest publishes exactly one local-only realtime boundary', () => {
  const manifest = json(join(APP, 'app.json'))
  assert.equal(manifest.appId, 'realtime-gateway')
  assert.equal(manifest.kind, 'realtime-service')
  assert.deepEqual(manifest.contracts, [
    {
      id: 'realtime-gateway-v1',
      protocol: 'realtime',
      version: 'v1',
      path: 'api/v1/protocol.schema.json',
    },
  ])
  assert.deepEqual(manifest.dependencies, [])
  assert.deepEqual(manifest.tenancy, {
    mode: 'dedicated-deployment',
    sharedDeployment: false,
    scopeSource: 'deployment-binding',
  })
  assert.deepEqual(manifest.deployment, { status: 'local-only', target: null, regions: [] })
  assert.deepEqual(manifest.retention, {
    status: 'unselected',
    class: 'sensitive-user-content',
    policyId: null,
  })
  assert.ok(readFileSync(join(APP, manifest.runbook), 'utf8').includes('Open production gates'))
  assert.doesNotThrow(() => readFileSync(join(APP, manifest.contracts[0].path)))
})

test('public protocol binds every runtime byte/count limit and closes every object shape', () => {
  const schema = json(join(API, 'protocol.schema.json'))
  assert.equal(schema.$schema, 'https://json-schema.org/draft/2020-12/schema')
  assert.equal(schema.$id, 'https://gumi.astrale.ai/realtime-gateway/v1/protocol.schema.json')
  assert.equal(schema['x-gumi-transport'].subprotocol, PROTOCOL_VERSION)
  assert.equal(schema['x-gumi-transport'].maxControlFrameBytes, LIMITS.maxControlFrameBytes)
  assert.equal(schema['x-gumi-transport'].maxPendingMessages, LIMITS.maxPendingMessages)
  assert.equal(schema['x-gumi-binary-audio-frame'].headerBytes, LIMITS.audioEnvelopeBytes)
  assert.equal(schema['x-gumi-binary-audio-frame'].maxPayloadBytes, LIMITS.maxAudioPayloadBytes)
  assert.equal(schema['x-gumi-binary-audio-frame'].maxFrameBytes, LIMITS.maxAudioFrameBytes)
  assert.equal(schema['x-gumi-turn-limits'].maxChunks, LIMITS.maxChunksPerTurn)
  assert.equal(schema['x-gumi-turn-limits'].maxAudioBytes, LIMITS.maxAudioBytesPerTurn)
  assert.equal(schema['x-gumi-turn-limits'].maxDisplayResultBytes, LIMITS.maxResultTextBytes)
  assert.equal(schema['x-gumi-turn-limits'].maxSessionTtlMs, LIMITS.maxSessionTtlMs)
  assert.deepEqual(objectSchemasWithoutClosure(schema), [])

  const forbidden = [...propertyNames(schema)].filter((name) =>
    /^(?:action|actions|tool|tools|grant|grants|query|policy|astraleCredential|providerCredential|transcript)$/i.test(name),
  )
  assert.deepEqual(forbidden, [])
  assert.deepEqual(schema.$defs.DisplayResult.required, ['kind', 'text', 'trust'])
  assert.equal(schema.$defs.DisplayResult.properties.trust.const, 'untrusted-provider-content')
})

test('all client control variants parse as strict exact frames and unknown authority fields are impossible', () => {
  const common = { requestId: ID('10'), turnId: ID('11'), retryId: ID('12') }
  const frames = [
    {
      type: 'client.hello',
      requestId: common.requestId,
      protocol: PROTOCOL_VERSION,
      sessionId: ID('13'),
      connectionId: ID('14'),
      resume: null,
    },
    {
      type: 'turn.start',
      ...common,
      admissionId: ID('15'),
      correlationId: ID('16'),
      audio: {
        contentType: 'audio/opus',
        codec: 'opus',
        sampleRateHz: 16_000,
        channels: 1,
        frameDurationMs: 20,
      },
    },
    {
      type: 'turn.end',
      ...common,
      finalSequence: -1,
      sequenceDigest: EMPTY_SEQUENCE_DIGEST,
    },
    { type: 'turn.cancel', ...common, reason: 'user-release' },
    { type: 'turn.status', ...common },
    { type: 'turn.reconcile', ...common },
  ]
  for (const frame of frames) assert.deepEqual(parseClientControlFrame(JSON.stringify(frame)), frame)

  assert.throws(
    () => parseClientControlFrame(JSON.stringify({ ...frames[1], astraleCredential: 'must-not-exist' })),
    { code: 'FRAME_INVALID' },
  )
  assert.throws(
    () => parseClientControlFrame(JSON.stringify({ ...frames[1], action: { tool: 'shell' } })),
    { code: 'FRAME_INVALID' },
  )
})

test('binary audio layout matches the published offsets and authenticates both stable identities', () => {
  const schema = json(join(API, 'protocol.schema.json'))
  const fields = Object.fromEntries(schema['x-gumi-binary-audio-frame'].fields.map((field) => [field.name, field]))
  const frame = encodeAudioFrame({
    turnId: ID('20'),
    retryId: ID('21'),
    sequence: 0x01020304,
    payload: Buffer.from('opus-fixture'),
  })
  assert.equal(frame.subarray(fields.magic.offset, fields.magic.bytes).toString('ascii'), 'GRT1')
  assert.equal(frame.readUInt32BE(fields.sequence.offset), 0x01020304)
  const decoded = decodeAudioFrame(frame)
  assert.equal(decoded.turnId, ID('20'))
  assert.equal(decoded.retryId, ID('21'))
  assert.equal(decoded.sequence, 0x01020304)
})

test('package is independently locked to the maintained websocket transport library', () => {
  const pkg = json(join(APP, 'package.json'))
  const lock = json(join(APP, 'package-lock.json'))
  assert.deepEqual(pkg.dependencies, { ws: '8.21.1' })
  assert.equal(lock.lockfileVersion, 3)
  assert.equal(lock.packages['node_modules/ws'].version, '8.21.1')
  assert.equal(lock.packages[''].dependencies.ws, '8.21.1')
  assert.ok(pkg.scripts.verify.includes('test:contract'))
  assert.ok(pkg.scripts.verify.includes('test:integration'))
  assert.ok(pkg.scripts.verify.includes('test:boundary'))
})

test('normative protocol explicitly fences ambiguous effects and rejects executable result semantics', () => {
  const protocol = readFileSync(join(API, 'protocol.md'), 'utf8')
  for (const phrase of [
    '`outcome-unknown` is not failure and is not permission to retry',
    'A general Astrale session',
    'inert display content',
    'no action, tool, query, policy, grant',
  ]) {
    assert.ok(protocol.includes(phrase), `missing protocol invariant: ${phrase}`)
  }
})
