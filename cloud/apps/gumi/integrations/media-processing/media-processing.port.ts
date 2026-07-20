import type { DerivedArtifactProjection, TranscriptPage } from './media-processing.schemas.js'

/**
 * Facts a production adapter must bind into one short-lived media-processing control credential.
 * `actualCaller` is derived from Astrale authentication; none of these values may come from an
 * untrusted provider response.
 */
export interface RecordingProcessingLookupScope {
  readonly actualCaller: string
  readonly recordingPath: string
  readonly manifestId: string
  readonly manifestDigest: string
  readonly objectContentDigest: string
}

/**
 * A least-authority reader for one exact Recording. Input content identity is captured at issuance,
 * so lookup calls cannot substitute another recording's digest or manifest binding.
 */
export interface RecordingBoundProcessingReader {
  resolveDerivedArtifact(input: {
    readonly processingJobId: string
    readonly expectedOutputContentDigest: string
  }): Promise<DerivedArtifactProjection>

  readTranscriptPage(input: {
    readonly processingJobId: string
    readonly expectedArtifactId: string
    readonly expectedOutputContentDigest: string
    readonly startIndex: string
  }): Promise<TranscriptPage>
}

export interface MediaProcessing {
  issueRecordingBoundReader(
    scope: RecordingProcessingLookupScope,
  ): Promise<RecordingBoundProcessingReader>
}

/** Production-safe placeholder: missing adapter composition always fails closed. */
export const unavailableMediaProcessing: MediaProcessing = {
  async issueRecordingBoundReader() {
    throw new Error('media-processing integration is not configured')
  },
}
