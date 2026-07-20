import { edgeClass } from '@astrale-os/kernel-core'

import { Recording } from '../capture/recording.js'
import { Transcript } from './transcript.js'

export const transcribes = edgeClass(
  { as: 'transcript', types: [Transcript], cardinality: '1' as const },
  { as: 'recording', types: [Recording] },
)
