import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, extname, join, relative, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import { LIMITS } from '../src/core/constants.mjs'
import { GatewayError, ProviderKnownError } from '../src/core/error.mjs'
import { parseClientControlFrame, validateAuthority, validateDisplayResult } from '../src/core/validation.mjs'
import { assertGatewayPorts, assertWebSocketPorts } from '../src/ports.mjs'
import { ManualClock, MetadataOnlyLoopbackProvider } from '../src/testing/in-memory-ports.mjs'

const APP = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const SOURCE = join(APP, 'src')
const ID = (suffix) => `0190c6f0-7b21-7a40-8b11-${suffix.padStart(12, '0')}`

function files(root) {
  const result = []
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const path = join(root, entry.name)
    if (entry.isDirectory()) result.push(...files(path))
    else result.push(path)
  }
  return result
}

function source(path) {
  return readFileSync(path, 'utf8')
}

function authority(overrides = undefined) {
  return {
    credentialKind: 'voice-turn',
    scope: 'realtime-gateway:voice-turn',
    audience: 'gumi.realtime-gateway',
    principalId: ID('3'),
    deploymentBindingId: ID('1'),
    deviceId: ID('2'),
    edgeHostId: ID('3'),
    admissionId: ID('4'),
    sessionId: ID('5'),
    expiresAt: '2026-07-19T12:04:00.000Z',
    issuerKeyRevision: 'fixture-v1',
    ...overrides,
  }
}

test('pure core cannot import websocket, runtime, testing, environment, or other cloud applications', () => {
  const coreFiles = files(join(SOURCE, 'core')).filter((file) => extname(file) === '.mjs')
  assert.ok(coreFiles.length >= 6)
  for (const file of coreFiles) {
    const body = source(file)
    assert.doesNotMatch(body, /from ['"]ws['"]/, relative(APP, file))
    assert.doesNotMatch(body, /\.\.\/testing|\.\.\/runtime|process\.env|cloud\/apps/, relative(APP, file))
  }
  const server = source(join(SOURCE, 'ws', 'server.mjs'))
  assert.doesNotMatch(server, /\.\.\/testing|\.\.\/runtime|process\.env/)
  assert.match(server, /from 'ws'/)
})

test('only the explicit local composition imports volatile fakes and loopback provider', () => {
  const importingTesting = files(SOURCE)
    .filter((file) => extname(file) === '.mjs' && source(file).includes('/testing/'))
    .map((file) => relative(SOURCE, file))
  assert.deepEqual(importingTesting, ['runtime/local-composition.mjs'])
  const composition = source(join(SOURCE, 'runtime', 'local-composition.mjs'))
  assert.match(composition, /MetadataOnlyLoopbackProvider/)
  assert.doesNotMatch(composition, /OpenAI|ElevenLabs|Anthropic|Gemini|providerKey|apiKey/i)
})

test('port composition refuses implicit capabilities', () => {
  assert.throws(() => assertGatewayPorts({ sessions: {}, provider: {}, clock: {} }), /sessions\.transact/)
  assert.throws(
    () => assertWebSocketPorts({ authorizer: {}, gateway: {}, clock: { now() {} } }),
    /authorizer\.authenticate/,
  )
  const methods = ['openTurn', 'pushAudio', 'finishTurn', 'cancelTurn', 'reconcileTurn']
  const provider = Object.fromEntries(methods.map((method) => [method, () => {}]))
  assert.doesNotThrow(() =>
    assertGatewayPorts({
      sessions: { transact() {} },
      provider,
      clock: { now() {} },
    }),
  )
})

test('authority parser rejects a general Astrale credential, body widening, and principal substitution', () => {
  const now = new ManualClock().now()
  assert.deepEqual(validateAuthority(authority(), now), authority())
  assert.throws(() => validateAuthority({ ...authority(), astraleCredential: 'forbidden' }, now), {
    code: 'FRAME_INVALID',
  })
  assert.throws(() => validateAuthority(authority({ credentialKind: 'astrale-session' }), now), {
    code: 'AUTHORITY_KIND_MISMATCH',
  })
  assert.throws(() => validateAuthority(authority({ principalId: ID('99') }), now), {
    code: 'AUTHORITY_PRINCIPAL_MISMATCH',
  })
  assert.throws(() => validateAuthority(authority({ audience: 'gumi.astrale.ai' }), now), {
    code: 'AUTHORITY_SCOPE_MISMATCH',
  })
})

test('provider results are exact inert display values and cannot smuggle executable fields', () => {
  const valid = {
    kind: 'display-text',
    text: 'A visible but untrusted answer',
    trust: 'untrusted-provider-content',
  }
  assert.deepEqual(validateDisplayResult(valid), valid)
  for (const extra of [
    { action: { kind: 'run' } },
    { tool: 'shell' },
    { grant: 'root' },
    { query: '/private' },
    { trust: 'trusted-command' },
  ]) {
    assert.throws(() => validateDisplayResult({ ...valid, ...extra }), { code: 'PROVIDER_RESULT_INVALID' })
  }
})

test('control bound is UTF-8 bytes, not a character-count loophole', () => {
  const oversized = JSON.stringify({
    type: 'turn.status',
    padding: '🧠'.repeat(Math.floor(LIMITS.maxControlFrameBytes / 3)),
  })
  assert.ok(oversized.length < LIMITS.maxControlFrameBytes)
  assert.ok(Buffer.byteLength(oversized, 'utf8') > LIMITS.maxControlFrameBytes)
  assert.throws(() => parseClientControlFrame(oversized), { code: 'CONTROL_FRAME_TOO_LARGE' })
})

test('metadata-only loopback discards audio content and enforces provider capacity', async () => {
  const provider = new MetadataOnlyLoopbackProvider({ maxAttempts: 1 })
  const request = {
    sessionId: ID('5'),
    turnId: ID('8'),
    retryId: ID('9'),
    correlationId: ID('10'),
    admissionScope: {
      deploymentBindingId: ID('1'),
      deviceId: ID('2'),
      edgeHostId: ID('3'),
      admissionId: ID('4'),
    },
    audio: {
      contentType: 'audio/opus',
      codec: 'opus',
      sampleRateHz: 16_000,
      channels: 1,
      frameDurationMs: 20,
    },
    idempotencyKey: 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  }
  const opened = await provider.openTurn(request)
  const secretAudio = Buffer.from('SENSITIVE-VOICE-BYTES-MUST-NOT-PERSIST')
  await provider.pushAudio({
    ...request,
    idempotencyKey: `${request.idempotencyKey}:audio:0:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb`,
    providerTrace: opened.providerTrace,
    sequence: 0,
    contentDigest: 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
    payload: secretAudio,
  })
  const serialized = JSON.stringify(provider)
  assert.doesNotMatch(serialized, /SENSITIVE-VOICE-BYTES-MUST-NOT-PERSIST/)
  assert.doesNotMatch(serialized, new RegExp(secretAudio.toString('base64')))

  await assert.rejects(
    provider.openTurn({ ...request, idempotencyKey: 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc' }),
    (error) => error instanceof ProviderKnownError && error.code === 'LOCAL_PROVIDER_CAPACITY',
  )
})

test('ordinary logs are allowlisted away from bearer, audio, display text, and generic payload fields', () => {
  const server = source(join(SOURCE, 'ws', 'server.mjs'))
  assert.doesNotMatch(server, /logger\?\.[a-z]+\([^)]*(?:authorization|token|credential|payload|result\.text)/is)
  assert.doesNotMatch(server, /console\.(?:log|debug|info|warn|error)/)
  assert.doesNotMatch(server, /JSON\.stringify\((?:authority|request|frame\.payload)/)
  const allSource = files(SOURCE).map(source).join('\n')
  assert.doesNotMatch(allSource, /eval\s*\(|new Function\s*\(/)
  assert.doesNotMatch(allSource, /child_process|node:fs|node:vm/)
})

test('every source module loads without starting listeners or external effects', async () => {
  const modules = files(SOURCE).filter((file) => extname(file) === '.mjs')
  assert.ok(modules.length >= 10)
  for (const file of modules) await import(file)
})
