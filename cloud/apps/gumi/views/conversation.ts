import { selfOf } from '@astrale-os/kernel-dsl'
import { defineView } from '@astrale-os/sdk'

import { Conversation } from '#schema'

export const conversation = defineView({
  auth: 'required',
  mount: '/ui/conversation',
  viewFor: selfOf(Conversation),
  description: 'Inspect one Conversation and its exact immutable Recording and Transcript receipts.',
})

