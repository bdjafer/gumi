import { ProcessingError, fail } from './error.mjs'
import {
  addSeconds,
  before,
  clone,
  codePointLength,
  digest,
  opaque,
  positiveU64,
  u64,
  utcTimestamp,
  uuidV7,
  valueDigest,
} from './primitives.mjs'
import {
  callbackPrincipal,
  controlPrincipal,
  validateCancelRequest,
  validateClaimRequest,
  validateCompletionRequest,
  validateCreateRequest,
  validateRenewRequest,
  validateRetryRequest,
  workerPrincipal,
} from './validation.mjs'
import { assertPorts } from '../ports.mjs'

const DEFAULT_MAX_ATTEMPTS = 3
const DEFAULT_LEASE_SECONDS = 120
const HARD_MAX_ATTEMPTS = 10
const HARD_MAX_LEASE_SECONDS = 3600
const DEFAULT_HARD_MAX_ARTIFACT_BYTES = 67_108_864n
const TRANSCRIPT_PAGE_SEGMENTS = 4
const MAX_TRANSCRIPT_SEGMENTS = 100_000n

function artifactIntegrity(detail, cause = undefined) {
  return new ProcessingError(
    'ARTIFACT_INTEGRITY_MISMATCH',
    422,
    detail,
    undefined,
    cause,
  )
}

function validateTranscriptPageSegments(value, { startIndex, expectedCount, mediaDurationMs }) {
  try {
    if (!Array.isArray(value) || value.length !== expectedCount) {
      throw new TypeError('page cardinality mismatch')
    }
    const duration = u64(mediaDurationMs, 'transcript page mediaDurationMs')
    let priorEnd
    return value.map((segment, offset) => {
      if (!segment || typeof segment !== 'object' || Array.isArray(segment)) {
        throw new TypeError('segment is not an object')
      }
      const expectedKeys = segment.speakerLabel === undefined
        ? ['endMs', 'index', 'startMs', 'text']
        : ['endMs', 'index', 'speakerLabel', 'startMs', 'text']
      const actualKeys = Object.keys(segment).sort()
      if (
        actualKeys.length !== expectedKeys.length ||
        actualKeys.some((key, index) => key !== expectedKeys[index])
      ) {
        throw new TypeError('segment fields mismatch')
      }
      const expectedIndex = String(startIndex + offset)
      if (segment.index !== expectedIndex) throw new TypeError('segment index mismatch')
      const start = u64(segment.startMs, `transcript segment ${expectedIndex} startMs`)
      const end = u64(segment.endMs, `transcript segment ${expectedIndex} endMs`)
      if (end < start || end > duration || (priorEnd !== undefined && start < priorEnd)) {
        throw new TypeError('segment timing mismatch')
      }
      if (typeof segment.text !== 'string' || codePointLength(segment.text) > 32_768) {
        throw new TypeError('segment text bound mismatch')
      }
      if (
        segment.speakerLabel !== undefined &&
        (
          typeof segment.speakerLabel !== 'string' ||
          codePointLength(segment.speakerLabel) < 1 ||
          codePointLength(segment.speakerLabel) > 256
        )
      ) {
        throw new TypeError('segment speaker label bound mismatch')
      }
      priorEnd = end
      return clone(segment)
    })
  } catch (cause) {
    if (cause instanceof ProcessingError && cause.code === 'ARTIFACT_INTEGRITY_MISMATCH') throw cause
    throw artifactIntegrity('The transcript page does not match its immutable artifact.', cause)
  }
}

function generatedId(value, purpose) {
  try {
    return uuidV7(value, purpose)
  } catch (cause) {
    throw new ProcessingError(
      'DURABILITY_UNAVAILABLE',
      503,
      `The identity port returned an invalid ${purpose}.`,
      undefined,
      cause,
    )
  }
}

function missingJob(processingJobId) {
  fail('PROCESSING_JOB_NOT_FOUND', 404, 'The scoped processing job does not exist.', { processingJobId })
}

function requestConflict(requestId) {
  fail('REQUEST_ID_CONFLICT', 409, 'The request identity is already bound to different immutable facts.', { requestId })
}

function assertControlOwner(job, principal) {
  if (job.controlCallerId !== principal.callerId) {
    fail('CONTROL_SCOPE_MISMATCH', 403, 'The control caller does not own this processing job.', {
      processingJobId: job.id,
    })
  }
}

function assertWorkerPipeline(job, principal) {
  if (!principal.allowedPipelineIds.includes(job.pipeline.pipelineId)) {
    fail('WORKER_SCOPE_MISMATCH', 403, 'The worker is not authorized for this immutable pipeline.')
  }
}

function durableWorkerReplayFailure(detail, cause) {
  return new ProcessingError(
    'DURABILITY_UNAVAILABLE',
    503,
    detail,
    undefined,
    cause,
  )
}

function workerReplayBinding(record, expectedOperation, expectedRequestId) {
  try {
    if (record?.operation !== expectedOperation) throw new TypeError('operation mismatch')
    const requestId = uuidV7(record.requestId, 'durable worker replay requestId')
    if (requestId !== expectedRequestId) throw new TypeError('request identity mismatch')
    const processingJobId = uuidV7(record?.processingJobId, 'durable worker replay processingJobId')
    if (typeof record.pipelineId !== 'string' || record.pipelineId.length === 0) throw new TypeError('missing pipeline')
    if (typeof record.workerId !== 'string' || record.workerId.length === 0) throw new TypeError('missing worker')
    return { processingJobId, pipelineId: record.pipelineId, requestId, workerId: record.workerId }
  } catch (cause) {
    throw durableWorkerReplayFailure(
      'A durable worker request binding lost its authorization facts.',
      cause,
    )
  }
}

function authorizeWorkerReplay(record, job, principal, expectedOperation, expectedRequestId) {
  const binding = workerReplayBinding(record, expectedOperation, expectedRequestId)
  if (
    !job ||
    job.id !== binding.processingJobId ||
    job.pipeline?.pipelineId !== binding.pipelineId ||
    binding.workerId !== principal.workerId
  ) {
    throw durableWorkerReplayFailure(
      'A durable worker request binding lost its processing job.',
    )
  }
  assertWorkerPipeline(job, principal)
  return binding
}

function requireCanonicalReplayResponse(actual, expected) {
  try {
    if (valueDigest(actual) !== valueDigest(expected)) throw new TypeError('response mismatch')
  } catch (cause) {
    throw durableWorkerReplayFailure('A durable worker request binding has an incoherent response.', cause)
  }
}

function replayClaimRecord(record, job, principal, request, fingerprint) {
  const binding = authorizeWorkerReplay(record, job, principal, 'claim', request.requestId)
  if (record.fingerprint !== fingerprint) requestConflict(request.requestId)
  let attempt
  try {
    const attemptId = uuidV7(record.attemptId, 'durable claim attemptId')
    attempt = job.attempts.find((candidate) => candidate.id === attemptId)
    if (
      binding.processingJobId !== request.processingJobId ||
      !attempt ||
      attempt.claimRequestId !== binding.requestId ||
      attempt.workerId !== binding.workerId ||
      attempt.generation !== increment(request.expectedGeneration) ||
      typeof attempt.initialLeaseExpiresAt !== 'string'
    ) {
      throw new TypeError('attempt mismatch')
    }
    utcTimestamp(attempt.initialLeaseExpiresAt, 'durable claim initialLeaseExpiresAt')
  } catch (cause) {
    throw durableWorkerReplayFailure('A durable claim binding lost its immutable attempt facts.', cause)
  }
  const expected = claimResponse(job, {
    ...attempt,
    leaseRevision: '0',
    leaseExpiresAt: attempt.initialLeaseExpiresAt,
  })
  requireCanonicalReplayResponse(record.response, expected)
  return clone(record.response)
}

function replayRenewRecord(record, job, principal, request, fingerprint) {
  const binding = authorizeWorkerReplay(record, job, principal, 'renew', request.requestId)
  if (record.fingerprint !== fingerprint) requestConflict(request.requestId)
  let expected
  try {
    const attemptId = uuidV7(record.attemptId, 'durable renewal attemptId')
    const generation = u64(record.generation, 'durable renewal generation').toString()
    const leaseId = uuidV7(record.leaseId, 'durable renewal leaseId')
    const leaseRevision = positiveU64(record.leaseRevision, 'durable renewal leaseRevision').toString()
    const leaseExpiresAt = utcTimestamp(record.leaseExpiresAt, 'durable renewal leaseExpiresAt')
    const attempt = job.attempts.find((candidate) => candidate.id === attemptId)
    if (
      binding.processingJobId !== request.processingJobId ||
      attemptId !== request.attemptId ||
      generation !== request.generation ||
      leaseId !== request.leaseId ||
      leaseRevision !== increment(request.expectedLeaseRevision) ||
      !attempt ||
      attempt.workerId !== binding.workerId ||
      attempt.generation !== generation ||
      attempt.leaseId !== leaseId ||
      u64(attempt.leaseRevision, 'durable attempt leaseRevision') < BigInt(leaseRevision)
    ) {
      throw new TypeError('renewed attempt mismatch')
    }
    expected = {
      schemaVersion: 'gumi.media-processing.lease-renewed.v1',
      requestId: binding.requestId,
      processingJobId: binding.processingJobId,
      attemptId,
      generation,
      leaseId,
      leaseRevision,
      leaseExpiresAt,
    }
  } catch (cause) {
    throw durableWorkerReplayFailure('A durable renewal binding lost its immutable lease facts.', cause)
  }
  requireCanonicalReplayResponse(record.response, expected)
  return clone(record.response)
}

function assertGeneration(job, expected) {
  if (job.generation !== expected) {
    fail('GENERATION_CONFLICT', 409, 'The request targets a stale processing generation.', {
      processingJobId: job.id,
      expectedGeneration: expected,
      actualGeneration: job.generation,
    })
  }
}

function increment(value) {
  return String(BigInt(value) + 1n)
}

function activeAttempt(job) {
  return job.attempts.find((attempt) => attempt.id === job.activeAttemptId)
}

function leaseExpired(attempt, now) {
  return !before(now, attempt.leaseExpiresAt)
}

function requestKey(operation, actor, id) {
  return `${operation}/${actor}/${id}`
}

function requestReplay(record, fingerprint, requestId) {
  if (!record) return undefined
  if (record.fingerprint !== fingerprint) requestConflict(requestId)
  return clone(record.response)
}

function validateResolvedInput(value, requested) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new ProcessingError('INPUT_MANIFEST_UNAVAILABLE', 503, 'The manifest reader returned no immutable input.')
  }
  try {
    if (value.schemaVersion !== 'gumi.media-processing.immutable-input.v1') {
      throw new TypeError('unsupported immutable input schema')
    }
    uuidV7(value.manifestId, 'resolved manifestId')
    digest(value.manifestDigest, 'resolved manifestDigest')
    digest(value.contentDigest, 'resolved contentDigest')
    positiveU64(value.byteLength, 'resolved byteLength')
    positiveU64(value.durationMs, 'resolved durationMs')
    opaque(value.objectHandle, 'resolved objectHandle', { max: 512 })
    opaque(value.contentType, 'resolved contentType', { max: 160 })
  } catch (cause) {
    throw new ProcessingError(
      'INPUT_MANIFEST_UNAVAILABLE',
      503,
      'The manifest reader returned an invalid immutable input projection.',
      undefined,
      cause,
    )
  }
  if (value.manifestId !== requested.manifestId || value.manifestDigest !== requested.expectedManifestDigest) {
    fail('INPUT_MANIFEST_DIGEST_MISMATCH', 409, 'The immutable input does not match the digest-bound request.', {
      manifestId: requested.manifestId,
      expectedManifestDigest: requested.expectedManifestDigest,
    })
  }
  return {
    manifestId: value.manifestId,
    manifestDigest: value.manifestDigest,
    contentDigest: value.contentDigest,
    contentType: value.contentType,
    byteLength: value.byteLength,
    durationMs: value.durationMs,
    objectHandle: value.objectHandle,
  }
}

function publicAttempt(attempt, now) {
  const completing = attempt.state === 'leased' && Boolean(attempt.completionReservation)
  const expired = attempt.state === 'leased' && !completing && leaseExpired(attempt, now)
  return {
    attemptId: attempt.id,
    attemptNumber: attempt.number,
    generation: attempt.generation,
    state: completing ? 'completing' : expired ? 'outcome-unknown' : attempt.state,
    leaseRevision: attempt.leaseRevision,
    claimedAt: attempt.claimedAt,
    leaseExpiresAt: attempt.leaseExpiresAt,
    outcomeUnknown: expired || attempt.outcomeUnknown,
    ...(attempt.completedAt ? { completedAt: attempt.completedAt } : {}),
    ...(attempt.failureCode ? { failureCode: attempt.failureCode } : {}),
    ...(attempt.retryNotBefore ? { retryNotBefore: attempt.retryNotBefore } : {}),
  }
}

function effectiveState(job, now) {
  const attempt = activeAttempt(job)
  if (job.state === 'running' && attempt?.completionReservation) return 'completing'
  if (job.state === 'running' && attempt && leaseExpired(attempt, now)) return 'outcome-unknown'
  return job.state
}

function statusResponse(job, now) {
  const result = job.result
    ? {
        artifactId: job.result.artifactId,
        inputContentDigest: job.result.input.contentDigest,
        outputContentDigest: job.result.output.contentDigest,
      }
    : undefined
  return {
    schemaVersion: 'gumi.media-processing.job-status.v1',
    processingJobId: job.id,
    createRequestId: job.createRequestId,
    state: effectiveState(job, now),
    generation: job.generation,
    stateRevision: job.revision,
    maxAttempts: String(job.maxAttempts),
    input: {
      manifestId: job.input.manifestId,
      manifestDigest: job.input.manifestDigest,
      contentDigest: job.input.contentDigest,
    },
    pipeline: clone(job.pipeline),
    attempts: job.attempts.map((attempt) => publicAttempt(attempt, now)),
    ...(result ? { result } : {}),
    ...(job.cancellation ? { cancellation: clone(job.cancellation) } : {}),
    createdAt: job.createdAt,
    updatedAt: job.updatedAt,
  }
}

function createResponse(job, disposition, now) {
  return {
    schemaVersion: 'gumi.media-processing.job-created.v1',
    requestId: job.createRequestId,
    disposition,
    job: statusResponse(job, now),
  }
}

function claimResponse(job, attempt) {
  return {
    schemaVersion: 'gumi.media-processing.attempt-lease.v1',
    requestId: attempt.claimRequestId,
    processingJobId: job.id,
    attemptId: attempt.id,
    attemptNumber: attempt.number,
    generation: attempt.generation,
    leaseId: attempt.leaseId,
    leaseRevision: attempt.leaseRevision,
    claimedAt: attempt.claimedAt,
    leaseExpiresAt: attempt.leaseExpiresAt,
    input: {
      manifestId: job.input.manifestId,
      manifestDigest: job.input.manifestDigest,
      objectHandle: job.input.objectHandle,
      contentDigest: job.input.contentDigest,
      contentType: job.input.contentType,
      byteLength: job.input.byteLength,
      durationMs: job.input.durationMs,
    },
    pipeline: clone(job.pipeline),
  }
}

function validateAttemptScope(job, request, principal, now, { permitReservation = false } = {}) {
  const attempt = job.attempts.find((candidate) => candidate.id === request.attemptId)
  if (!attempt) fail('ATTEMPT_NOT_FOUND', 404, 'The scoped attempt does not exist.', { attemptId: request.attemptId })
  if (attempt.generation !== request.generation || job.generation !== request.generation) {
    fail('ATTEMPT_SUPERSEDED', 409, 'The callback targets a superseded generation.', {
      processingJobId: job.id,
      attemptId: attempt.id,
      currentGeneration: job.generation,
    })
  }
  if (
    principal.processingJobId !== job.id ||
    principal.attemptId !== attempt.id ||
    principal.providerId !== job.pipeline.provider.providerId
  ) {
    fail('CALLBACK_SCOPE_MISMATCH', 403, 'The authenticated callback is not bound to this job, attempt, and provider.')
  }
  if (job.state === 'canceled') fail('JOB_CANCELED', 409, 'The processing job was canceled.')
  if (job.state !== 'running' || job.activeAttemptId !== attempt.id) {
    fail('ATTEMPT_SUPERSEDED', 409, 'The attempt is no longer the active processing generation.')
  }
  if (attempt.completionReservation) {
    if (permitReservation) return attempt
    fail('COMPLETION_IN_PROGRESS', 409, 'The attempt has a durable immutable completion reservation.')
  }
  if (leaseExpired(attempt, now)) {
    fail('ATTEMPT_LEASE_EXPIRED', 409, 'The provider outcome arrived after the attempt lease expired.', {
      processingJobId: job.id,
      attemptId: attempt.id,
      outcomeUnknown: true,
    })
  }
  return attempt
}

function assertExpectedResultBinding(job, result) {
  const expected = job.pipeline.provider
  const actual = result.provenance
  if (
    actual.providerId !== expected.providerId ||
    actual.model !== expected.model ||
    actual.modelVersion !== expected.modelVersion
  ) {
    fail('PROVIDER_PROVENANCE_CONFLICT', 409, 'The result provenance differs from the immutable pipeline binding.', {
      processingJobId: job.id,
    })
  }
  if (result.timing.mediaDurationMs !== job.input.durationMs) {
    fail('RESULT_PROVENANCE_CONFLICT', 409, 'The result media duration differs from the immutable input.', {
      processingJobId: job.id,
    })
  }
  if (
    result.language.basis === 'requested' &&
    (job.pipeline.languageHint === undefined || result.language.tag !== job.pipeline.languageHint)
  ) {
    fail('RESULT_PROVENANCE_CONFLICT', 409, 'Requested-language provenance differs from the pipeline binding.', {
      processingJobId: job.id,
    })
  }
}

function completionResponse(job, attempt, disposition) {
  return {
    schemaVersion: 'gumi.media-processing.attempt-completed.v1',
    callbackId: attempt.completionCallbackId,
    processingJobId: job.id,
    attemptId: attempt.id,
    generation: attempt.generation,
    disposition,
    jobState: job.state,
    ...(job.result
      ? {
          result: {
            artifactId: job.result.artifactId,
            inputContentDigest: job.result.input.contentDigest,
            outputContentDigest: job.result.output.contentDigest,
          },
        }
      : {}),
    completedAt: attempt.completedAt,
  }
}

export class MediaProcessingService {
  constructor({
    storage,
    clock,
    ids,
    manifestReader,
    artifacts,
    maxAttempts = DEFAULT_MAX_ATTEMPTS,
    leaseDurationSeconds = DEFAULT_LEASE_SECONDS,
    hardMaxArtifactBytes = DEFAULT_HARD_MAX_ARTIFACT_BYTES,
  }) {
    assertPorts({ storage, clock, ids, manifestReader, artifacts })
    if (!Number.isSafeInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > HARD_MAX_ATTEMPTS) {
      throw new TypeError(`maxAttempts must be between 1 and ${HARD_MAX_ATTEMPTS}`)
    }
    if (
      !Number.isSafeInteger(leaseDurationSeconds) ||
      leaseDurationSeconds < 1 ||
      leaseDurationSeconds > HARD_MAX_LEASE_SECONDS
    ) {
      throw new TypeError(`leaseDurationSeconds must be between 1 and ${HARD_MAX_LEASE_SECONDS}`)
    }
    if (
      typeof hardMaxArtifactBytes !== 'bigint' ||
      hardMaxArtifactBytes < 1n ||
      hardMaxArtifactBytes > DEFAULT_HARD_MAX_ARTIFACT_BYTES
    ) {
      throw new TypeError('hardMaxArtifactBytes must be a bigint in 1..67108864')
    }
    this.storage = storage
    this.clock = clock
    this.ids = ids
    this.manifestReader = manifestReader
    this.artifacts = artifacts
    this.maxAttempts = maxAttempts
    this.leaseDurationSeconds = leaseDurationSeconds
    this.hardMaxArtifactBytes = hardMaxArtifactBytes
  }

  async createJob(request, actualPrincipal) {
    validateCreateRequest(request)
    const principal = controlPrincipal(actualPrincipal)
    const fingerprint = valueDigest(request)
    const key = requestKey('create', principal.callerId, request.requestId)
    const prior = await this.storage.readRequest(key)
    if (prior) {
      if (prior.fingerprint !== fingerprint) requestConflict(request.requestId)
      const existing = await this.storage.readJob(prior.processingJobId)
      if (!existing) throw new ProcessingError('DURABILITY_UNAVAILABLE', 503, 'A durable create binding lost its job.')
      return createResponse(existing, 'existing', this.clock.now())
    }

    const resolved = validateResolvedInput(
      await this.manifestReader.resolveImmutableManifest({
        manifestId: request.inputManifest.manifestId,
        expectedManifestDigest: request.inputManifest.expectedManifestDigest,
        controlCallerId: principal.callerId,
      }),
      request.inputManifest,
    )
    const processingJobId = generatedId(await this.ids.newOpaqueId('processing-job'), 'processingJobId')
    const now = utcTimestamp(this.clock.now(), 'clock.now')
    const job = await this.storage.transaction('create-job', async (tx) => {
      const replay = tx.getRequest(key)
      if (replay) {
        if (replay.fingerprint !== fingerprint) requestConflict(request.requestId)
        const existing = tx.getJob(replay.processingJobId)
        if (!existing) throw new ProcessingError('DURABILITY_UNAVAILABLE', 503, 'A create binding lost its job.')
        return existing
      }
      const created = {
        id: processingJobId,
        controlCallerId: principal.callerId,
        createRequestId: request.requestId,
        createFingerprint: fingerprint,
        input: clone(resolved),
        pipeline: clone(request.pipeline),
        maxAttempts: this.maxAttempts,
        state: 'queued',
        generation: '0',
        revision: '1',
        attempts: [],
        activeAttemptId: undefined,
        result: undefined,
        cancellation: undefined,
        createdAt: now,
        updatedAt: now,
      }
      tx.putJob(created)
      tx.putRequest(key, { fingerprint, processingJobId })
      return created
    })
    return createResponse(job, job.id === processingJobId ? 'created' : 'existing', now)
  }

  async getStatus(processingJobId, actualPrincipal) {
    uuidV7(processingJobId, 'processingJobId')
    const principal = controlPrincipal(actualPrincipal)
    const job = await this.storage.readJob(processingJobId)
    if (!job) missingJob(processingJobId)
    assertControlOwner(job, principal)
    return statusResponse(job, utcTimestamp(this.clock.now(), 'clock.now'))
  }

  async claimAttempt(request, actualPrincipal) {
    validateClaimRequest(request)
    const principal = workerPrincipal(actualPrincipal)
    const fingerprint = valueDigest(request)
    const key = requestKey('claim', principal.workerId, request.requestId)

    const priorRecord = await this.storage.readRequest(key)
    if (priorRecord) {
      const binding = workerReplayBinding(priorRecord, 'claim', request.requestId)
      return replayClaimRecord(
        priorRecord,
        await this.storage.readJob(binding.processingJobId),
        principal,
        request,
        fingerprint,
      )
    }

    const candidateAttemptId = generatedId(await this.ids.newOpaqueId('processing-attempt'), 'attemptId')
    const candidateLeaseId = generatedId(await this.ids.newOpaqueId('processing-lease'), 'leaseId')
    const now = utcTimestamp(this.clock.now(), 'clock.now')
    return this.storage.transaction('claim-attempt', async (tx) => {
      const replayRecord = tx.getRequest(key)
      if (replayRecord) {
        const binding = workerReplayBinding(replayRecord, 'claim', request.requestId)
        return replayClaimRecord(
          replayRecord,
          tx.getJob(binding.processingJobId),
          principal,
          request,
          fingerprint,
        )
      }
      const job = tx.getJob(request.processingJobId)
      if (!job) missingJob(request.processingJobId)
      assertWorkerPipeline(job, principal)
      assertGeneration(job, request.expectedGeneration)
      const current = activeAttempt(job)
      if (job.state === 'running' && current?.completionReservation) {
        fail('COMPLETION_IN_PROGRESS', 409, 'The current attempt has a durable completion reservation.')
      }
      if (job.state === 'running' && current && leaseExpired(current, now)) {
        fail(
          'OUTCOME_UNKNOWN_REQUIRES_RECONCILIATION',
          409,
          'The expired attempt may have caused an external effect; acknowledge it explicitly before retrying.',
          { processingJobId: job.id, attemptId: current.id },
        )
      }
      if (job.state !== 'queued') {
        fail('JOB_NOT_CLAIMABLE', 409, 'The processing job is not queued for a worker.', {
          processingJobId: job.id,
          state: effectiveState(job, now),
        })
      }
      if (job.attempts.length >= job.maxAttempts) {
        fail('ATTEMPT_LIMIT_REACHED', 409, 'The processing job exhausted its bounded attempt budget.')
      }
      job.generation = increment(job.generation)
      job.revision = increment(job.revision)
      job.state = 'running'
      job.updatedAt = now
      const attempt = {
        id: candidateAttemptId,
        number: String(job.attempts.length + 1),
        generation: job.generation,
        workerId: principal.workerId,
        claimRequestId: request.requestId,
        leaseId: candidateLeaseId,
        leaseRevision: '0',
        claimedAt: now,
        initialLeaseExpiresAt: addSeconds(now, this.leaseDurationSeconds),
        leaseExpiresAt: addSeconds(now, this.leaseDurationSeconds),
        state: 'leased',
        outcomeUnknown: false,
      }
      job.attempts.push(attempt)
      job.activeAttemptId = attempt.id
      const response = claimResponse(job, attempt)
      tx.putJob(job)
      tx.putRequest(key, {
        operation: 'claim',
        requestId: request.requestId,
        fingerprint,
        processingJobId: job.id,
        pipelineId: job.pipeline.pipelineId,
        workerId: principal.workerId,
        attemptId: attempt.id,
        response,
      })
      return response
    })
  }

  async renewLease(request, actualPrincipal) {
    validateRenewRequest(request)
    const principal = workerPrincipal(actualPrincipal)
    const fingerprint = valueDigest(request)
    const key = requestKey('renew', principal.workerId, request.requestId)

    const priorRecord = await this.storage.readRequest(key)
    if (priorRecord) {
      const binding = workerReplayBinding(priorRecord, 'renew', request.requestId)
      return replayRenewRecord(
        priorRecord,
        await this.storage.readJob(binding.processingJobId),
        principal,
        request,
        fingerprint,
      )
    }

    const now = utcTimestamp(this.clock.now(), 'clock.now')
    return this.storage.transaction('renew-lease', async (tx) => {
      const replayRecord = tx.getRequest(key)
      if (replayRecord) {
        const binding = workerReplayBinding(replayRecord, 'renew', request.requestId)
        return replayRenewRecord(
          replayRecord,
          tx.getJob(binding.processingJobId),
          principal,
          request,
          fingerprint,
        )
      }
      const job = tx.getJob(request.processingJobId)
      if (!job) missingJob(request.processingJobId)
      assertWorkerPipeline(job, principal)
      assertGeneration(job, request.generation)
      const attempt = job.attempts.find((candidate) => candidate.id === request.attemptId)
      if (!attempt) fail('ATTEMPT_NOT_FOUND', 404, 'The scoped attempt does not exist.')
      if (attempt.workerId !== principal.workerId) {
        fail('WORKER_SCOPE_MISMATCH', 403, 'The worker does not own this attempt lease.')
      }
      if (
        job.state !== 'running' ||
        job.activeAttemptId !== attempt.id ||
        attempt.generation !== request.generation ||
        attempt.leaseId !== request.leaseId
      ) {
        fail('ATTEMPT_SUPERSEDED', 409, 'The worker lease is no longer active.')
      }
      if (attempt.leaseRevision !== request.expectedLeaseRevision) {
        fail('LEASE_REVISION_CONFLICT', 409, 'The renewal targets a stale lease revision.', {
          actualLeaseRevision: attempt.leaseRevision,
        })
      }
      if (leaseExpired(attempt, now)) {
        fail('ATTEMPT_LEASE_EXPIRED', 409, 'An expired worker lease cannot be renewed.', { outcomeUnknown: true })
      }
      attempt.leaseRevision = increment(attempt.leaseRevision)
      attempt.leaseExpiresAt = addSeconds(now, this.leaseDurationSeconds)
      job.revision = increment(job.revision)
      job.updatedAt = now
      const response = {
        schemaVersion: 'gumi.media-processing.lease-renewed.v1',
        requestId: request.requestId,
        processingJobId: job.id,
        attemptId: attempt.id,
        generation: attempt.generation,
        leaseId: attempt.leaseId,
        leaseRevision: attempt.leaseRevision,
        leaseExpiresAt: attempt.leaseExpiresAt,
      }
      tx.putJob(job)
      tx.putRequest(key, {
        operation: 'renew',
        requestId: request.requestId,
        fingerprint,
        processingJobId: job.id,
        pipelineId: job.pipeline.pipelineId,
        workerId: principal.workerId,
        attemptId: attempt.id,
        generation: attempt.generation,
        leaseId: attempt.leaseId,
        leaseRevision: attempt.leaseRevision,
        leaseExpiresAt: attempt.leaseExpiresAt,
        response,
      })
      return response
    })
  }

  async retryJob(request, actualPrincipal) {
    validateRetryRequest(request)
    const principal = controlPrincipal(actualPrincipal)
    const fingerprint = valueDigest(request)
    const key = requestKey('retry', principal.callerId, request.requestId)
    const prior = requestReplay(await this.storage.readRequest(key), fingerprint, request.requestId)
    if (prior) return prior
    const now = utcTimestamp(this.clock.now(), 'clock.now')
    return this.storage.transaction('retry-job', async (tx) => {
      const replay = requestReplay(tx.getRequest(key), fingerprint, request.requestId)
      if (replay) return replay
      const job = tx.getJob(request.processingJobId)
      if (!job) missingJob(request.processingJobId)
      assertControlOwner(job, principal)
      assertGeneration(job, request.expectedGeneration)
      const attempt = activeAttempt(job)
      if (attempt?.completionReservation) {
        fail('COMPLETION_IN_PROGRESS', 409, 'The current attempt has a durable completion reservation.')
      }
      const expired = job.state === 'running' && attempt && leaseExpired(attempt, now)
      const unknown = job.state === 'outcome-unknown' || expired
      if (unknown && !request.acknowledgeOutcomeUnknown) {
        fail(
          'OUTCOME_UNKNOWN_ACK_REQUIRED',
          409,
          'Retrying may duplicate an external provider effect; explicit acknowledgement is required.',
        )
      }
      if (!unknown && job.state !== 'retryable') {
        fail('JOB_NOT_RETRYABLE', 409, 'The processing job is not in a retryable state.', {
          state: effectiveState(job, now),
        })
      }
      const priorAttempt = job.attempts.at(-1)
      if (job.state === 'retryable' && priorAttempt?.retryNotBefore && before(now, priorAttempt.retryNotBefore)) {
        fail('RETRY_NOT_READY', 409, 'The provider retry delay has not elapsed.', {
          processingJobId: job.id,
          retryNotBefore: priorAttempt.retryNotBefore,
        })
      }
      if (job.attempts.length >= job.maxAttempts) {
        fail('ATTEMPT_LIMIT_REACHED', 409, 'The processing job exhausted its bounded attempt budget.')
      }
      if (unknown && attempt) {
        attempt.state = 'outcome-unknown'
        attempt.outcomeUnknown = true
        attempt.completedAt = now
      }
      job.state = 'queued'
      job.activeAttemptId = undefined
      job.revision = increment(job.revision)
      job.updatedAt = now
      const response = {
        schemaVersion: 'gumi.media-processing.job-retried.v1',
        requestId: request.requestId,
        processingJobId: job.id,
        disposition: 'queued',
        acknowledgedOutcomeUnknown: unknown,
        generation: job.generation,
        stateRevision: job.revision,
        queuedAt: now,
      }
      tx.putJob(job)
      tx.putRequest(key, { fingerprint, response })
      return response
    })
  }

  async cancelJob(request, actualPrincipal) {
    validateCancelRequest(request)
    const principal = controlPrincipal(actualPrincipal)
    const fingerprint = valueDigest(request)
    const key = requestKey('cancel', principal.callerId, request.requestId)
    const prior = requestReplay(await this.storage.readRequest(key), fingerprint, request.requestId)
    if (prior) return prior
    const now = utcTimestamp(this.clock.now(), 'clock.now')
    return this.storage.transaction('cancel-job', async (tx) => {
      const replay = requestReplay(tx.getRequest(key), fingerprint, request.requestId)
      if (replay) return replay
      const job = tx.getJob(request.processingJobId)
      if (!job) missingJob(request.processingJobId)
      assertControlOwner(job, principal)
      assertGeneration(job, request.expectedGeneration)
      if (['succeeded', 'failed', 'canceled'].includes(job.state)) {
        fail('JOB_TERMINAL', 409, 'A terminal processing job cannot be canceled.', { state: job.state })
      }
      const attempt = activeAttempt(job)
      if (attempt?.completionReservation) {
        fail('COMPLETION_IN_PROGRESS', 409, 'The current attempt has a durable completion reservation.')
      }
      const outcomeUnknown = job.state === 'running' && Boolean(attempt)
      if (attempt) {
        attempt.state = 'canceled'
        attempt.outcomeUnknown = outcomeUnknown
        attempt.completedAt = now
      }
      job.state = 'canceled'
      job.activeAttemptId = undefined
      job.revision = increment(job.revision)
      job.updatedAt = now
      job.cancellation = {
        requestId: request.requestId,
        canceledAt: now,
        providerOutcomeUnknown: outcomeUnknown,
      }
      const response = {
        schemaVersion: 'gumi.media-processing.job-canceled.v1',
        requestId: request.requestId,
        processingJobId: job.id,
        disposition: 'canceled',
        generation: job.generation,
        providerOutcomeUnknown: outcomeUnknown,
        canceledAt: now,
      }
      tx.putJob(job)
      tx.putRequest(key, { fingerprint, response })
      return response
    })
  }

  async completeAttempt(request, actualPrincipal) {
    validateCompletionRequest(request)
    const principal = callbackPrincipal(actualPrincipal)
    if (principal.processingJobId !== request.processingJobId || principal.attemptId !== request.attemptId) {
      fail('CALLBACK_SCOPE_MISMATCH', 403, 'The callback principal is bound to a different job or attempt.')
    }
    const fingerprint = valueDigest(request)
    const key = requestKey('complete', principal.providerId, request.callbackId)

    const prior = requestReplay(await this.storage.readRequest(key), fingerprint, request.callbackId)
    if (prior) return prior

    if (request.outcome !== 'succeeded') {
      const now = utcTimestamp(this.clock.now(), 'clock.now')
      return this.#completeFailure({ request, principal, fingerprint, key, now })
    }
    return this.#completeSuccess({ request, principal, fingerprint, key })
  }

  async #completeFailure({ request, principal, fingerprint, key, now }) {
    return this.storage.transaction('complete-failed-attempt', async (tx) => {
      const replay = requestReplay(tx.getRequest(key), fingerprint, request.callbackId)
      if (replay) return replay
      const job = tx.getJob(request.processingJobId)
      if (!job) missingJob(request.processingJobId)
      const attempt = validateAttemptScope(job, request, principal, now)
      attempt.state = request.outcome
      attempt.failureCode = request.failure.code
      if (request.outcome === 'retryable-failure' && request.failure.retryAfterSeconds) {
        attempt.retryNotBefore = addSeconds(now, Number(request.failure.retryAfterSeconds))
      }
      attempt.completedAt = now
      attempt.completionCallbackId = request.callbackId
      job.activeAttemptId = undefined
      job.state =
        request.outcome === 'retryable-failure' && job.attempts.length < job.maxAttempts ? 'retryable' : 'failed'
      job.revision = increment(job.revision)
      job.updatedAt = now
      const response = completionResponse(job, attempt, 'committed')
      tx.putJob(job)
      tx.putRequest(key, { fingerprint, response })
      return response
    })
  }

  async #completeSuccess({ request, principal, fingerprint, key }) {
    const prior = requestReplay(await this.storage.readRequest(key), fingerprint, request.callbackId)
    if (prior) return prior
    const preflightJob = await this.storage.readJob(request.processingJobId)
    if (!preflightJob) missingJob(request.processingJobId)
    const preflightCandidate = preflightJob.attempts.find((candidate) => candidate.id === request.attemptId)
    const preflightReservation = preflightCandidate?.completionReservation
    const now = preflightReservation ? undefined : utcTimestamp(this.clock.now(), 'clock.now')
    validateAttemptScope(preflightJob, request, principal, now, { permitReservation: true })
    assertExpectedResultBinding(preflightJob, request.result)
    if (BigInt(request.result.artifact.byteLength) > this.hardMaxArtifactBytes) {
      fail('ARTIFACT_TOO_LARGE', 413, 'The derived artifact exceeds the configured immutable size bound.', {
        maximumByteLength: String(this.hardMaxArtifactBytes),
      })
    }
    const artifactRecord = {
      stagedArtifactHandle: request.result.artifact.stagedArtifactHandle,
      contentDigest: request.result.artifact.contentDigest,
      byteLength: request.result.artifact.byteLength,
      contentType: request.result.artifact.contentType,
      processingJobId: request.processingJobId,
      attemptId: request.attemptId,
      generation: request.generation,
      artifactFacts: {
        language: clone(request.result.language),
        timing: clone(request.result.timing),
        segments: clone(request.result.segments),
      },
    }
    await this.artifacts.validateStagedArtifact(artifactRecord)
    let candidateArtifactId
    if (preflightReservation) {
      if (
        preflightReservation.callbackId !== request.callbackId ||
        preflightReservation.fingerprint !== fingerprint
      ) {
        fail('ATTEMPT_COMPLETION_CONFLICT', 409, 'The attempt already reserved a different immutable completion.')
      }
      candidateArtifactId = preflightReservation.artifactId
    } else {
      candidateArtifactId = generatedId(await this.ids.newOpaqueId('derived-artifact'), 'artifactId')
    }
    const reservation = await this.storage.transaction('reserve-completion', async (tx) => {
      const replay = tx.getRequest(key)
      if (replay) {
        if (replay.fingerprint !== fingerprint) requestConflict(request.callbackId)
        return { terminalResponse: clone(replay.response) }
      }
      const job = tx.getJob(request.processingJobId)
      if (!job) missingJob(request.processingJobId)
      const attempt = validateAttemptScope(job, request, principal, now, { permitReservation: true })
      assertExpectedResultBinding(job, request.result)
      if (attempt.completionReservation) {
        if (
          attempt.completionReservation.callbackId !== request.callbackId ||
          attempt.completionReservation.fingerprint !== fingerprint
        ) {
          fail('ATTEMPT_COMPLETION_CONFLICT', 409, 'The attempt already reserved a different immutable completion.')
        }
        return {
          artifactId: attempt.completionReservation.artifactId,
          input: clone(job.input),
          pipeline: clone(job.pipeline),
        }
      }
      if (now === undefined) {
        throw new ProcessingError(
          'DURABILITY_UNAVAILABLE',
          503,
          'A durable completion reservation disappeared during recovery.',
        )
      }
      attempt.completionReservation = {
        callbackId: request.callbackId,
        fingerprint,
        artifactId: candidateArtifactId,
        reservedAt: now,
        output: {
          contentDigest: request.result.artifact.contentDigest,
          byteLength: request.result.artifact.byteLength,
          contentType: request.result.artifact.contentType,
        },
      }
      job.revision = increment(job.revision)
      job.updatedAt = now
      tx.putJob(job)
      return { artifactId: candidateArtifactId, input: clone(job.input), pipeline: clone(job.pipeline) }
    })
    if (reservation.terminalResponse) return reservation.terminalResponse

    const committedArtifact = await this.artifacts.commitDerivedArtifact({
      ...artifactRecord,
      artifactId: reservation.artifactId,
      inputContentDigest: reservation.input.contentDigest,
    })
    try {
      opaque(committedArtifact?.artifactHandle, 'artifactStore.artifactHandle', { max: 512 })
    } catch (cause) {
      throw new ProcessingError(
        'DURABILITY_UNAVAILABLE',
        503,
        'The artifact store returned an invalid immutable handle.',
        undefined,
        cause,
      )
    }

    return this.storage.transaction('commit-completion', async (tx) => {
      const replay = requestReplay(tx.getRequest(key), fingerprint, request.callbackId)
      if (replay) return replay
      const job = tx.getJob(request.processingJobId)
      if (!job) missingJob(request.processingJobId)
      const attempt = validateAttemptScope(job, request, principal, now, { permitReservation: true })
      const held = attempt.completionReservation
      if (!held || held.callbackId !== request.callbackId || held.fingerprint !== fingerprint) {
        fail('ATTEMPT_COMPLETION_CONFLICT', 409, 'The durable completion reservation no longer matches.')
      }
      const completedAt = utcTimestamp(this.clock.now(), 'clock.now')
      job.result = {
        schemaVersion: 'gumi.media-processing.derived-artifact-projection.v1',
        processingJobId: job.id,
        artifactId: held.artifactId,
        artifactHandle: committedArtifact.artifactHandle,
        input: {
          manifestId: job.input.manifestId,
          manifestDigest: job.input.manifestDigest,
          contentDigest: job.input.contentDigest,
        },
        output: {
          contentDigest: request.result.artifact.contentDigest,
          byteLength: request.result.artifact.byteLength,
          contentType: request.result.artifact.contentType,
        },
        provenance: {
          pipelineId: job.pipeline.pipelineId,
          configurationDigest: job.pipeline.configurationDigest,
          providerId: request.result.provenance.providerId,
          model: request.result.provenance.model,
          modelVersion: request.result.provenance.modelVersion,
          attemptId: attempt.id,
          generation: attempt.generation,
          language: clone(request.result.language),
          timing: clone(request.result.timing),
          segments: clone(request.result.segments),
        },
        committedAt: completedAt,
      }
      attempt.state = 'succeeded'
      attempt.completedAt = completedAt
      attempt.completionCallbackId = request.callbackId
      job.state = 'succeeded'
      job.activeAttemptId = undefined
      job.revision = increment(job.revision)
      job.updatedAt = completedAt
      const response = completionResponse(job, attempt, 'committed')
      tx.putJob(job)
      tx.putRequest(key, { fingerprint, response })
      return response
    })
  }

  async resolveResult({ processingJobId, expectedInputContentDigest, expectedOutputContentDigest }, actualPrincipal) {
    uuidV7(processingJobId, 'processingJobId')
    digest(expectedInputContentDigest, 'expectedInputContentDigest')
    digest(expectedOutputContentDigest, 'expectedOutputContentDigest')
    const principal = controlPrincipal(actualPrincipal)
    const job = await this.storage.readJob(processingJobId)
    if (!job) missingJob(processingJobId)
    assertControlOwner(job, principal)
    if (!job.result) fail('RESULT_NOT_FOUND', 404, 'The processing job has no committed derived artifact.')
    if (
      job.result.input.contentDigest !== expectedInputContentDigest ||
      job.result.output.contentDigest !== expectedOutputContentDigest
    ) {
      fail('RESULT_DIGEST_MISMATCH', 409, 'The immutable result does not match both expected digests.', {
        processingJobId,
      })
    }
    return clone(job.result)
  }

  async readTranscriptPage(
    { processingJobId, startIndex, expectedInputContentDigest, expectedOutputContentDigest },
    actualPrincipal,
  ) {
    uuidV7(processingJobId, 'processingJobId')
    const requestedStart = u64(startIndex, 'startIndex')
    if (requestedStart > MAX_TRANSCRIPT_SEGMENTS) {
      fail('INVALID_REQUEST', 400, 'The transcript page start exceeds the v1 segment bound.')
    }
    const result = await this.resolveResult(
      { processingJobId, expectedInputContentDigest, expectedOutputContentDigest },
      actualPrincipal,
    )

    let total
    try {
      total = u64(result.provenance?.segments?.count, 'result segment count')
      positiveU64(result.provenance?.timing?.mediaDurationMs, 'result media duration')
      if (total > MAX_TRANSCRIPT_SEGMENTS) throw new TypeError('segment count exceeds v1 bound')
    } catch (cause) {
      throw artifactIntegrity('The committed transcript projection has invalid aggregate facts.', cause)
    }
    if ((total === 0n && requestedStart !== 0n) || (total > 0n && requestedStart >= total)) {
      fail('TRANSCRIPT_PAGE_NOT_FOUND', 404, 'The requested transcript page is outside the immutable artifact.', {
        processingJobId,
      })
    }

    const start = Number(requestedStart)
    const remaining = Number(total - requestedStart)
    const expectedCount = Math.min(TRANSCRIPT_PAGE_SEGMENTS, remaining)
    const segments = validateTranscriptPageSegments(
      await this.artifacts.readTranscriptPage({
        artifactId: result.artifactId,
        processingJobId,
        inputContentDigest: result.input.contentDigest,
        outputContentDigest: result.output.contentDigest,
        byteLength: result.output.byteLength,
        contentType: result.output.contentType,
        artifactFacts: {
          language: clone(result.provenance.language),
          timing: clone(result.provenance.timing),
          segments: clone(result.provenance.segments),
        },
        startIndex: start,
        maximumSegments: TRANSCRIPT_PAGE_SEGMENTS,
      }),
      {
        startIndex: start,
        expectedCount,
        mediaDurationMs: result.provenance.timing.mediaDurationMs,
      },
    )
    const end = requestedStart + BigInt(segments.length)
    const page = {
      schemaVersion: 'gumi.media-processing.transcript-page.v1',
      processingJobId,
      artifactId: result.artifactId,
      inputContentDigest: result.input.contentDigest,
      outputContentDigest: result.output.contentDigest,
      language: result.provenance.language.tag,
      mediaDurationMs: result.provenance.timing.mediaDurationMs,
      totalSegmentCount: String(total),
      startIndex: String(requestedStart),
      endIndexExclusive: String(end),
      segments,
    }
    if (end < total) page.nextStartIndex = String(end)
    return page
  }
}
