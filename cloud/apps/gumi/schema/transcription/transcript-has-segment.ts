import { edgeClass } from '@astrale-os/kernel-core'

import { TranscriptSegment } from './transcript-segment.js'
import { Transcript } from './transcript.js'

export const transcript_has_segment = edgeClass(
  { as: 'transcript', types: [Transcript] },
  { as: 'segment', types: [TranscriptSegment], cardinality: '1' as const },
)
