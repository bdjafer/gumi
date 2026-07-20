# Omi CV1 human I/O v1

This directory owns the proposed Omi CV1 mapping of the portable
[device human-I/O contract](../../../../../docs/specs/device-human-io-contract.md). It contains no claim
that stock firmware already behaves this way.

## Evidence status

| Artifact or fact | Evidence level | Status |
| --- | --- | --- |
| CV1 has one tactile button, one logical RGB mapping, and a switched ERM haptic | Component/source | Established from pinned BOM, board, and firmware source |
| Owned unit is firmware `3.0.12`, hardware `5.0`, with observed button and haptic GATT surfaces | Bench inventory | Established by the read-only GATT probe |
| v3.0.12 tag's single-tap shutdown, one-second long notification, and intended active-low button wake from system-off | Source | Established at `Omi_CV1_v3.0.12` commit `85159556eac753a088c5efd1b419a5a867508e27`; shutdown trigger/gesture not yet observed physically |
| Owned stock off-state recovery | Bench | Button attempts did not wake the observed off state; charger insertion recovered it. Trigger, elapsed time, exact attempted press grammar, electrical path, and root cause remain unknown |
| Pairing dependency for bounded stock audio | Bench | Successful 10-second audio metadata witness was `NOT_BONDED`; current audio did not require pairing |
| Candidate tag's three-second hold shutdown | Source | Established at `Omi_CV1_v3.0.20` commit `aa1133cd17139aa09cbe4883cdf51f15094b9916`; not installed-device behavior |
| 30/350/500 ms gesture grammar, privacy colors, output patterns, and contextual maintenance hold | Proposed | Unqualified until simulator, firmware, and HIL conformance pass |
| LED visibility, both package behavior, brightness floor, haptic strength, and false-gesture rate | Bench | Pending |

Stock sources start the microphone during boot and reuse the RGB channels for link, charge, time, boot,
and error state. Stock behavior is therefore an extraction and migration input, not this protocol's
privacy contract.

## Files

- [`contract.json`](contract.json) is the machine-readable semantic mapping, timing profile, named
  physical patterns, arbitration order, lifecycle rules, and qualification gates.
- [`fixtures.json`](fixtures.json) supplies deterministic boundary, transition, fault, lifecycle, and
  output cases for firmware, simulator, driver, runtime, and shell conformance.
- [`protocol-integrity.test.mjs`](protocol-integrity.test.mjs) checks the two JSON artifacts locally for
  identity, unique case IDs, monotonic traces, state-axis values, named output references, the strict
  physical-confirmation lease boundary, acoustic-detector separation, and charger-independent off-state
  wake policy. Run it with
  `node --test protocol-integrity.test.mjs` from this directory.

The JSON documents are data contracts, not configuration that may be changed silently. A timing,
gesture, signal, priority, or semantic change creates a reviewed protocol revision and corresponding
fixture update.

## Ownership

- Generic semantic names and safety invariants belong to `edge/sdk` and `edge/runtime` contracts.
- Exact CV1 button/RGB/haptic mapping belongs here and in the CV1 driver/firmware.
- Firmware alone owns the privacy guard and the physical output arbiter.
- The shell may render equivalent accessible signals but cannot override device capture truth.
- Cloud applications do not own physical gesture recognition or privacy indication.

## Default grammar

Accepted button edges are already debounced before gesture recognition:

```text
stable edge:       30 ms
double window:    350 ms after first release, inclusive
hold deadline:    500 ms after press, inclusive
deadline tie:     release wins
maintenance hold: 2,000 ms inside an explicit 15-second confirmation lease
```

In Normal mode, single tap repeats status, double tap toggles durable Recording, and Hold controls
VoiceTurn. VoiceTurn overlays rather than replaces a Recording. Normal mode has no button power-off or
reset gesture.

From Off, a qualified button press is the normal wake source. That path cannot depend on a bond,
pairing, an active BLE link, edge or cloud reachability, or a connected charger. Charger insertion may
remain an emergency/recovery wake source, but it cannot be required for ordinary use. Wake enters
Booting with the microphone verified off and never resumes a prior capture. The owned stock unit does
not currently satisfy the observed user-visible wake requirement; this is a custom-firmware and HIL
gate, not a reason to pair the stock device.

An `expires_at_ms` confirmation lease is exclusive: the 2,000 ms commitment deadline must be strictly
earlier than lease expiry. An accepted release at the 2,000 ms deadline still precedes and cancels that
deadline. Reference-model callers must likewise submit every accepted edge at a timestamp before
advancing gesture deadlines to that timestamp. Stale or repeated accepted edges are invalid inputs and
must fail without advancing timers or consuming pending events.

## Privacy rule

The red base means only: microphone acquired, being acquired/released, or not proven off. Recording is
continuous red. VoiceTurn keeps the red base continuously on and modulates blue. No lower-priority
status, charge, link, update, or fault signal may blink that red base dark.

The declared PWM levels are logical targets. The minimum visible privacy level is intentionally named
`privacy_floor` rather than assigned an unmeasured optical value. HIL qualification supplies the final
calibrated device constant without changing the semantic contract.

## Fixture conventions

- Unless a case says `raw_level_changes`, input timestamps are accepted, debounced logical edges.
- Times are monotonic milliseconds relative to the start of one case.
- Accepted physical edges precede gesture deadlines at the same timestamp. Thus a second press wins over
  the single-tap deadline at 350 ms, and a release wins over the Hold deadline at 500 ms.
- Semantic-event traces are exhaustive per consumer owner: after selecting only the event types a
  component owns, conformance compares the complete ordered trace rather than a subset.
- Effect completions such as `privacy_guard_asserted`, `microphone_acquired`, and
  `microphone_released` are explicit inputs because hardware latency is not guessed by the recognizer.
- `forbidden` lists effects that must not occur anywhere in the case.
- Output traces name semantic patterns; PWM and motor drivers expand those names from `contract.json`.

## Required consumers

The current executable consumer is the pure CV1 reference model in `devices/omi-cv1/simulator`. Its
Android/JVM tests cover the proposed 30/350/500/2,000 ms boundaries, accepted-edge precedence,
invalid-edge transactionality, and confirmation-lease expiry. The JVM conformance suite loads the
exact 20-case corpus and checks its unique IDs against an explicit ownership partition. It executes one
debounce case and the recognizer-owned event slice of all 12 gesture-bearing cases with exact ordered
equality. Effect-completion and output events remain in those cases but are not attributed to the
gesture recognizer. A separate pure lifecycle/feedback owner executes all seven capture-fault,
lifecycle-projection, and output-arbitration cases, including exact semantic events, terminal and shell
projections, persistent-indicator intervals, haptics, suppressed patterns, and privacy-drop guards. Its
catalogs also check every one of the 13 logical indicator definitions and 10 switched-ERM patterns
exactly against `contract.json`.

The zero-dependency protocol integrity test beside the artifacts runs before any consumer-specific
interpretation is needed. It rejects silent JSON drift, including fixture values that are absent from
the device-owned state axes. Its checks remain proposal-level evidence and make no firmware or physical
conformance claim.

The simulator now also composes those existing owners for all 12 gesture-bearing cases. Starting from
their accepted, debounced button-edge inputs, it preserves one exact ordered trace through gesture and
command recognition, explicit hardware-effect completions, capture truth, indicator transitions,
haptics, and terminal state. The raw-bounce fixture remains the debouncer's separate input-layer proof,
because the corpus does not claim a gesture/output trace for that raw sample sequence.

These are simulator/reference-oracle results only. Effect completions are deterministic fixture inputs,
not emulated PDM, flash, radio, RGB, or motor drivers. They are not evidence that stock or custom
firmware implements the proposed behavior.

Two output limits remain explicit instead of being guessed: the contract does not yet order simultaneous
`LOW_POWER` and `CHARGING` candidates inside their shared priority tier, and a failed privacy-output
driver cannot provide a trustworthy visual fatal signal. The reference arbiter reports the former as an
unresolved same-priority decision and locks out lower visual patterns for the latter while retaining the
named `FAULT` haptic and shell fault semantics.

The complete consumer set remains:

1. the CV1 firmware gesture/capture/feedback tests;
2. the deterministic CV1 simulator;
3. CV1 driver mapping tests;
4. runtime projection/property tests;
5. Android/Linux shell projection tests; and
6. hardware-in-loop timing and output capture.

All portable consumers must accept a different future device mapping without importing CV1 colors or
timings into generic runtime policy.
