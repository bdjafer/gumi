import { AbsolutePath, EDIT, rawOf, type PathLike } from '@astrale-os/kernel-core'
import { remoteMethod, type Kernel, type MethodParams, type Step } from '@astrale-os/sdk'

import type { Deps } from '#deps'
import { StableIdentityConflictError } from '#core/shared/conflict'
import {
  transcriptPagePublicationPath,
  transcriptPath,
  transcriptPublicationPath,
  transcriptSegmentPath,
  transcriptionPath,
} from '#core/shared/paths'
import {
  DerivedArtifactProjectionSchema,
  TRANSCRIPT_PAGE_SEGMENT_LIMIT,
  TranscriptPageSchema,
  type DerivedArtifactProjection,
  type TranscriptPage,
} from '#integrations/media-processing/media-processing.schemas'
import type { MediaProcessing } from '#integrations/media-processing/media-processing.port'
import { D, UuidV7Schema, schema } from '#schema'

type Params = MethodParams<typeof schema, 'Recording', 'publishTranscript'>

interface RecordingBinding {
  nodeId: string
  path: string
  manifestId: string
  manifestDigest: string
  objectContentDigest: string
  durationMs: number
}

interface TranscriptionState {
  nodeId: string
  className: string
  path: string
  props: Record<string, unknown>
  recordingNodeIds: string[]
}

interface TranscriptState {
  nodeId: string
  className: string
  path: string
  props: Record<string, unknown>
  transcriptionNodeIds: string[]
  recordingNodeIds: string[]
}

interface TerminalState {
  transcription: TranscriptionState
  transcript: TranscriptState
  receipt: { className: string; props: Record<string, unknown> } | null
}

interface ReadyReplayState {
  transcriptionPath: string
  transcriptPath: string
  status: 'ready'
  replayed: true
}

const sameQualifiedProps = (
  actual: Record<string, unknown>,
  expected: Record<string, unknown>,
): boolean => Object.entries(expected).every(([key, value]) => actual[key] === value)

async function readRecording(kernel: Kernel, recording: PathLike): Promise<RecordingBinding> {
  const node = await kernel.getOrThrow(recording, 'Transcript Recording is not reachable')
  if (node.class.raw !== D.Recording.path.class.raw) {
    throw new Error('Transcript publication requires a Gumi Recording')
  }
  if (node.props[D.Recording.status.key] !== 'ready') {
    throw new Error('Transcript publication requires a ready immutable Recording')
  }
  const manifestId = UuidV7Schema.parse(node.props[D.Recording.manifestId.key])
  const manifestDigest = String(node.props[D.Recording.manifestDigest.key] ?? '')
  const objectContentDigest = String(node.props[D.Recording.objectContentDigest.key] ?? '')
  const durationMs = node.props[D.Recording.durationMs.key]
  if (
    !/^sha256:[0-9a-f]{64}$/.test(manifestDigest) ||
    !/^sha256:[0-9a-f]{64}$/.test(objectContentDigest) ||
    typeof durationMs !== 'number' ||
    !Number.isSafeInteger(durationMs) ||
    durationMs <= 0
  ) {
    throw new Error('Ready Recording is missing its immutable processing input facts')
  }
  return {
    nodeId: node.id,
    path: node.path.raw,
    manifestId,
    manifestDigest,
    objectContentDigest,
    durationMs,
  }
}

async function readTranscription(
  kernel: Kernel,
  path: PathLike,
): Promise<TranscriptionState | null> {
  const node = await kernel.get(path)
  if (!node) return null
  const recordings = await (
    await kernel.neighbors(node.path, D.recording_has_transcription.path.class.raw, 'in')
  ).all()
  return {
    nodeId: node.id,
    className: node.class.raw,
    path: node.path.raw,
    props: node.props,
    recordingNodeIds: recordings.map(({ id }) => id),
  }
}

function assertTranscriptionIdentity(
  state: TranscriptionState,
  recording: RecordingBinding,
  params: Params,
): void {
  const expected = {
    [D.Transcription.processingJobId.key]: params.processingJobId,
    [D.Transcription.manifestId.key]: recording.manifestId,
    [D.Transcription.manifestDigest.key]: recording.manifestDigest,
    [D.Transcription.inputContentDigest.key]: recording.objectContentDigest,
    [D.Transcription.expectedOutputContentDigest.key]: params.expectedOutputContentDigest,
  }
  if (
    state.className !== D.Transcription.path.class.raw ||
    !sameQualifiedProps(state.props, expected) ||
    state.recordingNodeIds.length !== 1 ||
    state.recordingNodeIds[0] !== recording.nodeId ||
    !['publishing', 'ready'].includes(String(state.props[D.Transcription.status.key]))
  ) {
    throw new StableIdentityConflictError('Transcription', params.processingJobId)
  }
}

function transcriptQualifiedProps(
  result: DerivedArtifactProjection,
  status: 'publishing' | 'ready',
): Record<string, unknown> {
  const props: Record<string, unknown> = {
    [D.Transcript.artifactId.key]: result.artifactId,
    [D.Transcript.processingJobId.key]: result.processingJobId,
    [D.Transcript.status.key]: status,
    [D.Transcript.artifactHandle.key]: result.artifactHandle,
    [D.Transcript.manifestId.key]: result.input.manifestId,
    [D.Transcript.manifestDigest.key]: result.input.manifestDigest,
    [D.Transcript.inputContentDigest.key]: result.input.contentDigest,
    [D.Transcript.outputContentDigest.key]: result.output.contentDigest,
    [D.Transcript.outputByteLength.key]: result.output.byteLength,
    [D.Transcript.contentType.key]: result.output.contentType,
    [D.Transcript.pipelineId.key]: result.provenance.pipelineId,
    [D.Transcript.configurationDigest.key]: result.provenance.configurationDigest,
    [D.Transcript.providerId.key]: result.provenance.providerId,
    [D.Transcript.model.key]: result.provenance.model,
    [D.Transcript.modelVersion.key]: result.provenance.modelVersion,
    [D.Transcript.attemptId.key]: result.provenance.attemptId,
    [D.Transcript.generation.key]: result.provenance.generation,
    [D.Transcript.languageTag.key]: result.provenance.language.tag,
    [D.Transcript.languageBasis.key]: result.provenance.language.basis,
    [D.Transcript.providerStartedAt.key]: result.provenance.timing.providerStartedAt,
    [D.Transcript.providerCompletedAt.key]: result.provenance.timing.providerCompletedAt,
    [D.Transcript.mediaDurationMs.key]: result.provenance.timing.mediaDurationMs,
    [D.Transcript.segmentCount.key]: result.provenance.segments.count,
    [D.Transcript.timedSegmentCount.key]: result.provenance.segments.timedCount,
    [D.Transcript.committedAt.key]: result.committedAt,
  }
  if (result.provenance.segments.firstStartMs !== undefined) {
    props[D.Transcript.firstSegmentStartMs.key] = result.provenance.segments.firstStartMs
  }
  if (result.provenance.segments.lastEndMs !== undefined) {
    props[D.Transcript.lastSegmentEndMs.key] = result.provenance.segments.lastEndMs
  }
  return props
}

/** Rebuild the complete immutable publisher projection from graph-owned Transcript facts. */
function derivedArtifactProjectionFromTranscript(
  transcript: TranscriptState,
): DerivedArtifactProjection {
  const props = transcript.props
  const firstStartMs = props[D.Transcript.firstSegmentStartMs.key]
  const lastEndMs = props[D.Transcript.lastSegmentEndMs.key]
  return DerivedArtifactProjectionSchema.parse({
    schemaVersion: 'gumi.media-processing.derived-artifact-projection.v1',
    processingJobId: props[D.Transcript.processingJobId.key],
    artifactId: props[D.Transcript.artifactId.key],
    artifactHandle: props[D.Transcript.artifactHandle.key],
    input: {
      manifestId: props[D.Transcript.manifestId.key],
      manifestDigest: props[D.Transcript.manifestDigest.key],
      contentDigest: props[D.Transcript.inputContentDigest.key],
    },
    output: {
      contentDigest: props[D.Transcript.outputContentDigest.key],
      byteLength: props[D.Transcript.outputByteLength.key],
      contentType: props[D.Transcript.contentType.key],
    },
    provenance: {
      pipelineId: props[D.Transcript.pipelineId.key],
      configurationDigest: props[D.Transcript.configurationDigest.key],
      providerId: props[D.Transcript.providerId.key],
      model: props[D.Transcript.model.key],
      modelVersion: props[D.Transcript.modelVersion.key],
      attemptId: props[D.Transcript.attemptId.key],
      generation: props[D.Transcript.generation.key],
      language: {
        tag: props[D.Transcript.languageTag.key],
        basis: props[D.Transcript.languageBasis.key],
      },
      timing: {
        providerStartedAt: props[D.Transcript.providerStartedAt.key],
        providerCompletedAt: props[D.Transcript.providerCompletedAt.key],
        mediaDurationMs: props[D.Transcript.mediaDurationMs.key],
      },
      segments: {
        count: props[D.Transcript.segmentCount.key],
        timedCount: props[D.Transcript.timedSegmentCount.key],
        ...(firstStartMs === undefined ? {} : { firstStartMs }),
        ...(lastEndMs === undefined ? {} : { lastEndMs }),
      },
    },
    committedAt: props[D.Transcript.committedAt.key],
  })
}

function expectedTranscriptPageCount(totalSegmentCount: string): string {
  const total = BigInt(totalSegmentCount)
  return String(
    total === 0n
      ? 1n
      : (total + TRANSCRIPT_PAGE_SEGMENT_LIMIT - 1n) / TRANSCRIPT_PAGE_SEGMENT_LIMIT,
  )
}

async function readTranscriptState(
  kernel: Kernel,
  path: PathLike,
): Promise<TranscriptState | null> {
  const node = await kernel.get(path)
  if (!node) return null
  const [transcriptions, recordings] = await Promise.all([
    kernel
      .neighbors(node.path, D.transcription_has_transcript.path.class.raw, 'in')
      .then((page) => page.all()),
    kernel
      .neighbors(node.path, D.transcribes.path.class.raw, 'out')
      .then((page) => page.all()),
  ])
  return {
    nodeId: node.id,
    className: node.class.raw,
    path: node.path.raw,
    props: node.props,
    transcriptionNodeIds: transcriptions.map(({ id }) => id),
    recordingNodeIds: recordings.map(({ id }) => id),
  }
}

function assertTranscriptIdentity(
  transcript: TranscriptState,
  transcription: TranscriptionState,
  recording: RecordingBinding,
  result: DerivedArtifactProjection,
): void {
  const status = transcript.props[D.Transcript.status.key]
  if (
    transcript.className !== D.Transcript.path.class.raw ||
    (status !== 'publishing' && status !== 'ready') ||
    !sameQualifiedProps(
      transcript.props,
      transcriptQualifiedProps(result, status),
    ) ||
    transcript.transcriptionNodeIds.length !== 1 ||
    transcript.transcriptionNodeIds[0] !== transcription.nodeId ||
    transcript.recordingNodeIds.length !== 1 ||
    transcript.recordingNodeIds[0] !== recording.nodeId
  ) {
    throw new StableIdentityConflictError('Transcript', result.artifactId)
  }
}

async function readTerminalState(
  kernel: Kernel,
  transcription: TranscriptionState,
): Promise<TerminalState | null> {
  const artifactId = transcription.props[D.Transcription.artifactId.key]
  if (typeof artifactId !== 'string') return null
  const transcript = await readTranscriptState(kernel, transcriptPath(transcription.path))
  if (!transcript) throw new Error('Transcription artifact binding lost its Transcript')
  const receipt = await kernel.get(transcriptPublicationPath(transcription.path))
  return {
    transcription,
    transcript,
    receipt: receipt ? { className: receipt.class.raw, props: receipt.props } : null,
  }
}

async function assertReadyReplay(
  kernel: Kernel,
  state: TerminalState,
  recording: RecordingBinding,
  params: Params,
): Promise<ReadyReplayState> {
  const { transcription, transcript, receipt } = state
  assertTranscriptionIdentity(transcription, recording, params)
  const result = derivedArtifactProjectionFromTranscript(transcript)
  assertResultBinding(result, recording, params)
  assertTranscriptIdentity(transcript, transcription, recording, result)
  const pageCount = expectedTranscriptPageCount(result.provenance.segments.count)
  if (
    transcription.path !== transcriptionPath(recording.path, params.processingJobId).raw ||
    transcription.props[D.Transcription.status.key] !== 'ready' ||
    transcription.props[D.Transcription.artifactId.key] !== result.artifactId ||
    transcript.path !== transcriptPath(transcription.path).raw ||
    transcript.props[D.Transcript.status.key] !== 'ready' ||
    receipt?.className !== D.TranscriptPublication.path.class.raw ||
    receipt.props[D.TranscriptPublication.processingJobId.key] !== result.processingJobId ||
    receipt.props[D.TranscriptPublication.artifactId.key] !== result.artifactId ||
    receipt.props[D.TranscriptPublication.inputContentDigest.key] !== result.input.contentDigest ||
    receipt.props[D.TranscriptPublication.outputContentDigest.key] !== result.output.contentDigest ||
    receipt.props[D.TranscriptPublication.totalSegmentCount.key] !==
      result.provenance.segments.count ||
    receipt.props[D.TranscriptPublication.pageCount.key] !== pageCount ||
    receipt.props[D.TranscriptPublication.artifactCommittedAt.key] !== result.committedAt
  ) {
    throw new Error('Ready Transcription is missing its exact terminal publication receipt')
  }

  const total = BigInt(result.provenance.segments.count)
  const expectedPages = BigInt(pageCount)
  for (let pageIndex = 0n; pageIndex < expectedPages; pageIndex += 1n) {
    const start = total === 0n ? 0n : pageIndex * TRANSCRIPT_PAGE_SEGMENT_LIMIT
    const end = total < start + TRANSCRIPT_PAGE_SEGMENT_LIMIT
      ? total
      : start + TRANSCRIPT_PAGE_SEGMENT_LIMIT
    const pageReceipt = await kernel.get(
      transcriptPagePublicationPath(transcript.path, String(start)),
    )
    if (
      !pageReceipt ||
      pageReceipt.class.raw !== D.TranscriptPagePublication.path.class.raw ||
      pageReceipt.props[D.TranscriptPagePublication.artifactId.key] !== result.artifactId ||
      pageReceipt.props[D.TranscriptPagePublication.outputContentDigest.key] !==
        result.output.contentDigest ||
      pageReceipt.props[D.TranscriptPagePublication.totalSegmentCount.key] !==
        result.provenance.segments.count ||
      pageReceipt.props[D.TranscriptPagePublication.startIndex.key] !== String(start) ||
      pageReceipt.props[D.TranscriptPagePublication.endIndexExclusive.key] !== String(end) ||
      pageReceipt.props[D.TranscriptPagePublication.segmentCount.key] !== String(end - start)
    ) {
      throw new Error(`Ready Transcript is missing exact page receipt ${String(start)}`)
    }
  }

  return {
    transcriptionPath: transcription.path,
    transcriptPath: transcript.path,
    status: 'ready',
    replayed: true,
  }
}

function materializeReadyReplay(state: ReadyReplayState) {
  return {
    transcription: AbsolutePath.parse(state.transcriptionPath),
    transcript: AbsolutePath.parse(state.transcriptPath),
    status: state.status,
    replayed: state.replayed,
  }
}

function assertResultBinding(
  result: DerivedArtifactProjection,
  recording: RecordingBinding,
  params: Params,
): void {
  if (
    result.processingJobId !== params.processingJobId ||
    result.input.manifestId !== recording.manifestId ||
    result.input.manifestDigest !== recording.manifestDigest ||
    result.input.contentDigest !== recording.objectContentDigest ||
    result.output.contentDigest !== params.expectedOutputContentDigest ||
    BigInt(result.provenance.timing.mediaDurationMs) !== BigInt(recording.durationMs) ||
    result.provenance.segments.timedCount !== result.provenance.segments.count
  ) {
    throw new Error('Derived transcript artifact does not belong to this immutable Recording')
  }
}

function assertPageBinding(
  page: TranscriptPage,
  result: DerivedArtifactProjection,
  expectedStart: string,
): void {
  if (
    page.processingJobId !== result.processingJobId ||
    page.artifactId !== result.artifactId ||
    page.inputContentDigest !== result.input.contentDigest ||
    page.outputContentDigest !== result.output.contentDigest ||
    page.language !== result.provenance.language.tag ||
    page.mediaDurationMs !== result.provenance.timing.mediaDurationMs ||
    page.totalSegmentCount !== result.provenance.segments.count ||
    page.startIndex !== expectedStart
  ) {
    throw new Error('Transcript page does not belong to the verified derived artifact')
  }
}

async function assertCommittedPage(
  kernel: Kernel,
  transcript: TranscriptState,
  page: TranscriptPage,
): Promise<void> {
  const receipt = await kernel.get(transcriptPagePublicationPath(transcript.path, page.startIndex))
  if (
    !receipt ||
    receipt.class.raw !== D.TranscriptPagePublication.path.class.raw ||
    receipt.props[D.TranscriptPagePublication.artifactId.key] !== page.artifactId ||
    receipt.props[D.TranscriptPagePublication.outputContentDigest.key] !== page.outputContentDigest ||
    receipt.props[D.TranscriptPagePublication.totalSegmentCount.key] !== page.totalSegmentCount ||
    receipt.props[D.TranscriptPagePublication.startIndex.key] !== page.startIndex ||
    receipt.props[D.TranscriptPagePublication.endIndexExclusive.key] !== page.endIndexExclusive ||
    receipt.props[D.TranscriptPagePublication.segmentCount.key] !== String(page.segments.length)
  ) {
    throw new StableIdentityConflictError(
      'TranscriptPagePublication',
      `${page.artifactId}:${page.startIndex}`,
    )
  }
  for (const segment of page.segments) {
    const node = await kernel.get(transcriptSegmentPath(transcript.path, segment.index))
    const expected: Record<string, unknown> = {
      [D.TranscriptSegment.index.key]: segment.index,
      [D.TranscriptSegment.startMs.key]: segment.startMs,
      [D.TranscriptSegment.endMs.key]: segment.endMs,
      [D.TranscriptSegment.text.key]: segment.text,
    }
    if (segment.kind !== undefined) {
      expected[D.TranscriptSegment.kind.key] = segment.kind
    }
    if (segment.speakerLabel !== undefined) {
      expected[D.TranscriptSegment.speakerLabel.key] = segment.speakerLabel
    }
    if (
      !node ||
      node.class.raw !== D.TranscriptSegment.path.class.raw ||
      !sameQualifiedProps(node.props, expected)
    ) {
      throw new StableIdentityConflictError('TranscriptSegment', segment.index)
    }
    const owners = await (
      await kernel.neighbors(node.path, D.transcript_has_segment.path.class.raw, 'in')
    ).all()
    if (owners.length !== 1 || owners[0]?.id !== transcript.nodeId) {
      throw new StableIdentityConflictError('TranscriptSegment', segment.index)
    }
  }
}

async function commitPage(
  kernel: Kernel,
  step: Step,
  transcript: TranscriptState,
  page: TranscriptPage,
): Promise<void> {
  try {
    await step.run(`commit-transcript-page-${page.startIndex}`, () =>
      kernel.mutate((mutation) => {
        for (const segment of page.segments) {
          const props: Record<string, unknown> = {
            [D.TranscriptSegment.index.key]: segment.index,
            [D.TranscriptSegment.startMs.key]: segment.startMs,
            [D.TranscriptSegment.endMs.key]: segment.endMs,
            [D.TranscriptSegment.text.key]: segment.text,
          }
          if (segment.kind !== undefined) {
            props[D.TranscriptSegment.kind.key] = segment.kind
          }
          if (segment.speakerLabel !== undefined) {
            props[D.TranscriptSegment.speakerLabel.key] = segment.speakerLabel
          }
          const created = mutation.createNode(
            D.TranscriptSegment.path.class.raw,
            transcriptSegmentPath(transcript.path, segment.index),
            props,
          )
          mutation.link(transcript.path, D.transcript_has_segment.path.class.raw, created)
        }
        mutation.createNode(
          D.TranscriptPagePublication.path.class.raw,
          transcriptPagePublicationPath(transcript.path, page.startIndex),
          {
            [D.TranscriptPagePublication.artifactId.key]: page.artifactId,
            [D.TranscriptPagePublication.outputContentDigest.key]: page.outputContentDigest,
            [D.TranscriptPagePublication.totalSegmentCount.key]: page.totalSegmentCount,
            [D.TranscriptPagePublication.startIndex.key]: page.startIndex,
            [D.TranscriptPagePublication.endIndexExclusive.key]: page.endIndexExclusive,
            [D.TranscriptPagePublication.segmentCount.key]: String(page.segments.length),
          },
        )
      }),
    )
  } catch (error) {
    try {
      await step.run(`read-concurrent-transcript-page-${page.startIndex}`, () =>
        assertCommittedPage(kernel, transcript, page),
      )
      return
    } catch {
      throw error
    }
  }
}

export async function publishTranscript(
  recordingPath: PathLike,
  params: Params,
  deps: {
    kernel: Kernel
    mediaProcessing: MediaProcessing
    principal: PathLike
    step: Step
  },
) {
  const recording = await deps.step.run('read-transcript-recording', () =>
    readRecording(deps.kernel, recordingPath),
  )
  const intentPath = transcriptionPath(recording.path, params.processingJobId)
  let transcription = await deps.step.run('read-existing-transcription', () =>
    readTranscription(deps.kernel, intentPath),
  )
  if (transcription) {
    assertTranscriptionIdentity(transcription, recording, params)
    if (transcription.props[D.Transcription.status.key] === 'ready') {
      const terminal = await deps.step.run('read-ready-transcription', () =>
        readTerminalState(deps.kernel, transcription!),
      )
      if (!terminal) throw new Error('Ready Transcription lost its artifact binding')
      const replay = await deps.step.run('validate-ready-transcription', () =>
        assertReadyReplay(deps.kernel, terminal, recording, params),
      )
      return materializeReadyReplay(replay)
    }
  } else {
    try {
      await deps.step.run('create-transcription-intent', () =>
        deps.kernel.mutate((mutation) => {
          const created = mutation.createNode(D.Transcription.path.class.raw, intentPath, {
            [D.Transcription.processingJobId.key]: params.processingJobId,
            [D.Transcription.status.key]: 'publishing',
            [D.Transcription.manifestId.key]: recording.manifestId,
            [D.Transcription.manifestDigest.key]: recording.manifestDigest,
            [D.Transcription.inputContentDigest.key]: recording.objectContentDigest,
            [D.Transcription.expectedOutputContentDigest.key]: params.expectedOutputContentDigest,
          })
          mutation.link(recording.path, D.recording_has_transcription.path.class.raw, created)
        }),
      )
    } catch (error) {
      transcription = await deps.step.run('read-concurrent-transcription-intent', () =>
        readTranscription(deps.kernel, intentPath),
      )
      if (!transcription) throw error
      assertTranscriptionIdentity(transcription, recording, params)
    }
    transcription ??= await deps.step.run('read-created-transcription-intent', () =>
      readTranscription(deps.kernel, intentPath),
    )
    if (!transcription) throw new Error('Transcription intent commit was not observable')
  }

  const result = DerivedArtifactProjectionSchema.parse(
    await deps.step.run('resolve-derived-transcript-artifact', async () => {
      const reader = await deps.mediaProcessing.issueRecordingBoundReader({
        actualCaller: rawOf(deps.principal),
        recordingPath: recording.path,
        manifestId: recording.manifestId,
        manifestDigest: recording.manifestDigest,
        objectContentDigest: recording.objectContentDigest,
      })
      return reader.resolveDerivedArtifact({
        processingJobId: params.processingJobId,
        expectedOutputContentDigest: params.expectedOutputContentDigest,
      })
    }),
  )
  assertResultBinding(result, recording, params)

  const boundArtifactId = transcription.props[D.Transcription.artifactId.key]
  if (boundArtifactId !== undefined && boundArtifactId !== result.artifactId) {
    throw new StableIdentityConflictError('Transcription artifact', params.processingJobId)
  }

  // The semantic path is fixed by the Recording-owned processing-job identity, not by an
  // externally returned artifact ID. Competing or drifted artifact results therefore contend on
  // one graph identity and cannot fork sibling Transcripts before terminal publication.
  const resultTranscriptPath = transcriptPath(intentPath)
  let transcript = await deps.step.run('read-existing-transcript', () =>
    readTranscriptState(deps.kernel, resultTranscriptPath),
  )
  if (transcript) {
    assertTranscriptIdentity(transcript, transcription, recording, result)
  } else {
    try {
      await deps.step.run('create-publishing-transcript', () =>
        deps.kernel.mutate((mutation) => {
          const created = mutation.createNode(
            D.Transcript.path.class.raw,
            resultTranscriptPath,
            transcriptQualifiedProps(result, 'publishing'),
          )
          mutation.updateNode(D.Transcription.path.class.raw, intentPath, {
            [D.Transcription.artifactId.key]: result.artifactId,
          })
          mutation.link(intentPath, D.transcription_has_transcript.path.class.raw, created)
          mutation.link(created, D.transcribes.path.class.raw, recording.path)
        }),
      )
    } catch (error) {
      transcript = await deps.step.run('read-concurrent-publishing-transcript', () =>
        readTranscriptState(deps.kernel, resultTranscriptPath),
      )
      if (!transcript) throw error
      assertTranscriptIdentity(transcript, transcription, recording, result)
    }
    transcript ??= await deps.step.run('read-created-publishing-transcript', () =>
      readTranscriptState(deps.kernel, resultTranscriptPath),
    )
    if (!transcript) throw new Error('Transcript commit was not observable')
  }

  let startIndex = '0'
  let firstPage = true
  let observedFirstStartMs: string | undefined
  let observedLastEndMs: string | undefined
  while (firstPage || BigInt(startIndex) < BigInt(result.provenance.segments.count)) {
    firstPage = false
    const page = TranscriptPageSchema.parse(
      await deps.step.run(`read-transcript-page-${startIndex}`, async () => {
        const reader = await deps.mediaProcessing.issueRecordingBoundReader({
          actualCaller: rawOf(deps.principal),
          recordingPath: recording.path,
          manifestId: recording.manifestId,
          manifestDigest: recording.manifestDigest,
          objectContentDigest: recording.objectContentDigest,
        })
        return reader.readTranscriptPage({
          processingJobId: params.processingJobId,
          expectedArtifactId: result.artifactId,
          expectedOutputContentDigest: params.expectedOutputContentDigest,
          startIndex,
        })
      }),
    )
    assertPageBinding(page, result, startIndex)
    const firstSegment = page.segments[0]
    const lastSegment = page.segments.at(-1)
    if (
      firstSegment !== undefined &&
      observedLastEndMs !== undefined &&
      BigInt(firstSegment.startMs) < BigInt(observedLastEndMs)
    ) {
      throw new Error('Transcript page timing overlaps or moves backward across a page boundary')
    }
    observedFirstStartMs ??= firstSegment?.startMs
    observedLastEndMs = lastSegment?.endMs ?? observedLastEndMs
    await commitPage(deps.kernel, deps.step, transcript, page)
    if (page.nextStartIndex === undefined) break
    startIndex = page.nextStartIndex
  }
  if (
    observedFirstStartMs !== result.provenance.segments.firstStartMs ||
    observedLastEndMs !== result.provenance.segments.lastEndMs
  ) {
    throw new Error('Transcript pages do not reproduce the immutable aggregate timing bounds')
  }

  try {
    await deps.step.run('commit-transcript-publication', () =>
      deps.kernel.mutate((mutation) => {
        mutation.createNode(
          D.TranscriptPublication.path.class.raw,
          transcriptPublicationPath(intentPath),
          {
            [D.TranscriptPublication.processingJobId.key]: result.processingJobId,
            [D.TranscriptPublication.artifactId.key]: result.artifactId,
            [D.TranscriptPublication.inputContentDigest.key]: result.input.contentDigest,
            [D.TranscriptPublication.outputContentDigest.key]: result.output.contentDigest,
            [D.TranscriptPublication.totalSegmentCount.key]: result.provenance.segments.count,
            [D.TranscriptPublication.pageCount.key]: expectedTranscriptPageCount(
              result.provenance.segments.count,
            ),
            [D.TranscriptPublication.artifactCommittedAt.key]: result.committedAt,
          },
        )
        mutation.updateNode(D.Transcript.path.class.raw, resultTranscriptPath, {
          [D.Transcript.status.key]: 'ready',
        })
        mutation.updateNode(D.Transcription.path.class.raw, intentPath, {
          [D.Transcription.status.key]: 'ready',
          [D.Transcription.artifactId.key]: result.artifactId,
        })
      }),
    )
  } catch (error) {
    const convergedTranscription = await deps.step.run('read-concurrent-transcript-publication', () =>
      readTranscription(deps.kernel, intentPath),
    )
    if (!convergedTranscription) throw error
    assertTranscriptionIdentity(convergedTranscription, recording, params)
    const terminal = await deps.step.run('read-concurrent-ready-transcript', () =>
      readTerminalState(deps.kernel, convergedTranscription),
    )
    if (terminal) {
      const replay = await deps.step.run('validate-concurrent-ready-transcription', () =>
        assertReadyReplay(deps.kernel, terminal, recording, params),
      )
      return materializeReadyReplay(replay)
    }
    throw error
  }

  return {
    transcription: intentPath,
    transcript: resultTranscriptPath,
    status: 'ready' as const,
    replayed: false,
  }
}

export function authorizePublishTranscript(input: {
  kernel: Kernel
  principal: PathLike
  recording: PathLike
}) {
  return input.kernel.auth.require({
    who: input.principal,
    on: input.recording,
    perms: EDIT,
    context: 'Recording.publishTranscript',
  })
}

export const publishTranscriptMethod = remoteMethod<Deps>()(
  schema,
  'Recording',
  'publishTranscript',
  {
    authorize: ({ auth, kernel, self }) =>
      authorizePublishTranscript({ kernel, principal: auth.principal, recording: self.path }),
    execute: ({ auth, deps, kernel, params, self, step }) =>
      publishTranscript(self.path, params, {
        kernel,
        mediaProcessing: deps.mediaProcessing,
        principal: auth.principal,
        step,
      }),
  },
)
