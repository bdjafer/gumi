import { AbsolutePath, rawOf, type PathLike } from '@astrale-os/kernel-core'
import { createInlineStep } from '@astrale-os/sdk/step'
import { describe, expect, it } from 'vitest'

import { FakeMediaIngest } from '#integrations/media-ingest/fake-media-ingest'
import { authorizeCloseCapture, closeCapture } from '#runtime/capture/close-capture'
import {
  authorizeFinalizeRecording,
  finalizeRecording,
} from '#runtime/capture/finalize-recording'
import { authorizeOpenCapture, openCapture } from '#runtime/capture/open-capture'
import { authorizeRegisterDevice, registerDevice } from '#runtime/fleet/register-device'
import { D, type ImmutableManifestProjection } from '#schema'

import type { Kernel } from '@astrale-os/sdk'

interface FakeNode {
  id: string
  class: { raw: string }
  path: AbsolutePath
  props: Record<string, unknown>
}

class CaptureKernel {
  readonly nodes = new Map<string, FakeNode>()
  readonly edges: Array<{ source: string; edge: string; target: string }> = []
  readonly authorizationChecks: Array<Record<string, unknown>> = []
  private nextId = 1

  constructor(private readonly allow = true) {}

  seed(path: string, className = 'kernel:class.Folder'): FakeNode {
    const node: FakeNode = {
      id: `node-${this.nextId++}`,
      class: { raw: className },
      path: AbsolutePath.parse(path),
      props: {},
    }
    this.nodes.set(path, node)
    return node
  }

  init(): Kernel {
    const kernel = {
      auth: {
        require: async (input: Record<string, unknown>) => {
          this.authorizationChecks.push(input)
          if (!this.allow) throw new Error('PERMISSION_DENIED')
        },
      },
      get: async (path: PathLike) => this.nodes.get(rawOf(path)) ?? null,
      getOrThrow: async (path: PathLike, context?: string) => {
        const node = this.nodes.get(rawOf(path))
        if (!node) throw new Error(context ?? `Missing node ${rawOf(path)}`)
        return node
      },
      neighbors: async (path: PathLike, edge: string, direction: 'in' | 'out' | 'both') => {
        const raw = rawOf(path)
        const refs = this.edges.flatMap((candidate) => {
          if (candidate.edge !== edge) return []
          if (direction !== 'in' && candidate.source === raw) return [candidate.target]
          if (direction !== 'out' && candidate.target === raw) return [candidate.source]
          return []
        })
        const nodes = refs.map((ref) => {
          const node = this.nodes.get(ref) ?? [...this.nodes.values()].find(({ id }) => `@${id}` === ref)
          if (!node) throw new Error(`Missing linked node ${ref}`)
          return node
        })
        return { all: async () => nodes }
      },
      mutate: async (
        build: (mutation: {
          createNode(className: string, path: PathLike, props: Record<string, unknown>): FakeNode
          link(source: unknown, edge: string, target: unknown): void
          updateNode(className: string, path: PathLike, props: Record<string, unknown>): void
        }) => void,
      ) => {
        const created: FakeNode[] = []
        const linked: Array<{ source: string; edge: string; target: string }> = []
        const updated: Array<{ node: FakeNode; props: Record<string, unknown> }> = []
        const mutation = {
          createNode: (className: string, path: PathLike, props: Record<string, unknown>) => {
            const raw = rawOf(path)
            if (this.nodes.has(raw) || created.some((node) => node.path.raw === raw)) {
              throw new Error(`PATH_CONFLICT: ${raw}`)
            }
            const node: FakeNode = {
              id: `node-${this.nextId++}`,
              class: { raw: className },
              path: AbsolutePath.parse(raw),
              props: { ...props },
            }
            created.push(node)
            return node
          },
          link: (source: unknown, edge: string, target: unknown) => {
            linked.push({ source: refOf(source), edge, target: refOf(target) })
          },
          updateNode: (className: string, path: PathLike, props: Record<string, unknown>) => {
            const node = this.nodes.get(rawOf(path))
            if (!node || node.class.raw !== className) {
              throw new Error(`Missing update target ${rawOf(path)}`)
            }
            updated.push({ node, props: { ...props } })
          },
        }
        build(mutation)
        for (const node of created) this.nodes.set(node.path.raw, node)
        for (const update of updated) update.node.props = { ...update.node.props, ...update.props }
        this.edges.push(...linked)
      },
      updateNode: async (className: string, path: PathLike, props: Record<string, unknown>) => {
        const node = this.nodes.get(rawOf(path))
        if (!node || node.class.raw !== className) throw new Error(`Missing update target ${rawOf(path)}`)
        node.props = { ...node.props, ...props }
      },
    }
    return kernel as unknown as Kernel
  }
}

function refOf(value: unknown): string {
  if (typeof value === 'string') return value
  if (value && typeof value === 'object') {
    const candidate = value as { path?: { raw?: string }; raw?: string; id?: string }
    return candidate.path?.raw ?? candidate.raw ?? candidate.id ?? 'unknown'
  }
  return String(value)
}

const OWNER = '/identities/ada'
const OTHER_OWNER = '/identities/grace'
const PARENT = AbsolutePath.parse('/people/ada')
const DEVICE_ID = '0190c6f0-7b21-7a40-8b11-000000000001'
const CAPTURE_ID = '0190c6f0-7b21-7a40-8b11-000000000003'
const EDGE_HOST_ID = '0190c6f0-7b21-7a40-8b11-000000000005'
const PRIMARY_STREAM_ID = '0190c6f0-7b21-7a40-8b11-000000000006'
const RECORDING_ID = '0190c6f0-7b21-7a40-8b11-000000000008'
const MANIFEST_ID = '0190c6f0-7b21-7a40-8b11-000000000009'
const DIGEST = `sha256:${'ab'.repeat(32)}`

function manifest(
  overrides: Partial<ImmutableManifestProjection> = {},
): ImmutableManifestProjection {
  return {
    schemaVersion: 'gumi.media-ingest.immutable-manifest-projection.v1',
    manifestId: MANIFEST_ID,
    manifestDigest: DIGEST,
    captureSessionId: CAPTURE_ID,
    deviceId: DEVICE_ID,
    edgeHostId: EDGE_HOST_ID,
    primaryStreamId: PRIMARY_STREAM_ID,
    objectHandle: 'gumi-media:object/0190c6f0-7b21-7a40-8b11-00000000000a',
    objectContentDigest: `sha256:${'cd'.repeat(32)}`,
    objectByteLength: '4096',
    objectContentType: 'audio/ogg; codecs=opus',
    sequenceStart: '10',
    sequenceEndExclusive: '20',
    codec: 'opus',
    sampleRateHz: 16_000,
    channels: 1,
    startedAt: '2026-07-19T00:00:00.000Z',
    endedAt: '2026-07-19T00:00:01.000Z',
    durationMs: 1_000,
    ...overrides,
  }
}

async function openedWorld() {
  const capture = new CaptureKernel()
  capture.seed(OWNER)
  capture.seed(PARENT.raw)
  const kernel = capture.init()
  const step = createInlineStep()
  const device = await registerDevice(
    {
      parent: PARENT,
      deviceId: DEVICE_ID,
      product: 'omi-cv1',
      hardwareRevision: '5.0',
      firmwareVersion: '3.0.12',
    },
    { kernel, principal: OWNER, step },
  )
  const session = await openCapture(
    device,
    { captureId: CAPTURE_ID, edgeHostId: EDGE_HOST_ID, mode: 'recording' },
    { kernel, principal: OWNER, step },
  )
  return { capture, kernel, step, device, session }
}

async function closedWorld() {
  const world = await openedWorld()
  await closeCapture(world.session, { kernel: world.kernel, step: world.step })
  return world
}

describe('fleet and capture convergence', () => {
  it('replays stable Device and CaptureSession identities without duplicate graph writes', async () => {
    const world = await openedWorld()
    const edgeCount = world.capture.edges.length

    const deviceReplay = await registerDevice(
      {
        parent: PARENT,
        deviceId: DEVICE_ID,
        product: 'omi-cv1',
        hardwareRevision: '5.0',
        firmwareVersion: '3.0.20',
      },
      { kernel: world.kernel, principal: OWNER, step: world.step },
    )
    const captureReplay = await openCapture(
      world.device,
      { captureId: CAPTURE_ID, edgeHostId: EDGE_HOST_ID, mode: 'recording' },
      { kernel: world.kernel, principal: OWNER, step: world.step },
    )

    expect(deviceReplay.raw).toBe(world.device.raw)
    expect(captureReplay.raw).toBe(world.session.raw)
    expect(world.capture.edges).toHaveLength(edgeCount)
  })

  it('rejects stable identity reuse with different immutable facts', async () => {
    const world = await openedWorld()
    await expect(
      openCapture(
        world.device,
        { captureId: CAPTURE_ID, edgeHostId: EDGE_HOST_ID, mode: 'voice-turn' },
        { kernel: world.kernel, principal: OWNER, step: world.step },
      ),
    ).rejects.toThrow(/different immutable facts/)
  })

  it('rejects Device and CaptureSession replay through a different owner/requester binding', async () => {
    const world = await openedWorld()
    world.capture.seed(OTHER_OWNER)

    await expect(
      registerDevice(
        {
          parent: PARENT,
          deviceId: DEVICE_ID,
          product: 'omi-cv1',
          hardwareRevision: '5.0',
          firmwareVersion: '3.0.12',
        },
        { kernel: world.kernel, principal: OTHER_OWNER, step: world.step },
      ),
    ).rejects.toThrow(/different immutable facts/)
    await expect(
      openCapture(
        world.device,
        { captureId: CAPTURE_ID, edgeHostId: EDGE_HOST_ID, mode: 'recording' },
        { kernel: world.kernel, principal: OTHER_OWNER, step: world.step },
      ),
    ).rejects.toThrow(/different immutable facts/)
  })

  it('closes a CaptureSession once and replays its original terminal timestamp', async () => {
    const world = await openedWorld()

    const first = await closeCapture(world.session, { kernel: world.kernel, step: world.step })
    const replay = await closeCapture(world.session, { kernel: world.kernel, step: world.step })

    expect(first).toMatchObject({ status: 'closed', replayed: false })
    expect(replay).toEqual({ ...first, replayed: true })
    const closure = world.capture.nodes.get(`${world.session.raw}/closure`)!
    expect(closure.class.raw).toBe(D.CaptureClosure.path.class.raw)
    expect(closure.props[D.CaptureClosure.closedAt.key]).toBe(first.closedAt)
  })

  it('uses the fixed closure receipt as a real concurrent close arbiter', async () => {
    const world = await openedWorld()

    const results = await Promise.all([
      closeCapture(world.session, { kernel: world.kernel, step: world.step }),
      closeCapture(world.session, { kernel: world.kernel, step: world.step }),
    ])

    expect(results.map(({ replayed }) => replayed).sort()).toEqual([false, true])
    expect(new Set(results.map(({ closedAt }) => closedAt)).size).toBe(1)
    const closures = [...world.capture.nodes.values()].filter(
      ({ class: nodeClass }) => nodeClass.raw === D.CaptureClosure.path.class.raw,
    )
    expect(closures).toHaveLength(1)
  })

  it('refuses immutable Recording publication while capture is still open', async () => {
    const world = await openedWorld()
    const mediaIngest = new FakeMediaIngest(new Map([[MANIFEST_ID, manifest()]]))

    await expect(
      finalizeRecording(
        world.session,
        { recordingId: RECORDING_ID, manifestId: MANIFEST_ID, manifestDigest: DIGEST },
        { kernel: world.kernel, mediaIngest, principal: OWNER, step: world.step },
      ),
    ).rejects.toThrow(/is open/)
    expect(mediaIngest.requests).toHaveLength(0)
  })

  it('converges one ready Recording from an exact immutable manifest', async () => {
    const world = await closedWorld()
    const mediaIngest = new FakeMediaIngest(new Map([[MANIFEST_ID, manifest()]]))
    const params = {
      recordingId: RECORDING_ID,
      manifestId: MANIFEST_ID,
      manifestDigest: DIGEST,
    }

    const first = await finalizeRecording(world.session, params, {
      kernel: world.kernel,
      mediaIngest,
      principal: OWNER,
      step: world.step,
    })
    const replay = await finalizeRecording(world.session, params, {
      kernel: world.kernel,
      mediaIngest,
      principal: OWNER,
      step: world.step,
    })

    expect(first).toMatchObject({ status: 'ready', replayed: false })
    expect(replay).toMatchObject({ recording: first.recording, status: 'ready', replayed: true })
    expect(mediaIngest.issuedScopes).toHaveLength(1)
    expect(mediaIngest.requests).toHaveLength(1)
    expect(mediaIngest.requests[0]?.scope).toEqual({
      actualCaller: OWNER,
      capturePath: world.session.raw,
      captureSessionId: CAPTURE_ID,
      devicePath: world.device.raw,
      deviceId: DEVICE_ID,
      edgeHostId: EDGE_HOST_ID,
    })
    const recording = world.capture.nodes.get(first.recording)!
    expect(recording.props[D.Recording.status.key]).toBe('ready')
    expect(recording.props[D.Recording.objectHandle.key]).toBe(manifest().objectHandle)
    expect(recording.props[D.Recording.objectContentDigest.key]).toBe(manifest().objectContentDigest)
    expect(recording.props[D.Recording.objectByteLength.key]).toBe(manifest().objectByteLength)
    expect(recording.props[D.Recording.objectContentType.key]).toBe(manifest().objectContentType)
    expect(recording.props[D.Recording.primaryStreamId.key]).toBe(PRIMARY_STREAM_ID)
    expect(JSON.stringify(recording.props)).not.toContain('audioBytes')
    const receipt = world.capture.nodes.get(`${first.recording}/finalization`)!
    expect(receipt.class.raw).toBe(D.RecordingFinalization.path.class.raw)
    expect(receipt.props[D.RecordingFinalization.deviceId.key]).toBe(DEVICE_ID)
    expect(receipt.props[D.RecordingFinalization.edgeHostId.key]).toBe(EDGE_HOST_ID)

    const finalizedAt = recording.props[D.Recording.finalizedAt.key]
    delete recording.props[D.Recording.finalizedAt.key]
    await expect(
      finalizeRecording(world.session, params, {
        kernel: world.kernel,
        mediaIngest,
        principal: OWNER,
        step: world.step,
      }),
    ).rejects.toThrow()
    recording.props[D.Recording.finalizedAt.key] = finalizedAt

    const objectByteLength = recording.props[D.Recording.objectByteLength.key]
    recording.props[D.Recording.objectByteLength.key] = 4096
    await expect(
      finalizeRecording(world.session, params, {
        kernel: world.kernel,
        mediaIngest,
        principal: OWNER,
        step: world.step,
      }),
    ).rejects.toThrow()
    recording.props[D.Recording.objectByteLength.key] = objectByteLength

    const receiptDeviceId = receipt.props[D.RecordingFinalization.deviceId.key]
    receipt.props[D.RecordingFinalization.deviceId.key] =
      '0190c6f0-7b21-7a40-8b11-000000000099'
    await expect(
      finalizeRecording(world.session, params, {
        kernel: world.kernel,
        mediaIngest,
        principal: OWNER,
        step: world.step,
      }),
    ).rejects.toThrow(/missing its exact capture\/device\/edge finalization receipt/)
    receipt.props[D.RecordingFinalization.deviceId.key] = receiptDeviceId

    delete recording.props[D.Recording.objectHandle.key]
    await expect(
      finalizeRecording(world.session, params, {
        kernel: world.kernel,
        mediaIngest,
        principal: OWNER,
        step: world.step,
      }),
    ).rejects.toThrow()
    expect(mediaIngest.requests).toHaveLength(1)
  })

  it('uses a fixed finalization receipt to converge concurrent Recording publication', async () => {
    const world = await closedWorld()
    const mediaIngest = new FakeMediaIngest(new Map([[MANIFEST_ID, manifest()]]))
    const params = {
      recordingId: RECORDING_ID,
      manifestId: MANIFEST_ID,
      manifestDigest: DIGEST,
    }

    const results = await Promise.all([
      finalizeRecording(world.session, params, {
        kernel: world.kernel,
        mediaIngest,
        principal: OWNER,
        step: world.step,
      }),
      finalizeRecording(world.session, params, {
        kernel: world.kernel,
        mediaIngest,
        principal: OWNER,
        step: world.step,
      }),
    ])

    expect(results.map(({ replayed }) => replayed).sort()).toEqual([false, true])
    expect(new Set(results.map(({ recording }) => recording)).size).toBe(1)
    const receipts = [...world.capture.nodes.values()].filter(
      ({ class: nodeClass }) => nodeClass.raw === D.RecordingFinalization.path.class.raw,
    )
    expect(receipts).toHaveLength(1)
  })

  it('does not publish ready semantic truth for a foreign or invalid sequence manifest', async () => {
    const foreign = await closedWorld()
    const foreignIngest = new FakeMediaIngest(
      new Map([
        [
          MANIFEST_ID,
          manifest({ captureSessionId: '0190c6f0-7b21-7a40-8b11-000000000099' }),
        ],
      ]),
    )
    await expect(
      finalizeRecording(
        foreign.session,
        { recordingId: RECORDING_ID, manifestId: MANIFEST_ID, manifestDigest: DIGEST },
        {
          kernel: foreign.kernel,
          mediaIngest: foreignIngest,
          principal: OWNER,
          step: foreign.step,
        },
      ),
    ).rejects.toThrow(/does not belong/)

    const foreignRecording = [...foreign.capture.nodes.values()].find(
      (node) => node.class.raw === D.Recording.path.class.raw,
    )!
    expect(foreignRecording.props[D.Recording.status.key]).toBe('pending-manifest')

    for (const bindingOverride of [
      { deviceId: '0190c6f0-7b21-7a40-8b11-000000000098' },
      { edgeHostId: '0190c6f0-7b21-7a40-8b11-000000000097' },
    ]) {
      const bindingWorld = await closedWorld()
      const bindingIngest = new FakeMediaIngest(
        new Map([[MANIFEST_ID, manifest(bindingOverride)]]),
      )
      await expect(
        finalizeRecording(
          bindingWorld.session,
          { recordingId: RECORDING_ID, manifestId: MANIFEST_ID, manifestDigest: DIGEST },
          {
            kernel: bindingWorld.kernel,
            mediaIngest: bindingIngest,
            principal: OWNER,
            step: bindingWorld.step,
          },
        ),
      ).rejects.toThrow(/does not belong/)
    }

    const invalid = await closedWorld()
    const invalidIngest = new FakeMediaIngest(
      new Map([[MANIFEST_ID, manifest({ sequenceStart: '20', sequenceEndExclusive: '20' })]]),
    )
    await expect(
      finalizeRecording(
        invalid.session,
        { recordingId: RECORDING_ID, manifestId: MANIFEST_ID, manifestDigest: DIGEST },
        {
          kernel: invalid.kernel,
          mediaIngest: invalidIngest,
          principal: OWNER,
          step: invalid.step,
        },
      ),
    ).rejects.toThrow(/non-empty and ordered/)
  })
})

describe('caller authority gates', () => {
  it('checks the actual principal on every callable receiver before effects', async () => {
    const denied = new CaptureKernel(false)
    const kernel = denied.init()

    await expect(
      authorizeRegisterDevice({ kernel, principal: OWNER, parent: PARENT }),
    ).rejects.toThrow('PERMISSION_DENIED')
    await expect(
      authorizeOpenCapture({ kernel, principal: OWNER, device: '/devices/one' }),
    ).rejects.toThrow('PERMISSION_DENIED')
    await expect(
      authorizeCloseCapture({ kernel, principal: OWNER, capture: '/captures/one' }),
    ).rejects.toThrow('PERMISSION_DENIED')
    await expect(
      authorizeFinalizeRecording({ kernel, principal: OWNER, capture: '/captures/one' }),
    ).rejects.toThrow('PERMISSION_DENIED')

    expect(denied.authorizationChecks.map((entry) => entry.context)).toEqual([
      'Device.register',
      'Device.openCapture',
      'CaptureSession.close',
      'CaptureSession.finalizeRecording',
    ])
    expect(denied.authorizationChecks.every((entry) => entry.who === OWNER)).toBe(true)
  })
})
