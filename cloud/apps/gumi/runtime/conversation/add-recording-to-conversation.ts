import { EDIT, READ, rawOf, type PathLike } from '@astrale-os/kernel-core'
import { remoteMethod, type Kernel, type MethodParams, type Step } from '@astrale-os/sdk'

import type { Deps } from '#deps'
import { StableIdentityConflictError } from '#core/shared/conflict'
import { conversationMembershipPath } from '#core/shared/paths'
import { D, UtcTimestampSchema, UuidV7Schema, schema } from '#schema'

type Params = MethodParams<typeof schema, 'Conversation', 'addRecording'>

interface SourceState {
  conversationClass: string
  recordingClass: string
  recordingId: unknown
  recordingStatus: unknown
  recordingNodeId: string
  transcriptClass: string
  transcriptArtifactId: unknown
  transcriptStatus: unknown
  transcribedRecordingIds: string[]
  principalId: string
}

interface MembershipState {
  className: string
  recordingId: unknown
  transcriptArtifactId: unknown
  addedAt: unknown
  conversationIds: string[]
  recordingIds: string[]
  transcriptIds: string[]
  actorIds: string[]
}

async function readSourceState(
  conversation: PathLike,
  params: Params,
  principal: PathLike,
  kernel: Kernel,
): Promise<SourceState> {
  const [conversationNode, recording, transcript, principalNode] = await Promise.all([
    kernel.getOrThrow(conversation, 'Conversation.addRecording receiver is not reachable'),
    kernel.getOrThrow(params.recording, 'Conversation.addRecording Recording is not reachable'),
    kernel.getOrThrow(params.transcript, 'Conversation.addRecording Transcript is not reachable'),
    kernel.getOrThrow(principal, 'Conversation.addRecording principal is not reachable'),
  ])
  const transcribedRecordings = await kernel
    .neighbors(transcript.path, D.transcribes.path.class.raw, 'out')
    .then((page) => page.all())
  return {
    conversationClass: conversationNode.class.raw,
    recordingClass: recording.class.raw,
    recordingId: recording.props[D.Recording.recordingId.key],
    recordingStatus: recording.props[D.Recording.status.key],
    recordingNodeId: recording.id,
    transcriptClass: transcript.class.raw,
    transcriptArtifactId: transcript.props[D.Transcript.artifactId.key],
    transcriptStatus: transcript.props[D.Transcript.status.key],
    transcribedRecordingIds: transcribedRecordings.map((node) => node.id),
    principalId: principalNode.id,
  }
}

function validateSourceState(state: SourceState): { recordingId: string; artifactId: string } {
  if (state.conversationClass !== D.Conversation.path.class.raw) {
    throw new Error('Conversation.addRecording requires a Gumi Conversation')
  }
  if (state.recordingClass !== D.Recording.path.class.raw || state.recordingStatus !== 'ready') {
    throw new Error('Conversation.addRecording requires a ready Gumi Recording')
  }
  if (state.transcriptClass !== D.Transcript.path.class.raw || state.transcriptStatus !== 'ready') {
    throw new Error('Conversation.addRecording requires a ready Gumi Transcript')
  }
  const recordingId = UuidV7Schema.safeParse(state.recordingId)
  const artifactId = UuidV7Schema.safeParse(state.transcriptArtifactId)
  if (!recordingId.success || !artifactId.success) {
    throw new Error('Conversation.addRecording source identities are malformed')
  }
  if (
    state.transcribedRecordingIds.length !== 1 ||
    state.transcribedRecordingIds[0] !== state.recordingNodeId
  ) {
    throw new Error('Conversation.addRecording Transcript does not transcribe the exact Recording')
  }
  return { recordingId: recordingId.data, artifactId: artifactId.data }
}

async function readMembershipState(path: PathLike, kernel: Kernel): Promise<MembershipState | null> {
  const node = await kernel.get(path)
  if (!node) return null
  const [conversations, recordings, transcripts, actors] = await Promise.all([
    kernel.neighbors(node.path, D.conversation_has_membership.path.class.raw, 'in').then((page) => page.all()),
    kernel.neighbors(node.path, D.membership_has_recording.path.class.raw, 'out').then((page) => page.all()),
    kernel.neighbors(node.path, D.membership_has_transcript.path.class.raw, 'out').then((page) => page.all()),
    kernel.neighbors(node.path, D.membership_added_by.path.class.raw, 'out').then((page) => page.all()),
  ])
  return {
    className: node.class.raw,
    recordingId: node.props[D.ConversationMembership.recordingId.key],
    transcriptArtifactId: node.props[D.ConversationMembership.transcriptArtifactId.key],
    addedAt: node.props[D.ConversationMembership.addedAt.key],
    conversationIds: conversations.map((candidate) => candidate.id),
    recordingIds: recordings.map((candidate) => candidate.id),
    transcriptIds: transcripts.map((candidate) => candidate.id),
    actorIds: actors.map((candidate) => candidate.id),
  }
}

function matchesMembership(
  state: MembershipState,
  source: SourceState,
  identities: { recordingId: string; artifactId: string },
  nodeIds: { conversation: string; transcript: string },
): boolean {
  return (
    state.className === D.ConversationMembership.path.class.raw &&
    state.recordingId === identities.recordingId &&
    state.transcriptArtifactId === identities.artifactId &&
    UtcTimestampSchema.safeParse(state.addedAt).success &&
    state.conversationIds.length === 1 &&
    state.conversationIds[0] === nodeIds.conversation &&
    state.recordingIds.length === 1 &&
    state.recordingIds[0] === source.recordingNodeId &&
    state.transcriptIds.length === 1 &&
    state.transcriptIds[0] === nodeIds.transcript &&
    state.actorIds.length === 1 &&
    state.actorIds[0] === source.principalId
  )
}

export async function addRecordingToConversation(
  conversation: PathLike,
  params: Params,
  deps: { kernel: Kernel; principal: PathLike; step: Step },
): Promise<{ membership: ReturnType<typeof conversationMembershipPath>; replayed: boolean }> {
  const source = await deps.step.run('read-conversation-membership-sources', () =>
    readSourceState(conversation, params, deps.principal, deps.kernel),
  )
  const identities = validateSourceState(source)
  const [conversationNode, transcriptNode] = await deps.step.run(
    'resolve-conversation-membership-node-ids',
    async () => {
      const [conversationValue, transcriptValue] = await Promise.all([
        deps.kernel.getOrThrow(conversation),
        deps.kernel.getOrThrow(params.transcript),
      ])
      return [conversationValue.id, transcriptValue.id] as [string, string]
    },
  )
  const path = conversationMembershipPath(conversation, identities.recordingId)
  const existing = await deps.step.run('read-existing-conversation-membership', () =>
    readMembershipState(path, deps.kernel),
  )
  if (existing) {
    if (!matchesMembership(existing, source, identities, {
      conversation: conversationNode,
      transcript: transcriptNode,
    })) {
      throw new StableIdentityConflictError('ConversationMembership', identities.recordingId)
    }
    return { membership: path, replayed: true }
  }

  const addedAt = await deps.step.run('read-conversation-membership-time', () =>
    new Date().toISOString(),
  )
  try {
    await deps.step.run('commit-conversation-membership', () =>
      deps.kernel.mutate((mutation) => {
        const membership = mutation.createNode(D.ConversationMembership.path.class.raw, path, {
          [D.ConversationMembership.recordingId.key]: identities.recordingId,
          [D.ConversationMembership.transcriptArtifactId.key]: identities.artifactId,
          [D.ConversationMembership.addedAt.key]: addedAt,
        })
        mutation.link(conversation, D.conversation_has_membership.path.class.raw, membership)
        mutation.link(membership, D.membership_has_recording.path.class.raw, params.recording)
        mutation.link(membership, D.membership_has_transcript.path.class.raw, params.transcript)
        mutation.link(membership, D.membership_added_by.path.class.raw, deps.principal)
      }),
    )
  } catch (error) {
    const converged = await deps.step.run('read-concurrent-conversation-membership', () =>
      readMembershipState(path, deps.kernel),
    )
    if (
      converged &&
      matchesMembership(converged, source, identities, {
        conversation: conversationNode,
        transcript: transcriptNode,
      })
    ) {
      return { membership: path, replayed: true }
    }
    throw error
  }
  return { membership: path, replayed: false }
}

export function authorizeAddRecordingToConversation(input: {
  kernel: Kernel
  principal: PathLike
  conversation: PathLike
  recording: PathLike
  transcript: PathLike
}) {
  return Promise.all([
    input.kernel.auth.require({
      who: input.principal,
      on: input.conversation,
      perms: EDIT,
      context: 'Conversation.addRecording conversation',
    }),
    input.kernel.auth.require({
      who: input.principal,
      on: input.recording,
      perms: READ,
      context: 'Conversation.addRecording recording',
    }),
    input.kernel.auth.require({
      who: input.principal,
      on: input.transcript,
      perms: READ,
      context: 'Conversation.addRecording transcript',
    }),
  ]).then(() => undefined)
}

export const addRecordingToConversationMethod = remoteMethod<Deps>()(
  schema,
  'Conversation',
  'addRecording',
  {
    authorize: ({ auth, kernel, params, self }) =>
      authorizeAddRecordingToConversation({
        kernel,
        principal: auth.principal,
        conversation: self.path,
        recording: params.recording,
        transcript: params.transcript,
      }),
    execute: ({ auth, kernel, params, self, step }) =>
      addRecordingToConversation(self.path, params, {
        kernel,
        principal: rawOf(auth.principal),
        step,
      }),
  },
)
