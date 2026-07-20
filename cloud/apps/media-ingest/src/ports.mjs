/**
 * The executable core deliberately depends on behavior, not a database or cloud SDK.
 * A production adapter must provide these three ports with the durability semantics
 * documented here. `assertPorts` keeps configuration failures at composition time.
 */
export function assertPorts({ storage, clock, tokens }) {
  for (const [name, value, methods] of [
    ['storage', storage, ['transaction', 'readSession', 'readChunkBody', 'composeImmutableObject', 'readManifestById']],
    ['clock', clock, ['now']],
    ['tokens', tokens, ['newOpaqueId', 'issueCredential']],
  ]) {
    if (!value) throw new TypeError(`Missing ${name} port`)
    for (const method of methods) {
      if (typeof value[method] !== 'function') throw new TypeError(`${name}.${method} must be a function`)
    }
  }
}
