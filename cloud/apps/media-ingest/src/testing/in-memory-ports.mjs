import { DurabilityError, IngestError } from '../core/error.mjs'
import { byteDigest, clone, utcTimestamp } from '../core/primitives.mjs'

function emptyState() {
  return {
    sessions: new Map(),
    createRequests: new Map(),
    chunkBodies: new Map(),
    objects: new Map(),
    manifests: new Map(),
  }
}

export class InMemoryStorage {
  #state = emptyState()
  #failures = []
  #tail = Promise.resolve()

  failNext(boundary, phase = 'before') {
    if (!['before', 'after'].includes(phase)) throw new TypeError('phase must be before or after')
    this.#failures.push({ boundary, phase })
  }

  #trip(boundary, phase) {
    const index = this.#failures.findIndex((failure) => failure.boundary === boundary && failure.phase === phase)
    if (index === -1) return
    this.#failures.splice(index, 1)
    throw new DurabilityError(boundary, phase)
  }

  #serialize(operation) {
    const result = this.#tail.then(operation)
    this.#tail = result.catch(() => undefined)
    return result
  }

  async transaction(boundary, callback) {
    return this.#serialize(async () => {
      const draft = clone(this.#state)
      const transaction = {
        getSession: (id) => draft.sessions.get(id),
        putSession: (session) => draft.sessions.set(session.id, session),
        getCreateRequest: (requestId) => draft.createRequests.get(requestId),
        bindCreateRequest: (requestId, binding) => draft.createRequests.set(requestId, binding),
        putChunkBody: (key, bytes) => {
          const existing = draft.chunkBodies.get(key)
          if (existing && !existing.equals(bytes)) {
            throw new IngestError('DURABILITY_UNAVAILABLE', 503, 'A durable chunk body key changed identity.')
          }
          draft.chunkBodies.set(key, Buffer.from(bytes))
        },
        hasObject: (id) => draft.objects.has(id),
        putManifest: (record) => {
          const existing = draft.manifests.get(record.manifest.manifestId)
          if (existing && existing.digest !== record.digest) {
            throw new IngestError('DURABILITY_UNAVAILABLE', 503, 'An immutable manifest identity changed.')
          }
          draft.manifests.set(record.manifest.manifestId, clone(record))
        },
      }
      const value = await callback(transaction)
      this.#trip(boundary, 'before')
      this.#state = draft
      this.#trip(boundary, 'after')
      return clone(value)
    })
  }

  async readSession(id) {
    await this.#tail
    const session = this.#state.sessions.get(id)
    return session ? clone(session) : undefined
  }

  async readChunkBody(key) {
    await this.#tail
    const body = this.#state.chunkBodies.get(key)
    return body ? Buffer.from(body) : undefined
  }

  async composeImmutableObject(record) {
    return this.#serialize(async () => {
      const boundary = 'store-final-object'
      const bodies = record.orderedChunkBodyKeys.map((key) => this.#state.chunkBodies.get(key))
      if (bodies.some((body) => !body)) {
        throw new IngestError('DURABILITY_UNAVAILABLE', 503, 'An immutable object source chunk is absent.')
      }
      const bytes = Buffer.concat(bodies)
      if (String(bytes.length) !== record.byteLength || byteDigest(bytes) !== record.contentDigest) {
        throw new IngestError('DURABILITY_UNAVAILABLE', 503, 'Immutable object composition facts do not match its chunks.')
      }
      const existing = this.#state.objects.get(record.mediaObjectId)
      if (existing) {
        if (
          existing.contentDigest !== record.contentDigest ||
          existing.contentType !== record.contentType ||
          !Buffer.from(existing.bytes).equals(bytes)
        ) {
          throw new IngestError('DURABILITY_UNAVAILABLE', 503, 'An immutable media object identity changed.')
        }
        return { disposition: 'duplicate' }
      }
      const next = clone(this.#state)
      next.objects.set(record.mediaObjectId, {
        mediaObjectId: record.mediaObjectId,
        contentType: record.contentType,
        contentDigest: record.contentDigest,
        byteLength: record.byteLength,
        bytes: Buffer.from(bytes),
      })
      this.#trip(boundary, 'before')
      this.#state = next
      this.#trip(boundary, 'after')
      return { disposition: 'stored' }
    })
  }

  async readManifestById(manifestId) {
    await this.#tail
    const record = this.#state.manifests.get(manifestId)
    return record ? clone(record) : undefined
  }

  async readObject(mediaObjectId) {
    await this.#tail
    const object = this.#state.objects.get(mediaObjectId)
    return object ? { ...clone(object), bytes: Buffer.from(object.bytes) } : undefined
  }

  async inspectMetadata() {
    await this.#tail
    return {
      sessions: [...this.#state.sessions.values()].map((session) => clone(session)),
      createRequests: [...this.#state.createRequests.entries()].map(([key, value]) => [key, clone(value)]),
      manifests: [...this.#state.manifests.values()].map((manifest) => clone(manifest)),
      chunkBodyCount: this.#state.chunkBodies.size,
      objectFacts: [...this.#state.objects.values()].map((object) => ({
        mediaObjectId: object.mediaObjectId,
        contentType: object.contentType,
        byteLength: String(object.bytes.length),
        contentDigest: byteDigest(object.bytes),
      })),
    }
  }
}

export class ManualClock {
  #millis

  constructor(initial = '2026-07-19T20:00:00Z') {
    this.set(initial)
  }

  now() {
    return utcTimestamp(this.#millis)
  }

  set(value) {
    const millis = Date.parse(value)
    if (!Number.isFinite(millis)) throw new TypeError('ManualClock requires a valid timestamp')
    this.#millis = millis
  }

  advanceSeconds(seconds) {
    this.#millis += seconds * 1000
  }
}

export class DeterministicTokenPort {
  #nextId
  #ids
  #tokens
  #issued = new Map()
  #counter = 0
  #failures = []
  #usedIds = new Set()
  #allIssued = []

  constructor({ nextId = 1, ids = [], tokens = [] } = {}) {
    this.#nextId = BigInt(nextId)
    this.#ids = [...ids]
    this.#tokens = [...tokens]
  }

  failNext(phase = 'before') {
    if (!['before', 'after'].includes(phase)) throw new TypeError('phase must be before or after')
    this.#failures.push(phase)
  }

  #trip(phase) {
    const index = this.#failures.indexOf(phase)
    if (index === -1) return
    this.#failures.splice(index, 1)
    throw new DurabilityError('issue-credential', phase)
  }

  async newOpaqueId() {
    const queued = this.#ids.shift()
    if (queued) {
      if (this.#usedIds.has(queued)) throw new TypeError(`duplicate deterministic ID ${queued}`)
      this.#usedIds.add(queued)
      return queued
    }
    for (;;) {
      const suffix = this.#nextId.toString(16).padStart(12, '0')
      this.#nextId += 1n
      const candidate = `0190c6f0-7b21-7a40-8b11-${suffix}`
      if (this.#usedIds.has(candidate)) continue
      this.#usedIds.add(candidate)
      return candidate
    }
  }

  async issueCredential({ idempotencyKey, binding, issuedAt, expiresAt }) {
    if (idempotencyKey && this.#issued.has(idempotencyKey)) return clone(this.#issued.get(idempotencyKey))
    this.#trip('before')
    this.#counter += 1
    const accessToken =
      this.#tokens.shift() ?? `fixture-only-opaque-ingest-token-${String(this.#counter).padStart(16, '0')}`
    const credential = { tokenType: 'Bearer', accessToken, issuedAt, expiresAt }
    const record = { credential: clone(credential), binding: clone(binding) }
    if (idempotencyKey) this.#issued.set(idempotencyKey, record)
    this.#allIssued.push(clone(record))
    this.#trip('after')
    return record
  }

  inspectIssued() {
    return this.#allIssued.map((record) => clone(record))
  }
}
