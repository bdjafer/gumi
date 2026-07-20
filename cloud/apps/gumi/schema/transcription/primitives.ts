import { z } from 'zod'

import {
  PositiveUnsigned64Schema,
  Sha256DigestSchema,
  Unsigned64Schema,
  UtcTimestampSchema,
  UuidV7Schema,
} from '../capture/media-manifest.js'

export {
  PositiveUnsigned64Schema,
  Sha256DigestSchema,
  Unsigned64Schema,
  UtcTimestampSchema,
  UuidV7Schema,
}

export const ProcessingSlugSchema = z
  .string()
  .regex(/^[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?$/)
export const ProcessingOpaqueValueSchema = z
  .string()
  .refine((value) => [...value].length >= 1 && [...value].length <= 512)
  .refine((value) => [...value].every((character) => {
    const codePoint = character.codePointAt(0)
    return codePoint !== undefined && codePoint > 0x1f && codePoint !== 0x7f
  }))
export const ProcessingModelValueSchema = ProcessingOpaqueValueSchema.refine(
  (value) => [...value].length <= 160,
)
export const TranscriptArtifactContentTypeSchema = z.literal(
  'application/vnd.gumi.transcript+json',
)
export const TranscriptLanguageTagSchema = z
  .string()
  .regex(/^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/)
export const TranscriptLanguageBasisSchema = z.enum([
  'requested',
  'detected',
  'provider-default',
])
export const TranscriptTextSchema = z
  .string()
  .refine((value) => [...value].length <= 32_768)
export const TranscriptSpeakerLabelSchema = z
  .string()
  .refine((value) => [...value].length >= 1 && [...value].length <= 256)
