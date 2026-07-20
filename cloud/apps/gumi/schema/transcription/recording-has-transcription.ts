import { edgeClass } from '@astrale-os/kernel-core'

import { Recording } from '../capture/recording.js'
import { Transcription } from './transcription.js'

export const recording_has_transcription = edgeClass(
  { as: 'recording', types: [Recording] },
  { as: 'transcription', types: [Transcription], cardinality: '1' as const },
)
