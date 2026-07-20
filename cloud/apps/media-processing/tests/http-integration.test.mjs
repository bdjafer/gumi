import assert from 'node:assert/strict'
import http from 'node:http'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import {
  DeterministicIds,
  InMemoryArtifactStore,
  InMemoryHttpAuthorizer,
  InMemoryManifestReader,
  InMemoryProcessingStorage,
  ManualClock,
  MediaProcessingService,
  RateLimitedError,
  createMediaProcessingHttpServer,
} from '../src/index.mjs'
import { byteDigest } from '../src/core/primitives.mjs'

const APP = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const id = (number) => `0190c6f0-7b21-7a40-8b11-${BigInt(number).toString(16).padStart(12, '0')}`
const hash = (character) => `sha256:${character.repeat(64)}`

const CONTROL_TOKEN = 'processing-control-token-never-log-0001'
const WORKER_TOKEN = 'processing-worker-token-never-log-0002'
const CALLBACK_TOKEN = 'processing-callback-token-never-log-0003'
const FOREIGN_CALLBACK_TOKEN = 'processing-foreign-callback-token-never-log-0004'
const INVALID_TOKEN = 'processing-invalid-token-with-secret-message-0005'
const RATE_LIMITED_TOKEN = 'processing-rate-limited-token-never-log-0006'
const VERIFIER_FAILURE_TOKEN = 'processing-verifier-failure-token-never-log-0008'
const CORRELATION_ID = id(90)
const CONTROL_AUDIENCE = 'gumi.media-processing.control'
const WORKER_AUDIENCE = 'gumi.media-processing.worker'
const CALLBACK_AUDIENCE = 'gumi.media-processing.callback'
const HTTP_OPTIONS = {
  controlCredentialAudience: CONTROL_AUDIENCE,
  workerCredentialAudience: WORKER_AUDIENCE,
  callbackCredentialAudience: CALLBACK_AUDIENCE,
}

function fixture(path) {
  return JSON.parse(readFileSync(join(APP, 'fixtures', 'v1', path), 'utf8'))
}

function deferred() {
  let resolvePromise
  let rejectPromise
  const promise = new Promise((resolve, reject) => {
    resolvePromise = resolve
    rejectPromise = reject
  })
  return { promise, resolve: resolvePromise, reject: rejectPromise }
}

async function within(promise, label, milliseconds = 2_000) {
  let timer
  try {
    return await Promise.race([
      promise,
      new Promise((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`Timed out waiting for ${label}`)), milliseconds)
      }),
    ])
  } finally {
    clearTimeout(timer)
  }
}

function jsonBytes(value) {
  return Buffer.from(JSON.stringify(value), 'utf8')
}

function connectionOptions(origin, path) {
  const target = new URL(origin)
  return { protocol: target.protocol, hostname: target.hostname, port: target.port, path }
}

function request(origin, { method = 'GET', path = '/', headers = {}, body = undefined, omitLength = false } = {}) {
  const bytes = body === undefined ? undefined : Buffer.isBuffer(body) ? body : Buffer.from(body)
  const outgoingHeaders = { ...headers }
  if (bytes !== undefined && !omitLength && outgoingHeaders['content-length'] === undefined) {
    outgoingHeaders['content-length'] = String(bytes.length)
  }
  return new Promise((resolvePromise, rejectPromise) => {
    const client = http.request({ ...connectionOptions(origin, path), method, headers: outgoingHeaders }, (response) => {
      const chunks = []
      response.on('data', (chunk) => chunks.push(Buffer.from(chunk)))
      response.on('end', () => {
        const bytes = Buffer.concat(chunks)
        const contentType = response.headers['content-type'] ?? ''
        const json = contentType.includes('json') && bytes.length > 0 ? JSON.parse(bytes.toString('utf8')) : undefined
        resolvePromise({ status: response.statusCode, headers: response.headers, bytes, json })
      })
    })
    client.on('error', rejectPromise)
    client.end(bytes)
  })
}

function openRequest(origin, { method = 'POST', path, headers, body }) {
  const bytes = Buffer.isBuffer(body) ? body : Buffer.from(body)
  const client = http.request({
    ...connectionOptions(origin, path),
    method,
    headers: { ...headers, 'content-length': String(bytes.length) },
  })
  const completion = new Promise((resolvePromise) => {
    client.on('response', (response) => {
      response.resume()
      response.on('end', () => resolvePromise({ kind: 'response', status: response.statusCode }))
    })
    client.on('error', (error) => resolvePromise({ kind: 'error', error }))
    client.on('close', () => resolvePromise({ kind: 'closed' }))
  })
  client.end(bytes)
  return { client, completion }
}

function controlClaims(overrides = undefined) {
  return {
    credentialKind: 'control',
    scopes: ['media-processing:control'],
    audience: CONTROL_AUDIENCE,
    principalId: '@astrale/gumi/control',
    ...overrides,
  }
}

function workerClaims(overrides = undefined) {
  return {
    credentialKind: 'worker',
    scopes: ['media-processing:worker'],
    audience: WORKER_AUDIENCE,
    workerId: 'worker/eu-west/1',
    allowedPipelineIds: ['transcription.v1'],
    ...overrides,
  }
}

function callbackClaims(lease, overrides = undefined) {
  return {
    credentialKind: 'provider-callback',
    scopes: ['media-processing:provider-callback'],
    audience: CALLBACK_AUDIENCE,
    providerId: 'fixture-provider',
    processingJobId: lease.processingJobId,
    attemptId: lease.attemptId,
    ...overrides,
  }
}

function bearer(token, extra = undefined) {
  return { authorization: `Bearer ${token}`, ...extra }
}

function immutableInput() {
  return {
    schemaVersion: 'gumi.media-processing.immutable-input.v1',
    manifestId: id(1),
    manifestDigest: hash('1'),
    objectHandle: 'gumi-media:object/fixture-1',
    contentDigest: hash('2'),
    contentType: 'audio/ogg; codecs=opus',
    byteLength: '4096',
    durationMs: '60000',
  }
}

function serviceContext(serviceOverride = undefined) {
  const storage = new InMemoryProcessingStorage()
  const clock = new ManualClock()
  const ids = new DeterministicIds({ next: 100 })
  const manifestReader = new InMemoryManifestReader([immutableInput()])
  const artifacts = new InMemoryArtifactStore()
  const core = new MediaProcessingService({ storage, clock, ids, manifestReader, artifacts })
  return { storage, clock, ids, manifestReader, artifacts, core, service: serviceOverride ?? core }
}

function recordingLogger(onRecord = undefined) {
  const records = []
  const record = (value) => {
    records.push(structuredClone(value))
    onRecord?.(value)
  }
  return { records, info: record, warn: record, error: record }
}

async function harness(t, { service = undefined, authorizer = undefined, logger = undefined, ...adapterOptions } = {}) {
  const context = serviceContext(service)
  const actualAuthorizer = authorizer ?? new InMemoryHttpAuthorizer()
    .register(CONTROL_TOKEN, controlClaims())
    .register(WORKER_TOKEN, workerClaims())
  const actualLogger = logger ?? recordingLogger()
  const adapter = createMediaProcessingHttpServer({
    service: service ?? context.core,
    authorizer: actualAuthorizer,
    logger: actualLogger,
    ...HTTP_OPTIONS,
    ...adapterOptions,
  })
  const address = await adapter.listen()
  t.after(() => adapter.close({ gracePeriodMs: 100 }))
  return { ...context, authorizer: actualAuthorizer, logger: actualLogger, adapter, origin: address.origin }
}

function createRequest(requestId = id(2)) {
  const value = fixture('success/create-job-request.json')
  value.requestId = requestId
  return value
}

function claimRequest(processingJobId, requestId = id(10), expectedGeneration = '0') {
  return {
    schemaVersion: 'gumi.media-processing.claim-attempt.v1',
    requestId,
    processingJobId,
    expectedGeneration,
  }
}

function failureCompletion(lease, callbackId = id(20)) {
  return {
    schemaVersion: 'gumi.media-processing.complete-attempt.v1',
    callbackId,
    processingJobId: lease.processingJobId,
    attemptId: lease.attemptId,
    generation: lease.generation,
    outcome: 'retryable-failure',
    failure: { code: 'PROVIDER_UNAVAILABLE', retryAfterSeconds: '1' },
  }
}

function transcriptBytes() {
  return Buffer.from(JSON.stringify({
    schemaVersion: 'gumi.media-processing.transcript-artifact.v1',
    language: 'en-US',
    mediaDurationMs: '60000',
    segments: [],
  }))
}

function successCompletion(lease, bytes, callbackId = id(30)) {
  return {
    schemaVersion: 'gumi.media-processing.complete-attempt.v1',
    callbackId,
    processingJobId: lease.processingJobId,
    attemptId: lease.attemptId,
    generation: lease.generation,
    outcome: 'succeeded',
    result: {
      artifact: {
        stagedArtifactHandle: 'stage/http-transcript-1',
        contentDigest: byteDigest(bytes),
        byteLength: String(bytes.length),
        contentType: 'application/vnd.gumi.transcript+json',
      },
      provenance: {
        providerId: 'fixture-provider',
        model: 'fixture-transcribe',
        modelVersion: '2026-07-19',
      },
      language: { tag: 'en-US', basis: 'requested' },
      timing: {
        providerStartedAt: '2026-07-19T20:00:01Z',
        providerCompletedAt: '2026-07-19T20:00:03Z',
        mediaDurationMs: '60000',
      },
      segments: { count: '0', timedCount: '0' },
    },
  }
}

async function postJson(origin, path, token, value, extraHeaders = undefined) {
  return request(origin, {
    method: 'POST',
    path,
    headers: bearer(token, { 'content-type': 'application/json', ...extraHeaders }),
    body: jsonBytes(value),
  })
}

function assertProblem(response, status, code) {
  assert.equal(response.status, status)
  assert.match(response.headers['content-type'], /^application\/problem\+json/)
  assert.deepEqual(Object.keys(response.json).sort(), [
    'code',
    ...(code === 'RATE_LIMITED' ? ['retryAfterSeconds'] : []),
    'status',
    'title',
    'traceId',
    'type',
  ].sort())
  assert.equal(response.json.status, status)
  assert.equal(response.json.code, code)
  assert.match(response.json.traceId, UUID_V7)
  assert.equal(response.headers['x-request-id'], response.json.traceId)
  assert.match(response.json.type, /^https:\/\/gumi\.astrale\.ai\/problems\/media-processing\/v1\//)
}

function stubService(overrides = undefined) {
  return Object.fromEntries([
    ...[
      'createJob',
      'getStatus',
      'retryJob',
      'cancelJob',
      'claimAttempt',
      'renewLease',
      'completeAttempt',
      'resolveResult',
      'readTranscriptPage',
    ]
      .map((name) => [name, async () => ({ schemaVersion: `stub.${name}.v1` })]),
    ...Object.entries(overrides ?? {}),
  ])
}

test('HTTP composition requires three explicit, distinct credential audiences and the complete service', () => {
  const service = stubService()
  const authorizer = { authenticate: async () => null }
  assert.throws(() => createMediaProcessingHttpServer({ service, authorizer }), /controlCredentialAudience/)
  assert.throws(
    () => createMediaProcessingHttpServer({
      service,
      authorizer,
      controlCredentialAudience: 'same',
      workerCredentialAudience: 'same',
      callbackCredentialAudience: 'different',
    }),
    /must be distinct/,
  )
  assert.throws(
    () => createMediaProcessingHttpServer({ service: {}, authorizer, ...HTTP_OPTIONS }),
    /service.createJob/,
  )
})

test('real HTTP routes all publisher operations without weakening job, attempt, or digest binding', async (t) => {
  const h = await harness(t)
  const firstCreate = await postJson(h.origin, '/v1/processing-jobs', CONTROL_TOKEN, createRequest(), {
    'x-correlation-id': CORRELATION_ID,
  })
  assert.equal(firstCreate.status, 201)
  assert.equal(firstCreate.json.disposition, 'created')
  assert.equal(firstCreate.headers['cache-control'], 'private, no-store')
  assert.equal(firstCreate.headers['x-correlation-id'], CORRELATION_ID)
  const firstJobId = firstCreate.json.job.processingJobId

  const replay = await postJson(h.origin, '/v1/processing-jobs', CONTROL_TOKEN, createRequest())
  assert.equal(replay.status, 200)
  assert.equal(replay.json.disposition, 'existing')
  assert.equal(replay.json.job.processingJobId, firstJobId)

  const status = await request(h.origin, {
    path: `/v1/processing-jobs/${firstJobId}`,
    headers: bearer(CONTROL_TOKEN),
  })
  assert.equal(status.status, 200)
  assert.equal(status.json.state, 'queued')

  const claimed = await postJson(
    h.origin,
    `/v1/processing-jobs/${firstJobId}/attempts:claim`,
    WORKER_TOKEN,
    claimRequest(firstJobId),
  )
  assert.equal(claimed.status, 200)
  assert.equal(claimed.headers['cache-control'], 'private, no-store')
  const firstLease = claimed.json

  const renewed = await postJson(
    h.origin,
    `/v1/processing-jobs/${firstJobId}/attempts/${firstLease.attemptId}:renew`,
    WORKER_TOKEN,
    {
      schemaVersion: 'gumi.media-processing.renew-lease.v1',
      requestId: id(11),
      processingJobId: firstJobId,
      attemptId: firstLease.attemptId,
      generation: firstLease.generation,
      leaseId: firstLease.leaseId,
      expectedLeaseRevision: '0',
    },
  )
  assert.equal(renewed.status, 200)
  assert.equal(renewed.json.leaseRevision, '1')

  h.authorizer.register(CALLBACK_TOKEN, callbackClaims(firstLease))
  const failed = await postJson(
    h.origin,
    `/v1/provider-callbacks/${firstLease.attemptId}:complete`,
    CALLBACK_TOKEN,
    failureCompletion(firstLease),
  )
  assert.equal(failed.status, 200)
  assert.equal(failed.json.jobState, 'retryable')
  h.clock.advanceSeconds(1)

  const retried = await postJson(h.origin, `/v1/processing-jobs/${firstJobId}:retry`, CONTROL_TOKEN, {
    schemaVersion: 'gumi.media-processing.retry-job.v1',
    requestId: id(21),
    processingJobId: firstJobId,
    expectedGeneration: '1',
    acknowledgeOutcomeUnknown: false,
  })
  assert.equal(retried.status, 200)
  assert.equal(retried.json.disposition, 'queued')

  const canceled = await postJson(h.origin, `/v1/processing-jobs/${firstJobId}:cancel`, CONTROL_TOKEN, {
    schemaVersion: 'gumi.media-processing.cancel-job.v1',
    requestId: id(22),
    processingJobId: firstJobId,
    expectedGeneration: '1',
  })
  assert.equal(canceled.status, 200)
  assert.equal(canceled.json.disposition, 'canceled')

  const secondCreate = await postJson(h.origin, '/v1/processing-jobs', CONTROL_TOKEN, createRequest(id(3)))
  assert.equal(secondCreate.status, 201)
  const secondJobId = secondCreate.json.job.processingJobId
  const secondClaim = await postJson(
    h.origin,
    `/v1/processing-jobs/${secondJobId}/attempts:claim`,
    WORKER_TOKEN,
    claimRequest(secondJobId, id(12)),
  )
  assert.equal(secondClaim.status, 200)
  const secondLease = secondClaim.json
  const bytes = transcriptBytes()
  h.artifacts.stage('stage/http-transcript-1', bytes)
  h.authorizer.register('second-callback-token-never-log-0007', callbackClaims(secondLease))
  const completed = await postJson(
    h.origin,
    `/v1/provider-callbacks/${secondLease.attemptId}:complete`,
    'second-callback-token-never-log-0007',
    successCompletion(secondLease, bytes),
  )
  assert.equal(completed.status, 200)
  assert.equal(completed.json.jobState, 'succeeded')

  const result = await request(h.origin, {
    path: `/v1/processing-results/${secondJobId}?expectedInputContentDigest=${hash('2')}&expectedOutputContentDigest=${byteDigest(bytes)}`,
    headers: bearer(CONTROL_TOKEN),
  })
  assert.equal(result.status, 200)
  assert.equal(result.headers['cache-control'], 'private, no-store')
  assert.equal(result.json.processingJobId, secondJobId)
  assert.equal(result.json.output.contentDigest, byteDigest(bytes))
  assert.equal(JSON.stringify(result.json).includes('segments'), true)
  assert.equal(JSON.stringify(result.json).includes('stage/http-transcript-1'), false)
  assert.equal(Object.hasOwn(result.json, 'text'), false)

  const transcriptPage = await request(h.origin, {
    path: `/v1/processing-results/${secondJobId}/transcript-pages/0?expectedInputContentDigest=${hash('2')}&expectedOutputContentDigest=${byteDigest(bytes)}`,
    headers: bearer(CONTROL_TOKEN),
  })
  assert.equal(transcriptPage.status, 200)
  assert.equal(transcriptPage.headers['cache-control'], 'private, no-store')
  assert.equal(transcriptPage.json.processingJobId, secondJobId)
  assert.equal(transcriptPage.json.startIndex, '0')
  assert.equal(transcriptPage.json.endIndexExclusive, '0')
  assert.deepEqual(transcriptPage.json.segments, [])

  const encodedAndReordered = await request(h.origin, {
    path: `/v1/processing-results/${secondJobId}?expectedOutputContentDigest=${byteDigest(bytes).replace(':', '%3A')}&expectedInputContentDigest=${hash('2').replace(':', '%3A')}`,
    headers: bearer(CONTROL_TOKEN),
  })
  assert.equal(encodedAndReordered.status, 200)
  assert.deepEqual(encodedAndReordered.json, result.json)
  assert.deepEqual(
    [...new Set(h.logger.records.map((record) => record.operationId))].sort(),
    [
      'cancelProcessingJob',
      'claimProcessingAttempt',
      'completeProcessingAttempt',
      'createProcessingJob',
      'getProcessingJob',
      'readTranscriptPage',
      'renewProcessingLease',
      'resolveProcessingResult',
      'retryProcessingJob',
    ],
  )
})

test('control, worker, and callback credentials are non-interchangeable and callback-bound', async (t) => {
  const h = await harness(t)
  const created = await postJson(h.origin, '/v1/processing-jobs', CONTROL_TOKEN, createRequest())
  const jobId = created.json.job.processingJobId

  const controlOnWorker = await postJson(
    h.origin,
    `/v1/processing-jobs/${jobId}/attempts:claim`,
    CONTROL_TOKEN,
    claimRequest(jobId),
  )
  assertProblem(controlOnWorker, 403, 'WORKER_SCOPE_MISMATCH')

  const workerOnControl = await request(h.origin, {
    path: `/v1/processing-jobs/${jobId}`,
    headers: bearer(WORKER_TOKEN),
  })
  assertProblem(workerOnControl, 403, 'CONTROL_SCOPE_MISMATCH')

  const missing = await request(h.origin, { path: `/v1/processing-jobs/${jobId}` })
  assertProblem(missing, 401, 'AUTHENTICATION_REQUIRED')
  assert.match(missing.headers['www-authenticate'], /gumi-media-processing-control/)

  const duplicated = await request(h.origin, {
    path: `/v1/processing-jobs/${jobId}`,
    headers: { authorization: [`Bearer ${CONTROL_TOKEN}`, `Bearer ${WORKER_TOKEN}`] },
  })
  assertProblem(duplicated, 401, 'AUTHENTICATION_REQUIRED')

  const claimed = await postJson(
    h.origin,
    `/v1/processing-jobs/${jobId}/attempts:claim`,
    WORKER_TOKEN,
    claimRequest(jobId),
  )
  const lease = claimed.json
  h.authorizer
    .register(CALLBACK_TOKEN, callbackClaims(lease))
    .register(FOREIGN_CALLBACK_TOKEN, callbackClaims(lease, { attemptId: id(99) }))
    .register('wrong-worker-audience-token-never-log', workerClaims({ audience: CONTROL_AUDIENCE }))

  const foreignCallback = await postJson(
    h.origin,
    `/v1/provider-callbacks/${lease.attemptId}:complete`,
    FOREIGN_CALLBACK_TOKEN,
    failureCompletion(lease),
  )
  assertProblem(foreignCallback, 403, 'CALLBACK_SCOPE_MISMATCH')

  const wrongBodyJob = failureCompletion(lease)
  wrongBodyJob.processingJobId = id(98)
  const wrongBinding = await postJson(
    h.origin,
    `/v1/provider-callbacks/${lease.attemptId}:complete`,
    CALLBACK_TOKEN,
    wrongBodyJob,
  )
  assertProblem(wrongBinding, 403, 'CALLBACK_SCOPE_MISMATCH')

  const wrongAudience = await postJson(
    h.origin,
    `/v1/processing-jobs/${jobId}/attempts:claim`,
    'wrong-worker-audience-token-never-log',
    claimRequest(jobId, id(13)),
  )
  assertProblem(wrongAudience, 403, 'WORKER_SCOPE_MISMATCH')
  assert.equal((await h.storage.inspectMetadata()).jobs[0].attempts.length, 1)
})

test('canonical routes, methods, media types, path bindings, and result queries fail before core mutation', async (t) => {
  let calls = 0
  const service = stubService(Object.fromEntries(
    ['createJob', 'retryJob', 'claimAttempt'].map((name) => [name, async () => {
      calls += 1
      return { schemaVersion: 'not-reached.v1' }
    }]),
  ))
  const h = await harness(t, { service })

  const wrongMethod = await request(h.origin, {
    method: 'PUT',
    path: '/v1/processing-jobs',
    headers: bearer(CONTROL_TOKEN),
  })
  assertProblem(wrongMethod, 405, 'INVALID_REQUEST')
  assert.equal(wrongMethod.headers.allow, 'POST')
  const attackerNamedMethod = await request(h.origin, {
    method: 'PATCH',
    path: '/v1/processing-jobs',
    headers: bearer(CONTROL_TOKEN),
  })
  assertProblem(attackerNamedMethod, 405, 'INVALID_REQUEST')

  const jobId = id(100)
  const getRetryAction = await request(h.origin, {
    path: `/v1/processing-jobs/${jobId}:retry`,
  })
  assertProblem(getRetryAction, 405, 'INVALID_REQUEST')
  assert.equal(getRetryAction.headers.allow, 'POST')
  const getCancelAction = await request(h.origin, {
    path: `/v1/processing-jobs/${jobId}:cancel`,
  })
  assertProblem(getCancelAction, 405, 'INVALID_REQUEST')
  assert.equal(getCancelAction.headers.allow, 'POST')
  const postGenericJob = await postJson(h.origin, `/v1/processing-jobs/${jobId}`, CONTROL_TOKEN, {})
  assertProblem(postGenericJob, 405, 'INVALID_REQUEST')
  assert.equal(postGenericJob.headers.allow, 'GET')
  const unknownAction = await postJson(h.origin, `/v1/processing-jobs/${jobId}:unknown`, CONTROL_TOKEN, {})
  assertProblem(unknownAction, 404, 'INVALID_REQUEST')

  const encoded = await postJson(h.origin, '/v1/processing-jobs%2f', CONTROL_TOKEN, createRequest())
  assertProblem(encoded, 400, 'INVALID_REQUEST')
  const extraQuery = await postJson(h.origin, '/v1/processing-jobs?secret=never-log', CONTROL_TOKEN, createRequest())
  assertProblem(extraQuery, 400, 'INVALID_REQUEST')
  const emptyQuery = await postJson(h.origin, '/v1/processing-jobs?', CONTROL_TOKEN, createRequest())
  assertProblem(emptyQuery, 400, 'INVALID_REQUEST')
  const malformedId = await request(h.origin, {
    path: '/v1/processing-jobs/not-a-job-id',
    headers: bearer(CONTROL_TOKEN),
  })
  assertProblem(malformedId, 400, 'INVALID_REQUEST')

  const wrongType = await request(h.origin, {
    method: 'POST',
    path: '/v1/processing-jobs',
    headers: bearer(CONTROL_TOKEN, { 'content-type': 'text/plain' }),
    body: jsonBytes(createRequest()),
  })
  assertProblem(wrongType, 400, 'INVALID_REQUEST')

  const mismatch = claimRequest(id(101))
  const wrongPathBinding = await postJson(
    h.origin,
    `/v1/processing-jobs/${jobId}/attempts:claim`,
    WORKER_TOKEN,
    mismatch,
  )
  assertProblem(wrongPathBinding, 400, 'INVALID_REQUEST')

  const duplicateQuery = await request(h.origin, {
    path: `/v1/processing-results/${jobId}?expectedInputContentDigest=${hash('2')}&expectedInputContentDigest=${hash('1')}`,
    headers: bearer(CONTROL_TOKEN),
  })
  assertProblem(duplicateQuery, 400, 'INVALID_REQUEST')
  const uppercaseDigestPrefix = await request(h.origin, {
    path: `/v1/processing-results/${jobId}?expectedInputContentDigest=SHA256%3A${'1'.repeat(64)}&expectedOutputContentDigest=${hash('2')}`,
    headers: bearer(CONTROL_TOKEN),
  })
  assertProblem(uppercaseDigestPrefix, 400, 'INVALID_REQUEST')
  const nonCanonicalPage = await request(h.origin, {
    path: `/v1/processing-results/${jobId}/transcript-pages/00?expectedInputContentDigest=${hash('1')}&expectedOutputContentDigest=${hash('2')}`,
    headers: bearer(CONTROL_TOKEN),
  })
  assertProblem(nonCanonicalPage, 400, 'INVALID_REQUEST')
  const pageBeyondContract = await request(h.origin, {
    path: `/v1/processing-results/${jobId}/transcript-pages/100001?expectedInputContentDigest=${hash('1')}&expectedOutputContentDigest=${hash('2')}`,
    headers: bearer(CONTROL_TOKEN),
  })
  assertProblem(pageBeyondContract, 400, 'INVALID_REQUEST')
  assert.equal(calls, 0)
  assert.equal(JSON.stringify(h.logger.records).includes('secret=never-log'), false)
  assert.equal(JSON.stringify(h.logger.records).includes('not-a-job-id'), false)
  assert.equal(h.logger.records.some((record) => record.method === 'OTHER'), true)
})

test('streamed request and serialized response bytes are bounded without partial core claims', async (t) => {
  let creates = 0
  const service = stubService({
    createJob: async () => {
      creates += 1
      return { disposition: 'created', value: 'x'.repeat(2_000) }
    },
    getStatus: async () => ({ value: 'x'.repeat(2_000) }),
  })
  const h = await harness(t, { service, maxJsonBodyBytes: 256, maxJsonResponseBytes: 1_024 })

  const oversizedDeclared = await request(h.origin, {
    method: 'POST',
    path: '/v1/processing-jobs',
    headers: bearer(CONTROL_TOKEN, { 'content-type': 'application/json' }),
    body: Buffer.alloc(300, 0x20),
  })
  assertProblem(oversizedDeclared, 413, 'INVALID_REQUEST')

  const oversizedStream = await request(h.origin, {
    method: 'POST',
    path: '/v1/processing-jobs',
    headers: bearer(CONTROL_TOKEN, { 'content-type': 'application/json' }),
    body: Buffer.alloc(300, 0x20),
    omitLength: true,
  })
  assertProblem(oversizedStream, 413, 'INVALID_REQUEST')
  assert.equal(creates, 0)

  const invalidUtf8 = await request(h.origin, {
    method: 'POST',
    path: '/v1/processing-jobs',
    headers: bearer(CONTROL_TOKEN, { 'content-type': 'application/json' }),
    body: Buffer.from([0xff]),
  })
  assertProblem(invalidUtf8, 400, 'INVALID_REQUEST')

  const oversizedResponse = await request(h.origin, {
    path: `/v1/processing-jobs/${id(100)}`,
    headers: bearer(CONTROL_TOKEN),
  })
  assertProblem(oversizedResponse, 503, 'DURABILITY_UNAVAILABLE')
  assert.ok(oversizedResponse.bytes.length < 1_024)
})

test('RFC 7807 problems, typed retry delay, IDs, and logs expose only the publisher allowlist', async (t) => {
  const logger = recordingLogger()
  const poisonedRateLimit = new RateLimitedError(17, new Error('redis password never visible'))
  poisonedRateLimit.headers = {
    'cache-control': 'public, max-age=31536000',
    connection: 'upgrade',
    'content-length': '999999',
    'content-type': 'text/html',
    'retry-after': '86400',
    'set-cookie': 'admin=true',
    'x-poisoned': 'true',
    'x-content-type-options': 'disabled',
    'x-correlation-id': id(91),
    'x-request-id': id(92),
  }
  const authorizer = new InMemoryHttpAuthorizer()
    .register(CONTROL_TOKEN, controlClaims())
    .reject(INVALID_TOKEN, new Error('upstream verifier leaked key=super-secret'))
    .fail(RATE_LIMITED_TOKEN, poisonedRateLimit)
    .fail(VERIFIER_FAILURE_TOKEN, new Error('verifier database password never visible'))
  const h = await harness(t, { authorizer, logger })

  const invalid = await request(h.origin, {
    path: `/v1/processing-jobs/${id(100)}`,
    headers: bearer(INVALID_TOKEN),
  })
  assertProblem(invalid, 401, 'AUTHENTICATION_REQUIRED')

  const limited = await request(h.origin, {
    path: `/v1/processing-jobs/${id(100)}`,
    headers: bearer(RATE_LIMITED_TOKEN, { 'x-correlation-id': CORRELATION_ID }),
  })
  assertProblem(limited, 429, 'RATE_LIMITED')
  assert.equal(limited.headers['retry-after'], '17')
  assert.equal(limited.json.retryAfterSeconds, '17')
  assert.equal(limited.headers['x-correlation-id'], CORRELATION_ID)
  assert.equal(limited.headers['cache-control'], 'no-store')
  assert.equal(limited.headers['x-content-type-options'], 'nosniff')
  assert.notEqual(limited.headers['x-request-id'], id(92))
  assert.match(limited.headers['x-request-id'], UUID_V7)
  assert.match(limited.headers['content-type'], /^application\/problem\+json/)
  assert.equal(Number(limited.headers['content-length']), limited.bytes.length)
  assert.equal(limited.headers['set-cookie'], undefined)
  assert.equal(limited.headers['x-poisoned'], undefined)
  assert.notEqual(limited.headers.connection, 'upgrade')

  poisonedRateLimit.code = 'AUTHENTICATION_REQUIRED'
  poisonedRateLimit.status = 401
  poisonedRateLimit.retryAfterSeconds = 0
  const malformedRateLimit = await request(h.origin, {
    path: `/v1/processing-jobs/${id(100)}`,
    headers: bearer(RATE_LIMITED_TOKEN),
  })
  assertProblem(malformedRateLimit, 503, 'DURABILITY_UNAVAILABLE')
  assert.equal(malformedRateLimit.headers['retry-after'], undefined)
  assert.equal(malformedRateLimit.json.retryAfterSeconds, undefined)

  const verifierFailure = await request(h.origin, {
    path: `/v1/processing-jobs/${id(100)}`,
    headers: bearer(VERIFIER_FAILURE_TOKEN),
  })
  assertProblem(verifierFailure, 503, 'DURABILITY_UNAVAILABLE')

  const malformedCorrelation = await request(h.origin, {
    path: `/v1/processing-jobs/${id(100)}`,
    headers: bearer(CONTROL_TOKEN, { 'x-correlation-id': 'not-a-uuid' }),
  })
  assertProblem(malformedCorrelation, 400, 'INVALID_REQUEST')

  const serialized = JSON.stringify({ responses: [invalid.json, limited.json, verifierFailure.json], logs: logger.records })
  for (const forbidden of [
    INVALID_TOKEN,
    RATE_LIMITED_TOKEN,
    VERIFIER_FAILURE_TOKEN,
    'super-secret',
    'redis password',
    'verifier database password',
    'authorization',
    'bearerToken',
  ]) {
    assert.equal(serialized.includes(forbidden), false, forbidden)
  }
  for (const record of logger.records) {
    assert.deepEqual(
      Object.keys(record).every((key) => [
        'event',
        'traceId',
        'method',
        'operationId',
        'status',
        'durationMs',
        'terminalPhase',
        'correlationId',
        'errorCode',
        'coreEffectStarted',
        'coreEffectSettled',
        'processingJobId',
        'attemptId',
      ].includes(key)),
      true,
    )
  }
})

test('disconnect during authorization cancels before any core method is entered', async (t) => {
  const entered = deferred()
  const canceled = deferred()
  const authorizer = {
    authenticate: async ({ signal }) => {
      entered.resolve()
      return new Promise((_resolve, reject) => {
        signal.addEventListener('abort', () => {
          canceled.resolve()
          reject(signal.reason)
        }, { once: true })
      })
    },
  }
  let coreCalls = 0
  const h = await harness(t, {
    authorizer,
    service: stubService({ createJob: async () => { coreCalls += 1 } }),
  })
  const pending = openRequest(h.origin, {
    path: '/v1/processing-jobs',
    headers: bearer(CONTROL_TOKEN, { 'content-type': 'application/json' }),
    body: jsonBytes(createRequest()),
  })
  await entered.promise
  pending.client.destroy()
  await canceled.promise
  await pending.completion
  assert.equal(coreCalls, 0)
})

test('disconnect after a mutating effect starts sends no false ACK and exact replay converges', async (t) => {
  const context = serviceContext()
  const committed = deferred()
  const release = deferred()
  const firstLogged = deferred()
  const disconnectedAtServer = deferred()
  let createCalls = 0
  const service = Object.create(context.core)
  service.createJob = async (...args) => {
    createCalls += 1
    const outcome = await context.core.createJob(...args)
    if (createCalls === 1) {
      committed.resolve(outcome)
      await release.promise
    }
    return outcome
  }
  const logger = recordingLogger((record) => {
    if (record.operationId === 'createProcessingJob' && record.status === 499) firstLogged.resolve(record)
  })
  const registeredAuthorizer = new InMemoryHttpAuthorizer().register(CONTROL_TOKEN, controlClaims())
  let firstAuthorization = true
  const authorizer = {
    authenticate: async (request) => {
      if (firstAuthorization) {
        firstAuthorization = false
        request.signal.addEventListener('abort', disconnectedAtServer.resolve, { once: true })
      }
      return registeredAuthorizer.authenticate(request)
    },
  }
  const adapter = createMediaProcessingHttpServer({ service, authorizer, logger, ...HTTP_OPTIONS })
  const address = await adapter.listen()
  t.after(() => adapter.close({ gracePeriodMs: 100 }))

  const body = jsonBytes(createRequest())
  const pending = openRequest(address.origin, {
    path: '/v1/processing-jobs',
    headers: bearer(CONTROL_TOKEN, { 'content-type': 'application/json' }),
    body,
  })
  const firstOutcome = await within(committed.promise, 'the first durable create effect')
  assert.equal(firstOutcome.disposition, 'created')
  pending.client.destroy()
  await within(disconnectedAtServer.promise, 'the server to observe client disconnect')
  release.resolve()
  const disconnected = await within(pending.completion, 'the disconnected client to settle')
  assert.notEqual(disconnected.kind, 'response')
  const record = await within(firstLogged.promise, 'the disconnected request log')
  assert.equal(record.coreEffectStarted, true)
  assert.equal(record.coreEffectSettled, true)
  assert.equal(record.terminalPhase, 'effect-settled-without-response')

  const replay = await postJson(address.origin, '/v1/processing-jobs', CONTROL_TOKEN, createRequest())
  assert.equal(replay.status, 200)
  assert.equal(replay.json.disposition, 'existing')
  assert.equal(replay.json.job.processingJobId, firstOutcome.job.processingJobId)
})

test('graceful close stops admission and aborts pre-effect work after the bounded grace period', async () => {
  const entered = deferred()
  const canceled = deferred()
  const authorizer = {
    authenticate: async ({ signal }) => {
      entered.resolve()
      return new Promise((_resolve, reject) => {
        signal.addEventListener('abort', () => {
          canceled.resolve()
          reject(signal.reason)
        }, { once: true })
      })
    },
  }
  let coreCalls = 0
  const adapter = createMediaProcessingHttpServer({
    service: stubService({ createJob: async () => { coreCalls += 1 } }),
    authorizer,
    ...HTTP_OPTIONS,
  })
  const address = await adapter.listen()
  const pending = openRequest(address.origin, {
    path: '/v1/processing-jobs',
    headers: bearer(CONTROL_TOKEN, { 'content-type': 'application/json' }),
    body: jsonBytes(createRequest()),
  })
  await entered.promise
  const result = await adapter.close({ gracePeriodMs: 0 })
  await canceled.promise
  await pending.completion
  assert.equal(result.abortedRequests, 1)
  assert.equal(result.drained, true)
  assert.equal(coreCalls, 0)
  await assert.rejects(adapter.listen(), /cannot listen again/)
})

test('graceful close reports an uncooperative pre-effect adapter as undrained', async () => {
  const entered = deferred()
  const release = deferred()
  const logged = deferred()
  let authenticationSignal
  const authorizer = {
    authenticate: async ({ signal }) => {
      authenticationSignal = signal
      entered.resolve()
      await release.promise
      return controlClaims()
    },
  }
  const logger = recordingLogger((record) => {
    if (record.operationId === 'createProcessingJob') logged.resolve(record)
  })
  let coreCalls = 0
  const adapter = createMediaProcessingHttpServer({
    service: stubService({ createJob: async () => { coreCalls += 1 } }),
    authorizer,
    logger,
    ...HTTP_OPTIONS,
  })
  const address = await adapter.listen()
  const pending = openRequest(address.origin, {
    path: '/v1/processing-jobs',
    headers: bearer(CONTROL_TOKEN, { 'content-type': 'application/json' }),
    body: jsonBytes(createRequest()),
  })
  await entered.promise

  const result = await within(adapter.close({ gracePeriodMs: 0 }), 'the uncooperative adapter shutdown')
  assert.equal(authenticationSignal.aborted, true)
  assert.deepEqual(result, { drained: false, abortedRequests: 1, activeRequests: 1 })
  assert.equal(coreCalls, 0)

  release.resolve()
  await pending.completion
  const record = await within(logged.promise, 'the uncooperative request to settle after release')
  assert.equal(record.status, 499)
  assert.equal(record.terminalPhase, 'request-cancelled-before-effect')
  assert.equal(coreCalls, 0)
})
