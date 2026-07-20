import { resolve } from 'node:path'

import { CREDENTIAL_AUDIENCE, VOICE_TURN_SCOPE } from './core/constants.mjs'
import { createLocalRealtimeGateway } from './runtime/local-composition.mjs'

const REQUIRED_LOCAL_ENV = [
  'GUMI_RT_LOCAL_TOKEN',
  'GUMI_RT_BINDING_ID',
  'GUMI_RT_DEVICE_ID',
  'GUMI_RT_EDGE_HOST_ID',
  'GUMI_RT_ADMISSION_ID',
  'GUMI_RT_SESSION_ID',
]

export async function runLocalFromEnvironment(env = process.env) {
  const missing = REQUIRED_LOCAL_ENV.filter((name) => !env[name])
  if (missing.length > 0) throw new Error(`missing local realtime environment: ${missing.join(', ')}`)
  const expiresAt = new Date(Date.now() + 4 * 60 * 1_000).toISOString()
  const authority = {
    credentialKind: 'voice-turn',
    scope: VOICE_TURN_SCOPE,
    audience: CREDENTIAL_AUDIENCE,
    principalId: env.GUMI_RT_EDGE_HOST_ID,
    deploymentBindingId: env.GUMI_RT_BINDING_ID,
    deviceId: env.GUMI_RT_DEVICE_ID,
    edgeHostId: env.GUMI_RT_EDGE_HOST_ID,
    admissionId: env.GUMI_RT_ADMISSION_ID,
    sessionId: env.GUMI_RT_SESSION_ID,
    expiresAt,
    issuerKeyRevision: 'local-only-v1',
  }
  const composition = createLocalRealtimeGateway({
    credentials: [{ token: env.GUMI_RT_LOCAL_TOKEN, authority }],
    clock: { now: () => new Date() },
    port: env.PORT === undefined ? 8789 : Number(env.PORT),
  })
  const address = await composition.server.listen()
  process.stdout.write(`realtime-gateway local loopback listening at ${address.webSocketUrl}\n`)
  process.stdout.write('credential value is intentionally not printed; local state is volatile and metadata-only\n')
  return composition
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  runLocalFromEnvironment().catch((error) => {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 1
  })
}
