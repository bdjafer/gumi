import { fail, invalid } from './error.mjs'
import { exactObject, u64 } from './primitives.mjs'

export function validateRange(value, path = '$') {
  exactObject(value, ['first', 'last'], [], path)
  const first = u64(value.first, `${path}.first`)
  const last = u64(value.last, `${path}.last`)
  if (first > last) fail('INVALID_SEQUENCE_RANGE', 400, `${path}: first must not exceed last`)
  return { first, last }
}

export function rangeSize(range) {
  return range.last - range.first + 1n
}

export function overlaps(left, right) {
  return left.first <= right.last && right.first <= left.last
}

export function canonicalRanges(records) {
  const sorted = records
    .map((record) => record.sequenceRange ?? record.descriptor?.sequenceRange)
    .map((range) => ({ first: BigInt(range.first), last: BigInt(range.last) }))
    .sort((left, right) => (left.first < right.first ? -1 : left.first > right.first ? 1 : 0))
  const merged = []
  for (const range of sorted) {
    const previous = merged.at(-1)
    if (!previous || range.first > previous.last + 1n) merged.push({ ...range })
    else if (range.last > previous.last) previous.last = range.last
  }
  return merged
}

export function missingRanges(first, last, committed) {
  if (first > last) invalid('range', 'first must not exceed last')
  const missing = []
  let cursor = first
  for (const range of committed) {
    if (range.last < first || range.first > last) continue
    const boundedFirst = range.first < first ? first : range.first
    const boundedLast = range.last > last ? last : range.last
    if (cursor < boundedFirst) missing.push({ first: cursor, last: boundedFirst - 1n })
    if (boundedLast >= cursor) cursor = boundedLast + 1n
  }
  if (cursor <= last) missing.push({ first: cursor, last })
  return missing
}

export function wireRange(range) {
  return { first: String(range.first), last: String(range.last) }
}

export function streamSnapshot(policy, chunks) {
  const records = [...chunks.values()]
  const committed = canonicalRanges(records)
  const first = BigInt(policy.sequencePolicy.first)
  const terminalSequence = records.find((record) => record.containerFacts?.endsLogicalStream)?.containerFacts
    .terminalSequence ?? null
  if (committed.length === 0) {
    return {
      streamId: policy.streamId,
      sequencePolicy: structuredClone(policy.sequencePolicy),
      codec: structuredClone(policy.codec),
      oggLayout: structuredClone(policy.oggLayout),
      accountedRange: null,
      committedRanges: [],
      missingRanges: [],
      durableThrough: null,
      terminalSequence,
      storedChunkCount: '0',
      storedBytes: '0',
    }
  }
  const greatest = committed.at(-1).last
  const missing = missingRanges(first, greatest, committed)
  const durableThrough = committed[0].first === first ? committed[0].last : null
  const storedBytes = records.reduce((sum, record) => sum + BigInt(record.descriptor.payloadBytes), 0n)
  return {
    streamId: policy.streamId,
    sequencePolicy: structuredClone(policy.sequencePolicy),
    codec: structuredClone(policy.codec),
    oggLayout: structuredClone(policy.oggLayout),
    accountedRange: wireRange({ first, last: greatest }),
    committedRanges: committed.map(wireRange),
    missingRanges: missing.map(wireRange),
    durableThrough: durableThrough === null ? null : String(durableThrough),
    terminalSequence,
    storedChunkCount: String(records.length),
    storedBytes: String(storedBytes),
  }
}
