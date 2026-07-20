export type ConversationEntryState = 'loading' | 'verified' | 'unavailable' | 'integrity-fault'

export interface ConversationModel {
  id: string
  path: string
  conversationId: string
  title: string
  createdAt: string
}

export interface RecordingModel {
  id: string
  recordingId: string
  status: 'pending-manifest' | 'ready'
  startedAt?: string
  endedAt?: string
  durationMs?: number
  codec?: string
  sampleRateHz?: number
  channels?: number
}

export interface TranscriptModel {
  id: string
  artifactId: string
  status: 'publishing' | 'ready'
  languageTag: string
  providerId: string
  model: string
  committedAt: string
  segmentCount: string
}

export interface TranscriptSegmentModel {
  id: string
  index: string
  startMs: string
  endMs: string
  kind: 'speech' | 'audio-event'
  speakerLabel?: string
  text: string
}

export interface ConversationEntryModel {
  membershipId: string
  addedAt: string
  actorId?: string
  state: ConversationEntryState
  revalidating?: boolean
  issue?: string
  recording?: RecordingModel
  transcript?: TranscriptModel
  segments: readonly TranscriptSegmentModel[]
  segmentsTruncated: boolean
}
