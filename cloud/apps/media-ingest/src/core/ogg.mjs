import { TextDecoder } from 'node:util'

import { fail } from './error.mjs'

const CRC_TABLE = Array.from({ length: 256 }, (_, index) => {
  let value = (index << 24) >>> 0
  for (let bit = 0; bit < 8; bit += 1) {
    value = ((value & 0x80000000) !== 0 ? (value << 1) ^ 0x04c11db7 : value << 1) >>> 0
  }
  return value
})

function oggCrc(page) {
  let crc = 0
  for (let index = 0; index < page.length; index += 1) {
    const byte = index >= 22 && index < 26 ? 0 : page[index]
    crc = (((crc << 8) >>> 0) ^ CRC_TABLE[((crc >>> 24) ^ byte) & 0xff]) >>> 0
  }
  return crc
}

function malformed(message) {
  fail('INVALID_REQUEST', 400, `Invalid ogg-opus-page-fragment-v1 body: ${message}`)
}

const NO_GRANULE_POSITION = 0xffff_ffff_ffff_ffffn
const OPUS_HEAD_MAGIC = Buffer.from('OpusHead')
const OPUS_TAGS_MAGIC = Buffer.from('OpusTags')
const UTF8_DECODER = new TextDecoder('utf-8', { fatal: true })
const M1_SINGLE_PACKET_PAGE_PROFILE = 'gumi.ogg-opus.single-packet-page.v1'
const MAX_PLAYABLE_SAMPLES_48K = 86_400n * 48_000n

function assertMagic(packet, magic, label) {
  if (!Buffer.isBuffer(packet) || !packet.subarray(0, magic.length).equals(magic)) malformed(`invalid ${label}`)
}

/** The M1 profile is version-1, family-0 mono/stereo with no deferred output gain. */
function assertOpusHead(packet, codec) {
  assertMagic(packet, OPUS_HEAD_MAGIC, 'OpusHead')
  if (packet.length !== 19) malformed('the M1 OpusHead must be exactly 19 octets')
  if (packet[8] !== 1) malformed('the M1 OpusHead version must be 1')
  if (packet[9] !== codec.channelCount) malformed('Opus channel count differs from the session codec')
  const preSkip = BigInt(packet.readUInt16LE(10))
  if (packet.readUInt32LE(12) !== codec.sampleRateHz) malformed('Opus input sample rate differs from the session codec')
  if (packet.readInt16LE(16) !== 0) malformed('the M1 OpusHead output gain must be zero')
  if (packet[18] !== 0) malformed('the M1 OpusHead channel mapping family must be zero')
  return { preSkip }
}

function decodeUtf8(bytes, label) {
  try {
    return UTF8_DECODER.decode(bytes)
  } catch {
    malformed(`${label} is not valid UTF-8`)
  }
}

function assertOpusTags(packet) {
  assertMagic(packet, OPUS_TAGS_MAGIC, 'OpusTags')
  if (packet.length < 16) malformed('OpusTags omits mandatory length fields')
  let offset = 8
  const takeLength = (label) => {
    if (offset + 4 > packet.length) malformed(`OpusTags omits ${label} length`)
    const length = packet.readUInt32LE(offset)
    offset += 4
    if (length > packet.length - offset) malformed(`OpusTags ${label} exceeds the packet boundary`)
    return length
  }

  const vendorLength = takeLength('vendor string')
  const vendor = decodeUtf8(packet.subarray(offset, offset + vendorLength), 'OpusTags vendor string')
  offset += vendorLength
  if (offset + 4 > packet.length) malformed('OpusTags omits user comment list length')
  const commentCount = packet.readUInt32LE(offset)
  offset += 4
  if (commentCount > Math.floor((packet.length - offset) / 4)) {
    malformed('OpusTags comment count cannot fit in the packet')
  }
  const comments = []
  for (let index = 0; index < commentCount; index += 1) {
    const commentLength = takeLength(`comment ${index}`)
    comments.push(decodeUtf8(packet.subarray(offset, offset + commentLength), `OpusTags comment ${index}`))
    offset += commentLength
  }
  // RFC 7845 permits trailing padding or extension bytes after the declared comment list.
  return { vendor, comments, trailingBytes: packet.length - offset }
}

function assertPageIdentity(parsed) {
  const first = parsed.pages[0]
  for (const [index, page] of parsed.pages.entries()) {
    if (page.serial !== first.serial) malformed('an HTTP chunk changes Ogg logical-stream serial')
    if (index > 0 && page.sequence !== ((parsed.pages[index - 1].sequence + 1) >>> 0)) {
      malformed('an HTTP chunk has a non-contiguous Ogg page sequence')
    }
  }
}

function assertInitialHeaderLayout(parsed, codec) {
  const firstPage = parsed.pages[0]
  if (firstPage.sequence !== 0) malformed('logical stream does not begin at page sequence zero')
  if (firstPage.completedPackets !== 1 || !firstPage.endsAtPacketBoundary) {
    malformed('OpusHead must be the only packet data on the first Ogg page')
  }
  if (firstPage.granulePosition !== 0n) malformed('OpusHead page has a non-zero granule')
  if ((firstPage.headerType & 4) !== 0) malformed('OpusHead page is marked end-of-stream')
  const head = assertOpusHead(parsed.packets[0], codec)
  const tags = assertOpusTags(parsed.packets[1])

  let completedPackets = firstPage.completedPackets
  let commentCompletionPage = -1
  for (let index = 1; index < parsed.pages.length; index += 1) {
    const page = parsed.pages[index]
    if (completedPackets < 2 && page.completedPackets === 0 && page.granulePosition !== NO_GRANULE_POSITION) {
      malformed('a continued OpusTags page must use the no-granule sentinel')
    }
    completedPackets += page.completedPackets
    if (completedPackets >= 2) {
      commentCompletionPage = index
      if (completedPackets !== 2 || page.completedPackets !== 1 || !page.endsAtPacketBoundary) {
        malformed('OpusTags must finish alone at the end of its Ogg page')
      }
      if (page.granulePosition !== 0n) malformed('completed OpusTags page has a non-zero granule')
      break
    }
  }
  if (commentCompletionPage === -1) malformed('logical stream has no complete OpusTags packet')
  return { ...head, tags, commentCompletionPage }
}

function opusFrameDurationUs(packet) {
  if (!Buffer.isBuffer(packet) || packet.length < 1) malformed('an Opus audio packet is empty')
  if ((packet[0] & 0x03) !== 0) {
    malformed('the M1 fixed-frame profile requires exactly one Opus frame per packet')
  }
  if (packet.length > 1276) malformed('an Opus frame exceeds the RFC 6716 size bound')
  const configuration = packet[0] >>> 3
  if (configuration < 12) return [10_000, 20_000, 40_000, 60_000][configuration % 4]
  if (configuration < 16) return [10_000, 20_000][configuration % 2]
  return [2_500, 5_000, 10_000, 20_000][configuration % 4]
}

function assertFixedFramePacket(packet, codec) {
  const durationUs = opusFrameDurationUs(packet)
  if (durationUs !== codec.frameDurationUs) {
    malformed('Opus TOC duration differs from the scoped codec configuration')
  }
  return BigInt(durationUs) * 48_000n / 1_000_000n
}

function assertDeterministicM1Layout(
  parsed,
  { isFirst, audioRange, firstAudioSequence, layout, codec, headerFacts },
) {
  if (layout.profile !== M1_SINGLE_PACKET_PAGE_PROFILE) malformed('unsupported deterministic Ogg layout profile')
  const rangeFirst = BigInt(audioRange.first)
  const rangeLast = BigInt(audioRange.last)
  const first = BigInt(firstAudioSequence)
  if (rangeFirst < first || rangeLast < rangeFirst) malformed('audio range contradicts the deterministic layout origin')
  const audioPageCount = rangeLast - rangeFirst + 1n
  const firstAudioPageSequence = 2n + (rangeFirst - first)
  const lastAudioPageSequence = 2n + (rangeLast - first)
  if (lastAudioPageSequence > 0xffff_ffffn) malformed('audio range cannot map to Ogg page sequences')

  const headerPageCount = isFirst ? 2n : 0n
  if (BigInt(parsed.pages.length) !== headerPageCount + audioPageCount) {
    malformed('deterministic audio page count does not match the declared sequence range')
  }
  if (parsed.packets.length !== parsed.pages.length) {
    malformed('the deterministic profile requires exactly one packet per Ogg page')
  }

  const expectedSerial = Number(BigInt(layout.serialNumber))
  for (const page of parsed.pages) {
    if (page.serial !== expectedSerial) malformed('Ogg serial differs from the scoped deterministic layout')
  }

  if (isFirst) {
    if (headerFacts.commentCompletionPage !== 1) {
      malformed('the deterministic profile requires OpusTags to occupy page sequence one')
    }
    if (headerFacts.preSkip !== BigInt(layout.preSkip48kSamples)) {
      malformed('Opus pre-skip differs from the scoped deterministic layout')
    }
    if (
      headerFacts.tags.vendor !== 'gumi' ||
      headerFacts.tags.comments.length !== 0 ||
      headerFacts.tags.trailingBytes !== 0
    ) {
      malformed('the deterministic OpusTags packet must be exactly vendor gumi with zero comments')
    }
    const [headPage, tagsPage] = parsed.pages
    if (headPage.headerType !== 2 || headPage.sequence !== 0) {
      malformed('the deterministic OpusHead page must be page zero with only beginning-of-stream set')
    }
    if (tagsPage.headerType !== 0 || tagsPage.sequence !== 1) {
      malformed('the deterministic OpusTags page must be unflagged page sequence one')
    }
  }

  const frameSamples = BigInt(codec.frameDurationUs) * 48_000n / 1_000_000n
  const preSkip = BigInt(layout.preSkip48kSamples)
  const audioPages = parsed.pages.slice(isFirst ? 2 : 0)
  for (const [index, audioPage] of audioPages.entries()) {
    const sequence = rangeFirst + BigInt(index)
    const audioOffset = sequence - first
    const expectedPageSequence = 2n + audioOffset
    if (audioPage.sequence !== Number(expectedPageSequence)) {
      malformed('audio page sequence does not match its scoped audio sequence')
    }
    if (audioPage.completedPackets !== 1 || !audioPage.endsAtPacketBoundary) {
      malformed('each deterministic audio page must contain exactly one complete Opus packet')
    }
    const endsLogicalStream = (audioPage.headerType & 4) !== 0
    if (audioPage.headerType !== (endsLogicalStream ? 4 : 0)) {
      malformed('a deterministic audio page has unexpected Ogg flags')
    }

    const untrimmedGranule = (audioOffset + 1n) * frameSamples
    if (endsLogicalStream) {
      if (index !== audioPages.length - 1) malformed('end-of-stream appears before the final audio page')
      if (audioPage.granulePosition < preSkip) malformed('terminal granule precedes scoped Opus pre-skip')
      if (audioPage.granulePosition > untrimmedGranule) malformed('terminal granule exceeds deterministic samples')
      if (untrimmedGranule - audioPage.granulePosition > frameSamples) {
        malformed('terminal granule trims more than the deterministic final Opus packet')
      }
      if (audioPage.granulePosition - preSkip > MAX_PLAYABLE_SAMPLES_48K) {
        malformed('terminal granule exceeds the v1 playable-duration bound')
      }
    } else {
      if (audioPage.granulePosition !== untrimmedGranule) {
        malformed('non-terminal granule does not match the deterministic audio sequence')
      }
      if (untrimmedGranule > preSkip + MAX_PLAYABLE_SAMPLES_48K) {
        malformed('non-terminal granule leaves no bounded terminal continuation')
      }
    }
  }

  const endsLogicalStream = (audioPages.at(-1).headerType & 4) !== 0

  return {
    profile: layout.profile,
    serialNumber: layout.serialNumber,
    firstAudioPageSequence: String(firstAudioPageSequence),
    lastAudioPageSequence: String(lastAudioPageSequence),
    endsLogicalStream,
    terminalSequence: endsLogicalStream ? String(rangeLast) : null,
  }
}

export function parseOgg(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length === 0) malformed('body must be a non-empty byte buffer')
  const pages = []
  const packets = []
  let packetParts = []
  let offset = 0
  let priorEndedAtBoundary = true

  while (offset < bytes.length) {
    if (offset + 27 > bytes.length) malformed('truncated page header')
    if (bytes.subarray(offset, offset + 4).toString('ascii') !== 'OggS') malformed('missing Ogg capture pattern')
    if (bytes[offset + 4] !== 0) malformed('unsupported Ogg version')
    const headerType = bytes[offset + 5]
    if ((headerType & ~0x07) !== 0) malformed('page uses reserved header-type flags')
    const segmentCount = bytes[offset + 26]
    const segmentTableEnd = offset + 27 + segmentCount
    if (segmentTableEnd > bytes.length) malformed('truncated segment table')
    let payloadLength = 0
    for (let index = offset + 27; index < segmentTableEnd; index += 1) payloadLength += bytes[index]
    const pageEnd = segmentTableEnd + payloadLength
    if (pageEnd > bytes.length) malformed('truncated page payload')
    const page = bytes.subarray(offset, pageEnd)
    if (oggCrc(page) !== page.readUInt32LE(22)) malformed('page CRC mismatch')

    const continued = (headerType & 1) !== 0
    if (pages.length === 0 && continued) malformed('an HTTP chunk starts with a continued packet')
    if (pages.length > 0 && continued === priorEndedAtBoundary) malformed('page continuation flag contradicts lacing')

    let payloadOffset = segmentTableEnd
    let completedPackets = 0
    for (let index = offset + 27; index < segmentTableEnd; index += 1) {
      const length = bytes[index]
      packetParts.push(bytes.subarray(payloadOffset, payloadOffset + length))
      payloadOffset += length
      if (length < 255) {
        packets.push(Buffer.concat(packetParts))
        packetParts = []
        completedPackets += 1
      }
    }
    priorEndedAtBoundary = packetParts.length === 0
    pages.push({
      headerType,
      granulePosition: page.readBigUInt64LE(6),
      serial: page.readUInt32LE(14),
      sequence: page.readUInt32LE(18),
      completedPackets,
      endsAtPacketBoundary: priorEndedAtBoundary,
    })
    offset = pageEnd
  }
  if (!priorEndedAtBoundary) malformed('HTTP chunk ends inside an Ogg packet')
  return { pages, packets }
}

export function validateOggChunk(
  bytes,
  { isFirst, expectedAudioPackets, codec, layout, audioRange, firstAudioSequence },
) {
  const parsed = parseOgg(bytes)
  const firstPage = parsed.pages[0]
  assertPageIdentity(parsed)
  let headerFacts
  if (isFirst) {
    if ((firstPage.headerType & 2) === 0) malformed('first sequence range has no beginning-of-stream page')
    headerFacts = assertInitialHeaderLayout(parsed, codec)
  } else if ((firstPage.headerType & 2) !== 0) {
    malformed('a non-initial sequence range repeats the beginning-of-stream page')
  }
  for (const [index, page] of parsed.pages.entries()) {
    if (index > 0 && (page.headerType & 2) !== 0) malformed('beginning-of-stream appears after the first page')
    if (index < parsed.pages.length - 1 && (page.headerType & 4) !== 0) {
      malformed('end-of-stream appears before the final page in an HTTP chunk')
    }
  }
  const headerPackets = isFirst ? 2 : 0
  const audioPackets = BigInt(parsed.packets.length - headerPackets)
  if (audioPackets !== expectedAudioPackets) malformed('audio packet count does not match the declared sequence range')
  for (const packet of parsed.packets.slice(headerPackets)) assertFixedFramePacket(packet, codec)
  const containerFacts = layout
    ? assertDeterministicM1Layout(parsed, {
        isFirst,
        audioRange,
        firstAudioSequence,
        layout,
        codec,
        headerFacts,
      })
    : {
        profile: null,
        serialNumber: String(firstPage.serial),
        firstAudioPageSequence: String(firstPage.sequence),
        lastAudioPageSequence: String(parsed.pages.at(-1).sequence),
        endsLogicalStream: (parsed.pages.at(-1).headerType & 4) !== 0,
        terminalSequence: null,
      }
  if (isFirst) {
    // The initial chunk carries enough state to validate its granules immediately. In particular,
    // a one-chunk recording must not receive a durable ACK and only discover invalid EOS trim later.
    const inspector = createOpusStreamInspector(codec)
    inspector.accept(parsed)
    if ((parsed.pages.at(-1).headerType & 4) !== 0) inspector.finish(expectedAudioPackets)
  }
  return { ...parsed, containerFacts }
}

/** Incremental validator so finalization never needs to concatenate the complete recording in RAM. */
export function createOpusStreamInspector(codec) {
  let serial
  let nextPageSequence = 0
  let pageCount = 0
  let packetCount = 0n
  let preSkip
  let decodedSamples = 0n
  let granuleBase
  let previousGranule
  let terminalGranule
  let ended = false

  return {
    accept(parsed) {
      let packetOffset = 0
      for (const page of parsed.pages) {
        if (ended) malformed('a page appears after end-of-stream')
        if (pageCount === 0) {
          if ((page.headerType & 2) === 0) malformed('assembled object has no beginning-of-stream page')
          if (page.sequence !== 0) malformed('logical stream does not begin at page sequence zero')
          serial = page.serial
        } else {
          if ((page.headerType & 2) !== 0) malformed('assembled object repeats beginning-of-stream')
          if (page.serial !== serial) malformed('assembled object changes logical-stream serial')
          if (page.sequence !== nextPageSequence) malformed('assembled object has a non-contiguous page sequence')
        }

        const completed = parsed.packets.slice(packetOffset, packetOffset + page.completedPackets)
        packetOffset += page.completedPackets
        let audioSamplesOnPage = 0n
        let finalPacketSamples = 0n
        let completesHeader = false
        for (const packet of completed) {
          if (packetCount === 0n) {
            preSkip = assertOpusHead(packet, codec).preSkip
            completesHeader = true
          } else if (packetCount === 1n) {
            assertOpusTags(packet)
            completesHeader = true
          } else {
            finalPacketSamples = assertFixedFramePacket(packet, codec)
            audioSamplesOnPage += finalPacketSamples
          }
          packetCount += 1n
        }
        if (completesHeader && audioSamplesOnPage > 0n) {
          malformed('the M1 profile does not mix header and audio packets on one page')
        }

        if (audioSamplesOnPage === 0n) {
          if (completesHeader && page.granulePosition !== 0n) malformed('completed Opus header page has a non-zero granule')
          if (packetCount > 2n && page.completedPackets === 0 && page.granulePosition !== NO_GRANULE_POSITION) {
            malformed('an audio page without a completed packet must use the no-granule sentinel')
          }
        } else {
          decodedSamples += audioSamplesOnPage
          if (page.granulePosition === NO_GRANULE_POSITION) malformed('an audio page with completed packets has no granule')
          const isTerminal = (page.headerType & 4) !== 0
          if (granuleBase === undefined) {
            if (isTerminal) {
              if (page.granulePosition < preSkip) malformed('terminal granule precedes Opus pre-skip')
              // RFC 7845 section 4.5: a first audio page that is also EOS works forward
              // from zero when trimmed, or backwards from a positive initial granule otherwise.
              granuleBase = page.granulePosition < decodedSamples ? 0n : page.granulePosition - decodedSamples
            } else {
              if (page.granulePosition < decodedSamples) {
                malformed('initial audio granule precedes decoded samples')
              }
              granuleBase = page.granulePosition - decodedSamples
            }
          }
          const untrimmed = granuleBase + decodedSamples
          if (isTerminal) {
            if (page.granulePosition > untrimmed) malformed('terminal granule exceeds decoded samples')
            if (untrimmed - page.granulePosition > finalPacketSamples) {
              malformed('terminal granule trims more than the final Opus packet')
            }
          } else if (page.granulePosition !== untrimmed) {
            malformed('non-terminal audio granule does not match fixed-frame packet duration')
          }
          if (previousGranule !== undefined && page.granulePosition < previousGranule) {
            malformed('audio granule positions are not monotonic')
          }
          previousGranule = page.granulePosition
          terminalGranule = page.granulePosition
        }

        if ((page.headerType & 4) !== 0) ended = true
        nextPageSequence = (page.sequence + 1) >>> 0
        pageCount += 1
      }
      if (packetOffset !== parsed.packets.length) malformed('page packet accounting is inconsistent')
    },

    finish(expectedAudioPackets) {
      if (!ended) malformed('terminal sequence range has no end-of-stream page')
      if (packetCount < 2n || preSkip === undefined) malformed('assembled stream has incomplete Opus headers')
      if (packetCount - 2n !== expectedAudioPackets) malformed('assembled audio packet count mismatch')
      if (terminalGranule === undefined || terminalGranule < preSkip) malformed('terminal granule precedes Opus pre-skip')
      const durationMsBig = ((terminalGranule - preSkip) * 1000n) / 48_000n
      if (durationMsBig > 86_400_000n) malformed('playable duration exceeds the v1 bound')
      return { durationMs: Number(durationMsBig) }
    },
  }
}

export function inspectCompleteOpusStream(bytes, { expectedAudioPackets, codec }) {
  const parsed = validateOggChunk(bytes, { isFirst: true, expectedAudioPackets, codec })
  const inspector = createOpusStreamInspector(codec)
  inspector.accept(parsed)
  return inspector.finish(expectedAudioPackets)
}
