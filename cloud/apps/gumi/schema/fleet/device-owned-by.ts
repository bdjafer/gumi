import { edgeClass, KernelSchema } from '@astrale-os/kernel-core'

import { Device } from './device.js'

/** The human identity whose standing authority owns a provisioned device. */
export const device_owned_by = edgeClass(
  { as: 'device', types: [Device], cardinality: '1' as const },
  {
    as: 'owner',
    types: [KernelSchema.interfaces.Identity],
  },
)
