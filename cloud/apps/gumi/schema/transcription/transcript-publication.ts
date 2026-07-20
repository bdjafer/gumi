import { nodeClass } from '@astrale-os/kernel-core'

import {
  PositiveUnsigned64Schema,
  Sha256DigestSchema,
  Unsigned64Schema,
  UtcTimestampSchema,
  UuidV7Schema,
} from './primitives.js'

/** Single terminal receipt committed atomically with both semantic ready states. */
export const TranscriptPublication = nodeClass({
  props: {
    processingJobId: UuidV7Schema,
    artifactId: UuidV7Schema,
    inputContentDigest: Sha256DigestSchema,
    outputContentDigest: Sha256DigestSchema,
    totalSegmentCount: Unsigned64Schema,
    pageCount: PositiveUnsigned64Schema,
    artifactCommittedAt: UtcTimestampSchema,
  },
  methods: {},
})
