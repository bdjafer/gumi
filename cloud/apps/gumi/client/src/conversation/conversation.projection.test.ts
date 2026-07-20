import { describe, expect, it } from 'vitest'

import { projectConversationEntry } from './conversation.projection'

const membership = {
  id: 'membership-1',
  path: { raw: '/conversation/recording-1' },
  props: {
    recordingId: 'recording-id',
    transcriptArtifactId: 'artifact-id',
    addedAt: '2026-07-19T10:00:00.000Z',
  },
}
const recording = {
  id: 'recording-node',
  path: { raw: '/recording' },
  props: { recordingId: 'recording-id', status: 'ready' as const },
}
const transcript = {
  id: 'transcript-node',
  path: { raw: '/transcript' },
  props: {
    artifactId: 'artifact-id',
    status: 'ready' as const,
    languageTag: 'en',
    providerId: 'provider',
    model: 'model',
    committedAt: '2026-07-19T10:00:00.000Z',
    segmentCount: '2',
  },
}

describe('Conversation projection', () => {
  it('projects only an exact, ready, actor-bound receipt as verified', () => {
    const result = projectConversationEntry({
      membership,
      conversationId: 'conversation-node',
      conversationLinks: [{ id: 'conversation-node' }],
      recordings: [recording],
      transcripts: [transcript],
      actors: [{ id: 'actor-node' }],
      transcribedRecordings: [{ id: 'recording-node' }],
      segments: [
        { id: 'segment-10', path: { raw: '/segment-10' }, props: { index: '10', startMs: '10', endMs: '20', text: 'second' } },
        { id: 'segment-2', path: { raw: '/segment-2' }, props: { index: '2', startMs: '0', endMs: '10', kind: 'audio-event', text: 'tone' } },
      ],
      pending: false,
    })

    expect(result.state).toBe('verified')
    expect(result.actorId).toBe('actor-node')
    expect(result.segments.map((segment) => segment.index)).toEqual(['2', '10'])
    expect(result.segments[0]?.kind).toBe('audio-event')
    expect(result.segments[1]?.kind).toBe('speech')
  })

  it.each([
    ['foreign conversation', { conversationLinks: [{ id: 'other' }] }],
    ['substituted recording', { recordings: [{ ...recording, props: { ...recording.props, recordingId: 'other' } }] }],
    ['substituted transcript', { transcripts: [{ ...transcript, props: { ...transcript.props, artifactId: 'other' } }] }],
    ['wrong transcribes edge', { transcribedRecordings: [{ id: 'other' }] }],
  ])('rejects %s instead of guessing', (_label, override) => {
    const result = projectConversationEntry({
      membership,
      conversationId: 'conversation-node',
      conversationLinks: [{ id: 'conversation-node' }],
      recordings: [recording],
      transcripts: [transcript],
      actors: [{ id: 'actor-node' }],
      transcribedRecordings: [{ id: 'recording-node' }],
      segments: [],
      pending: false,
      ...override,
    })
    expect(result.state).toBe('integrity-fault')
  })

  it.each([
    ['conversation link', { conversationLinks: [] }],
    ['recording', { recordings: [] }],
    ['transcript', { transcripts: [] }],
    ['actor', { actors: [] }],
    ['transcribes proof', { transcribedRecordings: [] }],
  ])('treats a missing or masked %s as unavailable, not corrupt', (_label, override) => {
    const result = projectConversationEntry({
      membership,
      conversationId: 'conversation-node',
      conversationLinks: [{ id: 'conversation-node' }],
      recordings: [recording],
      transcripts: [transcript],
      actors: [{ id: 'actor-node' }],
      transcribedRecordings: [{ id: 'recording-node' }],
      segments: [],
      pending: false,
      ...override,
    })
    expect(result.state).toBe('unavailable')
  })

  it('keeps last-good exact material visible but marks it as revalidating', () => {
    const result = projectConversationEntry({
      membership,
      conversationId: 'conversation-node',
      conversationLinks: [{ id: 'conversation-node' }],
      recordings: [recording],
      transcripts: [transcript],
      actors: [{ id: 'actor-node' }],
      transcribedRecordings: [{ id: 'recording-node' }],
      segments: [],
      pending: true,
    })
    expect(result).toMatchObject({ state: 'verified', revalidating: true })
  })

  it('does not convert a masked relation into an integrity claim', () => {
    const result = projectConversationEntry({
      membership,
      conversationId: 'conversation-node',
      conversationLinks: [],
      recordings: [],
      transcripts: [],
      actors: [],
      transcribedRecordings: [],
      segments: [],
      pending: false,
      error: new Error('masked'),
    })
    expect(result).toMatchObject({ state: 'unavailable', issue: 'masked' })
  })
})
