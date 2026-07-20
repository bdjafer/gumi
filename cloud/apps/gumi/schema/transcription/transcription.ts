import { nodeClass } from '@astrale-os/kernel-core'
import { z } from 'zod'

import {
  Sha256DigestSchema,
  UuidV7Schema,
} from './primitives.js'

export const TranscriptionStatusSchema = z.enum(['publishing', 'ready'])

/** Stable, recording-owned import identity for one external processing job. */
export const Transcription = nodeClass({
  props: {
    processingJobId: UuidV7Schema,
    status: TranscriptionStatusSchema,
    manifestId: UuidV7Schema,
    manifestDigest: Sha256DigestSchema,
    inputContentDigest: Sha256DigestSchema,
    expectedOutputContentDigest: Sha256DigestSchema,
    artifactId: UuidV7Schema.optional(),
  },
  indexes: [
    { property: 'processingJobId', type: 'btree' },
    { property: 'status', type: 'btree' },
    { property: 'artifactId', type: 'btree' },
  ],
  methods: {},
})

export type TranscriptionStatus = z.infer<typeof TranscriptionStatusSchema>
