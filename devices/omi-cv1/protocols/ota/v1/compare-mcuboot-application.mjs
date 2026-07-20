import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const IMAGE_MAGIC = 0x96f3b83d;
const IMAGE_HEADER_MIN_SIZE = 32;
const TLV_INFO_SIZE = 4;
const TLV_ENTRY_HEADER_SIZE = 4;
const TLV_MAGIC = 0x6907;
const PROTECTED_TLV_MAGIC = 0x6908;
const TLV_KEYHASH = 0x01;
const TLV_SHA256 = 0x10;
const TLV_RSA2048_PSS = 0x20;
const SHA256_SIZE = 32;
const RSA2048_SIGNATURE_SIZE = 256;

export class McubootFormatError extends Error {
  constructor(message) {
    super(message);
    this.name = "McubootFormatError";
  }
}

export class McubootMismatchError extends Error {
  constructor(message) {
    super(message);
    this.name = "McubootMismatchError";
  }
}

function asBuffer(bytes, label) {
  if (!Buffer.isBuffer(bytes) && !(bytes instanceof Uint8Array)) {
    throw new TypeError(`${label} must be a Buffer or Uint8Array`);
  }
  return Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
}

function requireAvailable(buffer, start, size, label, description) {
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(size) || start < 0 || size < 0) {
    throw new McubootFormatError(`${label}: invalid ${description} range`);
  }
  const end = start + size;
  if (!Number.isSafeInteger(end) || end > buffer.length) {
    throw new McubootFormatError(
      `${label}: ${description} extends beyond the image (${end} > ${buffer.length} bytes)`,
    );
  }
  return end;
}

function hex16(value) {
  return `0x${value.toString(16).padStart(4, "0")}`;
}

function hex8(value) {
  return `0x${value.toString(16).padStart(2, "0")}`;
}

function parseTlvArea(buffer, start, expectedMagic, expectedSize, area, label) {
  requireAvailable(buffer, start, TLV_INFO_SIZE, label, `${area} TLV info header`);

  const magic = buffer.readUInt16LE(start);
  const totalSize = buffer.readUInt16LE(start + 2);
  if (magic !== expectedMagic) {
    throw new McubootFormatError(
      `${label}: ${area} TLV magic is ${hex16(magic)}, expected ${hex16(expectedMagic)}`,
    );
  }
  if (totalSize < TLV_INFO_SIZE) {
    throw new McubootFormatError(`${label}: ${area} TLV total size ${totalSize} is smaller than its header`);
  }
  if (expectedSize !== undefined && totalSize !== expectedSize) {
    throw new McubootFormatError(
      `${label}: ${area} TLV size ${totalSize} does not match MCUboot header value ${expectedSize}`,
    );
  }

  const end = requireAvailable(buffer, start, totalSize, label, `${area} TLV area`);
  const entries = [];
  let cursor = start + TLV_INFO_SIZE;
  while (cursor < end) {
    requireAvailable(buffer, cursor, TLV_ENTRY_HEADER_SIZE, label, `${area} TLV entry header`);
    if (cursor + TLV_ENTRY_HEADER_SIZE > end) {
      throw new McubootFormatError(`${label}: ${area} TLV entry header crosses the TLV boundary`);
    }

    const type = buffer[cursor];
    const reserved = buffer[cursor + 1];
    const length = buffer.readUInt16LE(cursor + 2);
    if (reserved !== 0) {
      throw new McubootFormatError(
        `${label}: ${area} TLV ${hex8(type)} has non-zero reserved byte ${hex8(reserved)}`,
      );
    }

    const valueStart = cursor + TLV_ENTRY_HEADER_SIZE;
    const valueEnd = valueStart + length;
    if (!Number.isSafeInteger(valueEnd) || valueEnd > end) {
      throw new McubootFormatError(
        `${label}: ${area} TLV ${hex8(type)} value crosses the TLV boundary (${valueEnd} > ${end})`,
      );
    }
    entries.push({
      area,
      type,
      reserved,
      length,
      headerStart: cursor,
      valueStart,
      valueEnd,
    });
    cursor = valueEnd;
  }

  if (cursor !== end) {
    throw new McubootFormatError(`${label}: ${area} TLV entries do not end on the declared boundary`);
  }
  return { area, start, end, magic, totalSize, entries };
}

function requireUniqueTlv(entries, type, length, name, label) {
  const matches = entries.filter((entry) => entry.type === type);
  if (matches.length !== 1) {
    throw new McubootFormatError(`${label}: expected exactly one ${name} TLV, found ${matches.length}`);
  }
  const [entry] = matches;
  if (entry.length !== length) {
    throw new McubootFormatError(
      `${label}: ${name} TLV is ${entry.length} bytes, expected ${length}`,
    );
  }
  if (entry.area !== "unprotected") {
    throw new McubootFormatError(`${label}: ${name} TLV must be in the unprotected TLV area`);
  }
  return entry;
}

export function parseMcubootApplication(bytes, label = "image") {
  const buffer = asBuffer(bytes, label);
  requireAvailable(buffer, 0, IMAGE_HEADER_MIN_SIZE, label, "MCUboot image header");

  const magic = buffer.readUInt32LE(0);
  if (magic !== IMAGE_MAGIC) {
    throw new McubootFormatError(
      `${label}: image magic is 0x${magic.toString(16).padStart(8, "0")}, expected 0x${IMAGE_MAGIC.toString(16)}`,
    );
  }

  const headerSize = buffer.readUInt16LE(8);
  const protectedTlvSize = buffer.readUInt16LE(10);
  const payloadSize = buffer.readUInt32LE(12);
  if (headerSize < IMAGE_HEADER_MIN_SIZE) {
    throw new McubootFormatError(
      `${label}: MCUboot header size ${headerSize} is smaller than ${IMAGE_HEADER_MIN_SIZE}`,
    );
  }
  requireAvailable(buffer, 0, headerSize, label, "MCUboot image header");

  const payloadStart = headerSize;
  const payloadEnd = requireAvailable(buffer, payloadStart, payloadSize, label, "application payload");

  let protectedTlv = {
    area: "protected",
    start: payloadEnd,
    end: payloadEnd,
    magic: null,
    totalSize: 0,
    entries: [],
  };
  if (protectedTlvSize > 0) {
    protectedTlv = parseTlvArea(
      buffer,
      payloadEnd,
      PROTECTED_TLV_MAGIC,
      protectedTlvSize,
      "protected",
      label,
    );
  }

  const unprotectedTlv = parseTlvArea(
    buffer,
    protectedTlv.end,
    TLV_MAGIC,
    undefined,
    "unprotected",
    label,
  );
  if (unprotectedTlv.end !== buffer.length) {
    throw new McubootFormatError(
      `${label}: unprotected TLV area ends at ${unprotectedTlv.end}, but image has ${buffer.length} bytes`,
    );
  }

  const entries = [...protectedTlv.entries, ...unprotectedTlv.entries];
  const sha256 = requireUniqueTlv(entries, TLV_SHA256, SHA256_SIZE, "SHA-256", label);
  const keyhash = requireUniqueTlv(entries, TLV_KEYHASH, SHA256_SIZE, "key-hash", label);
  const rsa2048 = requireUniqueTlv(
    entries,
    TLV_RSA2048_PSS,
    RSA2048_SIGNATURE_SIZE,
    "RSA-2048 PSS signature",
    label,
  );

  if (unprotectedTlv.entries.at(-1) !== rsa2048 || rsa2048.valueEnd !== buffer.length) {
    throw new McubootFormatError(
      `${label}: RSA-2048 PSS signature must be the final TLV and final ${RSA2048_SIGNATURE_SIZE} image bytes`,
    );
  }

  const digestInputEnd = payloadEnd + protectedTlvSize;
  const computedDigest = createHash("sha256").update(buffer.subarray(0, digestInputEnd)).digest();
  const encodedDigest = buffer.subarray(sha256.valueStart, sha256.valueEnd);
  if (!computedDigest.equals(encodedDigest)) {
    throw new McubootFormatError(
      `${label}: SHA-256 TLV does not match header, payload, and protected TLV bytes`,
    );
  }

  return {
    buffer,
    fileSize: buffer.length,
    magic,
    headerSize,
    protectedTlvSize,
    payloadSize,
    payloadStart,
    payloadEnd,
    digestInputEnd,
    protectedTlv,
    unprotectedTlv,
    entries,
    sha256,
    keyhash,
    rsa2048,
    deterministicEnd: rsa2048.valueStart,
  };
}

function signedDelta(value) {
  return value > 0 ? `+${value}` : String(value);
}

function firstDifference(left, right, start, end) {
  for (let index = start; index < end; index += 1) {
    if (left[index] !== right[index]) return index;
  }
  return -1;
}

function mismatch(message) {
  throw new McubootMismatchError(message);
}

function compareSize(name, official, local) {
  if (official !== local) {
    mismatch(
      `${name} mismatch: official=${official} bytes, local=${local} bytes (${signedDelta(local - official)} bytes)`,
    );
  }
}

function entryLayout(image) {
  return image.entries.map(({ area, type, length }) => ({ area, type, length }));
}

function sameLayout(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function tlvName(type) {
  if (type === TLV_SHA256) return "SHA-256";
  if (type === TLV_KEYHASH) return "key-hash";
  if (type === TLV_RSA2048_PSS) return "RSA-2048 PSS signature";
  return `TLV ${hex8(type)}`;
}

export function compareMcubootApplications(officialBytes, localBytes) {
  const official = parseMcubootApplication(officialBytes, "official image");
  const local = parseMcubootApplication(localBytes, "local image");

  compareSize("header size", official.headerSize, local.headerSize);
  compareSize("payload size", official.payloadSize, local.payloadSize);
  compareSize("protected TLV size", official.protectedTlvSize, local.protectedTlvSize);

  let difference = firstDifference(official.buffer, local.buffer, 0, official.headerSize);
  if (difference !== -1) mismatch(`header mismatch at image offset ${difference}`);

  difference = firstDifference(
    official.buffer,
    local.buffer,
    official.payloadStart,
    official.payloadEnd,
  );
  if (difference !== -1) {
    mismatch(
      `payload mismatch at image offset ${difference} (payload offset ${difference - official.payloadStart})`,
    );
  }

  const officialProtected = official.buffer.subarray(official.protectedTlv.start, official.protectedTlv.end);
  const localProtected = local.buffer.subarray(local.protectedTlv.start, local.protectedTlv.end);
  if (!officialProtected.equals(localProtected)) mismatch("protected TLV area mismatch");

  const officialLayout = entryLayout(official);
  const localLayout = entryLayout(local);
  if (!sameLayout(officialLayout, localLayout)) {
    mismatch(
      `TLV layout mismatch: official=${JSON.stringify(officialLayout)}, local=${JSON.stringify(localLayout)}`,
    );
  }

  compareSize("RSA signature offset", official.deterministicEnd, local.deterministicEnd);
  compareSize("file size", official.fileSize, local.fileSize);

  for (let index = 0; index < official.entries.length; index += 1) {
    const officialEntry = official.entries[index];
    const localEntry = local.entries[index];
    const officialHeader = official.buffer.subarray(officialEntry.headerStart, officialEntry.valueStart);
    const localHeader = local.buffer.subarray(localEntry.headerStart, localEntry.valueStart);
    if (!officialHeader.equals(localHeader)) {
      mismatch(`${tlvName(officialEntry.type)} TLV header mismatch`);
    }
    if (officialEntry.type === TLV_RSA2048_PSS) continue;

    const officialValue = official.buffer.subarray(officialEntry.valueStart, officialEntry.valueEnd);
    const localValue = local.buffer.subarray(localEntry.valueStart, localEntry.valueEnd);
    if (!officialValue.equals(localValue)) mismatch(`${tlvName(officialEntry.type)} TLV mismatch`);
  }

  const officialPrefix = official.buffer.subarray(0, official.deterministicEnd);
  const localPrefix = local.buffer.subarray(0, local.deterministicEnd);
  if (!officialPrefix.equals(localPrefix)) {
    mismatch("deterministic image bytes mismatch before the RSA-2048 PSS signature value");
  }

  const officialSignature = official.buffer.subarray(official.rsa2048.valueStart);
  const localSignature = local.buffer.subarray(local.rsa2048.valueStart);
  return {
    ok: true,
    headerSize: official.headerSize,
    payloadSize: official.payloadSize,
    protectedTlvSize: official.protectedTlvSize,
    deterministicBytes: official.deterministicEnd,
    fileSize: official.fileSize,
    signatureBytesEqual: officialSignature.equals(localSignature),
  };
}

function usage() {
  return "Usage: node compare-mcuboot-application.mjs <official-omi.signed.bin> <local-omi.signed.bin>";
}

async function main(argv) {
  if (argv.length !== 2 || argv.includes("--help") || argv.includes("-h")) {
    console.error(usage());
    return argv.includes("--help") || argv.includes("-h") ? 0 : 2;
  }

  try {
    const [official, local] = await Promise.all(argv.map((path) => readFile(path)));
    const result = compareMcubootApplications(official, local);
    const signatureResult = result.signatureBytesEqual
      ? "RSA-PSS signature bytes are also identical"
      : "only the RSA-PSS signature bytes differ, as permitted";
    console.log(
      `PASS: ${result.deterministicBytes} deterministic bytes match ` +
        `(header=${result.headerSize}, payload=${result.payloadSize}, file=${result.fileSize}); ${signatureResult}.`,
    );
    return 0;
  } catch (error) {
    console.error(`FAIL: ${error.message}`);
    return 1;
  }
}

const invokedPath = process.argv[1] ? pathToFileURL(process.argv[1]).href : null;
if (invokedPath === import.meta.url) {
  process.exitCode = await main(process.argv.slice(2));
}

