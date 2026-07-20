import { RealtimeGateway } from '../core/realtime-gateway.mjs'
import {
  InMemorySessionStore,
  InMemoryVoiceTurnAuthorizer,
  ManualClock,
  MetadataOnlyLoopbackProvider,
} from '../testing/in-memory-ports.mjs'
import { createRealtimeGatewayWebSocketServer } from '../ws/server.mjs'

export function createLocalRealtimeGateway({
  credentials,
  clock = new ManualClock(new Date().toISOString()),
  logger = undefined,
  host = '127.0.0.1',
  port = 0,
} = {}) {
  if (!Array.isArray(credentials) || credentials.length === 0) {
    throw new TypeError('local composition requires at least one explicit test credential')
  }
  const sessions = new InMemorySessionStore({ clock })
  const provider = new MetadataOnlyLoopbackProvider()
  const authorizer = new InMemoryVoiceTurnAuthorizer()
  for (const credential of credentials) authorizer.register(credential.token, credential.authority)
  const gateway = new RealtimeGateway({ sessions, provider, clock })
  const server = createRealtimeGatewayWebSocketServer({
    gateway,
    authorizer,
    clock,
    logger,
    host,
    port,
  })
  return { server, gateway, sessions, provider, authorizer, clock }
}
