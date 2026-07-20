import { TextDecoder } from 'node:util'

import { HttpAdapterError, RequestCancelledError, invalidHttpRequest } from './errors.mjs'
import { parseContentLength } from './headers.mjs'

const UTF8 = new TextDecoder('utf-8', { fatal: true })

function cancellationReason(signal) {
  return signal.reason instanceof Error ? signal.reason : new RequestCancelledError()
}

export async function readBoundedBody(
  request,
  { limitBytes, signal, requireContentLength = false, expectedLength = undefined, tooLargeCode = 'INVALID_REQUEST' },
) {
  if (!Number.isSafeInteger(limitBytes) || limitBytes <= 0) throw new TypeError('limitBytes must be a positive safe integer')
  if (signal.aborted) throw cancellationReason(signal)

  let contentLength
  try {
    contentLength = parseContentLength(request, { required: requireContentLength })
  } catch (error) {
    request.resume()
    throw error
  }
  if (contentLength !== undefined && contentLength > limitBytes) {
    request.resume()
    throw new HttpAdapterError(tooLargeCode, 413, 'The request body exceeds the configured HTTP boundary.', {
      details: { maximumBytes: String(limitBytes), declaredBytes: String(contentLength) },
    })
  }
  if (expectedLength !== undefined && contentLength !== expectedLength) {
    request.resume()
    throw new HttpAdapterError(
      'CONTENT_LENGTH_MISMATCH',
      422,
      'Content-Length differs from the exact declared payload length.',
      { details: { contentLength: contentLength === undefined ? null : String(contentLength), declaredBytes: String(expectedLength) } },
    )
  }

  return new Promise((resolve, reject) => {
    const chunks = []
    let received = 0
    let done = false

    const cleanup = () => {
      request.off('data', onData)
      request.off('end', onEnd)
      request.off('aborted', onAborted)
      request.off('error', onError)
      signal.removeEventListener('abort', onSignalAbort)
    }
    const settle = (callback, value) => {
      if (done) return
      done = true
      cleanup()
      callback(value)
    }
    const onData = (chunk) => {
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
      received += bytes.length
      if (received > limitBytes) {
        request.pause()
        settle(
          reject,
          new HttpAdapterError(tooLargeCode, 413, 'The streamed request body exceeds the configured HTTP boundary.', {
            details: { maximumBytes: String(limitBytes) },
          }),
        )
        request.resume()
        return
      }
      chunks.push(bytes)
    }
    const onEnd = () => {
      if (contentLength !== undefined && received !== contentLength) {
        settle(
          reject,
          new HttpAdapterError('CONTENT_LENGTH_MISMATCH', 422, 'Received bytes differ from Content-Length.', {
            details: { contentLength: String(contentLength), receivedBytes: String(received) },
          }),
        )
        return
      }
      settle(resolve, Buffer.concat(chunks, received))
    }
    const onAborted = () => settle(reject, new RequestCancelledError())
    const onError = () => settle(reject, new RequestCancelledError())
    const onSignalAbort = () => settle(reject, cancellationReason(signal))

    request.on('data', onData)
    request.once('end', onEnd)
    request.once('aborted', onAborted)
    request.once('error', onError)
    signal.addEventListener('abort', onSignalAbort, { once: true })
  })
}

export async function readJsonBody(request, options) {
  const bytes = await readBoundedBody(request, {
    ...options,
    tooLargeCode: 'REQUEST_BODY_TOO_LARGE',
  })
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
