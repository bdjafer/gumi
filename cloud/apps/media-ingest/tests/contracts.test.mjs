import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const APP = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const API = join(APP, 'api', 'v1')
const FIXTURES = join(APP, 'fixtures', 'v1')
const U64_MAX = 18_446_744_073_709_551_615n
const U64_EXCLUSIVE_BOUNDARY_MAX = 18_446_744_073_709_551_616n
const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const SHA256 = /^sha256:[0-9a-f]{64}$/
const OGG_CRC_TABLE = Array.from({ length: 256 }, (_, index) => {
  let value = (index << 24) >>> 0
  for (let bit = 0; bit < 8; bit += 1) {
    value = ((value & 0x80000000) !== 0 ? (value << 1) ^ 0x04c11db7 : value << 1) >>> 0
  }
  return value
})

const jsonCache = new Map()

function readJson(path) {
  const absolute = resolve(path)
  if (!jsonCache.has(absolute)) {
    jsonCache.set(absolute, JSON.parse(readFileSync(absolute, 'utf8')))
  }
  return jsonCache.get(absolute)
}

function jsonFiles(root) {
  const result = []
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const path = join(root, entry.name)
    if (entry.isDirectory()) result.push(...jsonFiles(path))
    else if (entry.name.endsWith('.json')) result.push(path)
  }
  return result.sort()
}

function pointer(document, fragment, context) {
  if (!fragment || fragment === '#') return document
  assert.match(fragment, /^#\//, `${context}: unsupported JSON pointer ${fragment}`)
  return fragment
    .slice(2)
    .split('/')
    .map((part) => part.replaceAll('~1', '/').replaceAll('~0', '~'))
    .reduce((value, key) => {
      assert.ok(value && Object.hasOwn(value, key), `${context}: missing JSON pointer key ${key}`)
      return value[key]
    }, document)
}

function resolveRef(ref, fromFile) {
  const hash = ref.indexOf('#')
  const filePart = hash === -1 ? ref : ref.slice(0, hash)
  const fragment = hash === -1 ? '' : ref.slice(hash)
  const targetFile = filePart ? resolve(dirname(fromFile), filePart) : resolve(fromFile)
  const document = readJson(targetFile)
  return { schema: pointer(document, fragment, `${relative(APP, fromFile)} -> ${ref}`), file: targetFile }
}

function typeMatches(value, type) {
  if (type === 'null') return value === null
  if (type === 'array') return Array.isArray(value)
  if (type === 'object') return value !== null && typeof value === 'object' && !Array.isArray(value)
  if (type === 'integer') return Number.isInteger(value)
  return typeof value === type
}

function validationError(path, message) {
  return new Error(`${path}: ${message}`)
}

function validate(value, schema, schemaFile, path = '$') {
  if (schema.$ref) {
    const target = resolveRef(schema.$ref, schemaFile)
    validate(value, target.schema, target.file, path)
  }

  if (schema.oneOf) {
    const successes = schema.oneOf.filter((candidate) => {
      try {
        validate(value, candidate, schemaFile, path)
        return true
      } catch {
        return false
      }
    })
    if (successes.length !== 1) throw validationError(path, `expected exactly one oneOf match, got ${successes.length}`)
  }

  if (Object.hasOwn(schema, 'const')) assert.deepEqual(value, schema.const, `${path}: const mismatch`)
  if (schema.enum) assert.ok(schema.enum.some((item) => Object.is(item, value)), `${path}: not in enum`)

  if (schema.type) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type]
    if (!types.some((type) => typeMatches(value, type))) {
      throw validationError(path, `expected ${types.join('|')}, got ${value === null ? 'null' : typeof value}`)
    }
  }

  if (typeof value === 'string') {
    if (schema.minLength !== undefined) assert.ok(value.length >= schema.minLength, `${path}: too short`)
    if (schema.maxLength !== undefined) assert.ok(value.length <= schema.maxLength, `${path}: too long`)
    if (schema.pattern) assert.match(value, new RegExp(schema.pattern), `${path}: pattern mismatch`)
    if (schema['x-gumi-maximum'] !== undefined) {
      assert.ok(BigInt(value) <= BigInt(schema['x-gumi-maximum']), `${path}: exceeds declared maximum`)
    }
    if (schema.format === 'date-time') {
      assert.match(value, /(Z|[+-][0-9]{2}:[0-9]{2})$/, `${path}: timestamp has no explicit offset`)
      assert.ok(Number.isFinite(Date.parse(value)), `${path}: invalid date-time`)
    }
    if (schema.format === 'uri-reference') assert.ok(value.length > 0, `${path}: empty URI reference`)
  }

  if (typeof value === 'number') {
    if (schema.minimum !== undefined) assert.ok(value >= schema.minimum, `${path}: below minimum`)
    if (schema.maximum !== undefined) assert.ok(value <= schema.maximum, `${path}: above maximum`)
  }

  if (Array.isArray(value)) {
    if (schema.minItems !== undefined) assert.ok(value.length >= schema.minItems, `${path}: too few items`)
    if (schema.maxItems !== undefined) assert.ok(value.length <= schema.maxItems, `${path}: too many items`)
    if (schema.uniqueItems) {
      const canonical = value.map(canonicalJson)
      assert.equal(new Set(canonical).size, canonical.length, `${path}: duplicate array items`)
    }
    if (schema.items) value.forEach((item, index) => validate(item, schema.items, schemaFile, `${path}[${index}]`))
  }

  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    for (const key of schema.required ?? []) assert.ok(Object.hasOwn(value, key), `${path}: missing ${key}`)
    if (schema.additionalProperties === false) {
      const allowed = new Set(Object.keys(schema.properties ?? {}))
      for (const key of Object.keys(value)) assert.ok(allowed.has(key), `${path}: unexpected property ${key}`)
    }
    for (const [key, propertySchema] of Object.entries(schema.properties ?? {})) {
      if (Object.hasOwn(value, key)) validate(value[key], propertySchema, schemaFile, `${path}.${key}`)
    }
  }
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(',')}}`
  }
  return JSON.stringify(value)
}

function digest(value) {
  return `sha256:${createHash('sha256').update(canonicalJson(value)).digest('hex')}`
}

function byteDigest(value) {
  return `sha256:${createHash('sha256').update(value).digest('hex')}`
}

function oggPageCrc(page) {
  let crc = 0
  for (let index = 0; index < page.length; index += 1) {
    const byte = index >= 22 && index < 26 ? 0 : page[index]
    crc = (((crc << 8) >>> 0) ^ OGG_CRC_TABLE[((crc >>> 24) ^ byte) & 0xff]) >>> 0
  }
  return crc
}

function assertCompleteOggPages(body, context) {
  const pages = []
  let offset = 0
  while (offset < body.length) {
    assert.equal(body.subarray(offset, offset + 4).toString('ascii'), 'OggS', `${context}: missing Ogg capture pattern`)
    assert.ok(offset + 27 <= body.length, `${context}: truncated Ogg page header`)
    assert.equal(body[offset + 4], 0, `${context}: unsupported Ogg bitstream version`)
    const segmentCount = body[offset + 26]
    const segmentTableEnd = offset + 27 + segmentCount
    assert.ok(segmentTableEnd <= body.length, `${context}: truncated Ogg segment table`)
    let payloadBytes = 0
    let packetCount = 0
    for (let index = offset + 27; index < segmentTableEnd; index += 1) {
      payloadBytes += body[index]
      if (body[index] < 255) packetCount += 1
    }
    const pageEnd = segmentTableEnd + payloadBytes
    assert.ok(pageEnd <= body.length, `${context}: truncated Ogg page payload`)
    const page = body.subarray(offset, pageEnd)
    assert.equal(oggPageCrc(page), page.readUInt32LE(22), `${context}: invalid Ogg page CRC`)
    pages.push({
      headerType: body[offset + 5],
      granulePosition: body.readBigUInt64LE(offset + 6),
      serial: body.readUInt32LE(offset + 14),
      sequence: body.readUInt32LE(offset + 18),
      packetCount,
      endsAtPacketBoundary: segmentCount > 0 && body[segmentTableEnd - 1] < 255,
      first: offset,
      lastExclusive: pageEnd,
    })
    offset = pageEnd
  }
  assert.equal(offset, body.length, `${context}: trailing non-page bytes`)
  assert.ok(pages.length > 0, `${context}: no complete Ogg page`)
  return pages
}

function asU64(value, context) {
  assert.equal(typeof value, 'string', `${context}: u64 must be a string`)
  assert.match(value, /^(0|[1-9][0-9]{0,19})$/, `${context}: non-canonical u64`)
  const parsed = BigInt(value)
  assert.ok(parsed <= U64_MAX, `${context}: u64 overflow`)
  return parsed
}

function asU64ExclusiveBoundary(value, context) {
  assert.equal(typeof value, 'string', `${context}: exclusive boundary must be a string`)
  assert.match(value, /^[1-9][0-9]{0,19}$/, `${context}: non-canonical exclusive boundary`)
  const parsed = BigInt(value)
  assert.ok(parsed <= U64_EXCLUSIVE_BOUNDARY_MAX, `${context}: exclusive boundary overflow`)
  return parsed
}

function assertRange(range, context) {
  const first = asU64(range.first, `${context}.first`)
  const last = asU64(range.last, `${context}.last`)
  assert.ok(first <= last, `${context}: first exceeds last`)
  return { first, last }
}

function assertCanonicalRanges(ranges, context) {
  let previous
  for (const [index, range] of ranges.entries()) {
    const current = assertRange(range, `${context}[${index}]`)
    if (previous) {
      assert.ok(previous.last + 1n < current.first, `${context}: ranges overlap or are not maximally merged`)
    }
    previous = current
  }
}

function assertPartition(accountedRange, committedRanges, missingRanges, context) {
  assertCanonicalRanges(committedRanges, `${context}.committedRanges`)
  assertCanonicalRanges(missingRanges, `${context}.missingRanges`)
  const accounted = assertRange(accountedRange, `${context}.accountedRange`)
  const combined = [...committedRanges.map((range) => ({ ...assertRange(range, context), kind: 'committed' })),
    ...missingRanges.map((range) => ({ ...assertRange(range, context), kind: 'missing' }))]
    .sort((a, b) => (a.first < b.first ? -1 : a.first > b.first ? 1 : 0))
  assert.ok(combined.length > 0, `${context}: empty partition`)
  assert.equal(combined[0].first, accounted.first, `${context}: partition does not start at accounted first`)
  assert.equal(combined.at(-1).last, accounted.last, `${context}: partition does not end at accounted last`)
  for (let index = 1; index < combined.length; index += 1) {
    assert.equal(combined[index - 1].last + 1n, combined[index].first, `${context}: partition gap or overlap`)
    assert.notEqual(combined[index - 1].kind, combined[index].kind, `${context}: adjacent like ranges were not merged`)
  }
}

function assertAccountedWithinPolicy(accountedRange, sequencePolicy, context) {
  const accounted = assertRange(accountedRange, `${context}.accountedRange`)
  const first = asU64(sequencePolicy.first, `${context}.sequencePolicy.first`)
  const maximumLast = asU64(sequencePolicy.maximumLast, `${context}.sequencePolicy.maximumLast`)
  assert.ok(first <= maximumLast, `${context}: invalid sequence policy`)
  assert.equal(accounted.first, first, `${context}: accounted range must start at policy first`)
  assert.ok(accounted.last <= maximumLast, `${context}: accounted range ends after policy`)
}

function walk(value, visitor, path = '$') {
  visitor(value, path)
  if (Array.isArray(value)) value.forEach((item, index) => walk(item, visitor, `${path}[${index}]`))
  else if (value !== null && typeof value === 'object') {
    for (const [key, item] of Object.entries(value)) walk(item, visitor, `${path}.${key}`)
  }
}

function fixture(relativePath) {
  return readJson(join(FIXTURES, relativePath))
}

const fixtureContracts = [
  ['success/create-session-request.json', 'session.schema.json', 'CreateIngestSessionRequest'],
  ['success/create-session-response.json', 'session.schema.json', 'CreateIngestSessionResponse'],
  ['success/refresh-credential-request.json', 'session.schema.json', 'RefreshIngestCredentialRequest'],
  ['success/refresh-credential-response.json', 'session.schema.json', 'RefreshIngestCredentialResponse'],
  ['success/chunk-descriptor.json', 'chunk.schema.json', 'ChunkDescriptor'],
  ['success/chunk-stored-ack.json', 'chunk.schema.json', 'DurableAck'],
  ['success/chunk-duplicate-ack.json', 'chunk.schema.json', 'DurableAck'],
  ['success/status-with-gap.json', 'session.schema.json', 'IngestStatus'],
  ['success/status-leading-gap.json', 'session.schema.json', 'IngestStatus'],
  ['success/finalize-request.json', 'manifest.schema.json', 'FinalizationRequest'],
  ['success/finalize-response.json', 'manifest.schema.json', 'FinalizationResponse'],
  ['success/finalize-replay-response.json', 'manifest.schema.json', 'FinalizationResponse'],
  ['success/immutable-manifest-projection.json', 'manifest.schema.json', 'ImmutableManifestProjection'],
  ['failure/chunk-conflicting-duplicate.json', 'problem.schema.json', 'Problem'],
  ['failure/finalize-sequence-gap.json', 'problem.schema.json', 'Problem'],
  ['failure/finalize-duplicate-stream.json', 'problem.schema.json', 'Problem'],
  ['failure/finalize-extra-durable-range.json', 'problem.schema.json', 'Problem'],
  ['failure/finalize-conflicting-replay.json', 'problem.schema.json', 'Problem'],
  ['failure/refresh-expired-session.json', 'problem.schema.json', 'Problem'],
  ['failure/content-digest-mismatch.json', 'problem.schema.json', 'Problem'],
]

test('every checked-in JSON artifact parses and schemas declare JSON Schema 2020-12', () => {
  const files = jsonFiles(APP)
  assert.ok(files.length >= 28, 'expected the API, schemas, and fixtures')
  for (const file of files) readJson(file)

  const ids = new Set()
  for (const file of jsonFiles(join(API, 'schemas'))) {
    const schema = readJson(file)
    assert.equal(schema.$schema, 'https://json-schema.org/draft/2020-12/schema', relative(APP, file))
    assert.match(schema.$id, /^https:\/\/gumi\.astrale\.ai\/schemas\/media-ingest\/v1\//)
    assert.ok(!ids.has(schema.$id), `duplicate schema id ${schema.$id}`)
    ids.add(schema.$id)
  }

  const commonFile = join(API, 'schemas', 'common.schema.json')
  const common = readJson(commonFile)
  validate('18446744073709551615', common.$defs.U64, commonFile, 'U64 max')
  assert.throws(() => validate('18446744073709551616', common.$defs.U64, commonFile, 'U64 overflow'))
  validate('18446744073709551616', common.$defs.U64ExclusiveBoundary, commonFile, 'exclusive max')
  assert.throws(() => validate('18446744073709551617', common.$defs.U64ExclusiveBoundary, commonFile, 'exclusive overflow'))
  validate('4294967295', common.$defs.OggSerialNumber, commonFile, 'Ogg serial max')
  assert.throws(() => validate('4294967296', common.$defs.OggSerialNumber, commonFile, 'Ogg serial overflow'))
  validate('65535', common.$defs.OpusPreSkip48kSamples, commonFile, 'Opus pre-skip max')
  assert.throws(() => validate('65536', common.$defs.OpusPreSkip48kSamples, commonFile, 'Opus pre-skip overflow'))
  validate(
    'sha-256=:WEQtKIFOrKEvt5SI2lgiJYUc+aZfA8TlqCuyHU54gFc=:',
    common.$defs.ContentDigestHeader,
    commonFile,
    'RFC 9530 Content-Digest',
  )
  assert.throws(() =>
    validate('sha256:58442d28814eaca1', common.$defs.ContentDigestHeader, commonFile, 'non-standard digest header'),
  )
})

test('OpenAPI 3.1 surface resolves every reference and keeps control and data credentials separate', () => {
  const file = join(API, 'openapi.json')
  const openapi = readJson(file)
  assert.equal(openapi.openapi, '3.1.0')
  assert.equal(openapi.jsonSchemaDialect, 'https://json-schema.org/draft/2020-12/schema')
  assert.equal(openapi['x-gumi-contract-version'], 'gumi.media-ingest.v1')

  walk(openapi, (value) => {
    if (value && typeof value === 'object' && typeof value.$ref === 'string') resolveRef(value.$ref, file)
  })

  const controlOperations = new Set([
    'createIngestSession',
    'refreshIngestCredential',
    'getImmutableManifestProjection',
  ])
  for (const [path, operations] of Object.entries(openapi.paths)) {
    for (const operation of Object.values(operations)) {
      const expected = controlOperations.has(operation.operationId) ? [{ controlBearer: [] }] : [{ ingestBearer: [] }]
      assert.deepEqual(operation.security, expected, `${operation.operationId} has the wrong credential boundary`)
      assert.equal(operation.responses['401'].$ref, '#/components/responses/UnauthorizedProblem')
      assert.equal(operation.responses['429'].$ref, '#/components/responses/RateLimitedProblem')
      assert.equal(operation.responses['400'].$ref, '#/components/responses/Problem')
      assert.equal(
        operation.parameters.filter((parameter) => parameter.$ref === '#/components/parameters/CorrelationId').length,
        1,
        `${operation.operationId} must expose one optional correlation ID`,
      )
      for (const [status, response] of Object.entries(operation.responses)) {
        if (response.$ref || Number(status) >= 300) continue
        assert.equal(response.headers['X-Request-ID'].$ref, '#/components/headers/RequestId')
        assert.equal(response.headers['X-Correlation-ID'].$ref, '#/components/headers/CorrelationId')
      }
    }
  }

  for (const operationId of ['createIngestSession', 'refreshIngestCredential', 'finalizeIngestSession']) {
    const operation = Object.values(openapi.paths)
      .flatMap((path) => Object.values(path))
      .find((candidate) => candidate.operationId === operationId)
    assert.equal(operation.responses['413'].$ref, '#/components/responses/Problem')
  }
  assert.equal(
    openapi.components.responses.RateLimitedProblem.content['application/problem+json'].schema.$ref,
    './schemas/problem.schema.json#/$defs/RateLimitedProblem',
  )

  const chunk = openapi.paths['/v1/ingest-sessions/{ingestSessionId}/streams/{streamId}/chunks/{chunkId}'].put
  assert.ok(chunk.requestBody.content['application/octet-stream'])
  const contentDigest = chunk.parameters
    .map((parameter) => resolveRef(parameter.$ref, file).schema)
    .find((parameter) => parameter.name === 'Content-Digest')
  assert.ok(contentDigest, 'chunk upload has no RFC 9530 Content-Digest header')
  assert.equal(chunk.responses['200'].content['application/json'].schema.$ref, './schemas/chunk.schema.json#/$defs/DurableAck')
  assert.ok(!chunk.responses['202'], 'accepted-but-not-durable response is forbidden')

  const refresh = openapi.paths['/v1/ingest-sessions/{ingestSessionId}/credentials'].post
  assert.equal(refresh.responses['200'].headers['Cache-Control'].$ref, '#/components/headers/NoStore')
  const create = openapi.paths['/v1/ingest-sessions'].post
  assert.equal(create.responses['201'].headers['Cache-Control'].$ref, '#/components/headers/NoStore')
  assert.equal(create.responses['200'].headers['Cache-Control'].$ref, '#/components/headers/NoStore')
  for (const response of Object.values(openapi.components.responses)) {
    assert.equal(response.headers['X-Request-ID'].$ref, '#/components/headers/RequestId')
    assert.equal(response.headers['X-Correlation-ID'].$ref, '#/components/headers/CorrelationId')
  }

  const lookup = openapi.paths['/v1/manifests/{manifestId}'].get
  assert.ok(lookup.parameters.some((parameter) => parameter.$ref === '#/components/parameters/ExpectedManifestDigest'))
  assert.equal(
    lookup.responses['200'].content['application/json'].schema.$ref,
    './schemas/manifest.schema.json#/$defs/ImmutableManifestProjection',
  )
  assert.equal(lookup.responses['200'].headers['Cache-Control'].schema.const, 'private, no-store')
})

test('rate-limit and JSON-size problems are explicit schema-level wire contracts', () => {
  const file = join(API, 'schemas', 'problem.schema.json')
  const schema = readJson(file)
  assert.ok(schema.$defs.Problem.properties.code.enum.includes('REQUEST_BODY_TOO_LARGE'))
  const rateLimited = {
    type: 'https://gumi.astrale.ai/problems/media-ingest/v1/rate-limited',
    title: 'Rate limited',
    status: 429,
    code: 'RATE_LIMITED',
    detail: 'The scoped caller is temporarily rate limited.',
    traceId: '0190c6f0-7b21-7a40-8b11-000000000020',
    retryAfterSeconds: 7,
  }
  validate(rateLimited, schema.$defs.RateLimitedProblem, file, 'RateLimitedProblem')
  const missingRetryDelay = structuredClone(rateLimited)
  delete missingRetryDelay.retryAfterSeconds
  assert.throws(() => validate(missingRetryDelay, schema.$defs.RateLimitedProblem, file, 'RateLimitedProblem'))
})

test('all success and failure fixtures conform to their canonical JSON Schema definitions', () => {
  for (const [fixturePath, schemaName, definition] of fixtureContracts) {
    const schemaFile = join(API, 'schemas', schemaName)
    const schema = readJson(schemaFile).$defs[definition]
    validate(fixture(fixturePath), schema, schemaFile, fixturePath)
  }
})

test('the semantic projection requires exact immutable primary-object integrity facts', () => {
  const schemaFile = join(API, 'schemas', 'manifest.schema.json')
  const schema = readJson(schemaFile).$defs.ImmutableManifestProjection
  const projection = structuredClone(fixture('success/immutable-manifest-projection.json'))

  for (const field of ['objectContentDigest', 'objectByteLength', 'objectContentType']) {
    const missing = structuredClone(projection)
    delete missing[field]
    assert.throws(() => validate(missing, schema, schemaFile, `projection without ${field}`))
  }

  assert.throws(() =>
    validate({ ...projection, objectByteLength: 241 }, schema, schemaFile, 'numeric objectByteLength'),
  )
  assert.throws(() =>
    validate({ ...projection, objectByteLength: '0' }, schema, schemaFile, 'empty objectByteLength'),
  )
  assert.throws(() =>
    validate({ ...projection, objectContentType: 'audio/ogg' }, schema, schemaFile, 'imprecise objectContentType'),
  )
})

test('credential rotation extends data-plane access without extending or replacing the session', () => {
  const created = fixture('success/create-session-response.json')
  const refreshRequest = fixture('success/refresh-credential-request.json')
  const refreshed = fixture('success/refresh-credential-response.json')

  assert.equal(refreshed.requestId, refreshRequest.requestId)
  assert.equal(refreshed.ingestSessionId, created.ingestSessionId)
  assert.equal(refreshed.sessionExpiresAt, created.sessionExpiresAt)
  assert.notEqual(refreshed.credential.accessToken, created.credential.accessToken)
  assert.ok(Date.parse(refreshed.credential.issuedAt) > Date.parse(created.credential.issuedAt))
  assert.ok(Date.parse(refreshed.credential.expiresAt) <= Date.parse(refreshed.sessionExpiresAt))
  assert.ok(Date.parse(created.retainedUntil) >= Date.parse(created.sessionExpiresAt))

  const expired = fixture('failure/refresh-expired-session.json')
  assert.equal(expired.code, 'INGEST_SESSION_EXPIRED')
  assert.equal(expired.status, 409)
  assert.equal(expired.details.ingestSessionId, created.ingestSessionId)
  assert.equal(expired.details.sessionExpiresAt, created.sessionExpiresAt)
})

test('all protocol identities, sequence values, and digests use lossless stable encodings', () => {
  const idKeys = new Set([
    'requestId',
    'traceId',
    'ingestSessionId',
    'captureSessionId',
    'deviceId',
    'edgeHostId',
    'streamId',
    'chunkId',
    'acknowledgedChunkId',
    'manifestId',
    'mediaObjectId',
  ])
  for (const file of jsonFiles(FIXTURES)) {
    walk(readJson(file), (value, path) => {
      const key = path.split('.').at(-1)
      if (idKeys.has(key) && value !== null) assert.match(value, UUID_V7, `${relative(APP, file)} ${path}`)
      if (/Digest$/.test(key)) assert.match(value, SHA256, `${relative(APP, file)} ${path}`)
      if (/^(first|last|maximumLast|sequenceStart|stateRevision|.*Bytes|.*ByteLength|.*ChunkCount|chunkCount|droppedFrameCount|durableThrough)$/.test(key) && value !== null) {
        asU64(value, `${relative(APP, file)} ${path}`)
      }
      if (key === 'sequenceEndExclusive') asU64ExclusiveBoundary(value, `${relative(APP, file)} ${path}`)
    })
  }
})

test('a durable acknowledgement names the exact chunk and an identical replay preserves durable state', () => {
  const descriptor = fixture('success/chunk-descriptor.json')
  const stored = fixture('success/chunk-stored-ack.json')
  const duplicate = fixture('success/chunk-duplicate-ack.json')
  const policy = fixture('success/create-session-response.json').streams[0]

  assert.equal(stored.acknowledgedChunkId, descriptor.chunkId)
  assert.equal(stored.acknowledgedContentDigest, descriptor.contentDigest)
  assert.equal(stored.acknowledgedDescriptorDigest, digest(descriptor))
  assert.deepEqual(stored.acknowledgedSequenceRange, descriptor.sequenceRange)
  assert.equal(stored.disposition, 'stored')
  assert.equal(duplicate.disposition, 'duplicate')
  assert.ok(!Object.hasOwn(stored, 'accepted'), 'request acceptance is not a durability fact')
  assertAccountedWithinPolicy(stored.accountedRange, policy.sequencePolicy, 'stored ack')
  assertPartition(stored.accountedRange, stored.committedRanges, stored.missingRanges, 'stored ack')

  for (const key of [
    'ingestSessionId',
    'streamId',
    'acknowledgedChunkId',
    'acknowledgedContentDigest',
    'acknowledgedDescriptorDigest',
    'acknowledgedSequenceRange',
    'accountedRange',
    'committedRanges',
    'missingRanges',
    'durableThrough',
    'stateRevision',
    'sessionState',
  ]) {
    assert.deepEqual(duplicate[key], stored[key], `duplicate changed durable field ${key}`)
  }
})

test('a conflicting duplicate is explicit and preserves the first durable digest', () => {
  const stored = fixture('success/chunk-stored-ack.json')
  const problem = fixture('failure/chunk-conflicting-duplicate.json')
  assert.equal(problem.status, 409)
  assert.equal(problem.code, 'CHUNK_DIGEST_CONFLICT')
  assert.equal(problem.details.chunkId, stored.acknowledgedChunkId)
  assert.equal(problem.details.existingDigest, stored.acknowledgedContentDigest)
  assert.notEqual(problem.details.receivedDigest, problem.details.existingDigest)
})

test('resume status is an exact partition and its gap blocks finalization', () => {
  const status = fixture('success/status-with-gap.json')
  const stream = status.streams[0]
  const problem = fixture('failure/finalize-sequence-gap.json')
  assertAccountedWithinPolicy(stream.accountedRange, stream.sequencePolicy, 'status')
  assertPartition(stream.accountedRange, stream.committedRanges, stream.missingRanges, 'status')
  assert.equal(stream.durableThrough, '1')
  assert.equal(problem.status, 409)
  assert.equal(problem.code, 'SEQUENCE_GAP')
  assert.equal(problem.details.streamId, stream.streamId)
  assert.deepEqual(problem.details.missingRanges, stream.missingRanges)

  const leading = fixture('success/status-leading-gap.json').streams[0]
  assertAccountedWithinPolicy(leading.accountedRange, leading.sequencePolicy, 'leading-gap status')
  assertPartition(leading.accountedRange, leading.committedRanges, leading.missingRanges, 'leading-gap status')
  assert.deepEqual(leading.missingRanges, [{ first: leading.sequencePolicy.first, last: '1' }])
  assert.equal(leading.durableThrough, null)
})

test('finalization binds expected complete ranges to one immutable manifest digest', () => {
  const request = fixture('success/finalize-request.json')
  const response = fixture('success/finalize-response.json')
  const replay = fixture('success/finalize-replay-response.json')
  const expected = request.streams[0]
  const actual = response.manifest.streams[0]
  const policies = fixture('success/create-session-response.json').streams
  const session = fixture('success/create-session-response.json')

  assert.deepEqual(
    request.streams.map((stream) => stream.streamId).sort(),
    policies.map((stream) => stream.streamId).sort(),
    'finalization stream set differs from the session stream set',
  )
  assert.equal(new Set(request.streams.map((stream) => stream.streamId)).size, request.streams.length)
  assert.equal(expected.expectedRange.first, policies[0].sequencePolicy.first)
  assert.ok(BigInt(expected.expectedRange.last) <= BigInt(policies[0].sequencePolicy.maximumLast))
  assert.ok(policies.some((policy) => policy.streamId === session.primaryStreamId))
  assert.equal(response.manifest.primaryStreamId, session.primaryStreamId)

  assert.equal(actual.streamId, expected.streamId)
  assert.deepEqual(actual.sequenceRange, expected.expectedRange)
  assert.deepEqual(actual.committedRanges, [expected.expectedRange])
  assert.equal(actual.chunkCount, expected.expectedChunkCount)
  assert.equal(actual.object.byteLength, expected.expectedByteLength)
  assert.equal(actual.object.contentDigest, expected.expectedContentDigest)
  assert.equal(response.manifestDigest, digest(response.manifest))
  assert.equal(replay.disposition, 'already-finalized')
  assert.equal(replay.requestId, response.requestId)
  assert.equal(replay.manifestDigest, response.manifestDigest)
  assert.deepEqual(replay.manifest, response.manifest)

  const changed = structuredClone(response.manifest)
  changed.streams[0].object.byteLength = '9601'
  assert.notEqual(digest(changed), response.manifestDigest, 'a changed manifest retained its integrity identity')

  const duplicate = fixture('failure/finalize-duplicate-stream.json')
  assert.equal(duplicate.code, 'FINALIZATION_STREAM_SET_MISMATCH')
  assert.equal(new Set(duplicate.details.declaredStreamIds).size, 1)
  assert.equal(duplicate.details.declaredStreamIds.length, 2)

  const extra = fixture('failure/finalize-extra-durable-range.json')
  assert.equal(extra.code, 'FINALIZATION_RANGE_CONFLICT')
  assert.ok(
    BigInt(extra.details.durableRanges.at(-1).last) > BigInt(extra.details.declaredExpectedRange.last),
    'extra-range fixture does not actually exceed the declared terminal sequence',
  )
})

test('the M1 payload profile assembles exact Ogg pages by sequence without remuxing', () => {
  const conformance = fixture('success/deterministic-assembly.json')
  const policy = fixture('success/create-session-response.json').streams[0]
  assert.equal(conformance.payloadFormat, 'ogg-opus-page-fragment-v1')
  assert.equal(conformance.layoutProfile, policy.oggLayout.profile)
  const chunks = [...conformance.chunks].sort((left, right) =>
    BigInt(left.sequenceRange.first) < BigInt(right.sequenceRange.first) ? -1 : 1,
  )
  const bodies = []
  let previous
  const frameSamples = BigInt(policy.codec.frameDurationUs) * 48_000n / 1_000_000n
  for (const [index, chunk] of chunks.entries()) {
    const range = assertRange(chunk.sequenceRange, `assembly.chunks[${index}].sequenceRange`)
    if (previous) assert.equal(previous.last + 1n, range.first, 'assembly fixture has a sequence gap')
    previous = range
    const body = Buffer.from(chunk.bodyBase64, 'base64')
    assert.equal(String(body.length), chunk.payloadBytes)
    assert.equal(byteDigest(body), chunk.contentDigest)
    assert.equal(
      chunk.contentDigestHeader,
      `sha-256=:${createHash('sha256').update(body).digest('base64')}:`,
      'RFC 9530 Content-Digest does not represent the exact chunk body',
    )
    const pages = assertCompleteOggPages(body, `assembly.chunks[${index}]`)
    const headerPageCount = index === 0 ? 2 : 0
    assert.equal(
      BigInt(pages.length - headerPageCount),
      range.last - range.first + 1n,
      `assembly.chunks[${index}] has the wrong audio-page count`,
    )
    assert.equal(pages[0].headerType & 1, 0, `assembly.chunks[${index}] starts with a continued packet`)
    assert.ok(pages.at(-1).endsAtPacketBoundary, `assembly.chunks[${index}] ends inside an Ogg packet`)
    const headerPacketCount = index === 0 ? 2 : 0
    const audioPacketCount = pages.reduce((sum, page) => sum + page.packetCount, 0) - headerPacketCount
    assert.equal(BigInt(audioPacketCount), range.last - range.first + 1n, `assembly.chunks[${index}] packet range mismatch`)
    const audioPages = pages.slice(headerPageCount)
    for (const [audioIndex, audioPage] of audioPages.entries()) {
      const audioSequence = range.first + BigInt(audioIndex)
      assert.equal(audioPage.packetCount, 1, 'audio page does not contain exactly one packet')
      assert.equal(audioPage.serial, Number(policy.oggLayout.serialNumber), 'page serial differs from session layout')
      assert.equal(audioPage.sequence, 2 + Number(audioSequence - BigInt(policy.sequencePolicy.first)))
      const untrimmedGranule = (audioSequence - BigInt(policy.sequencePolicy.first) + 1n) * frameSamples
      if ((audioPage.headerType & 4) === 0) assert.equal(audioPage.granulePosition, untrimmedGranule)
      else {
        assert.equal(audioIndex, audioPages.length - 1, 'EOS appears before the end of a chunk')
        assert.ok(audioPage.granulePosition <= untrimmedGranule)
        assert.ok(untrimmedGranule - audioPage.granulePosition <= frameSamples)
        assert.ok(audioPage.granulePosition >= BigInt(policy.oggLayout.preSkip48kSamples))
      }
    }
    bodies.push(body)
  }
  const assembled = Buffer.concat(bodies)
  const allPages = assertCompleteOggPages(assembled, 'assembled object')
  assert.ok((allPages[0].headerType & 2) !== 0, 'assembled object has no beginning-of-stream page')
  assert.ok((allPages.at(-1).headerType & 4) !== 0, 'assembled object has no end-of-stream page')
  assert.ok(assembled.includes(Buffer.from('OpusHead')), 'assembled object has no OpusHead packet')
  assert.ok(assembled.includes(Buffer.from('OpusTags')), 'assembled object has no OpusTags packet')
  for (const [index, page] of allPages.entries()) {
    assert.equal(page.serial, allPages[0].serial, 'assembled object changes Ogg logical-stream serial')
    assert.equal(page.sequence, index, 'assembled object has a non-contiguous Ogg page sequence')
  }
  assert.equal(String(assembled.length), conformance.expectedByteLength)
  assert.equal(byteDigest(assembled), conformance.expectedContentDigest)

  const opusHeadOffset = assembled.indexOf(Buffer.from('OpusHead'))
  const preSkip = assembled.readUInt16LE(opusHeadOffset + 10)
  const playableSamples = allPages.at(-1).granulePosition - BigInt(preSkip)
  const durationMs = playableSamples / 48n
  assert.equal(Number(durationMs), fixture('success/finalize-response.json').manifest.streams[0].durationMs)

  const finalization = fixture('success/finalize-request.json').streams[0]
  assert.deepEqual(finalization.expectedRange, {
    first: chunks[0].sequenceRange.first,
    last: chunks.at(-1).sequenceRange.last,
  })
  assert.equal(finalization.expectedChunkCount, String(chunks.length))
  assert.equal(finalization.expectedByteLength, conformance.expectedByteLength)
  assert.equal(finalization.expectedContentDigest, conformance.expectedContentDigest)
})

test('the semantic projection is a digest-bound lossless view of the primary immutable stream', () => {
  const response = fixture('success/finalize-response.json')
  const projection = fixture('success/immutable-manifest-projection.json')
  const manifest = response.manifest
  const stream = manifest.streams.find((candidate) => candidate.streamId === projection.primaryStreamId)

  assert.ok(stream, 'projection primary stream is absent from the immutable manifest')
  assert.equal(projection.manifestId, manifest.manifestId)
  assert.equal(projection.manifestDigest, response.manifestDigest)
  assert.equal(projection.manifestDigest, digest(manifest))
  assert.equal(projection.captureSessionId, manifest.captureSessionId)
  assert.equal(projection.deviceId, manifest.deviceId)
  assert.equal(projection.edgeHostId, manifest.edgeHostId)
  assert.equal(projection.primaryStreamId, manifest.primaryStreamId)
  assert.equal(projection.objectHandle, `gumi-media:object/${stream.object.mediaObjectId}`)
  assert.equal(projection.objectContentDigest, stream.object.contentDigest)
  assert.equal(projection.objectByteLength, stream.object.byteLength)
  assert.equal(projection.objectContentType, stream.object.contentType)
  assert.equal(typeof projection.objectByteLength, 'string')
  assert.equal(asU64(projection.objectByteLength, 'projection.objectByteLength'), BigInt(stream.object.byteLength))
  assert.equal(projection.sequenceStart, stream.sequenceRange.first)
  assert.equal(BigInt(projection.sequenceEndExclusive), BigInt(stream.sequenceRange.last) + 1n)
  assert.equal(projection.codec, stream.codec.name)
  assert.equal(projection.sampleRateHz, stream.codec.sampleRateHz)
  assert.equal(projection.channels, stream.codec.channelCount)
  assert.equal(projection.startedAt, stream.startedAt)
  assert.equal(projection.endedAt, stream.endedAt)
  assert.equal(projection.durationMs, stream.durationMs)
  assert.ok(Date.parse(projection.endedAt) >= Date.parse(projection.startedAt))
  assert.equal(Date.parse(projection.endedAt) - Date.parse(projection.startedAt), projection.durationMs)
})

test('problem codes retain their transport status and never masquerade as durable acknowledgements', () => {
  const expected = new Map([
    ['CHUNK_DIGEST_CONFLICT', 409],
    ['SEQUENCE_GAP', 409],
    ['FINALIZATION_STREAM_SET_MISMATCH', 409],
    ['FINALIZATION_RANGE_CONFLICT', 409],
    ['SESSION_ALREADY_FINALIZED', 409],
    ['INGEST_SESSION_EXPIRED', 409],
    ['CONTENT_DIGEST_MISMATCH', 422],
  ])
  for (const file of jsonFiles(join(FIXTURES, 'failure'))) {
    const problem = readJson(file)
    assert.equal(problem.status, expected.get(problem.code), relative(APP, file))
    assert.ok(!Object.hasOwn(problem, 'committedRanges'))
    assert.ok(!Object.hasOwn(problem, 'durableThrough'))
  }
})
