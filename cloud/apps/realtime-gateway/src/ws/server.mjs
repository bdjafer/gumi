import http from 'node:http'

import { WebSocket, WebSocketServer } from 'ws'

import { LIMITS, PROTOCOL_VERSION } from '../core/constants.mjs'
import { decodeAudioFrame } from '../core/audio-frame.mjs'
import { GatewayError } from '../core/error.mjs'
import { byteLength } from '../core/primitives.mjs'
import { parseClientControlFrame, validateAuthority } from '../core/validation.mjs'
import { assertWebSocketPorts } from '../ports.mjs'

const FATAL_CODES = new Set([
  'CONTROL_FRAME_TOO_LARGE',
  'CONTROL_JSON_INVALID',
  'FRAME_INVALID',
  'CONTROL_TYPE_UNSUPPORTED',
  'PROTOCOL_VERSION_UNSUPPORTED',
  'HELLO_REQUIRED',
  'HELLO_REPLAYED',
  'STALE_CONNECTION_FENCE',
  'AUTHORITY_KIND_MISMATCH',
  'AUTHORITY_SCOPE_MISMATCH',
  'AUTHORITY_PRINCIPAL_MISMATCH',
  'AUTHORITY_SESSION_MISMATCH',
  'AUTHORITY_EXPIRED',
  'AUTHORITY_TTL_EXCEEDED',
  'SESSION_EXPIRED',
])

function writeUpgradeError(socket, status, reason, extraHeaders = undefined) {
  const body = Buffer.from(`${reason}\n`, 'utf8')
  const headers = {
    Connection: 'close',
    'Content-Type': 'text/plain; charset=utf-8',
    'Content-Length': String(body.length),
    'Cache-Control': 'no-store',
    ...extraHeaders,
  }
  const lines = [`HTTP/1.1 ${status} ${reason}`]
  for (const [name, value] of Object.entries(headers)) lines.push(`${name}: ${value}`)
  socket.end(`${lines.join('\r\n')}\r\n\r\n${body}`)
}

function bearerFromRequest(request) {
  const authorizationHeaders = []
  for (let index = 0; index < request.rawHeaders.length; index += 2) {
    if (request.rawHeaders[index].toLowerCase() === 'authorization') {
      authorizationHeaders.push(request.rawHeaders[index + 1])
    }
  }
  if (authorizationHeaders.length !== 1) return null
  const match = /^Bearer ([A-Za-z0-9._~-]{32,512})$/.exec(authorizationHeaders[0])
  return match?.[1] ?? null
}

function exactSubprotocol(request) {
  const raw = request.headers['sec-websocket-protocol']
  return typeof raw === 'string' && raw.trim() === PROTOCOL_VERSION
}

function safeLog(logger, level, record) {
  const method = typeof logger?.[level] === 'function' ? logger[level] : undefined
  method?.(record)
}

function responseForStatus(requestId, status, replay = false) {
  const turn = status.turn
  if (turn?.state === 'completed') {
    return {
      type: 'turn.result',
      requestId,
      replay,
      sessionId: status.sessionId,
      turnId: turn.turnId,
      retryId: turn.retryId,
      correlationId: turn.correlationId,
      outcome: 'completed',
      sequenceDigest: turn.sequenceDigest,
      providerTrace: turn.providerTrace,
      result: turn.result,
    }
  }
  return { type: 'turn.status', requestId, replay, status }
}

function closeReason(code) {
  return Buffer.from(code, 'utf8').subarray(0, 120).toString('utf8')
}

export function createRealtimeGatewayWebSocketServer({
  gateway,
  authorizer,
  clock,
  logger = undefined,
  host = '127.0.0.1',
  port = 0,
  helloTimeoutMs = LIMITS.helloTimeoutMs,
} = {}) {
  assertWebSocketPorts({ authorizer, gateway, clock })
  if (!Number.isSafeInteger(port) || port < 0 || port > 65_535) throw new TypeError('port is invalid')
  if (!Number.isSafeInteger(helloTimeoutMs) || helloTimeoutMs < 1 || helloTimeoutMs > 30_000) {
    throw new TypeError('helloTimeoutMs is invalid')
  }

  const httpServer = http.createServer((request, response) => {
    if (request.method === 'GET' && request.url === '/healthz') {
      const body = Buffer.from(JSON.stringify({ status: 'ok', service: 'realtime-gateway', protocol: PROTOCOL_VERSION }))
      response.writeHead(200, {
        'content-type': 'application/json',
        'content-length': body.length,
        'cache-control': 'no-store',
      })
      response.end(body)
      return
    }
    response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' })
    response.end('Not Found\n')
  })
  const webSockets = new WebSocketServer({
    noServer: true,
    maxPayload: LIMITS.maxAudioFrameBytes,
    perMessageDeflate: false,
    handleProtocols: (protocols) => (protocols.has(PROTOCOL_VERSION) ? PROTOCOL_VERSION : false),
  })

  httpServer.on('upgrade', async (request, socket, head) => {
    socket.on('error', () => {})
    let url
    try {
      url = new URL(request.url, 'http://realtime-gateway.local')
    } catch {
      writeUpgradeError(socket, 400, 'Bad Request')
      return
    }
    if (request.method !== 'GET' || url.pathname !== '/v1/realtime' || url.search !== '') {
      writeUpgradeError(socket, 404, 'Not Found')
      return
    }
    if (!exactSubprotocol(request)) {
      writeUpgradeError(socket, 426, 'Upgrade Required', { 'Sec-WebSocket-Protocol': PROTOCOL_VERSION })
      return
    }
    const token = bearerFromRequest(request)
    if (!token) {
      writeUpgradeError(socket, 401, 'Unauthorized', { 'WWW-Authenticate': 'Bearer realm="gumi-realtime-gateway"' })
      return
    }
    let authority
    try {
      authority = await authorizer.authenticate(token)
      if (!authority) throw new GatewayError('AUTHENTICATION_FAILED', 'voice-turn credential was not accepted')
      authority = validateAuthority(authority, clock.now())
    } catch {
      writeUpgradeError(socket, 403, 'Forbidden')
      return
    }
    webSockets.handleUpgrade(request, socket, head, (webSocket) => {
      webSockets.emit('connection', webSocket, request, authority)
    })
  })

  webSockets.on('connection', (webSocket, _request, authority) => {
    const context = {
      authority,
      lease: null,
      helloReceived: false,
      pendingMessages: 0,
      queue: Promise.resolve(),
    }
    const sendJson = (value) => {
      const encoded = JSON.stringify(value)
      if (byteLength(encoded) > LIMITS.maxControlFrameBytes) {
        throw new GatewayError('SERVER_CONTROL_FRAME_TOO_LARGE', 'server response exceeded its control-frame bound')
      }
      if (webSocket.readyState === WebSocket.OPEN) webSocket.send(encoded)
    }

    const timer = setTimeout(() => {
      if (!context.helloReceived && webSocket.readyState === WebSocket.OPEN) {
        sendJson({
          type: 'error',
          requestId: null,
          code: 'HELLO_TIMEOUT',
          message: 'client.hello was not received before the bounded deadline',
          retryable: true,
        })
        webSocket.close(1008, 'HELLO_TIMEOUT')
      }
    }, helloTimeoutMs)
    timer.unref?.()

    const handleError = (error, requestId = null) => {
      const normalized =
        error instanceof GatewayError
          ? error
          : new GatewayError('INTERNAL_ERROR', 'realtime operation failed with an ambiguous internal outcome', {
              retryable: true,
            })
      safeLog(logger, 'warn', {
        event: 'realtime-error',
        sessionId: authority.sessionId,
        code: normalized.code,
      })
      try {
        sendJson({
          type: 'error',
          requestId,
          code: normalized.code,
          message: normalized.message,
          retryable: normalized.retryable,
        })
      } catch {
        webSocket.terminate()
        return
      }
      if (FATAL_CODES.has(normalized.code) && webSocket.readyState === WebSocket.OPEN) {
        webSocket.close(normalized.closeCode, closeReason(normalized.code))
      }
    }

    const handleMessage = async (data, isBinary) => {
      if (isBinary) {
        if (!context.lease) throw new GatewayError('HELLO_REQUIRED', 'client.hello must precede binary audio')
        const frame = decodeAudioFrame(data)
        const outcome = await gateway.pushAudio(context.lease, frame)
        sendJson({ type: 'audio.ack', ...outcome.acknowledgement })
        safeLog(logger, 'info', {
          event: 'audio-ack',
          sessionId: context.lease.sessionId,
          turnId: frame.turnId,
          retryId: frame.retryId,
          sequence: frame.sequence,
          disposition: outcome.acknowledgement.disposition,
        })
        return
      }

      const frame = parseClientControlFrame(data.toString('utf8'))
      if (!context.lease) {
        if (frame.type !== 'client.hello') throw new GatewayError('HELLO_REQUIRED', 'client.hello must be first')
        const connected = await gateway.connect({ authority, hello: frame })
        context.lease = connected.lease
        context.helloReceived = true
        clearTimeout(timer)
        sendJson({
          type: 'server.ready',
          requestId: frame.requestId,
          protocol: PROTOCOL_VERSION,
          sessionId: connected.status.sessionId,
          connectionGeneration: connected.status.connectionGeneration,
          limits: {
            maxControlFrameBytes: LIMITS.maxControlFrameBytes,
            maxAudioPayloadBytes: LIMITS.maxAudioPayloadBytes,
            maxAudioBytesPerTurn: LIMITS.maxAudioBytesPerTurn,
            maxChunksPerTurn: LIMITS.maxChunksPerTurn,
            maxPendingMessages: LIMITS.maxPendingMessages,
          },
          status: connected.status,
        })
        safeLog(logger, 'info', {
          event: 'session-connected',
          sessionId: connected.status.sessionId,
          connectionGeneration: connected.status.connectionGeneration,
        })
        return
      }
      if (frame.type === 'client.hello') throw new GatewayError('HELLO_REPLAYED', 'client.hello is allowed only once')

      switch (frame.type) {
        case 'turn.start': {
          const outcome = await gateway.startTurn(context.lease, frame)
          const turn = outcome.status.turn
          if (turn.state === 'receiving') {
            sendJson({
              type: 'turn.accepted',
              requestId: frame.requestId,
              replay: outcome.replay,
              sessionId: outcome.status.sessionId,
              turnId: turn.turnId,
              retryId: turn.retryId,
              correlationId: turn.correlationId,
              nextSequence: turn.nextSequence,
              sequenceDigest: turn.sequenceDigest,
              providerTrace: turn.providerTrace,
            })
          } else {
            sendJson(responseForStatus(frame.requestId, outcome.status, outcome.replay))
          }
          break
        }
        case 'turn.end': {
          const outcome = await gateway.finishTurn(context.lease, frame)
          sendJson(responseForStatus(frame.requestId, outcome.status, outcome.replay))
          break
        }
        case 'turn.cancel': {
          const outcome = await gateway.cancelTurn(context.lease, frame)
          sendJson(responseForStatus(frame.requestId, outcome.status, outcome.replay))
          break
        }
        case 'turn.status': {
          const status = await gateway.turnStatus(context.lease, frame)
          sendJson(responseForStatus(frame.requestId, status, true))
          break
        }
        case 'turn.reconcile': {
          const outcome = await gateway.reconcileTurn(context.lease, frame)
          sendJson(responseForStatus(frame.requestId, outcome.status, outcome.replay))
          break
        }
      }
    }

    webSocket.on('message', (data, isBinary) => {
      context.pendingMessages += 1
      if (context.pendingMessages > LIMITS.maxPendingMessages) {
        handleError(
          new GatewayError('MESSAGE_BACKLOG_EXCEEDED', 'connection exceeded its bounded message backlog', {
            closeCode: 1009,
            retryable: true,
          }),
        )
        webSocket.close(1009, 'MESSAGE_BACKLOG_EXCEEDED')
        context.pendingMessages -= 1
        return
      }
      context.queue = context.queue
        .then(() => handleMessage(data, isBinary))
        .catch((error) => handleError(error))
        .finally(() => {
          context.pendingMessages -= 1
        })
    })

    webSocket.on('close', () => {
      clearTimeout(timer)
      if (context.lease) {
        void gateway.disconnect(context.lease).catch(() => {})
      }
    })
    webSocket.on('error', () => {})
  })

  return {
    async listen() {
      if (httpServer.listening) throw new Error('realtime gateway server is already listening')
      await new Promise((resolvePromise, rejectPromise) => {
        const onError = (error) => {
          httpServer.off('listening', onListening)
          rejectPromise(error)
        }
        const onListening = () => {
          httpServer.off('error', onError)
          resolvePromise()
        }
        httpServer.once('error', onError)
        httpServer.once('listening', onListening)
        httpServer.listen(port, host)
      })
      const address = httpServer.address()
      const addressHost = address.address.includes(':') ? `[${address.address}]` : address.address
      return {
        origin: `http://${addressHost}:${address.port}`,
        webSocketUrl: `ws://${addressHost}:${address.port}/v1/realtime`,
      }
    },
    async close({ gracePeriodMs = 250 } = {}) {
      for (const client of webSockets.clients) client.close(1001, 'SERVER_SHUTDOWN')
      const deadline = setTimeout(() => {
        for (const client of webSockets.clients) client.terminate()
      }, gracePeriodMs)
      deadline.unref?.()
      await Promise.all([
        new Promise((resolvePromise) => webSockets.close(() => resolvePromise())),
        new Promise((resolvePromise, rejectPromise) => {
          if (!httpServer.listening) return resolvePromise()
          httpServer.close((error) => (error ? rejectPromise(error) : resolvePromise()))
        }),
      ])
      clearTimeout(deadline)
    },
    httpServer,
    webSockets,
  }
}
