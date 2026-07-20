/**
 * The core owns state transitions, while adapters own persistence, media-manifest
 * lookup, immutable artifact bytes, identity allocation, and time. No adapter may
 * return provider credentials or transcript content through these control-plane values.
 */
export function assertPorts({ storage, clock, ids, manifestReader, artifacts }) {
  for (const [name, value, methods] of [
    ['storage', storage, ['transaction', 'readJob', 'readRequest']],
    ['clock', clock, ['now']],
    ['ids', ids, ['newOpaqueId']],
    ['manifestReader', manifestReader, ['resolveImmutableManifest']],
    ['artifacts', artifacts, ['validateStagedArtifact', 'commitDerivedArtifact', 'readTranscriptPage']],
  ]) {
    if (!value) throw new TypeError(`Missing ${name} port`)
    for (const method of methods) {
      if (typeof value[method] !== 'function') throw new TypeError(`${name}.${method} must be a function`)
    }
  }
}
