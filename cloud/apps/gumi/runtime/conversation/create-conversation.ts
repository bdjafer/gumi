import { EDIT, rawOf, type PathLike } from '@astrale-os/kernel-core'
import { remoteMethod, type Kernel, type MethodParams, type Step } from '@astrale-os/sdk'

import type { Deps } from '#deps'
import { StableIdentityConflictError } from '#core/shared/conflict'
import { conversationPath } from '#core/shared/paths'
import { D, UtcTimestampSchema, schema } from '#schema'

type Params = MethodParams<typeof schema, 'Conversation', 'create'>

interface ConversationState {
  className: string
  conversationId: unknown
  title: unknown
  createdAt: unknown
  ownerIds: string[]
  principalId: string
}

async function readConversationState(
  path: PathLike,
  principal: PathLike,
  kernel: Kernel,
): Promise<ConversationState | null> {
  const node = await kernel.get(path)
  if (!node) return null
  const [owners, principalNode] = await Promise.all([
    kernel.neighbors(node.path, D.conversation_owned_by.path.class.raw, 'out').then((page) => page.all()),
    kernel.getOrThrow(principal, 'Conversation.create principal is not reachable'),
  ])
  return {
    className: node.class.raw,
    conversationId: node.props[D.Conversation.conversationId.key],
    title: node.props[D.Conversation.title.key],
    createdAt: node.props[D.Conversation.createdAt.key],
    ownerIds: owners.map((owner) => owner.id),
    principalId: principalNode.id,
  }
}

function matchesConversation(state: ConversationState, params: Params): boolean {
  return (
    state.className === D.Conversation.path.class.raw &&
    state.conversationId === params.conversationId &&
    state.title === params.title &&
    UtcTimestampSchema.safeParse(state.createdAt).success &&
    state.ownerIds.length === 1 &&
    state.ownerIds[0] === state.principalId
  )
}

export async function createConversation(
  params: Params,
  deps: { kernel: Kernel; principal: PathLike; step: Step },
) {
  const parent = await deps.step.run('resolve-conversation-parent', async () => {
    const node = await deps.kernel.getOrThrow(
      params.parent,
      'Conversation.create parent is not reachable',
    )
    return node.path.raw
  })
  const path = conversationPath(parent, params.conversationId)
  const existing = await deps.step.run('read-existing-conversation', () =>
    readConversationState(path, deps.principal, deps.kernel),
  )
  if (existing) {
    if (!matchesConversation(existing, params)) {
      throw new StableIdentityConflictError('Conversation', params.conversationId)
    }
    return path
  }

  const createdAt = await deps.step.run('read-conversation-creation-time', () =>
    new Date().toISOString(),
  )
  try {
    await deps.step.run('create-conversation', () =>
      deps.kernel.mutate((mutation) => {
        const conversation = mutation.createNode(D.Conversation.path.class.raw, path, {
          [D.Conversation.conversationId.key]: params.conversationId,
          ...(params.title ? { [D.Conversation.title.key]: params.title } : {}),
          [D.Conversation.createdAt.key]: createdAt,
        })
        mutation.link(conversation, D.conversation_owned_by.path.class.raw, deps.principal)
      }),
    )
  } catch (error) {
    const converged = await deps.step.run('read-concurrent-conversation', () =>
      readConversationState(path, deps.principal, deps.kernel),
    )
    if (converged && matchesConversation(converged, params)) return path
    throw error
  }
  return path
}

export function authorizeCreateConversation(input: {
  kernel: Kernel
  principal: PathLike
  parent: PathLike
}) {
  return input.kernel.auth.require({
    who: input.principal,
    on: input.parent,
    perms: EDIT,
    context: 'Conversation.create',
  })
}

export const createConversationMethod = remoteMethod<Deps>()(
  schema,
  'Conversation',
  'create',
  {
    authorize: ({ auth, kernel, params }) =>
      authorizeCreateConversation({
        kernel,
        principal: auth.principal,
        parent: params.parent,
      }),
    execute: ({ auth, kernel, params, step }) =>
      createConversation(params, { kernel, principal: rawOf(auth.principal), step }),
  },
)
