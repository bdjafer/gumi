import { HttpAdapterError, bearerChallenge } from './errors.mjs'
import { headerValues } from './headers.mjs'

const AUTHENTICATION_CODES = new Set([
  'INVALID_CONTROL_CREDENTIAL',
  'INVALID_INGEST_CREDENTIAL',
  'INGEST_CREDENTIAL_EXPIRED',
])

const AUTHORIZATION_DENIAL_CODES = new Set([
  'DEVICE_REVOKED',
  'CAPTURE_REVOKED',
])

const AUTHENTICATION_DETAILS = new Map([
  ['INVALID_CONTROL_CREDENTIAL', 'The control credential could not be authenticated.'],
  ['INVALID_INGEST_CREDENTIAL', 'The ingest credential could not be authenticated.'],
  ['INGEST_CREDENTIAL_EXPIRED', 'The ingest credential has expired.'],
])

const AUTHORIZATION_DETAILS = new Map([
  ['DEVICE_REVOKED', 'The device binding has been revoked.'],
  ['CAPTURE_REVOKED', 'The capture binding has been revoked.'],
])

export class CredentialAuthenticationError extends Error {
  constructor(code, cause = undefined) {
    if (!AUTHENTICATION_CODES.has(code)) throw new TypeError(`Unsupported credential error code ${code}`)
    super('The bearer credential could not be authenticated.', { cause })
    this.name = 'CredentialAuthenticationError'
    this.code = code
  }
}

export class CredentialAuthorizationError extends Error {
  constructor(code, cause = undefined) {
    if (!AUTHORIZATION_DENIAL_CODES.has(code)) throw new TypeError(`Unsupported credential denial code ${code}`)
    super('The authenticated credential was denied by its bound authorization policy.', { cause })
    this.name = 'CredentialAuthorizationError'
    this.code = code
  }
}

export function assertAuthorizer(authorizer) {
  if (!authorizer || typeof authorizer.authenticate !== 'function') {
    throw new TypeError('authorizer.authenticate must be an injected function')
  }
}

function bearerToken(request, realm) {
  const values = headerValues(request, 'authorization')
  if (values.length === 0) {
    throw new HttpAdapterError('AUTHENTICATION_REQUIRED', 401, 'A bearer credential is required.', {
      headers: { 'www-authenticate': bearerChallenge(realm) },
    })
  }
  if (values.length !== 1) {
    throw new HttpAdapterError('AUTHENTICATION_REQUIRED', 401, 'Exactly one bearer credential is required.', {
      headers: { 'www-authenticate': bearerChallenge(realm) },
    })
  }
  const match = /^Bearer ([\x21-\x7e]{1,8192})$/i.exec(values[0])
  if (!match || /[",;]/.test(match[1])) {
    throw new HttpAdapterError('AUTHENTICATION_REQUIRED', 401, 'The Authorization header is not a usable bearer credential.', {
      headers: { 'www-authenticate': bearerChallenge(realm) },
    })
  }
  return match[1]
}

function scopes(claims) {
  if (!Array.isArray(claims?.scopes) || claims.scopes.some((scope) => typeof scope !== 'string')) {
    throw new HttpAdapterError(
      'DURABILITY_UNAVAILABLE',
      503,
      'The authentication adapter returned malformed scope claims.',
    )
  }
  return new Set(claims.scopes)
}

function requiredClaimString(claims, name) {
  const value = claims?.[name]
  if (
    typeof value !== 'string' ||
    value.length === 0 ||
    value.length > 512 ||
    value.trim() !== value ||
    /[\u0000-\u001f\u007f]/.test(value)
  ) {
    throw new HttpAdapterError(
      'DURABILITY_UNAVAILABLE',
      503,
      `The authentication adapter returned a malformed ${name} claim.`,
    )
  }
  return value
}

async function authenticate(request, authorizer, realm, context) {
  const token = bearerToken(request, realm)
  try {
    const claims = await authorizer.authenticate({ bearerToken: token, signal: context.signal })
    if (claims === null || claims === undefined) {
      const code = context.requiredKind === 'control' ? 'INVALID_CONTROL_CREDENTIAL' : 'INVALID_INGEST_CREDENTIAL'
      throw new CredentialAuthenticationError(code)
    }
    return claims
  } catch (error) {
    if (context.signal.aborted) throw context.signal.reason
    if (error instanceof CredentialAuthenticationError) {
      const code = context.requiredKind === 'control'
        ? 'INVALID_CONTROL_CREDENTIAL'
        : error.code === 'INGEST_CREDENTIAL_EXPIRED'
          ? error.code
          : 'INVALID_INGEST_CREDENTIAL'
      throw new HttpAdapterError(code, 401, AUTHENTICATION_DETAILS.get(code), {
        headers: { 'www-authenticate': bearerChallenge(realm) },
      })
    }
    if (error instanceof CredentialAuthorizationError) {
      throw new HttpAdapterError(error.code, 403, AUTHORIZATION_DETAILS.get(error.code))
    }
    if (error instanceof HttpAdapterError) throw error
    throw new HttpAdapterError(
      'DURABILITY_UNAVAILABLE',
      503,
      'The credential verifier was unavailable.',
      { cause: error },
    )
  }
}

export async function authorizeControl(request, authorizer, context) {
  const claims = await authenticate(request, authorizer, 'gumi-media-ingest-control', {
    ...context,
    requiredKind: 'control',
  })
  const granted = scopes(claims)
  const principalId = requiredClaimString(claims, 'principalId')
  const audience = requiredClaimString(claims, 'audience')
  if (
    claims.credentialKind !== 'control' ||
    !granted.has('media-ingest:control') ||
    principalId !== context.expectedPrincipalId ||
    audience !== context.expectedAudience
  ) {
    throw new HttpAdapterError(
      'CONTROL_SCOPE_MISMATCH',
      403,
      'The credential cannot exercise the media-ingest control plane.',
    )
  }
  return claims
}

export async function authorizeData(
  request,
  authorizer,
  { signal, ingestSessionId, streamId = undefined, expectedAudience },
) {
  const claims = await authenticate(request, authorizer, 'gumi-media-ingest-data', {
    signal,
    requiredKind: 'ingest',
  })
  const granted = scopes(claims)
  requiredClaimString(claims, 'principalId')
  const audience = requiredClaimString(claims, 'audience')
  if (claims.credentialKind !== 'ingest' || !granted.has('media-ingest:data') || audience !== expectedAudience) {
    throw new HttpAdapterError(
      'SESSION_SCOPE_MISMATCH',
      403,
      'The credential cannot exercise the media-ingest data plane.',
    )
  }
  if (typeof claims.ingestSessionId !== 'string' || !Array.isArray(claims.streamIds)) {
    throw new HttpAdapterError(
      'DURABILITY_UNAVAILABLE',
      503,
      'The authentication adapter returned malformed ingest binding claims.',
    )
  }
  if (claims.ingestSessionId !== ingestSessionId || (streamId !== undefined && !claims.streamIds.includes(streamId))) {
    throw new HttpAdapterError(
      'SESSION_SCOPE_MISMATCH',
      403,
      'The ingest credential is not bound to the requested session and stream.',
    )
  }
  if (claims.streamIds.some((candidate) => typeof candidate !== 'string')) {
    throw new HttpAdapterError(
      'DURABILITY_UNAVAILABLE',
      503,
      'The authentication adapter returned malformed stream claims.',
    )
  }
  return claims
}
