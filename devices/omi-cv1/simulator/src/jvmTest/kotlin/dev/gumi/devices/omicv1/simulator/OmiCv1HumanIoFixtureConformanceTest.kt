package dev.gumi.devices.omicv1.simulator

import dev.gumi.devices.omicv1.simulator.humanio.OMI_CV1_CONFIRMATION_HOLD_MILLIS
import dev.gumi.devices.omicv1.simulator.humanio.OMI_CV1_DEBOUNCE_MILLIS
import dev.gumi.devices.omicv1.simulator.humanio.OMI_CV1_DOUBLE_TAP_WINDOW_MILLIS
import dev.gumi.devices.omicv1.simulator.humanio.OMI_CV1_HOLD_MILLIS
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1AcceptedButtonEdge
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1ButtonLevel
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1GestureContext
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1GestureEvent
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1GestureRecognizer
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1StableButtonDebouncer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class OmiCv1HumanIoFixtureConformanceTest {
    @Test
    fun `the twenty-case corpus is explicitly partitioned across executable reference owners`() {
        val fixture = resource("/fixtures.json")
        val allCases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        val allIds = allCases.map { it.stringAt("id") }
        val debounceIds = allCases
            .filter { it.stringAt("operation") == "debounce" }
            .map { it.stringAt("id") }
            .toSet()
        val recognizerProjectionIds = allCases
            .filter { it.stringAt("operation").startsWith("gesture") }
            .map { it.stringAt("id") }
            .toSet()
        val projectedIds = debounceIds + recognizerProjectionIds

        assertEquals("gumi.device-human-io-fixtures/v1", fixture.stringAt("schema"))
        assertEquals("gumi.omi-cv1-human-io/v1", fixture.stringAt("protocol"))
        assertEquals("proposed_unqualified", fixture.stringAt("status"))
        assertEquals(
            true,
            fixture.objectAt("conventions")
                .booleanAt("semantic_event_traces_are_exhaustive_per_consumer_owner"),
        )
        assertEquals(20, allIds.size)
        assertEquals(20, allIds.toSet().size, "fixture case ids must be unique")
        assertEquals(setOf(DEBOUNCE_EXECUTED_CASE_ID), debounceIds)
        assertEquals(RECOGNIZER_PROJECTED_CASE_IDS, recognizerProjectionIds)
        assertEquals(LIFECYCLE_OUTPUT_EXECUTED_CASE_IDS, allIds.toSet() - projectedIds)
        assertEquals(
            allIds.toSet(),
            debounceIds + recognizerProjectionIds + LIFECYCLE_OUTPUT_EXECUTED_CASE_IDS,
        )
    }

    @Test
    fun `reference constants and precedence are sourced from the device-owned contract`() {
        val contract = resource("/contract.json")
        val timing = contract.objectAt("timing_ms")

        assertEquals(OMI_CV1_DEBOUNCE_MILLIS, timing.longAt("debounce_stable"))
        assertEquals(
            OMI_CV1_DOUBLE_TAP_WINDOW_MILLIS,
            timing.longAt("double_tap_window_after_first_release"),
        )
        assertEquals(OMI_CV1_HOLD_MILLIS, timing.longAt("hold_deadline_after_press"))
        assertEquals(OMI_CV1_CONFIRMATION_HOLD_MILLIS, timing.longAt("maintenance_confirmation_hold"))
        assertEquals(true, timing.booleanAt("double_tap_window_inclusive"))
        assertEquals(true, timing.booleanAt("hold_deadline_inclusive"))
        assertEquals(false, timing.booleanAt("maintenance_confirmation_lease_expiry_inclusive"))
        assertEquals(
            listOf("accepted_button_edges", "gesture_deadlines"),
            timing.getValue("same_timestamp_precedence").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `the checked debounce fixture executes byte-for-byte through the reference debouncer`() {
        val case = cases().single { it.stringAt("operation") == "debounce" }
        val subject = OmiCv1StableButtonDebouncer(
            initialLevel = case.stringAt("initial_level").buttonLevel(),
        )
        val actual = buildList {
            case.arrayAt("inputs").forEach { element ->
                val input = element.jsonObject
                addAll(subject.onRawLevel(input.longAt("at_ms"), input.stringAt("value").buttonLevel()))
            }
            val expectedLast = case.objectAt("expected").arrayAt("accepted_edges")
                .maxOf { it.jsonObject.longAt("at_ms") }
            addAll(subject.advanceTo(expectedLast))
        }
        val expected = case.objectAt("expected").arrayAt("accepted_edges").map { element ->
            val edge = element.jsonObject
            OmiCv1AcceptedButtonEdge(
                atMillis = edge.longAt("at_ms"),
                level = when (edge.stringAt("type")) {
                    "button_down" -> OmiCv1ButtonLevel.PRESSED
                    "button_up" -> OmiCv1ButtonLevel.RELEASED
                    else -> error("Unknown accepted edge ${edge.stringAt("type")}")
                },
            )
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `every recognizer-owned fixture has an exact deterministic event trace`() {
        val gestureCases = cases().filter { it.stringAt("operation").startsWith("gesture") }
        assertEquals(12, gestureCases.size)

        gestureCases.forEach { case ->
            val events = execute(case)
            val expected = case.objectAt("expected").optionalArray("semantic_events")
                .map { it.jsonObject.expectedGestureEvent() }
                .filter { it.type in MODEL_OWNED_EVENT_TYPES }
            val actual = events.map(OmiCv1GestureEvent::fixtureProjection)
            assertEquals(expected, actual, "${case.stringAt("id")}: recognizer event trace")

            val forbidden = case.objectAt("expected").optionalArray("forbidden")
                .map { it.jsonPrimitive.content }
                .toSet()
            val violation = actual.firstOrNull { it.type in forbidden }
            assertEquals(null, violation, "${case.stringAt("id")}: produced forbidden $violation")
        }
    }

    private fun execute(case: JsonObject): List<OmiCv1GestureEvent> {
        val initial = case.objectAt("initial")
        val maintenance = initial.optionalString("maintenance") ?: "normal"
        val context = when {
            maintenance == "awaiting_confirmation" -> OmiCv1GestureContext.AWAITING_CONFIRMATION
            maintenance != "normal" -> OmiCv1GestureContext.MAINTENANCE_EXCLUSIVE
            initial.optionalString("base_recording") == "active" -> OmiCv1GestureContext.NORMAL_RECORDING
            else -> OmiCv1GestureContext.NORMAL_IDLE
        }
        val subject = OmiCv1GestureRecognizer(
            context = context,
            realtimeAdmissionAvailable = initial.optionalString("realtime_admission_lease") != "absent",
            confirmationOperation = initial.optionalString("confirmation_operation"),
            confirmationLeaseExpiresAtMillis = initial.optionalLong("confirmation_lease_expires_at_ms"),
        )
        val inputs = case.arrayAt("inputs").map { it.jsonObject }
        val events = mutableListOf<OmiCv1GestureEvent>()
        inputs.groupBy { it.longAt("at_ms") }.toSortedMap().forEach { (atMillis, atTime) ->
            atTime.filter { it.stringAt("type") == "button_down" || it.stringAt("type") == "button_up" }
                .forEach { input ->
                    events += subject.acceptEdge(
                        atMillis,
                        if (input.stringAt("type") == "button_down") {
                            OmiCv1ButtonLevel.PRESSED
                        } else {
                            OmiCv1ButtonLevel.RELEASED
                        },
                    )
                }
            events += subject.advanceTo(atMillis)
        }
        val expectedLast = case.objectAt("expected").optionalArray("semantic_events")
            .maxOfOrNull { it.jsonObject.longAt("at_ms") }
        val lastInput = inputs.maxOfOrNull { it.longAt("at_ms") } ?: 0L
        if (expectedLast != null && expectedLast > lastInput) events += subject.advanceTo(expectedLast)
        return events
    }

    private fun cases(): List<JsonObject> = resource("/fixtures.json")
        .getValue("cases")
        .jsonArray
        .map { it.jsonObject }

    private fun resource(path: String): JsonObject = checkNotNull(javaClass.getResourceAsStream(path))
        .bufferedReader()
        .use { Json.parseToJsonElement(it.readText()).jsonObject }
}

private data class FixtureEvent(
    val atMillis: Long,
    val type: String,
    val operation: String? = null,
    val reason: String? = null,
)

private fun JsonObject.expectedGestureEvent() = FixtureEvent(
    atMillis = longAt("at_ms"),
    type = stringAt("type"),
    operation = optionalString("operation"),
    reason = optionalString("reason"),
)

private fun OmiCv1GestureEvent.fixtureProjection() = FixtureEvent(
    atMillis = atMillis,
    type = type.wireName,
    operation = operation,
    reason = reason,
)

private fun JsonObject.stringAt(name: String) = getValue(name).jsonPrimitive.content
private fun JsonObject.longAt(name: String) = stringAt(name).toLong()
private fun JsonObject.booleanAt(name: String) = stringAt(name).toBooleanStrict()
private fun JsonObject.objectAt(name: String) = getValue(name).jsonObject
private fun JsonObject.arrayAt(name: String) = getValue(name).jsonArray
private fun JsonObject.optionalString(name: String) = get(name)?.jsonPrimitive?.content
private fun JsonObject.optionalLong(name: String) = optionalString(name)?.toLong()
private fun JsonObject.optionalArray(name: String): JsonArray = get(name)?.jsonArray ?: JsonArray(emptyList())

private fun String.buttonLevel() = when (this) {
    "pressed" -> OmiCv1ButtonLevel.PRESSED
    "released" -> OmiCv1ButtonLevel.RELEASED
    else -> error("Unknown button level $this")
}

private val MODEL_OWNED_EVENT_TYPES = setOf(
    "single_tap",
    "double_tap",
    "hold_committed",
    "hold_released",
    "repeat_status",
    "start_base_recording_requested",
    "stop_base_recording_requested",
    "start_voice_turn_requested",
    "start_voice_turn_overlay_requested",
    "end_voice_turn_requested",
    "voice_turn_refused",
    "physical_confirmation",
)

private const val DEBOUNCE_EXECUTED_CASE_ID = "debounce-collapses-switch-bounce"

private val RECOGNIZER_PROJECTED_CASE_IDS = setOf(
    "single-tap-repeats-status-without-capture",
    "double-tap-window-inclusive-at-350-ms",
    "second-press-after-351-ms-does-not-retroactively-double",
    "release-at-exact-hold-deadline-wins",
    "hold-acquires-voice-turn-only-after-all-effects",
    "tap-then-held-second-press-becomes-hold-not-double",
    "double-tap-starts-recording-after-hardware-commit",
    "double-tap-stops-recording-only-after-release",
    "voice-turn-over-recording-returns-to-same-recording",
    "voice-turn-without-admission-is-refused-mic-off",
    "maintenance-update-ignores-capture-gestures",
    "contextual-maintenance-hold-confirms-at-2000-ms",
)

internal val LIFECYCLE_OUTPUT_EXECUTED_CASE_IDS = setOf(
    "link-loss-does-not-stop-active-recording",
    "disconnect-invalidates-verified-idle-projection",
    "privacy-guard-failure-rejects-recording-acquisition",
    "storage-full-stops-at-durable-boundary",
    "fault-during-recording-cannot-blink-privacy-dark",
    "watchdog-restart-never-resumes-recording",
    "aad-armed-idle-remains-distinct-from-capture",
)
