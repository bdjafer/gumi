import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import { IngestError } from '../src/core/error.mjs'
import { MediaIngestService } from '../src/core/ingest-service.mjs'
import { createOpusStreamInspector, validateOggChunk } from '../src/core/ogg.mjs'
import { byteDigest, timestamp, U64_EXCLUSIVE_MAX, U64_MAX, u64, valueDigest } from '../src/core/primitives.mjs'
import { DeterministicTokenPort, InMemoryStorage, ManualClock } from '../src/testing/in-memory-ports.mjs'

const APP = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const FIXTURES = join(APP, 'fixtures', 'v1')
const OGG_CRC_TABLE = Array.from({ length: 256 }, (_, index) => {
  let value = (index << 24) >>> 0
  for (let bit = 0; bit < 8; bit += 1) {
    value = ((value & 0x80000000) !== 0 ? (value << 1) ^ 0x04c11db7 : value << 1) >>> 0
  }
  return value
})

function fixture(path) {
  return JSON.parse(readFileSync(join(FIXTURES, path), 'utf8'))
}

function rewriteOggCrcs(bytes) {
  let offset = 0
  while (offset < bytes.length) {
    const segmentTableEnd = offset + 27 + bytes[offset + 26]
    let payloadLength = 0
    for (let index = offset + 27; index < segmentTableEnd; index += 1) payloadLength += bytes[index]
    const pageEnd = segmentTableEnd + payloadLength
    let crc = 0
    for (let index = offset; index < pageEnd; index += 1) {
      const byte = index >= offset + 22 && index < offset + 26 ? 0 : bytes[index]
      crc = (((crc << 8) >>> 0) ^ OGG_CRC_TABLE[((crc >>> 24) ^ byte) & 0xff]) >>> 0
    }
    bytes.writeUInt32LE(crc, offset + 22)
    offset = pageEnd
  }
}

function opusHead(codec, preSkip = 312) {
  const head = Buffer.alloc(19)
  Buffer.from('OpusHead').copy(head)
  head[8] = 1
  head[9] = codec.channelCount
  head.writeUInt16LE(preSkip, 10)
  head.writeUInt32LE(codec.sampleRateHz, 12)
  return head
}

function opusTags(vendor = 'gumi', comments = []) {
  const vendorBytes = Buffer.from(vendor, 'utf8')
  const commentBytes = comments.map((comment) => Buffer.from(comment, 'utf8'))
  const tags = Buffer.alloc(
    16 + vendorBytes.length + commentBytes.reduce((sum, comment) => sum + 4 + comment.length, 0),
  )
  Buffer.from('OpusTags').copy(tags)
  tags.writeUInt32LE(vendorBytes.length, 8)
  vendorBytes.copy(tags, 12)
  tags.writeUInt32LE(commentBytes.length, 12 + vendorBytes.length)
  let offset = 16 + vendorBytes.length
  for (const comment of commentBytes) {
    tags.writeUInt32LE(comment.length, offset)
    offset += 4
    comment.copy(tags, offset)
    offset += comment.length
  }
  return tags
}

function oggPage({ headerType, granulePosition, sequence, packets, serial = 7 }) {
  const lacing = []
  for (const packet of packets) {
    let remaining = packet.length
    while (remaining >= 255) {
      lacing.push(255)
      remaining -= 255
    }
    lacing.push(remaining)
  }
  const page = Buffer.alloc(27 + lacing.length + packets.reduce((sum, packet) => sum + packet.length, 0))
  Buffer.from('OggS').copy(page)
  page[5] = headerType
  page.writeBigUInt64LE(granulePosition, 6)
  page.writeUInt32LE(serial, 14)
  page.writeUInt32LE(sequence, 18)
  page[26] = lacing.length
  Buffer.from(lacing).copy(page, 27)
  let payloadOffset = 27 + lacing.length
  for (const packet of packets) {
    packet.copy(page, payloadOffset)
    payloadOffset += packet.length
  }
  rewriteOggCrcs(page)
  return page
}

const IDS = {
  session: '0190c6f0-7b21-7a40-8b11-000000000002',
  manifest: '0190c6f0-7b21-7a40-8b11-000000000009',
  object: '0190c6f0-7b21-7a40-8b11-00000000000a',
  secondChunk: '0190c6f0-7b21-7a40-8b11-000000000013',
  secondStream: '0190c6f0-7b21-7a40-8b11-000000000014',
  secondStreamFirstChunk: '0190c6f0-7b21-7a40-8b11-000000000015',
  secondStreamLastChunk: '0190c6f0-7b21-7a40-8b11-000000000016',
}

function harness({ ids = [IDS.session, IDS.manifest, IDS.object], tokens, serviceOptions = {} } = {}) {
  const storage = new InMemoryStorage()
  const clock = new ManualClock('2026-07-19T20:00:00Z')
  const tokenPort = new DeterministicTokenPort({
    ids,
    tokens: tokens ?? [
      'fixture-only-not-a-real-ingest-token-0001',
      'fixture-only-not-a-real-ingest-token-0002',
    ],
  })
  return {
    storage,
    clock,
    tokens: tokenPort,
    service: new MediaIngestService({ storage, clock, tokens: tokenPort, ...serviceOptions }),
  }
}

function assemblyChunks(streamId = fixture('success/chunk-descriptor.json').streamId) {
  const assembly = fixture('success/deterministic-assembly.json')
  const first = fixture('success/chunk-descriptor.json')
  first.streamId = streamId
  const second = {
    schemaVersion: 'gumi.media-ingest.chunk.v1',
    ingestSessionId: IDS.session,
    streamId,
    chunkId: IDS.secondChunk,
    sequenceRange: assembly.chunks[1].sequenceRange,
    payloadBytes: assembly.chunks[1].payloadBytes,
    payloadFormat: 'ogg-opus-page-fragment-v1',
    contentDigest: assembly.chunks[1].contentDigest,
    codecConfigurationId: 'opus-16000-mono-20ms-v1',
    sourceStartedAt: '2026-07-19T20:00:02Z',
    edgeReceivedAt: '2026-07-19T20:00:02.125Z',
    sourceRetransmission: false,
  }
  return [
    { descriptor: first, body: Buffer.from(assembly.chunks[0].bodyBase64, 'base64') },
    { descriptor: second, body: Buffer.from(assembly.chunks[1].bodyBase64, 'base64') },
  ]
}

function audioPageChunk(baseDescriptor, { chunkId, sequence, headerType, granulePosition, packet = Buffer.from([72]) }) {
  const policy = fixture('success/create-session-request.json').streams[0]
  const body = oggPage({
    headerType,
    granulePosition,
    sequence: 2 + sequence,
    packets: [packet],
    serial: Number(policy.oggLayout.serialNumber),
  })
  const descriptor = structuredClone(baseDescriptor)
  descriptor.chunkId = chunkId
  descriptor.sequenceRange = { first: String(sequence), last: String(sequence) }
  descriptor.payloadBytes = String(body.length)
  descriptor.contentDigest = byteDigest(body)
  return { descriptor, body }
}

async function create(h) {
  return h.service.createSession(fixture('success/create-session-request.json'))
}

async function uploadComplete(h) {
  const chunks = assemblyChunks()
  h.clock.set('2026-07-19T20:00:02Z')
  const firstAck = await h.service.putChunk(chunks[0].descriptor, chunks[0].body)
  h.clock.set('2026-07-19T20:00:05Z')
  const secondAck = await h.service.putChunk(chunks[1].descriptor, chunks[1].body)
  return { chunks, firstAck, secondAck }
}

async function preparedHarness() {
  const h = harness()
  await create(h)
  await uploadComplete(h)
  h.clock.set('2026-07-19T20:00:10Z')
  return h
}

async function rejectsCode(promise, code, status) {
  await assert.rejects(promise, (error) => {
    assert.ok(error instanceof IngestError)
    assert.equal(error.code, code)
    if (status !== undefined) assert.equal(error.status, status)
    return true
  })
}

test('fixture path executes byte-for-byte through create, durable chunk ACK, finalization, and projection', async () => {
  const h = harness()
  const created = await create(h)
  assert.equal(created.disposition, 'created')
  assert.deepEqual(created.response, fixture('success/create-session-response.json'))

  const { firstAck } = await uploadComplete(h)
  assert.deepEqual(firstAck, fixture('success/chunk-stored-ack.json'))
  h.clock.set('2026-07-19T20:00:10Z')
  const finalized = await h.service.finalize(IDS.session, fixture('success/finalize-request.json'))
  assert.deepEqual(finalized, fixture('success/finalize-response.json'))
  assert.equal(finalized.manifestDigest, valueDigest(finalized.manifest))

  const projection = await h.service.getImmutableManifestProjection(finalized.manifest.manifestId, finalized.manifestDigest)
  assert.deepEqual(projection, fixture('success/immutable-manifest-projection.json'))
  const primary = finalized.manifest.streams.find((stream) => stream.streamId === finalized.manifest.primaryStreamId)
  assert.ok(primary)
  assert.deepEqual(
    {
      contentDigest: projection.objectContentDigest,
      byteLength: projection.objectByteLength,
      contentType: projection.objectContentType,
    },
    {
      contentDigest: primary.object.contentDigest,
      byteLength: primary.object.byteLength,
      contentType: primary.object.contentType,
    },
  )
  const object = await h.storage.readObject(IDS.object)
  const expectedBytes = Buffer.concat(assemblyChunks().map((chunk) => chunk.body))
  assert.ok(object.bytes.equals(expectedBytes))
  assert.equal(object.contentDigest, byteDigest(expectedBytes))
})

test('session creation is idempotent by requestId and rejects contradictory reuse', async () => {
  const h = harness()
  const first = await create(h)
  h.clock.advanceSeconds(10)
  const replay = await create(h)
  assert.equal(replay.disposition, 'replay')
  assert.deepEqual(replay.response, first.response)

  const changed = fixture('success/create-session-request.json')
  changed.deviceId = '0190c6f0-7b21-7a40-8b11-000000000099'
  await rejectsCode(h.service.createSession(changed), 'REQUEST_ID_CONFLICT', 409)
  const status = await h.service.getStatus(IDS.session)
  assert.equal(status.stateRevision, '0')
  assert.equal(status.streams[0].storedChunkCount, '0')
})

test('server hard caps reject caller-selected resource policies before session durability', async () => {
  const h = harness({
    serviceOptions: {
      hardMaxChunkBytes: 32_768n,
      hardMaxStreamBytes: 50_000_000n,
      hardMaxSessionBytes: 50_000_000n,
    },
  })

  await rejectsCode(create(h), 'INVALID_REQUEST', 400)
  await rejectsCode(h.service.getStatus(IDS.session), 'INGEST_SESSION_NOT_FOUND', 404)
})

test('session creation binds canonical deterministic Ogg layout facts before durability', async () => {
  const mutations = [
    ['missing layout', (stream) => { delete stream.oggLayout }],
    ['unknown profile', (stream) => { stream.oggLayout.profile = 'gumi.ogg-opus.unknown.v1' }],
    ['serial overflow', (stream) => { stream.oggLayout.serialNumber = '4294967296' }],
    ['pre-skip overflow', (stream) => { stream.oggLayout.preSkip48kSamples = '65536' }],
    ['page-sequence overflow', (stream) => { stream.sequencePolicy.maximumLast = '4294967294' }],
  ]

  for (const [label, mutate] of mutations) {
    const h = harness()
    const request = fixture('success/create-session-request.json')
    mutate(request.streams[0])
    await rejectsCode(h.service.createSession(request), label === 'page-sequence overflow' ? 'INVALID_SEQUENCE_RANGE' : 'INVALID_REQUEST', 400)
    await rejectsCode(h.service.getStatus(IDS.session), 'INGEST_SESSION_NOT_FOUND', 404)
  }
})

test('fixed-frame TOC and bounded Ogg granules are executable ingest invariants', async () => {
  const h = harness()
  const request = fixture('success/create-session-request.json')
  request.streams[0].codec.configurationId = 'opus-16000-mono-10ms-v1'
  request.streams[0].codec.frameDurationUs = 10_000
  await h.service.createSession(request)
  const [first] = assemblyChunks()
  first.descriptor.codecConfigurationId = 'opus-16000-mono-10ms-v1'
  await rejectsCode(h.service.putChunk(first.descriptor, first.body), 'INVALID_REQUEST', 400)

  const codec = fixture('success/create-session-request.json').streams[0].codec
  const head = opusHead(codec)
  const tags = opusTags()
  const audio = Buffer.from([72])
  const inspector = createOpusStreamInspector(codec)
  assert.throws(
    () => inspector.accept({
      pages: [
        { headerType: 2, granulePosition: 0n, serial: 7, sequence: 0, completedPackets: 1 },
        { headerType: 0, granulePosition: 0n, serial: 7, sequence: 1, completedPackets: 1 },
        { headerType: 0, granulePosition: 960n, serial: 7, sequence: 2, completedPackets: 1 },
        { headerType: 4, granulePosition: 959n, serial: 7, sequence: 3, completedPackets: 1 },
      ],
      packets: [head, tags, audio, audio],
    }),
    /terminal granule trims more than the final Opus packet/,
  )
})

test('single-audio-page EOS honors RFC 7845 initial granule and trim rules', () => {
  const codec = fixture('success/create-session-request.json').streams[0].codec
  const head = opusHead(codec)
  const tags = opusTags()
  const audio = Buffer.from([72])
  const valid = createOpusStreamInspector(codec)
  valid.accept({
    pages: [
      { headerType: 2, granulePosition: 0n, serial: 7, sequence: 0, completedPackets: 1 },
      { headerType: 0, granulePosition: 0n, serial: 7, sequence: 1, completedPackets: 1 },
      { headerType: 4, granulePosition: 2232n, serial: 7, sequence: 2, completedPackets: 2 },
    ],
    packets: [head, tags, audio, audio],
  })
  assert.deepEqual(valid.finish(2n), { durationMs: 40 })
  const validBody = Buffer.concat([
    oggPage({ headerType: 2, granulePosition: 0n, sequence: 0, packets: [head] }),
    oggPage({ headerType: 0, granulePosition: 0n, sequence: 1, packets: [tags] }),
    oggPage({ headerType: 4, granulePosition: 2232n, sequence: 2, packets: [audio, audio] }),
  ])
  assert.doesNotThrow(() => validateOggChunk(validBody, { isFirst: true, expectedAudioPackets: 2n, codec }))

  const invalidTrim = createOpusStreamInspector(codec)
  assert.throws(
    () => invalidTrim.accept({
      pages: [
        { headerType: 2, granulePosition: 0n, serial: 7, sequence: 0, completedPackets: 1 },
        { headerType: 0, granulePosition: 0n, serial: 7, sequence: 1, completedPackets: 1 },
        { headerType: 4, granulePosition: 959n, serial: 7, sequence: 2, completedPackets: 2 },
      ],
      packets: [head, tags, audio, audio],
    }),
    /terminal granule trims more than the final Opus packet/,
  )
  const invalidBody = Buffer.concat([
    oggPage({ headerType: 2, granulePosition: 0n, sequence: 0, packets: [head] }),
    oggPage({ headerType: 0, granulePosition: 0n, sequence: 1, packets: [tags] }),
    oggPage({ headerType: 4, granulePosition: 959n, sequence: 2, packets: [audio, audio] }),
  ])
  assert.throws(
    () => validateOggChunk(invalidBody, { isFirst: true, expectedAudioPackets: 2n, codec }),
    /terminal granule trims more than the final Opus packet/,
  )
})

test('invalid immutable Opus headers are rejected before durability and corrected bytes can commit', async () => {
  const corruptions = [
    ['version', (body, headOffset) => { body[headOffset + 8] = 2 }],
    ['channel count', (body, headOffset) => { body[headOffset + 9] = 2 }],
    ['input sample rate', (body, headOffset) => { body.writeUInt32LE(48_000, headOffset + 12) }],
    ['output gain', (body, headOffset) => { body.writeInt16LE(1, headOffset + 16) }],
    ['mapping family', (body, headOffset) => { body[headOffset + 18] = 1 }],
    ['scoped pre-skip', (body, headOffset) => { body.writeUInt16LE(313, headOffset + 10) }],
    ['OpusTags length', (body, _headOffset, tagsOffset) => { body.writeUInt32LE(0xffff_ffff, tagsOffset + 8) }],
    ['OpusTags UTF-8', (body, _headOffset, tagsOffset) => { body[tagsOffset + 12] = 0xff }],
  ]

  for (const [label, corrupt] of corruptions) {
    const h = harness()
    await create(h)
    const [first] = assemblyChunks()
    const validBody = Buffer.from(first.body)
    const validDescriptor = structuredClone(first.descriptor)
    const headOffset = first.body.indexOf(Buffer.from('OpusHead'))
    const tagsOffset = first.body.indexOf(Buffer.from('OpusTags'))
    corrupt(first.body, headOffset, tagsOffset)
    rewriteOggCrcs(first.body)
    first.descriptor.contentDigest = byteDigest(first.body)

    await rejectsCode(h.service.putChunk(first.descriptor, first.body), 'INVALID_REQUEST', 400)
    const afterRejection = await h.service.getStatus(IDS.session)
    assert.equal(afterRejection.stateRevision, '0', label)
    assert.equal(afterRejection.streams[0].storedChunkCount, '0', label)
    assert.equal((await h.service.putChunk(validDescriptor, validBody)).disposition, 'stored', label)
  }
})

test('deterministic OpusTags rejects unauthorized vendor comments and trailing metadata before durability', async () => {
  const h = harness()
  await create(h)
  const policy = fixture('success/create-session-request.json').streams[0]
  const [first] = assemblyChunks()
  const variants = [
    ['vendor', opusTags('other')],
    ['comment', opusTags('gumi', ['note=not-authorized'])],
    ['trailing metadata', Buffer.concat([opusTags('gumi'), Buffer.from([0])])],
  ]

  for (const [label, tags] of variants) {
    const body = Buffer.concat([
      oggPage({
        headerType: 2,
        granulePosition: 0n,
        sequence: 0,
        packets: [opusHead(policy.codec)],
        serial: Number(policy.oggLayout.serialNumber),
      }),
      oggPage({
        headerType: 0,
        granulePosition: 0n,
        sequence: 1,
        packets: [tags],
        serial: Number(policy.oggLayout.serialNumber),
      }),
      oggPage({
        headerType: 0,
        granulePosition: 960n,
        sequence: 2,
        packets: [Buffer.from([72])],
        serial: Number(policy.oggLayout.serialNumber),
      }),
    ])
    const descriptor = structuredClone(first.descriptor)
    descriptor.sequenceRange = { first: '0', last: '0' }
    descriptor.payloadBytes = String(body.length)
    descriptor.contentDigest = byteDigest(body)
    await rejectsCode(h.service.putChunk(descriptor, body), 'INVALID_REQUEST', 400)
    assert.equal((await h.service.getStatus(IDS.session)).stateRevision, '0', label)
  }

  assert.equal((await h.service.putChunk(first.descriptor, first.body)).disposition, 'stored')
})

test('sparse chunks self-validate serial, page sequence, and granule before durable acknowledgement', async () => {
  const h = harness()
  await create(h)
  const [, terminal] = assemblyChunks()
  const corruptions = [
    ['serial', (body) => body.writeUInt32LE(7, 14)],
    ['page sequence', (body) => body.writeUInt32LE(7, 18)],
    ['granule', (body) => body.writeBigUInt64LE(2_000n, 6)],
  ]

  for (const [label, corrupt] of corruptions) {
    const body = Buffer.from(terminal.body)
    corrupt(body)
    rewriteOggCrcs(body)
    const descriptor = structuredClone(terminal.descriptor)
    descriptor.contentDigest = byteDigest(body)
    await rejectsCode(h.service.putChunk(descriptor, body), 'INVALID_REQUEST', 400)
    const status = await h.service.getStatus(IDS.session)
    assert.equal(status.stateRevision, '0', label)
    assert.equal(status.streams[0].storedChunkCount, '0', label)
  }

  assert.equal((await h.service.putChunk(terminal.descriptor, terminal.body)).disposition, 'stored')
})

test('durable EOS permits earlier gap repair but rejects every contradictory later ordering', async () => {
  const terminalFirst = harness()
  await create(terminalFirst)
  const [first, terminal] = assemblyChunks()
  await terminalFirst.service.putChunk(terminal.descriptor, terminal.body)

  const afterTerminal = audioPageChunk(terminal.descriptor, {
    chunkId: '0190c6f0-7b21-7a40-8b11-00000000001a',
    sequence: 4,
    headerType: 0,
    granulePosition: 4_800n,
  })
  await rejectsCode(
    terminalFirst.service.putChunk(afterTerminal.descriptor, afterTerminal.body),
    'CHUNK_METADATA_CONFLICT',
    409,
  )
  assert.equal((await terminalFirst.service.putChunk(first.descriptor, first.body)).disposition, 'stored')
  assert.deepEqual((await terminalFirst.service.getStatus(IDS.session)).streams[0].committedRanges, [
    { first: '0', last: '3' },
  ])

  const laterFirst = harness()
  await create(laterFirst)
  await laterFirst.service.putChunk(afterTerminal.descriptor, afterTerminal.body)
  await rejectsCode(laterFirst.service.putChunk(terminal.descriptor, terminal.body), 'CHUNK_METADATA_CONFLICT', 409)
  const status = await laterFirst.service.getStatus(IDS.session)
  assert.deepEqual(status.streams[0].committedRanges, [{ first: '4', last: '4' }])
  assert.equal(status.streams[0].terminalSequence, null)
})

test('a lost initial credential response safely replays the committed session and same scoped token', async () => {
  const h = harness()
  h.tokens.failNext('after')
  await rejectsCode(create(h), 'DURABILITY_UNAVAILABLE', 503)
  const replay = await create(h)
  assert.equal(replay.disposition, 'replay')
  assert.equal(replay.response.credential.accessToken, 'fixture-only-not-a-real-ingest-token-0001')
})

test('credential refresh is bounded by session expiry, does not mutate durable revision, and never revokes earlier tokens', async () => {
  const h = harness()
  const created = await create(h)
  h.clock.set('2026-07-19T20:14:00Z')
  const refreshed = await h.service.refreshCredential(IDS.session, fixture('success/refresh-credential-request.json'))
  assert.deepEqual(refreshed, fixture('success/refresh-credential-response.json'))
  assert.notEqual(refreshed.credential.accessToken, created.response.credential.accessToken)
  assert.ok(Date.parse(refreshed.credential.expiresAt) <= Date.parse(created.response.sessionExpiresAt))
  assert.equal((await h.service.getStatus(IDS.session)).stateRevision, '0')

  h.clock.set('2026-07-19T21:00:00Z')
  await rejectsCode(
    h.service.refreshCredential(IDS.session, fixture('success/refresh-credential-request.json')),
    'INGEST_SESSION_EXPIRED',
    409,
  )
})

test('a lost refresh response may leave two valid bounded credentials without mutating the session', async () => {
  const h = harness()
  await create(h)
  h.clock.set('2026-07-19T20:10:00Z')
  h.tokens.failNext('after')
  const request = fixture('success/refresh-credential-request.json')
  await rejectsCode(h.service.refreshCredential(IDS.session, request), 'DURABILITY_UNAVAILABLE', 503)
  const retry = await h.service.refreshCredential(IDS.session, request)
  const issued = h.tokens.inspectIssued()
  assert.equal(issued.length, 3)
  assert.equal(new Set(issued.map((record) => record.credential.accessToken)).size, 3)
  assert.ok(issued.every((record) => Date.parse(record.credential.expiresAt) <= Date.parse('2026-07-19T21:00:00Z')))
  assert.equal(retry.ingestSessionId, IDS.session)
  assert.equal((await h.service.getStatus(IDS.session)).stateRevision, '0')
})

test('executable u64 parsing is exact at both protocol boundaries and never coerces through Number', () => {
  assert.equal(u64(String(U64_MAX), 'max'), U64_MAX)
  assert.equal(u64(String(U64_EXCLUSIVE_MAX), 'exclusive', { exclusiveBoundary: true }), U64_EXCLUSIVE_MAX)
  assert.throws(() => u64(String(U64_MAX + 1n), 'overflow'), /outside its declared bounds/)
  assert.throws(() => u64('01', 'non-canonical'), /canonical decimal/)
})

test('RFC 3339 validation rejects calendar normalization that Date.parse would silently accept', () => {
  assert.throws(() => timestamp('2026-02-30T20:00:01Z', 'timestamp'), /RFC 3339/)
  assert.throws(() => timestamp('2025-02-29T20:00:01+01:00', 'timestamp'), /RFC 3339/)
  assert.equal(timestamp('2024-02-29T20:00:01.123456789-05:30', 'timestamp'), '2024-02-29T20:00:01.123456789-05:30')
})

test('chunk validation rejects length, digest, malformed profile, conflict, overlap, and policy overflow without overwrite', async () => {
  const h = harness()
  await create(h)
  const [first, second] = assemblyChunks()

  const badLength = structuredClone(first.descriptor)
  badLength.payloadBytes = String(first.body.length - 1)
  await rejectsCode(h.service.putChunk(badLength, first.body), 'CONTENT_LENGTH_MISMATCH', 422)

  const badDigest = structuredClone(first.descriptor)
  badDigest.contentDigest = `sha256:${'b'.repeat(64)}`
  await rejectsCode(h.service.putChunk(badDigest, first.body), 'CONTENT_DIGEST_MISMATCH', 422)

  const malformed = Buffer.from(first.body)
  malformed[0] ^= 1
  const malformedDescriptor = structuredClone(first.descriptor)
  malformedDescriptor.contentDigest = byteDigest(malformed)
  await rejectsCode(h.service.putChunk(malformedDescriptor, malformed), 'INVALID_REQUEST', 400)

  const tooShortRange = structuredClone(first.descriptor)
  tooShortRange.sequenceRange = { first: '0', last: '0' }
  await rejectsCode(h.service.putChunk(tooShortRange, first.body), 'INVALID_REQUEST', 400)

  const tooLongRange = structuredClone(first.descriptor)
  tooLongRange.sequenceRange = { first: '0', last: '2' }
  await rejectsCode(h.service.putChunk(tooLongRange, first.body), 'INVALID_REQUEST', 400)

  const stored = await h.service.putChunk(first.descriptor, first.body)
  assert.equal(stored.disposition, 'stored')
  const duplicate = await h.service.putChunk(first.descriptor, first.body)
  assert.equal(duplicate.disposition, 'duplicate')
  assert.equal(duplicate.stateRevision, stored.stateRevision)

  const changedMetadata = structuredClone(first.descriptor)
  changedMetadata.sourceRetransmission = true
  await rejectsCode(h.service.putChunk(changedMetadata, first.body), 'CHUNK_METADATA_CONFLICT', 409)

  const changedBody = Buffer.from(second.body)
  const changedIdentity = structuredClone(first.descriptor)
  changedIdentity.payloadBytes = String(changedBody.length)
  changedIdentity.contentDigest = byteDigest(changedBody)
  await rejectsCode(h.service.putChunk(changedIdentity, changedBody), 'CHUNK_DIGEST_CONFLICT', 409)

  const overlap = structuredClone(first.descriptor)
  overlap.chunkId = '0190c6f0-7b21-7a40-8b11-000000000017'
  await rejectsCode(h.service.putChunk(overlap, first.body), 'SEQUENCE_OVERLAP', 409)

  const tooLarge = structuredClone(second.descriptor)
  tooLarge.chunkId = '0190c6f0-7b21-7a40-8b11-00000000001a'
  tooLarge.sequenceRange = { first: '1000000', last: '1000000' }
  await rejectsCode(h.service.putChunk(tooLarge, second.body), 'INVALID_SEQUENCE_RANGE', 400)

  const object = await h.storage.readChunkBody(`${IDS.session}/${first.descriptor.streamId}/${first.descriptor.chunkId}`)
  assert.ok(object.equals(first.body))
  assert.equal((await h.service.getStatus(IDS.session)).stateRevision, '1')
})

test('sparse durable chunks expose a leading gap and exact monotonic resume state', async () => {
  const h = harness()
  await create(h)
  const [, second] = assemblyChunks()
  h.clock.set('2026-07-19T20:00:03Z')
  await h.service.putChunk(second.descriptor, second.body)
  const status = await h.service.getStatus(IDS.session)
  assert.equal(status.stateRevision, '1')
  assert.deepEqual(status.streams[0].accountedRange, { first: '0', last: '3' })
  assert.deepEqual(status.streams[0].committedRanges, [{ first: '2', last: '3' }])
  assert.deepEqual(status.streams[0].missingRanges, [{ first: '0', last: '1' }])
  assert.equal(status.streams[0].durableThrough, null)
  assert.equal(status.streams[0].terminalSequence, '3')
  await rejectsCode(h.service.finalize(IDS.session, fixture('success/finalize-request.json')), 'SEQUENCE_GAP', 409)
  assert.equal((await h.service.getStatus(IDS.session)).state, 'open')
})

test('expired sessions retain evidence, increment revision once, and consistently reject later chunks', async () => {
  const h = harness()
  await create(h)
  h.clock.set('2026-07-19T21:00:00Z')
  const status = await h.service.getStatus(IDS.session)
  assert.equal(status.state, 'expired')
  assert.equal(status.stateRevision, '1')
  assert.equal((await h.service.getStatus(IDS.session)).stateRevision, '1')
  const [first] = assemblyChunks()
  await rejectsCode(h.service.putChunk(first.descriptor, first.body), 'INGEST_SESSION_EXPIRED', 409)
})

for (const phase of ['before', 'after']) {
  test(`chunk ${phase}-commit failure never creates a false durable acknowledgement`, async () => {
    const h = harness()
    await create(h)
    const [first] = assemblyChunks()
    h.storage.failNext('store-chunk', phase)
    await rejectsCode(h.service.putChunk(first.descriptor, first.body), 'DURABILITY_UNAVAILABLE', 503)
    const statusAfterFailure = await h.service.getStatus(IDS.session)
    assert.equal(statusAfterFailure.streams[0].storedChunkCount, phase === 'before' ? '0' : '1')
    const retry = await h.service.putChunk(first.descriptor, first.body)
    assert.equal(retry.disposition, phase === 'before' ? 'stored' : 'duplicate')
    assert.equal(retry.stateRevision, '1')
  })
}

test('finalization rejects duplicate/omitted stream identities, gaps, counter drift, and extra durable ranges', async () => {
  const h = await preparedHarness()
  const duplicate = fixture('success/finalize-request.json')
  duplicate.streams.push(structuredClone(duplicate.streams[0]))
  await rejectsCode(h.service.finalize(IDS.session, duplicate), 'FINALIZATION_STREAM_SET_MISMATCH', 409)

  const badCounter = fixture('success/finalize-request.json')
  badCounter.streams[0].expectedChunkCount = '1'
  await rejectsCode(h.service.finalize(IDS.session, badCounter), 'FINALIZATION_RANGE_CONFLICT', 409)

  const shortRange = fixture('success/finalize-request.json')
  shortRange.streams[0].expectedRange.last = '1'
  shortRange.streams[0].expectedChunkCount = '1'
  shortRange.streams[0].expectedByteLength = '168'
  shortRange.streams[0].expectedContentDigest = assemblyChunks()[0].descriptor.contentDigest
  await rejectsCode(h.service.finalize(IDS.session, shortRange), 'FINALIZATION_RANGE_CONFLICT', 409)
  assert.equal((await h.service.getStatus(IDS.session)).state, 'open')
})

for (const [boundary, phase] of [
  ['reserve-finalization', 'before'],
  ['reserve-finalization', 'after'],
  ['store-final-object', 'before'],
  ['store-final-object', 'after'],
  ['commit-finalization', 'before'],
  ['commit-finalization', 'after'],
]) {
  test(`finalization resumes safely after ${boundary} ${phase}-commit failure`, async () => {
    const h = await preparedHarness()
    h.storage.failNext(boundary, phase)
    const request = fixture('success/finalize-request.json')
    await rejectsCode(h.service.finalize(IDS.session, request), 'DURABILITY_UNAVAILABLE', 503)
    const retry = await h.service.finalize(IDS.session, request)
    assert.equal(retry.disposition, boundary === 'commit-finalization' && phase === 'after' ? 'already-finalized' : 'finalized')
    assert.equal(retry.manifestDigest, valueDigest(retry.manifest))
    const projection = await h.service.getImmutableManifestProjection(retry.manifest.manifestId, retry.manifestDigest)
    assert.equal(projection.captureSessionId, fixture('success/immutable-manifest-projection.json').captureSessionId)
    assert.equal(projection.sequenceStart, '0')
    assert.equal(projection.sequenceEndExclusive, '4')
    assert.equal(projection.objectContentDigest, retry.manifest.streams[0].object.contentDigest)
    assert.equal(projection.objectByteLength, retry.manifest.streams[0].object.byteLength)
    assert.equal(projection.objectContentType, retry.manifest.streams[0].object.contentType)
    assert.equal(projection.durationMs, 60)
  })
}

test('terminal replay returns the immutable result and contradictory replay cannot change it', async () => {
  const h = await preparedHarness()
  const request = fixture('success/finalize-request.json')
  const first = await h.service.finalize(IDS.session, request)
  const replay = await h.service.finalize(IDS.session, request)
  assert.equal(replay.disposition, 'already-finalized')
  assert.deepEqual(replay.manifest, first.manifest)

  const contradiction = structuredClone(request)
  contradiction.requestId = '0190c6f0-7b21-7a40-8b11-000000000018'
  contradiction.streams[0].expectedContentDigest = `sha256:${'b'.repeat(64)}`
  await rejectsCode(h.service.finalize(IDS.session, contradiction), 'SESSION_ALREADY_FINALIZED', 409)
  const preserved = await h.service.getImmutableManifestProjection(first.manifest.manifestId, first.manifestDigest)
  assert.equal(preserved.manifestDigest, first.manifestDigest)
  await rejectsCode(
    h.service.getImmutableManifestProjection(first.manifest.manifestId, `sha256:${'b'.repeat(64)}`),
    'MANIFEST_DIGEST_CONFLICT',
    409,
  )
})

test('multi-stream finalization requires and commits the exact session stream set', async () => {
  const ids = [IDS.session, IDS.manifest, IDS.object, '0190c6f0-7b21-7a40-8b11-000000000019']
  const h = harness({ ids })
  const request = fixture('success/create-session-request.json')
  const secondPolicy = structuredClone(request.streams[0])
  secondPolicy.streamId = IDS.secondStream
  request.streams.push(secondPolicy)
  await h.service.createSession(request)

  const primaryChunks = assemblyChunks()
  const secondaryChunks = assemblyChunks(IDS.secondStream)
  secondaryChunks[0].descriptor.chunkId = IDS.secondStreamFirstChunk
  secondaryChunks[1].descriptor.chunkId = IDS.secondStreamLastChunk
  for (const chunk of [...primaryChunks, ...secondaryChunks]) await h.service.putChunk(chunk.descriptor, chunk.body)

  const finalizeRequest = fixture('success/finalize-request.json')
  const secondExpected = structuredClone(finalizeRequest.streams[0])
  secondExpected.streamId = IDS.secondStream
  finalizeRequest.streams.push(secondExpected)
  const finalized = await h.service.finalize(IDS.session, finalizeRequest)
  assert.equal(finalized.manifest.streams.length, 2)
  assert.deepEqual(
    finalized.manifest.streams.map((stream) => stream.streamId),
    request.streams.map((stream) => stream.streamId),
  )
})

test('raw media bytes and bearer tokens remain outside durable session/manifest metadata', async () => {
  const h = await preparedHarness()
  await h.service.finalize(IDS.session, fixture('success/finalize-request.json'))
  const metadata = await h.storage.inspectMetadata()
  let foundBuffer = false
  let foundToken = false
  const visit = (value) => {
    if (Buffer.isBuffer(value) || value instanceof Uint8Array) foundBuffer = true
    if (typeof value === 'string' && value.includes('ingest-token')) foundToken = true
    if (value instanceof Map) for (const [key, child] of value) { visit(key); visit(child) }
    else if (Array.isArray(value)) value.forEach(visit)
    else if (value && typeof value === 'object') Object.values(value).forEach(visit)
  }
  visit(metadata)
  assert.equal(foundBuffer, false)
  assert.equal(foundToken, false)
  assert.equal(metadata.chunkBodyCount, 2)
  assert.equal(metadata.objectFacts.length, 1)
})
