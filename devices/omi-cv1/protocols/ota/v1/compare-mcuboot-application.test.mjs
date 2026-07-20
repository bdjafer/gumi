import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import {
  McubootFormatError,
  McubootMismatchError,
  compareMcubootApplications,
} from "./compare-mcuboot-application.mjs";

const IMAGE_MAGIC = 0x96f3b83d;
const TLV_MAGIC = 0x6907;

function tlv(type, value) {
  const header = Buffer.alloc(4);
  header[0] = type;
  header.writeUInt16LE(value.length, 2);
  return Buffer.concat([header, value]);
}

function syntheticImage({ payloadSize = 64, payloadFill = 0x42, signatureFill = 0x91 } = {}) {
  const header = Buffer.alloc(32);
  header.writeUInt32LE(IMAGE_MAGIC, 0);
  header.writeUInt16LE(header.length, 8);
  header.writeUInt32LE(payloadSize, 12);

  const payload = Buffer.alloc(payloadSize, payloadFill);
  const digest = createHash("sha256").update(Buffer.concat([header, payload])).digest();
  const entries = [
    tlv(0x10, digest),
    tlv(0x01, Buffer.alloc(32, 0x5a)),
    tlv(0x20, Buffer.alloc(256, signatureFill)),
  ];
  const info = Buffer.alloc(4);
  info.writeUInt16LE(TLV_MAGIC, 0);
  info.writeUInt16LE(4 + entries.reduce((size, entry) => size + entry.length, 0), 2);
  return Buffer.concat([header, payload, info, ...entries]);
}

function unprotectedTlvStart(image) {
  return image.readUInt16LE(8) + image.readUInt32LE(12) + image.readUInt16LE(10);
}

test("accepts two byte-exact MCUboot applications", () => {
  const official = syntheticImage();
  const result = compareMcubootApplications(official, Buffer.from(official));

  assert.equal(result.ok, true);
  assert.equal(result.signatureBytesEqual, true);
  assert.equal(result.payloadSize, 64);
  assert.equal(result.deterministicBytes, official.length - 256);
});

test("allows variance only in the final 256-byte RSA-PSS signature value", () => {
  const result = compareMcubootApplications(
    syntheticImage({ signatureFill: 0x11 }),
    syntheticImage({ signatureFill: 0xee }),
  );

  assert.equal(result.ok, true);
  assert.equal(result.signatureBytesEqual, false);
});

test("reports the exact payload-size delta before comparing bytes", () => {
  assert.throws(
    () => compareMcubootApplications(syntheticImage(), syntheticImage({ payloadSize: 192 })),
    (error) => {
      assert.equal(error instanceof McubootMismatchError, true);
      assert.match(
        error.message,
        /payload size mismatch: official=64 bytes, local=192 bytes \(\+128 bytes\)/,
      );
      return true;
    },
  );
});

test("rejects a SHA-256 TLV that does not describe its image", () => {
  const official = syntheticImage();
  const invalidDigest = Buffer.from(official);
  const digestValueStart = unprotectedTlvStart(invalidDigest) + 4 + 4;
  invalidDigest[digestValueStart] ^= 0xff;

  assert.throws(
    () => compareMcubootApplications(official, invalidDigest),
    (error) => {
      assert.equal(error instanceof McubootFormatError, true);
      assert.match(error.message, /local image: SHA-256 TLV does not match/);
      return true;
    },
  );
});

test("rejects deterministic payload variance even when its digest is internally valid", () => {
  assert.throws(
    () => compareMcubootApplications(syntheticImage(), syntheticImage({ payloadFill: 0x43 })),
    (error) => {
      assert.equal(error instanceof McubootMismatchError, true);
      assert.match(error.message, /payload mismatch at image offset 32 \(payload offset 0\)/);
      return true;
    },
  );
});

test("rejects malformed TLV boundaries", () => {
  const official = syntheticImage();
  const malformed = Buffer.from(official);
  const tlvStart = unprotectedTlvStart(malformed);
  malformed.writeUInt16LE(malformed.readUInt16LE(tlvStart + 2) + 1, tlvStart + 2);

  assert.throws(
    () => compareMcubootApplications(official, malformed),
    (error) => {
      assert.equal(error instanceof McubootFormatError, true);
      assert.match(error.message, /unprotected TLV area extends beyond the image/);
      return true;
    },
  );
});

test("rejects bytes appended after the declared final signature", () => {
  const official = syntheticImage();
  const withExtraByte = Buffer.concat([official, Buffer.from([0])]);

  assert.throws(
    () => compareMcubootApplications(official, withExtraByte),
    /unprotected TLV area ends at .* but image has .* bytes/,
  );
});

test("rejects a truncated final signature", () => {
  const official = syntheticImage();
  const missingByte = official.subarray(0, official.length - 1);

  assert.throws(
    () => compareMcubootApplications(official, missingByte),
    /unprotected TLV area extends beyond the image/,
  );
});

