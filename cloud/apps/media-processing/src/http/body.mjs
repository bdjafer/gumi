import { TextDecoder } from 'node:util'

import { HttpAdapterError, RequestCancelledError, invalidHttpRequest } from './errors.mjs'
import { parseContentLength } from './headers.mjs'

const UTF8 = new TextDecoder('utf-8', { fatal: true })

function cancellationReason(signal) {
  return signal.reason instanceof Error ? signal.reason : new RequestCancelledError()
}

export async function readBoundedBody(request, { limitBytes, signal }) {
  if (!Number.isSafeInteger(limitBytes) || limitBytes < 1) throw new TypeError('limitBytes must be positive')
  if (signal.aborted) throw cancellationReason(signal)

  let contentLength
  try {
    contentLength = parseContentLength(request)
  } catch (error) {
    request.resume()
    throw error
  }
  if (contentLength !== undefined && contentLength > limitBytes) {
    request.resume()
    throw new HttpAdapterError('INVALID_REQUEST', 413, 'The request body exceeds the configured HTTP boundary.')
  }

  return new Promise((resolve, reject) => {
    const chunks = []
    let received = 0
    let settled = false

    const cleanup = () => {
      request.off('data', onData)
      request.off('end', onEnd)
      request.off('aborted', onAborted)
      request.off('error', onAborted)
      signal.removeEventListener('abort', onSignalAbort)
    }
    const finish = (callback, value) => {
      if (settled) return
      settled = true
      cleanup()
      callback(value)
    }
    const onData = (chunk) => {
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
      received += bytes.length
      if (received > limitBytes) {
        request.pause()
        finish(reject, new HttpAdapterError('INVALID_REQUEST', 413, 'The streamed request body exceeds the configured HTTP boundary.'))
        request.resume()
        return
      }
      chunks.push(bytes)
    }
    const onEnd = () => {
      if (contentLength !== undefined && received !== contentLength) {
        finish(reject, invalidHttpRequest('Received bytes differ from Content-Length.'))
        return
      }
      finish(resolve, Buffer.concat(chunks, received))
    }
    const onAborted = () => finish(reject, new RequestCancelledError())
    const onSignalAbort = () => finish(reject, cancellationReason(signal))

    request.on('data', onData)
    request.once('end', onEnd)
    request.once('aborted', onAborted)
    request.once('error', onAborted)
    signal.addEventListener('abort', onSignalAbort, { once: true })
  })
}

export async function readJsonBody(request, options) {
  const bytes = await readBoundedBody(request, options)
  if (bytes.length === 0) throw invalidHttpRequest('A non-empty JSON request body is required.')
  let text
  try {
    text = UTF8.decode(bytes)
  } catch {
    throw invalidHttpRequest('The JSON request body must be valid UTF-8.')
  }
  try {
    return JSON.parse(text)
  } catch {
    throw invalidHttpRequest('The request body is not valid JSON.')
  }
}
