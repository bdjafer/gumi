import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import { validateOggChunk } from '../src/core/ogg.mjs'

const APP = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const FIXTURE = join(APP, 'fixtures', 'v1', 'conformance', 'edge-muxer-single-packet.hex')

const CONTEXT = {
  isFirst: true,
  expectedAudioPackets: 1n,
  codec: {
    name: 'opus',
    configurationId: 'opus-16000-mono-20ms-v1',
    sampleRateHz: 16_000,
    channelCount: 1,
    frameDurationUs: 20_000,
  },
  layout: {
    profile: 'gumi.ogg-opus.single-packet-page.v1',
    serialNumber: '16909060',
    preSkip48kSamples: '312',
  },
  audioRange: { first: '10', last: '10' },
  firstAudioSequence: '10',
}

function fixtureBytes() {
  const hex = readFileSync(FIXTURE, 'utf8').trim()
  assert.match(hex, /^(?:[0-9a-f]{2})+$/, 'edge fixture must be canonical lowercase hex')
  return Buffer.from(hex, 'hex')
}

test('the independent ingest parser accepts the exact edge-muxer golden stream', () => {
  const parsed = validateOggChunk(fixtureBytes(), CONTEXT)

  assert.equal(parsed.pages.length, 3)
  assert.equal(parsed.packets.length, 3)
  assert.equal(parsed.containerFacts.profile, CONTEXT.layout.profile)
  assert.equal(parsed.containerFacts.serialNumber, CONTEXT.layout.serialNumber)
  assert.equal(parsed.containerFacts.terminalSequence, '10')
  assert.equal(parsed.pages.at(-1).granulePosition, 960n)
})

test('the same boundary rejects a byte-corrupted edge fixture before durable acceptance', () => {
  const corrupted = fixtureBytes()
  corrupted[corrupted.length - 1] ^= 0x01

  assert.throws(
    () => validateOggChunk(corrupted, CONTEXT),
    (error) => error?.code === 'INVALID_REQUEST' && /CRC mismatch/.test(error.message),
  )
})
