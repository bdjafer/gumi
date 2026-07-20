import { CredentialAuthenticationError } from '../http/auth.mjs'

/**
 * HTTP-boundary test infrastructure only. It is not a token issuer, signature
 * verifier, audience registry, revocation system, or production adapter.
 */
export class InMemoryHttpAuthorizer {
  #records = new Map()

  register(bearerToken, claims) {
    if (typeof bearerToken !== 'string' || bearerToken.length === 0) throw new TypeError('bearerToken is required')
    this.#records.set(bearerToken, { kind: 'claims', value: structuredClone(claims) })
    return this
  }

  reject(bearerToken, cause = undefined) {
    this.#records.set(bearerToken, { kind: 'authentication-error', value: cause })
    return this
  }

  fail(bearerToken, error) {
    this.#records.set(bearerToken, { kind: 'error', value: error })
    return this
  }

  async authenticate({ bearerToken, signal }) {
    signal?.throwIfAborted()
    const record = this.#records.get(bearerToken)
    if (!record) return null
    if (record.kind === 'authentication-error') throw new CredentialAuthenticationError(record.value)
    if (record.kind === 'error') throw record.value
    return structuredClone(record.value)
  }
}
