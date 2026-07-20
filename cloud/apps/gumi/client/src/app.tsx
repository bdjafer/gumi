import { ShellProvider, type ShellConfig } from '@astrale-os/shell-react'

import type { ConversationEntryModel, ConversationModel } from '@/conversation/conversation.model'
import { ConversationEntry, ConversationPage } from '@/conversation/conversation.screen'
import { ConversationView } from '@/conversation/conversation.view'

const SHELL_CONFIG: ShellConfig = { mode: 'sandboxed', initTimeoutMs: 8_000 }

export function App() {
  if (typeof window === 'undefined' || window.parent === window) return <Preview />
  return (
    <ShellProvider config={SHELL_CONFIG} fallback={<Loading />}>
      <DomainView />
    </ShellProvider>
  )
}

function DomainView() {
  return <ConversationView />
}

function Preview() {
  return (
    <ConversationPage conversation={PREVIEW_CONVERSATION} membershipCount={1}>
      <ConversationEntry entry={PREVIEW_ENTRY} />
    </ConversationPage>
  )
}

function Loading() {
  return (
    <main className="grid min-h-screen place-items-center text-muted-foreground">Connecting…</main>
  )
}

const PREVIEW_CONVERSATION: ConversationModel = {
  id: 'conversation-preview',
  path: '/preview/conversation',
  conversationId: '019b9b90-a1c2-7d34-8e56-7890abcdef12',
  title: 'Architecture walk-through',
  createdAt: '2026-07-19T08:42:00.000Z',
}

const PREVIEW_ENTRY: ConversationEntryModel = {
  membershipId: 'membership-preview-019b9b90',
  addedAt: '2026-07-19T09:18:20.000Z',
  actorId: 'identity-preview-019b9b90',
  state: 'verified',
  recording: {
    id: 'recording-node-preview',
    recordingId: '019b9b91-a1c2-7d34-8e56-7890abcdef12',
    status: 'ready',
    startedAt: '2026-07-19T08:42:00.000Z',
    endedAt: '2026-07-19T09:15:42.000Z',
    durationMs: 2_022_000,
    codec: 'opus',
    sampleRateHz: 16_000,
    channels: 1,
  },
  transcript: {
    id: 'transcript-node-preview',
    artifactId: '019b9b92-a1c2-7d34-8e56-7890abcdef12',
    status: 'ready',
    languageTag: 'en',
    providerId: 'fixture',
    model: 'deterministic-v1',
    committedAt: '2026-07-19T09:18:20.000Z',
    segmentCount: '3',
  },
  segments: [
    { id: 'segment-0', index: '0', startMs: '0', endMs: '4200', kind: 'speech', speakerLabel: 'Speaker A', text: 'The phone is an edge host, but the control plane cannot assume Android forever.' },
    { id: 'segment-1', index: '1', startMs: '4600', endMs: '9800', kind: 'speech', speakerLabel: 'Speaker B', text: 'Right. Device drivers terminate below stable ports, and the same application core can run elsewhere.' },
    { id: 'segment-2', index: '2', startMs: '10400', endMs: '11200', kind: 'audio-event', text: 'brief pause' },
  ],
  segmentsTruncated: false,
}
