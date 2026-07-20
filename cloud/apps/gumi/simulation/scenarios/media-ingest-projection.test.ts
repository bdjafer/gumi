import { readFile } from 'node:fs/promises'

import { describe, expect, it } from 'vitest'

import { ImmutableManifestProjectionSchema } from '#schema'

const publisherFixtureUrl = new URL(
  '../../../media-ingest/fixtures/v1/success/immutable-manifest-projection.json',
  import.meta.url,
)

async function publisherFixture(): Promise<unknown> {
  return JSON.parse(await readFile(publisherFixtureUrl, 'utf8'))
}

describe('publisher-owned immutable manifest projection', () => {
  it('admits the canonical media-ingest fixture at the Astrale trust boundary', async () => {
    const parsed = ImmutableManifestProjectionSchema.parse(await publisherFixture())

    expect(parsed.schemaVersion).toBe('gumi.media-ingest.immutable-manifest-projection.v1')
    expect(parsed.codec).toBe('opus')
    expect(parsed.deviceId).toMatch(/-7[0-9a-f]{3}-/)
    expect(parsed.edgeHostId).toMatch(/-7[0-9a-f]{3}-/)
    expect(parsed.primaryStreamId).toMatch(/-7[0-9a-f]{3}-/)
    expect(parsed.objectContentDigest).toMatch(/^sha256:[0-9a-f]{64}$/)
    expect(parsed.objectByteLength).toBe('241')
    expect(parsed.objectContentType).toBe('audio/ogg; codecs=opus')
  })

  it('rejects unowned fields and identifiers from another UUID generation', async () => {
    const fixture = (await publisherFixture()) as Record<string, unknown>

    expect(() =>
      ImmutableManifestProjectionSchema.parse({ ...fixture, signedObjectUrl: 'https://storage.invalid' }),
    ).toThrow()
    expect(() =>
      ImmutableManifestProjectionSchema.parse({
        ...fixture,
        manifestId: '44444444-4444-4444-8444-444444444444',
      }),
    ).toThrow()
    const { deviceId: _deviceId, ...withoutDevice } = fixture
    const { edgeHostId: _edgeHostId, ...withoutEdgeHost } = fixture
    expect(() => ImmutableManifestProjectionSchema.parse(withoutDevice)).toThrow()
    expect(() => ImmutableManifestProjectionSchema.parse(withoutEdgeHost)).toThrow()
    expect(() =>
      ImmutableManifestProjectionSchema.parse({ ...fixture, objectByteLength: 241 }),
    ).toThrow()
    expect(() =>
      ImmutableManifestProjectionSchema.parse({ ...fixture, objectByteLength: '0' }),
    ).toThrow()
    expect(() =>
      ImmutableManifestProjectionSchema.parse({ ...fixture, objectContentType: 'audio/ogg' }),
    ).toThrow()
  })

  it('accepts only the one-past-u64 exclusive boundary', async () => {
    const fixture = (await publisherFixture()) as Record<string, unknown>

    expect(
      ImmutableManifestProjectionSchema.parse({
        ...fixture,
        sequenceStart: '18446744073709551615',
        sequenceEndExclusive: '18446744073709551616',
      }).sequenceEndExclusive,
    ).toBe('18446744073709551616')
    expect(() =>
      ImmutableManifestProjectionSchema.parse({
        ...fixture,
        sequenceEndExclusive: '18446744073709551617',
      }),
    ).toThrow()
  })
})
