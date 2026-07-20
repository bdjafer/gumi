import { readFile } from 'node:fs/promises'

import { describe, expect, it } from 'vitest'

import { FakeMediaProcessing } from '../../integrations/media-processing/fake-media-processing.js'
import {
  DerivedArtifactProjectionSchema,
  TranscriptPageSchema,
} from '../../integrations/media-processing/media-processing.schemas.js'
import {
  unavailableMediaProcessing,
  type RecordingProcessingLookupScope,
} from '../../integrations/media-processing/media-processing.port.js'

const projectionFixtureUrl = new URL(
  '../../../media-processing/fixtures/v1/success/derived-artifact-projection.json',
  import.meta.url,
)
const pageFixtureUrl = new URL(
  '../../../media-processing/fixtures/v1/success/transcript-page.json',
  import.meta.url,
)

async function readFixture(url: URL): Promise<Record<string, unknown>> {
  return JSON.parse(await readFile(url, 'utf8')) as Record<string, unknown>
}

function clone<T>(value: T): T {
  return structuredClone(value)
}

function populatedPage(emptyFixture: Record<string, unknown>): Record<string, unknown> {
  return {
    ...emptyFixture,
    totalSegmentCount: '5',
    endIndexExclusive: '4',
    nextStartIndex: '4',
    segments: [
      { index: '0', startMs: '0', endMs: '100', text: 'one' },
      { index: '1', startMs: '100', endMs: '200', speakerLabel: 'speaker-a', text: 'two' },
      { index: '2', startMs: '250', endMs: '300', text: 'three' },
      { index: '3', startMs: '300', endMs: '400', text: 'four' },
    ],
  }
}

describe('media-processing publisher trust boundary', () => {
  it('admits both canonical publisher fixtures without importing publisher internals', async () => {
    const projection = DerivedArtifactProjectionSchema.parse(await readFixture(projectionFixtureUrl))
    const page = TranscriptPageSchema.parse(await readFixture(pageFixtureUrl))

    expect(projection.schemaVersion).toBe(
      'gumi.media-processing.derived-artifact-projection.v1',
    )
    expect(projection.output.contentDigest).toMatch(/^sha256:[0-9a-f]{64}$/)
    expect(projection.input.contentDigest).not.toBe(projection.output.contentDigest)
    expect(page.schemaVersion).toBe('gumi.media-processing.transcript-page.v1')
    expect(page.segments).toEqual([])
  })

  it('rejects malformed projection structure, primitives, and impossible aggregate facts', async () => {
    const fixture = await readFixture(projectionFixtureUrl)
    const invalid = (mutate: (candidate: Record<string, any>) => void) => {
      const candidate = clone(fixture) as Record<string, any>
      mutate(candidate)
      expect(() => DerivedArtifactProjectionSchema.parse(candidate)).toThrow()
    }

    invalid((value) => { value.signedArtifactUrl = 'https://storage.invalid/secret' })
    invalid((value) => { value.processingJobId = '44444444-4444-4444-8444-444444444444' })
    invalid((value) => { value.output.contentDigest = `sha256:${'A'.repeat(64)}` })
    invalid((value) => { value.output.byteLength = '67108865' })
    invalid((value) => { value.provenance.generation = '18446744073709551616' })
    invalid((value) => { value.provenance.timing.providerStartedAt = '2026-02-30T20:00:01Z' })
    invalid((value) => {
      value.provenance.timing.providerCompletedAt = '2026-07-19T20:00:00Z'
      value.provenance.timing.providerStartedAt = '2026-07-19T20:00:01Z'
    })
    invalid((value) => {
      value.provenance.segments.count = '1'
      value.provenance.segments.timedCount = '2'
    })
    invalid((value) => { value.provenance.segments.count = '100001' })
    invalid((value) => {
      value.provenance.segments.firstStartMs = '0'
      value.provenance.segments.lastEndMs = '1'
    })
    invalid((value) => {
      value.provenance.segments.count = '1'
      value.provenance.segments.timedCount = '1'
    })
    invalid((value) => {
      value.provenance.segments.count = '1'
      value.provenance.segments.timedCount = '1'
      value.provenance.segments.firstStartMs = '10'
      value.provenance.segments.lastEndMs = '60001'
    })
  })

  it('enforces exact half-open pages, continuation, contiguous indices, and segment bounds', async () => {
    const fixture = populatedPage(await readFixture(pageFixtureUrl))
    expect(TranscriptPageSchema.parse(fixture).nextStartIndex).toBe('4')

    const unicodeBoundary = clone(fixture) as Record<string, any>
    unicodeBoundary.segments[0].speakerLabel = '🟣'.repeat(256)
    unicodeBoundary.segments[0].text = '🟣'.repeat(32_768)
    expect(TranscriptPageSchema.parse(unicodeBoundary).segments[0]?.speakerLabel).toHaveLength(512)

    const invalid = (mutate: (candidate: Record<string, any>) => void) => {
      const candidate = clone(fixture) as Record<string, any>
      mutate(candidate)
      expect(() => TranscriptPageSchema.parse(candidate)).toThrow()
    }

    invalid((value) => { value.providerPayload = { token: 'secret' } })
    invalid((value) => { value.inputContentDigest = `sha256:${'a'.repeat(63)}` })
    invalid((value) => { value.totalSegmentCount = '18446744073709551616' })
    invalid((value) => { value.totalSegmentCount = '100001' })
    invalid((value) => { value.segments.pop() })
    invalid((value) => { value.endIndexExclusive = '3' })
    invalid((value) => { delete value.nextStartIndex })
    invalid((value) => { value.nextStartIndex = '3' })
    invalid((value) => { value.segments[2].index = '9' })
    invalid((value) => { value.segments[2].startMs = '199' })
    invalid((value) => { value.segments[3].endMs = '60001' })
    invalid((value) => { value.segments[0].text = '🟣'.repeat(32_769) })
    invalid((value) => { value.segments[1].speakerLabel = '' })
    invalid((value) => { value.segments[1].speakerLabel = '🟣'.repeat(257) })

    const finalPage = clone(fixture) as Record<string, any>
    finalPage.totalSegmentCount = '4'
    delete finalPage.nextStartIndex
    expect(TranscriptPageSchema.parse(finalPage).endIndexExclusive).toBe('4')
    finalPage.nextStartIndex = '4'
    expect(() => TranscriptPageSchema.parse(finalPage)).toThrow()
  })
})

describe('recording-bound media-processing capability', () => {
  it('captures caller and recording facts, then binds both result and page digests', async () => {
    const projectionFixture = await readFixture(projectionFixtureUrl)
    const pageFixture = await readFixture(pageFixtureUrl)
    const projection = DerivedArtifactProjectionSchema.parse(projectionFixture)
    const page = TranscriptPageSchema.parse(pageFixture)
    const mutableScope: RecordingProcessingLookupScope = {
      actualCaller: '@caller-one',
      recordingPath: '/recordings/one',
      manifestId: projection.input.manifestId,
      manifestDigest: projection.input.manifestDigest,
      objectContentDigest: projection.input.contentDigest,
    }
    const fake = new FakeMediaProcessing(
      new Map([[projection.processingJobId, projectionFixture]]),
      new Map([[projection.processingJobId, new Map([[page.startIndex, pageFixture]])]]),
    )
    const reader = await fake.issueRecordingBoundReader(mutableScope)
    ;(mutableScope as { actualCaller: string }).actualCaller = '@attacker'

    const result = await reader.resolveDerivedArtifact({
      processingJobId: projection.processingJobId,
      expectedOutputContentDigest: projection.output.contentDigest,
    })
    const returnedPage = await reader.readTranscriptPage({
      processingJobId: projection.processingJobId,
      expectedArtifactId: projection.artifactId,
      expectedOutputContentDigest: projection.output.contentDigest,
      startIndex: '0',
    })

    expect(result.artifactId).toBe(projection.artifactId)
    expect(returnedPage.artifactId).toBe(projection.artifactId)
    expect(fake.issuedScopes[0]?.actualCaller).toBe('@caller-one')
    expect(fake.requests).toEqual([
      {
        operation: 'resolve-derived-artifact',
        scope: fake.issuedScopes[0],
        processingJobId: projection.processingJobId,
        expectedInputContentDigest: projection.input.contentDigest,
        expectedOutputContentDigest: projection.output.contentDigest,
      },
      {
        operation: 'read-transcript-page',
        scope: fake.issuedScopes[0],
        processingJobId: projection.processingJobId,
        expectedArtifactId: projection.artifactId,
        expectedInputContentDigest: projection.input.contentDigest,
        expectedOutputContentDigest: projection.output.contentDigest,
        startIndex: '0',
      },
    ])
  })

  it('fails closed on every foreign publisher binding', async () => {
    const fixture = await readFixture(projectionFixtureUrl)
    const canonical = DerivedArtifactProjectionSchema.parse(fixture)
    const pageFixture = await readFixture(pageFixtureUrl)
    const scope: RecordingProcessingLookupScope = {
      actualCaller: '@caller-one',
      recordingPath: '/recordings/one',
      manifestId: canonical.input.manifestId,
      manifestDigest: canonical.input.manifestDigest,
      objectContentDigest: canonical.input.contentDigest,
    }

    const expectProjectionConflict = async (mutate: (value: Record<string, any>) => void) => {
      const foreign = clone(fixture) as Record<string, any>
      mutate(foreign)
      const fake = new FakeMediaProcessing(new Map([[canonical.processingJobId, foreign]]))
      const reader = await fake.issueRecordingBoundReader(scope)
      await expect(
        reader.resolveDerivedArtifact({
          processingJobId: canonical.processingJobId,
          expectedOutputContentDigest: canonical.output.contentDigest,
        }),
      ).rejects.toThrow()
    }
    await expectProjectionConflict((value) => { value.input.manifestId = value.artifactId })
    await expectProjectionConflict((value) => { value.input.manifestDigest = `sha256:${'4'.repeat(64)}` })
    await expectProjectionConflict((value) => { value.input.contentDigest = `sha256:${'5'.repeat(64)}` })
    await expectProjectionConflict((value) => { value.output.contentDigest = `sha256:${'6'.repeat(64)}` })

    const expectPageConflict = async (mutate: (value: Record<string, any>) => void) => {
      const foreign = clone(pageFixture) as Record<string, any>
      mutate(foreign)
      const fake = new FakeMediaProcessing(
        new Map([[canonical.processingJobId, fixture]]),
        new Map([[canonical.processingJobId, new Map([['0', foreign]])]]),
      )
      const reader = await fake.issueRecordingBoundReader(scope)
      await expect(
        reader.readTranscriptPage({
          processingJobId: canonical.processingJobId,
          expectedArtifactId: canonical.artifactId,
          expectedOutputContentDigest: canonical.output.contentDigest,
          startIndex: '0',
        }),
      ).rejects.toThrow()
    }
    await expectPageConflict((value) => { value.processingJobId = value.artifactId })
    await expectPageConflict((value) => { value.artifactId = value.processingJobId })
    await expectPageConflict((value) => { value.inputContentDigest = `sha256:${'7'.repeat(64)}` })
    await expectPageConflict((value) => { value.outputContentDigest = `sha256:${'8'.repeat(64)}` })
    const wrongStartFake = new FakeMediaProcessing(
      new Map([[canonical.processingJobId, fixture]]),
      new Map([[canonical.processingJobId, new Map([['1', pageFixture]])]]),
    )
    const wrongStartReader = await wrongStartFake.issueRecordingBoundReader(scope)
    await expect(
      wrongStartReader.readTranscriptPage({
        processingJobId: canonical.processingJobId,
        expectedArtifactId: canonical.artifactId,
        expectedOutputContentDigest: canonical.output.contentDigest,
        startIndex: '1',
      }),
    ).rejects.toThrow('page start changed')
  })

  it('has no permissive production fallback', async () => {
    await expect(
      unavailableMediaProcessing.issueRecordingBoundReader({
        actualCaller: '@caller-one',
        recordingPath: '/recordings/one',
        manifestId: '0190c6f0-7b21-7a40-8b11-000000000001',
        manifestDigest: `sha256:${'1'.repeat(64)}`,
        objectContentDigest: `sha256:${'2'.repeat(64)}`,
      }),
    ).rejects.toThrow('not configured')
  })
})
