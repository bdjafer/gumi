export class ProcessingError extends Error {
  constructor(code, status, message, details, cause) {
    super(message, { cause })
    this.name = 'ProcessingError'
    this.code = code
    this.status = status
    if (details !== undefined) this.details = structuredClone(details)
  }
}

export class DurabilityError extends Error {
  constructor(boundary, phase) {
    super(`Injected durability failure ${phase} ${boundary}`)
    this.name = 'DurabilityError'
    this.boundary = boundary
    this.phase = phase
  }
}

export function fail(code, status, message, details) {
  throw new ProcessingError(code, status, message, details)
}

export function invalid(message, details) {
  fail('INVALID_REQUEST', 400, message, details)
}
