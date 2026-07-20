import { nodeClass, pathSchema } from '@astrale-os/kernel-core'
import { fn } from '@astrale-os/kernel-dsl'
import { z } from 'zod'

import { icon } from '../../icons.js'
import { UuidV7Schema } from '../capture/media-manifest.js'

export const ConversationTitleSchema = z.string().trim().min(1).max(240)

const CONVERSATION_ICON = icon(
  '<path d="M7 17.5 3.5 20v-4.5A7 7 0 0 1 8 3h5a7 7 0 0 1 0 14H7Z"/><path d="M8 8h5M8 12h3"/>',
)

/** A durable grouping of meaningful, immutable Gumi material. */
export const Conversation = nodeClass({
  icon: CONVERSATION_ICON,
  props: {
    conversationId: UuidV7Schema,
    title: ConversationTitleSchema.optional(),
    createdAt: z.string().datetime(),
  },
  indexes: [
    { property: 'conversationId', type: 'btree' },
    { property: 'createdAt', type: 'btree' },
  ],
  methods: {
    create: fn({
      static: true,
      params: {
        parent: pathSchema(),
        conversationId: UuidV7Schema,
        title: ConversationTitleSchema.optional(),
      },
      returns: pathSchema(),
    }),
    addRecording: fn({
      params: {
        recording: pathSchema(),
        transcript: pathSchema(),
      },
      returns: z.object({
        membership: pathSchema(),
        replayed: z.boolean(),
      }),
    }),
  },
})
