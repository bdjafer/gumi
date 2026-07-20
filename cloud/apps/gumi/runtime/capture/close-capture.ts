import { EDIT, type PathLike } from '@astrale-os/kernel-core'
import { remoteMethod, type Kernel, type Step } from '@astrale-os/sdk'

import type { Deps } from '#deps'
import { captureClosurePath } from '#core/shared/paths'
import { D, UtcTimestampSchema, schema } from '#schema'

interface CaptureCloseState {
  captureClass: string
  status: unknown
  closedAt: unknown
  closureClass: string | null
  closureClosedAt: unknown
}

function completedClose(
  current: CaptureCloseState,
): { status: 'closed'; closedAt: string; replayed: true } | null {
  if (current.status !== 'closed') return null
  const closedAt = UtcTimestampSchema.safeParse(current.closedAt)
  if (
    current.captureClass !== D.CaptureSession.path.class.raw ||
    current.closureClass !== D.CaptureClosure.path.class.raw ||
    !closedAt.success ||
    current.closureClosedAt !== closedAt.data
  ) {
    throw new Error('Closed CaptureSession is missing its matching one-shot closure receipt')
  }
  return { status: 'closed', closedAt: closedAt.data, replayed: true }
}

export async function closeCapture(
  capture: PathLike,
  deps: { kernel: Kernel; step: Step },
): Promise<{ status: 'closed'; closedAt: string; replayed: boolean }> {
  const closure = captureClosurePath(capture)
  const current = await deps.step.run('read-capture-to-close', async () => {
    const [node, receipt] = await Promise.all([
      deps.kernel.getOrThrow(capture, 'CaptureSession.close receiver is not reachable'),
      deps.kernel.get(closure),
    ])
    return {
      captureClass: node.class.raw,
      status: node.props[D.CaptureSession.status.key],
      closedAt: node.props[D.CaptureSession.closedAt.key],
      closureClass: receipt?.class.raw ?? null,
      closureClosedAt: receipt?.props[D.CaptureClosure.closedAt.key] ?? null,
    }
  })
  if (current.captureClass !== D.CaptureSession.path.class.raw) {
    throw new Error('CaptureSession.close requires a Gumi CaptureSession')
  }
  const replay = completedClose(current)
  if (replay) return replay
  if (current.status !== 'open') {
    throw new Error(`CaptureSession in ${String(current.status)} state cannot be closed normally`)
  }
  if (current.closureClass !== null) {
    throw new Error('Open CaptureSession unexpectedly already has a closure receipt')
  }

  const closedAt = await deps.step.run('read-capture-close-time', () => new Date().toISOString())
  try {
    await deps.step.run('commit-capture-closure', () =>
      deps.kernel.mutate((mutation) => {
        mutation.createNode(D.CaptureClosure.path.class.raw, closure, {
          [D.CaptureClosure.closedAt.key]: closedAt,
        })
        mutation.updateNode(D.CaptureSession.path.class.raw, capture, {
          [D.CaptureSession.status.key]: 'closed',
          [D.CaptureSession.closedAt.key]: closedAt,
        })
      }),
    )
  } catch (error) {
    const converged = await deps.step.run('read-concurrent-capture-closure', async () => {
      const [node, receipt] = await Promise.all([deps.kernel.get(capture), deps.kernel.get(closure)])
      return {
        captureClass: node?.class.raw ?? '',
        status: node?.props[D.CaptureSession.status.key],
        closedAt: node?.props[D.CaptureSession.closedAt.key],
        closureClass: receipt?.class.raw ?? null,
        closureClosedAt: receipt?.props[D.CaptureClosure.closedAt.key] ?? null,
      }
    })
    const concurrentReplay = completedClose(converged)
    if (concurrentReplay) return concurrentReplay
    throw error
  }
  return { status: 'closed', closedAt, replayed: false }
}

export function authorizeCloseCapture(input: {
  kernel: Kernel
  principal: PathLike
  capture: PathLike
}) {
  return input.kernel.auth.require({
    who: input.principal,
    on: input.capture,
    perms: EDIT,
    context: 'CaptureSession.close',
  })
}

export const closeCaptureMethod = remoteMethod<Deps>()(schema, 'CaptureSession', 'close', {
  authorize: ({ auth, kernel, self }) =>
    authorizeCloseCapture({ kernel, principal: auth.principal, capture: self.path }),
  execute: ({ kernel, self, step }) => closeCapture(self.path, { kernel, step }),
})
