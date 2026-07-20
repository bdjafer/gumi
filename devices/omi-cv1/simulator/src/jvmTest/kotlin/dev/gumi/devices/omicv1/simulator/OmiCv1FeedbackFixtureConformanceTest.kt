package dev.gumi.devices.omicv1.simulator

import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1AcousticDetector
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1BaseRecording
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1Fault
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1HapticCatalog
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1HapticPattern
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1HumanIoInput
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1HumanIoInputType
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1HumanIoState
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1HumanIoStep
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1IndicatorCatalog
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1IndicatorDefinition
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1IndicatorLevel
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1IndicatorPattern
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1IndicatorSegment
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1IndicatorShape
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1LifecycleEvent
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1LifecycleReference
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1Link
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1LogicalColor
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1Maintenance
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1MicTruth
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1MotorSegment
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1Power
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1ShellCapture
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1Storage
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1VoiceTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmiCv1FeedbackFixtureConformanceTest {
    @Test
    fun `logical indicator and switched-motor catalogs exactly match the device contract`() {
        val contract = resource("/contract.json")
        val contractIndicators = contract.objectAt("indicator").objectAt("patterns")
        val contractHaptics = contract.objectAt("haptic").objectAt("patterns")
        val unqualifiedLimits = contract.objectAt("feedback_arbitration")
            .objectAt("explicit_unqualified_limits")

        assertEquals(
            OmiCv1IndicatorPattern.entries.mapTo(linkedSetOf()) { it.wireName },
            contractIndicators.keys,
        )
        assertEquals(
            OmiCv1HapticPattern.entries.mapTo(linkedSetOf()) { it.wireName },
            contractHaptics.keys,
        )
        assertEquals(
            "unresolved_same_priority_requires_protocol_revision",
            unqualifiedLimits.stringAt("simultaneous_low_power_and_charging"),
        )
        assertEquals(
            "no_trustworthy_visual_signal_available_lock_out_lower_visual_patterns",
            unqualifiedLimits.stringAt("privacy_output_driver_failed"),
        )
        OmiCv1IndicatorPattern.entries.forEach { pattern ->
            assertEquals(
                contractIndicators.objectAt(pattern.wireName).indicatorDefinition(pattern),
                OmiCv1IndicatorCatalog.definitions.getValue(pattern),
                pattern.wireName,
            )
        }
        OmiCv1HapticPattern.entries.forEach { pattern ->
            assertEquals(
                contractHaptics.arrayAt(pattern.wireName).map { segment ->
                    val value = segment.jsonObject
                    if ("on_ms" in value) {
                        OmiCv1MotorSegment(true, value.longAt("on_ms"))
                    } else {
                        OmiCv1MotorSegment(false, value.longAt("off_ms"))
                    }
                },
                OmiCv1HapticCatalog.definitions.getValue(pattern),
                pattern.wireName,
            )
        }
    }

    @Test
    fun `all seven lifecycle fault and arbitration fixtures execute through their owning model`() {
        val cases = resource("/fixtures.json").arrayAt("cases")
            .map { it.jsonObject }
            .filter { it.stringAt("id") in LIFECYCLE_OUTPUT_EXECUTED_CASE_IDS }
        assertEquals(7, cases.size)

        cases.forEach(::assertCase)
    }

    private fun assertCase(case: JsonObject) {
        val expected = case.objectAt("expected")
        val subject = OmiCv1LifecycleReference(case.objectAt("initial").initialState())
        val initialIndicator = subject.currentIndicator()
        val steps = case.arrayAt("inputs").map { element ->
            val input = element.jsonObject
            subject.apply(
                OmiCv1HumanIoInput(
                    atMillis = input.longAt("at_ms"),
                    type = OmiCv1HumanIoInputType.entries.single {
                        it.wireName == input.stringAt("type")
                    },
                    value = input.optionalString("value"),
                    reason = input.optionalString("reason"),
                ),
            )
        }

        val actualEvents = steps.flatMap(OmiCv1HumanIoStep::semanticEvents)
        val expectedEvents = expected.optionalArray("semantic_events").map { element ->
            val event = element.jsonObject
            OmiCv1LifecycleEvent(
                atMillis = event.longAt("at_ms"),
                type = dev.gumi.devices.omicv1.simulator.humanio.OmiCv1LifecycleEventType.entries
                    .single { it.wireName == event.stringAt("type") },
                value = event.optionalString("value"),
                reason = event.optionalString("reason"),
                recordingId = event.optionalString("recording_id"),
            )
        }
        assertEquals(expectedEvents, actualEvents, "${case.stringAt("id")}: semantic events")

        expected.optionalObject("terminal")?.let { terminal ->
            terminal.forEach { (key, expectedValue) ->
                val actual = terminalValue(subject.state, subject.currentIndicator(), key)
                assertEquals(
                    expectedValue.jsonPrimitive.content,
                    actual,
                    "${case.stringAt("id")}: terminal $key",
                )
            }
        }
        expected.optionalObject("shell")?.let { shell ->
            val actual = subject.shellProjection()
            shell.forEach { (key, expectedValue) ->
                val value = when (key) {
                    "capture" -> actual.capture.wireName
                    "label" -> actual.label
                    "secondary_label" -> actual.secondaryLabel
                    "link" -> actual.link.wireName
                    "fault" -> actual.faultReason
                    else -> error("Unhandled shell fixture field $key")
                }
                assertEquals(
                    expectedValue.jsonPrimitive.content,
                    value,
                    "${case.stringAt("id")}: shell $key",
                )
            }
        }

        val actualHaptics = steps.flatMap { step ->
            step.haptics.map { step.atMillis to it.wireName }
        }
        val expectedHaptics = expected.optionalArray("output_patterns").mapNotNull { element ->
            val output = element.jsonObject
            output.optionalString("haptic")?.let { output.longAt("at_ms") to it }
        }
        assertEquals(expectedHaptics, actualHaptics, "${case.stringAt("id")}: haptics")

        val transitions = steps.mapNotNull(OmiCv1HumanIoStep::indicatorTransition)
        fun indicatorAt(atMillis: Long, inclusive: Boolean): OmiCv1IndicatorPattern? {
            val transition = transitions.lastOrNull {
                if (inclusive) it.atMillis <= atMillis else it.atMillis < atMillis
            }
            return transition?.current ?: initialIndicator
        }
        expected.optionalArray("output_patterns").forEach { element ->
            val output = element.jsonObject
            val expectedIndicator = output.optionalString("indicator") ?: return@forEach
            val pattern = expectedIndicator.takeUnless { it == "off" }?.let { wireName ->
                OmiCv1IndicatorPattern.entries.single { it.wireName == wireName }
            }
            output.optionalLong("from_ms")?.let { from ->
                assertEquals(
                    pattern,
                    indicatorAt(from, inclusive = true),
                    "${case.stringAt("id")}: indicator from $from",
                )
            }
            output.optionalLong("until_ms")?.let { until ->
                assertEquals(
                    pattern,
                    indicatorAt(until, inclusive = false),
                    "${case.stringAt("id")}: indicator until $until",
                )
            }
            output.optionalLong("at_ms")?.let { at ->
                val step = steps.lastOrNull { it.atMillis == at }
                assertEquals(
                    pattern,
                    step?.outputDecision?.selected ?: indicatorAt(at, inclusive = true),
                    "${case.stringAt("id")}: indicator at $at",
                )
            }
            if ("from_ms" in output && "until_ms" !in output) {
                assertEquals(
                    pattern,
                    subject.currentIndicator(),
                    "${case.stringAt("id")}: terminal indicator interval",
                )
            }
        }

        val expectedSuppressed = expected.optionalArray("suppressed_patterns")
            .mapTo(linkedSetOf()) { it.jsonPrimitive.content }
        val actualSuppressed = steps.flatMapTo(linkedSetOf()) { step ->
            step.outputDecision.suppressed.map(OmiCv1IndicatorPattern::wireName)
        }
        assertEquals(expectedSuppressed, actualSuppressed, "${case.stringAt("id")}: suppressed")

        val forbidden = expected.optionalArray("forbidden").mapTo(linkedSetOf()) { it.jsonPrimitive.content }
        assertTrue(actualEvents.none { it.type.wireName in forbidden }, "${case.stringAt("id")}: forbidden event")
        if ("privacy_guard_off" in forbidden || forbidden.any { it.startsWith("privacy_guard_off_") }) {
            val violatingTransition = steps.firstOrNull { step ->
                step.indicatorTransition?.let { transition ->
                    transition.previous in PRIVACY_PATTERNS &&
                        transition.current !in PRIVACY_PATTERNS &&
                        step.state.micTruth != OmiCv1MicTruth.VERIFIED_OFF
                } == true
            }
            assertNull(violatingTransition, "${case.stringAt("id")}: privacy guard dropped")
        }
        if ("verified_idle_projection" in forbidden || "verified_idle_projection_after_100_ms" in forbidden) {
            assertFalse(subject.shellProjection().capture == OmiCv1ShellCapture.VERIFIED_IDLE)
        }
    }

    private fun JsonObject.initialState() = OmiCv1HumanIoState(
        power = enumValue(optionalString("power"), OmiCv1Power.entries, OmiCv1Power.OPERATIONAL) {
            it.wireName
        },
        micTruth = enumValue(optionalString("mic_truth"), OmiCv1MicTruth.entries, OmiCv1MicTruth.VERIFIED_OFF) {
            it.wireName
        },
        baseRecording = enumValue(
            optionalString("base_recording"),
            OmiCv1BaseRecording.entries,
            OmiCv1BaseRecording.INACTIVE,
        ) { it.wireName },
        baseRecordingId = optionalString("base_recording_id"),
        voiceTurn = enumValue(
            optionalString("voice_turn"),
            OmiCv1VoiceTurn.entries,
            OmiCv1VoiceTurn.INACTIVE,
        ) { it.wireName },
        link = enumValue(optionalString("link"), OmiCv1Link.entries, OmiCv1Link.READY) { it.wireName },
        maintenance = enumValue(
            optionalString("maintenance"),
            OmiCv1Maintenance.entries,
            OmiCv1Maintenance.NORMAL,
        ) { it.wireName },
        storage = enumValue(
            optionalString("storage"),
            OmiCv1Storage.entries,
            OmiCv1Storage.HEALTHY,
        ) { it.wireName },
        fault = enumValue(optionalString("fault"), OmiCv1Fault.entries, OmiCv1Fault.NONE) { it.wireName },
        acousticDetector = enumValue(
            optionalString("acoustic_detector"),
            OmiCv1AcousticDetector.entries,
            OmiCv1AcousticDetector.DISARMED,
        ) { it.wireName },
    )

    private fun terminalValue(
        state: OmiCv1HumanIoState,
        indicator: OmiCv1IndicatorPattern?,
        key: String,
    ): String? = when (key) {
        "power" -> state.power.wireName
        "mic_truth" -> state.micTruth.wireName
        "base_recording" -> state.baseRecording.wireName
        "base_recording_id" -> state.baseRecordingId
        "voice_turn" -> state.voiceTurn.wireName
        "link" -> state.link.wireName
        "maintenance" -> state.maintenance.wireName
        "storage" -> state.storage.wireName
        "fault" -> state.fault.wireName
        "acoustic_detector" -> state.acousticDetector.wireName
        "indicator" -> indicator?.wireName ?: "off"
        else -> error("Unhandled terminal fixture field $key")
    }

    private fun JsonObject.indicatorDefinition(pattern: OmiCv1IndicatorPattern): OmiCv1IndicatorDefinition {
        val repeatRule = when {
            "repeat_until_ms" in this -> "until_${longAt("repeat_until_ms")}_ms"
            stringAt("repeat") == "1" -> "once"
            else -> stringAt("repeat")
        }
        if (optionalString("shape") == "breathe") {
            return OmiCv1IndicatorDefinition(
                pattern = pattern,
                shape = OmiCv1IndicatorShape.BREATHE,
                repeatRule = repeatRule,
                cycleMillis = longAt("cycle_ms"),
                baseColor = logicalColor(stringAt("color")),
                baseLevel = level(stringAt("level")),
            )
        }
        if ("red_base" in this) {
            return OmiCv1IndicatorDefinition(
                pattern = pattern,
                shape = OmiCv1IndicatorShape.RED_BASE_BLUE_MODULATION,
                repeatRule = repeatRule,
                baseColor = OmiCv1LogicalColor.RED,
                baseLevel = OmiCv1IndicatorLevel.PRIVACY_FLOOR_OR_HIGHER,
                blueModulationHz = longAt("blue_modulation_hz").toUInt(),
                redBaseMayTurnOff = getValue("red_may_turn_off").jsonPrimitive.boolean,
            )
        }
        return OmiCv1IndicatorDefinition(
            pattern = pattern,
            shape = OmiCv1IndicatorShape.SEGMENTED,
            repeatRule = repeatRule,
            segments = arrayAt("segments").map { element ->
                val segment = element.jsonObject
                val duration = segment["duration_ms"].let { value ->
                    if (value == null || value is JsonNull) null else value.jsonPrimitive.long
                }
                if ("red" in segment) {
                    OmiCv1IndicatorSegment(
                        duration,
                        OmiCv1LogicalColor.RED,
                        OmiCv1IndicatorLevel.PRIVACY_FLOOR_OR_HIGHER,
                    )
                } else {
                    val color = logicalColor(segment.stringAt("color"))
                    OmiCv1IndicatorSegment(
                        duration,
                        color,
                        if (color == OmiCv1LogicalColor.OFF) {
                            OmiCv1IndicatorLevel.OFF
                        } else {
                            level(segment.stringAt("level"))
                        },
                    )
                }
            },
        )
    }

    private fun logicalColor(value: String): OmiCv1LogicalColor = when (value) {
        "off" -> OmiCv1LogicalColor.OFF
        "red" -> OmiCv1LogicalColor.RED
        "green" -> OmiCv1LogicalColor.GREEN
        "blue" -> OmiCv1LogicalColor.BLUE
        "amber" -> OmiCv1LogicalColor.AMBER
        else -> error("Unknown logical color $value")
    }

    private fun level(value: String): OmiCv1IndicatorLevel = when (value) {
        "status" -> OmiCv1IndicatorLevel.STATUS
        "privacy_floor_or_higher" -> OmiCv1IndicatorLevel.PRIVACY_FLOOR_OR_HIGHER
        else -> error("Unknown indicator level $value")
    }

    private fun resource(path: String): JsonObject = checkNotNull(javaClass.getResourceAsStream(path))
        .bufferedReader()
        .use { Json.parseToJsonElement(it.readText()).jsonObject }

    private companion object {
        val PRIVACY_PATTERNS = setOf(
            OmiCv1IndicatorPattern.PRIVACY_RECORDING,
            OmiCv1IndicatorPattern.PRIVACY_VOICE_TURN,
            OmiCv1IndicatorPattern.PRIVACY_UNKNOWN,
        )
    }
}

private inline fun <T> enumValue(
    wireName: String?,
    values: List<T>,
    fallback: T,
    name: (T) -> String,
): T = wireName?.let { value -> values.single { name(it) == value } } ?: fallback

private fun JsonObject.stringAt(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.longAt(name: String): Long = stringAt(name).toLong()
private fun JsonObject.objectAt(name: String): JsonObject = getValue(name).jsonObject
private fun JsonObject.arrayAt(name: String): JsonArray = getValue(name).jsonArray
private fun JsonObject.optionalString(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
private fun JsonObject.optionalLong(name: String): Long? = optionalString(name)?.toLong()
private fun JsonObject.optionalArray(name: String): JsonArray = get(name)?.jsonArray ?: JsonArray(emptyList())
private fun JsonObject.optionalObject(name: String): JsonObject? = get(name)?.jsonObject
