import {
  HttpAdapterError,
  RateLimitedError,
} from './errors.mjs'
import { headerValues } from './headers.mjs'

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const SLUG = /^[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?$/

export class CredentialAuthenticationError extends Error {
  constructor(cause = undefined) {
    super('The bearer credential could not be authenticated.', { cause })
    this.name = 'CredentialAuthenticationError'
  }
}

export function assertAuthorizer(authorizer) {
  if (!authorizer || typeof authorizer.authenticate !== 'function') {
    throw new TypeError('authorizer.authenticate must be an injected function')
  }
}

function bearerToken(request, realm) {
  const values = headerValues(request, 'authorization')
  if (values.length !== 1) {
    throw new HttpAdapterError('AUTHENTICATION_REQUIRED', 401, 'Exactly one bearer credential is required.', {
      challengeRealm: realm,
    })
  }
  const match = /^Bearer ([\x21-\x7e]{1,8192})$/i.exec(values[0])
  if (!match || /[",;]/.test(match[1])) {
    throw new HttpAdapterError('AUTHENTICATION_REQUIRED', 401, 'The Authorization header is not usable.', {
      challengeRealm: realm,
    })
  }
  return match[1]
}

function adapterUnavailable(message) {
  throw new HttpAdapterError('DURABILITY_UNAVAILABLE', 503, message)
}

function requiredString(claims, name, { max = 512 } = {}) {
  const value = claims?.[name]
  if (
    typeof value !== 'string' ||
    value.length === 0 ||
    value.length > max ||
    value.trim() !== value ||
    /[\u0000-\u001f\u007f]/.test(value)
  ) {
    adapterUnavailable(`The authentication adapter returned a malformed ${name} claim.`)
  }
  return value
}

function scopes(claims) {
  if (!Array.isArray(claims?.scopes) || claims.scopes.length < 1 || claims.scopes.length > 64) {
    adapterUnavailable('The authentication adapter returned malformed scope claims.')
  }
  if (
    claims.scopes.some(
      (scope) => typeof scope !== 'string' || scope.length < 1 || scope.length > 128 || !/^[a-z0-9:_-]+$/.test(scope),
    ) ||
    new Set(claims.scopes).size !== claims.scopes.length
  ) {
    adapterUnavailable('The authentication adapter returned malformed scope claims.')
  }
  return new Set(claims.scopes)
}

function uuidClaim(claims, name) {
  const value = requiredString(claims, name, { max: 36 })
  if (!UUID_V7.test(value)) adapterUnavailable(`The authentication adapter returned a malformed ${name} claim.`)
  return value
}

function slugClaim(claims, name) {
  const value = requiredString(claims, name, { max: 128 })
  if (!SLUG.test(value)) adapterUnavailable(`The authentication adapter returned a malformed ${name} claim.`)
  return value
}

async function authenticate(request, authorizer, { realm, requiredKind, expectedAudience, signal }) {
  const token = bearerToken(request, realm)
  let claims
  try {
    claims = await authorizer.authenticate({ bearerToken: token, requiredKind, expectedAudience, signal })
  } catch (error) {
    if (signal.aborted) throw signal.reason
    if (error instanceof CredentialAuthenticationError) {
      throw new HttpAdapterError('AUTHENTICATION_REQUIRED', 401, 'The bearer credential could not be authenticated.', {
        challengeRealm: realm,
      })
    }
    if (error instanceof RateLimitedError) throw error
    throw new HttpAdapterError('DURABILITY_UNAVAILABLE', 503, 'The credential verifier was unavailable.', {
      cause: error,
    })
  }
  if (claims === null || claims === undefined) {
    throw new HttpAdapterError('AUTHENTICATION_REQUIRED', 401, 'The bearer credential could not be authenticated.', {
      challengeRealm: realm,
    })
  }
  if (!claims || typeof claims !== 'object' || Array.isArray(claims)) {
    adapterUnavailable('The authentication adapter returned malformed claims.')
  }
  const kind = requiredString(claims, 'credentialKind', { max: 64 })
  const audience = requiredString(claims, 'audience')
  const granted = scopes(claims)
  return { claims, kind, audience, granted }
}

function assertPlane({ kind, audience, granted }, { requiredKind, expectedAudience, requiredScope, code, message }) {
  if (kind !== requiredKind || audience !== expectedAudience || !granted.has(requiredScope)) {
    throw new HttpAdapterError(code, 403, message)
  }
}

export async function authorizeControl(request, authorizer, { signal, expectedAudience }) {
  const authenticated = await authenticate(request, authorizer, {
    realm: 'gumi-media-processing-control',
    requiredKind: 'control',
    expectedAudience,
    signal,
  })
  assertPlane(authenticated, {
    requiredKind: 'control',
    expectedAudience,
    requiredScope: 'media-processing:control',
    code: 'CONTROL_SCOPE_MISMATCH',
    message: 'The credential cannot exercise the media-processing control plane.',
  })
  return {
    kind: 'control',
    callerId: requiredString(authenticated.claims, 'principalId'),
  }
}

export async function authorizeWorker(request, authorizer, { signal, expectedAudience }) {
  const authenticated = await authenticate(request, authorizer, {
    realm: 'gumi-media-processing-worker',
    requiredKind: 'worker',
    expectedAudience,
    signal,
  })
  assertPlane(authenticated, {
    requiredKind: 'worker',
    expectedAudience,
    requiredScope: 'media-processing:worker',
    code: 'WORKER_SCOPE_MISMATCH',
    message: 'The credential cannot exercise the media-processing worker plane.',
  })
  const pipelineIds = authenticated.claims.allowedPipelineIds
  if (
    !Array.isArray(pipelineIds) ||
    pipelineIds.length < 1 ||
    pipelineIds.length > 64 ||
    new Set(pipelineIds).size !== pipelineIds.length ||
    pipelineIds.some((pipelineId) => typeof pipelineId !== 'string' || !SLUG.test(pipelineId))
  ) {
    adapterUnavailable('The authentication adapter returned a malformed allowedPipelineIds claim.')
  }
  return {
    kind: 'worker',
    workerId: requiredString(authenticated.claims, 'workerId'),
    allowedPipelineIds: structuredClone(pipelineIds),
  }
}

export async function authorizeCallback(request, authorizer, { signal, expectedAudience, expectedAttemptId }) {
  const authenticated = await authenticate(request, authorizer, {
    realm: 'gumi-media-processing-provider-callback',
    requiredKind: 'provider-callback',
    expectedAudience,
    signal,
  })
  assertPlane(authenticated, {
    requiredKind: 'provider-callback',
    expectedAudience,
    requiredScope: 'media-processing:provider-callback',
    code: 'CALLBACK_SCOPE_MISMATCH',
    message: 'The credential cannot exercise the media-processing callback plane.',
  })
  const principal = {
    kind: 'provider-callback',
    providerId: slugClaim(authenticated.claims, 'providerId'),
    processingJobId: uuidClaim(authenticated.claims, 'processingJobId'),
    attemptId: uuidClaim(authenticated.claims, 'attemptId'),
  }
  if (principal.attemptId !== expectedAttemptId) {
    throw new HttpAdapterError(
      'CALLBACK_SCOPE_MISMATCH',
      403,
      'The callback credential is not bound to the requested attempt.',
    )
  }
  return principal
}
