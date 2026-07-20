export class StableIdentityConflictError extends Error {
  constructor(kind: string, id: string) {
    super(`${kind} stable identity ${id} already exists with different immutable facts`)
    this.name = 'StableIdentityConflictError'
  }
}
