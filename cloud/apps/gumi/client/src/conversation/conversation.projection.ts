import type {
  ConversationEntryModel,
  ConversationModel,
  RecordingModel,
  TranscriptModel,
  TranscriptSegmentModel,
} from './conversation.model'

interface NodeValue<Props> {
  id: string
  path: { raw: string }
  props: Props
}

interface ConversationProps {
  conversationId: string
  title?: string
  createdAt: string
}

interface MembershipProps {
  recordingId: string
  transcriptArtifactId: string
  addedAt: string
}

interface RecordingProps {
  recordingId: string
  status: 'pending-manifest' | 'ready'
  startedAt?: string
  endedAt?: string
  durationMs?: number
  codec?: string
  sampleRateHz?: number
  channels?: number
}

interface TranscriptProps {
  artifactId: string
  status: 'publishing' | 'ready'
  languageTag: string
  providerId: string
  model: string
  committedAt: string
  segmentCount: string
}

interface SegmentProps {
  index: string
  startMs: string
  endMs: string
  kind?: 'speech' | 'audio-event'
  speakerLabel?: string
  text: string
}

export function projectConversation(node: NodeValue<ConversationProps>): ConversationModel {
  return {
    id: node.id,
    path: node.path.raw,
    conversationId: node.props.conversationId,
    title: node.props.title ?? 'Untitled conversation',
    createdAt: node.props.createdAt,
  }
}

export function projectConversationEntry(input: {
  membership: NodeValue<MembershipProps>
  conversationId: string
  conversationLinks: readonly { id: string }[]
  recordings: readonly NodeValue<RecordingProps>[]
  transcripts: readonly NodeValue<TranscriptProps>[]
  actors: readonly { id: string }[]
  transcribedRecordings: readonly { id: string }[]
  segments: readonly NodeValue<SegmentProps>[]
  pending: boolean
  error?: unknown
  segmentsTruncated?: boolean
}): ConversationEntryModel {
  const base = {
    membershipId: input.membership.id,
    addedAt: input.membership.props.addedAt,
    segments: projectSegments(input.segments),
    segmentsTruncated: input.segmentsTruncated ?? false,
  }

  if (input.error) {
    return { ...base, state: 'unavailable', issue: readableError(input.error) }
  }

  const finding = relationshipFinding(input)
  if (input.pending && finding?.state === 'unavailable') return { ...base, state: 'loading' }
  if (finding) return { ...base, ...finding }

  const recording = input.recordings[0]
  const transcript = input.transcripts[0]
  return {
    ...base,
    state: 'verified',
    revalidating: input.pending,
    actorId: input.actors[0]?.id,
    recording: projectRecording(recording),
    transcript: projectTranscript(transcript),
  }
}

function relationshipFinding(input: {
  membership: NodeValue<MembershipProps>
  conversationId: string
  conversationLinks: readonly { id: string }[]
  recordings: readonly NodeValue<RecordingProps>[]
  transcripts: readonly NodeValue<TranscriptProps>[]
  actors: readonly { id: string }[]
  transcribedRecordings: readonly { id: string }[]
}): { state: 'unavailable' | 'integrity-fault'; issue: string } | undefined {
  if (input.conversationLinks.length === 0) {
    return unavailable('Conversation membership binding is not readable in this session.')
  }
  if (input.conversationLinks.length !== 1 || input.conversationLinks[0]?.id !== input.conversationId) {
    return integrityFault('Membership is not bound to this exact Conversation.')
  }
  if (input.recordings.length === 0) {
    return unavailable('Recording binding is not readable in this session.')
  }
  if (input.recordings.length !== 1) {
    return integrityFault('Membership must bind exactly one Recording.')
  }
  if (input.transcripts.length === 0) {
    return unavailable('Transcript binding is not readable in this session.')
  }
  if (input.transcripts.length !== 1) {
    return integrityFault('Membership must bind exactly one Transcript.')
  }
  if (input.actors.length === 0) {
    return unavailable('Membership actor is not readable in this session.')
  }
  if (input.actors.length !== 1) {
    return integrityFault('Membership must identify exactly one actor.')
  }

  const recording = input.recordings[0]
  const transcript = input.transcripts[0]
  if (recording.props.recordingId !== input.membership.props.recordingId) {
    return integrityFault('Recording identity does not match the immutable membership receipt.')
  }
  if (transcript.props.artifactId !== input.membership.props.transcriptArtifactId) {
    return integrityFault('Transcript identity does not match the immutable membership receipt.')
  }
  if (recording.props.status !== 'ready' || transcript.props.status !== 'ready') {
    return integrityFault('A Conversation membership may expose only ready source material.')
  }
  if (input.transcribedRecordings.length === 0) {
    return unavailable('Transcript-to-Recording proof is not readable in this session.')
  }
  if (input.transcribedRecordings.length !== 1 || input.transcribedRecordings[0]?.id !== recording.id) {
    return integrityFault('Transcript does not prove that it transcribes this exact Recording.')
  }
  return undefined
}

function unavailable(issue: string) {
  return { state: 'unavailable' as const, issue }
}

function integrityFault(issue: string) {
  return { state: 'integrity-fault' as const, issue }
}

function projectRecording(node: NodeValue<RecordingProps>): RecordingModel {
  return { id: node.id, ...node.props }
}

function projectTranscript(node: NodeValue<TranscriptProps>): TranscriptModel {
  return { id: node.id, ...node.props }
}

function projectSegments(nodes: readonly NodeValue<SegmentProps>[]): TranscriptSegmentModel[] {
  return nodes
    .map((node) => ({ id: node.id, ...node.props, kind: node.props.kind ?? 'speech' }))
    .sort((left, right) => compareUnsigned(left.index, right.index))
}

function compareUnsigned(left: string, right: string): number {
  const a = left.replace(/^0+(?=\d)/, '')
  const b = right.replace(/^0+(?=\d)/, '')
  return a.length === b.length ? a.localeCompare(b) : a.length - b.length
}

function readableError(error: unknown): string {
  return error instanceof Error ? error.message : 'Related material is not readable in this session.'
}
