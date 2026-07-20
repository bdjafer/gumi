import { createServer } from 'node:http'

import { uuidV7 } from '../core/primitives.mjs'
import { authorizeControl, authorizeData, assertAuthorizer } from './auth.mjs'
import { readBoundedBody, readJsonBody } from './body.mjs'
import { chunkDescriptorFromRequest } from './chunk-descriptor.mjs'
import {
  HttpAdapterError,
  RequestCancelledError,
  asProblem,
  invalidHttpRequest,
} from './errors.mjs'
import {
  parseCanonicalU64Header,
  parseContentLength,
  parseCorrelationId,
  requireMediaType,
  singleHeader,
} from './headers.mjs'
import { assertTraceIdFactory, newUuidV7, nextTraceId } from './trace-id.mjs'

const ROUTES = [
  {
    method: 'POST',
    pattern: /^\/v1\/ingest-sessions$/,
    operationId: 'createIngestSession',
    plane: 'control',
  },
  {
    method: 'POST',
    pattern: /^\/v1\/ingest-sessions\/([^/]{1,128})\/credentials$/,
    parameterNames: ['ingestSessionId'],
    operationId: 'refreshIngestCredential',
    plane: 'control',
  },
  {
    method: 'GET',
    pattern: /^\/v1\/manifests\/([^/]{1,128})$/,
    parameterNames: ['manifestId'],
    operationId: 'getImmutableManifestProjection',
    plane: 'control',
  },
  {
    method: 'GET',
    pattern: /^\/v1\/ingest-sessions\/([^/]{1,128})\/status$/,
    parameterNames: ['ingestSessionId'],
    operationId: 'getIngestStatus',
    plane: 'data',
  },
  {
    method: 'PUT',
    pattern: /^\/v1\/ingest-sessions\/([^/]{1,128})\/streams\/([^/]{1,128})\/chunks\/([^/]{1,128})$/,
    parameterNames: ['ingestSessionId', 'streamId', 'chunkId'],
    operationId: 'putMediaChunk',
    plane: 'data',
  },
  {
    method: 'POST',
    pattern: /^\/v1\/ingest-sessions\/([^/]{1,128})\/finalize$/,
    parameterNames: ['ingestSessionId'],
    operationId: 'finalizeIngestSession',
    plane: 'data',
  },
]

const SERVICE_METHODS = [
  'createSession',
  'refreshCredential',
  'getStatus',
  'putChunk',
  'finalize',
  'getImmutableManifestProjection',
]

function assertService(service) {
  if (!service) throw new TypeError('service is required')
  for (const method of SERVICE_METHODS) {
    if (typeof service[method] !== 'function') throw new TypeError(`service.${method} must be a function`)
  }
}

function boundedInteger(value, name, minimum, maximum) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new TypeError(`${name} must be an integer in ${minimum}..${maximum}`)
  }
  return value
}

function boundedIdentity(value, name) {
  if (
    typeof value !== 'string' ||
    value.length === 0 ||
    value.length > 512 ||
    value.trim() !== value ||
    /[\u0000-\u001f\u007f]/.test(value)
  ) {
    throw new TypeError(`${name} must be a canonical non-empty string of at most 512 characters`)
  }
  return value
}

function canonicalOriginFormPath(requestTarget) {
  if (typeof requestTarget !== 'string' || !requestTarget.startsWith('/') || requestTarget.startsWith('//')) {
    throw invalidHttpRequest('The request target must use canonical origin form.')
  }
  if (
    requestTarget.includes('?') ||
    requestTarget.includes('#') ||
    requestTarget.includes('\\') ||
    requestTarget.includes('%') ||
    requestTarget.split('/').some((segment) => segment === '.' || segment === '..')
  ) {
    throw invalidHttpRequest('The request target is not a canonical media-ingest path.')
  }
  return requestTarget
}

function routeFor(method, pathname) {
  const pathMatches = []
  for (const route of ROUTES) {
    const match = route.pattern.exec(pathname)
    if (!match) continue
    pathMatches.push(route)
    if (route.method !== method) continue
    const params = {}
    for (const [index, name] of (route.parameterNames ?? []).entries()) params[name] = match[index + 1]
    return { ...route, params }
  }
  if (pathMatches.length > 0) {
    const allowed = [...new Set(pathMatches.map((route) => route.method))].sort().join(', ')
    throw new HttpAdapterError('INVALID_REQUEST', 405, 'The HTTP method is not allowed for this resource.', {
      headers: { allow: allowed },
    })
  }
  throw new HttpAdapterError('INVALID_REQUEST', 404, 'No media-ingest route matches the request target.')
}

function validatePathParameters(params) {
  for (const [name, value] of Object.entries(params)) uuidV7(value, name)
}

function assertNoRequestBody(request) {
  if (singleHeader(request, 'transfer-encoding') !== undefined) {
    request.resume()
    throw invalidHttpRequest('This operation does not accept a request body.')
  }
  const contentLength = parseContentLength(request)
  if (contentLength !== undefined && contentLength !== 0) {
    request.resume()
    throw invalidHttpRequest('This operation does not accept a request body.')
  }
  request.resume()
}

function responseHeaders(context, extra = undefined) {
  const result = {
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    'x-request-id': context.traceId,
    ...extra,
  }
  if (context.correlationId !== undefined) result['x-correlation-id'] = context.correlationId
  return result
}

function canRespond(response) {
  return !response.destroyed && !response.writableEnded
}

function writeJson(response, context, status, value, headers = undefined) {
  if (!canRespond(response)) throw new RequestCancelledError()
  const bytes = Buffer.from(JSON.stringify(value), 'utf8')
  response.writeHead(status, {
    ...responseHeaders(context, headers),
    'content-type': 'application/json; charset=utf-8',
    'content-length': String(bytes.length),
  })
  response.end(bytes)
  context.status = status
  context.phase = 'response-sent'
}

function writeProblem(response, context, error) {
  if (!canRespond(response)) return undefined
  const problem = asProblem(error, context.traceId)
  const bytes = Buffer.from(JSON.stringify(problem.body), 'utf8')
  response.writeHead(problem.status, {
    ...responseHeaders(context, problem.headers),
    'content-type': 'application/problem+json; charset=utf-8',
    'content-length': String(bytes.length),
  })
  response.end(bytes)
  context.status = problem.status
  context.errorCode = problem.code
  return problem
}

function assertConnected(context) {
  if (context.signal.aborted) {
    throw context.signal.reason instanceof Error ? context.signal.reason : new RequestCancelledError()
  }
}

async function dispatch(request, response, context, options) {
  const pathname = canonicalOriginFormPath(request.url)
  context.correlationId = parseCorrelationId(request)
  const route = routeFor(request.method, pathname)
  context.operationId = route.operationId
  validatePathParameters(route.params)
  context.resource = route.params

  context.phase = 'authorization'
  if (route.plane === 'control') {
    await authorizeControl(request, options.authorizer, {
      signal: context.signal,
      expectedPrincipalId: options.controlPrincipalId,
      expectedAudience: options.credentialAudience,
    })
  } else {
    await authorizeData(request, options.authorizer, {
      signal: context.signal,
      ingestSessionId: route.params.ingestSessionId,
      streamId: route.params.streamId,
      expectedAudience: options.credentialAudience,
    })
  }
  assertConnected(context)
  context.phase = 'request-body'

  switch (route.operationId) {
    case 'createIngestSession': {
      requireMediaType(request, 'application/json', { allowUtf8: true })
      const body = await readJsonBody(request, {
        limitBytes: options.maxJsonBodyBytes,
        signal: context.signal,
      })
      assertConnected(context)
      context.phase = 'core-effect'
      const outcome = await options.service.createSession(body)
      assertConnected(context)
      writeJson(response, context, outcome.disposition === 'created' ? 201 : 200, outcome.response, {
        'cache-control': 'private, no-store',
      })
      return
    }
    case 'refreshIngestCredential': {
      requireMediaType(request, 'application/json', { allowUtf8: true })
      const body = await readJsonBody(request, {
        limitBytes: options.maxJsonBodyBytes,
        signal: context.signal,
      })
      assertConnected(context)
      context.phase = 'core-effect'
      const result = await options.service.refreshCredential(route.params.ingestSessionId, body)
      assertConnected(context)
      writeJson(response, context, 200, result, { 'cache-control': 'private, no-store' })
      return
    }
    case 'getImmutableManifestProjection': {
      assertNoRequestBody(request)
      const expectedDigest = singleHeader(request, 'Gumi-Expected-Manifest-Digest', { required: true })
      assertConnected(context)
      context.phase = 'core-effect'
      const result = await options.service.getImmutableManifestProjection(route.params.manifestId, expectedDigest)
      assertConnected(context)
      writeJson(response, context, 200, result, { 'cache-control': 'private, no-store' })
      return
    }
    case 'getIngestStatus': {
      assertNoRequestBody(request)
      assertConnected(context)
      context.phase = 'core-effect'
      const result = await options.service.getStatus(route.params.ingestSessionId)
      assertConnected(context)
      writeJson(response, context, 200, result)
      return
    }
    case 'putMediaChunk': {
      requireMediaType(request, 'application/octet-stream')
      const descriptor = chunkDescriptorFromRequest(request, route.params)
      const payloadBytes = parseCanonicalU64Header(descriptor.payloadBytes, 'Gumi-Payload-Bytes', { positive: true })
      if (payloadBytes > BigInt(Number.MAX_SAFE_INTEGER)) {
        request.resume()
        throw new HttpAdapterError('CHUNK_TOO_LARGE', 413, 'The declared chunk length exceeds this runtime.')
      }
      const bytes = await readBoundedBody(request, {
        limitBytes: options.maxChunkBodyBytes,
        signal: context.signal,
        requireContentLength: true,
        expectedLength: Number(payloadBytes),
        tooLargeCode: 'CHUNK_TOO_LARGE',
      })
      assertConnected(context)
      context.phase = 'core-effect'
      const result = await options.service.putChunk(descriptor, bytes)
      assertConnected(context)
      writeJson(response, context, 200, result)
      return
    }
    case 'finalizeIngestSession': {
      requireMediaType(request, 'application/json', { allowUtf8: true })
      const body = await readJsonBody(request, {
        limitBytes: options.maxJsonBodyBytes,
        signal: context.signal,
      })
      assertConnected(context)
      context.phase = 'core-effect'
      const result = await options.service.finalize(route.params.ingestSessionId, body)
      assertConnected(context)
      writeJson(response, context, 200, result)
      return
    }
    default:
      throw new TypeError(`Unhandled operation ${route.operationId}`)
  }
}

function safeLog(logger, level, record) {
  try {
    logger?.[level]?.(record)
  } catch {
    // Observability cannot change protocol behavior.
  }
}

function requestLog(context, request, elapsedMs) {
  const record = {
    event: 'media-ingest.http.request',
    traceId: context.traceId,
    method: request.method,
    operationId: context.operationId,
    status: context.status,
    durationMs: elapsedMs,
  }
  if (context.correlationId !== undefined) record.correlationId = context.correlationId
  if (context.errorCode !== undefined) record.errorCode = context.errorCode
  if (context.phase !== undefined) record.terminalPhase = context.phase
  for (const key of ['ingestSessionId', 'streamId', 'chunkId', 'manifestId']) {
    if (context.resource?.[key] !== undefined) record[key] = context.resource[key]
  }
  return record
}

export function createMediaIngestHttpServer({
  service,
  authorizer,
  controlPrincipalId,
  credentialAudience,
  logger = undefined,
  traceIdFactory = newUuidV7,
  maxJsonBodyBytes = 262_144,
  maxChunkBodyBytes = 1_048_576,
  maxHeaderBytes = 32_768,
  requestTimeoutMs = 30_000,
  headersTimeoutMs = 10_000,
  keepAliveTimeoutMs = 5_000,
  maxRequestsPerSocket = 100,
} = {}) {
  assertService(service)
  assertAuthorizer(authorizer)
  boundedIdentity(controlPrincipalId, 'controlPrincipalId')
  boundedIdentity(credentialAudience, 'credentialAudience')
  assertTraceIdFactory(traceIdFactory)
  boundedInteger(maxJsonBodyBytes, 'maxJsonBodyBytes', 1, 16_777_216)
  boundedInteger(maxChunkBodyBytes, 'maxChunkBodyBytes', 1, 1_073_741_824)
  boundedInteger(maxHeaderBytes, 'maxHeaderBytes', 1024, 1_048_576)
  boundedInteger(requestTimeoutMs, 'requestTimeoutMs', 1, 3_600_000)
  boundedInteger(headersTimeoutMs, 'headersTimeoutMs', 1, 3_600_000)
  boundedInteger(keepAliveTimeoutMs, 'keepAliveTimeoutMs', 1, 600_000)
  boundedInteger(maxRequestsPerSocket, 'maxRequestsPerSocket', 1, 1_000_000)

  const active = new Set()
  const controllers = new Set()
  const drainWaiters = new Set()
  let closing = false
  let closePromise

  const options = {
    service,
    authorizer,
    logger,
    controlPrincipalId,
    credentialAudience,
    maxJsonBodyBytes,
    maxChunkBodyBytes,
  }
  let prefetchedTraceId = nextTraceId(traceIdFactory)
  const allocateTraceId = () => {
    if (prefetchedTraceId !== undefined) {
      const traceId = prefetchedTraceId
      prefetchedTraceId = undefined
      return traceId
    }
    try {
      return nextTraceId(traceIdFactory)
    } catch {
      // A transient observability-ID provider failure must not crash the HTTP process.
      return newUuidV7()
    }
  }
  const nodeServer = createServer({ maxHeaderSize: maxHeaderBytes })
  nodeServer.requestTimeout = requestTimeoutMs
  nodeServer.headersTimeout = headersTimeoutMs
  nodeServer.keepAliveTimeout = keepAliveTimeoutMs
  nodeServer.maxRequestsPerSocket = maxRequestsPerSocket

  const notifyDrained = () => {
    if (active.size !== 0) return
    for (const resolve of drainWaiters) resolve()
    drainWaiters.clear()
  }

  nodeServer.on('request', (request, response) => {
    const controller = new AbortController()
    controllers.add(controller)
    const context = {
      traceId: allocateTraceId(),
      signal: controller.signal,
      operationId: 'unmatched',
      status: 500,
      phase: 'routing',
    }
    const started = process.hrtime.bigint()
    const cancel = () => {
      if (!controller.signal.aborted) controller.abort(new RequestCancelledError())
    }
    request.once('aborted', cancel)
    request.socket.once('close', cancel)
    response.once('close', () => {
      if (!response.writableEnded) cancel()
    })

    const task = (async () => {
      try {
        if (closing) {
          throw new HttpAdapterError('DURABILITY_UNAVAILABLE', 503, 'The HTTP adapter is shutting down.')
        }
        await dispatch(request, response, context, options)
      } catch (error) {
        if (error instanceof RequestCancelledError || controller.signal.aborted) {
          context.status = 499
          context.errorCode = 'REQUEST_CANCELLED'
        } else {
          writeProblem(response, context, error)
        }
      } finally {
        const elapsedMs = Number((process.hrtime.bigint() - started) / 1_000_000n)
        const level = context.status >= 500 ? 'error' : context.status >= 400 ? 'warn' : 'info'
        safeLog(logger, level, requestLog(context, request, elapsedMs))
      }
    })()

    active.add(task)
    task.finally(() => {
      active.delete(task)
      controllers.delete(controller)
      request.off('aborted', cancel)
      request.socket.off('close', cancel)
      notifyDrained()
    })
  })

  nodeServer.on('clientError', (_error, socket) => {
    if (!socket.writable) return
    socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
  })

  const rejectExpectation = (request, response) => {
    const context = {
      traceId: allocateTraceId(),
      operationId: 'unmatched',
      status: 400,
      phase: 'expectation',
    }
    try {
      context.correlationId = parseCorrelationId(request)
      writeProblem(
        response,
        context,
        invalidHttpRequest('The Expect request header is not supported; send only a bounded request body.'),
      )
    } catch (error) {
      writeProblem(response, context, error)
    }
    request.resume()
    safeLog(logger, 'warn', requestLog(context, request, 0))
  }
  nodeServer.on('checkContinue', rejectExpectation)
  nodeServer.on('checkExpectation', rejectExpectation)

  const waitUntilDrained = () => {
    if (active.size === 0) return Promise.resolve()
    return new Promise((resolve) => drainWaiters.add(resolve))
  }

  const currentAddress = () => {
    const address = nodeServer.address()
    if (!address || typeof address === 'string') return undefined
    const host = address.family === 'IPv6' ? `[${address.address}]` : address.address
    return { ...address, origin: `http://${host}:${address.port}` }
  }

  return {
    nodeServer,

    async listen({ host = '127.0.0.1', port = 0 } = {}) {
      if (closing) throw new Error('The HTTP adapter is closing and cannot listen again.')
      boundedInteger(port, 'port', 0, 65_535)
      await new Promise((resolve, reject) => {
        const onError = (error) => {
          nodeServer.off('listening', onListening)
          reject(error)
        }
        const onListening = () => {
          nodeServer.off('error', onError)
          resolve()
        }
        nodeServer.once('error', onError)
        nodeServer.once('listening', onListening)
        nodeServer.listen({ host, port })
      })
      return currentAddress()
    },

    address: currentAddress,

    async close({ gracePeriodMs = 10_000 } = {}) {
      if (closePromise) return closePromise
      boundedInteger(gracePeriodMs, 'gracePeriodMs', 0, 600_000)
      closing = true
      closePromise = (async () => {
        const closed = nodeServer.listening
          ? new Promise((resolve, reject) => {
              nodeServer.close((error) => (error ? reject(error) : resolve()))
            })
          : Promise.resolve()
        nodeServer.closeIdleConnections?.()

        let timer
        const drainedBeforeDeadline = await Promise.race([
          waitUntilDrained().then(() => true),
          new Promise((resolve) => {
            timer = setTimeout(() => resolve(false), gracePeriodMs)
          }),
        ])
        if (timer !== undefined) clearTimeout(timer)

        const abortedRequests = drainedBeforeDeadline ? 0 : controllers.size
        if (!drainedBeforeDeadline) {
          for (const controller of controllers) {
            if (!controller.signal.aborted) controller.abort(new RequestCancelledError('The server shutdown grace period elapsed.'))
          }
          nodeServer.closeAllConnections?.()
        }
        await closed
        return {
          drained: active.size === 0,
          abortedRequests,
          activeRequests: active.size,
        }
      })()
      return closePromise
    },
  }
}
