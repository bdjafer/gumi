import { fail, invalid } from './error.mjs'
import {
  boolean,
  codecConfigurationId,
  exactObject,
  integer,
  literal,
  sha256,
  string,
  timestamp,
  u64,
  uuidV7,
} from './primitives.mjs'
import { rangeSize, validateRange } from './ranges.mjs'

const DISCONTINUITY_REASONS = new Set([
  'device-ring-overwrite',
  'source-restart',
  'source-reported-gap',
  'edge-spool-loss',
  'unknown',
])

const OGG_SERIAL_MAX = 4_294_967_295n
const OGG_AUDIO_SEQUENCE_SPAN_MAX = OGG_SERIAL_MAX - 2n
const OPUS_PRE_SKIP_MAX = 65_535n
const M1_SINGLE_PACKET_PAGE_PROFILE = 'gumi.ogg-opus.single-packet-page.v1'

export function validateCodec(value, path) {
  exactObject(
    value,
    ['name', 'configurationId', 'sampleRateHz', 'channelCount', 'frameDurationUs'],
    [],
    path,
  )
  literal(value.name, 'opus', `${path}.name`)
  codecConfigurationId(value.configurationId, `${path}.configurationId`)
  integer(value.sampleRateHz, `${path}.sampleRateHz`, 8000, 48000)
  integer(value.channelCount, `${path}.channelCount`, 1, 2)
  if (![2500, 5000, 10000, 20000, 40000, 60000].includes(value.frameDurationUs)) {
    invalid(`${path}.frameDurationUs`, 'unsupported Opus frame duration')
  }
}

export function validateOggLayout(value, path) {
  exactObject(value, ['profile', 'serialNumber', 'preSkip48kSamples'], [], path)
  literal(value.profile, M1_SINGLE_PACKET_PAGE_PROFILE, `${path}.profile`)
  const serialNumber = u64(value.serialNumber, `${path}.serialNumber`)
  if (serialNumber > OGG_SERIAL_MAX) invalid(`${path}.serialNumber`, 'Ogg serial number exceeds unsigned 32-bit')
  const preSkip = u64(value.preSkip48kSamples, `${path}.preSkip48kSamples`)
  if (preSkip > OPUS_PRE_SKIP_MAX) invalid(`${path}.preSkip48kSamples`, 'Opus pre-skip exceeds unsigned 16-bit')
}

export function validateStreamPolicy(value, path) {
  exactObject(
    value,
    ['streamId', 'kind', 'sequencePolicy', 'maxChunkBytes', 'maxTotalBytes', 'codec', 'oggLayout'],
    [],
    path,
  )
  uuidV7(value.streamId, `${path}.streamId`)
  literal(value.kind, 'audio', `${path}.kind`)
  exactObject(value.sequencePolicy, ['first', 'maximumLast'], [], `${path}.sequencePolicy`)
  const first = u64(value.sequencePolicy.first, `${path}.sequencePolicy.first`)
  const maximumLast = u64(value.sequencePolicy.maximumLast, `${path}.sequencePolicy.maximumLast`)
  if (first > maximumLast) fail('INVALID_SEQUENCE_RANGE', 400, `${path}.sequencePolicy has an inverted range`)
  if (maximumLast - first > OGG_AUDIO_SEQUENCE_SPAN_MAX) {
    fail(
      'INVALID_SEQUENCE_RANGE',
      400,
      `${path}.sequencePolicy cannot map to the deterministic Ogg page-sequence space`,
    )
  }
  const maxChunkBytes = u64(value.maxChunkBytes, `${path}.maxChunkBytes`, { positive: true })
  const maxTotalBytes = u64(value.maxTotalBytes, `${path}.maxTotalBytes`, { positive: true })
  if (maxChunkBytes > maxTotalBytes) invalid(path, 'maxChunkBytes must not exceed maxTotalBytes')
  validateCodec(value.codec, `${path}.codec`)
  validateOggLayout(value.oggLayout, `${path}.oggLayout`)
}

export function validateCreateRequest(value) {
  exactObject(
    value,
    [
      'schemaVersion',
      'requestId',
      'captureSessionId',
      'deviceId',
      'edgeHostId',
      'primaryStreamId',
      'sessionExpiresInSeconds',
      'credentialExpiresInSeconds',
      'streams',
    ],
  )
  literal(value.schemaVersion, 'gumi.media-ingest.create-session.v1', '$.schemaVersion')
  for (const key of ['requestId', 'captureSessionId', 'deviceId', 'edgeHostId', 'primaryStreamId']) {
    uuidV7(value[key], `$.${key}`)
  }
  integer(value.sessionExpiresInSeconds, '$.sessionExpiresInSeconds', 60, 86_400)
  integer(value.credentialExpiresInSeconds, '$.credentialExpiresInSeconds', 60, 3_600)
  if (!Array.isArray(value.streams) || value.streams.length < 1 || value.streams.length > 4) {
    invalid('$.streams', 'expected 1..4 streams')
  }
  value.streams.forEach((stream, index) => validateStreamPolicy(stream, `$.streams[${index}]`))
  const streamIds = value.streams.map((stream) => stream.streamId)
  if (new Set(streamIds).size !== streamIds.length) invalid('$.streams', 'streamId values must be unique')
  if (!streamIds.includes(value.primaryStreamId)) invalid('$.primaryStreamId', 'must identify exactly one declared stream')
}

export function validateRefreshRequest(value) {
  exactObject(value, ['schemaVersion', 'requestId', 'expiresInSeconds'])
  literal(value.schemaVersion, 'gumi.media-ingest.refresh-credential.v1', '$.schemaVersion')
  uuidV7(value.requestId, '$.requestId')
  integer(value.expiresInSeconds, '$.expiresInSeconds', 60, 3_600)
}

export function validateChunkDescriptor(value) {
  exactObject(
    value,
    [
      'schemaVersion',
      'ingestSessionId',
      'streamId',
      'chunkId',
      'sequenceRange',
      'payloadBytes',
      'payloadFormat',
      'contentDigest',
      'codecConfigurationId',
      'sourceStartedAt',
      'sourceRetransmission',
    ],
    ['edgeReceivedAt', 'sourceDiscontinuityBefore'],
  )
  literal(value.schemaVersion, 'gumi.media-ingest.chunk.v1', '$.schemaVersion')
  uuidV7(value.ingestSessionId, '$.ingestSessionId')
  uuidV7(value.streamId, '$.streamId')
  uuidV7(value.chunkId, '$.chunkId')
  const range = validateRange(value.sequenceRange, '$.sequenceRange')
  u64(value.payloadBytes, '$.payloadBytes', { positive: true })
  literal(value.payloadFormat, 'ogg-opus-page-fragment-v1', '$.payloadFormat')
  sha256(value.contentDigest, '$.contentDigest')
  codecConfigurationId(value.codecConfigurationId, '$.codecConfigurationId')
  timestamp(value.sourceStartedAt, '$.sourceStartedAt')
  if (value.edgeReceivedAt !== undefined) timestamp(value.edgeReceivedAt, '$.edgeReceivedAt')
  boolean(value.sourceRetransmission, '$.sourceRetransmission')
  if (value.sourceDiscontinuityBefore !== undefined) {
    const discontinuity = value.sourceDiscontinuityBefore
    exactObject(discontinuity, ['reason', 'droppedFrameCount'], [], '$.sourceDiscontinuityBefore')
    string(discontinuity.reason, '$.sourceDiscontinuityBefore.reason')
    if (!DISCONTINUITY_REASONS.has(discontinuity.reason)) {
      invalid('$.sourceDiscontinuityBefore.reason', 'unsupported discontinuity reason')
    }
    u64(discontinuity.droppedFrameCount, '$.sourceDiscontinuityBefore.droppedFrameCount')
  }
  return range
}

export function validateFinalizationRequest(value) {
  exactObject(value, ['schemaVersion', 'requestId', 'streams'])
  literal(value.schemaVersion, 'gumi.media-ingest.finalize.v1', '$.schemaVersion')
  uuidV7(value.requestId, '$.requestId')
  if (!Array.isArray(value.streams) || value.streams.length < 1 || value.streams.length > 4) {
    invalid('$.streams', 'expected 1..4 streams')
  }
  value.streams.forEach((stream, index) => {
    const path = `$.streams[${index}]`
    exactObject(
      stream,
      ['streamId', 'expectedRange', 'expectedChunkCount', 'expectedByteLength', 'expectedContentDigest'],
      [],
      path,
    )
    uuidV7(stream.streamId, `${path}.streamId`)
    validateRange(stream.expectedRange, `${path}.expectedRange`)
    u64(stream.expectedChunkCount, `${path}.expectedChunkCount`, { positive: true })
    u64(stream.expectedByteLength, `${path}.expectedByteLength`, { positive: true })
    sha256(stream.expectedContentDigest, `${path}.expectedContentDigest`)
  })
}

export function assertDescriptorWithinPolicy(descriptor, policy, range) {
  const first = BigInt(policy.sequencePolicy.first)
  const maximumLast = BigInt(policy.sequencePolicy.maximumLast)
  if (range.first < first || range.last > maximumLast) {
    fail('INVALID_SEQUENCE_RANGE', 400, 'The chunk sequence range is outside the authorized stream policy.', {
      sequenceRange: descriptor.sequenceRange,
      sequencePolicy: policy.sequencePolicy,
    })
  }
  if (descriptor.codecConfigurationId !== policy.codec.configurationId) {
    fail('CHUNK_METADATA_CONFLICT', 409, 'The chunk codec configuration differs from the scoped stream policy.')
  }
  return rangeSize(range)
}
