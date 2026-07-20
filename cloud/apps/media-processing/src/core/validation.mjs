import {
  digest,
  languageTag,
  opaque,
  positiveU64,
  slug,
  u64,
  utcTimestamp,
  uuidV7,
} from './primitives.mjs'
import { invalid } from './error.mjs'

export const FAILURE_CODES_BY_OUTCOME = Object.freeze({
  'retryable-failure': Object.freeze([
    'PROVIDER_TIMEOUT',
    'PROVIDER_RATE_LIMITED',
    'PROVIDER_UNAVAILABLE',
    'TRANSIENT_PROVIDER_FAILURE',
  ]),
  'permanent-failure': Object.freeze([
    'PROVIDER_INVALID_INPUT',
    'PROVIDER_OUTPUT_INVALID',
    'PROVIDER_POLICY_REJECTED',
    'PROVIDER_CANCELED',
    'PERMANENT_PROVIDER_FAILURE',
  ]),
})

function object(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid(`${label} must be an object`)
  return value
}

function exactKeys(value, required, optional, label) {
  object(value, label)
  const keys = Object.keys(value)
  for (const key of required) if (!keys.includes(key)) invalid(`${label}.${key} is required`)
  const allowed = new Set([...required, ...optional])
  for (const key of keys) if (!allowed.has(key)) invalid(`${label}.${key} is not allowed`)
}

function literal(actual, expected, label) {
  if (actual !== expected) invalid(`${label} must equal ${expected}`)
}

function requestBase(value, schemaVersion) {
  object(value, '$')
  literal(value.schemaVersion, schemaVersion, '$.schemaVersion')
  uuidV7(value.requestId ?? value.callbackId, value.requestId ? 'requestId' : 'callbackId')
}

export function validateCreateRequest(value) {
  exactKeys(value, ['schemaVersion', 'requestId', 'inputManifest', 'pipeline'], [], '$')
  requestBase(value, 'gumi.media-processing.create-job.v1')
  exactKeys(value.inputManifest, ['manifestId', 'expectedManifestDigest'], [], '$.inputManifest')
  uuidV7(value.inputManifest.manifestId, 'inputManifest.manifestId')
  digest(value.inputManifest.expectedManifestDigest, 'inputManifest.expectedManifestDigest')

  exactKeys(value.pipeline, ['pipelineId', 'configurationDigest', 'provider'], ['languageHint'], '$.pipeline')
  slug(value.pipeline.pipelineId, 'pipeline.pipelineId')
  digest(value.pipeline.configurationDigest, 'pipeline.configurationDigest')
  exactKeys(value.pipeline.provider, ['providerId', 'model', 'modelVersion'], [], '$.pipeline.provider')
  slug(value.pipeline.provider.providerId, 'pipeline.provider.providerId')
  opaque(value.pipeline.provider.model, 'pipeline.provider.model', { max: 160 })
  opaque(value.pipeline.provider.modelVersion, 'pipeline.provider.modelVersion', { max: 160 })
  if (value.pipeline.languageHint !== undefined) languageTag(value.pipeline.languageHint, 'pipeline.languageHint')
  return value
}

export function validateClaimRequest(value) {
  exactKeys(value, ['schemaVersion', 'requestId', 'processingJobId', 'expectedGeneration'], [], '$')
  requestBase(value, 'gumi.media-processing.claim-attempt.v1')
  uuidV7(value.processingJobId, 'processingJobId')
  u64(value.expectedGeneration, 'expectedGeneration')
  return value
}

export function validateRenewRequest(value) {
  exactKeys(
    value,
    [
      'schemaVersion',
      'requestId',
      'processingJobId',
      'attemptId',
      'generation',
      'leaseId',
      'expectedLeaseRevision',
    ],
    [],
    '$',
  )
  requestBase(value, 'gumi.media-processing.renew-lease.v1')
  uuidV7(value.processingJobId, 'processingJobId')
  uuidV7(value.attemptId, 'attemptId')
  positiveU64(value.generation, 'generation')
  uuidV7(value.leaseId, 'leaseId')
  u64(value.expectedLeaseRevision, 'expectedLeaseRevision')
  return value
}

export function validateRetryRequest(value) {
  exactKeys(
    value,
    ['schemaVersion', 'requestId', 'processingJobId', 'expectedGeneration', 'acknowledgeOutcomeUnknown'],
    [],
    '$',
  )
  requestBase(value, 'gumi.media-processing.retry-job.v1')
  uuidV7(value.processingJobId, 'processingJobId')
  u64(value.expectedGeneration, 'expectedGeneration')
  if (typeof value.acknowledgeOutcomeUnknown !== 'boolean') invalid('acknowledgeOutcomeUnknown must be boolean')
  return value
}

export function validateCancelRequest(value) {
  exactKeys(value, ['schemaVersion', 'requestId', 'processingJobId', 'expectedGeneration'], [], '$')
  requestBase(value, 'gumi.media-processing.cancel-job.v1')
  uuidV7(value.processingJobId, 'processingJobId')
  u64(value.expectedGeneration, 'expectedGeneration')
  return value
}

function validateResult(result) {
  exactKeys(result, ['artifact', 'provenance', 'language', 'timing', 'segments'], [], '$.result')
  exactKeys(
    result.artifact,
    ['stagedArtifactHandle', 'contentDigest', 'byteLength', 'contentType'],
    [],
    '$.result.artifact',
  )
  opaque(result.artifact.stagedArtifactHandle, 'result.artifact.stagedArtifactHandle', { max: 512 })
  digest(result.artifact.contentDigest, 'result.artifact.contentDigest')
  positiveU64(result.artifact.byteLength, 'result.artifact.byteLength')
  literal(result.artifact.contentType, 'application/vnd.gumi.transcript+json', 'result.artifact.contentType')

  exactKeys(result.provenance, ['providerId', 'model', 'modelVersion'], [], '$.result.provenance')
  slug(result.provenance.providerId, 'result.provenance.providerId')
  opaque(result.provenance.model, 'result.provenance.model', { max: 160 })
  opaque(result.provenance.modelVersion, 'result.provenance.modelVersion', { max: 160 })

  exactKeys(result.language, ['tag', 'basis'], [], '$.result.language')
  languageTag(result.language.tag, 'result.language.tag')
  if (!['requested', 'detected', 'provider-default'].includes(result.language.basis)) {
    invalid('result.language.basis is not supported')
  }

  exactKeys(
    result.timing,
    ['providerStartedAt', 'providerCompletedAt', 'mediaDurationMs'],
    [],
    '$.result.timing',
  )
  utcTimestamp(result.timing.providerStartedAt, 'result.timing.providerStartedAt')
  utcTimestamp(result.timing.providerCompletedAt, 'result.timing.providerCompletedAt')
  if (Date.parse(result.timing.providerCompletedAt) < Date.parse(result.timing.providerStartedAt)) {
    invalid('result.timing.providerCompletedAt precedes providerStartedAt')
  }
  positiveU64(result.timing.mediaDurationMs, 'result.timing.mediaDurationMs')

  exactKeys(result.segments, ['count', 'timedCount'], ['firstStartMs', 'lastEndMs'], '$.result.segments')
  const count = u64(result.segments.count, 'result.segments.count')
  const timedCount = u64(result.segments.timedCount, 'result.segments.timedCount')
  if (timedCount > count) invalid('result.segments.timedCount exceeds count')
  if (timedCount === 0n) {
    if (result.segments.firstStartMs !== undefined || result.segments.lastEndMs !== undefined) {
      invalid('untimed results cannot declare segment timing bounds')
    }
  } else {
    const first = u64(result.segments.firstStartMs, 'result.segments.firstStartMs')
    const last = u64(result.segments.lastEndMs, 'result.segments.lastEndMs')
    if (last < first || last > u64(result.timing.mediaDurationMs, 'result.timing.mediaDurationMs')) {
      invalid('segment timing bounds fall outside the media duration')
    }
  }
}

export function validateCompletionRequest(value) {
  exactKeys(
    value,
    ['schemaVersion', 'callbackId', 'processingJobId', 'attemptId', 'generation', 'outcome'],
    ['result', 'failure'],
    '$',
  )
  requestBase(value, 'gumi.media-processing.complete-attempt.v1')
  uuidV7(value.processingJobId, 'processingJobId')
  uuidV7(value.attemptId, 'attemptId')
  positiveU64(value.generation, 'generation')
  if (!['succeeded', 'retryable-failure', 'permanent-failure'].includes(value.outcome)) {
    invalid('outcome is not supported')
  }
  if (value.outcome === 'succeeded') {
    if (value.failure !== undefined) invalid('a successful completion cannot include failure facts')
    validateResult(value.result)
  } else {
    if (value.result !== undefined) invalid('a failed completion cannot include artifact result facts')
    exactKeys(value.failure, ['code'], ['retryAfterSeconds'], '$.failure')
    if (typeof value.failure.code !== 'string' || !/^[A-Z][A-Z0-9_]{1,63}$/.test(value.failure.code)) {
      invalid('failure.code must be a stable uppercase code')
    }
    if (!FAILURE_CODES_BY_OUTCOME[value.outcome].includes(value.failure.code)) {
      invalid(`failure.code is not valid for ${value.outcome}`)
    }
    if (value.outcome === 'permanent-failure' && value.failure.retryAfterSeconds !== undefined) {
      invalid('permanent failure cannot declare retryAfterSeconds')
    }
    if (value.failure.retryAfterSeconds !== undefined) {
      const retry = Number(positiveU64(value.failure.retryAfterSeconds, 'failure.retryAfterSeconds'))
      if (!Number.isSafeInteger(retry) || retry > 86_400) invalid('failure.retryAfterSeconds exceeds one day')
    }
  }
  return value
}

export function controlPrincipal(value) {
  exactKeys(value, ['kind', 'callerId'], [], 'principal')
  literal(value.kind, 'control', 'principal.kind')
  opaque(value.callerId, 'principal.callerId')
  return value
}

export function workerPrincipal(value) {
  exactKeys(value, ['kind', 'workerId', 'allowedPipelineIds'], [], 'principal')
  literal(value.kind, 'worker', 'principal.kind')
  opaque(value.workerId, 'principal.workerId')
  if (!Array.isArray(value.allowedPipelineIds) || value.allowedPipelineIds.length < 1 || value.allowedPipelineIds.length > 64) {
    invalid('principal.allowedPipelineIds must be a non-empty bounded array')
  }
  const unique = new Set(value.allowedPipelineIds)
  if (unique.size !== value.allowedPipelineIds.length) invalid('principal.allowedPipelineIds must be unique')
  value.allowedPipelineIds.forEach((pipelineId) => slug(pipelineId, 'principal.allowedPipelineIds item'))
  return value
}

export function callbackPrincipal(value) {
  exactKeys(value, ['kind', 'providerId', 'processingJobId', 'attemptId'], [], 'principal')
  literal(value.kind, 'provider-callback', 'principal.kind')
  slug(value.providerId, 'principal.providerId')
  uuidV7(value.processingJobId, 'principal.processingJobId')
  uuidV7(value.attemptId, 'principal.attemptId')
  return value
}
