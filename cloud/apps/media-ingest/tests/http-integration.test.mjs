import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import http from 'node:http'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import { MediaIngestService } from '../src/core/ingest-service.mjs'
import { IngestError } from '../src/core/error.mjs'
import { CredentialAuthenticationError } from '../src/http/auth.mjs'
import { RateLimitedError } from '../src/http/errors.mjs'
import { createMediaIngestHttpServer } from '../src/http/server.mjs'
import { InMemoryHttpAuthorizer } from '../src/testing/in-memory-http-authorizer.mjs'
import { DeterministicTokenPort, InMemoryStorage, ManualClock } from '../src/testing/in-memory-ports.mjs'

const APP = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const FIXTURES = join(APP, 'fixtures', 'v1')
const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const CONTROL_TOKEN = 'control-fixture-token-never-for-the-data-plane-0001'
const FOREIGN_CONTROL_TOKEN = 'control-fixture-token-without-the-required-scope-0002'
const WRONG_SESSION_TOKEN = 'ingest-fixture-token-bound-to-another-session-0003'
const WRONG_STREAM_TOKEN = 'ingest-fixture-token-bound-to-another-stream-0004'
const EXPIRED_TOKEN = 'expired-ingest-fixture-token-never-log-this-0005'
const FOREIGN_PRINCIPAL_TOKEN = 'control-fixture-token-for-foreign-principal-0006'
const WRONG_AUDIENCE_TOKEN = 'ingest-fixture-token-for-wrong-audience-0007'
const DEVICE_REVOKED_TOKEN = 'ingest-fixture-token-for-revoked-device-0008'
const CAPTURE_REVOKED_TOKEN = 'ingest-fixture-token-for-revoked-capture-0009'
const CORRELATION_ID = '0190c6f0-7b21-7a40-8b11-000000000020'
const SESSION_ID = '0190c6f0-7b21-7a40-8b11-000000000002'
const EDGE_HOST_ID = '0190c6f0-7b21-7a40-8b11-000000000005'
const STREAM_ID = '0190c6f0-7b21-7a40-8b11-000000000006'
const MANIFEST_ID = '0190c6f0-7b21-7a40-8b11-000000000009'
const OBJECT_ID = '0190c6f0-7b21-7a40-8b11-00000000000a'
const REPLAY_CANDIDATE_ID = '0190c6f0-7b21-7a40-8b11-00000000000b'
const SECOND_CHUNK_ID = '0190c6f0-7b21-7a40-8b11-000000000013'
const FOREIGN_ID = '0190c6f0-7b21-7a40-8b11-000000000099'
const CONTROL_PRINCIPAL_ID = 'gumi.astrale.ai'
const CREDENTIAL_AUDIENCE = 'gumi.media-ingest'
const HTTP_AUTH_CONTRACT = {
  controlPrincipalId: CONTROL_PRINCIPAL_ID,
  credentialAudience: CREDENTIAL_AUDIENCE,
}

function fixture(path) {
  return JSON.parse(readFileSync(join(FIXTURES, path), 'utf8'))
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

function jsonBytes(value) {
  return Buffer.from(JSON.stringify(value), 'utf8')
}

function connectionOptions(origin, path) {
  const target = new URL(origin)
  return {
    protocol: target.protocol,
    hostname: target.hostname,
    port: target.port,
    path,
  }
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
        const responseBytes = Buffer.concat(chunks)
        const contentType = response.headers['content-type'] ?? ''
        let json
        if (contentType.includes('json') && responseBytes.length > 0) json = JSON.parse(responseBytes.toString('utf8'))
        resolvePromise({ status: response.statusCode, headers: response.headers, bytes: responseBytes, json })
      })
    })
    client.on('error', rejectPromise)
    if (bytes !== undefined) client.end(bytes)
    else client.end()
  })
}

function openRequest(origin, { method = 'GET', path = '/', headers = {}, body = undefined } = {}) {
  const bytes = body === undefined ? undefined : Buffer.isBuffer(body) ? body : Buffer.from(body)
  const outgoingHeaders = { ...headers }
  if (bytes !== undefined && outgoingHeaders['content-length'] === undefined) {
    outgoingHeaders['content-length'] = String(bytes.length)
  }
  const client = http.request({ ...connectionOptions(origin, path), method, headers: outgoingHeaders })
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

function controlHeaders(extra = undefined) {
  return { authorization: `Bearer ${CONTROL_TOKEN}`, ...extra }
}

function dataHeaders(token, extra = undefined) {
  return { authorization: `Bearer ${token}`, ...extra }
}

function controlClaims(overrides = undefined) {
  return {
    credentialKind: 'control',
    scopes: ['media-ingest:control'],
    principalId: CONTROL_PRINCIPAL_ID,
    audience: CREDENTIAL_AUDIENCE,
    ...overrides,
  }
}

function dataClaims(overrides = undefined) {
  return {
    credentialKind: 'ingest',
    scopes: ['media-ingest:data'],
    principalId: EDGE_HOST_ID,
    audience: CREDENTIAL_AUDIENCE,
    ingestSessionId: SESSION_ID,
    streamIds: [STREAM_ID],
    ...overrides,
  }
}

function contentDigestHeader(digest) {
  return `sha-256=:${Buffer.from(digest.slice('sha256:'.length), 'hex').toString('base64')}:`
}

function chunkHeaders(descriptor, extra = undefined) {
  const result = {
    'content-type': 'application/octet-stream',
    'gumi-sequence-first': descriptor.sequenceRange.first,
    'gumi-sequence-last': descriptor.sequenceRange.last,
    'gumi-payload-bytes': descriptor.payloadBytes,
    'gumi-payload-format': descriptor.payloadFormat,
    'content-digest': contentDigestHeader(descriptor.contentDigest),
    'gumi-codec-configuration-id': descriptor.codecConfigurationId,
    'gumi-source-started-at': descriptor.sourceStartedAt,
    'gumi-source-retransmission': String(descriptor.sourceRetransmission),
    ...extra,
  }
  if (descriptor.edgeReceivedAt !== undefined) result['gumi-edge-received-at'] = descriptor.edgeReceivedAt
  if (descriptor.sourceDiscontinuityBefore !== undefined) {
    result['gumi-discontinuity-reason'] = descriptor.sourceDiscontinuityBefore.reason
    result['gumi-dropped-frame-count'] = descriptor.sourceDiscontinuityBefore.droppedFrameCount
  }
  return result
}

function assemblyChunks() {
  const assembly = fixture('success/deterministic-assembly.json')
  const first = fixture('success/chunk-descriptor.json')
  const second = {
    ...structuredClone(first),
    chunkId: SECOND_CHUNK_ID,
    sequenceRange: assembly.chunks[1].sequenceRange,
    payloadBytes: assembly.chunks[1].payloadBytes,
    contentDigest: assembly.chunks[1].contentDigest,
    sourceStartedAt: '2026-07-19T20:00:02Z',
    edgeReceivedAt: '2026-07-19T20:00:02.125Z',
  }
  return [
    { descriptor: first, body: Buffer.from(assembly.chunks[0].bodyBase64, 'base64') },
    { descriptor: second, body: Buffer.from(assembly.chunks[1].bodyBase64, 'base64') },
  ]
}

function recordingLogger() {
  const records = []
  return {
    records,
    info: (record) => records.push(structuredClone(record)),
    warn: (record) => records.push(structuredClone(record)),
    error: (record) => records.push(structuredClone(record)),
  }
}

async function harness(t, adapterOptions = undefined) {
  const storage = new InMemoryStorage()
  const clock = new ManualClock('2026-07-19T20:00:00Z')
  const tokens = new DeterministicTokenPort({
    ids: [SESSION_ID, REPLAY_CANDIDATE_ID, MANIFEST_ID, OBJECT_ID],
    tokens: ['fixture-only-opaque-ingest-token-HTTP-000000000001'],
  })
  const service = new MediaIngestService({ storage, clock, tokens })
  const authorizer = new InMemoryHttpAuthorizer().register(CONTROL_TOKEN, controlClaims())
  const logger = recordingLogger()
  const adapter = createMediaIngestHttpServer({
    service,
    authorizer,
    logger,
    ...HTTP_AUTH_CONTRACT,
    ...adapterOptions,
  })
  const address = await adapter.listen()
  t.after(() => adapter.close({ gracePeriodMs: 100 }))
  return { storage, clock, tokens, service, authorizer, logger, adapter, origin: address.origin }
}

async function createSessionOverHttp(h, extraHeaders = undefined) {
  const body = jsonBytes(fixture('success/create-session-request.json'))
  return request(h.origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: controlHeaders({ 'content-type': 'application/json', ...extraHeaders }),
    body,
  })
}

function registerCreatedCredential(h, createResponse) {
  const token = createResponse.json.credential.accessToken
  const requestBody = fixture('success/create-session-request.json')
  h.authorizer.register(token, dataClaims({
    ingestSessionId: createResponse.json.ingestSessionId,
    streamIds: requestBody.streams.map((stream) => stream.streamId),
  }))
  return token
}

function assertProblem(response, { status, code }) {
  assert.equal(response.status, status)
  assert.match(response.headers['content-type'], /^application\/problem\+json/)
  assert.equal(response.json.status, status)
  assert.equal(response.json.code, code)
  assert.match(response.json.type, /^https:\/\/gumi\.astrale\.ai\/problems\/media-ingest\/v1\//)
  assert.match(response.json.traceId, UUID_V7)
  assert.equal(response.headers['x-request-id'], response.json.traceId)
}

test('HTTP composition requires an explicit control principal and credential audience', () => {
  const service = Object.fromEntries(
    ['createSession', 'refreshCredential', 'getStatus', 'putChunk', 'finalize', 'getImmutableManifestProjection'].map((name) => [
      name,
      async () => ({}),
    ]),
  )
  const authorizer = { authenticate: async () => null }
  assert.throws(
    () => createMediaIngestHttpServer({ service, authorizer, credentialAudience: CREDENTIAL_AUDIENCE }),
    /controlPrincipalId/,
  )
  assert.throws(
    () => createMediaIngestHttpServer({ service, authorizer, controlPrincipalId: CONTROL_PRINCIPAL_ID }),
    /credentialAudience/,
  )
})

test('real HTTP server routes the complete idempotent byte path and preserves exact binary assembly', async (t) => {
  const h = await harness(t)
  const created = await createSessionOverHttp(h, { 'x-correlation-id': CORRELATION_ID })
  assert.equal(created.status, 201)
  assert.equal(created.json.ingestSessionId, SESSION_ID)
  assert.equal(created.headers['cache-control'], 'private, no-store')
  assert.equal(created.headers['x-correlation-id'], CORRELATION_ID)
  assert.match(created.headers['x-request-id'], UUID_V7)
  const ingestToken = registerCreatedCredential(h, created)

  const replay = await createSessionOverHttp(h)
  assert.equal(replay.status, 200)
  assert.deepEqual(replay.json, created.json)

  const statusBefore = await request(h.origin, {
    path: `/v1/ingest-sessions/${SESSION_ID}/status`,
    headers: dataHeaders(ingestToken),
  })
  assert.equal(statusBefore.status, 200)
  assert.equal(statusBefore.json.stateRevision, '0')

  const chunks = assemblyChunks()
  h.clock.set('2026-07-19T20:00:02Z')
  const firstPath = `/v1/ingest-sessions/${SESSION_ID}/streams/${chunks[0].descriptor.streamId}/chunks/${chunks[0].descriptor.chunkId}`
  const duplicateResults = await Promise.all([
    request(h.origin, {
      method: 'PUT',
      path: firstPath,
      headers: dataHeaders(ingestToken, chunkHeaders(chunks[0].descriptor)),
      body: chunks[0].body,
    }),
    request(h.origin, {
      method: 'PUT',
      path: firstPath,
      headers: dataHeaders(ingestToken, chunkHeaders(chunks[0].descriptor)),
      body: chunks[0].body,
    }),
  ])
  assert.deepEqual(
    duplicateResults.map((response) => response.json.disposition).sort(),
    ['duplicate', 'stored'],
  )
  for (const response of duplicateResults) {
    assert.equal(response.status, 200)
    assert.equal(response.json.acknowledgedContentDigest, chunks[0].descriptor.contentDigest)
  }

  h.clock.set('2026-07-19T20:00:05Z')
  const second = await request(h.origin, {
    method: 'PUT',
    path: `/v1/ingest-sessions/${SESSION_ID}/streams/${chunks[1].descriptor.streamId}/chunks/${chunks[1].descriptor.chunkId}`,
    headers: dataHeaders(ingestToken, chunkHeaders(chunks[1].descriptor)),
    body: chunks[1].body,
  })
  assert.equal(second.status, 200)
  assert.equal(second.json.disposition, 'stored')

  h.clock.set('2026-07-19T20:00:10Z')
  const finalizationBody = jsonBytes(fixture('success/finalize-request.json'))
  const finalized = await request(h.origin, {
    method: 'POST',
    path: `/v1/ingest-sessions/${SESSION_ID}/finalize`,
    headers: dataHeaders(ingestToken, { 'content-type': 'application/json' }),
    body: finalizationBody,
  })
  assert.equal(finalized.status, 200)
  assert.equal(finalized.json.disposition, 'finalized')
  assert.equal(finalized.json.manifest.manifestId, MANIFEST_ID)

  const finalizeReplay = await request(h.origin, {
    method: 'POST',
    path: `/v1/ingest-sessions/${SESSION_ID}/finalize`,
    headers: dataHeaders(ingestToken, { 'content-type': 'application/json' }),
    body: finalizationBody,
  })
  assert.equal(finalizeReplay.status, 200)
  assert.equal(finalizeReplay.json.disposition, 'already-finalized')
  assert.equal(finalizeReplay.json.manifestDigest, finalized.json.manifestDigest)

  const projection = await request(h.origin, {
    path: `/v1/manifests/${MANIFEST_ID}`,
    headers: controlHeaders({ 'gumi-expected-manifest-digest': finalized.json.manifestDigest }),
  })
  assert.equal(projection.status, 200)
  assert.deepEqual(projection.json, fixture('success/immutable-manifest-projection.json'))
  assert.equal(projection.json.objectContentDigest, finalized.json.manifest.streams[0].object.contentDigest)
  assert.equal(projection.json.objectByteLength, finalized.json.manifest.streams[0].object.byteLength)
  assert.equal(projection.json.objectContentType, finalized.json.manifest.streams[0].object.contentType)
  assert.equal(projection.headers['cache-control'], 'private, no-store')

  const object = await h.storage.readObject(OBJECT_ID)
  const expectedBytes = Buffer.concat(chunks.map((chunk) => chunk.body))
  assert.ok(object.bytes.equals(expectedBytes))
  assert.equal(createHash('sha256').update(object.bytes).digest('hex'), finalized.json.manifest.streams[0].object.contentDigest.slice(7))

  const serializedLogs = JSON.stringify(h.logger.records)
  assert.ok(h.logger.records.length >= 9)
  assert.doesNotMatch(serializedLogs, new RegExp(CONTROL_TOKEN))
  assert.doesNotMatch(serializedLogs, new RegExp(ingestToken))
  assert.doesNotMatch(serializedLogs, new RegExp(chunks[0].body.toString('base64')))
  assert.ok(h.logger.records.every((record) => Object.keys(record).every((key) => !/token|credential|body|payload/i.test(key))))
})

test('control and data credentials are mutually non-interchangeable and session/stream bound', async (t) => {
  const h = await harness(t)
  const missing = await request(h.origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: { 'content-type': 'application/json' },
    body: jsonBytes(fixture('success/create-session-request.json')),
  })
  assertProblem(missing, { status: 401, code: 'AUTHENTICATION_REQUIRED' })
  assert.match(missing.headers['www-authenticate'], /^Bearer realm="gumi-media-ingest-control"/)

  h.authorizer.register(FOREIGN_CONTROL_TOKEN, controlClaims({ scopes: ['unrelated:control'] }))
  const wrongControlScope = await request(h.origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: {
      authorization: `Bearer ${FOREIGN_CONTROL_TOKEN}`,
      'content-type': 'application/json',
    },
    body: jsonBytes(fixture('success/create-session-request.json')),
  })
  assertProblem(wrongControlScope, { status: 403, code: 'CONTROL_SCOPE_MISMATCH' })

  h.authorizer.register(FOREIGN_PRINCIPAL_TOKEN, controlClaims({ principalId: 'foreign.astrale.application' }))
  const wrongControlPrincipal = await request(h.origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: {
      authorization: `Bearer ${FOREIGN_PRINCIPAL_TOKEN}`,
      'content-type': 'application/json',
    },
    body: jsonBytes(fixture('success/create-session-request.json')),
  })
  assertProblem(wrongControlPrincipal, { status: 403, code: 'CONTROL_SCOPE_MISMATCH' })

  h.authorizer.register(WRONG_AUDIENCE_TOKEN, controlClaims({ audience: 'another.cloud.application' }))
  const wrongControlAudience = await request(h.origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: {
      authorization: `Bearer ${WRONG_AUDIENCE_TOKEN}`,
      'content-type': 'application/json',
    },
    body: jsonBytes(fixture('success/create-session-request.json')),
  })
  assertProblem(wrongControlAudience, { status: 403, code: 'CONTROL_SCOPE_MISMATCH' })

  const duplicateAuthorization = await request(h.origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: {
      authorization: [`Bearer ${CONTROL_TOKEN}`, `Bearer ${FOREIGN_CONTROL_TOKEN}`],
      'content-type': 'application/json',
    },
    body: jsonBytes(fixture('success/create-session-request.json')),
  })
  assertProblem(duplicateAuthorization, { status: 401, code: 'AUTHENTICATION_REQUIRED' })

  const created = await createSessionOverHttp(h)
  const ingestToken = registerCreatedCredential(h, created)
  const statusPath = `/v1/ingest-sessions/${SESSION_ID}/status`

  const dataOnControl = await request(h.origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: dataHeaders(ingestToken, { 'content-type': 'application/json' }),
    body: jsonBytes(fixture('success/create-session-request.json')),
  })
  assertProblem(dataOnControl, { status: 403, code: 'CONTROL_SCOPE_MISMATCH' })

  const controlOnData = await request(h.origin, { path: statusPath, headers: controlHeaders() })
  assertProblem(controlOnData, { status: 403, code: 'SESSION_SCOPE_MISMATCH' })

  h.authorizer.register(WRONG_SESSION_TOKEN, dataClaims({
    ingestSessionId: FOREIGN_ID,
  }))
  const wrongSession = await request(h.origin, { path: statusPath, headers: dataHeaders(WRONG_SESSION_TOKEN) })
  assertProblem(wrongSession, { status: 403, code: 'SESSION_SCOPE_MISMATCH' })

  h.authorizer.register(WRONG_AUDIENCE_TOKEN, dataClaims({ audience: 'another.cloud.application' }))
  const wrongAudience = await request(h.origin, { path: statusPath, headers: dataHeaders(WRONG_AUDIENCE_TOKEN) })
  assertProblem(wrongAudience, { status: 403, code: 'SESSION_SCOPE_MISMATCH' })

  h.authorizer.register(WRONG_STREAM_TOKEN, dataClaims({
    streamIds: [FOREIGN_ID],
  }))
  const chunk = assemblyChunks()[0]
  const wrongStream = await request(h.origin, {
    method: 'PUT',
    path: `/v1/ingest-sessions/${SESSION_ID}/streams/${chunk.descriptor.streamId}/chunks/${chunk.descriptor.chunkId}`,
    headers: dataHeaders(WRONG_STREAM_TOKEN, chunkHeaders(chunk.descriptor)),
    body: chunk.body,
  })
  assertProblem(wrongStream, { status: 403, code: 'SESSION_SCOPE_MISMATCH' })

  const invalid = await request(h.origin, {
    path: statusPath,
    headers: dataHeaders('valid-shape-but-unknown-ingest-token-000006'),
  })
  assertProblem(invalid, { status: 401, code: 'INVALID_INGEST_CREDENTIAL' })
  assert.match(invalid.headers['www-authenticate'], /^Bearer realm="gumi-media-ingest-data"/)

  h.authorizer.reject(EXPIRED_TOKEN, 'INGEST_CREDENTIAL_EXPIRED')
  const expired = await request(h.origin, { path: statusPath, headers: dataHeaders(EXPIRED_TOKEN) })
  assertProblem(expired, { status: 401, code: 'INGEST_CREDENTIAL_EXPIRED' })

  h.authorizer.deny(DEVICE_REVOKED_TOKEN, 'DEVICE_REVOKED')
  const revokedDevice = await request(h.origin, { path: statusPath, headers: dataHeaders(DEVICE_REVOKED_TOKEN) })
  assertProblem(revokedDevice, { status: 403, code: 'DEVICE_REVOKED' })
  assert.equal(revokedDevice.json.detail, 'The device binding has been revoked.')

  h.authorizer.deny(CAPTURE_REVOKED_TOKEN, 'CAPTURE_REVOKED')
  const revokedCapture = await request(h.origin, { path: statusPath, headers: dataHeaders(CAPTURE_REVOKED_TOKEN) })
  assertProblem(revokedCapture, { status: 403, code: 'CAPTURE_REVOKED' })
  assert.equal(revokedCapture.json.detail, 'The capture binding has been revoked.')

  const serializedLogs = JSON.stringify(h.logger.records)
  for (const secret of [
    CONTROL_TOKEN,
    FOREIGN_CONTROL_TOKEN,
    FOREIGN_PRINCIPAL_TOKEN,
    ingestToken,
    WRONG_SESSION_TOKEN,
    WRONG_STREAM_TOKEN,
    WRONG_AUDIENCE_TOKEN,
    EXPIRED_TOKEN,
    DEVICE_REVOKED_TOKEN,
    CAPTURE_REVOKED_TOKEN,
  ]) {
    assert.ok(!serializedLogs.includes(secret))
  }
})

test('HTTP body boundary rejects malformed metadata, absent lengths, and oversized streams before core commit', async (t) => {
  const h = await harness(t, { maxChunkBodyBytes: 167, maxJsonBodyBytes: 512 })
  const created = await createSessionOverHttp(h)
  assertProblem(created, { status: 413, code: 'REQUEST_BODY_TOO_LARGE' })

  const streamedOversize = await request(h.origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: controlHeaders({
      'content-type': 'application/json',
      'transfer-encoding': 'chunked',
    }),
    body: jsonBytes(fixture('success/create-session-request.json')),
    omitLength: true,
  })
  assertProblem(streamedOversize, { status: 413, code: 'REQUEST_BODY_TOO_LARGE' })

  const createBody = jsonBytes(fixture('success/create-session-request.json'))
  const wrongMediaType = await request(h.origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: controlHeaders({ 'content-type': 'text/plain' }),
    body: createBody,
  })
  assertProblem(wrongMediaType, { status: 400, code: 'INVALID_REQUEST' })

  const compressed = await request(h.origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: controlHeaders({
      'content-type': 'application/json',
      'content-encoding': 'gzip',
    }),
    body: createBody,
  })
  assertProblem(compressed, { status: 400, code: 'INVALID_REQUEST' })

  const expectContinue = await request(h.origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: controlHeaders({
      'content-type': 'application/json',
      expect: '100-continue',
    }),
    body: createBody,
  })
  assertProblem(expectContinue, { status: 400, code: 'INVALID_REQUEST' })

  const chunkHarness = await harness(t, { maxChunkBodyBytes: 167, maxJsonBodyBytes: 4096 })
  const session = await createSessionOverHttp(chunkHarness)
  const ingestToken = registerCreatedCredential(chunkHarness, session)
  const chunk = assemblyChunks()[0]
  const path = `/v1/ingest-sessions/${SESSION_ID}/streams/${chunk.descriptor.streamId}/chunks/${chunk.descriptor.chunkId}`

  const tooLarge = await request(chunkHarness.origin, {
    method: 'PUT',
    path,
    headers: dataHeaders(ingestToken, chunkHeaders(chunk.descriptor)),
    body: chunk.body,
  })
  assertProblem(tooLarge, { status: 413, code: 'CHUNK_TOO_LARGE' })

  const mismatchedLength = await request(chunkHarness.origin, {
    method: 'PUT',
    path,
    headers: dataHeaders(ingestToken, chunkHeaders(chunk.descriptor, { 'content-length': '167' })),
    body: chunk.body.subarray(0, 167),
  })
  assertProblem(mismatchedLength, { status: 422, code: 'CONTENT_LENGTH_MISMATCH' })

  const noLength = await request(chunkHarness.origin, {
    method: 'PUT',
    path,
    headers: dataHeaders(ingestToken, chunkHeaders(chunk.descriptor, { 'transfer-encoding': 'chunked' })),
    body: chunk.body,
    omitLength: true,
  })
  assertProblem(noLength, { status: 400, code: 'INVALID_REQUEST' })

  const malformedDigestHeaders = chunkHeaders(chunk.descriptor)
  malformedDigestHeaders['content-digest'] = `${malformedDigestHeaders['content-digest']}, sha-512=:AAAA:`
  const malformedDigest = await request(chunkHarness.origin, {
    method: 'PUT',
    path,
    headers: dataHeaders(ingestToken, malformedDigestHeaders),
    body: chunk.body,
  })
  assertProblem(malformedDigest, { status: 400, code: 'INVALID_REQUEST' })

  const metadata = await chunkHarness.storage.inspectMetadata()
  assert.equal(metadata.chunkBodyCount, 0)
  assert.equal(metadata.sessions[0].revision, 0n)
})

test('typed rate limiting emits one validated Retry-After contract', async (t) => {
  assert.throws(() => new RateLimitedError(-1), /0\.\.86400/)
  assert.throws(() => new RateLimitedError(86_401), /0\.\.86400/)

  const h = await harness(t)
  const created = await createSessionOverHttp(h)
  const ingestToken = registerCreatedCredential(h, created)
  h.service.getStatus = async () => {
    throw new RateLimitedError(7)
  }

  const response = await request(h.origin, {
    path: `/v1/ingest-sessions/${SESSION_ID}/status`,
    headers: dataHeaders(ingestToken),
  })
  assertProblem(response, { status: 429, code: 'RATE_LIMITED' })
  assert.equal(response.headers['retry-after'], '7')
  assert.equal(response.json.retryAfterSeconds, 7)
  assert.equal(response.json.details, undefined)

  h.service.getStatus = async () => {
    throw new IngestError('RATE_LIMITED', 429, 'An untyped rate limit must not cross the boundary.', {
      retryAfterSeconds: 7,
    })
  }
  const malformed = await request(h.origin, {
    path: `/v1/ingest-sessions/${SESSION_ID}/status`,
    headers: dataHeaders(ingestToken),
  })
  assertProblem(malformed, { status: 503, code: 'DURABILITY_UNAVAILABLE' })
  assert.equal(malformed.headers['retry-after'], undefined)
  assert.equal(malformed.json.retryAfterSeconds, undefined)
})

test('authentication verifier messages and causes never cross the HTTP boundary', async (t) => {
  const secret = 'verifier-key-id-and-token-must-not-cross-boundary'
  const failure = new CredentialAuthenticationError('INVALID_INGEST_CREDENTIAL', new Error(secret))
  failure.message = secret
  const service = Object.fromEntries(
    ['createSession', 'refreshCredential', 'getStatus', 'putChunk', 'finalize', 'getImmutableManifestProjection'].map((name) => [
      name,
      async () => assert.fail('core must not be reached'),
    ]),
  )
  const logger = recordingLogger()
  const adapter = createMediaIngestHttpServer({
    service,
    authorizer: { authenticate: async () => { throw failure } },
    logger,
    ...HTTP_AUTH_CONTRACT,
  })
  const { origin } = await adapter.listen()
  t.after(() => adapter.close({ gracePeriodMs: 20 }))

  const response = await request(origin, {
    path: `/v1/ingest-sessions/${SESSION_ID}/status`,
    headers: dataHeaders('opaque-authentication-failure-probe-token'),
  })
  assertProblem(response, { status: 401, code: 'INVALID_INGEST_CREDENTIAL' })
  assert.equal(response.json.detail, 'The ingest credential could not be authenticated.')
  assert.ok(!JSON.stringify({ response: response.json, logs: logger.records }).includes(secret))
})

test('problems, request IDs, correlation IDs, unknown routes, and methods stay deterministic', async (t) => {
  const h = await harness(t)
  const badCorrelation = await request(h.origin, {
    path: '/v1/not-a-route',
    headers: { 'x-correlation-id': 'attacker-controlled-log-line\tsecret' },
  })
  assertProblem(badCorrelation, { status: 400, code: 'INVALID_REQUEST' })
  assert.equal(badCorrelation.headers['x-correlation-id'], undefined)

  const unknown = await request(h.origin, { path: '/v1/not-a-route', headers: { 'x-correlation-id': CORRELATION_ID } })
  assertProblem(unknown, { status: 404, code: 'INVALID_REQUEST' })
  assert.equal(unknown.headers['x-correlation-id'], CORRELATION_ID)

  const wrongMethod = await request(h.origin, { method: 'DELETE', path: '/v1/ingest-sessions' })
  assertProblem(wrongMethod, { status: 405, code: 'INVALID_REQUEST' })
  assert.equal(wrongMethod.headers.allow, 'POST')

  const query = await request(h.origin, { path: '/v1/ingest-sessions?token=must-not-log' })
  assertProblem(query, { status: 400, code: 'INVALID_REQUEST' })
  assert.ok(!JSON.stringify(h.logger.records).includes('must-not-log'))

  for (const ambiguousTarget of [
    '//attacker.invalid/v1/ingest-sessions',
    '/v1/ignored/%2e%2e/ingest-sessions',
    '/v1/ignored/../ingest-sessions',
    '/v1\\ignored\\..\\ingest-sessions',
  ]) {
    const response = await request(h.origin, { path: ambiguousTarget })
    assertProblem(response, { status: 400, code: 'INVALID_REQUEST' })
  }

  const invalidPathSecret = 'invalid-path-value-must-not-enter-logs'
  const invalidPath = await request(h.origin, {
    path: `/v1/ingest-sessions/${invalidPathSecret}/status`,
  })
  assertProblem(invalidPath, { status: 400, code: 'INVALID_REQUEST' })
  assert.ok(!JSON.stringify(h.logger.records).includes(invalidPathSecret))
})

test('client disconnect cancels authorization before any core method is entered', async (t) => {
  const started = deferred()
  const aborted = deferred()
  let coreCalls = 0
  const service = Object.fromEntries(
    ['createSession', 'refreshCredential', 'getStatus', 'putChunk', 'finalize', 'getImmutableManifestProjection'].map((name) => [
      name,
      async () => {
        coreCalls += 1
        throw new Error('core must not be reached')
      },
    ]),
  )
  const authorizer = {
    async authenticate({ signal }) {
      started.resolve()
      return new Promise((resolvePromise, rejectPromise) => {
        signal.addEventListener(
          'abort',
          () => {
            aborted.resolve()
            rejectPromise(signal.reason)
          },
          { once: true },
        )
      })
    },
  }
  const logger = recordingLogger()
  const adapter = createMediaIngestHttpServer({ service, authorizer, logger, ...HTTP_AUTH_CONTRACT })
  const { origin } = await adapter.listen()
  t.after(() => adapter.close({ gracePeriodMs: 20 }))

  const pending = openRequest(origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: controlHeaders({ 'content-type': 'application/json', 'content-length': '1' }),
  })
  await started.promise
  pending.client.destroy()
  await aborted.promise
  await pending.completion
  await new Promise((resolvePromise) => setImmediate(resolvePromise))
  assert.equal(coreCalls, 0)
  assert.equal(logger.records.at(-1).status, 499)
})

test('disconnect after core dispatch does not falsely cancel already-started core work', async (t) => {
  const entered = deferred()
  const release = deferred()
  let completed = false
  const service = {
    createSession: async () => assert.fail('unexpected core call'),
    refreshCredential: async () => assert.fail('unexpected core call'),
    putChunk: async () => assert.fail('unexpected core call'),
    finalize: async () => assert.fail('unexpected core call'),
    getImmutableManifestProjection: async () => assert.fail('unexpected core call'),
    async getStatus() {
      entered.resolve()
      await release.promise
      completed = true
      return { state: 'open' }
    },
  }
  const authorizer = new InMemoryHttpAuthorizer().register(WRONG_SESSION_TOKEN, dataClaims())
  const logger = recordingLogger()
  const adapter = createMediaIngestHttpServer({ service, authorizer, logger, ...HTTP_AUTH_CONTRACT })
  const { origin } = await adapter.listen()
  t.after(() => adapter.close({ gracePeriodMs: 20 }))

  const pending = openRequest(origin, {
    path: `/v1/ingest-sessions/${SESSION_ID}/status`,
    headers: dataHeaders(WRONG_SESSION_TOKEN),
  })
  await entered.promise
  pending.client.destroy()
  await pending.completion
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 20))
  release.resolve()
  await new Promise((resolvePromise) => setImmediate(resolvePromise))
  assert.equal(completed, true)
  assert.equal(logger.records.at(-1).status, 499)
  assert.equal(logger.records.at(-1).terminalPhase, 'core-effect')
})

test('disconnect during a mutating chunk effect commits without a false ACK and converges by identical replay', async (t) => {
  const h = await harness(t)
  const created = await createSessionOverHttp(h)
  const ingestToken = registerCreatedCredential(h, created)
  const chunk = assemblyChunks()[0]
  const path = `/v1/ingest-sessions/${SESSION_ID}/streams/${chunk.descriptor.streamId}/chunks/${chunk.descriptor.chunkId}`
  const entered = deferred()
  const release = deferred()
  const committed = deferred()
  const disconnected = deferred()
  const putChunk = h.service.putChunk.bind(h.service)
  h.service.putChunk = async (...arguments_) => {
    entered.resolve()
    await release.promise
    const result = await putChunk(...arguments_)
    committed.resolve(result)
    return result
  }
  h.adapter.nodeServer.once('request', (incoming) => {
    incoming.socket.once('close', () => disconnected.resolve())
  })

  const pending = openRequest(h.origin, {
    method: 'PUT',
    path,
    headers: dataHeaders(ingestToken, chunkHeaders(chunk.descriptor)),
    body: chunk.body,
  })
  await entered.promise
  pending.client.destroy()
  await pending.completion
  await disconnected.promise
  release.resolve()
  const firstOutcome = await committed.promise
  assert.equal(firstOutcome.disposition, 'stored')
  await new Promise((resolvePromise) => setImmediate(resolvePromise))
  assert.equal(h.logger.records.at(-1).status, 499)
  assert.equal(h.logger.records.at(-1).terminalPhase, 'core-effect')

  const replay = await request(h.origin, {
    method: 'PUT',
    path,
    headers: dataHeaders(ingestToken, chunkHeaders(chunk.descriptor)),
    body: chunk.body,
  })
  assert.equal(replay.status, 200)
  assert.equal(replay.json.disposition, 'duplicate')
  assert.equal(replay.json.acknowledgedContentDigest, chunk.descriptor.contentDigest)

  const metadata = await h.storage.inspectMetadata()
  assert.equal(metadata.chunkBodyCount, 1)
  assert.equal(metadata.sessions[0].revision, 1n)
})

test('graceful close stops admission and aborts pre-effect work after its bounded grace period', async () => {
  const started = deferred()
  const aborted = deferred()
  const service = Object.fromEntries(
    ['createSession', 'refreshCredential', 'getStatus', 'putChunk', 'finalize', 'getImmutableManifestProjection'].map((name) => [
      name,
      async () => assert.fail('core must not be reached'),
    ]),
  )
  const authorizer = {
    async authenticate({ signal }) {
      started.resolve()
      return new Promise((resolvePromise, rejectPromise) => {
        signal.addEventListener(
          'abort',
          () => {
            aborted.resolve()
            rejectPromise(new CredentialAuthenticationError('INVALID_CONTROL_CREDENTIAL'))
          },
          { once: true },
        )
      })
    },
  }
  const adapter = createMediaIngestHttpServer({ service, authorizer, ...HTTP_AUTH_CONTRACT })
  const { origin } = await adapter.listen()
  const pending = openRequest(origin, {
    method: 'POST',
    path: '/v1/ingest-sessions',
    headers: controlHeaders({ 'content-type': 'application/json', 'content-length': '1' }),
  })
  await started.promise
  const closed = await adapter.close({ gracePeriodMs: 5 })
  await aborted.promise
  await pending.completion
  assert.equal(closed.abortedRequests, 1)
  assert.equal(closed.drained, true)
  assert.equal(closed.activeRequests, 0)
})
