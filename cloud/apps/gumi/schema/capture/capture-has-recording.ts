import { edgeClass } from '@astrale-os/kernel-core'

import { CaptureSession } from './capture-session.js'
import { Recording } from './recording.js'

export const capture_has_recording = edgeClass(
  { as: 'capture', types: [CaptureSession] },
  { as: 'recording', types: [Recording], cardinality: '1' as const },
)
