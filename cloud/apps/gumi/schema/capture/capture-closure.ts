import { nodeClass } from '@astrale-os/kernel-core'
import { z } from 'zod'

/** The single immutable terminal event that arbitrates concurrent CaptureSession.close calls. */
export const CaptureClosure = nodeClass({
  props: {
    closedAt: z.string().datetime(),
  },
  methods: {},
})
