import { edgeClass } from '@astrale-os/kernel-core'

import { Transcript } from './transcript.js'
import { Transcription } from './transcription.js'

export const transcription_has_transcript = edgeClass(
  { as: 'transcription', types: [Transcription], cardinality: '1' as const },
  { as: 'transcript', types: [Transcript], cardinality: '1' as const },
)
