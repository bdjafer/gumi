export class IngestError extends Error {
  constructor(code, status, message, details = undefined, cause = undefined) {
    super(message, { cause })
    this.name = 'IngestError'
    this.code = code
    this.status = status
    if (details !== undefined) this.details = details
  }
}

export class DurabilityError extends IngestError {
  constructor(boundary, phase) {
    super(
      'DURABILITY_UNAVAILABLE',
      503,
      `The durable ${boundary} boundary was unavailable ${phase} commit.`,
      { boundary, phase },
    )
    this.name = 'DurabilityError'
  }
}

export function fail(code, status, message, details = undefined) {
  throw new IngestError(code, status, message, details)
}

export function invalid(path, message) {
  fail('INVALID_REQUEST', 400, `${path}: ${message}`)
}
