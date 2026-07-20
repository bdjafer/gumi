export class GatewayError extends Error {
  constructor(code, message, { closeCode = 1008, retryable = false, details = undefined } = {}) {
    super(message)
    this.name = 'GatewayError'
    this.code = code
    this.closeCode = closeCode
    this.retryable = retryable
    this.details = details
  }
}

export class ProviderEffectOutcomeUnknownError extends Error {
  constructor(message = 'provider effect outcome is unknown', { providerTrace = undefined } = {}) {
    super(message)
    this.name = 'ProviderEffectOutcomeUnknownError'
    this.providerTrace = providerTrace
  }
}

export class ProviderKnownError extends Error {
  constructor(code, message, { retryable = false, providerTrace = undefined } = {}) {
    super(message)
    this.name = 'ProviderKnownError'
    this.code = code
    this.retryable = retryable
    this.providerTrace = providerTrace
  }
}

export function isOutcomeUnknown(error) {
  return error instanceof ProviderEffectOutcomeUnknownError
}
