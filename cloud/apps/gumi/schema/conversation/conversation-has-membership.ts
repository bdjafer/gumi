import { edgeClass } from '@astrale-os/kernel-core'

import { Conversation } from './conversation.js'
import { ConversationMembership } from './conversation-membership.js'

export const conversation_has_membership = edgeClass(
  { as: 'conversation', types: [Conversation] },
  { as: 'membership', types: [ConversationMembership], cardinality: '1' as const },
)
