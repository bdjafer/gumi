import { edgeClass } from '@astrale-os/kernel-core'

import { Recording } from '../capture/recording.js'
import { ConversationMembership } from './conversation-membership.js'

export const membership_has_recording = edgeClass(
  { as: 'membership', types: [ConversationMembership], cardinality: '1' as const },
  { as: 'recording', types: [Recording] },
)
