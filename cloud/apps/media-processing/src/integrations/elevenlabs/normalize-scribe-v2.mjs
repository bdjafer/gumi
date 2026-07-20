import {
  codePointLength,
  digest,
  languageTag,
  opaque,
  positiveU64,
  u64,
  uuidV7,
} from '../../core/primitives.mjs'

const MAX_RECORDS = 100_000
const MAX_PROVIDER_TEXT_CODE_POINTS = 4_194_304
const MAX_RECORD_TEXT_CODE_POINTS = 4_096
const MAX_SEGMENT_TEXT_CODE_POINTS = 32_768
const MAX_MEDIA_DURATION_MS = 86_400_000n
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f]/u

export class ScribeV2NormalizationError extends TypeError {
  constructor(code, field) {
    super(`${code}: ${field}`)
    this.name = 'ScribeV2NormalizationError'
    this.code = code
    this.field = field
  }
}

function reject(code, field) {
  throw new ScribeV2NormalizationError(code, field)
}

function record(value, field) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    reject('INVALID_OBJECT', field)
  }
  return value
}

function exactKeys(value, required, optional, field) {
  record(value, field)
  const keys = Object.keys(value)
  for (const key of required) if (!keys.includes(key)) reject('MISSING_FIELD', `${field}.${key}`)
  const allowed = new Set([...required, ...optional])
  for (const key of keys) if (!allowed.has(key)) reject('UNSUPPORTED_FIELD', `${field}.${key}`)
}

function boundedText(value, field, { minimum = 0, maximum, controls = false }) {
  if (typeof value !== 'string') reject('INVALID_TEXT', field)
  const length = codePointLength(value)
  if (length < minimum || length > maximum || (!controls && CONTROL_CHARACTER.test(value))) {
    reject('INVALID_TEXT', field)
  }
  return value
}

function finiteNumber(value, field) {
  if (typeof value !== 'number' || !Number.isFinite(value)) reject('INVALID_NUMBER', field)
  return value
}

function secondsToMilliseconds(value, field, mediaDurationMs) {
  const seconds = finiteNumber(value, field)
  if (seconds < 0) reject('INVALID_TIMESTAMP', field)
  const milliseconds = Math.round(seconds * 1_000)
  if (!Number.isSafeInteger(milliseconds) || BigInt(milliseconds) > mediaDurationMs) {
    reject('TIMESTAMP_OUTSIDE_MEDIA', field)
  }
  return milliseconds
}

function validateBinding(binding) {
  exactKeys(
    binding,
    [
      'providerRequestId',
      'processingJobId',
      'attemptId',
      'generation',
      'configurationDigest',
      'mediaDurationMs',
    ],
    [],
    'binding',
  )
  opaque(binding.providerRequestId, 'binding.providerRequestId', { max: 256 })
  uuidV7(binding.processingJobId, 'binding.processingJobId')
  uuidV7(binding.attemptId, 'binding.attemptId')
  positiveU64(binding.generation, 'binding.generation')
  digest(binding.configurationDigest, 'binding.configurationDigest')
  const mediaDurationMs = positiveU64(binding.mediaDurationMs, 'binding.mediaDurationMs')
  if (mediaDurationMs > MAX_MEDIA_DURATION_MS) reject('MEDIA_DURATION_TOO_LARGE', 'binding.mediaDurationMs')
  return mediaDurationMs
}

function validateMetadata(metadata, binding) {
  exactKeys(
    metadata,
    ['processing_job_id', 'attempt_id', 'generation', 'configuration_digest'],
    [],
    'payload.data.webhook_metadata',
  )
  if (
    metadata.processing_job_id !== binding.processingJobId ||
    metadata.attempt_id !== binding.attemptId ||
    metadata.generation !== binding.generation ||
    metadata.configuration_digest !== binding.configurationDigest
  ) {
    reject('PROVIDER_BINDING_MISMATCH', 'payload.data.webhook_metadata')
  }
}

function validateProviderRecord(value, index, mediaDurationMs) {
  const field = `payload.data.transcription.words[${index}]`
  exactKeys(
    value,
    ['text', 'start', 'end', 'type'],
    ['speaker_id', 'logprob'],
    field,
  )
  if (!['word', 'spacing', 'audio_event'].includes(value.type)) {
    reject('UNSUPPORTED_RECORD_TYPE', `${field}.type`)
  }
  const maximum = value.type === 'spacing' ? 64 : MAX_RECORD_TEXT_CODE_POINTS
  const text = boundedText(value.text, `${field}.text`, {
    minimum: 1,
    maximum,
    controls: value.type === 'spacing',
  })
  if (value.type === 'spacing' && !/^\s+$/u.test(text)) {
    reject('INVALID_SPACING', `${field}.text`)
  }
  const startMs = secondsToMilliseconds(value.start, `${field}.start`, mediaDurationMs)
  const endMs = secondsToMilliseconds(value.end, `${field}.end`, mediaDurationMs)
  if (endMs < startMs) reject('REVERSED_TIMESTAMP', field)
  let speakerLabel
  if (value.speaker_id !== undefined) {
    speakerLabel = boundedText(value.speaker_id, `${field}.speaker_id`, {
      minimum: 1,
      maximum: 256,
    })
  }
  if (value.logprob !== undefined) finiteNumber(value.logprob, `${field}.logprob`)
  return { type: value.type, text, startMs, endMs, speakerLabel }
}

function sameSpeaker(left, right) {
  return left === right
}

function buildSegments(records) {
  const segments = []
  let speech
  let pendingSpacing = []

  const append = (segment) => {
    if (codePointLength(segment.text) > MAX_SEGMENT_TEXT_CODE_POINTS) {
      reject('SEGMENT_TEXT_TOO_LARGE', 'payload.data.transcription.words')
    }
    const previous = segments.at(-1)
    if (previous && segment.startMs < Number(previous.endMs)) {
      reject('UNSUPPORTED_OVERLAP', 'payload.data.transcription.words')
    }
    segments.push({
      index: String(segments.length),
      startMs: String(segment.startMs),
      endMs: String(segment.endMs),
      kind: segment.kind,
      ...(segment.speakerLabel === undefined ? {} : { speakerLabel: segment.speakerLabel }),
      text: segment.text,
    })
  }

  const flushSpeech = () => {
    if (!speech) return
    append({ ...speech, kind: 'speech' })
    speech = undefined
  }

  for (const item of records) {
    if (item.type === 'spacing') {
      if (speech && (item.speakerLabel === undefined || sameSpeaker(item.speakerLabel, speech.speakerLabel))) {
        speech.text += item.text
        speech.startMs = Math.min(speech.startMs, item.startMs)
        speech.endMs = Math.max(speech.endMs, item.endMs)
      } else {
        pendingSpacing.push(item)
      }
      continue
    }

    if (item.type === 'audio_event') {
      if (pendingSpacing.length > 0) reject('AMBIGUOUS_SPACING', 'payload.data.transcription.words')
      flushSpeech()
      append({ ...item, kind: 'audio-event' })
      continue
    }

    const spacingText = pendingSpacing.map(({ text }) => text).join('')
    const spacingStart = pendingSpacing.reduce(
      (minimum, spacing) => Math.min(minimum, spacing.startMs),
      item.startMs,
    )
    const spacingEnd = pendingSpacing.reduce(
      (maximum, spacing) => Math.max(maximum, spacing.endMs),
      item.endMs,
    )
    if (
      pendingSpacing.some(
        ({ speakerLabel }) =>
          speakerLabel !== undefined && !sameSpeaker(speakerLabel, item.speakerLabel),
      )
    ) {
      reject('AMBIGUOUS_SPACING', 'payload.data.transcription.words')
    }
    pendingSpacing = []

    if (speech && sameSpeaker(speech.speakerLabel, item.speakerLabel)) {
      speech.text += `${spacingText}${item.text}`
      speech.startMs = Math.min(speech.startMs, spacingStart)
      speech.endMs = Math.max(speech.endMs, spacingEnd, item.endMs)
    } else {
      flushSpeech()
      speech = {
        text: `${spacingText}${item.text}`,
        startMs: Math.min(spacingStart, item.startMs),
        endMs: Math.max(spacingEnd, item.endMs),
        speakerLabel: item.speakerLabel,
      }
    }
  }
  if (pendingSpacing.length > 0) reject('AMBIGUOUS_SPACING', 'payload.data.transcription.words')
  flushSpeech()
  return segments
}

/**
 * Convert one already-authenticated Scribe v2 completion into Gumi's immutable transcript artifact.
 * Signature verification and callback-attempt authorization deliberately remain adapter concerns.
 */
export function normalizeScribeV2Webhook(payload, binding) {
  const mediaDurationMs = validateBinding(record(binding, 'binding'))
  exactKeys(payload, ['type', 'data'], [], 'payload')
  if (payload.type !== 'speech_to_text_transcription') {
    reject('UNSUPPORTED_EVENT_TYPE', 'payload.type')
  }
  exactKeys(
    payload.data,
    ['request_id', 'webhook_metadata', 'transcription'],
    [],
    'payload.data',
  )
  if (payload.data.request_id !== binding.providerRequestId) {
    reject('PROVIDER_BINDING_MISMATCH', 'payload.data.request_id')
  }
  validateMetadata(payload.data.webhook_metadata, binding)

  const transcription = payload.data.transcription
  exactKeys(
    transcription,
    ['language_code', 'language_probability', 'text', 'words'],
    [],
    'payload.data.transcription',
  )
  languageTag(transcription.language_code, 'payload.data.transcription.language_code')
  const probability = finiteNumber(
    transcription.language_probability,
    'payload.data.transcription.language_probability',
  )
  if (probability < 0 || probability > 1) {
    reject('INVALID_PROBABILITY', 'payload.data.transcription.language_probability')
  }
  boundedText(transcription.text, 'payload.data.transcription.text', {
    maximum: MAX_PROVIDER_TEXT_CODE_POINTS,
    controls: true,
  })
  if (!Array.isArray(transcription.words) || transcription.words.length > MAX_RECORDS) {
    reject('RECORD_LIMIT_EXCEEDED', 'payload.data.transcription.words')
  }
  const records = transcription.words.map((value, index) =>
    validateProviderRecord(record(value, `payload.data.transcription.words[${index}]`), index, mediaDurationMs),
  )
  if (records.map(({ text }) => text).join('') !== transcription.text) {
    reject('TRANSCRIPT_TEXT_MISMATCH', 'payload.data.transcription.text')
  }

  return {
    schemaVersion: 'gumi.media-processing.transcript-artifact.v1',
    language: transcription.language_code,
    mediaDurationMs: String(mediaDurationMs),
    segments: buildSegments(records),
  }
}
