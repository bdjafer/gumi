import { EDIT, rawOf, type PathLike } from '@astrale-os/kernel-core'
import { remoteMethod, type Kernel, type MethodParams, type Step } from '@astrale-os/sdk'

import type { Deps } from '#deps'
import { assertManifestRange } from '#core/capture/manifest'
import { StableIdentityConflictError } from '#core/shared/conflict'
import { recordingFinalizationPath, recordingPath } from '#core/shared/paths'
import type { MediaIngest } from '#integrations/media-ingest/media-ingest.port'
import {
  D,
  ImmutableManifestProjectionSchema,
  UtcTimestampSchema,
  UuidV7Schema,
  schema,
} from '#schema'

type Params = MethodParams<typeof schema, 'CaptureSession', 'finalizeRecording'>

interface CaptureManifestBinding {
  captureNodeId: string
  capturePath: string
  captureId: string
  devicePath: string
  deviceId: string
  edgeHostId: string
  status: unknown
}

interface RecordingState {
  class: string
  props: Record<string, unknown>
  captureNodeIds: string[]
  finalization: { class: string; props: Record<string, unknown> } | null
}

async function readRecordingState(
  kernel: Kernel,
  recording: PathLike,
  finalization: PathLike,
): Promise<RecordingState | null> {
  const node = await kernel.get(recording)
  if (!node) return null
  const [captures, receipt] = await Promise.all([
    kernel
      .neighbors(node.path, D.capture_has_recording.path.class.raw, 'in')
      .then((page) => page.all()),
    kernel.get(finalization),
  ])
  return {
    class: node.class.raw,
    props: node.props,
    captureNodeIds: captures.map((capture) => capture.id),
    finalization: receipt ? { class: receipt.class.raw, props: receipt.props } : null,
  }
}

function assertRecordingIdentity(
  existing: RecordingState,
  params: Params,
  capture: CaptureManifestBinding,
): void {
  const same =
    existing.class === D.Recording.path.class.raw &&
    existing.props[D.Recording.recordingId.key] === params.recordingId &&
    existing.props[D.Recording.manifestId.key] === params.manifestId &&
    existing.props[D.Recording.manifestDigest.key] === params.manifestDigest &&
    existing.captureNodeIds.length === 1 &&
    existing.captureNodeIds[0] === capture.captureNodeId
  if (!same) throw new StableIdentityConflictError('Recording', params.recordingId)
}

function readyReplay(
  existing: RecordingState,
  capture: CaptureManifestBinding,
  recording: string,
): { recording: string; status: 'ready'; replayed: true } | null {
  if (existing.props[D.Recording.status.key] !== 'ready') return null

  const storedProjection = ImmutableManifestProjectionSchema.parse({
    schemaVersion: 'gumi.media-ingest.immutable-manifest-projection.v1',
    manifestId: existing.props[D.Recording.manifestId.key],
    manifestDigest: existing.props[D.Recording.manifestDigest.key],
    captureSessionId: existing.props[D.Recording.captureSessionId.key],
    deviceId: existing.props[D.Recording.deviceId.key],
    edgeHostId: existing.props[D.Recording.edgeHostId.key],
    primaryStreamId: existing.props[D.Recording.primaryStreamId.key],
    objectHandle: existing.props[D.Recording.objectHandle.key],
    objectContentDigest: existing.props[D.Recording.objectContentDigest.key],
    objectByteLength: existing.props[D.Recording.objectByteLength.key],
    objectContentType: existing.props[D.Recording.objectContentType.key],
    sequenceStart: existing.props[D.Recording.sequenceStart.key],
    sequenceEndExclusive: existing.props[D.Recording.sequenceEndExclusive.key],
    codec: existing.props[D.Recording.codec.key],
    sampleRateHz: existing.props[D.Recording.sampleRateHz.key],
    channels: existing.props[D.Recording.channels.key],
    startedAt: existing.props[D.Recording.startedAt.key],
    endedAt: existing.props[D.Recording.endedAt.key],
    durationMs: existing.props[D.Recording.durationMs.key],
  })
  assertManifestRange(storedProjection)
  const finalizedAt = UtcTimestampSchema.parse(existing.props[D.Recording.finalizedAt.key])
  const receipt = existing.finalization
  if (
    storedProjection.captureSessionId !== capture.captureId ||
    storedProjection.deviceId !== capture.deviceId ||
    storedProjection.edgeHostId !== capture.edgeHostId ||
    receipt?.class !== D.RecordingFinalization.path.class.raw ||
    receipt.props[D.RecordingFinalization.manifestId.key] !== storedProjection.manifestId ||
    receipt.props[D.RecordingFinalization.manifestDigest.key] !== storedProjection.manifestDigest ||
    receipt.props[D.RecordingFinalization.captureSessionId.key] !== storedProjection.captureSessionId ||
    receipt.props[D.RecordingFinalization.deviceId.key] !== storedProjection.deviceId ||
    receipt.props[D.RecordingFinalization.edgeHostId.key] !== storedProjection.edgeHostId ||
    receipt.props[D.RecordingFinalization.finalizedAt.key] !== finalizedAt
  ) {
    throw new Error('Ready Recording is missing its exact capture/device/edge finalization receipt')
  }
  return { recording, status: 'ready', replayed: true }
}

function assertPending(existing: RecordingState): void {
  if (existing.props[D.Recording.status.key] !== 'pending-manifest' || existing.finalization !== null) {
    throw new Error('Recording has an invalid non-terminal state or premature finalization receipt')
  }
}

export async function finalizeRecording(
  capture: PathLike,
  params: Params,
  deps: { kernel: Kernel; mediaIngest: MediaIngest; principal: PathLike; step: Step },
): Promise<{ recording: string; status: 'ready'; replayed: boolean }> {
  const captureState = await deps.step.run('read-recording-capture', async () => {
    const node = await deps.kernel.getOrThrow(capture, 'Recording capture is not reachable')
    const devices = await (
      await deps.kernel.neighbors(node.path, D.device_has_capture.path.class.raw, 'in')
    ).all()
    if (devices.length !== 1 || devices[0]?.class.raw !== D.Device.path.class.raw) {
      throw new Error('CaptureSession must have exactly one provisioned Gumi Device binding')
    }
    const device = devices[0]
    return {
      class: node.class.raw,
      captureNodeId: node.id,
      capturePath: node.path.raw,
      captureId: UuidV7Schema.parse(node.props[D.CaptureSession.captureId.key]),
      edgeHostId: UuidV7Schema.parse(node.props[D.CaptureSession.edgeHostId.key]),
      status: node.props[D.CaptureSession.status.key],
      devicePath: device.path.raw,
      deviceId: UuidV7Schema.parse(device.props[D.Device.deviceId.key]),
    }
  })
  if (captureState.class !== D.CaptureSession.path.class.raw) {
    throw new Error('Recording must belong to a Gumi CaptureSession')
  }
  if (captureState.status === 'open') {
    throw new Error('Recording cannot be finalized while its CaptureSession is open')
  }
  if (captureState.status !== 'closed' && captureState.status !== 'faulted') {
    throw new Error('Recording requires a terminal Gumi CaptureSession')
  }

  const path = recordingPath(capture, params.recordingId)
  const finalization = recordingFinalizationPath(path)
  let existing = await deps.step.run('read-existing-recording', () =>
    readRecordingState(deps.kernel, path, finalization),
  )
  if (existing) {
    assertRecordingIdentity(existing, params, captureState)
    const replay = readyReplay(existing, captureState, path.raw)
    if (replay) return replay
    assertPending(existing)
  } else {
    try {
      await deps.step.run('create-pending-recording', () =>
        deps.kernel.mutate((mutation) => {
          const recording = mutation.createNode(D.Recording.path.class.raw, path, {
            [D.Recording.recordingId.key]: params.recordingId,
            [D.Recording.status.key]: 'pending-manifest',
            [D.Recording.manifestId.key]: params.manifestId,
            [D.Recording.manifestDigest.key]: params.manifestDigest,
          })
          mutation.link(capture, D.capture_has_recording.path.class.raw, recording)
        }),
      )
    } catch (error) {
      existing = await deps.step.run('read-concurrent-pending-recording', () =>
        readRecordingState(deps.kernel, path, finalization),
      )
      if (!existing) throw error
      assertRecordingIdentity(existing, params, captureState)
      const replay = readyReplay(existing, captureState, path.raw)
      if (replay) return replay
      assertPending(existing)
    }
  }

  const manifestWire = await deps.step.run('verify-immutable-media-manifest', async () => {
    const reader = await deps.mediaIngest.issueCaptureBoundManifestReader({
      actualCaller: rawOf(deps.principal),
      capturePath: captureState.capturePath,
      captureSessionId: captureState.captureId,
      devicePath: captureState.devicePath,
      deviceId: captureState.deviceId,
      edgeHostId: captureState.edgeHostId,
    })
    const manifest = await reader.getImmutableManifest({
      manifestId: params.manifestId,
      expectedDigest: params.manifestDigest,
    })
    return ImmutableManifestProjectionSchema.parse(manifest)
  })
  assertManifestRange(manifestWire)
  if (
    manifestWire.manifestId !== params.manifestId ||
    manifestWire.manifestDigest !== params.manifestDigest ||
    manifestWire.captureSessionId !== captureState.captureId ||
    manifestWire.deviceId !== captureState.deviceId ||
    manifestWire.edgeHostId !== captureState.edgeHostId
  ) {
    throw new Error('Immutable manifest does not belong to this capture/device/edge-host binding')
  }

  const finalizedAt = await deps.step.run('read-recording-finalization-time', () =>
    new Date().toISOString(),
  )
  try {
    await deps.step.run('commit-recording-finalization', () =>
      deps.kernel.mutate((mutation) => {
        mutation.createNode(D.RecordingFinalization.path.class.raw, finalization, {
          [D.RecordingFinalization.manifestId.key]: manifestWire.manifestId,
          [D.RecordingFinalization.manifestDigest.key]: manifestWire.manifestDigest,
          [D.RecordingFinalization.captureSessionId.key]: manifestWire.captureSessionId,
          [D.RecordingFinalization.deviceId.key]: manifestWire.deviceId,
          [D.RecordingFinalization.edgeHostId.key]: manifestWire.edgeHostId,
          [D.RecordingFinalization.finalizedAt.key]: finalizedAt,
        })
        mutation.updateNode(D.Recording.path.class.raw, path, {
          [D.Recording.status.key]: 'ready',
          [D.Recording.captureSessionId.key]: manifestWire.captureSessionId,
          [D.Recording.deviceId.key]: manifestWire.deviceId,
          [D.Recording.edgeHostId.key]: manifestWire.edgeHostId,
          [D.Recording.objectHandle.key]: manifestWire.objectHandle,
          [D.Recording.objectContentDigest.key]: manifestWire.objectContentDigest,
          [D.Recording.objectByteLength.key]: manifestWire.objectByteLength,
          [D.Recording.objectContentType.key]: manifestWire.objectContentType,
          [D.Recording.primaryStreamId.key]: manifestWire.primaryStreamId,
          [D.Recording.sequenceStart.key]: manifestWire.sequenceStart,
          [D.Recording.sequenceEndExclusive.key]: manifestWire.sequenceEndExclusive,
          [D.Recording.codec.key]: manifestWire.codec,
          [D.Recording.sampleRateHz.key]: manifestWire.sampleRateHz,
          [D.Recording.channels.key]: manifestWire.channels,
          [D.Recording.startedAt.key]: manifestWire.startedAt,
          [D.Recording.endedAt.key]: manifestWire.endedAt,
          [D.Recording.durationMs.key]: manifestWire.durationMs,
          [D.Recording.finalizedAt.key]: finalizedAt,
        })
      }),
    )
  } catch (error) {
    const converged = await deps.step.run('read-concurrent-recording-finalization', () =>
      readRecordingState(deps.kernel, path, finalization),
    )
    if (!converged) throw error
    assertRecordingIdentity(converged, params, captureState)
    const replay = readyReplay(converged, captureState, path.raw)
    if (replay) return replay
    throw error
  }
  return { recording: path.raw, status: 'ready', replayed: false }
}

export function authorizeFinalizeRecording(input: {
  kernel: Kernel
  principal: PathLike
  capture: PathLike
}) {
  return input.kernel.auth.require({
    who: input.principal,
    on: input.capture,
    perms: EDIT,
    context: 'CaptureSession.finalizeRecording',
  })
}

export const finalizeRecordingMethod = remoteMethod<Deps>()(
  schema,
  'CaptureSession',
  'finalizeRecording',
  {
    authorize: ({ auth, kernel, self }) =>
      authorizeFinalizeRecording({ kernel, principal: auth.principal, capture: self.path }),
    execute: ({ auth, deps, kernel, params, self, step }) =>
      finalizeRecording(rawOf(self.path), params, {
        kernel,
        mediaIngest: deps.mediaIngest,
        principal: rawOf(auth.principal),
        step,
      }),
  },
)
