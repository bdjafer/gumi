import { edgeClass } from '@astrale-os/kernel-core'

import { Transcript } from '../transcription/transcript.js'
import { ConversationMembership } from './conversation-membership.js'

export const membership_has_transcript = edgeClass(
  { as: 'membership', types: [ConversationMembership], cardinality: '1' as const },
  { as: 'transcript', types: [Transcript] },
)
