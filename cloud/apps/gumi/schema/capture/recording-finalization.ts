import { nodeClass } from '@astrale-os/kernel-core'
import { z } from 'zod'

import { ManifestDigestSchema, UuidV7Schema } from './media-manifest.js'

/**
 * The single immutable terminal receipt for a Recording. Its fixed child path is the concurrency
 * arbiter; the receipt and ready Recording projection are committed in one graph mutation.
 */
export const RecordingFinalization = nodeClass({
  props: {
    manifestId: UuidV7Schema,
    manifestDigest: ManifestDigestSchema,
    captureSessionId: UuidV7Schema,
    deviceId: UuidV7Schema,
    edgeHostId: UuidV7Schema,
    finalizedAt: z.string().datetime(),
  },
  methods: {},
})
