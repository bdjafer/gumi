import { createHash } from 'node:crypto'

import { IngestError, fail, invalid } from './error.mjs'
import { createOpusStreamInspector, validateOggChunk } from './ogg.mjs'
import {
  addSeconds,
  assertDigest,
  byteDigest,
  clone,
  minTimestamp,
  sha256,
  u64,
  utcTimestamp,
  valueDigest,
  uuidV7,
} from './primitives.mjs'
import { canonicalRanges, missingRanges, overlaps, rangeSize, streamSnapshot, validateRange, wireRange } from './ranges.mjs'
import {
  assertDescriptorWithinPolicy,
  validateChunkDescriptor,
  validateCreateRequest,
  validateFinalizationRequest,
  validateRefreshRequest,
} from './validation.mjs'
import { assertPorts } from '../ports.mjs'

const DEFAULT_RETENTION_SECONDS = 7 * 24 * 60 * 60
const DEFAULT_HARD_MAX_CHUNK_BYTES = 1_048_576n
const DEFAULT_HARD_MAX_STREAM_BYTES = 268_435_456n
const DEFAULT_HARD_MAX_SESSION_BYTES = 536_870_912n

function boundedPositiveBigInt(value, label) {
  if (typeof value !== 'bigint' || value <= 0n || value > 18_446_744_073_709_551_615n) {
    throw new TypeError(`${label} must be a positive unsigned 64-bit bigint`)
  }
  return value
}

function generatedId(value, purpose) {
  try {
    return uuidV7(value, purpose)
  } catch (cause) {
    throw new IngestError(
      'DURABILITY_UNAVAILABLE',
      503,
      `The ${purpose} port returned an invalid opaque identity.`,
      undefined,
      cause,
    )
  }
}

function issuedCredential(value, expected) {
  const credential = value?.credential
  if (
    !credential ||
    credential.tokenType !== 'Bearer' ||
    typeof credential.accessToken !== 'string' ||
    credential.accessToken.length < 32 ||
    credential.accessToken.length > 8192 ||
    credential.issuedAt !== expected.issuedAt ||
    credential.expiresAt !== expected.expiresAt
  ) {
    throw new IngestError('DURABILITY_UNAVAILABLE', 503, 'The token port returned an invalid bounded credential.')
  }
  return clone(credential)
}

function missingSession(id) {
  fail('INGEST_SESSION_NOT_FOUND', 404, 'The scoped ingest session does not exist.', { ingestSessionId: id })
}

function streamNotFound(sessionId, streamId) {
  fail('STREAM_NOT_FOUND', 404, 'The scoped stream does not exist.', { ingestSessionId: sessionId, streamId })
}

function expired(session) {
  fail('INGEST_SESSION_EXPIRED', 409, 'The ingest session has expired.', {
    ingestSessionId: session.id,
    sessionExpiresAt: session.sessionExpiresAt,
    retainedUntil: session.retainedUntil,
  })
}

function sessionBinding(session) {
  return {
    ingestSessionId: session.id,
    captureSessionId: session.request.captureSessionId,
    deviceId: session.request.deviceId,
    edgeHostId: session.request.edgeHostId,
    primaryStreamId: session.request.primaryStreamId,
    streams: session.request.streams.map((stream) => ({
      streamId: stream.streamId,
      sequencePolicy: clone(stream.sequencePolicy),
      codec: clone(stream.codec),
      oggLayout: clone(stream.oggLayout),
    })),
  }
}

function createResponse(session, credential) {
  return {
    schemaVersion: 'gumi.media-ingest.session.v1',
    requestId: session.request.requestId,
    ingestSessionId: session.id,
    captureSessionId: session.request.captureSessionId,
    deviceId: session.request.deviceId,
    edgeHostId: session.request.edgeHostId,
    primaryStreamId: session.request.primaryStreamId,
    state: 'open',
    sessionExpiresAt: session.sessionExpiresAt,
    retainedUntil: session.retainedUntil,
    credential,
    streams: clone(session.request.streams),
  }
}

function policyFor(session, streamId) {
  return session.request.streams.find((stream) => stream.streamId === streamId)
}

function streamStateFor(session, streamId) {
  return session.streamStates.get(streamId)
}

function isPastOrEqual(now, deadline) {
  return Date.parse(now) >= Date.parse(deadline)
}

function incrementRevision(session, now) {
  session.revision += 1n
  session.updatedAt = now
}

function statusResponse(session) {
  return {
    schemaVersion: 'gumi.media-ingest.status.v1',
    ingestSessionId: session.id,
    captureSessionId: session.request.captureSessionId,
    state: session.state,
    stateRevision: String(session.revision),
    sessionExpiresAt: session.sessionExpiresAt,
    retainedUntil: session.retainedUntil,
    streams: session.request.streams.map((policy) =>
      streamSnapshot(policy, streamStateFor(session, policy.streamId).chunks),
    ),
    manifestId: session.manifestId,
    updatedAt: session.updatedAt,
  }
}

function ackResponse(session, descriptor, disposition, now) {
  const snapshot = streamSnapshot(policyFor(session, descriptor.streamId), streamStateFor(session, descriptor.streamId).chunks)
  return {
    schemaVersion: 'gumi.media-ingest.ack.v1',
    ingestSessionId: session.id,
    streamId: descriptor.streamId,
    acknowledgedChunkId: descriptor.chunkId,
    acknowledgedContentDigest: descriptor.contentDigest,
    acknowledgedDescriptorDigest: valueDigest(descriptor),
    acknowledgedSequenceRange: clone(descriptor.sequenceRange),
    disposition,
    committedRanges: snapshot.committedRanges,
    missingRanges: snapshot.missingRanges,
    accountedRange: snapshot.accountedRange,
    durableThrough: snapshot.durableThrough,
    stateRevision: String(session.revision),
    sessionState: session.state,
    acknowledgedAt: now,
  }
}

function chunkBodyKey(descriptor) {
  return `${descriptor.ingestSessionId}/${descriptor.streamId}/${descriptor.chunkId}`
}

function sameSet(left, right) {
  if (left.length !== right.length) return false
  const expected = new Set(right)
  return left.every((value) => expected.has(value))
}

function assertTerminalOrdering(session, policy, streamState, range, containerFacts) {
  for (const record of streamState.chunks.values()) {
    const durableRange = validateRange(record.descriptor.sequenceRange)
    if (record.containerFacts?.endsLogicalStream && range.first > durableRange.last) {
      fail('CHUNK_METADATA_CONFLICT', 409, 'The chunk appears after the durable end-of-stream sequence.', {
        ingestSessionId: session.id,
        streamId: policy.streamId,
        terminalSequence: record.containerFacts.terminalSequence,
        receivedSequence: String(range.first),
      })
    }
    if (containerFacts.endsLogicalStream && durableRange.last > range.last) {
      fail('CHUNK_METADATA_CONFLICT', 409, 'The end-of-stream page precedes an already durable sequence.', {
        ingestSessionId: session.id,
        streamId: policy.streamId,
        terminalSequence: containerFacts.terminalSequence,
        durableSequenceAfterTerminal: String(durableRange.last),
      })
    }
  }
}

function assertFinalizationMetadata(session, request) {
  const declaredIds = request.streams.map((stream) => stream.streamId)
  const requiredIds = session.request.streams.map((stream) => stream.streamId)
  if (new Set(declaredIds).size !== declaredIds.length || !sameSet(declaredIds, requiredIds)) {
    fail('FINALIZATION_STREAM_SET_MISMATCH', 409, 'Finalization must declare every scoped stream exactly once.', {
      ingestSessionId: session.id,
      declaredStreamIds: declaredIds,
      requiredStreamIds: requiredIds,
    })
  }

  const byId = new Map(request.streams.map((stream) => [stream.streamId, stream]))
  return session.request.streams.map((policy) => {
    const expected = byId.get(policy.streamId)
    const expectedRange = validateRange(expected.expectedRange, `stream ${policy.streamId}.expectedRange`)
    const policyFirst = BigInt(policy.sequencePolicy.first)
    const maximumLast = BigInt(policy.sequencePolicy.maximumLast)
    if (expectedRange.first !== policyFirst || expectedRange.last > maximumLast) {
      fail('FINALIZATION_RANGE_CONFLICT', 409, 'Finalization range differs from the authorized sequence policy.', {
        ingestSessionId: session.id,
        streamId: policy.streamId,
        declaredExpectedRange: expected.expectedRange,
        sequencePolicy: policy.sequencePolicy,
      })
    }

    const records = [...streamStateFor(session, policy.streamId).chunks.values()]
    const committed = canonicalRanges(records)
    const durableLast = committed.at(-1)?.last
    if (durableLast !== undefined && durableLast > expectedRange.last) {
      fail('FINALIZATION_RANGE_CONFLICT', 409, 'Durable data exists beyond the declared terminal range.', {
        ingestSessionId: session.id,
        streamId: policy.streamId,
        declaredExpectedRange: expected.expectedRange,
        durableRanges: committed.map(wireRange),
      })
    }
    const gaps = missingRanges(expectedRange.first, expectedRange.last, committed)
    if (gaps.length > 0) {
      fail('SEQUENCE_GAP', 409, 'Finalization cannot commit a stream with missing sequence ranges.', {
        ingestSessionId: session.id,
        streamId: policy.streamId,
        missingRanges: gaps.map(wireRange),
      })
    }
    const storedBytes = records.reduce((sum, record) => sum + BigInt(record.descriptor.payloadBytes), 0n)
    if (BigInt(expected.expectedChunkCount) !== BigInt(records.length) || BigInt(expected.expectedByteLength) !== storedBytes) {
      fail('FINALIZATION_RANGE_CONFLICT', 409, 'Finalization counters differ from durable stream state.', {
        ingestSessionId: session.id,
        streamId: policy.streamId,
        durableChunkCount: String(records.length),
        durableByteLength: String(storedBytes),
      })
    }
    const terminalRecords = records.filter((record) => record.containerFacts?.endsLogicalStream)
    if (
      terminalRecords.length !== 1 ||
      BigInt(terminalRecords[0].descriptor.sequenceRange.last) !== expectedRange.last
    ) {
      fail('FINALIZATION_RANGE_CONFLICT', 409, 'Finalization terminal range does not carry the unique end-of-stream page.', {
        ingestSessionId: session.id,
        streamId: policy.streamId,
        expectedTerminalSequence: String(expectedRange.last),
        durableTerminalSequences: terminalRecords.map((record) => record.descriptor.sequenceRange.last),
      })
    }
    return {
      policy,
      expected,
      expectedRange,
      records: records.sort((left, right) => {
        const l = BigInt(left.descriptor.sequenceRange.first)
        const r = BigInt(right.descriptor.sequenceRange.first)
        return l < r ? -1 : l > r ? 1 : 0
      }),
    }
  })
}

function finalizedResponse(session, disposition = 'already-finalized') {
  return {
    schemaVersion: 'gumi.media-ingest.finalized.v1',
    requestId: session.finalization.request.requestId,
    disposition,
    manifestDigest: session.manifestDigest,
    manifest: clone(session.manifest),
  }
}

function terminalReplay(session, requestFingerprint, request) {
  if (session.finalization.fingerprint !== requestFingerprint) {
    fail('SESSION_ALREADY_FINALIZED', 409, 'The immutable finalization is already bound to a different request.', {
      ingestSessionId: session.id,
      manifestId: session.manifestId,
      existingManifestDigest: session.manifestDigest,
      receivedExpectedContentDigest: request.streams[0]?.expectedContentDigest,
    })
  }
  return finalizedResponse(session)
}

export class MediaIngestService {
  constructor({
    storage,
    clock,
    tokens,
    unfinalizedRetentionSeconds = DEFAULT_RETENTION_SECONDS,
    hardMaxChunkBytes = DEFAULT_HARD_MAX_CHUNK_BYTES,
    hardMaxStreamBytes = DEFAULT_HARD_MAX_STREAM_BYTES,
    hardMaxSessionBytes = DEFAULT_HARD_MAX_SESSION_BYTES,
  }) {
    assertPorts({ storage, clock, tokens })
    if (!Number.isSafeInteger(unfinalizedRetentionSeconds) || unfinalizedRetentionSeconds < 0) {
      throw new TypeError('unfinalizedRetentionSeconds must be a non-negative safe integer')
    }
    this.storage = storage
    this.clock = clock
    this.tokens = tokens
    this.unfinalizedRetentionSeconds = unfinalizedRetentionSeconds
    this.hardMaxChunkBytes = boundedPositiveBigInt(hardMaxChunkBytes, 'hardMaxChunkBytes')
    this.hardMaxStreamBytes = boundedPositiveBigInt(hardMaxStreamBytes, 'hardMaxStreamBytes')
    this.hardMaxSessionBytes = boundedPositiveBigInt(hardMaxSessionBytes, 'hardMaxSessionBytes')
    if (this.hardMaxChunkBytes > this.hardMaxStreamBytes || this.hardMaxStreamBytes > this.hardMaxSessionBytes) {
      throw new TypeError('hard media limits must be ordered chunk <= stream <= session')
    }
  }

  async createSession(request) {
    validateCreateRequest(request)
    let requestedSessionBytes = 0n
    for (const [index, stream] of request.streams.entries()) {
      const maxChunkBytes = BigInt(stream.maxChunkBytes)
      const maxTotalBytes = BigInt(stream.maxTotalBytes)
      if (maxChunkBytes > this.hardMaxChunkBytes) {
        invalid(`$.streams[${index}].maxChunkBytes`, `exceeds server hard cap ${this.hardMaxChunkBytes}`)
      }
      if (maxTotalBytes > this.hardMaxStreamBytes) {
        invalid(`$.streams[${index}].maxTotalBytes`, `exceeds server hard cap ${this.hardMaxStreamBytes}`)
      }
      requestedSessionBytes += maxTotalBytes
    }
    if (requestedSessionBytes > this.hardMaxSessionBytes) {
      invalid('$.streams', `aggregate maxTotalBytes exceeds server hard cap ${this.hardMaxSessionBytes}`)
    }
    const fingerprint = valueDigest(request)
    const now = this.clock.now()
    const candidateId = await this.tokens.newOpaqueId('ingest-session')
    generatedId(candidateId, 'tokens.newOpaqueId(ingest-session)')
    const sessionExpiresAt = addSeconds(now, request.sessionExpiresInSeconds)
    const retainedUntil = addSeconds(sessionExpiresAt, this.unfinalizedRetentionSeconds)
    const initialCredentialExpiresAt = minTimestamp(
      addSeconds(now, request.credentialExpiresInSeconds),
      sessionExpiresAt,
    )

    const outcome = await this.storage.transaction('create-session', (transaction) => {
      const binding = transaction.getCreateRequest(request.requestId)
      if (binding) {
        if (binding.fingerprint !== fingerprint) {
          fail('REQUEST_ID_CONFLICT', 409, 'The create requestId is already bound to another request.', {
            requestId: request.requestId,
            ingestSessionId: binding.ingestSessionId,
          })
        }
        return { disposition: 'replay', session: transaction.getSession(binding.ingestSessionId) }
      }
      const streamStates = new Map(request.streams.map((stream) => [stream.streamId, { chunks: new Map() }]))
      const session = {
        id: candidateId,
        request: clone(request),
        createFingerprint: fingerprint,
        createdAt: now,
        updatedAt: now,
        state: 'open',
        revision: 0n,
        sessionExpiresAt,
        retainedUntil,
        initialCredential: { issuedAt: now, expiresAt: initialCredentialExpiresAt },
        streamStates,
        finalization: null,
        manifestId: null,
        manifestDigest: null,
        manifest: null,
      }
      transaction.putSession(session)
      transaction.bindCreateRequest(request.requestId, {
        fingerprint,
        ingestSessionId: candidateId,
      })
      return { disposition: 'created', session }
    })

    const issued = await this.tokens.issueCredential({
      idempotencyKey: `initial:${outcome.session.id}`,
      binding: sessionBinding(outcome.session),
      issuedAt: outcome.session.initialCredential.issuedAt,
      expiresAt: outcome.session.initialCredential.expiresAt,
    })
    const credential = issuedCredential(issued, outcome.session.initialCredential)
    return { disposition: outcome.disposition, response: createResponse(outcome.session, credential) }
  }

  async refreshCredential(ingestSessionId, request) {
    validateRefreshRequest(request)
    const session = await this.storage.readSession(ingestSessionId)
    if (!session) missingSession(ingestSessionId)
    const now = this.clock.now()
    if (isPastOrEqual(now, session.sessionExpiresAt) || session.state === 'expired') expired(session)
    const expiresAt = minTimestamp(addSeconds(now, request.expiresInSeconds), session.sessionExpiresAt)
    const issued = await this.tokens.issueCredential({
      binding: sessionBinding(session),
      issuedAt: now,
      expiresAt,
    })
    const credential = issuedCredential(issued, { issuedAt: now, expiresAt })
    return {
      schemaVersion: 'gumi.media-ingest.refreshed-credential.v1',
      requestId: request.requestId,
      ingestSessionId: session.id,
      sessionExpiresAt: session.sessionExpiresAt,
      credential,
    }
  }

  async getStatus(ingestSessionId) {
    let session = await this.storage.readSession(ingestSessionId)
    if (!session) missingSession(ingestSessionId)
    const now = this.clock.now()
    if (session.state === 'open' && isPastOrEqual(now, session.sessionExpiresAt)) {
      session = await this.storage.transaction('expire-session', (transaction) => {
        const current = transaction.getSession(ingestSessionId)
        if (current.state === 'open' && isPastOrEqual(now, current.sessionExpiresAt)) {
          current.state = 'expired'
          incrementRevision(current, now)
          transaction.putSession(current)
        }
        return current
      })
    }
    return statusResponse(session)
  }

  async putChunk(descriptor, body) {
    const range = validateChunkDescriptor(descriptor)
    if (!(Buffer.isBuffer(body) || body instanceof Uint8Array)) invalid('body', 'expected binary bytes')
    if (BigInt(body.byteLength) > this.hardMaxChunkBytes) {
      fail('CHUNK_TOO_LARGE', 413, 'The request body exceeds the server hard maximum.', {
        hardMaxChunkBytes: String(this.hardMaxChunkBytes),
        receivedBytes: String(body.byteLength),
      })
    }
    const bytes = Buffer.from(body)
    const declaredLength = u64(descriptor.payloadBytes, '$.payloadBytes', { positive: true })
    if (declaredLength !== BigInt(bytes.length)) {
      fail('CONTENT_LENGTH_MISMATCH', 422, 'The request body length differs from Gumi-Payload-Bytes.', {
        declaredBytes: descriptor.payloadBytes,
        receivedBytes: String(bytes.length),
      })
    }
    assertDigest(bytes, descriptor.contentDigest)
    const descriptorDigest = valueDigest(descriptor)
    const now = this.clock.now()

    const outcome = await this.storage.transaction('store-chunk', (transaction) => {
      const session = transaction.getSession(descriptor.ingestSessionId)
      if (!session) return { kind: 'missing' }
      if (session.state === 'open' && isPastOrEqual(now, session.sessionExpiresAt)) {
        session.state = 'expired'
        incrementRevision(session, now)
        transaction.putSession(session)
        return { kind: 'expired', session }
      }
      if (session.state === 'expired') return { kind: 'expired', session }
      if (session.state !== 'open') {
        return { kind: 'terminal', session }
      }
      const policy = policyFor(session, descriptor.streamId)
      if (!policy) return { kind: 'stream-missing', session }
      const expectedAudioPackets = assertDescriptorWithinPolicy(descriptor, policy, range)
      if (declaredLength > BigInt(policy.maxChunkBytes)) {
        fail('CHUNK_TOO_LARGE', 413, 'The chunk exceeds its scoped maximum byte length.', {
          maxChunkBytes: policy.maxChunkBytes,
          receivedBytes: descriptor.payloadBytes,
        })
      }
      const streamState = streamStateFor(session, descriptor.streamId)
      const existing = streamState.chunks.get(descriptor.chunkId)
      if (existing) {
        if (existing.descriptor.contentDigest !== descriptor.contentDigest) {
          fail('CHUNK_DIGEST_CONFLICT', 409, 'The chunk identity is already bound to different bytes.', {
            ingestSessionId: session.id,
            streamId: descriptor.streamId,
            chunkId: descriptor.chunkId,
            existingDigest: existing.descriptor.contentDigest,
            receivedDigest: descriptor.contentDigest,
          })
        }
        if (existing.descriptorDigest !== descriptorDigest) {
          fail('CHUNK_METADATA_CONFLICT', 409, 'The chunk identity is already bound to different metadata.', {
            ingestSessionId: session.id,
            streamId: descriptor.streamId,
            chunkId: descriptor.chunkId,
          })
        }
        return { kind: 'duplicate', session }
      }
      const parsed = validateOggChunk(bytes, {
        isFirst: range.first === BigInt(policy.sequencePolicy.first),
        expectedAudioPackets,
        codec: policy.codec,
        layout: policy.oggLayout,
        audioRange: range,
        firstAudioSequence: BigInt(policy.sequencePolicy.first),
      })
      assertTerminalOrdering(session, policy, streamState, range, parsed.containerFacts)
      for (const record of streamState.chunks.values()) {
        const existingRange = validateRange(record.descriptor.sequenceRange)
        if (overlaps(existingRange, range)) {
          fail('SEQUENCE_OVERLAP', 409, 'The chunk range overlaps another durable chunk identity.', {
            ingestSessionId: session.id,
            streamId: descriptor.streamId,
            chunkId: descriptor.chunkId,
            existingChunkId: record.descriptor.chunkId,
          })
        }
      }
      const totalBytes = [...streamState.chunks.values()].reduce(
        (sum, record) => sum + BigInt(record.descriptor.payloadBytes),
        declaredLength,
      )
      if (totalBytes > BigInt(policy.maxTotalBytes)) {
        fail('CHUNK_TOO_LARGE', 413, 'The chunk would exceed the stream total-byte policy.', {
          maxTotalBytes: policy.maxTotalBytes,
          resultingBytes: String(totalBytes),
        })
      }
      const bodyKey = chunkBodyKey(descriptor)
      transaction.putChunkBody(bodyKey, bytes)
      streamState.chunks.set(descriptor.chunkId, {
        descriptor: clone(descriptor),
        descriptorDigest,
        bodyKey,
        containerFacts: clone(parsed.containerFacts),
        committedAt: now,
      })
      incrementRevision(session, now)
      transaction.putSession(session)
      return { kind: 'stored', session }
    })

    if (outcome.kind === 'missing') missingSession(descriptor.ingestSessionId)
    if (outcome.kind === 'stream-missing') streamNotFound(descriptor.ingestSessionId, descriptor.streamId)
    if (outcome.kind === 'expired') expired(outcome.session)
    if (outcome.kind === 'terminal') {
      fail('SESSION_ALREADY_FINALIZED', 409, 'The ingest session no longer accepts chunks.', {
        ingestSessionId: descriptor.ingestSessionId,
        state: outcome.session.state,
      })
    }
    return ackResponse(outcome.session, descriptor, outcome.kind === 'duplicate' ? 'duplicate' : 'stored', now)
  }

  async #assemble(session, request) {
    const streams = assertFinalizationMetadata(session, request)
    const assembled = []
    for (const stream of streams) {
      const inspector = createOpusStreamInspector(stream.policy.codec)
      const hasher = createHash('sha256')
      const bodyKeys = []
      let byteLength = 0n
      for (const record of stream.records) {
        const body = await this.storage.readChunkBody(record.bodyKey)
        if (!body || byteDigest(body) !== record.descriptor.contentDigest || String(body.length) !== record.descriptor.payloadBytes) {
          fail('DURABILITY_UNAVAILABLE', 503, 'Durable chunk bytes are absent or fail their committed integrity facts.', {
            ingestSessionId: session.id,
            streamId: stream.policy.streamId,
            chunkId: record.descriptor.chunkId,
          })
        }
        const range = validateRange(record.descriptor.sequenceRange)
        const parsed = validateOggChunk(body, {
          isFirst: range.first === BigInt(stream.policy.sequencePolicy.first),
          expectedAudioPackets: rangeSize(range),
          codec: stream.policy.codec,
          layout: stream.policy.oggLayout,
          audioRange: range,
          firstAudioSequence: BigInt(stream.policy.sequencePolicy.first),
        })
        inspector.accept(parsed)
        hasher.update(body)
        byteLength += BigInt(body.length)
        bodyKeys.push(record.bodyKey)
      }
      const contentDigest = `sha256:${hasher.digest('hex')}`
      if (contentDigest !== stream.expected.expectedContentDigest) {
        fail('FINAL_DIGEST_MISMATCH', 422, 'The assembled immutable bytes differ from the expected content digest.', {
          ingestSessionId: session.id,
          streamId: stream.policy.streamId,
          expectedContentDigest: stream.expected.expectedContentDigest,
          computedContentDigest: contentDigest,
        })
      }
      if (String(byteLength) !== stream.expected.expectedByteLength) {
        fail('FINALIZATION_RANGE_CONFLICT', 409, 'Assembled bytes differ from the declared byte length.')
      }
      const inspection = inspector.finish(rangeSize(stream.expectedRange))
      const startedAt = utcTimestamp(stream.records[0].descriptor.sourceStartedAt)
      const endedAt = utcTimestamp(Date.parse(startedAt) + inspection.durationMs)
      const discontinuities = stream.records.flatMap((record) => {
        const evidence = record.descriptor.sourceDiscontinuityBefore
        return evidence
          ? [{ atSequence: record.descriptor.sequenceRange.first, reason: evidence.reason, droppedFrameCount: evidence.droppedFrameCount }]
          : []
      })
      assembled.push({
        ...stream,
        bodyKeys,
        byteLength,
        contentDigest,
        startedAt,
        endedAt,
        durationMs: inspection.durationMs,
        discontinuities,
      })
    }
    return assembled
  }

  async finalize(ingestSessionId, request) {
    validateFinalizationRequest(request)
    const fingerprint = valueDigest(request)
    let session = await this.storage.readSession(ingestSessionId)
    if (!session) missingSession(ingestSessionId)
    if (session.state === 'finalized') return terminalReplay(session, fingerprint, request)
    if (session.state === 'expired') expired(session)
    if (session.state === 'failed') fail('DURABILITY_UNAVAILABLE', 503, 'The ingest session is in a failed state.')

    if (session.state === 'open' && isPastOrEqual(this.clock.now(), session.sessionExpiresAt)) {
      await this.getStatus(ingestSessionId)
      expired(await this.storage.readSession(ingestSessionId))
    }

    if (session.state === 'open') await this.#assemble(session, request)
    const candidatePlan = {
      fingerprint,
      request: clone(request),
      manifestId: await this.tokens.newOpaqueId('manifest'),
      objectIds: Object.fromEntries(
        await Promise.all(
          session.request.streams.map(async (stream) => [stream.streamId, await this.tokens.newOpaqueId('media-object')]),
        ),
      ),
      finalizedAt: this.clock.now(),
    }
    generatedId(candidatePlan.manifestId, 'tokens.newOpaqueId(manifest)')
    for (const [streamId, objectId] of Object.entries(candidatePlan.objectIds)) {
      generatedId(objectId, `tokens.newOpaqueId(media-object:${streamId})`)
    }
    const expectedRevision = session.revision

    const reserved = await this.storage.transaction('reserve-finalization', (transaction) => {
      const current = transaction.getSession(ingestSessionId)
      if (!current) return { kind: 'missing' }
      if (current.state === 'finalized') return { kind: 'finalized', session: current }
      if (current.state === 'expired') return { kind: 'expired', session: current }
      if (current.state === 'finalizing') {
        if (current.finalization.fingerprint !== fingerprint) return { kind: 'conflict', session: current }
        return { kind: 'reserved', session: current }
      }
      if (current.state !== 'open') return { kind: 'failed', session: current }
      if (current.revision !== expectedRevision) return { kind: 'retry' }
      assertFinalizationMetadata(current, request)
      current.state = 'finalizing'
      current.finalization = candidatePlan
      incrementRevision(current, candidatePlan.finalizedAt)
      transaction.putSession(current)
      return { kind: 'reserved', session: current }
    })

    if (reserved.kind === 'missing') missingSession(ingestSessionId)
    if (reserved.kind === 'expired') expired(reserved.session)
    if (reserved.kind === 'finalized') return terminalReplay(reserved.session, fingerprint, request)
    if (reserved.kind === 'conflict') {
      fail('REQUEST_ID_CONFLICT', 409, 'Another finalization request already owns this ingest session.', {
        ingestSessionId,
        requestId: request.requestId,
      })
    }
    if (reserved.kind === 'failed') fail('DURABILITY_UNAVAILABLE', 503, 'The ingest session cannot be finalized.')
    if (reserved.kind === 'retry') return this.finalize(ingestSessionId, request)

    session = reserved.session
    const assembled = await this.#assemble(session, request)
    const manifestStreams = []
    for (const stream of assembled) {
      const mediaObjectId = session.finalization.objectIds[stream.policy.streamId]
      await this.storage.composeImmutableObject({
        mediaObjectId,
        contentType: 'audio/ogg; codecs=opus',
        contentDigest: stream.contentDigest,
        byteLength: String(stream.byteLength),
        orderedChunkBodyKeys: stream.bodyKeys,
      })
      manifestStreams.push({
        streamId: stream.policy.streamId,
        kind: 'audio',
        sequenceRange: clone(stream.expected.expectedRange),
        committedRanges: [clone(stream.expected.expectedRange)],
        chunkCount: stream.expected.expectedChunkCount,
        codec: clone(stream.policy.codec),
        oggLayout: clone(stream.policy.oggLayout),
        startedAt: stream.startedAt,
        endedAt: stream.endedAt,
        durationMs: stream.durationMs,
        discontinuities: stream.discontinuities,
        object: {
          mediaObjectId,
          contentType: 'audio/ogg; codecs=opus',
          byteLength: stream.expected.expectedByteLength,
          contentDigest: stream.contentDigest,
        },
      })
    }
    const manifest = {
      schemaVersion: 'gumi.media-manifest.v1',
      manifestId: session.finalization.manifestId,
      ingestSessionId: session.id,
      captureSessionId: session.request.captureSessionId,
      deviceId: session.request.deviceId,
      edgeHostId: session.request.edgeHostId,
      primaryStreamId: session.request.primaryStreamId,
      createdAt: session.createdAt,
      finalizedAt: session.finalization.finalizedAt,
      streams: manifestStreams,
    }
    const manifestDigest = valueDigest(manifest)

    const committed = await this.storage.transaction('commit-finalization', (transaction) => {
      const current = transaction.getSession(ingestSessionId)
      if (current.state === 'finalized') return { kind: 'replay', session: current }
      if (current.state !== 'finalizing' || current.finalization.fingerprint !== fingerprint) {
        return { kind: 'conflict', session: current }
      }
      for (const stream of manifest.streams) {
        if (!transaction.hasObject(stream.object.mediaObjectId)) {
          fail('DURABILITY_UNAVAILABLE', 503, 'An immutable object is not durable at manifest commit.')
        }
      }
      transaction.putManifest({ digest: manifestDigest, manifest })
      current.state = 'finalized'
      current.manifestId = manifest.manifestId
      current.manifestDigest = manifestDigest
      current.manifest = clone(manifest)
      incrementRevision(current, this.clock.now())
      transaction.putSession(current)
      return { kind: 'committed', session: current }
    })
    if (committed.kind === 'conflict') {
      fail('SESSION_ALREADY_FINALIZED', 409, 'The finalization binding changed before manifest commit.')
    }
    return finalizedResponse(committed.session, committed.kind === 'committed' ? 'finalized' : 'already-finalized')
  }

  async getImmutableManifestProjection(manifestId, expectedManifestDigest) {
    uuidV7(manifestId, 'manifestId')
    sha256(expectedManifestDigest, 'expectedManifestDigest')
    const record = await this.storage.readManifestById(manifestId)
    if (!record) fail('INGEST_SESSION_NOT_FOUND', 404, 'The immutable manifest does not exist.', { manifestId })
    if (record.digest !== expectedManifestDigest) {
      fail('MANIFEST_DIGEST_CONFLICT', 409, 'The immutable manifest digest differs from the caller expectation.', {
        manifestId,
        expectedManifestDigest,
        actualManifestDigest: record.digest,
      })
    }
    const manifest = record.manifest
    const primary = manifest.streams.find((stream) => stream.streamId === manifest.primaryStreamId)
    if (!primary) throw new IngestError('DURABILITY_UNAVAILABLE', 503, 'The immutable manifest has no primary stream.')
    const endExclusive = BigInt(primary.sequenceRange.last) + 1n
    return {
      schemaVersion: 'gumi.media-ingest.immutable-manifest-projection.v1',
      manifestId: manifest.manifestId,
      manifestDigest: record.digest,
      captureSessionId: manifest.captureSessionId,
      deviceId: manifest.deviceId,
      edgeHostId: manifest.edgeHostId,
      primaryStreamId: manifest.primaryStreamId,
      objectHandle: `gumi-media:object/${primary.object.mediaObjectId}`,
      objectContentDigest: primary.object.contentDigest,
      objectByteLength: primary.object.byteLength,
      objectContentType: primary.object.contentType,
      sequenceStart: primary.sequenceRange.first,
      sequenceEndExclusive: String(endExclusive),
      codec: primary.codec.name,
      sampleRateHz: primary.codec.sampleRateHz,
      channels: primary.codec.channelCount,
      startedAt: primary.startedAt,
      endedAt: primary.endedAt,
      durationMs: primary.durationMs,
    }
  }
}
