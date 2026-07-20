import { CredentialAuthenticationError, CredentialAuthorizationError } from '../http/auth.mjs'

/**
 * Deterministic HTTP-boundary test infrastructure. It is deliberately not a
 * token issuer, signature verifier, revocation store, or production adapter.
 */
export class InMemoryHttpAuthorizer {
  #records = new Map()

  register(bearerToken, claims) {
    if (typeof bearerToken !== 'string' || bearerToken.length === 0) throw new TypeError('bearerToken is required')
    this.#records.set(bearerToken, { kind: 'claims', value: structuredClone(claims) })
    return this
  }

  reject(bearerToken, code) {
    this.#records.set(bearerToken, { kind: 'authentication-error', value: code })
    return this
  }

  deny(bearerToken, code) {
    this.#records.set(bearerToken, { kind: 'authorization-error', value: code })
    return this
  }

  async authenticate({ bearerToken, signal }) {
    signal?.throwIfAborted()
    const record = this.#records.get(bearerToken)
    if (!record) return null
    if (record.kind === 'authentication-error') throw new CredentialAuthenticationError(record.value)
    if (record.kind === 'authorization-error') throw new CredentialAuthorizationError(record.value)
    return structuredClone(record.value)
  }
}
