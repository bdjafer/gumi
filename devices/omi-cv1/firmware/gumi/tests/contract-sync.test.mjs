import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const testsDirectory = new URL("./", import.meta.url);
const firmwareDirectory = new URL("../", testsDirectory);
const protocolDirectory = new URL("../../../protocols/human-io/v1/", testsDirectory);
const provisionerProtocolDirectory = new URL(
  "../../../protocols/gatt/gumi-recording-root-provisioner-v1/",
  testsDirectory,
);
const header = await readFile(new URL("include/gumi/button.h", firmwareDirectory), "utf8");
const captureHeader = await readFile(new URL("include/gumi/capture.h", firmwareDirectory), "utf8");
const feedbackHeader = await readFile(new URL("include/gumi/feedback.h", firmwareDirectory), "utf8");
const interactionPolicyHeader = await readFile(
  new URL("include/gumi/interaction_policy.h", firmwareDirectory),
  "utf8",
);
const interactionPolicySource = await readFile(
  new URL("src/interaction_policy.c", firmwareDirectory),
  "utf8",
);
const semanticSignalHeader = await readFile(
  new URL("include/gumi/semantic_signal.h", firmwareDirectory),
  "utf8",
);
const provisionerHeader = await readFile(
  new URL(
    "zephyr/omi-v3012/include/gumi/omi_v3012_recording_root_provisioner.h",
    firmwareDirectory,
  ),
  "utf8",
);
const provisionerAndroidDecoder = await readFile(
  new URL(
    "../../application-updater/android/src/main/kotlin/dev/gumi/devices/omicv1/updater/android/" +
      "OmiCv1RecordingRootProvisionerStatus.kt",
    firmwareDirectory,
  ),
  "utf8",
);
const contract = JSON.parse(await readFile(new URL("contract.json", protocolDirectory), "utf8"));
const policyProfiles = JSON.parse(
  await readFile(new URL("policy-profiles.json", protocolDirectory), "utf8"),
);
const provisionerProfile = JSON.parse(
  await readFile(new URL("profile.json", provisionerProtocolDirectory), "utf8"),
);

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

test("versioned interaction profiles stay separate from gesture recognition and hardware effects", () => {
  for (const profileId of Object.keys(policyProfiles.profiles)) {
    assert.match(interactionPolicySource, new RegExp(`"${profileId}"`));
  }
  for (const intent of [
    "SHOW_STATUS",
    "START_BASE_RECORDING",
    "STOP_BASE_RECORDING",
    "BEGIN_VOICE_ACTION",
    "END_VOICE_ACTION",
    "BEGIN_INTERPRETATION_MARKER",
    "END_INTERPRETATION_MARKER",
  ]) {
    assert.match(interactionPolicyHeader, new RegExp(`GUMI_INTERACTION_INTENT_${intent}`));
  }
  assert.doesNotMatch(
    interactionPolicyHeader,
    /#include\s+"gumi\/(?:capture|feedback|recording|transport|microphone)[^"]*"/,
  );
  assert.equal(
    policyProfiles.profiles["manual-recording-push-to-talk-v1"].artifact_status[
      "functional-recording-0003"
    ],
    "behavior_equivalent_mapping_embedded_in_frozen_target_source",
  );
  assert.equal(
    policyProfiles.profiles["manual-recording-push-to-talk-v1"].artifact_status[
      "functional-recording-0004"
    ],
    "behavior_equivalent_mapping_inherited_unchanged_from_v0003",
  );
  assert.equal(
    policyProfiles.profiles["manual-recording-push-to-talk-v1"].artifact_status[
      "functional-recording-0005"
    ],
    "behavior_equivalent_mapping_inherited_unchanged_from_v0003",
  );
  assert.equal(
    policyProfiles.profiles["continuous-recording-marker-v1"].artifact_status[
      "functional-recording-0003"
    ],
    "not_present",
  );
  assert.equal(
    policyProfiles.profiles["continuous-recording-marker-v1"].artifact_status[
      "functional-recording-0004"
    ],
    "not_present",
  );
  assert.equal(
    policyProfiles.profiles["continuous-recording-marker-v1"].artifact_status[
      "functional-recording-0005"
    ],
    "not_present",
  );
});

test("semantic interpretation markers remain recording-correlated data", () => {
  for (const field of policyProfiles.semantic_signal.interpretation_marker.correlated_fields) {
    assert.match(semanticSignalHeader, new RegExp(`\\b${field}\\b`));
  }
  assert.match(semanticSignalHeader, /GUMI_SEMANTIC_SIGNAL_EVENT_INTERRUPTED/);
  assert.doesNotMatch(semanticSignalHeader, /\b(?:microphone|gpio|pdm|fatfs|ble|cloud|android)\b/i);
  assert.equal(
    policyProfiles.invariants.includes(
      "semantic_marker_is_data_and_never_changes_microphone_or_recording_truth",
    ),
    true,
  );
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

test("provisioner terminal mutation admission is identical across protocol, firmware, and Android", () => {
  assert.equal(provisionerProfile.status.flags.mutation_admitted, 1 << 5);
  assert.equal(
    provisionerProfile.status.accepted_terminal_states.provisioned.required_flags,
    0x3f,
  );
  assert.equal(
    provisionerProfile.status.accepted_terminal_states.already_present.required_flags,
    0x3b,
  );
  assert.equal(provisionerProfile.ota.preterminal_image_upload_denied, true);
  assert.equal(provisionerProfile.ota.preterminal_remote_reset_denied, true);
  assert.equal(provisionerProfile.provisioning.power_loss_atomic, false);
  assert.match(
    provisionerHeader,
    /GUMI_OMI_V3012_RECORDING_ROOT_FLAG_MUTATION_ADMITTED = 1U << 5/,
  );
  assert.match(provisionerAndroidDecoder, /private const val MUTATION_ADMITTED = 1 shl 5/);
  assert.match(
    provisionerAndroidDecoder,
    /MEXT_PRESENT or DERIVATION_VERIFIED or MUTATION_ADMITTED/,
  );
});
