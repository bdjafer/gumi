package dev.gumi.devices.omicv1.simulator

import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1AcousticDetector
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1BaseRecording
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1ButtonLevel
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1Fault
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1GestureContext
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1HumanIoInput
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1HumanIoInputType
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1HumanIoIntegrationReference
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1HumanIoState
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1IndicatorPattern
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1Link
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1Maintenance
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1MicTruth
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1Power
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1Storage
import dev.gumi.devices.omicv1.simulator.humanio.OmiCv1VoiceTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmiCv1IntegratedHumanIoFixtureConformanceTest {
    @Test
    fun `all twelve gesture cases form one exact recognizer capture and feedback chain`() {
        val cases = resource("/fixtures.json").arrayAt("cases")
            .map { it.jsonObject }
            .filter { it.stringAt("operation").startsWith("gesture") }
        assertEquals(12, cases.size)

        cases.forEach(::assertIntegratedCase)
    }

    private fun assertIntegratedCase(case: JsonObject) {
        val initial = case.objectAt("initial")
        val maintenance = initial.optionalString("maintenance") ?: "normal"
        val context = when {
            maintenance == "awaiting_confirmation" -> OmiCv1GestureContext.AWAITING_CONFIRMATION
            maintenance != "normal" -> OmiCv1GestureContext.MAINTENANCE_EXCLUSIVE
            initial.optionalString("base_recording") == "active" -> OmiCv1GestureContext.NORMAL_RECORDING
            else -> OmiCv1GestureContext.NORMAL_IDLE
        }
        val subject = OmiCv1HumanIoIntegrationReference(
            context = context,
            initialState = initial.initialIntegrationState(),
            realtimeAdmissionAvailable = initial.optionalString("realtime_admission_lease") != "absent",
            confirmationOperation = initial.optionalString("confirmation_operation"),
            confirmationLeaseExpiresAtMillis = initial.optionalLong("confirmation_lease_expires_at_ms"),
        )
        val initialIndicator = subject.currentIndicator
        val inputs = case.arrayAt("inputs").map { it.jsonObject }
        inputs.groupBy { it.longAt("at_ms") }.toSortedMap().forEach { (atMillis, atTime) ->
            atTime.filter { it.stringAt("type") in BUTTON_EDGE_TYPES }.forEach { input ->
                subject.acceptEdge(
                    atMillis,
                    if (input.stringAt("type") == "button_down") {
                        OmiCv1ButtonLevel.PRESSED
                    } else {
                        OmiCv1ButtonLevel.RELEASED
                    },
                )
            }
            subject.advanceTo(atMillis)
            atTime.filter { it.stringAt("type") !in BUTTON_EDGE_TYPES + DEADLINE_MARKERS }
                .forEach { input ->
                    subject.applyEffect(
                        OmiCv1HumanIoInput(
                            atMillis = atMillis,
                            type = OmiCv1HumanIoInputType.entries.single {
                                it.wireName == input.stringAt("type")
                            },
                            value = input.optionalString("value"),
                            reason = input.optionalString("reason"),
                        ),
                    )
                }
        }

        val expected = case.objectAt("expected")
        val expectedLastEvent = expected.optionalArray("semantic_events")
            .maxOfOrNull { it.jsonObject.longAt("at_ms") }
        val lastInput = inputs.maxOfOrNull { it.longAt("at_ms") } ?: 0L
        if (expectedLastEvent != null && expectedLastEvent > lastInput) {
            subject.advanceTo(expectedLastEvent)
        }

        val expectedEvents = expected.optionalArray("semantic_events").map { element ->
            val event = element.jsonObject
            IntegratedFixtureEvent(
                atMillis = event.longAt("at_ms"),
                type = event.stringAt("type"),
                operation = event.optionalString("operation"),
                reason = event.optionalString("reason"),
            )
        }
        val actualEvents = subject.events.map { event ->
            IntegratedFixtureEvent(
                atMillis = event.atMillis,
                type = event.type,
                operation = event.operation,
                reason = event.reason,
            )
        }
        assertEquals(expectedEvents, actualEvents, "${case.stringAt("id")}: integrated event trace")

        val expectedHaptics = expected.optionalArray("output_patterns").mapNotNull { element ->
            val output = element.jsonObject
            output.optionalString("haptic")?.let { output.longAt("at_ms") to it }
        }
        val actualHaptics = subject.steps.flatMap { step ->
            step.haptics.map { step.atMillis to it.wireName }
        }
        assertEquals(expectedHaptics, actualHaptics, "${case.stringAt("id")}: integrated haptics")

        val transitions = subject.steps.mapNotNull { it.indicatorTransition }
        fun indicatorAt(atMillis: Long, inclusive: Boolean): OmiCv1IndicatorPattern? =
            transitions.lastOrNull {
                if (inclusive) it.atMillis <= atMillis else it.atMillis < atMillis
            }?.current ?: initialIndicator

        expected.optionalArray("output_patterns").forEach { element ->
            val output = element.jsonObject
            val indicatorName = output.optionalString("indicator") ?: return@forEach
            val indicator = indicatorName.takeUnless { it == "off" }?.let { wireName ->
                OmiCv1IndicatorPattern.entries.single { it.wireName == wireName }
            }
            output.optionalLong("from_ms")?.let { from ->
                assertEquals(
                    indicator,
                    indicatorAt(from, inclusive = true),
                    "${case.stringAt("id")}: indicator from $from",
                )
            }
            output.optionalLong("until_ms")?.let { until ->
                assertEquals(
                    indicator,
                    indicatorAt(until, inclusive = false),
                    "${case.stringAt("id")}: indicator until $until",
                )
            }
            output.optionalLong("at_ms")?.let { at ->
                val matchingOutput = subject.steps.lastOrNull {
                    it.atMillis == at && it.outputDecision.selected == indicator
                }
                assertTrue(
                    matchingOutput != null || indicatorAt(at, inclusive = true) == indicator,
                    "${case.stringAt("id")}: indicator at $at",
                )
            }
            if ("from_ms" in output && "until_ms" !in output) {
                assertEquals(
                    indicator,
                    subject.currentIndicator,
                    "${case.stringAt("id")}: terminal indicator",
                )
            }
        }

        expected.optionalObject("terminal")?.forEach { (key, expectedValue) ->
            assertEquals(
                expectedValue.jsonPrimitive.content,
                integratedTerminalValue(subject.state, subject.currentIndicator, key),
                "${case.stringAt("id")}: terminal $key",
            )
        }

        expected.optionalObject("first_audio_frame")?.let { audio ->
            val lowerBound = audio.longAt("at_or_after_ms")
            assertEquals(
                lowerBound,
                subject.firstAudioPermittedAtMillis,
                "${case.stringAt("id")}: capture commitment is the first permitted audio instant",
            )
        }

        val forbidden = expected.optionalArray("forbidden").mapTo(linkedSetOf()) { it.jsonPrimitive.content }
        assertTrue(actualEvents.none { it.type in forbidden }, "${case.stringAt("id")}: forbidden event")
        if (forbidden.any { it == "audio_frame" || it.startsWith("audio_frame_before_") }) {
            val lowerBound = forbidden.firstNotNullOfOrNull { token ->
                AUDIO_BEFORE_MILLIS.matchEntire(token)?.groupValues?.get(1)?.toLong()
            }
            if (lowerBound == null) {
                assertNull(subject.firstAudioPermittedAtMillis, "${case.stringAt("id")}: audio stayed forbidden")
            } else {
                assertTrue(requireNotNull(subject.firstAudioPermittedAtMillis) >= lowerBound)
            }
        }
        if (forbidden.any { it.startsWith("privacy_guard_off") }) {
            val violation = subject.steps.firstOrNull { step ->
                step.indicatorTransition?.let { transition ->
                    transition.previous in PRIVACY_PATTERNS &&
                        transition.current !in PRIVACY_PATTERNS &&
                        step.state.micTruth != OmiCv1MicTruth.VERIFIED_OFF
                } == true
            }
            assertNull(violation, "${case.stringAt("id")}: privacy output remained continuous")
        }
    }

    private fun JsonObject.initialIntegrationState() = OmiCv1HumanIoState(
        power = integrationEnum(optionalString("power"), OmiCv1Power.entries, OmiCv1Power.OPERATIONAL) {
            it.wireName
        },
        micTruth = integrationEnum(
            optionalString("mic_truth"),
            OmiCv1MicTruth.entries,
            OmiCv1MicTruth.VERIFIED_OFF,
        ) { it.wireName },
        baseRecording = integrationEnum(
            optionalString("base_recording"),
            OmiCv1BaseRecording.entries,
            OmiCv1BaseRecording.INACTIVE,
        ) { it.wireName },
        baseRecordingId = optionalString("base_recording_id"),
        voiceTurn = integrationEnum(
            optionalString("voice_turn"),
            OmiCv1VoiceTurn.entries,
            OmiCv1VoiceTurn.INACTIVE,
        ) { it.wireName },
        link = integrationEnum(optionalString("link"), OmiCv1Link.entries, OmiCv1Link.READY) {
            it.wireName
        },
        maintenance = integrationEnum(
            optionalString("maintenance"),
            OmiCv1Maintenance.entries,
            OmiCv1Maintenance.NORMAL,
        ) { it.wireName },
        storage = integrationEnum(
            optionalString("storage"),
            OmiCv1Storage.entries,
            OmiCv1Storage.HEALTHY,
        ) { it.wireName },
        fault = integrationEnum(optionalString("fault"), OmiCv1Fault.entries, OmiCv1Fault.NONE) {
            it.wireName
        },
        acousticDetector = integrationEnum(
            optionalString("acoustic_detector"),
            OmiCv1AcousticDetector.entries,
            OmiCv1AcousticDetector.DISARMED,
        ) { it.wireName },
    )

    private fun resource(path: String): JsonObject = checkNotNull(javaClass.getResourceAsStream(path))
        .bufferedReader()
        .use { Json.parseToJsonElement(it.readText()).jsonObject }

    private companion object {
        val BUTTON_EDGE_TYPES = setOf("button_down", "button_up")
        val DEADLINE_MARKERS = setOf("hold_deadline", "contextual_hold_deadline")
        val AUDIO_BEFORE_MILLIS = Regex("audio_frame_before_([0-9]+)_ms")
        val PRIVACY_PATTERNS = setOf(
            OmiCv1IndicatorPattern.PRIVACY_RECORDING,
            OmiCv1IndicatorPattern.PRIVACY_VOICE_TURN,
            OmiCv1IndicatorPattern.PRIVACY_UNKNOWN,
        )
    }
}

private data class IntegratedFixtureEvent(
    val atMillis: Long,
    val type: String,
    val operation: String? = null,
    val reason: String? = null,
)

private inline fun <T> integrationEnum(
    wireName: String?,
    values: List<T>,
    fallback: T,
    name: (T) -> String,
): T = wireName?.let { value -> values.single { name(it) == value } } ?: fallback

private fun integratedTerminalValue(
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
    else -> error("Unhandled integrated terminal field $key")
}

private fun JsonObject.stringAt(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.longAt(name: String): Long = stringAt(name).toLong()
private fun JsonObject.objectAt(name: String): JsonObject = getValue(name).jsonObject
private fun JsonObject.arrayAt(name: String): JsonArray = getValue(name).jsonArray
private fun JsonObject.optionalString(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
private fun JsonObject.optionalLong(name: String): Long? = optionalString(name)?.toLong()
private fun JsonObject.optionalArray(name: String): JsonArray = get(name)?.jsonArray ?: JsonArray(emptyList())
private fun JsonObject.optionalObject(name: String): JsonObject? = get(name)?.jsonObject
