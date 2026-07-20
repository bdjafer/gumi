import { DurabilityError, ProcessingError } from '../core/error.mjs'
import { byteDigest, clone, codePointLength, languageTag, opaque, u64, utcTimestamp } from '../core/primitives.mjs'

const UTF8 = new TextDecoder('utf-8', { fatal: true })

function exactKeys(value, expected, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${label} is not an object`)
  const actual = Object.keys(value).sort()
  assertSame(actual, [...expected].sort(), `${label} fields`)
}

function assertSame(actual, expected, label) {
  if (actual.length !== expected.length || actual.some((value, index) => value !== expected[index])) {
    throw new TypeError(`${label} differ`)
  }
}

function validateTranscriptArtifact(bytes, facts) {
  let artifact
  try {
    artifact = JSON.parse(UTF8.decode(bytes))
    exactKeys(artifact, ['schemaVersion', 'language', 'mediaDurationMs', 'segments'], 'transcript artifact')
    if (artifact.schemaVersion !== 'gumi.media-processing.transcript-artifact.v1') {
      throw new TypeError('unsupported transcript artifact schema')
    }
    languageTag(artifact.language, 'artifact.language')
    const mediaDuration = u64(artifact.mediaDurationMs, 'artifact.mediaDurationMs')
    if (!Array.isArray(artifact.segments) || artifact.segments.length > 100_000) {
      throw new TypeError('artifact.segments is not a bounded array')
    }
    let firstStart
    let lastEnd
    for (const [index, segment] of artifact.segments.entries()) {
      const allowed = ['index', 'startMs', 'endMs', 'text']
      if (segment.kind !== undefined) allowed.push('kind')
      if (segment.speakerLabel !== undefined) allowed.push('speakerLabel')
      exactKeys(segment, allowed, `artifact.segments[${index}]`)
      if (segment.index !== String(index)) throw new TypeError('segment indices must be contiguous')
      const start = u64(segment.startMs, `artifact.segments[${index}].startMs`)
      const end = u64(segment.endMs, `artifact.segments[${index}].endMs`)
      if (end < start || end > mediaDuration) throw new TypeError('segment timing is invalid')
      if (index > 0 && start < lastEnd) throw new TypeError('segments overlap or move backward')
      if (typeof segment.text !== 'string' || codePointLength(segment.text) > 32_768) {
        throw new TypeError('segment text is invalid')
      }
      if (segment.kind !== undefined && !['speech', 'audio-event'].includes(segment.kind)) {
        throw new TypeError('segment kind is invalid')
      }
      if (
        segment.speakerLabel !== undefined &&
        (
          typeof segment.speakerLabel !== 'string' ||
          codePointLength(segment.speakerLabel) < 1 ||
          codePointLength(segment.speakerLabel) > 256
        )
      ) {
        throw new TypeError('speaker label is invalid')
      }
      firstStart ??= start
      lastEnd = end
    }
    if (
      artifact.language !== facts.language.tag ||
      artifact.mediaDurationMs !== facts.timing.mediaDurationMs ||
      String(artifact.segments.length) !== facts.segments.count ||
      facts.segments.timedCount !== facts.segments.count
    ) {
      throw new TypeError('artifact aggregate facts do not match callback facts')
    }
    if (artifact.segments.length === 0) {
      if (facts.segments.firstStartMs !== undefined || facts.segments.lastEndMs !== undefined) {
        throw new TypeError('empty artifact has timing bounds')
      }
    } else if (
      String(firstStart) !== facts.segments.firstStartMs ||
      String(lastEnd) !== facts.segments.lastEndMs
    ) {
      throw new TypeError('artifact timing bounds do not match callback facts')
    }
    return artifact
  } catch (cause) {
    throw new ProcessingError(
      'ARTIFACT_INTEGRITY_MISMATCH',
      422,
      'Staged artifact is not a valid bounded transcript artifact.',
      undefined,
      cause,
    )
  }
}

function emptyState() {
  return {
    jobs: new Map(),
    requests: new Map(),
  }
}

export class InMemoryProcessingStorage {
  #state = emptyState()
  #failures = []
  #missedRequestReads = new Map()
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
      const tx = {
        getJob: (id) => draft.jobs.get(id),
        putJob: (job) => draft.jobs.set(job.id, clone(job)),
        getRequest: (key) => draft.requests.get(key),
        putRequest: (key, record) => draft.requests.set(key, clone(record)),
      }
      const result = await callback(tx)
      this.#trip(boundary, 'before')
      this.#state = draft
      this.#trip(boundary, 'after')
      return clone(result)
    })
  }

  async readJob(id) {
    await this.#tail
    return clone(this.#state.jobs.get(id))
  }

  async readRequest(key) {
    await this.#tail
    const misses = this.#missedRequestReads.get(key) ?? 0
    if (misses > 0) {
      if (misses === 1) this.#missedRequestReads.delete(key)
      else this.#missedRequestReads.set(key, misses - 1)
      return undefined
    }
    return clone(this.#state.requests.get(key))
  }

  /** Test-only fault injection for exercising fail-closed durable replay validation. */
  async replaceRequestForTest(key, replacement) {
    return this.#serialize(async () => {
      if (!this.#state.requests.has(key)) throw new TypeError(`request record does not exist: ${key}`)
      this.#state.requests.set(key, clone(replacement))
    })
  }

  /** Test-only race injection: the next non-transactional read observes a stale miss. */
  missNextRequestReadForTest(key) {
    this.#missedRequestReads.set(key, (this.#missedRequestReads.get(key) ?? 0) + 1)
  }

  async inspectMetadata() {
    await this.#tail
    return {
      jobs: [...this.#state.jobs.values()].map(clone),
      requests: [...this.#state.requests.entries()].map(([key, value]) => [key, clone(value)]),
    }
  }
}

export class ManualClock {
  #millis

  constructor(initial = '2026-07-19T20:00:00Z') {
    this.set(initial)
  }

  now() {
    return new Date(this.#millis).toISOString().replace('.000Z', 'Z')
  }

  set(value) {
    utcTimestamp(value, 'ManualClock initial value')
    this.#millis = Date.parse(value)
  }

  advanceSeconds(seconds) {
    if (!Number.isSafeInteger(seconds)) throw new TypeError('seconds must be a safe integer')
    this.#millis += seconds * 1000
  }
}

export class DeterministicIds {
  #next
  #queued
  #used = new Set()

  constructor({ next = 1, queued = [] } = {}) {
    this.#next = BigInt(next)
    this.#queued = [...queued]
  }

  async newOpaqueId() {
    const queued = this.#queued.shift()
    if (queued) {
      if (this.#used.has(queued)) throw new TypeError(`duplicate deterministic ID ${queued}`)
      this.#used.add(queued)
      return queued
    }
    for (;;) {
      const suffix = this.#next.toString(16).padStart(12, '0')
      this.#next += 1n
      const candidate = `0190c6f0-7b21-7a40-8b11-${suffix}`
      if (this.#used.has(candidate)) continue
      this.#used.add(candidate)
      return candidate
    }
  }
}

export class InMemoryManifestReader {
  #manifests = new Map()
  #calls = []

  constructor(manifests = []) {
    for (const manifest of manifests) this.add(manifest)
  }

  add(manifest) {
    this.#manifests.set(manifest.manifestId, clone(manifest))
  }

  async resolveImmutableManifest(binding) {
    this.#calls.push(clone(binding))
    const value = this.#manifests.get(binding.manifestId)
    if (!value) throw new ProcessingError('INPUT_MANIFEST_NOT_FOUND', 404, 'The immutable input manifest was not found.')
    return clone(value)
  }

  inspectCalls() {
    return this.#calls.map(clone)
  }
}

export class InMemoryArtifactStore {
  #staged = new Map()
  #artifacts = new Map()
  #failures = []

  stage(stagedArtifactHandle, bytes, contentType = 'application/vnd.gumi.transcript+json') {
    opaque(stagedArtifactHandle, 'stagedArtifactHandle')
    const next = { bytes: Buffer.from(bytes), contentType }
    const existing = this.#staged.get(stagedArtifactHandle)
    if (existing && (existing.contentType !== contentType || !existing.bytes.equals(next.bytes))) {
      throw new ProcessingError('ARTIFACT_INTEGRITY_MISMATCH', 422, 'An app-owned staged artifact identity changed.')
    }
    this.#staged.set(stagedArtifactHandle, next)
  }

  failNext(phase = 'before') {
    if (!['before', 'after'].includes(phase)) throw new TypeError('phase must be before or after')
    this.#failures.push(phase)
  }

  #trip(phase) {
    const index = this.#failures.indexOf(phase)
    if (index === -1) return
    this.#failures.splice(index, 1)
    throw new DurabilityError('commit-derived-artifact', phase)
  }

  async validateStagedArtifact(record) {
    const staged = this.#staged.get(record.stagedArtifactHandle)
    if (!staged) throw new ProcessingError('ARTIFACT_STAGE_NOT_FOUND', 503, 'The app-owned artifact stage is absent.')
    if (
      staged.contentType !== record.contentType ||
      String(staged.bytes.length) !== record.byteLength ||
      byteDigest(staged.bytes) !== record.contentDigest
    ) {
      throw new ProcessingError('ARTIFACT_INTEGRITY_MISMATCH', 422, 'Staged artifact bytes do not match declared facts.')
    }
    validateTranscriptArtifact(staged.bytes, record.artifactFacts)
  }

  async commitDerivedArtifact(record) {
    await this.validateStagedArtifact(record)
    const staged = this.#staged.get(record.stagedArtifactHandle)
    const artifactHandle = `gumi-derived:artifact/${record.artifactId}`
    const existing = this.#artifacts.get(record.artifactId)
    if (existing) {
      if (
        existing.contentDigest !== record.contentDigest ||
        existing.inputContentDigest !== record.inputContentDigest ||
        !existing.bytes.equals(staged.bytes)
      ) {
        throw new ProcessingError('DURABILITY_UNAVAILABLE', 503, 'An immutable artifact identity changed.')
      }
      return { artifactHandle, disposition: 'duplicate' }
    }
    this.#trip('before')
    this.#artifacts.set(record.artifactId, {
      ...clone(record),
      artifactHandle,
      bytes: Buffer.from(staged.bytes),
    })
    this.#trip('after')
    return { artifactHandle, disposition: 'stored' }
  }

  async readTranscriptPage(record) {
    const artifact = this.#artifacts.get(record.artifactId)
    if (!artifact) {
      throw new ProcessingError(
        'DURABILITY_UNAVAILABLE',
        503,
        'The committed transcript artifact is unavailable.',
      )
    }
    if (
      artifact.processingJobId !== record.processingJobId ||
      artifact.inputContentDigest !== record.inputContentDigest ||
      artifact.contentDigest !== record.outputContentDigest ||
      artifact.contentType !== record.contentType ||
      String(artifact.bytes.length) !== record.byteLength ||
      byteDigest(artifact.bytes) !== record.outputContentDigest
    ) {
      throw new ProcessingError(
        'ARTIFACT_INTEGRITY_MISMATCH',
        422,
        'The committed transcript artifact no longer matches its immutable binding.',
      )
    }
    if (
      !Number.isSafeInteger(record.startIndex) ||
      record.startIndex < 0 ||
      !Number.isSafeInteger(record.maximumSegments) ||
      record.maximumSegments < 1
    ) {
      throw new TypeError('Transcript page bounds are invalid')
    }
    const transcript = validateTranscriptArtifact(artifact.bytes, record.artifactFacts)
    return clone(transcript.segments.slice(record.startIndex, record.startIndex + record.maximumSegments))
  }

  async readBytes(artifactId) {
    const artifact = this.#artifacts.get(artifactId)
    return artifact ? Buffer.from(artifact.bytes) : undefined
  }

  inspectMetadata() {
    return [...this.#artifacts.values()].map((artifact) => ({
      artifactId: artifact.artifactId,
      artifactHandle: artifact.artifactHandle,
      contentDigest: artifact.contentDigest,
      inputContentDigest: artifact.inputContentDigest,
      byteLength: String(artifact.bytes.length),
      contentType: artifact.contentType,
      processingJobId: artifact.processingJobId,
      attemptId: artifact.attemptId,
      generation: artifact.generation,
    }))
  }
}
