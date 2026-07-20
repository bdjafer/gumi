import { EDIT, rawOf, type PathLike } from '@astrale-os/kernel-core'
import { remoteMethod, type Kernel, type MethodParams, type Step } from '@astrale-os/sdk'

import type { Deps } from '#deps'
import { StableIdentityConflictError } from '#core/shared/conflict'
import { devicePath } from '#core/shared/paths'
import { D, schema } from '#schema'

type Params = MethodParams<typeof schema, 'Device', 'register'>

export async function registerDevice(
  params: Params,
  deps: { kernel: Kernel; principal: PathLike; step: Step },
) {
  const parent = await deps.step.run('resolve-device-parent', async () => {
    const node = await deps.kernel.getOrThrow(params.parent, 'Device.register parent is not reachable')
    return node.path.raw
  })
  const path = devicePath(parent, params.deviceId)
  const existing = await deps.step.run('read-existing-device', async () => {
    const node = await deps.kernel.get(path)
    if (!node) return null
    const [owners, principal] = await Promise.all([
      deps.kernel
        .neighbors(node.path, D.device_owned_by.path.class.raw, 'out')
        .then((page) => page.all()),
      deps.kernel.getOrThrow(deps.principal, 'Device.register principal is not reachable'),
    ])
    return {
      class: node.class.raw,
      props: node.props,
      ownerIds: owners.map((owner) => owner.id),
      principalId: principal.id,
    }
  })
  if (existing) {
    const same =
      existing.class === D.Device.path.class.raw &&
      existing.props[D.Device.deviceId.key] === params.deviceId &&
      existing.props[D.Device.product.key] === params.product &&
      existing.props[D.Device.hardwareRevision.key] === params.hardwareRevision &&
      existing.ownerIds.length === 1 &&
      existing.ownerIds[0] === existing.principalId
    if (!same) throw new StableIdentityConflictError('Device', params.deviceId)
    return path
  }

  const registeredAt = await deps.step.run('read-device-registration-time', () =>
    new Date().toISOString(),
  )
  await deps.step.run('create-device', () =>
    deps.kernel.mutate((mutation) => {
      const device = mutation.createNode(D.Device.path.class.raw, path, {
        [D.Device.deviceId.key]: params.deviceId,
        [D.Device.product.key]: params.product,
        ...(params.hardwareRevision
          ? { [D.Device.hardwareRevision.key]: params.hardwareRevision }
          : {}),
        ...(params.firmwareVersion
          ? { [D.Device.firmwareVersion.key]: params.firmwareVersion }
          : {}),
        [D.Device.status.key]: 'active',
        [D.Device.registeredAt.key]: registeredAt,
      })
      mutation.link(device, D.device_owned_by.path.class.raw, deps.principal)
    }),
  )
  return path
}

export function authorizeRegisterDevice(input: {
  kernel: Kernel
  principal: PathLike
  parent: PathLike
}) {
  return input.kernel.auth.require({
    who: input.principal,
    on: input.parent,
    perms: EDIT,
    context: 'Device.register',
  })
}

export const registerDeviceMethod = remoteMethod<Deps>()(schema, 'Device', 'register', {
  authorize: ({ auth, kernel, params }) =>
    authorizeRegisterDevice({ kernel, principal: auth.principal, parent: params.parent }),
  execute: ({ auth, kernel, params, step }) =>
    registerDevice(params, { kernel, principal: rawOf(auth.principal), step }),
})
