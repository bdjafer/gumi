import { defineDomain } from '@astrale-os/sdk'

import { deps } from '#deps'
import { methods } from '#runtime'
import { schema } from '#schema'
import { views } from '#views'

export const domain = defineDomain({
  schema,
  methods,
  deps,
  views,
})
