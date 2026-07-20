import assert from 'node:assert/strict'
import test from 'node:test'

import { decodeAudioFrame, encodeAudioFrame } from '../src/core/audio-frame.mjs'
import { EMPTY_SEQUENCE_DIGEST, LIMITS } from '../src/core/constants.mjs'
import {
  GatewayError,
  ProviderEffectOutcomeUnknownError,
} from '../src/core/error.mjs'
import { sha256 } from '../src/core/primitives.mjs'
import { RealtimeGateway } from '../src/core/realtime-gateway.mjs'
import {
  FaultInjectingProvider,
  InMemorySessionStore,
  ManualClock,
  MetadataOnlyLoopbackProvider,
} from '../src/testing/in-memory-ports.mjs'

const IDS = {
  binding: '0190c6f0-7b21-7a40-8b11-000000000001',
  device: '0190c6f0-7b21-7a40-8b11-000000000002',
  edgeHost: '0190c6f0-7b21-7a40-8b11-000000000003',
  admission: '0190c6f0-7b21-7a40-8b11-000000000004',
  session: '0190c6f0-7b21-7a40-8b11-000000000005',
  connection1: '0190c6f0-7b21-7a40-8b11-000000000006',
  connection2: '0190c6f0-7b21-7a40-8b11-000000000007',
  turn: '0190c6f0-7b21-7a40-8b11-000000000008',
  retry: '0190c6f0-7b21-7a40-8b11-000000000009',
  correlation: '0190c6f0-7b21-7a40-8b11-00000000000a',
  foreign: '0190c6f0-7b21-7a40-8b11-000000000099',
}

const AUDIO = {
  contentType: 'audio/opus',
  codec: 'opus',
  sampleRateHz: 16_000,
  channels: 1,
  frameDurationMs: 20,
}

function authority(overrides = undefined) {
  return {
    credentialKind: 'voice-turn',
    scope: 'realtime-gateway:voice-turn',
    audience: 'gumi.realtime-gateway',
    principalId: IDS.edgeHost,
    deploymentBindingId: IDS.binding,
    deviceId: IDS.device,
    edgeHostId: IDS.edgeHost,
    admissionId: IDS.admission,
    sessionId: IDS.session,
    expiresAt: '2026-07-19T12:04:00.000Z',
    issuerKeyRevision: 'fixture-key-v1',
    ...overrides,
  }
}

function hello(connectionId = IDS.connection1, resume = null, sessionId = IDS.session) {
  return {
    type: 'client.hello',
    requestId: IDS.correlation,
    protocol: 'gumi.realtime.v1',
    sessionId,
    connectionId,
    resume,
  }
}

function start(overrides = undefined) {
  return {
    type: 'turn.start',
    requestId: IDS.correlation,
    turnId: IDS.turn,
    retryId: IDS.retry,
    admissionId: IDS.admission,
    correlationId: IDS.correlation,
    audio: AUDIO,
    ...overrides,
  }
}

function audioFrame(sequence, payload = Buffer.from(`opus-${sequence}`), overrides = undefined) {
  return decodeAudioFrame(
    encodeAudioFrame({
      turnId: IDS.turn,
      retryId: IDS.retry,
      sequence,
      payload,
      ...overrides,
    }),
  )
}

function finish(status) {
  return {
    type: 'turn.end',
    requestId: IDS.correlation,
    turnId: IDS.turn,
    retryId: IDS.retry,
    finalSequence: status.turn.nextSequence - 1,
    sequenceDigest: status.turn.sequenceDigest,
  }
}

async function expectCode(promise, code) {
  await assert.rejects(promise, (error) => error instanceof GatewayError && error.code === code)
}

async function harness({ provider = undefined, storeOptions = undefined } = {}) {
  const clock = new ManualClock()
  const sessions = new InMemorySessionStore({ clock, ...storeOptions })
  const actualProvider = provider ?? new MetadataOnlyLoopbackProvider()
  const gateway = new RealtimeGateway({ sessions, provider: actualProvider, clock })
  const connected = await gateway.connect({ authority: authority(), hello: hello() })
  return { clock, sessions, provider: actualProvider, gateway, lease: connected.lease }
}

test('voice-turn authority is exact, edge-principal bound, expiring, and cannot cross session scope', async () => {
  const h = await harness()
  await h.gateway.disconnect(h.lease)

  await expectCode(
    h.gateway.connect({
      authority: authority({ deviceId: IDS.foreign }),
      hello: hello(IDS.connection2),
    }),
    'AUTHORITY_SCOPE_MISMATCH',
  )
  await expectCode(
    h.gateway.connect({
      authority: authority({ principalId: IDS.foreign }),
      hello: hello(IDS.connection2),
    }),
    'AUTHORITY_PRINCIPAL_MISMATCH',
  )
  await expectCode(
    h.gateway.connect({
      authority: authority({ expiresAt: '2026-07-19T12:00:00.000Z' }),
      hello: hello(IDS.connection2),
    }),
    'AUTHORITY_EXPIRED',
  )
  await expectCode(
    h.gateway.connect({ authority: authority(), hello: hello(IDS.connection2, null, IDS.foreign) }),
    'AUTHORITY_SESSION_MISMATCH',
  )
})

test('one immutable turn and retry identity replays exactly and rejects rebinding', async () => {
  const h = await harness()
  const first = await h.gateway.startTurn(h.lease, start())
  assert.equal(first.status.turn.state, 'receiving')
  assert.equal(first.status.turn.nextSequence, 0)
  assert.equal(first.status.turn.sequenceDigest, EMPTY_SEQUENCE_DIGEST)

  const replay = await h.gateway.startTurn(h.lease, start())
  assert.equal(replay.replay, true)
  assert.equal(h.provider.calls.filter((call) => call.operation === 'open').length, 1)

  await expectCode(
    h.gateway.startTurn(h.lease, start({ retryId: IDS.foreign })),
    'TURN_IDENTITY_CONFLICT',
  )
  await expectCode(
    h.gateway.startTurn(h.lease, start({ correlationId: IDS.foreign })),
    'TURN_IDENTITY_CONFLICT',
  )
})

test('audio accepts only exact ordered digest-bound frames and exact duplicate replay', async () => {
  const h = await harness()
  await h.gateway.startTurn(h.lease, start())
  const zero = audioFrame(0)
  const accepted = await h.gateway.pushAudio(h.lease, zero)
  assert.equal(accepted.acknowledgement.disposition, 'accepted')
  assert.equal(accepted.acknowledgement.sequence, 0)
  assert.notEqual(accepted.acknowledgement.sequenceDigest, EMPTY_SEQUENCE_DIGEST)

  const duplicate = await h.gateway.pushAudio(h.lease, zero)
  assert.equal(duplicate.acknowledgement.disposition, 'duplicate')
  assert.equal(h.provider.calls.filter((call) => call.operation === 'audio').length, 1)

  await expectCode(h.gateway.pushAudio(h.lease, audioFrame(0, Buffer.from('conflict'))), 'AUDIO_REPLAY_CONFLICT')
  await expectCode(h.gateway.pushAudio(h.lease, audioFrame(2)), 'AUDIO_SEQUENCE_GAP')

  const one = await h.gateway.pushAudio(h.lease, audioFrame(1))
  assert.equal(one.acknowledgement.sequence, 1)
  const status = await h.gateway.turnStatus(h.lease, { turnId: IDS.turn, retryId: IDS.retry })
  assert.equal(status.turn.nextSequence, 2)
  assert.equal(status.turn.receivedBytes, Buffer.byteLength('opus-0') + Buffer.byteLength('opus-1'))
})

test('finalization binds exact sequence truth and returns only correlated inert display content', async () => {
  const h = await harness()
  await h.gateway.startTurn(h.lease, start())
  await h.gateway.pushAudio(h.lease, audioFrame(0))
  const before = await h.gateway.turnStatus(h.lease, { turnId: IDS.turn, retryId: IDS.retry })

  await expectCode(
    h.gateway.finishTurn(h.lease, { ...finish(before), sequenceDigest: EMPTY_SEQUENCE_DIGEST }),
    'TURN_FINALIZATION_MISMATCH',
  )
  const completed = await h.gateway.finishTurn(h.lease, finish(before))
  assert.equal(completed.status.turn.state, 'completed')
  assert.equal(completed.status.turn.correlationId, IDS.correlation)
  assert.equal(completed.status.turn.result.kind, 'display-text')
  assert.equal(completed.status.turn.result.trust, 'untrusted-provider-content')
  assert.match(completed.status.turn.providerTrace.traceId, /^local-trace\//)

  const replay = await h.gateway.finishTurn(h.lease, finish(before))
  assert.equal(replay.replay, true)
  assert.equal(h.provider.calls.filter((call) => call.operation === 'finish').length, 1)
  await expectCode(
    h.gateway.finishTurn(h.lease, { ...finish(before), finalSequence: -1 }),
    'TURN_FINALIZATION_CONFLICT',
  )
})

test('cancellation is terminal, correlated, idempotent, and never invents action authority', async () => {
  const h = await harness()
  await h.gateway.startTurn(h.lease, start())
  const request = { turnId: IDS.turn, retryId: IDS.retry, reason: 'user-release' }
  const cancelled = await h.gateway.cancelTurn(h.lease, request)
  assert.equal(cancelled.status.turn.state, 'cancelled')
  assert.equal(cancelled.status.turn.result, null)
  const replay = await h.gateway.cancelTurn(h.lease, request)
  assert.equal(replay.replay, true)
  assert.equal(h.provider.calls.filter((call) => call.operation === 'cancel').length, 1)
})

test('provider open outcome-unknown fences audio until exact reconciliation', async () => {
  const provider = new FaultInjectingProvider()
  provider.failNext('openTurn', new ProviderEffectOutcomeUnknownError('timeout'))
  const h = await harness({ provider })
  const opened = await h.gateway.startTurn(h.lease, start())
  assert.equal(opened.status.turn.state, 'outcome-unknown')
  assert.equal(opened.status.turn.outcomeUnknownPhase, 'open')
  await expectCode(h.gateway.pushAudio(h.lease, audioFrame(0)), 'PROVIDER_OUTCOME_UNKNOWN')

  const reconciled = await h.gateway.reconcileTurn(h.lease, { turnId: IDS.turn, retryId: IDS.retry })
  assert.equal(reconciled.status.turn.state, 'failed')
  assert.equal(reconciled.status.turn.failure.code, 'PROVIDER_EFFECT_NOT_FOUND')
})

test('audio, finish, and cancel ambiguity remain non-terminal and can be cancelled or reconciled', async (t) => {
  await t.test('audio ambiguity blocks advancement and confirmed cancellation wins', async () => {
    const provider = new FaultInjectingProvider()
    const h = await harness({ provider })
    await h.gateway.startTurn(h.lease, start())
    provider.failNext('pushAudio', new ProviderEffectOutcomeUnknownError('socket reset'))
    const ambiguous = await h.gateway.pushAudio(h.lease, audioFrame(0))
    assert.equal(ambiguous.status.turn.state, 'outcome-unknown')
    assert.equal(ambiguous.status.turn.nextSequence, 0)
    const cancelled = await h.gateway.cancelTurn(h.lease, {
      turnId: IDS.turn,
      retryId: IDS.retry,
      reason: 'client-shutdown',
    })
    assert.equal(cancelled.status.turn.state, 'cancelled')
  })

  await t.test('finish ambiguity does not become completion', async () => {
    const provider = new FaultInjectingProvider()
    const h = await harness({ provider })
    await h.gateway.startTurn(h.lease, start())
    const status = await h.gateway.turnStatus(h.lease, { turnId: IDS.turn, retryId: IDS.retry })
    provider.failNext('finishTurn', new ProviderEffectOutcomeUnknownError('lost response'))
    const ambiguous = await h.gateway.finishTurn(h.lease, finish(status))
    assert.equal(ambiguous.status.turn.state, 'outcome-unknown')
    const reconciliation = await h.gateway.reconcileTurn(h.lease, { turnId: IDS.turn, retryId: IDS.retry })
    assert.equal(reconciliation.status.turn.state, 'outcome-unknown')
  })

  await t.test('cancel ambiguity stays unknown until a confirmed replay', async () => {
    const provider = new FaultInjectingProvider()
    const h = await harness({ provider })
    await h.gateway.startTurn(h.lease, start())
    provider.failNext('cancelTurn', new ProviderEffectOutcomeUnknownError('lost response'))
    const first = await h.gateway.cancelTurn(h.lease, {
      turnId: IDS.turn,
      retryId: IDS.retry,
      reason: 'user-release',
    })
    assert.equal(first.status.turn.state, 'outcome-unknown')
    const second = await h.gateway.cancelTurn(h.lease, {
      turnId: IDS.turn,
      retryId: IDS.retry,
      reason: 'user-release',
    })
    assert.equal(second.status.turn.state, 'cancelled')
  })
})

test('unexpected or oversized provider result is outcome-unknown, never partially trusted', async () => {
  class InvalidResultProvider extends MetadataOnlyLoopbackProvider {
    async finishTurn(request) {
      const completed = await super.finishTurn(request)
      return {
        ...completed,
        result: {
          kind: 'display-text',
          text: 'x'.repeat(LIMITS.maxResultTextBytes + 1),
          trust: 'untrusted-provider-content',
        },
      }
    }
  }
  const h = await harness({ provider: new InvalidResultProvider() })
  await h.gateway.startTurn(h.lease, start())
  const status = await h.gateway.turnStatus(h.lease, { turnId: IDS.turn, retryId: IDS.retry })
  const outcome = await h.gateway.finishTurn(h.lease, finish(status))
  assert.equal(outcome.status.turn.state, 'outcome-unknown')
  assert.equal(outcome.status.turn.result, null)
})

test('reconnect requires an exact resume fence and supersedes every old connection generation', async () => {
  const h = await harness()
  await h.gateway.startTurn(h.lease, start())
  await h.gateway.pushAudio(h.lease, audioFrame(0))
  await h.gateway.disconnect(h.lease)

  await expectCode(
    h.gateway.connect({ authority: authority(), hello: hello(IDS.connection2) }),
    'RESUME_REQUIRED',
  )
  await expectCode(
    h.gateway.connect({
      authority: authority(),
      hello: hello(IDS.connection2, { turnId: IDS.turn, retryId: IDS.retry, nextSequence: 0 }),
    }),
    'RESUME_FENCE_MISMATCH',
  )
  const resumed = await h.gateway.connect({
    authority: authority(),
    hello: hello(IDS.connection2, { turnId: IDS.turn, retryId: IDS.retry, nextSequence: 1 }),
  })
  assert.equal(resumed.lease.generation, 2)
  await expectCode(
    h.gateway.turnStatus(h.lease, { turnId: IDS.turn, retryId: IDS.retry }),
    'STALE_CONNECTION_FENCE',
  )
  const status = await h.gateway.turnStatus(resumed.lease, { turnId: IDS.turn, retryId: IDS.retry })
  assert.equal(status.turn.nextSequence, 1)
})

test('an interrupted transitional effect becomes provider-outcome-unknown on reconnect', async () => {
  let releaseProvider
  const wait = new Promise((resolve) => {
    releaseProvider = resolve
  })
  class DelayedProvider extends MetadataOnlyLoopbackProvider {
    async pushAudio(request) {
      await wait
      return super.pushAudio(request)
    }
  }
  const h = await harness({ provider: new DelayedProvider() })
  await h.gateway.startTurn(h.lease, start())
  const pending = h.gateway.pushAudio(h.lease, audioFrame(0))
  await new Promise((resolve) => setImmediate(resolve))
  await h.gateway.disconnect(h.lease)
  const resumed = await h.gateway.connect({
    authority: authority(),
    hello: hello(IDS.connection2, { turnId: IDS.turn, retryId: IDS.retry, nextSequence: 0 }),
  })
  assert.equal(resumed.status.turn.state, 'outcome-unknown')
  assert.equal(resumed.status.turn.outcomeUnknownPhase, 'audio')
  releaseProvider()
  await expectCode(pending, 'PROVIDER_EFFECT_FENCE_LOST')
})

test('session state is capacity and expiry bounded rather than retained without limit', async () => {
  const clock = new ManualClock()
  const sessions = new InMemorySessionStore({ clock, maxSessions: 1 })
  const provider = new MetadataOnlyLoopbackProvider()
  const gateway = new RealtimeGateway({ sessions, provider, clock })
  await gateway.connect({
    authority: authority({ expiresAt: '2026-07-19T12:01:00.000Z' }),
    hello: hello(),
  })
  await expectCode(
    gateway.connect({
      authority: authority({ sessionId: IDS.foreign }),
      hello: hello(IDS.connection2, null, IDS.foreign),
    }),
    'SESSION_CAPACITY_EXCEEDED',
  )
  clock.advance(2 * 60 * 1_000)
  const second = await gateway.connect({
    authority: authority({ sessionId: IDS.foreign }),
    hello: hello(IDS.connection2, null, IDS.foreign),
  })
  assert.equal(second.status.sessionId, IDS.foreign)
  assert.equal(sessions.size, 1)
})

test('binary envelope authenticates turn, retry, sequence, and exact payload digest', () => {
  const payload = Buffer.from('not-retained-opus')
  const encoded = encodeAudioFrame({
    turnId: IDS.turn,
    retryId: IDS.retry,
    sequence: 7,
    payload,
  })
  assert.equal(encoded.length, LIMITS.audioEnvelopeBytes + payload.length)
  const decoded = decodeAudioFrame(encoded)
  assert.equal(decoded.turnId, IDS.turn)
  assert.equal(decoded.retryId, IDS.retry)
  assert.equal(decoded.sequence, 7)
  assert.equal(decoded.contentDigest, sha256(payload))
  assert.deepEqual(decoded.payload, payload)

  encoded[encoded.length - 1] ^= 0xff
  assert.throws(() => decodeAudioFrame(encoded), { code: 'AUDIO_CONTENT_DIGEST_MISMATCH' })
})
