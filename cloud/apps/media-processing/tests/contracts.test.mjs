import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import {
  DeterministicIds,
  InMemoryArtifactStore,
  InMemoryManifestReader,
  InMemoryProcessingStorage,
  ManualClock,
  MediaProcessingService,
} from '../src/index.mjs'
import { byteDigest } from '../src/core/primitives.mjs'
import { FAILURE_CODES_BY_OUTCOME } from '../src/core/validation.mjs'
import { PUBLISHED_PROBLEM_CODES } from '../src/http/errors.mjs'

const APP = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const API = join(APP, 'api', 'v1')
const FIXTURES = join(APP, 'fixtures', 'v1')
const jsonCache = new Map()

function readJson(path) {
  const absolute = resolve(path)
  if (!jsonCache.has(absolute)) jsonCache.set(absolute, JSON.parse(readFileSync(absolute, 'utf8')))
  return structuredClone(jsonCache.get(absolute))
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
  assert.match(fragment, /^#\//, `${context}: unsupported pointer`)
  return fragment
    .slice(2)
    .split('/')
    .map((part) => part.replaceAll('~1', '/').replaceAll('~0', '~'))
    .reduce((value, key) => {
      assert.ok(value && Object.hasOwn(value, key), `${context}: missing pointer key ${key}`)
      return value[key]
    }, document)
}

function resolveRef(ref, fromFile) {
  const hash = ref.indexOf('#')
  const filePart = hash === -1 ? ref : ref.slice(0, hash)
  const fragment = hash === -1 ? '' : ref.slice(hash)
  const file = filePart ? resolve(dirname(fromFile), filePart) : resolve(fromFile)
  return { schema: pointer(readJson(file), fragment, `${relative(APP, fromFile)} -> ${ref}`), file }
}

function typeMatches(value, type) {
  if (type === 'object') return value !== null && typeof value === 'object' && !Array.isArray(value)
  if (type === 'array') return Array.isArray(value)
  if (type === 'integer') return Number.isInteger(value)
  if (type === 'null') return value === null
  return typeof value === type
}

function validate(value, schema, schemaFile, path = '$') {
  if (schema.$ref) {
    const target = resolveRef(schema.$ref, schemaFile)
    validate(value, target.schema, target.file, path)
  }
  for (const candidate of schema.allOf ?? []) validate(value, candidate, schemaFile, path)
  if (schema.not) {
    let matched = true
    try {
      validate(value, schema.not, schemaFile, path)
    } catch {
      matched = false
    }
    assert.equal(matched, false, `${path}: matched forbidden schema`)
  }
  if (schema.oneOf) {
    const matches = schema.oneOf.filter((candidate) => {
      try {
        validate(value, candidate, schemaFile, path)
        return true
      } catch {
        return false
      }
    })
    assert.equal(matches.length, 1, `${path}: expected exactly one oneOf match`)
  }
  if (Object.hasOwn(schema, 'const')) assert.deepEqual(value, schema.const, `${path}: const mismatch`)
  if (schema.enum) assert.ok(schema.enum.some((candidate) => Object.is(candidate, value)), `${path}: enum mismatch`)
  if (schema.type) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type]
    assert.ok(types.some((type) => typeMatches(value, type)), `${path}: type mismatch`)
  }
  if (typeof value === 'string') {
    const length = [...value].length
    if (schema.minLength !== undefined) assert.ok(length >= schema.minLength, `${path}: too short`)
    if (schema.maxLength !== undefined) assert.ok(length <= schema.maxLength, `${path}: too long`)
    if (schema.pattern) assert.match(value, new RegExp(schema.pattern), `${path}: pattern mismatch`)
    if (schema.format === 'date-time') assert.ok(Number.isFinite(Date.parse(value)), `${path}: invalid date-time`)
  }
  if (typeof value === 'number') {
    if (schema.minimum !== undefined) assert.ok(value >= schema.minimum, `${path}: below minimum`)
    if (schema.maximum !== undefined) assert.ok(value <= schema.maximum, `${path}: above maximum`)
  }
  if (Array.isArray(value)) {
    if (schema.maxItems !== undefined) assert.ok(value.length <= schema.maxItems, `${path}: too many items`)
    if (schema.items) value.forEach((item, index) => validate(item, schema.items, schemaFile, `${path}[${index}]`))
  }
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    for (const key of schema.required ?? []) assert.ok(Object.hasOwn(value, key), `${path}: missing ${key}`)
    if (schema.additionalProperties === false) {
      const allowed = new Set(Object.keys(schema.properties ?? {}))
      for (const key of Object.keys(value)) assert.ok(allowed.has(key), `${path}: unexpected ${key}`)
    }
    for (const [key, childSchema] of Object.entries(schema.properties ?? {})) {
      if (Object.hasOwn(value, key)) validate(value[key], childSchema, schemaFile, `${path}.${key}`)
    }
  }
}

function walk(value, visitor) {
  visitor(value)
  if (Array.isArray(value)) value.forEach((item) => walk(item, visitor))
  else if (value && typeof value === 'object') Object.values(value).forEach((item) => walk(item, visitor))
}

const id = (number) => `0190c6f0-7b21-7a40-8b11-${BigInt(number).toString(16).padStart(12, '0')}`
const hash = (character) => `sha256:${character.repeat(64)}`

test('every JSON artifact parses, every schema has a unique 2020-12 identity, and decimal-string bounds are standard JSON Schema', () => {
  const files = jsonFiles(APP)
  assert.ok(files.length >= 8)
  for (const file of files) readJson(file)

  const ids = new Set()
  for (const file of jsonFiles(join(API, 'schemas'))) {
    const schema = readJson(file)
    assert.equal(schema.$schema, 'https://json-schema.org/draft/2020-12/schema')
    assert.match(schema.$id, /^https:\/\/gumi\.astrale\.ai\/schemas\/media-processing\/v1\//)
    assert.equal(ids.has(schema.$id), false)
    ids.add(schema.$id)
  }
  const commonFile = join(API, 'schemas', 'common.schema.json')
  const common = readJson(commonFile)
  for (const value of ['0', '1', '9999999999999999999', '10000000000000000000', '18446744073709551614', '18446744073709551615']) {
    validate(value, common.$defs.U64, commonFile)
  }
  for (const value of ['-1', '00', '01', '18446744073709551616', '99999999999999999999', '184467440737095516150']) {
    assert.throws(() => validate(value, common.$defs.U64, commonFile), value)
  }
  for (const value of ['1', '9999999', '10000000', '67108863', '67108864']) {
    validate(value, common.$defs.DerivedArtifactByteLength, commonFile)
  }
  for (const value of ['0', '67108865', '99999999']) {
    assert.throws(() => validate(value, common.$defs.DerivedArtifactByteLength, commonFile), value)
  }

  walk(common, (value) => {
    if (value && typeof value === 'object') assert.equal(Object.hasOwn(value, 'x-gumi-maximum'), false)
  })
})

test('transcript source kind is bounded and speaker labels are optional but non-empty', () => {
  const file = join(API, 'schemas', 'transcript-artifact.schema.json')
  const schema = readJson(file).$defs.TranscriptArtifact
  const artifact = {
    schemaVersion: 'gumi.media-processing.transcript-artifact.v1',
    language: 'en-US',
    mediaDurationMs: '1',
    segments: [{ index: '0', startMs: '0', endMs: '1', text: 'hello' }],
  }
  validate(artifact, schema, file)
  artifact.segments[0].kind = 'speech'
  validate(artifact, schema, file)
  artifact.segments[0].kind = 'audio-event'
  validate(artifact, schema, file)
  artifact.segments[0].kind = 'provider-invented-kind'
  assert.throws(() => validate(artifact, schema, file))
  artifact.segments[0].kind = 'speech'
  artifact.segments[0].speakerLabel = 'speaker-1'
  validate(artifact, schema, file)
  artifact.segments[0].speakerLabel = '🟣'.repeat(256)
  validate(artifact, schema, file)
  artifact.segments[0].speakerLabel = '🟣'.repeat(257)
  assert.throws(() => validate(artifact, schema, file))
  artifact.segments[0].speakerLabel = ''
  assert.throws(() => validate(artifact, schema, file))
})

test('OpenAPI resolves every reference and keeps control, worker, and callback credentials distinct', () => {
  const file = join(API, 'openapi.json')
  const openapi = readJson(file)
  assert.equal(openapi.openapi, '3.1.0')
  assert.equal(openapi.jsonSchemaDialect, 'https://json-schema.org/draft/2020-12/schema')
  assert.equal(openapi['x-gumi-contract-version'], 'gumi.media-processing.v1')
  walk(openapi, (value) => {
    if (value && typeof value === 'object' && typeof value.$ref === 'string') resolveRef(value.$ref, file)
  })

  const operationIds = new Set()
  const expectedSecurity = {
    createProcessingJob: [{ controlBearer: [] }],
    getProcessingJob: [{ controlBearer: [] }],
    retryProcessingJob: [{ controlBearer: [] }],
    cancelProcessingJob: [{ controlBearer: [] }],
    claimProcessingAttempt: [{ workerBearer: [] }],
    renewProcessingLease: [{ workerBearer: [] }],
    completeProcessingAttempt: [{ providerCallbackBearer: [] }],
    resolveProcessingResult: [{ controlBearer: [] }],
    readTranscriptPage: [{ controlBearer: [] }],
  }
  for (const operations of Object.values(openapi.paths)) {
    for (const operation of Object.values(operations)) {
      assert.equal(operationIds.has(operation.operationId), false)
      operationIds.add(operation.operationId)
      assert.deepEqual(operation.security, expectedSecurity[operation.operationId])
      assert.ok(operation.responses.default)
    }
  }
  assert.deepEqual([...operationIds].sort(), Object.keys(expectedSecurity).sort())
  assert.equal(
    openapi.paths['/v1/processing-results/{processingJobId}'].get.responses['200'].content['application/json'].schema.$ref,
    '#/components/schemas/DerivedArtifactProjection',
  )
  assert.equal(
    openapi.paths['/v1/processing-results/{processingJobId}/transcript-pages/{startIndex}'].get.responses['200']
      .content['application/json'].schema.$ref,
    '#/components/schemas/TranscriptPage',
  )
})

test('the executable core conforms to every success schema and the golden result projection', async () => {
  const storage = new InMemoryProcessingStorage()
  const clock = new ManualClock()
  const ids = new DeterministicIds({ next: 100 })
  const manifestReader = new InMemoryManifestReader([
    {
      schemaVersion: 'gumi.media-processing.immutable-input.v1',
      manifestId: id(1),
      manifestDigest: hash('1'),
      objectHandle: 'gumi-media:object/fixture-1',
      contentDigest: hash('2'),
      contentType: 'audio/ogg; codecs=opus',
      byteLength: '4096',
      durationMs: '60000',
    },
  ])
  const artifacts = new InMemoryArtifactStore()
  const service = new MediaProcessingService({ storage, clock, ids, manifestReader, artifacts })
  const control = { kind: 'control', callerId: '@astrale/gumi/control' }
  const worker = {
    kind: 'worker',
    workerId: 'worker/eu-west/1',
    allowedPipelineIds: ['transcription.v1'],
  }
  const createRequest = readJson(join(FIXTURES, 'success', 'create-job-request.json'))
  const created = await service.createJob(createRequest, control)
  const claim = await service.claimAttempt(
    {
      schemaVersion: 'gumi.media-processing.claim-attempt.v1',
      requestId: id(10),
      processingJobId: created.job.processingJobId,
      expectedGeneration: '0',
    },
    worker,
  )
  const renewal = await service.renewLease(
    {
      schemaVersion: 'gumi.media-processing.renew-lease.v1',
      requestId: id(11),
      processingJobId: claim.processingJobId,
      attemptId: claim.attemptId,
      generation: claim.generation,
      leaseId: claim.leaseId,
      expectedLeaseRevision: '0',
    },
    worker,
  )
  const transcriptArtifact = readJson(join(FIXTURES, 'success', 'transcript-artifact.json'))
  const bytes = Buffer.from(JSON.stringify(transcriptArtifact))
  artifacts.stage('stage/transcript-1', bytes)
  const completed = await service.completeAttempt(
    {
      schemaVersion: 'gumi.media-processing.complete-attempt.v1',
      callbackId: id(20),
      processingJobId: claim.processingJobId,
      attemptId: claim.attemptId,
      generation: claim.generation,
      outcome: 'succeeded',
      result: {
        artifact: {
          stagedArtifactHandle: 'stage/transcript-1',
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
    },
    {
      kind: 'provider-callback',
      providerId: 'fixture-provider',
      processingJobId: claim.processingJobId,
      attemptId: claim.attemptId,
    },
  )
  const projection = await service.resolveResult(
    {
      processingJobId: claim.processingJobId,
      expectedInputContentDigest: hash('2'),
      expectedOutputContentDigest: byteDigest(bytes),
    },
    control,
  )
  const transcriptPage = await service.readTranscriptPage(
    {
      processingJobId: claim.processingJobId,
      startIndex: '0',
      expectedInputContentDigest: hash('2'),
      expectedOutputContentDigest: byteDigest(bytes),
    },
    control,
  )

  const schemas = [
    [createRequest, 'job.schema.json', 'CreateJobRequest'],
    [created, 'job.schema.json', 'JobCreatedResponse'],
    [created.job, 'job.schema.json', 'JobStatus'],
    [claim, 'attempt.schema.json', 'AttemptLease'],
    [renewal, 'attempt.schema.json', 'LeaseRenewedResponse'],
    [completed, 'attempt.schema.json', 'AttemptCompletedResponse'],
    [projection, 'result.schema.json', 'DerivedArtifactProjection'],
    [transcriptPage, 'transcript-page.schema.json', 'TranscriptPage'],
    [transcriptArtifact, 'transcript-artifact.schema.json', 'TranscriptArtifact'],
  ]
  for (const [value, fileName, definition] of schemas) {
    const file = join(API, 'schemas', fileName)
    validate(value, readJson(file).$defs[definition], file, definition)
  }
  assert.deepEqual(projection, readJson(join(FIXTURES, 'success', 'derived-artifact-projection.json')))
  assert.deepEqual(transcriptPage, readJson(join(FIXTURES, 'success', 'transcript-page.json')))
})

test('public result and callback schemas contain only bounded facts, never content or provider payload fields', () => {
  const forbidden = new Set([
    'text',
    'transcript',
    'words',
    'providerPayload',
    'providerResponse',
    'rawAudio',
    'accessToken',
    'signedUrl',
    'systemPrompt',
    'toolCall',
  ])
  for (const fileName of ['attempt.schema.json', 'result.schema.json']) {
    walk(readJson(join(API, 'schemas', fileName)), (value) => {
      if (!value || typeof value !== 'object' || !value.properties) return
      for (const key of Object.keys(value.properties)) assert.equal(forbidden.has(key), false, `${fileName}: ${key}`)
    })
  }
})

test('every core, HTTP, and test-port failure code is published by the problem schema', () => {
  const source = [...jsonFiles(join(API, 'schemas'))]
  assert.ok(source.length > 0)
  const implementation = [join(APP, 'src', 'core'), join(APP, 'src', 'http'), join(APP, 'src', 'testing')]
    .flatMap((root) => {
      const result = []
      const visit = (directory) => {
        for (const entry of readdirSync(directory, { withFileTypes: true })) {
          const path = join(directory, entry.name)
          if (entry.isDirectory()) visit(path)
          else if (entry.name.endsWith('.mjs')) result.push(readFileSync(path, 'utf8'))
        }
      }
      visit(root)
      return result
    })
    .join('\n')
  const used = new Set(
    [...implementation.matchAll(/(?:fail|new (?:ProcessingError|HttpAdapterError))\(\s*'([A-Z][A-Z0-9_]+)'/g)].map(
      (match) => match[1],
    ),
  )
  used.add('INVALID_REQUEST')
  used.add('RATE_LIMITED')
  const published = new Set(readJson(join(API, 'schemas', 'problem.schema.json')).$defs.Problem.properties.code.enum)
  assert.deepEqual([...PUBLISHED_PROBLEM_CODES].sort(), [...published].sort())
  for (const code of used) assert.ok(published.has(code), `unpublished problem code ${code}`)
})

test('callback failure vocabulary is finite and agrees with executable outcome validation', () => {
  const file = join(API, 'schemas', 'attempt.schema.json')
  const definitions = readJson(file).$defs
  const published = new Set([
    ...definitions.RetryableCompletionFailure.properties.code.enum,
    ...definitions.PermanentCompletionFailure.properties.code.enum,
  ])
  const executable = new Set(Object.values(FAILURE_CODES_BY_OUTCOME).flat())
  assert.deepEqual([...published].sort(), [...executable].sort())
  const overlap = FAILURE_CODES_BY_OUTCOME['retryable-failure'].filter((code) =>
    FAILURE_CODES_BY_OUTCOME['permanent-failure'].includes(code),
  )
  assert.deepEqual(overlap, [])
})
