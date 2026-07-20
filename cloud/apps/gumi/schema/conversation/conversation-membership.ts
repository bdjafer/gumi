import { nodeClass } from '@astrale-os/kernel-core'
import { z } from 'zod'

import { UuidV7Schema } from '../capture/media-manifest.js'

/** Immutable audit receipt for one Recording and its exact Transcript in a Conversation. */
export const ConversationMembership = nodeClass({
  props: {
    recordingId: UuidV7Schema,
    transcriptArtifactId: UuidV7Schema,
    addedAt: z.string().datetime(),
  },
  indexes: [
    { property: 'recordingId', type: 'btree' },
    { property: 'transcriptArtifactId', type: 'btree' },
  ],
  methods: {},
})
