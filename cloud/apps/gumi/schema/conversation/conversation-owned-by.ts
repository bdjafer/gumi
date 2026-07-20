import { edgeClass, KernelSchema } from '@astrale-os/kernel-core'

import { Conversation } from './conversation.js'

export const conversation_owned_by = edgeClass(
  { as: 'conversation', types: [Conversation], cardinality: '1' as const },
  { as: 'owner', types: [KernelSchema.interfaces.Identity] },
)
