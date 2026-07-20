import { nodeClass } from '@astrale-os/kernel-core'
import { z } from 'zod'

import {
  PositiveUnsigned64Schema,
  ProcessingModelValueSchema,
  ProcessingOpaqueValueSchema,
  ProcessingSlugSchema,
  Sha256DigestSchema,
  TranscriptArtifactContentTypeSchema,
  TranscriptLanguageBasisSchema,
  TranscriptLanguageTagSchema,
  Unsigned64Schema,
  UtcTimestampSchema,
  UuidV7Schema,
} from './primitives.js'

export const TranscriptStatusSchema = z.enum(['publishing', 'ready'])

/** Semantic transcript metadata. Segment text is separately bounded, inert child data. */
export const Transcript = nodeClass({
  props: {
    artifactId: UuidV7Schema,
    processingJobId: UuidV7Schema,
    status: TranscriptStatusSchema,
    artifactHandle: ProcessingOpaqueValueSchema,
    manifestId: UuidV7Schema,
    manifestDigest: Sha256DigestSchema,
    inputContentDigest: Sha256DigestSchema,
    outputContentDigest: Sha256DigestSchema,
    outputByteLength: PositiveUnsigned64Schema,
    contentType: TranscriptArtifactContentTypeSchema,
    pipelineId: ProcessingSlugSchema,
    configurationDigest: Sha256DigestSchema,
    providerId: ProcessingSlugSchema,
    model: ProcessingModelValueSchema,
    modelVersion: ProcessingModelValueSchema,
    attemptId: UuidV7Schema,
    generation: PositiveUnsigned64Schema,
    languageTag: TranscriptLanguageTagSchema,
    languageBasis: TranscriptLanguageBasisSchema,
    providerStartedAt: UtcTimestampSchema,
    providerCompletedAt: UtcTimestampSchema,
    mediaDurationMs: PositiveUnsigned64Schema,
    segmentCount: Unsigned64Schema,
    timedSegmentCount: Unsigned64Schema,
    firstSegmentStartMs: Unsigned64Schema.optional(),
    lastSegmentEndMs: Unsigned64Schema.optional(),
    committedAt: UtcTimestampSchema,
  },
  indexes: [
    { property: 'artifactId', type: 'btree' },
    { property: 'processingJobId', type: 'btree' },
    { property: 'status', type: 'btree' },
    { property: 'outputContentDigest', type: 'btree' },
    { property: 'providerId', type: 'btree' },
  ],
  methods: {},
})

export type TranscriptStatus = z.infer<typeof TranscriptStatusSchema>
