import { edgeClass } from '@astrale-os/kernel-core'

import { CaptureSession } from '../capture/capture-session.js'
import { Device } from './device.js'

export const device_has_capture = edgeClass(
  { as: 'device', types: [Device] },
  { as: 'capture', types: [CaptureSession], cardinality: '1' as const },
)
