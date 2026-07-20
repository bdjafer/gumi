import { nodeClass } from '@astrale-os/kernel-core'

import {
  Sha256DigestSchema,
  Unsigned64Schema,
  UuidV7Schema,
} from './primitives.js'

/** Immutable per-page receipt used to converge page imports across retries and races. */
export const TranscriptPagePublication = nodeClass({
  props: {
    artifactId: UuidV7Schema,
    outputContentDigest: Sha256DigestSchema,
    totalSegmentCount: Unsigned64Schema,
    startIndex: Unsigned64Schema,
    endIndexExclusive: Unsigned64Schema,
    segmentCount: Unsigned64Schema,
  },
  methods: {},
})
