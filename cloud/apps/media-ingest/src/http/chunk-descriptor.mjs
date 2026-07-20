import { invalidHttpRequest } from './errors.mjs'
import { parseCanonicalU64Header, parseContentDigest, singleHeader } from './headers.mjs'

function required(request, name) {
  return singleHeader(request, name, { required: true })
}

function optional(request, name) {
  return singleHeader(request, name)
}

export function chunkDescriptorFromRequest(request, { ingestSessionId, streamId, chunkId }) {
  const retransmission = required(request, 'Gumi-Source-Retransmission')
  if (retransmission !== 'true' && retransmission !== 'false') {
    throw invalidHttpRequest('Gumi-Source-Retransmission must be exactly true or false.')
  }

  const discontinuityReason = optional(request, 'Gumi-Discontinuity-Reason')
  const droppedFrameCount = optional(request, 'Gumi-Dropped-Frame-Count')
  if ((discontinuityReason === undefined) !== (droppedFrameCount === undefined)) {
    throw invalidHttpRequest(
      'Gumi-Discontinuity-Reason and Gumi-Dropped-Frame-Count must either both be present or both be absent.',
    )
  }

  const descriptor = {
    schemaVersion: 'gumi.media-ingest.chunk.v1',
    ingestSessionId,
    streamId,
    chunkId,
    sequenceRange: {
      first: required(request, 'Gumi-Sequence-First'),
      last: required(request, 'Gumi-Sequence-Last'),
    },
    payloadBytes: required(request, 'Gumi-Payload-Bytes'),
    payloadFormat: required(request, 'Gumi-Payload-Format'),
    contentDigest: parseContentDigest(required(request, 'Content-Digest')),
    codecConfigurationId: required(request, 'Gumi-Codec-Configuration-Id'),
    sourceStartedAt: required(request, 'Gumi-Source-Started-At'),
    sourceRetransmission: retransmission === 'true',
  }
  parseCanonicalU64Header(descriptor.sequenceRange.first, 'Gumi-Sequence-First')
  parseCanonicalU64Header(descriptor.sequenceRange.last, 'Gumi-Sequence-Last')
  parseCanonicalU64Header(descriptor.payloadBytes, 'Gumi-Payload-Bytes', { positive: true })

  const edgeReceivedAt = optional(request, 'Gumi-Edge-Received-At')
  if (edgeReceivedAt !== undefined) descriptor.edgeReceivedAt = edgeReceivedAt
  if (discontinuityReason !== undefined) {
    parseCanonicalU64Header(droppedFrameCount, 'Gumi-Dropped-Frame-Count')
    descriptor.sourceDiscontinuityBefore = {
      reason: discontinuityReason,
      droppedFrameCount,
    }
  }
  return descriptor
}
