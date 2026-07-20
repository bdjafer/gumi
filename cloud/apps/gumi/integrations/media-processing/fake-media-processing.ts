import {
  DerivedArtifactProjectionSchema,
  MediaProcessingSha256DigestSchema,
  MediaProcessingUnsigned64Schema,
  MediaProcessingUuidV7Schema,
  TranscriptPageSchema,
  type DerivedArtifactProjection,
  type TranscriptPage,
} from './media-processing.schemas.js'
import type {
  MediaProcessing,
  RecordingBoundProcessingReader,
  RecordingProcessingLookupScope,
} from './media-processing.port.js'

export type FakeMediaProcessingRequest =
  | {
      readonly operation: 'resolve-derived-artifact'
      readonly scope: RecordingProcessingLookupScope
      readonly processingJobId: string
      readonly expectedInputContentDigest: string
      readonly expectedOutputContentDigest: string
    }
  | {
      readonly operation: 'read-transcript-page'
      readonly scope: RecordingProcessingLookupScope
      readonly processingJobId: string
      readonly expectedArtifactId: string
      readonly expectedInputContentDigest: string
      readonly expectedOutputContentDigest: string
      readonly startIndex: string
    }

function mismatch(message: string): never {
  throw new Error(`media-processing immutable binding conflict: ${message}`)
}

/**
 * An executable adapter witness. Sources stay `unknown` deliberately: fixture and transport values
 * must cross the same Zod boundary as a production response before the fake exposes typed facts.
 */
export class FakeMediaProcessing implements MediaProcessing {
  readonly issuedScopes: RecordingProcessingLookupScope[] = []
  readonly requests: FakeMediaProcessingRequest[] = []

  constructor(
    private readonly projections: ReadonlyMap<string, unknown>,
    private readonly transcriptPages: ReadonlyMap<string, ReadonlyMap<string, unknown>> = new Map(),
  ) {}

  async issueRecordingBoundReader(
    inputScope: RecordingProcessingLookupScope,
  ): Promise<RecordingBoundProcessingReader> {
    const scope = Object.freeze({ ...inputScope })
    this.issuedScopes.push(scope)

    return {
      resolveDerivedArtifact: async ({ processingJobId, expectedOutputContentDigest }) => {
        MediaProcessingUuidV7Schema.parse(processingJobId)
        MediaProcessingSha256DigestSchema.parse(expectedOutputContentDigest)
        const expectedInputContentDigest = scope.objectContentDigest
        this.requests.push({
          operation: 'resolve-derived-artifact',
          scope,
          processingJobId,
          expectedInputContentDigest,
          expectedOutputContentDigest,
        })

        const source = this.projections.get(processingJobId)
        if (source === undefined) throw new Error(`Unknown processing result ${processingJobId}`)
        const projection = DerivedArtifactProjectionSchema.parse(source)
        this.assertProjectionBinding(
          projection,
          scope,
          processingJobId,
          expectedOutputContentDigest,
        )
        return projection
      },

      readTranscriptPage: async ({
        processingJobId,
        expectedArtifactId,
        expectedOutputContentDigest,
        startIndex,
      }) => {
        MediaProcessingUuidV7Schema.parse(processingJobId)
        MediaProcessingUuidV7Schema.parse(expectedArtifactId)
        MediaProcessingSha256DigestSchema.parse(expectedOutputContentDigest)
        MediaProcessingUnsigned64Schema.parse(startIndex)
        const expectedInputContentDigest = scope.objectContentDigest
        this.requests.push({
          operation: 'read-transcript-page',
          scope,
          processingJobId,
          expectedArtifactId,
          expectedInputContentDigest,
          expectedOutputContentDigest,
          startIndex,
        })

        const source = this.transcriptPages.get(processingJobId)?.get(startIndex)
        if (source === undefined) {
          throw new Error(`Unknown transcript page ${processingJobId}@${startIndex}`)
        }
        const page = TranscriptPageSchema.parse(source)
        this.assertPageBinding(page, {
          processingJobId,
          expectedArtifactId,
          expectedInputContentDigest,
          expectedOutputContentDigest,
          startIndex,
        })
        return page
      },
    }
  }

  private assertProjectionBinding(
    projection: DerivedArtifactProjection,
    scope: RecordingProcessingLookupScope,
    processingJobId: string,
    expectedOutputContentDigest: string,
  ): void {
    if (projection.processingJobId !== processingJobId) mismatch('processing job changed')
    if (projection.input.manifestId !== scope.manifestId) mismatch('manifest ID changed')
    if (projection.input.manifestDigest !== scope.manifestDigest) mismatch('manifest digest changed')
    if (projection.input.contentDigest !== scope.objectContentDigest) mismatch('input content changed')
    if (projection.output.contentDigest !== expectedOutputContentDigest) {
      mismatch('output content changed')
    }
  }

  private assertPageBinding(
    page: TranscriptPage,
    expected: {
      processingJobId: string
      expectedArtifactId: string
      expectedInputContentDigest: string
      expectedOutputContentDigest: string
      startIndex: string
    },
  ): void {
    if (page.processingJobId !== expected.processingJobId) mismatch('page processing job changed')
    if (page.artifactId !== expected.expectedArtifactId) mismatch('page artifact changed')
    if (page.inputContentDigest !== expected.expectedInputContentDigest) {
      mismatch('page input content changed')
    }
    if (page.outputContentDigest !== expected.expectedOutputContentDigest) {
      mismatch('page output content changed')
    }
    if (page.startIndex !== expected.startIndex) mismatch('page start changed')
  }
}
