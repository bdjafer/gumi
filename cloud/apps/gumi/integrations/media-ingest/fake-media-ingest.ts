import type { ImmutableManifestProjection } from '#schema'

import type {
  CaptureBoundManifestReader,
  CaptureManifestLookupScope,
  MediaIngest,
} from './media-ingest.port.js'

export class FakeMediaIngest implements MediaIngest {
  readonly issuedScopes: CaptureManifestLookupScope[] = []
  readonly requests: Array<{
    scope: CaptureManifestLookupScope
    manifestId: string
    expectedDigest: string
  }> = []

  constructor(private readonly manifests: ReadonlyMap<string, ImmutableManifestProjection>) {}

  async issueCaptureBoundManifestReader(
    inputScope: CaptureManifestLookupScope,
  ): Promise<CaptureBoundManifestReader> {
    const scope = Object.freeze({ ...inputScope })
    this.issuedScopes.push(scope)
    return {
      getImmutableManifest: async ({ manifestId, expectedDigest }) => {
        this.requests.push({ scope, manifestId, expectedDigest })
        const manifest = this.manifests.get(manifestId)
        if (!manifest) throw new Error(`Unknown immutable manifest ${manifestId}`)
        if (manifest.manifestDigest !== expectedDigest) {
          throw new Error(`Immutable manifest digest conflict for ${manifestId}`)
        }
        return manifest
      },
    }
  }
}
