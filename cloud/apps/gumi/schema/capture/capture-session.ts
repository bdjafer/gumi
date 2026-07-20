import { nodeClass } from '@astrale-os/kernel-core'
import { fn } from '@astrale-os/kernel-dsl'
import { z } from 'zod'

import { ManifestDigestSchema, UuidV7Schema } from './media-manifest.js'

export const CaptureModeSchema = z.enum(['recording', 'voice-turn'])
export const CaptureStatusSchema = z.enum(['open', 'closed', 'faulted'])

/** A semantic capture lifecycle. Packet state and mutable upload cursors stay in the data plane. */
export const CaptureSession = nodeClass({
  props: {
    captureId: UuidV7Schema,
    edgeHostId: UuidV7Schema,
    mode: CaptureModeSchema,
    status: CaptureStatusSchema,
    openedAt: z.string().datetime(),
    closedAt: z.string().datetime().optional(),
  },
  indexes: [
    { property: 'captureId', type: 'btree' },
    { property: 'status', type: 'btree' },
  ],
  methods: {
    close: fn({
      params: {},
      returns: z.object({
        status: z.literal('closed'),
        closedAt: z.string().datetime(),
        replayed: z.boolean(),
      }),
    }),
    finalizeRecording: fn({
      params: {
        recordingId: UuidV7Schema,
        manifestId: UuidV7Schema,
        manifestDigest: ManifestDigestSchema,
      },
      returns: z.object({
        recording: z.string(),
        status: z.literal('ready'),
        replayed: z.boolean(),
      }),
    }),
  },
})

export type CaptureMode = z.infer<typeof CaptureModeSchema>
export type CaptureStatus = z.infer<typeof CaptureStatusSchema>
