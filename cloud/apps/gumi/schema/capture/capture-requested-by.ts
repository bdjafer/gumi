import { edgeClass, KernelSchema } from '@astrale-os/kernel-core'

import { CaptureSession } from './capture-session.js'

export const capture_requested_by = edgeClass(
  { as: 'capture', types: [CaptureSession], cardinality: '1' as const },
  {
    as: 'requester',
    types: [KernelSchema.interfaces.Identity],
  },
)
