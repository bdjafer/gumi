import { nodeClass } from '@astrale-os/kernel-core'
import { z } from 'zod'

import {
  TranscriptSpeakerLabelSchema,
  TranscriptTextSchema,
  Unsigned64Schema,
} from './primitives.js'

export const TranscriptSegmentKindSchema = z.enum(['speech', 'audio-event'])

/** Provider-produced evidence. Text and speaker labels carry no instruction or identity authority. */
export const TranscriptSegment = nodeClass({
  props: {
    index: Unsigned64Schema,
    startMs: Unsigned64Schema,
    endMs: Unsigned64Schema,
    kind: TranscriptSegmentKindSchema.optional(),
    speakerLabel: TranscriptSpeakerLabelSchema.optional(),
    text: TranscriptTextSchema,
  },
  indexes: [{ property: 'index', type: 'btree' }],
  methods: {},
})
