import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const testsDirectory = new URL("./", import.meta.url);
const firmwareDirectory = new URL("../", testsDirectory);
const protocolDirectory = new URL("../../../protocols/human-io/v1/", testsDirectory);
const header = await readFile(new URL("include/gumi/button.h", firmwareDirectory), "utf8");
const captureHeader = await readFile(new URL("include/gumi/capture.h", firmwareDirectory), "utf8");
const feedbackHeader = await readFile(new URL("include/gumi/feedback.h", firmwareDirectory), "utf8");
const contract = JSON.parse(await readFile(new URL("contract.json", protocolDirectory), "utf8"));

function integerMacro(name) {
  const match = header.match(new RegExp(`^#define ${name} UINT64_C\\((\\d+)\\)$`, "m"));
  assert.ok(match, `${name} must remain an explicit UINT64_C integer macro`);
  return Number(match[1]);
}

test("portable firmware timing constants are locked to the device contract", () => {
  assert.equal(integerMacro("GUMI_BUTTON_DEBOUNCE_MS"), contract.timing_ms.debounce_stable);
  assert.equal(
    integerMacro("GUMI_BUTTON_DOUBLE_TAP_WINDOW_MS"),
    contract.timing_ms.double_tap_window_after_first_release,
  );
  assert.equal(integerMacro("GUMI_BUTTON_HOLD_MS"), contract.timing_ms.hold_deadline_after_press);
  assert.equal(
    integerMacro("GUMI_BUTTON_CONFIRMATION_HOLD_MS"),
    contract.timing_ms.maintenance_confirmation_hold,
  );
});

test("firmware API documents the contract's exact timestamp precedence", () => {
  assert.deepEqual(contract.timing_ms.same_timestamp_precedence, [
    "accepted_button_edges",
    "gesture_deadlines",
  ]);
  assert.equal(contract.timing_ms.double_tap_window_inclusive, true);
  assert.equal(contract.timing_ms.hold_deadline_inclusive, true);
  assert.equal(contract.timing_ms.maintenance_confirmation_lease_expiry_inclusive, false);
  assert.match(header, /Accepted edges at T must be supplied before advance_to\(T\)/);
});

test("physical recognizer exposes only device-local gestures, never capture policy actions", () => {
  for (const event of contract.gesture_events) {
    assert.match(header, new RegExp(`GUMI_BUTTON_EVENT_${event.toUpperCase()}`));
  }
  assert.doesNotMatch(header, /START_(BASE_RECORDING|VOICE_TURN)|STOP_BASE_RECORDING/);
});

test("capture supervisor exposes every contract-owned microphone truth and safety gate", () => {
  for (const state of contract.state_axes.mic_truth) {
    assert.match(captureHeader, new RegExp(`GUMI_CAPTURE_MIC_${state.toUpperCase()}`));
  }
  assert.match(captureHeader, /GUMI_CAPTURE_ACTION_ASSERT_PRIVACY_GUARD/);
  assert.match(captureHeader, /GUMI_CAPTURE_ACTION_DEASSERT_PRIVACY_GUARD/);
  assert.match(captureHeader, /gumi_capture_base_audio_is_permitted/);
  assert.match(captureHeader, /gumi_capture_voice_audio_is_permitted/);
  assert.equal(contract.lifecycle.boot, "verified_off");
  assert.equal(contract.lifecycle.watchdog_recovery, "verified_off_with_discontinuity");
});

test("capture supervisor remains device-local and transition-correlated", () => {
  assert.doesNotMatch(captureHeader, /\b(?:cloud|android|ble|zephyr)\b/i);
  assert.match(captureHeader, /uint64_t transition_id/);
  assert.match(captureHeader, /GUMI_CAPTURE_STATUS_STALE_TRANSITION/);
  assert.equal(
    contract.invariants.includes("no_audio_frame_before_capture_commitment"),
    true,
  );
  assert.equal(
    contract.invariants.includes("privacy_output_failure_rejects_or_stops_capture"),
    true,
  );
});

test("firmware feedback arbiter names every contract indicator and preserves priority", () => {
  for (const pattern of Object.keys(contract.indicator.patterns)) {
    assert.match(feedbackHeader, new RegExp(`GUMI_FEEDBACK_PATTERN_${pattern.toUpperCase()}`));
  }
  assert.deepEqual(contract.feedback_arbitration.priority_high_to_low, [
    "privacy_unknown_or_acquired",
    "fatal_safety",
    "recovery_update_pairing",
    "recoverable_warning",
    "power_charging",
    "requested_status_link",
  ]);
  assert.equal(contract.feedback_arbitration.lower_priority_may_override_privacy, false);
  assert.equal(contract.feedback_arbitration.single_writer, "firmware_feedback_arbiter");
});
