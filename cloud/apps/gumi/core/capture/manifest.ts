import type { ImmutableManifestProjection } from '#schema'

export function assertManifestRange(manifest: ImmutableManifestProjection): void {
  if (BigInt(manifest.sequenceEndExclusive) <= BigInt(manifest.sequenceStart)) {
    throw new Error('Immutable media manifest sequence range must be non-empty and ordered')
  }
  if (Date.parse(manifest.endedAt) < Date.parse(manifest.startedAt)) {
    throw new Error('Immutable media manifest ends before it starts')
  }
}
