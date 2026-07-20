package dev.gumi.devices.omicv1.simulator.humanio

data class OmiCv1IntegratedSemanticEvent(
    val atMillis: Long,
    val type: String,
    val operation: String? = null,
    val reason: String? = null,
    val value: String? = null,
    val recordingId: String? = null,
)

/**
 * Composition-only reference path. Gesture timing remains owned by [OmiCv1GestureRecognizer], while
 * capture truth and feedback remain owned by [OmiCv1LifecycleReference]. This class only forwards a
 * recognized semantic command to that existing owner and preserves one ordered trace.
 */
class OmiCv1HumanIoIntegrationReference(
    context: OmiCv1GestureContext,
    initialState: OmiCv1HumanIoState,
    realtimeAdmissionAvailable: Boolean = true,
    confirmationOperation: String? = null,
    confirmationLeaseExpiresAtMillis: Long? = null,
) {
    private val recognizer = OmiCv1GestureRecognizer(
        context = context,
        realtimeAdmissionAvailable = realtimeAdmissionAvailable,
        confirmationOperation = confirmationOperation,
        confirmationLeaseExpiresAtMillis = confirmationLeaseExpiresAtMillis,
    )
    private val lifecycle = OmiCv1LifecycleReference(initialState)
    private val orderedEvents = mutableListOf<OmiCv1IntegratedSemanticEvent>()
    private val outputSteps = mutableListOf<OmiCv1HumanIoStep>()

    var firstAudioPermittedAtMillis: Long? = if (captureIsCommitted(initialState)) 0L else null
        private set

    val events: List<OmiCv1IntegratedSemanticEvent> get() = orderedEvents.toList()
    val steps: List<OmiCv1HumanIoStep> get() = outputSteps.toList()
    val state: OmiCv1HumanIoState get() = lifecycle.state
    val currentIndicator: OmiCv1IndicatorPattern? get() = lifecycle.currentIndicator()
    val shell: OmiCv1HumanIoShellProjection get() = lifecycle.shellProjection()

    fun acceptEdge(atMillis: Long, level: OmiCv1ButtonLevel) {
        forward(recognizer.acceptEdge(atMillis, level))
    }

    fun advanceTo(atMillis: Long) {
        forward(recognizer.advanceTo(atMillis))
    }

    fun applyEffect(input: OmiCv1HumanIoInput) {
        append(lifecycle.apply(input))
    }

    private fun forward(events: List<OmiCv1GestureEvent>) {
        events.forEach { gesture ->
            orderedEvents += OmiCv1IntegratedSemanticEvent(
                atMillis = gesture.atMillis,
                type = gesture.type.wireName,
                operation = gesture.operation,
                reason = gesture.reason,
            )
            gesture.lifecycleInput()?.let { append(lifecycle.apply(it)) }
        }
    }

    private fun OmiCv1GestureEvent.lifecycleInput(): OmiCv1HumanIoInput? {
        val inputType = when (type) {
            OmiCv1GestureEventType.REPEAT_STATUS -> OmiCv1HumanIoInputType.STATUS_REPEAT_REQUESTED
            OmiCv1GestureEventType.START_BASE_RECORDING_REQUESTED ->
                OmiCv1HumanIoInputType.START_BASE_RECORDING_REQUESTED
            OmiCv1GestureEventType.STOP_BASE_RECORDING_REQUESTED ->
                OmiCv1HumanIoInputType.STOP_BASE_RECORDING_REQUESTED
            OmiCv1GestureEventType.START_VOICE_TURN_REQUESTED ->
                OmiCv1HumanIoInputType.START_VOICE_TURN_REQUESTED
            OmiCv1GestureEventType.START_VOICE_TURN_OVERLAY_REQUESTED ->
                OmiCv1HumanIoInputType.START_VOICE_TURN_OVERLAY_REQUESTED
            OmiCv1GestureEventType.END_VOICE_TURN_REQUESTED ->
                OmiCv1HumanIoInputType.END_VOICE_TURN_REQUESTED
            OmiCv1GestureEventType.VOICE_TURN_REFUSED -> OmiCv1HumanIoInputType.VOICE_TURN_REFUSED
            OmiCv1GestureEventType.SINGLE_TAP,
            OmiCv1GestureEventType.DOUBLE_TAP,
            OmiCv1GestureEventType.HOLD_COMMITTED,
            OmiCv1GestureEventType.HOLD_RELEASED,
            OmiCv1GestureEventType.PHYSICAL_CONFIRMATION,
            -> return null
        }
        return OmiCv1HumanIoInput(
            atMillis = atMillis,
            type = inputType,
            reason = reason,
        )
    }

    private fun append(step: OmiCv1HumanIoStep) {
        outputSteps += step
        step.semanticEvents.forEach { event ->
            orderedEvents += OmiCv1IntegratedSemanticEvent(
                atMillis = event.atMillis,
                type = event.type.wireName,
                reason = event.reason,
                value = event.value,
                recordingId = event.recordingId,
            )
        }
        if (
            firstAudioPermittedAtMillis == null &&
            step.semanticEvents.any {
                it.type == OmiCv1LifecycleEventType.BASE_RECORDING_STARTED ||
                    it.type == OmiCv1LifecycleEventType.VOICE_TURN_STARTED
            }
        ) {
            firstAudioPermittedAtMillis = step.atMillis
        }
    }

    private companion object {
        fun captureIsCommitted(state: OmiCv1HumanIoState): Boolean =
            state.baseRecording == OmiCv1BaseRecording.ACTIVE ||
                state.voiceTurn == OmiCv1VoiceTurn.ACTIVE
    }
}
