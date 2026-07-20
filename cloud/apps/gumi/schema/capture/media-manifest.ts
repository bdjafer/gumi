import { z } from 'zod'

import type { components } from '../../integrations/media-ingest/generated/media-ingest-v1.js'

export const UuidV7Schema = z
  .string()
  .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
export const Sha256DigestSchema = z.string().regex(/^sha256:[a-f0-9]{64}$/)
export const ManifestDigestSchema = Sha256DigestSchema
export const Unsigned64Schema = z
  .string()
  .regex(/^(0|[1-9][0-9]*)$/)
  .refine((value) => BigInt(value) <= 18_446_744_073_709_551_615n, 'must fit unsigned 64-bit')
export const PositiveUnsigned64Schema = z
  .string()
  .regex(/^[1-9][0-9]*$/)
  .refine((value) => BigInt(value) <= 18_446_744_073_709_551_615n, 'must fit positive unsigned 64-bit')
export const Unsigned64ExclusiveBoundarySchema = z
  .string()
  .regex(/^[1-9][0-9]*$/)
  .refine(
    (value) => BigInt(value) <= 18_446_744_073_709_551_616n,
    'must fit an exclusive unsigned 64-bit boundary',
  )

export const AudioCodecSchema = z.literal('opus')
export const ImmutableAudioObjectContentTypeSchema = z.literal('audio/ogg; codecs=opus')
export const OpaqueMediaHandleSchema = z
  .string()
  .min(3)
  .max(512)
  .regex(/^gumi-media:[a-z0-9][a-z0-9._~:/-]{0,510}[a-z0-9]$/)
export const UtcTimestampSchema = z
  .string()
  .regex(/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z$/)
  .refine((value) => !Number.isNaN(Date.parse(value)), 'must be a valid UTC timestamp')

/**
 * Runtime trust-boundary validation for the publisher-owned media-ingest projection. The static
 * shape is generated from media-ingest's OpenAPI document; this schema adds executable validation
 * before any facts are admitted into the Astrale graph.
 */
export const ImmutableManifestProjectionSchema: z.ZodType<ImmutableManifestProjection> = z.strictObject({
  schemaVersion: z.literal('gumi.media-ingest.immutable-manifest-projection.v1'),
  manifestId: UuidV7Schema,
  manifestDigest: ManifestDigestSchema,
  captureSessionId: UuidV7Schema,
  deviceId: UuidV7Schema,
  edgeHostId: UuidV7Schema,
  primaryStreamId: UuidV7Schema,
  objectHandle: OpaqueMediaHandleSchema,
  objectContentDigest: Sha256DigestSchema,
  objectByteLength: PositiveUnsigned64Schema,
  objectContentType: ImmutableAudioObjectContentTypeSchema,
  sequenceStart: Unsigned64Schema,
  sequenceEndExclusive: Unsigned64ExclusiveBoundarySchema,
  codec: AudioCodecSchema,
  sampleRateHz: z.number().int().min(8_000).max(48_000),
  channels: z.number().int().min(1).max(2),
  startedAt: UtcTimestampSchema,
  endedAt: UtcTimestampSchema,
  durationMs: z.number().int().min(0).max(86_400_000),
})

export type ImmutableManifestProjection = components['schemas']['ImmutableManifestProjection']
