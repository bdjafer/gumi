import type { ImmutableManifestProjection } from '#schema'

/**
 * Facts that a production adapter must bind into its short-lived media-ingest authorization. These
 * values are request scope, not ambient authority and not a substitute for provider verification.
 */
export interface CaptureManifestLookupScope {
  readonly actualCaller: string
  readonly capturePath: string
  readonly captureSessionId: string
  readonly devicePath: string
  readonly deviceId: string
  readonly edgeHostId: string
}

/**
 * A least-authority reader issued for exactly one capture binding. The captured scope is absent
 * from lookup calls so callers cannot substitute another capture, device, edge host, or principal.
 */
export interface CaptureBoundManifestReader {
  getImmutableManifest(input: {
    readonly manifestId: string
    readonly expectedDigest: string
  }): Promise<ImmutableManifestProjection>
}

export interface MediaIngest {
  /** Issue a fresh reader whose adapter authorization is bound to this exact call scope. */
  issueCaptureBoundManifestReader(
    scope: CaptureManifestLookupScope,
  ): Promise<CaptureBoundManifestReader>
}

/** Production-safe placeholder: missing composition fails instead of silently using test data. */
export const unavailableMediaIngest: MediaIngest = {
  async issueCaptureBoundManifestReader() {
    throw new Error('media-ingest integration is not configured')
  },
}
