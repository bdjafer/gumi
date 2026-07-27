#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { parseMcubootApplication } from "../../protocols/ota/v1/compare-mcuboot-application.mjs";

function usage() {
  return "Usage: node verify-application-release.mjs <release.json> <omi.signed.bin>";
}

function fail(message) {
  throw new Error(message);
}

function expectEqual(label, actual, expected) {
  if (actual !== expected) fail(`${label}: expected ${expected}, observed ${actual}`);
}

function valueHex(image, entry) {
  return image.buffer.subarray(entry.valueStart, entry.valueEnd).toString("hex");
}

async function main(argv) {
  if (argv.length !== 2 || argv.includes("--help") || argv.includes("-h")) {
    console.error(usage());
    return argv.includes("--help") || argv.includes("-h") ? 0 : 2;
  }

  const [releasePath, applicationPath] = argv;
  const [releaseBytes, applicationBytes] = await Promise.all([
    readFile(releasePath),
    readFile(applicationPath),
  ]);
  const release = JSON.parse(releaseBytes.toString("utf8"));
  expectEqual("release schema", release.release_schema, "gumi.omi-cv1.application-release/v1");
  expectEqual(
    "artifact authorization capability",
    release.artifact_conveys_physical_authorization,
    false,
  );

  const expected = release.application_artifact;
  if (!expected || typeof expected !== "object") fail("release has no application_artifact");
  const image = parseMcubootApplication(applicationBytes, expected.identity ?? "candidate application");
  const version = `${image.buffer[20]}.${image.buffer[21]}.${image.buffer.readUInt16LE(22)}+${image.buffer.readUInt32LE(24)}`;
  const tlvTypes = image.entries.map(({ type }) => type);

  expectEqual("file size", image.fileSize, expected.size_bytes);
  expectEqual(
    "file SHA-256",
    createHash("sha256").update(applicationBytes).digest("hex"),
    expected.file_sha256,
  );
  expectEqual("header size", image.headerSize, expected.header_size_bytes);
  expectEqual("payload size", image.payloadSize, expected.payload_size_bytes);
  expectEqual("protected TLV size", image.protectedTlvSize, expected.protected_tlv_size_bytes);
  expectEqual("MCUboot image hash", valueHex(image, image.sha256), expected.mcuboot_image_hash);
  expectEqual("compatibility key hash", valueHex(image, image.keyhash), expected.compatibility_key_hash);
  expectEqual("MCUboot version", version, expected.mcuboot_version);
  expectEqual("deterministic prefix", image.deterministicEnd, expected.deterministic_prefix_bytes);
  expectEqual("TLV layout", JSON.stringify(tlvTypes), JSON.stringify([0x10, 0x01, 0x20]));
  expectEqual("security counter", expected.security_counter, null);
  expectEqual("signature qualification", expected.signature_verified, true);

  console.log(
    `PASS: ${release.release_id} exact application bytes and MCUboot manifest match ` +
      `(image hash ${expected.mcuboot_image_hash}).`,
  );
  return 0;
}

try {
  process.exitCode = await main(process.argv.slice(2));
} catch (error) {
  console.error(`FAIL: ${error.message}`);
  process.exitCode = 1;
}
