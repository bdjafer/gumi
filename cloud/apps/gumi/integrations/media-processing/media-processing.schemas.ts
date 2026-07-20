import { z } from 'zod'

import type { components } from './generated/media-processing-v1.js'

const MAX_U64 = 18_446_744_073_709_551_615n
const MAX_TRANSCRIPT_BYTES = 67_108_864n
const MAX_TRANSCRIPT_SEGMENTS = 100_000n
/** Fixed publisher page cardinality from the v1 transcript-page contract. */
export const TRANSCRIPT_PAGE_SEGMENT_LIMIT = 4n

function codePointLength(value: string): number {
  return [...value].length
}

function containsControlCharacter(value: string): boolean {
  return [...value].some((character) => {
    const codePoint = character.codePointAt(0)
    return codePoint !== undefined && (codePoint <= 0x1f || codePoint === 0x7f)
  })
}

function boundedCodePointString(minimum: number, maximum: number) {
  return z.string().refine(
    (value) => {
      const length = codePointLength(value)
      return length >= minimum && length <= maximum
    },
    `must contain between ${minimum} and ${maximum} Unicode code points`,
  )
}

function issue(context: z.RefinementCtx, path: PropertyKey[], message: string): void {
  context.addIssue({ code: 'custom', path, message })
}

export const MediaProcessingUuidV7Schema = z
  .string()
  .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)

export const MediaProcessingSha256DigestSchema = z.string().regex(/^sha256:[0-9a-f]{64}$/)

export const MediaProcessingUnsigned64Schema = z
  .string()
  .regex(/^(?:0|[1-9][0-9]*)$/)
  .refine((value) => BigInt(value) <= MAX_U64, 'must fit unsigned 64-bit')

export const MediaProcessingPositiveUnsigned64Schema = z
  .string()
  .regex(/^[1-9][0-9]*$/)
  .refine((value) => BigInt(value) <= MAX_U64, 'must fit positive unsigned 64-bit')

const DerivedArtifactByteLengthSchema = MediaProcessingPositiveUnsigned64Schema.refine(
  (value) => BigInt(value) <= MAX_TRANSCRIPT_BYTES,
  'must not exceed the v1 64 MiB transcript artifact limit',
)

const UtcTimestampSchema = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/)
  .refine((value) => {
    const milliseconds = Date.parse(value)
    if (!Number.isFinite(milliseconds)) return false
    const canonical = new Date(milliseconds).toISOString()
    return value.includes('.') ? canonical === value : canonical.replace('.000Z', 'Z') === value
  }, 'must be a canonical RFC 3339 UTC timestamp')

const OpaqueIdSchema = boundedCodePointString(1, 512).refine(
  (value) => !containsControlCharacter(value),
  'must not contain control characters',
)

const OpaqueProviderValueSchema = boundedCodePointString(1, 160).refine(
  (value) => !containsControlCharacter(value),
  'must not contain control characters',
)

const StableSlugSchema = z
  .string()
  .regex(/^[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?$/)

const LanguageTagSchema = z
  .string()
  .min(2)
  .max(64)
  .regex(/^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/)

const DigestBoundInputSchema = z.strictObject({
  manifestId: MediaProcessingUuidV7Schema,
  manifestDigest: MediaProcessingSha256DigestSchema,
  contentDigest: MediaProcessingSha256DigestSchema,
})

const SegmentAggregateSchema = z
  .strictObject({
    count: MediaProcessingUnsigned64Schema,
    timedCount: MediaProcessingUnsigned64Schema,
    firstStartMs: MediaProcessingUnsigned64Schema.optional(),
    lastEndMs: MediaProcessingUnsigned64Schema.optional(),
  })

const DerivedArtifactProjectionBaseSchema = z.strictObject({
  schemaVersion: z.literal('gumi.media-processing.derived-artifact-projection.v1'),
  processingJobId: MediaProcessingUuidV7Schema,
  artifactId: MediaProcessingUuidV7Schema,
  artifactHandle: OpaqueIdSchema,
  input: DigestBoundInputSchema,
  output: z.strictObject({
    contentDigest: MediaProcessingSha256DigestSchema,
    byteLength: DerivedArtifactByteLengthSchema,
    contentType: z.literal('application/vnd.gumi.transcript+json'),
  }),
  provenance: z.strictObject({
    pipelineId: StableSlugSchema,
    configurationDigest: MediaProcessingSha256DigestSchema,
    providerId: StableSlugSchema,
    model: OpaqueProviderValueSchema,
    modelVersion: OpaqueProviderValueSchema,
    attemptId: MediaProcessingUuidV7Schema,
    generation: MediaProcessingPositiveUnsigned64Schema,
    language: z.strictObject({
      tag: LanguageTagSchema,
      basis: z.enum(['requested', 'detected', 'provider-default']),
    }),
    timing: z.strictObject({
      providerStartedAt: UtcTimestampSchema,
      providerCompletedAt: UtcTimestampSchema,
      mediaDurationMs: MediaProcessingPositiveUnsigned64Schema,
    }),
    segments: SegmentAggregateSchema,
  }),
  committedAt: UtcTimestampSchema,
})

export type DerivedArtifactProjection = components['schemas']['DerivedArtifactProjection']

/**
 * Runtime trust boundary for media-processing's immutable result projection. The generated type
 * describes the publisher contract; these checks reject impossible aggregate and timing facts before
 * a caller can project any of them into the Astrale graph.
 */
export const DerivedArtifactProjectionSchema: z.ZodType<DerivedArtifactProjection> =
  DerivedArtifactProjectionBaseSchema.superRefine((projection, context) => {
    const started = Date.parse(projection.provenance.timing.providerStartedAt)
    const completed = Date.parse(projection.provenance.timing.providerCompletedAt)
    if (completed < started) {
      issue(
        context,
        ['provenance', 'timing', 'providerCompletedAt'],
        'must not precede providerStartedAt',
      )
    }

    const count = BigInt(projection.provenance.segments.count)
    const timedCount = BigInt(projection.provenance.segments.timedCount)
    const duration = BigInt(projection.provenance.timing.mediaDurationMs)
    const first = projection.provenance.segments.firstStartMs
    const last = projection.provenance.segments.lastEndMs

    if (count > MAX_TRANSCRIPT_SEGMENTS) {
      issue(context, ['provenance', 'segments', 'count'], 'must not exceed 100000 segments')
    }
    if (timedCount > count) {
      issue(context, ['provenance', 'segments', 'timedCount'], 'must not exceed count')
    }
    if (timedCount === 0n) {
      if (first !== undefined || last !== undefined) {
        issue(
          context,
          ['provenance', 'segments'],
          'untimed segments must not declare timing bounds',
        )
      }
      return
    }
    if (first === undefined || last === undefined) {
      issue(context, ['provenance', 'segments'], 'timed segments require both timing bounds')
      return
    }
    if (BigInt(last) < BigInt(first) || BigInt(last) > duration) {
      issue(
        context,
        ['provenance', 'segments', 'lastEndMs'],
        'timing bounds must be ordered within the media duration',
      )
    }
  })

const TranscriptSegmentSchema = z.strictObject({
  index: MediaProcessingUnsigned64Schema,
  startMs: MediaProcessingUnsigned64Schema,
  endMs: MediaProcessingUnsigned64Schema,
  kind: z.enum(['speech', 'audio-event']).optional(),
  speakerLabel: boundedCodePointString(1, 256).optional(),
  text: boundedCodePointString(0, 32_768),
})

const TranscriptPageBaseSchema = z.strictObject({
  schemaVersion: z.literal('gumi.media-processing.transcript-page.v1'),
  processingJobId: MediaProcessingUuidV7Schema,
  artifactId: MediaProcessingUuidV7Schema,
  inputContentDigest: MediaProcessingSha256DigestSchema,
  outputContentDigest: MediaProcessingSha256DigestSchema,
  language: LanguageTagSchema,
  mediaDurationMs: MediaProcessingPositiveUnsigned64Schema,
  totalSegmentCount: MediaProcessingUnsigned64Schema,
  startIndex: MediaProcessingUnsigned64Schema,
  endIndexExclusive: MediaProcessingUnsigned64Schema,
  nextStartIndex: MediaProcessingUnsigned64Schema.optional(),
  segments: z.array(TranscriptSegmentSchema).max(Number(TRANSCRIPT_PAGE_SEGMENT_LIMIT)),
})

export type TranscriptPage = components['schemas']['TranscriptPage']

/** Validate the exact half-open publisher page and every untrusted transcript segment. */
export const TranscriptPageSchema: z.ZodType<TranscriptPage> = TranscriptPageBaseSchema.superRefine(
  (page, context) => {
    const total = BigInt(page.totalSegmentCount)
    const start = BigInt(page.startIndex)
    const end = BigInt(page.endIndexExclusive)
    const duration = BigInt(page.mediaDurationMs)

    if (total > MAX_TRANSCRIPT_SEGMENTS) {
      issue(context, ['totalSegmentCount'], 'must not exceed 100000 segments')
      return
    }
    if ((total === 0n && start !== 0n) || (total > 0n && start >= total)) {
      issue(context, ['startIndex'], 'must identify a publisher-defined page boundary')
    }

    const remaining = total >= start ? total - start : 0n
    const expectedCardinality = remaining < TRANSCRIPT_PAGE_SEGMENT_LIMIT
      ? remaining
      : TRANSCRIPT_PAGE_SEGMENT_LIMIT
    if (BigInt(page.segments.length) !== expectedCardinality || end !== start + expectedCardinality) {
      issue(
        context,
        ['endIndexExclusive'],
        'must close the exact half-open page implied by start, total, and segment cardinality',
      )
    }
    if (end > total) issue(context, ['endIndexExclusive'], 'must not exceed totalSegmentCount')

    if (end < total) {
      if (page.nextStartIndex === undefined || BigInt(page.nextStartIndex) !== end) {
        issue(context, ['nextStartIndex'], 'must equal endIndexExclusive while another page remains')
      }
    } else if (page.nextStartIndex !== undefined) {
      issue(context, ['nextStartIndex'], 'must be absent on the final page')
    }

    let priorEnd: bigint | undefined
    page.segments.forEach((segment, offset) => {
      const expectedIndex = start + BigInt(offset)
      const segmentStart = BigInt(segment.startMs)
      const segmentEnd = BigInt(segment.endMs)
      if (BigInt(segment.index) !== expectedIndex) {
        issue(context, ['segments', offset, 'index'], 'must be contiguous from startIndex')
      }
      if (
        segmentEnd < segmentStart ||
        segmentEnd > duration ||
        (priorEnd !== undefined && segmentStart < priorEnd)
      ) {
        issue(
          context,
          ['segments', offset, 'endMs'],
          'segment timing must be ordered, non-overlapping, and within the media duration',
        )
      }
      priorEnd = segmentEnd
    })
  },
)
