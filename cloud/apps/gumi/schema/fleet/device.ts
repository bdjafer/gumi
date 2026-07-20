import { nodeClass, pathSchema } from '@astrale-os/kernel-core'
import { fn } from '@astrale-os/kernel-dsl'
import { z } from 'zod'

import { CaptureModeSchema } from '../capture/capture-session.js'
import { UuidV7Schema } from '../capture/media-manifest.js'

export const DeviceStatusSchema = z.enum(['active', 'revoked'])

/**
 * A semantically registered physical device. Transport addresses and secrets never belong here;
 * hardware-possession proof and the provisioned EdgeHost binding remain production gates.
 */
export const Device = nodeClass({
  props: {
    deviceId: UuidV7Schema,
    product: z.string().trim().min(1).max(128),
    hardwareRevision: z.string().trim().min(1).max(128).optional(),
    firmwareVersion: z.string().trim().min(1).max(128).optional(),
    status: DeviceStatusSchema,
    registeredAt: z.string().datetime(),
  },
  indexes: [
    { property: 'deviceId', type: 'btree' },
    { property: 'status', type: 'btree' },
  ],
  methods: {
    register: fn({
      static: true,
      params: {
        parent: pathSchema(),
        deviceId: UuidV7Schema,
        product: z.string().trim().min(1).max(128),
        hardwareRevision: z.string().trim().min(1).max(128).optional(),
        firmwareVersion: z.string().trim().min(1).max(128).optional(),
      },
      returns: pathSchema(),
    }),
    openCapture: fn({
      params: {
        captureId: UuidV7Schema,
        edgeHostId: UuidV7Schema,
        mode: CaptureModeSchema,
      },
      returns: pathSchema(),
    }),
  },
})

export type DeviceStatus = z.infer<typeof DeviceStatusSchema>
