import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const directory = new URL("./", import.meta.url);
const contract = JSON.parse(await readFile(new URL("contract.json", directory), "utf8"));
const fixtures = JSON.parse(await readFile(new URL("fixtures.json", directory), "utf8"));

const fixtureCases = fixtures.cases;
const indicators = new Set(Object.keys(contract.indicator.patterns));
const haptics = new Set(Object.keys(contract.haptic.patterns));
const shellCaptureValues = new Set([
  ...Object.keys(contract.shell_capture_labels),
  contract.shell_rules.last_known_recording_on_disconnect,
]);
const nonAxisInitialFields = new Set([
  "base_recording_id",
  "confirmation_lease_expires_at_ms",
  "confirmation_operation",
  "indicator",
  "realtime_admission_lease",
  "shell_capture",
]);
const nonAxisTerminalFields = new Set(["base_recording_id", "indicator"]);

function caseById(id) {
  return fixtureCases.find((fixtureCase) => fixtureCase.id === id);
}

function assertMonotonic(items, label) {
  let previous = -1;
  for (const [index, item] of items.entries()) {
    assert.equal(Number.isSafeInteger(item.at_ms), true, `${label}[${index}].at_ms must be an integer`);
    assert.equal(item.at_ms >= 0, true, `${label}[${index}].at_ms must be non-negative`);
    assert.equal(item.at_ms >= previous, true, `${label} must be monotonic`);
    previous = item.at_ms;
  }
}

function assertStateProjection(projection, allowedMetadata, label) {
  for (const [field, value] of Object.entries(projection ?? {})) {
    const allowedValues = contract.state_axes[field];
    if (allowedValues !== undefined) {
      assert.equal(
        allowedValues.includes(value),
        true,
        `${label}.${field} has undeclared value ${JSON.stringify(value)}`,
      );
      continue;
    }
    assert.equal(allowedMetadata.has(field), true, `${label}.${field} is neither an axis nor metadata`);
  }
}

function assertIndicator(name, label) {
  assert.equal(name === "off" || indicators.has(name), true, `${label} references unknown indicator ${name}`);
}

test("contract and fixture documents identify the same proposed protocol", () => {
  assert.equal(contract.schema, "gumi.device-human-io-contract/v1");
  assert.equal(fixtures.schema, "gumi.device-human-io-fixtures/v1");
  assert.equal(fixtures.protocol, contract.protocol);
  assert.equal(fixtures.contract, "contract.json");
  assert.equal(contract.status, "proposed_unqualified");
  assert.equal(fixtures.status, contract.status);

  const ids = fixtureCases.map(({ id }) => id);
  assert.equal(ids.every((id) => typeof id === "string" && id.length > 0), true);
  assert.equal(new Set(ids).size, ids.length, "fixture case IDs must be unique");
});

test("fixture states, outputs, and timelines remain inside the device contract", () => {
  for (const fixtureCase of fixtureCases) {
    const label = fixtureCase.id;
    assertMonotonic(fixtureCase.inputs, `${label}.inputs`);
    assertMonotonic(fixtureCase.expected.semantic_events ?? [], `${label}.expected.semantic_events`);
    assertStateProjection(fixtureCase.initial, nonAxisInitialFields, `${label}.initial`);
    assertStateProjection(fixtureCase.expected.terminal, nonAxisTerminalFields, `${label}.expected.terminal`);

    if (fixtureCase.initial?.indicator !== undefined) {
      assertIndicator(fixtureCase.initial.indicator, `${label}.initial.indicator`);
    }
    if (fixtureCase.initial?.shell_capture !== undefined) {
      assert.equal(shellCaptureValues.has(fixtureCase.initial.shell_capture), true);
    }
    if (fixtureCase.expected.terminal?.indicator !== undefined) {
      assertIndicator(fixtureCase.expected.terminal.indicator, `${label}.expected.terminal.indicator`);
    }

    for (const [index, output] of (fixtureCase.expected.output_patterns ?? []).entries()) {
      const outputLabel = `${label}.expected.output_patterns[${index}]`;
      assert.equal(
        output.indicator !== undefined || output.haptic !== undefined,
        true,
        `${outputLabel} must name an indicator or haptic`,
      );
      if (output.indicator !== undefined) assertIndicator(output.indicator, outputLabel);
      if (output.haptic !== undefined) {
        assert.equal(haptics.has(output.haptic), true, `${outputLabel} references unknown haptic ${output.haptic}`);
      }
      if (output.from_ms !== undefined && output.until_ms !== undefined) {
        assert.equal(output.from_ms < output.until_ms, true, `${outputLabel} interval must be non-empty`);
      }
    }

    const shell = fixtureCase.expected.shell;
    if (shell?.capture !== undefined) {
      assert.equal(shellCaptureValues.has(shell.capture), true, `${label} has unknown shell capture ${shell.capture}`);
    }
    if (shell?.label !== undefined) {
      assert.equal(shell.label, contract.shell_capture_labels[shell.capture], `${label} has a non-canonical shell label`);
    }
  }
});

test("armed acoustic detection is modeled independently from audio capture", () => {
  assert.deepEqual(contract.state_axes.acoustic_detector, ["disarmed", "armed"]);
  assert.equal(
    contract.invariants.includes("acoustic_detector_armed_does_not_imply_pcm_stream_or_retained_audio"),
    true,
  );

  const fixtureCase = caseById("aad-armed-idle-remains-distinct-from-capture");
  assert.ok(fixtureCase, "the acoustic-detector separation fixture must remain present");
  assert.deepEqual(
    {
      mic_truth: fixtureCase.initial.mic_truth,
      base_recording: fixtureCase.initial.base_recording,
      acoustic_detector: fixtureCase.initial.acoustic_detector,
    },
    { mic_truth: "verified_off", base_recording: "inactive", acoustic_detector: "armed" },
  );
  assert.deepEqual(
    new Set(fixtureCase.expected.forbidden),
    new Set(["privacy_recording_indicator", "encoded_audio_frame", "retained_audio"]),
  );
});

test("normal off-state wake never depends on pairing, a link, the edge, cloud, or a charger", () => {
  const policy = contract.off_state_wake_policy;
  assert.equal(policy.required_normal_wake_source, "qualified_button_press");
  assert.equal(
    policy.charger_insertion,
    "permitted_recovery_source_but_never_required_for_normal_wake",
  );
  assert.deepEqual(policy.normal_wake_forbidden_dependencies, [
    "bond",
    "pairing",
    "active_ble_link",
    "edge_reachability",
    "cloud_reachability",
    "charger_connection",
  ]);
  assert.equal(policy.post_wake_power, "booting");
  assert.equal(policy.post_wake_mic_truth, "verified_off");
  assert.equal(policy.automatic_capture_resume, false);
  assert.equal(
    contract.invariants.includes(
      "off_state_button_wake_does_not_require_bond_link_edge_cloud_or_charger",
    ),
    true,
  );
  assert.equal(contract.qualification_gates.includes("button_wake_from_off_without_charger"), true);
});

test("physical confirmation commitment remains strictly inside its disclosed lease", () => {
  const fixtureCase = caseById("contextual-maintenance-hold-confirms-at-2000-ms");
  assert.ok(fixtureCase, "the physical-confirmation boundary fixture must remain present");

  const buttonDown = fixtureCase.inputs.find(({ type }) => type === "button_down");
  const deadline = fixtureCase.inputs.find(({ type }) => type === "contextual_hold_deadline");
  assert.equal(deadline.at_ms - buttonDown.at_ms, contract.timing_ms.maintenance_confirmation_hold);
  assert.equal(deadline.at_ms < fixtureCase.initial.confirmation_lease_expires_at_ms, true);
  assert.equal(contract.timing_ms.maintenance_confirmation_lease_expiry_inclusive, false);
  assert.equal(
    fixtureCase.expected.semantic_events.some(({ type }) => type === "physical_confirmation"),
    true,
  );
  assert.equal(
    fixtureCase.expected.forbidden.some((event) => event.includes("capture") || event === "audio_frame"),
    true,
  );
});
