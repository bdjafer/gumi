import {
  LIMITS,
  TERMINAL_TURN_STATES,
  TRANSITIONAL_TURN_STATES,
} from './constants.mjs'
import {
  GatewayError,
  ProviderKnownError,
  isOutcomeUnknown,
} from './error.mjs'
import {
  initialTurnProgress,
  nextSequenceDigest,
  providerIdempotencyKey,
  scopeFingerprint,
  sha256,
  stableDigest,
} from './primitives.mjs'
import {
  validateAuthority,
  validateDisplayResult,
  validateProviderTrace,
  validateReconcileResult,
} from './validation.mjs'
import { assertGatewayPorts } from '../ports.mjs'

function clone(value) {
  return value === undefined ? undefined : structuredClone(value)
}

function exactTurn(turn, turnId, retryId) {
  if (!turn || turn.turnId !== turnId || turn.retryId !== retryId) {
    throw new GatewayError('TURN_IDENTITY_MISMATCH', 'turn/retry identity does not match the admitted VoiceTurn')
  }
}

function connectionLease(state, connectionId) {
  return {
    sessionId: state.sessionId,
    connectionId,
    generation: state.connection.generation,
  }
}

function safeProviderTrace(raw) {
  if (raw === undefined || raw === null) return null
  try {
    return validateProviderTrace(raw)
  } catch {
    return null
  }
}

function publicFailure(failure) {
  if (!failure) return null
  return { code: failure.code, retryable: failure.retryable === true }
}

export function projectSessionStatus(state) {
  const turn = state.turn
  return {
    sessionId: state.sessionId,
    connectionGeneration: state.connection?.generation ?? state.lastConnectionGeneration,
    turn: turn
      ? {
          turnId: turn.turnId,
          retryId: turn.retryId,
          correlationId: turn.correlationId,
          state: turn.state,
          nextSequence: turn.nextSequence,
          acknowledgedSequence: turn.nextSequence - 1,
          receivedBytes: turn.receivedBytes,
          sequenceDigest: turn.sequenceDigest,
          providerTrace: turn.providerTrace,
          outcomeUnknownPhase: turn.outcomeUnknownPhase ?? null,
          failure: publicFailure(turn.failure),
          result: turn.result ?? null,
        }
      : null,
  }
}

function validateConnection(state, lease) {
  if (
    !state.connection ||
    state.connection.connectionId !== lease.connectionId ||
    state.connection.generation !== lease.generation ||
    state.sessionId !== lease.sessionId
  ) {
    throw new GatewayError('STALE_CONNECTION_FENCE', 'connection generation no longer owns this session')
  }
}

function recoverInterruptedEffect(turn) {
  if (!turn || !TRANSITIONAL_TURN_STATES.has(turn.state)) return turn
  return {
    ...turn,
    state: 'outcome-unknown',
    outcomeUnknownPhase: turn.effect?.phase ?? turn.state,
    effect: null,
    failure: null,
  }
}

function requireResume(state, resume) {
  if (!state.turn) {
    if (resume !== null) throw new GatewayError('RESUME_TURN_NOT_FOUND', 'session has no turn to resume')
    return
  }
  if (TERMINAL_TURN_STATES.has(state.turn.state)) {
    if (resume === null) return
  } else if (resume === null) {
    throw new GatewayError('RESUME_REQUIRED', 'an in-progress turn requires an exact resume fence')
  }
  if (
    resume.turnId !== state.turn.turnId ||
    resume.retryId !== state.turn.retryId ||
    resume.nextSequence !== state.turn.nextSequence
  ) {
    throw new GatewayError('RESUME_FENCE_MISMATCH', 'resume identity or sequence does not match gateway truth')
  }
}

function providerFailure(error) {
  if (error instanceof ProviderKnownError) {
    return {
      code: error.code,
      retryable: error.retryable,
      providerTrace: safeProviderTrace(error.providerTrace),
    }
  }
  return null
}

export class RealtimeGateway {
  constructor(ports) {
    assertGatewayPorts(ports)
    this.sessions = ports.sessions
    this.provider = ports.provider
    this.clock = ports.clock
  }

  async connect({ authority: rawAuthority, hello }) {
    const now = this.clock.now()
    const authority = validateAuthority(rawAuthority, now)
    if (hello.sessionId !== authority.sessionId) {
      throw new GatewayError('AUTHORITY_SESSION_MISMATCH', 'hello session does not match the admission credential')
    }
    return this.sessions.transact(authority.sessionId, (existing) => {
      let state = existing
      if (!state) {
        if (hello.resume !== null) {
          throw new GatewayError('RESUME_SESSION_NOT_FOUND', 'resume cannot create a new session')
        }
        state = {
          version: 1,
          sessionId: authority.sessionId,
          scopeFingerprint: scopeFingerprint(authority),
          scope: {
            deploymentBindingId: authority.deploymentBindingId,
            deviceId: authority.deviceId,
            edgeHostId: authority.edgeHostId,
            admissionId: authority.admissionId,
          },
          expiresAt: authority.expiresAt,
          lastConnectionGeneration: 0,
          connection: null,
          turn: null,
        }
      } else {
        if (state.scopeFingerprint !== scopeFingerprint(authority)) {
          throw new GatewayError('AUTHORITY_SCOPE_MISMATCH', 'credential cannot cross a session scope boundary')
        }
        if (Date.parse(state.expiresAt) <= now.getTime()) {
          throw new GatewayError('SESSION_EXPIRED', 'realtime session expired')
        }
        state.turn = recoverInterruptedEffect(state.turn)
        requireResume(state, hello.resume)
      }

      const generation = state.lastConnectionGeneration + 1
      state.lastConnectionGeneration = generation
      state.connection = { connectionId: hello.connectionId, generation }
      return {
        state,
        value: {
          lease: connectionLease(state, hello.connectionId),
          status: projectSessionStatus(state),
        },
      }
    })
  }

  async disconnect(lease) {
    return this.sessions.transact(lease.sessionId, (state) => {
      if (!state) return { state: null, value: false }
      if (
        state.connection?.connectionId === lease.connectionId &&
        state.connection?.generation === lease.generation
      ) {
        state.connection = null
        return { state, value: true }
      }
      return { state, value: false }
    })
  }

  async startTurn(lease, request) {
    const reserved = await this.sessions.transact(lease.sessionId, (state) => {
      if (!state) throw new GatewayError('SESSION_NOT_FOUND', 'realtime session does not exist')
      validateConnection(state, lease)
      if (request.admissionId !== state.scope.admissionId) {
        throw new GatewayError('ADMISSION_ID_MISMATCH', 'turn does not match the credential admission')
      }
      const descriptorDigest = stableDigest({
        turnId: request.turnId,
        retryId: request.retryId,
        admissionId: request.admissionId,
        correlationId: request.correlationId,
        audio: request.audio,
      })
      if (state.turn) {
        if (state.turn.descriptorDigest !== descriptorDigest) {
          throw new GatewayError('TURN_IDENTITY_CONFLICT', 'session already owns a different immutable turn')
        }
        return { state, value: { replay: true, status: projectSessionStatus(state) } }
      }

      const authority = {
        ...state.scope,
        sessionId: state.sessionId,
      }
      const idempotencyKey = providerIdempotencyKey(authority, request.turnId, request.retryId)
      state.turn = {
        turnId: request.turnId,
        retryId: request.retryId,
        admissionId: request.admissionId,
        correlationId: request.correlationId,
        descriptorDigest,
        audio: clone(request.audio),
        idempotencyKey,
        state: 'opening-provider',
        ...initialTurnProgress(),
        providerTrace: null,
        result: null,
        failure: null,
        outcomeUnknownPhase: null,
        finalSequence: null,
        finalSequenceDigest: null,
        effect: { phase: 'open', token: `open:${request.retryId}` },
      }
      return {
        state,
        value: {
          replay: false,
          effectToken: state.turn.effect.token,
          providerRequest: {
            sessionId: state.sessionId,
            turnId: request.turnId,
            retryId: request.retryId,
            correlationId: request.correlationId,
            admissionScope: clone(state.scope),
            audio: clone(request.audio),
            idempotencyKey,
          },
        },
      }
    })
    if (reserved.replay) return reserved

    let opened
    try {
      opened = await this.provider.openTurn(reserved.providerRequest)
      opened = { providerTrace: validateProviderTrace(opened?.providerTrace) }
    } catch (error) {
      return this.#settleProviderError(lease.sessionId, reserved.effectToken, 'open', error)
    }
    return this.sessions.transact(lease.sessionId, (state) => {
      if (!state?.turn || state.turn.effect?.token !== reserved.effectToken) {
        throw new GatewayError('PROVIDER_EFFECT_FENCE_LOST', 'provider open result lost its state fence')
      }
      state.turn.state = 'receiving'
      state.turn.providerTrace = opened.providerTrace
      state.turn.effect = null
      return { state, value: { replay: false, status: projectSessionStatus(state) } }
    })
  }

  async pushAudio(lease, frame) {
    const payload = Buffer.from(frame.payload)
    if (payload.length === 0 || payload.length > LIMITS.maxAudioPayloadBytes) {
      throw new GatewayError('AUDIO_FRAME_SIZE_INVALID', 'audio payload violates the per-frame limit')
    }
    if (sha256(payload) !== frame.contentDigest) {
      throw new GatewayError('AUDIO_CONTENT_DIGEST_MISMATCH', 'audio digest does not match payload')
    }

    const reserved = await this.sessions.transact(lease.sessionId, (state) => {
      if (!state) throw new GatewayError('SESSION_NOT_FOUND', 'realtime session does not exist')
      validateConnection(state, lease)
      exactTurn(state.turn, frame.turnId, frame.retryId)
      const turn = state.turn
      if (frame.sequence < turn.nextSequence) {
        const prior = turn.chunkDigests[frame.sequence]
        if (prior !== frame.contentDigest) {
          throw new GatewayError('AUDIO_REPLAY_CONFLICT', 'replayed sequence has a different digest')
        }
        return {
          state,
          value: {
            replay: true,
            acknowledgement: {
              turnId: turn.turnId,
              retryId: turn.retryId,
              sequence: frame.sequence,
              contentDigest: frame.contentDigest,
              sequenceDigest: turn.sequenceDigest,
              disposition: 'duplicate',
            },
          },
        }
      }
      if (frame.sequence > turn.nextSequence) {
        throw new GatewayError('AUDIO_SEQUENCE_GAP', 'audio sequence is not the exact next sequence')
      }
      if (turn.state === 'outcome-unknown') {
        throw new GatewayError('PROVIDER_OUTCOME_UNKNOWN', 'provider effect must be reconciled before more audio')
      }
      if (turn.state !== 'receiving') {
        throw new GatewayError('TURN_NOT_RECEIVING', 'turn is not accepting audio')
      }
      if (turn.nextSequence >= LIMITS.maxChunksPerTurn) {
        throw new GatewayError('TURN_CHUNK_LIMIT_EXCEEDED', 'turn reached its chunk limit')
      }
      if (turn.receivedBytes + payload.length > LIMITS.maxAudioBytesPerTurn) {
        throw new GatewayError('TURN_AUDIO_LIMIT_EXCEEDED', 'turn reached its audio byte limit')
      }

      const sequenceDigest = nextSequenceDigest(turn.sequenceDigest, frame.sequence, frame.contentDigest)
      const effectToken = `audio:${frame.sequence}:${frame.contentDigest}`
      turn.state = 'sending-audio'
      turn.effect = {
        phase: 'audio',
        token: effectToken,
        sequence: frame.sequence,
        contentDigest: frame.contentDigest,
        sequenceDigest,
        payloadBytes: payload.length,
      }
      return {
        state,
        value: {
          replay: false,
          effectToken,
          sequenceDigest,
          providerRequest: {
            sessionId: state.sessionId,
            turnId: turn.turnId,
            retryId: turn.retryId,
            correlationId: turn.correlationId,
            idempotencyKey: `${turn.idempotencyKey}:audio:${frame.sequence}:${frame.contentDigest}`,
            providerTrace: clone(turn.providerTrace),
            sequence: frame.sequence,
            contentDigest: frame.contentDigest,
            payload,
          },
        },
      }
    })
    if (reserved.replay) return reserved

    let accepted
    try {
      accepted = await this.provider.pushAudio(reserved.providerRequest)
      if (accepted?.acceptedSequence !== frame.sequence) {
        throw new Error('provider acknowledged a different audio sequence')
      }
      if (accepted.providerTrace !== undefined) validateProviderTrace(accepted.providerTrace)
    } catch (error) {
      return this.#settleProviderError(lease.sessionId, reserved.effectToken, 'audio', error)
    }

    return this.sessions.transact(lease.sessionId, (state) => {
      if (!state?.turn || state.turn.effect?.token !== reserved.effectToken) {
        throw new GatewayError('PROVIDER_EFFECT_FENCE_LOST', 'provider audio result lost its state fence')
      }
      const turn = state.turn
      turn.chunkDigests.push(frame.contentDigest)
      turn.nextSequence += 1
      turn.receivedBytes += payload.length
      turn.sequenceDigest = reserved.sequenceDigest
      turn.state = 'receiving'
      turn.effect = null
      return {
        state,
        value: {
          replay: false,
          acknowledgement: {
            turnId: turn.turnId,
            retryId: turn.retryId,
            sequence: frame.sequence,
            contentDigest: frame.contentDigest,
            sequenceDigest: turn.sequenceDigest,
            disposition: 'accepted',
          },
        },
      }
    })
  }

  async finishTurn(lease, request) {
    const reserved = await this.sessions.transact(lease.sessionId, (state) => {
      if (!state) throw new GatewayError('SESSION_NOT_FOUND', 'realtime session does not exist')
      validateConnection(state, lease)
      exactTurn(state.turn, request.turnId, request.retryId)
      const turn = state.turn
      if (turn.state === 'completed') {
        if (
          turn.finalSequence !== request.finalSequence ||
          turn.finalSequenceDigest !== request.sequenceDigest
        ) {
          throw new GatewayError('TURN_FINALIZATION_CONFLICT', 'turn was finalized with different sequence facts')
        }
        return { state, value: { replay: true, status: projectSessionStatus(state) } }
      }
      if (turn.state === 'outcome-unknown') {
        throw new GatewayError('PROVIDER_OUTCOME_UNKNOWN', 'provider effect must be reconciled before finalization')
      }
      if (turn.state !== 'receiving') throw new GatewayError('TURN_NOT_RECEIVING', 'turn cannot be finalized now')
      if (request.finalSequence !== turn.nextSequence - 1 || request.sequenceDigest !== turn.sequenceDigest) {
        throw new GatewayError('TURN_FINALIZATION_MISMATCH', 'final sequence or sequence digest is not gateway truth')
      }
      const effectToken = `finish:${request.sequenceDigest}`
      turn.state = 'finishing'
      turn.finalSequence = request.finalSequence
      turn.finalSequenceDigest = request.sequenceDigest
      turn.effect = { phase: 'finish', token: effectToken }
      return {
        state,
        value: {
          replay: false,
          effectToken,
          providerRequest: {
            sessionId: state.sessionId,
            turnId: turn.turnId,
            retryId: turn.retryId,
            correlationId: turn.correlationId,
            idempotencyKey: `${turn.idempotencyKey}:finish:${request.sequenceDigest}`,
            providerTrace: clone(turn.providerTrace),
            finalSequence: request.finalSequence,
            sequenceDigest: request.sequenceDigest,
            receivedBytes: turn.receivedBytes,
          },
        },
      }
    })
    if (reserved.replay) return reserved

    let finished
    try {
      finished = await this.provider.finishTurn(reserved.providerRequest)
      finished = {
        providerTrace: validateProviderTrace(finished?.providerTrace),
        result: validateDisplayResult(finished?.result),
      }
    } catch (error) {
      return this.#settleProviderError(lease.sessionId, reserved.effectToken, 'finish', error)
    }
    return this.sessions.transact(lease.sessionId, (state) => {
      if (!state?.turn || state.turn.effect?.token !== reserved.effectToken) {
        throw new GatewayError('PROVIDER_EFFECT_FENCE_LOST', 'provider finalization lost its state fence')
      }
      state.turn.state = 'completed'
      state.turn.providerTrace = finished.providerTrace
      state.turn.result = finished.result
      state.turn.effect = null
      return { state, value: { replay: false, status: projectSessionStatus(state) } }
    })
  }

  async cancelTurn(lease, request) {
    const reserved = await this.sessions.transact(lease.sessionId, (state) => {
      if (!state) throw new GatewayError('SESSION_NOT_FOUND', 'realtime session does not exist')
      validateConnection(state, lease)
      exactTurn(state.turn, request.turnId, request.retryId)
      const turn = state.turn
      if (turn.state === 'cancelled') return { state, value: { replay: true, status: projectSessionStatus(state) } }
      if (turn.state === 'completed' || turn.state === 'failed') {
        throw new GatewayError('TURN_ALREADY_TERMINAL', 'terminal turn cannot be cancelled')
      }
      if (TRANSITIONAL_TURN_STATES.has(turn.state)) {
        throw new GatewayError('TURN_TRANSITION_IN_PROGRESS', 'turn has another provider effect in progress')
      }
      const effectToken = `cancel:${request.reason}`
      turn.state = 'cancelling'
      turn.effect = { phase: 'cancel', token: effectToken }
      return {
        state,
        value: {
          replay: false,
          effectToken,
          providerRequest: {
            sessionId: state.sessionId,
            turnId: turn.turnId,
            retryId: turn.retryId,
            correlationId: turn.correlationId,
            idempotencyKey: `${turn.idempotencyKey}:cancel:${request.reason}`,
            providerTrace: clone(turn.providerTrace),
            reason: request.reason,
          },
        },
      }
    })
    if (reserved.replay) return reserved

    let cancelled
    try {
      cancelled = await this.provider.cancelTurn(reserved.providerRequest)
      cancelled = {
        providerTrace:
          cancelled?.providerTrace === null || cancelled?.providerTrace === undefined
            ? null
            : validateProviderTrace(cancelled.providerTrace),
      }
    } catch (error) {
      return this.#settleProviderError(lease.sessionId, reserved.effectToken, 'cancel', error)
    }
    return this.sessions.transact(lease.sessionId, (state) => {
      if (!state?.turn || state.turn.effect?.token !== reserved.effectToken) {
        throw new GatewayError('PROVIDER_EFFECT_FENCE_LOST', 'provider cancellation lost its state fence')
      }
      state.turn.state = 'cancelled'
      state.turn.providerTrace = cancelled.providerTrace ?? state.turn.providerTrace
      state.turn.effect = null
      return { state, value: { replay: false, status: projectSessionStatus(state) } }
    })
  }

  async turnStatus(lease, request) {
    return this.sessions.transact(lease.sessionId, (state) => {
      if (!state) throw new GatewayError('SESSION_NOT_FOUND', 'realtime session does not exist')
      validateConnection(state, lease)
      exactTurn(state.turn, request.turnId, request.retryId)
      return { state, value: projectSessionStatus(state) }
    })
  }

  async reconcileTurn(lease, request) {
    const reserved = await this.sessions.transact(lease.sessionId, (state) => {
      if (!state) throw new GatewayError('SESSION_NOT_FOUND', 'realtime session does not exist')
      validateConnection(state, lease)
      exactTurn(state.turn, request.turnId, request.retryId)
      const turn = state.turn
      if (TERMINAL_TURN_STATES.has(turn.state)) {
        return { state, value: { replay: true, status: projectSessionStatus(state) } }
      }
      if (turn.state !== 'outcome-unknown') {
        throw new GatewayError('RECONCILIATION_NOT_REQUIRED', 'turn has no ambiguous provider effect')
      }
      const effectToken = `reconcile:${turn.retryId}`
      turn.state = 'reconciling'
      turn.effect = { phase: 'reconcile', token: effectToken }
      return {
        state,
        value: {
          replay: false,
          effectToken,
          providerRequest: {
            sessionId: state.sessionId,
            turnId: turn.turnId,
            retryId: turn.retryId,
            correlationId: turn.correlationId,
            idempotencyKey: turn.idempotencyKey,
            providerTrace: clone(turn.providerTrace),
          },
        },
      }
    })
    if (reserved.replay) return reserved

    let reconciled
    try {
      reconciled = validateReconcileResult(await this.provider.reconcileTurn(reserved.providerRequest))
    } catch {
      return this.sessions.transact(lease.sessionId, (state) => {
        if (!state?.turn || state.turn.effect?.token !== reserved.effectToken) {
          throw new GatewayError('PROVIDER_EFFECT_FENCE_LOST', 'provider reconciliation lost its state fence')
        }
        state.turn.state = 'outcome-unknown'
        state.turn.effect = null
        return { state, value: { replay: false, status: projectSessionStatus(state) } }
      })
    }

    return this.sessions.transact(lease.sessionId, (state) => {
      if (!state?.turn || state.turn.effect?.token !== reserved.effectToken) {
        throw new GatewayError('PROVIDER_EFFECT_FENCE_LOST', 'provider reconciliation lost its state fence')
      }
      const turn = state.turn
      turn.effect = null
      if (reconciled.outcome === 'pending') {
        turn.state = 'outcome-unknown'
      } else if (reconciled.outcome === 'completed') {
        turn.state = 'completed'
        turn.providerTrace = reconciled.providerTrace
        turn.result = reconciled.result
      } else if (reconciled.outcome === 'cancelled') {
        turn.state = 'cancelled'
        turn.providerTrace = reconciled.providerTrace ?? turn.providerTrace
      } else if (reconciled.outcome === 'failed') {
        turn.state = 'failed'
        turn.providerTrace = reconciled.providerTrace ?? turn.providerTrace
        turn.failure = { code: reconciled.code, retryable: reconciled.retryable }
      } else {
        turn.state = 'failed'
        turn.failure = { code: 'PROVIDER_EFFECT_NOT_FOUND', retryable: true }
      }
      return { state, value: { replay: false, status: projectSessionStatus(state) } }
    })
  }

  async #settleProviderError(sessionId, effectToken, phase, error) {
    return this.sessions.transact(sessionId, (state) => {
      if (!state?.turn || state.turn.effect?.token !== effectToken) {
        throw new GatewayError('PROVIDER_EFFECT_FENCE_LOST', 'provider error lost its state fence')
      }
      const known = providerFailure(error)
      state.turn.effect = null
      if (known) {
        state.turn.state = 'failed'
        state.turn.failure = { code: known.code, retryable: known.retryable }
        state.turn.providerTrace = known.providerTrace ?? state.turn.providerTrace
      } else {
        state.turn.state = 'outcome-unknown'
        state.turn.outcomeUnknownPhase = phase
        state.turn.providerTrace =
          safeProviderTrace(isOutcomeUnknown(error) ? error.providerTrace : undefined) ?? state.turn.providerTrace
      }
      return { state, value: { replay: false, status: projectSessionStatus(state) } }
    })
  }
}
