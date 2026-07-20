import { EDIT, rawOf, type PathLike } from '@astrale-os/kernel-core'
import { remoteMethod, type Kernel, type MethodParams, type Step } from '@astrale-os/sdk'

import type { Deps } from '#deps'
import { StableIdentityConflictError } from '#core/shared/conflict'
import { capturePath } from '#core/shared/paths'
import { D, schema } from '#schema'

type Params = MethodParams<typeof schema, 'Device', 'openCapture'>

export async function openCapture(
  device: PathLike,
  params: Params,
  deps: { kernel: Kernel; principal: PathLike; step: Step },
) {
  const deviceState = await deps.step.run('read-capture-device', async () => {
    const node = await deps.kernel.getOrThrow(device, 'Capture device is not reachable')
    return {
      class: node.class.raw,
      status: node.props[D.Device.status.key],
    }
  })
  if (deviceState.class !== D.Device.path.class.raw || deviceState.status !== 'active') {
    throw new Error('Capture requires an active Gumi Device')
  }

  const path = capturePath(device, params.captureId)
  const existing = await deps.step.run('read-existing-capture', async () => {
    const node = await deps.kernel.get(path)
    if (!node) return null
    const [requesters, principal] = await Promise.all([
      deps.kernel
        .neighbors(node.path, D.capture_requested_by.path.class.raw, 'out')
        .then((page) => page.all()),
      deps.kernel.getOrThrow(deps.principal, 'Device.openCapture principal is not reachable'),
    ])
    return {
      class: node.class.raw,
      props: node.props,
      requesterIds: requesters.map((requester) => requester.id),
      principalId: principal.id,
    }
  })
  if (existing) {
    const same =
      existing.class === D.CaptureSession.path.class.raw &&
      existing.props[D.CaptureSession.captureId.key] === params.captureId &&
      existing.props[D.CaptureSession.edgeHostId.key] === params.edgeHostId &&
      existing.props[D.CaptureSession.mode.key] === params.mode &&
      existing.requesterIds.length === 1 &&
      existing.requesterIds[0] === existing.principalId
    if (!same) throw new StableIdentityConflictError('CaptureSession', params.captureId)
    return path
  }

  const openedAt = await deps.step.run('read-capture-open-time', () => new Date().toISOString())
  await deps.step.run('create-capture', () =>
    deps.kernel.mutate((mutation) => {
      const capture = mutation.createNode(D.CaptureSession.path.class.raw, path, {
        [D.CaptureSession.captureId.key]: params.captureId,
        [D.CaptureSession.edgeHostId.key]: params.edgeHostId,
        [D.CaptureSession.mode.key]: params.mode,
        [D.CaptureSession.status.key]: 'open',
        [D.CaptureSession.openedAt.key]: openedAt,
      })
      mutation.link(device, D.device_has_capture.path.class.raw, capture)
      mutation.link(capture, D.capture_requested_by.path.class.raw, deps.principal)
    }),
  )
  return path
}

export function authorizeOpenCapture(input: {
  kernel: Kernel
  principal: PathLike
  device: PathLike
}) {
  return input.kernel.auth.require({
    who: input.principal,
    on: input.device,
    perms: EDIT,
    context: 'Device.openCapture',
  })
}

export const openCaptureMethod = remoteMethod<Deps>()(schema, 'Device', 'openCapture', {
  authorize: ({ auth, kernel, self }) =>
    authorizeOpenCapture({ kernel, principal: auth.principal, device: self.path }),
  execute: ({ auth, kernel, params, self, step }) =>
    openCapture(self.path, params, { kernel, principal: rawOf(auth.principal), step }),
})
