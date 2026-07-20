import { createServer } from 'node:http'

import { authorizeCallback, authorizeControl, authorizeWorker, assertAuthorizer } from './auth.mjs'
import { readJsonBody } from './body.mjs'
import { encodeBoundedJson } from './bounded-json.mjs'
import {
  HttpAdapterError,
  RequestCancelledError,
  asProblem,
  invalidHttpRequest,
} from './errors.mjs'
import {
  assertNoRequestBody,
  isUuidV7,
  parseCorrelationId,
  requireJsonMediaType,
} from './headers.mjs'
import { assertTraceIdFactory, newUuidV7, nextTraceId } from './trace-id.mjs'

const DIGEST = /^sha256:[0-9a-f]{64}$/

const ROUTES = [
  {
    method: 'POST',
    pattern: /^\/v1\/processing-jobs$/,
    operationId: 'createProcessingJob',
    plane: 'control',
  },
  {
    method: 'GET',
    pattern: /^\/v1\/processing-jobs\/([^/:]{1,128})$/,
    parameterNames: ['processingJobId'],
    operationId: 'getProcessingJob',
    plane: 'control',
  },
  {
    method: 'POST',
    pattern: /^\/v1\/processing-jobs\/([^/:]{1,128}):retry$/,
    parameterNames: ['processingJobId'],
    operationId: 'retryProcessingJob',
    plane: 'control',
  },
  {
    method: 'POST',
    pattern: /^\/v1\/processing-jobs\/([^/:]{1,128}):cancel$/,
    parameterNames: ['processingJobId'],
    operationId: 'cancelProcessingJob',
    plane: 'control',
  },
  {
    method: 'POST',
    pattern: /^\/v1\/processing-jobs\/([^/]{1,128})\/attempts:claim$/,
    parameterNames: ['processingJobId'],
    operationId: 'claimProcessingAttempt',
    plane: 'worker',
  },
  {
    method: 'POST',
    pattern: /^\/v1\/processing-jobs\/([^/]{1,128})\/attempts\/([^/]{1,128}):renew$/,
    parameterNames: ['processingJobId', 'attemptId'],
    operationId: 'renewProcessingLease',
    plane: 'worker',
  },
  {
    method: 'POST',
    pattern: /^\/v1\/provider-callbacks\/([^/]{1,128}):complete$/,
    parameterNames: ['attemptId'],
    operationId: 'completeProcessingAttempt',
    plane: 'callback',
  },
  {
    method: 'GET',
    pattern: /^\/v1\/processing-results\/([^/]{1,128})$/,
    parameterNames: ['processingJobId'],
    operationId: 'resolveProcessingResult',
    plane: 'control',
    query: 'result-digests',
  },
  {
    method: 'GET',
    pattern: /^\/v1\/processing-results\/([^/]{1,128})\/transcript-pages\/([^/]{1,128})$/,
    parameterNames: ['processingJobId', 'startIndex'],
    operationId: 'readTranscriptPage',
    plane: 'control',
    query: 'result-digests',
  },
]

const SERVICE_METHODS = [
  'createJob',
  'getStatus',
  'retryJob',
  'cancelJob',
  'claimAttempt',
  'renewLease',
  'completeAttempt',
  'resolveResult',
  'readTranscriptPage',
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

function splitCanonicalRequestTarget(requestTarget) {
  if (typeof requestTarget !== 'string' || !requestTarget.startsWith('/') || requestTarget.startsWith('//')) {
    throw invalidHttpRequest('The request target must use canonical origin form.')
  }
  if (requestTarget.includes('#') || requestTarget.includes('\\')) {
    throw invalidHttpRequest('The request target is not a canonical media-processing target.')
  }
  const queryIndex = requestTarget.indexOf('?')
  const hasQuery = queryIndex !== -1
  const pathname = queryIndex === -1 ? requestTarget : requestTarget.slice(0, queryIndex)
  const rawQuery = queryIndex === -1 ? '' : requestTarget.slice(queryIndex + 1)
  if (
    pathname.length === 0 ||
    pathname.includes('%') ||
    pathname.split('/').some((segment) => segment === '.' || segment === '..') ||
    rawQuery.includes('?')
  ) {
    throw invalidHttpRequest('The request target is not a canonical media-processing target.')
  }
  return { pathname, rawQuery, hasQuery }
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
    const allowedMethods = [...new Set(pathMatches.map((route) => route.method))].sort()
    throw invalidHttpRequest('The HTTP method is not allowed for this resource.', 405, { allowedMethods })
  }
  throw invalidHttpRequest('No media-processing route matches the request target.', 404)
}

function validatePathParameters(params) {
  for (const [name, value] of Object.entries(params)) {
    if (name === 'startIndex') {
      if (!/^(0|[1-9][0-9]{0,5})$/.test(value) || BigInt(value) > 100_000n) {
        throw invalidHttpRequest('A transcript page start is not a canonical v1 segment index.')
      }
    } else if (!isUuidV7(value)) {
      throw invalidHttpRequest('A path identity is not a lowercase UUIDv7.')
    }
  }
}

function parseRouteQuery(route, rawQuery, hasQuery) {
  if (route.query !== 'result-digests') {
    if (hasQuery) throw invalidHttpRequest('This media-processing route does not accept query parameters.')
    return undefined
  }
  const values = {}
  const parts = rawQuery.split('&')
  if (parts.length !== 2) {
    throw invalidHttpRequest('Result resolution requires exactly two digest query parameters.')
  }
  for (const part of parts) {
    const separator = part.indexOf('=')
    if (separator < 1 || separator !== part.lastIndexOf('=')) {
      throw invalidHttpRequest('A result digest query parameter is malformed.')
    }
    const name = part.slice(0, separator)
    const rawValue = part.slice(separator + 1)
    if (!['expectedInputContentDigest', 'expectedOutputContentDigest'].includes(name) || Object.hasOwn(values, name)) {
      throw invalidHttpRequest('Result digest query names must be exact, unique, and complete.')
    }
    const value = rawValue.replace(/^sha256%3[aA]/, 'sha256:')
    if (!DIGEST.test(value) || (rawValue.includes('%') && !/^sha256%3[aA][0-9a-f]{64}$/.test(rawValue))) {
      throw invalidHttpRequest('A result digest query value is not canonical SHA-256.')
    }
    values[name] = value
  }
  return values
}

function assertBodyPathBinding(body, params, names) {
  for (const name of names) {
    if (!body || body[name] !== params[name]) {
      throw invalidHttpRequest(`The request body ${name} must equal the canonical path identity.`)
    }
  }
}

function responseHeaders(context, extra = undefined) {
  const result = {
    'cache-control': extra?.['cache-control'] === 'private, no-store' ? 'private, no-store' : 'no-store',
    'x-content-type-options': 'nosniff',
    'x-request-id': context.traceId,
  }
  for (const name of ['allow', 'retry-after', 'www-authenticate']) {
    if (typeof extra?.[name] === 'string') result[name] = extra[name]
  }
  if (context.correlationId !== undefined) result['x-correlation-id'] = context.correlationId
  return result
}

function canRespond(response) {
  return !response.destroyed && !response.writableEnded && !response.headersSent
}

function writeJson(response, context, status, value, { headers = undefined, maximumBytes } = {}) {
  if (!canRespond(response)) throw new RequestCancelledError()
  const bytes = encodeBoundedJson(value, maximumBytes)
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
  context.phase = 'problem-sent'
  return problem
}

function assertConnected(context) {
  if (context.signal.aborted) {
    throw context.signal.reason instanceof Error ? context.signal.reason : new RequestCancelledError()
  }
}

async function invokeCore(context, operation) {
  assertConnected(context)
  context.phase = 'core-effect'
  context.effectStarted = true
  const result = await operation()
  context.effectSettled = true
  context.phase = 'core-effect-settled'
  assertConnected(context)
  return result
}

async function dispatch(request, response, context, options) {
  const target = splitCanonicalRequestTarget(request.url)
  context.correlationId = parseCorrelationId(request)
  const route = routeFor(request.method, target.pathname)
  context.operationId = route.operationId
  validatePathParameters(route.params)
  const query = parseRouteQuery(route, target.rawQuery, target.hasQuery)
  context.resource = route.params

  context.phase = 'authorization'
  const principal = route.plane === 'control'
    ? await authorizeControl(request, options.authorizer, {
        signal: context.signal,
        expectedAudience: options.controlCredentialAudience,
      })
    : route.plane === 'worker'
      ? await authorizeWorker(request, options.authorizer, {
          signal: context.signal,
          expectedAudience: options.workerCredentialAudience,
        })
      : await authorizeCallback(request, options.authorizer, {
          signal: context.signal,
          expectedAudience: options.callbackCredentialAudience,
          expectedAttemptId: route.params.attemptId,
        })
  assertConnected(context)

  if (request.method === 'GET') assertNoRequestBody(request)
  else requireJsonMediaType(request)
  context.phase = 'request-body'

  let body
  if (request.method !== 'GET') {
    body = await readJsonBody(request, { limitBytes: options.maxJsonBodyBytes, signal: context.signal })
    assertConnected(context)
  }

  let result
  let status = 200
  let headers
  switch (route.operationId) {
    case 'createProcessingJob':
      result = await invokeCore(context, () => options.service.createJob(body, principal))
      status = result.disposition === 'created' ? 201 : 200
      headers = { 'cache-control': 'private, no-store' }
      break
    case 'getProcessingJob':
      result = await invokeCore(context, () => options.service.getStatus(route.params.processingJobId, principal))
      break
    case 'retryProcessingJob':
      assertBodyPathBinding(body, route.params, ['processingJobId'])
      result = await invokeCore(context, () => options.service.retryJob(body, principal))
      break
    case 'cancelProcessingJob':
      assertBodyPathBinding(body, route.params, ['processingJobId'])
      result = await invokeCore(context, () => options.service.cancelJob(body, principal))
      break
    case 'claimProcessingAttempt':
      assertBodyPathBinding(body, route.params, ['processingJobId'])
      result = await invokeCore(context, () => options.service.claimAttempt(body, principal))
      headers = { 'cache-control': 'private, no-store' }
      break
    case 'renewProcessingLease':
      assertBodyPathBinding(body, route.params, ['processingJobId', 'attemptId'])
      result = await invokeCore(context, () => options.service.renewLease(body, principal))
      break
    case 'completeProcessingAttempt':
      assertBodyPathBinding(body, route.params, ['attemptId'])
      if (body?.processingJobId !== principal.processingJobId) {
        throw new HttpAdapterError(
          'CALLBACK_SCOPE_MISMATCH',
          403,
          'The callback credential is not bound to the request processing job.',
        )
      }
      result = await invokeCore(context, () => options.service.completeAttempt(body, principal))
      break
    case 'resolveProcessingResult':
      result = await invokeCore(context, () => options.service.resolveResult({
        processingJobId: route.params.processingJobId,
        ...query,
      }, principal))
      headers = { 'cache-control': 'private, no-store' }
      break
    case 'readTranscriptPage':
      result = await invokeCore(context, () => options.service.readTranscriptPage({
        processingJobId: route.params.processingJobId,
        startIndex: route.params.startIndex,
        ...query,
      }, principal))
      headers = { 'cache-control': 'private, no-store' }
      break
    default:
      throw new TypeError(`Unhandled operation ${route.operationId}`)
  }

  writeJson(response, context, status, result, {
    headers,
    maximumBytes: options.maxJsonResponseBytes,
  })
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
    event: 'media-processing.http.request',
    traceId: context.traceId,
    method: ['GET', 'POST'].includes(request.method) ? request.method : 'OTHER',
    operationId: context.operationId,
    status: context.status,
    durationMs: elapsedMs,
    terminalPhase: context.phase,
  }
  if (context.correlationId !== undefined) record.correlationId = context.correlationId
  if (context.errorCode !== undefined) record.errorCode = context.errorCode
  if (context.effectStarted) record.coreEffectStarted = true
  if (context.effectSettled) record.coreEffectSettled = true
  for (const key of ['processingJobId', 'attemptId']) {
    if (context.resource?.[key] !== undefined) record[key] = context.resource[key]
  }
  return record
}

export function createMediaProcessingHttpServer({
  service,
  authorizer,
  controlCredentialAudience,
  workerCredentialAudience,
  callbackCredentialAudience,
  logger = undefined,
  traceIdFactory = newUuidV7,
  maxJsonBodyBytes = 262_144,
  maxJsonResponseBytes = 1_048_576,
  maxHeaderBytes = 32_768,
  requestTimeoutMs = 30_000,
  headersTimeoutMs = 10_000,
  keepAliveTimeoutMs = 5_000,
  maxRequestsPerSocket = 100,
} = {}) {
  assertService(service)
  assertAuthorizer(authorizer)
  const audiences = [
    boundedIdentity(controlCredentialAudience, 'controlCredentialAudience'),
    boundedIdentity(workerCredentialAudience, 'workerCredentialAudience'),
    boundedIdentity(callbackCredentialAudience, 'callbackCredentialAudience'),
  ]
  if (new Set(audiences).size !== audiences.length) {
    throw new TypeError('control, worker, and callback credential audiences must be distinct')
  }
  assertTraceIdFactory(traceIdFactory)
  boundedInteger(maxJsonBodyBytes, 'maxJsonBodyBytes', 1, 16_777_216)
  boundedInteger(maxJsonResponseBytes, 'maxJsonResponseBytes', 1_024, 16_777_216)
  boundedInteger(maxHeaderBytes, 'maxHeaderBytes', 1_024, 1_048_576)
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
    controlCredentialAudience,
    workerCredentialAudience,
    callbackCredentialAudience,
    maxJsonBodyBytes,
    maxJsonResponseBytes,
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
      effectStarted: false,
      effectSettled: false,
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
        if (closing) throw new HttpAdapterError('DURABILITY_UNAVAILABLE', 503, 'The HTTP adapter is shutting down.')
        await dispatch(request, response, context, options)
      } catch (error) {
        if (error instanceof RequestCancelledError || controller.signal.aborted) {
          context.status = 499
          context.errorCode = 'REQUEST_CANCELLED'
          context.phase = context.effectStarted
            ? context.effectSettled
              ? 'effect-settled-without-response'
              : 'effect-outcome-unknown'
            : 'request-cancelled-before-effect'
        } else {
          try {
            if (writeProblem(response, context, error) === undefined) {
              context.status = 499
              context.errorCode = 'REQUEST_CANCELLED'
              context.phase = context.effectStarted ? 'response-unavailable-after-effect' : 'response-unavailable'
            }
          } catch {
            context.status = 499
            context.errorCode = 'REQUEST_CANCELLED'
            context.phase = context.effectStarted ? 'response-failed-after-effect' : 'response-failed'
            response.destroy()
          }
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
      effectStarted: false,
      effectSettled: false,
    }
    try {
      context.correlationId = parseCorrelationId(request)
      writeProblem(response, context, invalidHttpRequest('The Expect request header is not supported.'))
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
            if (!controller.signal.aborted) {
              controller.abort(new RequestCancelledError('The server shutdown grace period elapsed.'))
            }
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
