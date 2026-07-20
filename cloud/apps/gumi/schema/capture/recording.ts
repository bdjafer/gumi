import { nodeClass, pathSchema } from '@astrale-os/kernel-core'
import { fn } from '@astrale-os/kernel-dsl'
import { z } from 'zod'

import {
  AudioCodecSchema,
  ImmutableAudioObjectContentTypeSchema,
  ManifestDigestSchema,
  OpaqueMediaHandleSchema,
  PositiveUnsigned64Schema,
  Sha256DigestSchema,
  Unsigned64Schema,
  Unsigned64ExclusiveBoundarySchema,
  UtcTimestampSchema,
  UuidV7Schema,
} from './media-manifest.js'

export const RecordingStatusSchema = z.enum(['pending-manifest', 'ready'])

/**
 * Traceable semantic reference to immutable media. Raw bytes, chunks, upload credentials,
 * and provider secrets deliberately remain outside the Astrale graph.
 */
export const Recording = nodeClass({
  props: {
    recordingId: UuidV7Schema,
    status: RecordingStatusSchema,
    manifestId: UuidV7Schema,
    manifestDigest: ManifestDigestSchema,
    captureSessionId: UuidV7Schema.optional(),
    deviceId: UuidV7Schema.optional(),
    edgeHostId: UuidV7Schema.optional(),
    primaryStreamId: UuidV7Schema.optional(),
    objectHandle: OpaqueMediaHandleSchema.optional(),
    objectContentDigest: Sha256DigestSchema.optional(),
    objectByteLength: PositiveUnsigned64Schema.optional(),
    objectContentType: ImmutableAudioObjectContentTypeSchema.optional(),
    sequenceStart: Unsigned64Schema.optional(),
    sequenceEndExclusive: Unsigned64ExclusiveBoundarySchema.optional(),
    codec: AudioCodecSchema.optional(),
    sampleRateHz: z.number().int().min(8_000).max(48_000).optional(),
    channels: z.number().int().min(1).max(2).optional(),
    startedAt: UtcTimestampSchema.optional(),
    endedAt: UtcTimestampSchema.optional(),
    durationMs: z.number().int().min(0).max(86_400_000).optional(),
    finalizedAt: z.string().datetime().optional(),
  },
  indexes: [
    { property: 'recordingId', type: 'btree' },
    { property: 'status', type: 'btree' },
    { property: 'manifestId', type: 'btree' },
    { property: 'deviceId', type: 'btree' },
    { property: 'edgeHostId', type: 'btree' },
    { property: 'primaryStreamId', type: 'btree' },
  ],
  methods: {
    publishTranscript: fn({
      params: {
        processingJobId: UuidV7Schema,
        expectedOutputContentDigest: Sha256DigestSchema,
      },
      returns: z.object({
        transcription: pathSchema(),
        transcript: pathSchema(),
        status: z.literal('ready'),
        replayed: z.boolean(),
      }),
    }),
  },
})

export type RecordingStatus = z.infer<typeof RecordingStatusSchema>
