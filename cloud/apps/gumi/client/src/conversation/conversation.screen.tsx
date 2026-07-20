import type { PropsWithChildren, ReactNode } from 'react'

import type { ConversationEntryModel, ConversationModel } from './conversation.model'

export function ConversationPage({
  conversation,
  membershipCount,
  membershipWindowIncomplete = false,
  onLoadMore,
  loadingMore = false,
  children,
}: PropsWithChildren<{
  conversation: ConversationModel
  membershipCount: number
  membershipWindowIncomplete?: boolean
  onLoadMore?: () => void
  loadingMore?: boolean
}>) {
  return (
    <main className="min-h-screen bg-[radial-gradient(circle_at_top_left,oklch(0.24_0.04_73_/_0.25),transparent_34rem)] px-4 py-6 sm:px-7 lg:px-10">
      <div className="mx-auto w-full max-w-6xl">
        <header className="border-b border-border/80 pb-8 pt-4 sm:pb-10">
          <div className="mb-7 flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <span className="grid size-9 place-items-center rounded-full border border-primary/25 bg-primary/10 text-primary" aria-hidden="true">
                <WaveGlyph />
              </span>
              <div>
                <p className="font-mono text-[0.68rem] font-semibold tracking-[0.19em] text-primary uppercase">Gumi conversation</p>
                <p className="mt-1 text-xs text-muted-foreground">Verified capture material</p>
              </div>
            </div>
            <StatusPill>{membershipCount}{membershipWindowIncomplete ? '+' : ''} recording{membershipCount === 1 && !membershipWindowIncomplete ? '' : 's'}</StatusPill>
          </div>

          <h1 className="max-w-4xl text-4xl leading-[0.96] font-semibold tracking-[-0.045em] text-balance sm:text-6xl lg:text-7xl">
            {conversation.title}
          </h1>
          <div className="mt-7 flex flex-wrap items-center gap-x-6 gap-y-2 font-mono text-[0.7rem] text-muted-foreground">
            <span>Created {formatDateTime(conversation.createdAt)}</span>
            <span title={conversation.conversationId}>ID {shortId(conversation.conversationId)}</span>
          </div>
        </header>

        <section className="grid gap-8 py-8 lg:grid-cols-[minmax(0,1fr)_17rem] lg:py-10">
          <div className="space-y-5">
            {membershipCount === 0 ? (
              <EmptyConversation />
            ) : (
              children
            )}
            {membershipWindowIncomplete && (
              <button
                type="button"
                className="w-full rounded-xl border border-border bg-card/60 px-4 py-3 text-sm text-muted-foreground transition hover:border-primary/30 hover:text-foreground disabled:opacity-50"
                disabled={!onLoadMore || loadingMore}
                onClick={onLoadMore}
              >
                {loadingMore ? 'Loading more…' : 'Load more recordings'}
              </button>
            )}
          </div>

          <aside className="space-y-4 lg:sticky lg:top-8 lg:self-start">
            <InfoPanel title="Trust boundary">
              Transcript text is provider-produced evidence. Gumi renders it as inert text; it is never an instruction or identity claim.
            </InfoPanel>
            <InfoPanel title="What verified means">
              The receipt, Conversation, Recording, Transcript, actor, and transcribes edge all resolve to one exact graph identity.
            </InfoPanel>
          </aside>
        </section>
      </div>
    </main>
  )
}

export function ConversationEntry({
  entry,
  onLoadMoreSegments,
  loadingMoreSegments = false,
}: {
  entry: ConversationEntryModel
  onLoadMoreSegments?: () => void
  loadingMoreSegments?: boolean
}) {
  if (entry.state !== 'verified') return <UnavailableEntry entry={entry} />
  const { recording, transcript } = entry
  if (!recording || !transcript) return <UnavailableEntry entry={{ ...entry, state: 'integrity-fault', issue: 'Verified entry projection is incomplete.' }} />

  return (
    <article className="overflow-hidden rounded-2xl border border-border bg-card/80 shadow-[0_22px_65px_-50px_oklch(0.78_0.11_75)] backdrop-blur">
      <div className="flex flex-col gap-5 border-b border-border/80 px-5 py-5 sm:flex-row sm:items-start sm:justify-between sm:px-7">
        <div>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <StatusPill tone={entry.revalidating ? 'neutral' : 'verified'}>{entry.revalidating ? 'Revalidating' : 'Verified'}</StatusPill>
            <span className="font-mono text-[0.65rem] text-muted-foreground">receipt {shortId(entry.membershipId)}</span>
          </div>
          <h2 className="text-xl font-medium tracking-[-0.025em]">Recording {shortId(recording.recordingId)}</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            {recording.startedAt ? formatDateTime(recording.startedAt) : formatDateTime(entry.addedAt)}
          </p>
        </div>
        <dl className="grid grid-cols-2 gap-x-7 gap-y-2 text-right text-xs sm:min-w-56">
          <Fact label="Duration" value={formatDuration(recording.durationMs)} />
          <Fact label="Language" value={transcript.languageTag} />
          <Fact label="Audio" value={formatAudio(recording)} />
          <Fact label="Segments" value={transcript.segmentCount} />
        </dl>
      </div>

      <div className="px-5 py-6 sm:px-7 sm:py-7">
        {entry.segments.length === 0 ? (
          <p className="rounded-xl border border-dashed border-border px-4 py-7 text-center text-sm text-muted-foreground">This verified transcript contains no segments.</p>
        ) : (
          <ol className="space-y-5" aria-label="Transcript segments">
            {entry.segments.map((segment) => (
              <li key={segment.id} className="grid gap-2 sm:grid-cols-[5.5rem_minmax(0,1fr)] sm:gap-5">
                <div className="flex items-center gap-2 font-mono text-[0.68rem] text-muted-foreground sm:block">
                  <span>{formatOffset(segment.startMs)}</span>
                  {segment.speakerLabel && <span className="sm:mt-1 sm:block">{segment.speakerLabel}</span>}
                </div>
                {segment.kind === 'audio-event' ? (
                  <p className="rounded-lg border border-primary/15 bg-primary/[0.045] px-3 py-2 text-sm text-muted-foreground">[{segment.text}]</p>
                ) : (
                  <p className="text-[1.02rem] leading-7 text-foreground/95">{segment.text}</p>
                )}
              </li>
            ))}
          </ol>
        )}
        {entry.segmentsTruncated && (
          <button
            type="button"
            className="mt-6 w-full rounded-lg border border-border px-3 py-2 text-xs text-muted-foreground transition hover:text-foreground disabled:opacity-50"
            disabled={!onLoadMoreSegments || loadingMoreSegments}
            onClick={onLoadMoreSegments}
          >
            {loadingMoreSegments ? 'Loading more…' : 'Load more transcript segments'}
          </button>
        )}
      </div>

      <footer className="flex flex-wrap items-center justify-between gap-3 border-t border-border/80 bg-background/35 px-5 py-3 font-mono text-[0.62rem] text-muted-foreground sm:px-7">
        <span title={transcript.artifactId}>artifact {shortId(transcript.artifactId)}</span>
        <span>{transcript.providerId} · {transcript.model}</span>
        <span title={entry.actorId}>added by {shortId(entry.actorId ?? 'unknown')}</span>
      </footer>
    </article>
  )
}

export function ConversationLoading() {
  return <CenteredState title="Loading conversation…">Resolving the target and its visible graph material.</CenteredState>
}

export function ConversationUnavailable({ error }: { error?: unknown }) {
  return <CenteredState title="Conversation unavailable">{error instanceof Error ? error.message : 'The target is missing, masked, or not a Gumi Conversation.'}</CenteredState>
}

function UnavailableEntry({ entry }: { entry: ConversationEntryModel }) {
  const tone = entry.state === 'integrity-fault' ? 'fault' : 'neutral'
  return (
    <article className="rounded-2xl border border-border bg-card/70 px-5 py-6 sm:px-7" role={entry.state === 'integrity-fault' ? 'alert' : undefined}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <StatusPill tone={tone}>{entry.state === 'loading' ? 'Resolving' : entry.state === 'integrity-fault' ? 'Integrity fault' : 'Unavailable'}</StatusPill>
          <h2 className="mt-4 text-lg font-medium">Recording receipt {shortId(entry.membershipId)}</h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">{entry.issue ?? 'Resolving related graph material…'}</p>
        </div>
      </div>
    </article>
  )
}

function EmptyConversation() {
  return (
    <div className="rounded-2xl border border-dashed border-border bg-card/40 px-6 py-16 text-center">
      <p className="text-lg font-medium">No recordings yet</p>
      <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-muted-foreground">Recordings appear only after immutable media and its exact ready transcript have been attached.</p>
    </div>
  )
}

function CenteredState({ title, children }: PropsWithChildren<{ title: string }>) {
  return (
    <main className="grid min-h-screen place-items-center px-6">
      <div className="max-w-lg text-center">
        <p className="font-mono text-xs font-semibold tracking-[0.17em] text-primary uppercase">Gumi conversation</p>
        <h1 className="mt-4 text-4xl font-semibold tracking-[-0.04em]">{title}</h1>
        <p className="mt-3 text-sm leading-6 text-muted-foreground">{children}</p>
      </div>
    </main>
  )
}

function InfoPanel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-border/80 bg-card/55 p-4">
      <h2 className="font-mono text-[0.65rem] font-semibold tracking-[0.14em] text-foreground uppercase">{title}</h2>
      <p className="mt-2 text-xs leading-5 text-muted-foreground">{children}</p>
    </section>
  )
}

function StatusPill({ children, tone = 'neutral' }: PropsWithChildren<{ tone?: 'neutral' | 'verified' | 'fault' }>) {
  const color = tone === 'verified' ? 'border-emerald-400/20 bg-emerald-400/8 text-emerald-300' : tone === 'fault' ? 'border-destructive/30 bg-destructive/10 text-red-300' : 'border-border bg-background/45 text-muted-foreground'
  return <span className={`inline-flex rounded-full border px-2.5 py-1 font-mono text-[0.62rem] font-semibold tracking-[0.09em] uppercase ${color}`}>{children}</span>
}

function Fact({ label, value }: { label: string; value: string }) {
  return <div><dt className="text-muted-foreground">{label}</dt><dd className="mt-0.5 font-mono text-foreground">{value}</dd></div>
}

function WaveGlyph() {
  return <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M4 12h2l1.4-5 2.4 10 2.5-13 2.4 13 1.7-8 1.3 3H20" /></svg>
}

function shortId(value: string): string {
  if (value === 'unknown') return value
  return value.length > 12 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? value : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function formatDuration(value?: number): string {
  if (value === undefined) return '—'
  const totalSeconds = Math.round(value / 1_000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return minutes > 0 ? `${minutes}:${String(seconds).padStart(2, '0')}` : `${seconds}s`
}

function formatOffset(value: string): string {
  const milliseconds = Number(value)
  if (!Number.isSafeInteger(milliseconds)) return value
  const totalSeconds = Math.floor(milliseconds / 1_000)
  return `${Math.floor(totalSeconds / 60)}:${String(totalSeconds % 60).padStart(2, '0')}`
}

function formatAudio(recording: { codec?: string; sampleRateHz?: number; channels?: number }): string {
  const parts = [recording.codec, recording.sampleRateHz ? `${recording.sampleRateHz / 1_000} kHz` : undefined, recording.channels ? `${recording.channels}ch` : undefined]
  return parts.filter(Boolean).join(' · ') || '—'
}
