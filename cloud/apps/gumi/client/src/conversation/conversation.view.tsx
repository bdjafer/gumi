import {
  useChildren,
  useIn,
  useNode,
  useOut,
  useTargetNode,
  type AnyBoundNode,
} from '@astrale-os/shell-react'

import { schema } from '@/gumi-schema'

import { projectConversation, projectConversationEntry } from './conversation.projection'
import {
  ConversationEntry,
  ConversationLoading,
  ConversationPage,
  ConversationUnavailable,
} from './conversation.screen'

export function ConversationView() {
  const target = useTargetNode()
  const conversation = useNode(schema, 'Conversation', target.node?.id, {
    suspend: false,
    keepPrevious: true,
  })

  if (target.error || conversation.error) return <ConversationUnavailable error={target.error ?? conversation.error} />
  if (!target.node && !target.pending) return <ConversationUnavailable />
  if (!conversation.node || conversation.node.id !== target.node?.id) return <ConversationLoading />

  return <ConversationWorkspace key={conversation.node.id} conversation={conversation.node} />
}

function ConversationWorkspace({ conversation }: { conversation: AnyBoundNode<typeof schema> }) {
  const memberships = useChildren(conversation, {
    type: 'ConversationMembership',
    limit: 100,
    window: 'append',
    suspend: false,
    keepPrevious: true,
  })

  if (!conversation.is('Conversation')) return <ConversationUnavailable />
  if (memberships.pending && memberships.children.length === 0) return <ConversationLoading />
  if (memberships.error && memberships.children.length === 0) {
    return <ConversationUnavailable error={memberships.error} />
  }

  return (
    <ConversationPage
      conversation={projectConversation(conversation)}
      membershipCount={memberships.children.length}
      membershipWindowIncomplete={memberships.more.has}
      loadingMore={memberships.more.pending}
      onLoadMore={() => void memberships.more.load()}
    >
      {[...memberships.children]
        .sort((left, right) => right.props.addedAt.localeCompare(left.props.addedAt))
        .map((membership) => (
          <ConversationMembershipView
            key={membership.id}
            conversationId={conversation.id}
            membership={membership}
          />
        ))}
    </ConversationPage>
  )
}

function ConversationMembershipView({
  conversationId,
  membership,
}: {
  conversationId: string
  membership: AnyBoundNode<typeof schema>
}) {
  const conversations = useIn(membership, 'conversation_has_membership', { suspend: false, keepPrevious: true })
  const recordings = useOut(membership, 'membership_has_recording', { suspend: false, keepPrevious: true })
  const transcripts = useOut(membership, 'membership_has_transcript', { suspend: false, keepPrevious: true })
  const actors = useOut(membership, 'membership_added_by', { suspend: false, keepPrevious: true })
  const transcript = transcripts.nodes.length === 1 && transcripts.nodes[0]?.is('Transcript') ? transcripts.nodes[0] : null
  const transcribedRecordings = useOut(transcript, 'transcribes', { suspend: false, keepPrevious: true })
  const segments = useChildren(transcript, {
    type: 'TranscriptSegment',
    limit: 200,
    window: 'append',
    suspend: false,
    keepPrevious: true,
  })

  if (!membership.is('ConversationMembership')) return null
  const pending = [conversations, recordings, transcripts, actors, transcribedRecordings, segments].some((result) => result.pending)
  const error = [conversations, recordings, transcripts, actors, transcribedRecordings, segments].map((result) => result.error).find(Boolean)
  const entry = projectConversationEntry({
    membership,
    conversationId,
    conversationLinks: conversations.nodes,
    recordings: recordings.nodes.filter((node) => node.is('Recording')),
    transcripts: transcripts.nodes.filter((node) => node.is('Transcript')),
    actors: actors.nodes,
    transcribedRecordings: transcribedRecordings.nodes,
    segments: segments.children,
    pending,
    error,
    segmentsTruncated: segments.more.has,
  })

  return (
    <ConversationEntry
      entry={entry}
      loadingMoreSegments={segments.more.pending}
      onLoadMoreSegments={() => void segments.more.load()}
    />
  )
}
