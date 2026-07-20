package dev.gumi.devices.omicv1.simulator.humanio

const val OMI_CV1_DEBOUNCE_MILLIS = 30L
const val OMI_CV1_DOUBLE_TAP_WINDOW_MILLIS = 350L
const val OMI_CV1_HOLD_MILLIS = 500L
const val OMI_CV1_CONFIRMATION_HOLD_MILLIS = 2_000L

enum class OmiCv1ButtonLevel {
    RELEASED,
    PRESSED,
}

data class OmiCv1AcceptedButtonEdge(
    val atMillis: Long,
    val level: OmiCv1ButtonLevel,
)

/**
 * Pure reference debouncer for the proposed CV1 firmware contract. It is an executable oracle, not a
 * claim about stock firmware and not an edge-side replacement for device-local gesture recognition.
 */
class OmiCv1StableButtonDebouncer(
    private val stableMillis: Long = OMI_CV1_DEBOUNCE_MILLIS,
    initialLevel: OmiCv1ButtonLevel = OmiCv1ButtonLevel.RELEASED,
) {
    init {
        require(stableMillis > 0)
    }

    private var observedLevel = initialLevel
    private var acceptedLevel = initialLevel
    private var observedSinceMillis = 0L
    private var currentMillis = 0L

    fun onRawLevel(atMillis: Long, level: OmiCv1ButtonLevel): List<OmiCv1AcceptedButtonEdge> {
        require(atMillis >= currentMillis) { "Raw button time must be monotonic" }
        val emitted = advanceTo(atMillis)
        if (level != observedLevel) {
            observedLevel = level
            observedSinceMillis = atMillis
        }
        currentMillis = atMillis
        return emitted
    }

    fun advanceTo(atMillis: Long): List<OmiCv1AcceptedButtonEdge> {
        require(atMillis >= currentMillis) { "Debounce time must be monotonic" }
        currentMillis = atMillis
        if (observedLevel == acceptedLevel) return emptyList()
        val deadline = observedSinceMillis + stableMillis
        if (deadline > atMillis) return emptyList()
        acceptedLevel = observedLevel
        return listOf(OmiCv1AcceptedButtonEdge(deadline, acceptedLevel))
    }
}

enum class OmiCv1GestureContext {
    NORMAL_IDLE,
    NORMAL_RECORDING,
    MAINTENANCE_EXCLUSIVE,
    AWAITING_CONFIRMATION,
    FATAL_PRIVACY,
}

enum class OmiCv1GestureEventType(val wireName: String) {
    SINGLE_TAP("single_tap"),
    DOUBLE_TAP("double_tap"),
    HOLD_COMMITTED("hold_committed"),
    HOLD_RELEASED("hold_released"),
    REPEAT_STATUS("repeat_status"),
    START_BASE_RECORDING_REQUESTED("start_base_recording_requested"),
    STOP_BASE_RECORDING_REQUESTED("stop_base_recording_requested"),
    START_VOICE_TURN_REQUESTED("start_voice_turn_requested"),
    START_VOICE_TURN_OVERLAY_REQUESTED("start_voice_turn_overlay_requested"),
    END_VOICE_TURN_REQUESTED("end_voice_turn_requested"),
    VOICE_TURN_REFUSED("voice_turn_refused"),
    PHYSICAL_CONFIRMATION("physical_confirmation"),
}

data class OmiCv1GestureEvent(
    val atMillis: Long,
    val type: OmiCv1GestureEventType,
    val operation: String? = null,
    val reason: String? = null,
)

/**
 * Deterministic proposed-Gumi gesture grammar. Accepted edges at time T are handled before deadlines
 * at T, making the inclusive double window and release-wins hold boundary deterministic. A caller
 * must therefore submit every accepted edge at T before calling [advanceTo] for T. Stale or duplicate
 * accepted edges fail before time or recognizer state advances.
 */
class OmiCv1GestureRecognizer(
    private val context: OmiCv1GestureContext,
    private val realtimeAdmissionAvailable: Boolean = true,
    private val confirmationOperation: String? = null,
    private val confirmationLeaseExpiresAtMillis: Long? = null,
) {
    init {
        require(context == OmiCv1GestureContext.AWAITING_CONFIRMATION || confirmationOperation == null) {
            "Only AwaitingConfirmation may own a confirmation operation"
        }
        require(context == OmiCv1GestureContext.AWAITING_CONFIRMATION || confirmationLeaseExpiresAtMillis == null) {
            "Only AwaitingConfirmation may own a confirmation lease"
        }
        if (context == OmiCv1GestureContext.AWAITING_CONFIRMATION) {
            require(!confirmationOperation.isNullOrBlank())
            require(confirmationLeaseExpiresAtMillis != null && confirmationLeaseExpiresAtMillis >= 0)
        }
    }

    private var currentMillis = 0L
    private var pressedAtMillis: Long? = null
    private var firstTapReleasedAtMillis: Long? = null
    private var secondPress = false
    private var holdCommitted = false
    private var confirmationCommitted = false
    private var deadlinesAdvancedAtCurrentMillis = false

    fun acceptEdge(atMillis: Long, level: OmiCv1ButtonLevel): List<OmiCv1GestureEvent> {
        require(atMillis >= currentMillis) { "Accepted button time must be monotonic" }
        require(atMillis != currentMillis || !deadlinesAdvancedAtCurrentMillis) {
            "Accepted button edges at a timestamp must precede gesture deadlines"
        }
        when (level) {
            OmiCv1ButtonLevel.PRESSED -> require(pressedAtMillis == null) {
                "Button-down cannot repeat before button-up"
            }
            OmiCv1ButtonLevel.RELEASED -> require(pressedAtMillis != null) {
                "Button-up requires a preceding button-down"
            }
        }
        val emitted = processDeadlinesBefore(atMillis).toMutableList()
        currentMillis = atMillis
        deadlinesAdvancedAtCurrentMillis = false
        when (level) {
            OmiCv1ButtonLevel.PRESSED -> onPressed(atMillis)
            OmiCv1ButtonLevel.RELEASED -> emitted += onReleased(atMillis)
        }
        return emitted
    }

    fun advanceTo(atMillis: Long): List<OmiCv1GestureEvent> {
        require(atMillis >= currentMillis) { "Gesture time must be monotonic" }
        val emitted = processDeadlines(atMillis, inclusive = true)
        currentMillis = atMillis
        deadlinesAdvancedAtCurrentMillis = true
        return emitted
    }

    private fun onPressed(atMillis: Long) {
        require(pressedAtMillis == null) { "Button-down cannot repeat before button-up" }
        pressedAtMillis = atMillis
        holdCommitted = false
        confirmationCommitted = false
        secondPress = context.isNormal && firstTapReleasedAtMillis?.let { release ->
            atMillis <= release + OMI_CV1_DOUBLE_TAP_WINDOW_MILLIS
        } == true
    }

    private fun onReleased(atMillis: Long): List<OmiCv1GestureEvent> {
        val pressedAt = requireNotNull(pressedAtMillis) { "Button-up requires a preceding button-down" }
        pressedAtMillis = null

        if (context == OmiCv1GestureContext.AWAITING_CONFIRMATION) {
            confirmationCommitted = false
            return emptyList()
        }
        if (!context.isNormal) return emptyList()
        if (holdCommitted) {
            holdCommitted = false
            secondPress = false
            firstTapReleasedAtMillis = null
            if (!realtimeAdmissionAvailable) return emptyList()
            return listOf(
                event(atMillis, OmiCv1GestureEventType.HOLD_RELEASED),
                event(atMillis, OmiCv1GestureEventType.END_VOICE_TURN_REQUESTED),
            )
        }
        require(atMillis - pressedAt <= OMI_CV1_HOLD_MILLIS) {
            "A press that passed its hold deadline must be advanced before release"
        }
        if (secondPress) {
            secondPress = false
            firstTapReleasedAtMillis = null
            val action = if (context == OmiCv1GestureContext.NORMAL_RECORDING) {
                OmiCv1GestureEventType.STOP_BASE_RECORDING_REQUESTED
            } else {
                OmiCv1GestureEventType.START_BASE_RECORDING_REQUESTED
            }
            return listOf(
                event(atMillis, OmiCv1GestureEventType.DOUBLE_TAP),
                event(atMillis, action),
            )
        }
        firstTapReleasedAtMillis = atMillis
        return emptyList()
    }

    private fun processDeadlinesBefore(atMillis: Long): List<OmiCv1GestureEvent> =
        processDeadlines(atMillis, inclusive = false)

    private fun processDeadlines(
        atMillis: Long,
        inclusive: Boolean,
    ): List<OmiCv1GestureEvent> {
        val emitted = mutableListOf<OmiCv1GestureEvent>()
        fun due(deadline: Long) = if (inclusive) deadline <= atMillis else deadline < atMillis

        val pressedAt = pressedAtMillis
        if (pressedAt != null && !holdCommitted && !confirmationCommitted) {
            val duration = if (context == OmiCv1GestureContext.AWAITING_CONFIRMATION) {
                OMI_CV1_CONFIRMATION_HOLD_MILLIS
            } else {
                OMI_CV1_HOLD_MILLIS
            }
            val deadline = pressedAt + duration
            if (due(deadline)) {
                if (context == OmiCv1GestureContext.AWAITING_CONFIRMATION) {
                    confirmationCommitted = true
                    if (deadline < requireNotNull(confirmationLeaseExpiresAtMillis)) {
                        emitted += event(
                            deadline,
                            OmiCv1GestureEventType.PHYSICAL_CONFIRMATION,
                            operation = confirmationOperation,
                        )
                    }
                } else if (context.isNormal) {
                    holdCommitted = true
                    firstTapReleasedAtMillis = null
                    secondPress = false
                    emitted += event(deadline, OmiCv1GestureEventType.HOLD_COMMITTED)
                    if (realtimeAdmissionAvailable) {
                        emitted += event(
                            deadline,
                            if (context == OmiCv1GestureContext.NORMAL_RECORDING) {
                                OmiCv1GestureEventType.START_VOICE_TURN_OVERLAY_REQUESTED
                            } else {
                                OmiCv1GestureEventType.START_VOICE_TURN_REQUESTED
                            },
                        )
                    } else {
                        emitted += event(
                            deadline,
                            OmiCv1GestureEventType.VOICE_TURN_REFUSED,
                            reason = "realtime_admission_unavailable",
                        )
                    }
                }
            }
        }

        val firstRelease = firstTapReleasedAtMillis
        if (pressedAtMillis == null && firstRelease != null) {
            val deadline = firstRelease + OMI_CV1_DOUBLE_TAP_WINDOW_MILLIS
            if (due(deadline)) {
                firstTapReleasedAtMillis = null
                emitted += event(deadline, OmiCv1GestureEventType.SINGLE_TAP)
                emitted += event(deadline, OmiCv1GestureEventType.REPEAT_STATUS)
            }
        }
        return emitted
    }

    private fun event(
        atMillis: Long,
        type: OmiCv1GestureEventType,
        operation: String? = null,
        reason: String? = null,
    ) = OmiCv1GestureEvent(atMillis, type, operation, reason)
}

private val OmiCv1GestureContext.isNormal: Boolean
    get() = this == OmiCv1GestureContext.NORMAL_IDLE || this == OmiCv1GestureContext.NORMAL_RECORDING
