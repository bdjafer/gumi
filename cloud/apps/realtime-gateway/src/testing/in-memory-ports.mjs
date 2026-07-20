import { LIMITS } from '../core/constants.mjs'
import { GatewayError, ProviderKnownError } from '../core/error.mjs'
import { stableDigest } from '../core/primitives.mjs'

function clone(value) {
  return value === undefined ? undefined : structuredClone(value)
}

export class ManualClock {
  constructor(iso = '2026-07-19T12:00:00.000Z') {
    this.set(iso)
  }

  now() {
    return new Date(this.current)
  }

  set(iso) {
    const parsed = Date.parse(iso)
    if (!Number.isFinite(parsed)) throw new TypeError('clock value must be an ISO timestamp')
    this.current = parsed
  }

  advance(milliseconds) {
    this.current += milliseconds
  }
}

export class InMemorySessionStore {
  constructor({ clock, maxSessions = LIMITS.maxSessions } = {}) {
    if (!clock || typeof clock.now !== 'function') throw new TypeError('clock is required')
    this.clock = clock
    this.maxSessions = maxSessions
    this.records = new Map()
    this.locks = new Map()
  }

  async transact(sessionId, operation) {
    const prior = this.locks.get(sessionId) ?? Promise.resolve()
    let release
    const current = new Promise((resolve) => {
      release = resolve
    })
    const queued = prior.then(() => current)
    this.locks.set(sessionId, queued)
    await prior
    try {
      this.#pruneExpired()
      const existing = clone(this.records.get(sessionId))
      const result = await operation(existing)
      if (!result || !Object.hasOwn(result, 'state') || !Object.hasOwn(result, 'value')) {
        throw new TypeError('session transaction must return { state, value }')
      }
      if (result.state === null) {
        this.records.delete(sessionId)
      } else {
        if (!existing && !this.records.has(sessionId) && this.records.size >= this.maxSessions) {
          throw new GatewayError('SESSION_CAPACITY_EXCEEDED', 'local session store reached its bounded capacity', {
            retryable: true,
          })
        }
        this.records.set(sessionId, clone(result.state))
      }
      return result.value
    } finally {
      release()
      if (this.locks.get(sessionId) === queued) this.locks.delete(sessionId)
    }
  }

  snapshot(sessionId) {
    return clone(this.records.get(sessionId))
  }

  get size() {
    this.#pruneExpired()
    return this.records.size
  }

  #pruneExpired() {
    const now = this.clock.now().getTime()
    for (const [sessionId, state] of this.records) {
      if (Date.parse(state.expiresAt) <= now) this.records.delete(sessionId)
    }
  }
}

function providerTrace(idempotencyKey) {
  const suffix = stableDigest(idempotencyKey).slice(7, 31)
  return { traceId: `local-trace/${suffix}`, attemptId: `local-attempt/${suffix}` }
}

export class MetadataOnlyLoopbackProvider {
  constructor({ maxAttempts = LIMITS.maxSessions } = {}) {
    this.maxAttempts = maxAttempts
    this.attempts = new Map()
    this.calls = []
  }

  async openTurn(request) {
    const descriptorDigest = stableDigest({
      sessionId: request.sessionId,
      turnId: request.turnId,
      retryId: request.retryId,
      correlationId: request.correlationId,
      admissionScope: request.admissionScope,
      audio: request.audio,
    })
    const existing = this.attempts.get(request.idempotencyKey)
    if (existing) {
      if (existing.descriptorDigest !== descriptorDigest) {
        throw new ProviderKnownError('LOCAL_PROVIDER_CONFLICT', 'provider idempotency key was rebound')
      }
      return { providerTrace: clone(existing.providerTrace) }
    }
    if (this.attempts.size >= this.maxAttempts) {
      throw new ProviderKnownError('LOCAL_PROVIDER_CAPACITY', 'local provider reached bounded capacity', {
        retryable: true,
      })
    }
    const trace = providerTrace(request.idempotencyKey)
    this.attempts.set(request.idempotencyKey, {
      descriptorDigest,
      providerTrace: trace,
      nextSequence: 0,
      receivedBytes: 0,
      audioEffects: new Map(),
      outcome: 'receiving',
      result: null,
    })
    this.calls.push({ operation: 'open', turnId: request.turnId, retryId: request.retryId })
    return { providerTrace: clone(trace) }
  }

  async pushAudio(request) {
    const attempt = this.#attempt(request)
    const prior = attempt.audioEffects.get(request.idempotencyKey)
    if (prior) {
      if (prior.sequence !== request.sequence || prior.contentDigest !== request.contentDigest) {
        throw new ProviderKnownError('LOCAL_PROVIDER_AUDIO_CONFLICT', 'audio idempotency key was rebound')
      }
      return { acceptedSequence: request.sequence, providerTrace: clone(attempt.providerTrace) }
    }
    if (attempt.outcome !== 'receiving' || request.sequence !== attempt.nextSequence) {
      throw new ProviderKnownError('LOCAL_PROVIDER_SEQUENCE_REJECTED', 'provider rejected audio sequence')
    }
    attempt.audioEffects.set(request.idempotencyKey, {
      sequence: request.sequence,
      contentDigest: request.contentDigest,
    })
    attempt.nextSequence += 1
    attempt.receivedBytes += request.payload.length
    this.calls.push({
      operation: 'audio',
      turnId: request.turnId,
      retryId: request.retryId,
      sequence: request.sequence,
      contentDigest: request.contentDigest,
      payloadBytes: request.payload.length,
    })
    return { acceptedSequence: request.sequence, providerTrace: clone(attempt.providerTrace) }
  }

  async finishTurn(request) {
    const attempt = this.#attempt(request)
    if (attempt.outcome === 'completed') {
      return { providerTrace: clone(attempt.providerTrace), result: clone(attempt.result) }
    }
    if (attempt.outcome !== 'receiving' || request.finalSequence !== attempt.nextSequence - 1) {
      throw new ProviderKnownError('LOCAL_PROVIDER_FINALIZATION_REJECTED', 'provider rejected finalization')
    }
    attempt.outcome = 'completed'
    attempt.result = {
      kind: 'display-text',
      text: `Local loopback received ${attempt.nextSequence} audio frame(s).`,
      trust: 'untrusted-provider-content',
    }
    this.calls.push({
      operation: 'finish',
      turnId: request.turnId,
      retryId: request.retryId,
      finalSequence: request.finalSequence,
      sequenceDigest: request.sequenceDigest,
      receivedBytes: request.receivedBytes,
    })
    return { providerTrace: clone(attempt.providerTrace), result: clone(attempt.result) }
  }

  async cancelTurn(request) {
    const attempt = this.attempts.get(request.idempotencyKey.split(':cancel:', 1)[0])
      ?? [...this.attempts.values()].find((candidate) => candidate.providerTrace.traceId === request.providerTrace?.traceId)
    if (!attempt) return { providerTrace: null }
    if (attempt.outcome === 'completed') {
      throw new ProviderKnownError('LOCAL_PROVIDER_ALREADY_COMPLETED', 'completed effect cannot be cancelled')
    }
    attempt.outcome = 'cancelled'
    this.calls.push({ operation: 'cancel', turnId: request.turnId, retryId: request.retryId, reason: request.reason })
    return { providerTrace: clone(attempt.providerTrace) }
  }

  async reconcileTurn(request) {
    const attempt = this.attempts.get(request.idempotencyKey)
    this.calls.push({ operation: 'reconcile', turnId: request.turnId, retryId: request.retryId })
    if (!attempt) return { outcome: 'not-found' }
    if (attempt.outcome === 'completed') {
      return {
        outcome: 'completed',
        providerTrace: clone(attempt.providerTrace),
        result: clone(attempt.result),
      }
    }
    if (attempt.outcome === 'cancelled') {
      return { outcome: 'cancelled', providerTrace: clone(attempt.providerTrace) }
    }
    return { outcome: 'pending' }
  }

  #attempt(request) {
    const baseKey = request.idempotencyKey.split(/:(?:audio|finish|cancel):/, 1)[0]
    const attempt = this.attempts.get(baseKey)
      ?? [...this.attempts.values()].find((candidate) => candidate.providerTrace.traceId === request.providerTrace?.traceId)
    if (!attempt) throw new ProviderKnownError('LOCAL_PROVIDER_ATTEMPT_NOT_FOUND', 'provider attempt was not found')
    return attempt
  }
}

export class FaultInjectingProvider {
  constructor(delegate = new MetadataOnlyLoopbackProvider()) {
    this.delegate = delegate
    this.failures = new Map()
  }

  failNext(method, error) {
    const queue = this.failures.get(method) ?? []
    queue.push(error)
    this.failures.set(method, queue)
  }

  async openTurn(request) {
    this.#maybeFail('openTurn')
    return this.delegate.openTurn(request)
  }

  async pushAudio(request) {
    this.#maybeFail('pushAudio')
    return this.delegate.pushAudio(request)
  }

  async finishTurn(request) {
    this.#maybeFail('finishTurn')
    return this.delegate.finishTurn(request)
  }

  async cancelTurn(request) {
    this.#maybeFail('cancelTurn')
    return this.delegate.cancelTurn(request)
  }

  async reconcileTurn(request) {
    this.#maybeFail('reconcileTurn')
    return this.delegate.reconcileTurn(request)
  }

  #maybeFail(method) {
    const queue = this.failures.get(method)
    if (!queue?.length) return
    throw queue.shift()
  }
}

export class InMemoryVoiceTurnAuthorizer {
  constructor({ maxCredentials = 1_024 } = {}) {
    this.maxCredentials = maxCredentials
    this.credentials = new Map()
  }

  register(token, authority) {
    if (typeof token !== 'string' || token.length < 32 || token.length > 512) {
      throw new TypeError('test bearer must be a bounded opaque string')
    }
    if (!this.credentials.has(token) && this.credentials.size >= this.maxCredentials) {
      throw new GatewayError('AUTHORIZER_CAPACITY_EXCEEDED', 'test authorizer reached bounded capacity')
    }
    this.credentials.set(token, clone(authority))
    return this
  }

  async authenticate(token) {
    return clone(this.credentials.get(token) ?? null)
  }
}
