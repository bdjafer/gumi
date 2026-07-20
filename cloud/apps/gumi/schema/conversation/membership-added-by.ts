import { edgeClass, KernelSchema } from '@astrale-os/kernel-core'

import { ConversationMembership } from './conversation-membership.js'

export const membership_added_by = edgeClass(
  { as: 'membership', types: [ConversationMembership], cardinality: '1' as const },
  { as: 'actor', types: [KernelSchema.interfaces.Identity] },
)
