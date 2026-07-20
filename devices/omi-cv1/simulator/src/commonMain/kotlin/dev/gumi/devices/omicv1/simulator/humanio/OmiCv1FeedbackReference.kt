package dev.gumi.devices.omicv1.simulator.humanio

/**
 * Logical colors from the proposed CV1 contract. These are not PWM values or optical measurements.
 * In particular, AMBER does not claim a qualified red/green channel ratio.
 */
enum class OmiCv1LogicalColor {
    OFF,
    RED,
    GREEN,
    BLUE,
    AMBER,
}

enum class OmiCv1IndicatorLevel {
    OFF,
    STATUS,
    PRIVACY_FLOOR_OR_HIGHER,
}

enum class OmiCv1IndicatorShape {
    SEGMENTED,
    BREATHE,
    RED_BASE_BLUE_MODULATION,
}

enum class OmiCv1IndicatorPattern(val wireName: String) {
    PRIVACY_RECORDING("privacy_recording"),
    PRIVACY_VOICE_TURN("privacy_voice_turn"),
    PRIVACY_UNKNOWN("privacy_unknown"),
    BOOTING("booting"),
    PAIRING("pairing"),
    UPDATING("updating"),
    VALIDATING("validating"),
    RECOVERY_REQUIRED("recovery_required"),
    RECOVERABLE_FAULT("recoverable_fault"),
    CHARGING("charging"),
    LOW_POWER("low_power"),
    READY_LINK_STATUS("ready_link_status"),
    DISCONNECTED_STATUS("disconnected_status"),
}

data class OmiCv1IndicatorSegment(
    val durationMillis: Long?,
    val color: OmiCv1LogicalColor,
    val level: OmiCv1IndicatorLevel,
) {
    init {
        require(durationMillis == null || durationMillis > 0) {
            "A bounded indicator segment must have positive duration"
        }
        require((color == OmiCv1LogicalColor.OFF) == (level == OmiCv1IndicatorLevel.OFF)) {
            "Only an off segment may use the off level"
        }
    }
}

/** Exact logical pattern definition. Hardware PWM calibration remains deliberately absent. */
data class OmiCv1IndicatorDefinition(
    val pattern: OmiCv1IndicatorPattern,
    val shape: OmiCv1IndicatorShape,
    val repeatRule: String,
    val segments: List<OmiCv1IndicatorSegment> = emptyList(),
    val cycleMillis: Long? = null,
    val baseColor: OmiCv1LogicalColor? = null,
    val baseLevel: OmiCv1IndicatorLevel? = null,
    val blueModulationHz: UInt? = null,
    val redBaseMayTurnOff: Boolean? = null,
) {
    init {
        require(repeatRule.isNotBlank())
        require(cycleMillis == null || cycleMillis > 0)
        require(blueModulationHz == null || blueModulationHz > 0u)
        when (shape) {
            OmiCv1IndicatorShape.SEGMENTED -> require(segments.isNotEmpty())
            OmiCv1IndicatorShape.BREATHE -> {
                require(segments.isEmpty() && cycleMillis != null)
                require(baseColor != null && baseLevel != null)
            }
            OmiCv1IndicatorShape.RED_BASE_BLUE_MODULATION -> {
                require(segments.isEmpty() && blueModulationHz != null)
                require(baseColor == OmiCv1LogicalColor.RED)
                require(baseLevel == OmiCv1IndicatorLevel.PRIVACY_FLOOR_OR_HIGHER)
                require(redBaseMayTurnOff == false)
            }
        }
    }
}

object OmiCv1IndicatorCatalog {
    val definitions: Map<OmiCv1IndicatorPattern, OmiCv1IndicatorDefinition> = listOf(
        segmented(
            OmiCv1IndicatorPattern.PRIVACY_RECORDING,
            "continuous",
            segment(null, OmiCv1LogicalColor.RED, OmiCv1IndicatorLevel.PRIVACY_FLOOR_OR_HIGHER),
        ),
        OmiCv1IndicatorDefinition(
            pattern = OmiCv1IndicatorPattern.PRIVACY_VOICE_TURN,
            shape = OmiCv1IndicatorShape.RED_BASE_BLUE_MODULATION,
            repeatRule = "continuous",
            baseColor = OmiCv1LogicalColor.RED,
            baseLevel = OmiCv1IndicatorLevel.PRIVACY_FLOOR_OR_HIGHER,
            blueModulationHz = 2u,
            redBaseMayTurnOff = false,
        ),
        segmented(
            OmiCv1IndicatorPattern.PRIVACY_UNKNOWN,
            "continuous",
            segment(null, OmiCv1LogicalColor.RED, OmiCv1IndicatorLevel.PRIVACY_FLOOR_OR_HIGHER),
        ),
        segmented(
            OmiCv1IndicatorPattern.BOOTING,
            "once",
            status(200, OmiCv1LogicalColor.BLUE),
            off(200),
            status(200, OmiCv1LogicalColor.BLUE),
        ),
        segmented(
            OmiCv1IndicatorPattern.PAIRING,
            "until_60000_ms",
            status(250, OmiCv1LogicalColor.BLUE),
            off(250),
        ),
        OmiCv1IndicatorDefinition(
            pattern = OmiCv1IndicatorPattern.UPDATING,
            shape = OmiCv1IndicatorShape.BREATHE,
            repeatRule = "until_state_exit",
            cycleMillis = 2_000,
            baseColor = OmiCv1LogicalColor.BLUE,
            baseLevel = OmiCv1IndicatorLevel.STATUS,
        ),
        segmented(
            OmiCv1IndicatorPattern.VALIDATING,
            "until_state_exit",
            status(150, OmiCv1LogicalColor.BLUE),
            off(150),
            status(150, OmiCv1LogicalColor.BLUE),
            off(1_500),
        ),
        segmented(
            OmiCv1IndicatorPattern.RECOVERY_REQUIRED,
            "until_state_exit",
            status(300, OmiCv1LogicalColor.AMBER),
            off(300),
            status(300, OmiCv1LogicalColor.AMBER),
            off(2_400),
        ),
        segmented(
            OmiCv1IndicatorPattern.RECOVERABLE_FAULT,
            "until_acknowledged_or_state_exit",
            status(150, OmiCv1LogicalColor.AMBER),
            off(150),
            status(150, OmiCv1LogicalColor.AMBER),
            off(150),
            status(150, OmiCv1LogicalColor.AMBER),
            off(2_000),
        ),
        segmented(
            OmiCv1IndicatorPattern.CHARGING,
            "while_idle_and_charging",
            status(200, OmiCv1LogicalColor.GREEN),
            off(3_800),
        ),
        segmented(
            OmiCv1IndicatorPattern.LOW_POWER,
            "while_idle_and_low_power",
            status(200, OmiCv1LogicalColor.AMBER),
            off(9_800),
        ),
        segmented(
            OmiCv1IndicatorPattern.READY_LINK_STATUS,
            "once",
            status(300, OmiCv1LogicalColor.BLUE),
        ),
        segmented(
            OmiCv1IndicatorPattern.DISCONNECTED_STATUS,
            "once",
            status(300, OmiCv1LogicalColor.AMBER),
        ),
    ).associateBy(OmiCv1IndicatorDefinition::pattern).also {
        require(it.keys == OmiCv1IndicatorPattern.entries.toSet()) {
            "Every proposed indicator pattern requires one exact logical definition"
        }
    }

    private fun segmented(
        pattern: OmiCv1IndicatorPattern,
        repeatRule: String,
        vararg segments: OmiCv1IndicatorSegment,
    ) = OmiCv1IndicatorDefinition(
        pattern = pattern,
        shape = OmiCv1IndicatorShape.SEGMENTED,
        repeatRule = repeatRule,
        segments = segments.toList(),
    )

    private fun segment(
        durationMillis: Long?,
        color: OmiCv1LogicalColor,
        level: OmiCv1IndicatorLevel,
    ) = OmiCv1IndicatorSegment(durationMillis, color, level)

    private fun status(durationMillis: Long, color: OmiCv1LogicalColor) =
        segment(durationMillis, color, OmiCv1IndicatorLevel.STATUS)

    private fun off(durationMillis: Long) =
        segment(durationMillis, OmiCv1LogicalColor.OFF, OmiCv1IndicatorLevel.OFF)
}

enum class OmiCv1HapticPattern(val wireName: String) {
    READY("ready"),
    VOICE_READY("voice_ready"),
    RECORDING_STARTED("recording_started"),
    RECORDING_STOPPED("recording_stopped"),
    REFUSED("refused"),
    WARNING("warning"),
    FAULT("fault"),
    VOICE_RESULT_OK("voice_result_ok"),
    VOICE_RESULT_FAILED("voice_result_failed"),
    SHUTDOWN_COMMITTED("shutdown_committed"),
}

data class OmiCv1MotorSegment(
    val active: Boolean,
    val durationMillis: Long,
) {
    init {
        require(durationMillis > 0)
    }
}

object OmiCv1HapticCatalog {
    val definitions: Map<OmiCv1HapticPattern, List<OmiCv1MotorSegment>> = mapOf(
        OmiCv1HapticPattern.READY to motor(on(80)),
        OmiCv1HapticPattern.VOICE_READY to motor(on(80)),
        OmiCv1HapticPattern.RECORDING_STARTED to motor(on(80), off(70), on(80)),
        OmiCv1HapticPattern.RECORDING_STOPPED to motor(on(220)),
        OmiCv1HapticPattern.REFUSED to motor(on(80), off(70), on(80), off(70), on(80)),
        OmiCv1HapticPattern.WARNING to motor(on(200), off(100), on(200)),
        OmiCv1HapticPattern.FAULT to motor(on(200), off(100), on(200), off(100), on(200)),
        OmiCv1HapticPattern.VOICE_RESULT_OK to motor(on(80), off(100), on(220)),
        OmiCv1HapticPattern.VOICE_RESULT_FAILED to motor(on(220), off(120), on(220)),
        OmiCv1HapticPattern.SHUTDOWN_COMMITTED to motor(on(300)),
    ).also {
        require(it.keys == OmiCv1HapticPattern.entries.toSet()) {
            "Every proposed haptic pattern requires one exact switched-motor definition"
        }
    }

    private fun motor(vararg segments: OmiCv1MotorSegment) = segments.toList()
    private fun on(durationMillis: Long) = OmiCv1MotorSegment(true, durationMillis)
    private fun off(durationMillis: Long) = OmiCv1MotorSegment(false, durationMillis)
}

enum class OmiCv1MicTruth(val wireName: String) {
    VERIFIED_OFF("verified_off"),
    ACQUIRING("acquiring"),
    ACQUIRED("acquired"),
    RELEASING("releasing"),
    UNKNOWN("unknown"),
}

enum class OmiCv1BaseRecording(val wireName: String) {
    INACTIVE("inactive"),
    ACTIVE("active"),
}

enum class OmiCv1VoiceTurn(val wireName: String) {
    INACTIVE("inactive"),
    STARTING("starting"),
    ACTIVE("active"),
    ENDING("ending"),
    FAILED("failed"),
}

enum class OmiCv1Link(val wireName: String) {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    AUTHENTICATING("authenticating"),
    READY("ready"),
    DEGRADED("degraded"),
}

enum class OmiCv1Maintenance(val wireName: String) {
    NORMAL("normal"),
    PAIRING("pairing"),
    AWAITING_CONFIRMATION("awaiting_confirmation"),
    UPDATING("updating"),
    VALIDATING("validating"),
    RECOVERY_REQUIRED("recovery_required"),
}

enum class OmiCv1Storage(val wireName: String) {
    HEALTHY("healthy"),
    LOW("low"),
    FULL("full"),
    CORRUPT("corrupt"),
}

enum class OmiCv1Fault(val wireName: String) {
    NONE("none"),
    WARNING("warning"),
    RECOVERABLE("recoverable"),
    FATAL_PRIVACY("fatal_privacy"),
}

enum class OmiCv1Power(val wireName: String) {
    OFF("off"),
    BOOTING("booting"),
    OPERATIONAL("operational"),
    SHUTTING_DOWN("shutting_down"),
}

enum class OmiCv1PowerLevel {
    NORMAL,
    LOW,
    CRITICAL,
    UNKNOWN,
}

enum class OmiCv1AcousticDetector(val wireName: String) {
    DISARMED("disarmed"),
    ARMED("armed"),
}

data class OmiCv1HumanIoState(
    val power: OmiCv1Power = OmiCv1Power.OPERATIONAL,
    val micTruth: OmiCv1MicTruth = OmiCv1MicTruth.VERIFIED_OFF,
    val baseRecording: OmiCv1BaseRecording = OmiCv1BaseRecording.INACTIVE,
    val baseRecordingId: String? = null,
    val voiceTurn: OmiCv1VoiceTurn = OmiCv1VoiceTurn.INACTIVE,
    val link: OmiCv1Link = OmiCv1Link.READY,
    val maintenance: OmiCv1Maintenance = OmiCv1Maintenance.NORMAL,
    val storage: OmiCv1Storage = OmiCv1Storage.HEALTHY,
    val fault: OmiCv1Fault = OmiCv1Fault.NONE,
    val faultReason: String? = null,
    val charging: Boolean = false,
    val powerLevel: OmiCv1PowerLevel = OmiCv1PowerLevel.NORMAL,
    val acousticDetector: OmiCv1AcousticDetector = OmiCv1AcousticDetector.DISARMED,
) {
    init {
        require(baseRecording == OmiCv1BaseRecording.ACTIVE || baseRecordingId == null) {
            "An inactive recording cannot retain a recording identity"
        }
        require(baseRecording != OmiCv1BaseRecording.ACTIVE || micTruth != OmiCv1MicTruth.VERIFIED_OFF) {
            "An active base recording cannot coexist with verified-off microphone truth"
        }
        require(micTruth != OmiCv1MicTruth.VERIFIED_OFF || voiceTurn == OmiCv1VoiceTurn.INACTIVE) {
            "A verified-off microphone cannot retain a voice turn"
        }
        require(power !in setOf(OmiCv1Power.OFF, OmiCv1Power.BOOTING) || micTruth == OmiCv1MicTruth.VERIFIED_OFF) {
            "Off and booting states must be microphone-off"
        }
    }
}

enum class OmiCv1IndicatorDecisionStatus {
    SELECTED,
    NO_OUTPUT,
    FATAL_PRIVACY_OUTPUT_UNAVAILABLE,
    UNRESOLVED_SAME_PRIORITY,
}

data class OmiCv1IndicatorDecision(
    val selected: OmiCv1IndicatorPattern?,
    val suppressed: Set<OmiCv1IndicatorPattern>,
    val status: OmiCv1IndicatorDecisionStatus,
    val unresolvedSamePriority: Set<OmiCv1IndicatorPattern> = emptySet(),
) {
    init {
        require(selected !in suppressed)
        require(
            status != OmiCv1IndicatorDecisionStatus.UNRESOLVED_SAME_PRIORITY ||
                unresolvedSamePriority.size > 1,
        )
    }
}

/**
 * Proposed single-writer arbiter. It refuses to invent an order inside the contract's shared
 * power/charging tier: if LOW_POWER and CHARGING are both eligible while no higher output owns the
 * LED, the result is explicitly unresolved until the protocol defines that tie.
 */
object OmiCv1FeedbackArbiter {
    fun decide(
        state: OmiCv1HumanIoState,
        requestedStatus: OmiCv1IndicatorPattern? = null,
    ): OmiCv1IndicatorDecision {
        require(
            requestedStatus == null || requestedStatus in STATUS_PATTERNS,
        ) { "Only a one-shot link/status pattern may enter the requested-status tier" }

        val candidates = buildList {
            privacyPattern(state)?.let { add(Candidate(PRIVACY_PRIORITY, it)) }
            maintenancePattern(state)?.let { add(Candidate(MAINTENANCE_PRIORITY, it)) }
            if (state.fault in setOf(OmiCv1Fault.WARNING, OmiCv1Fault.RECOVERABLE)) {
                add(Candidate(RECOVERABLE_WARNING_PRIORITY, OmiCv1IndicatorPattern.RECOVERABLE_FAULT))
            }
            if (state.power == OmiCv1Power.BOOTING) {
                add(Candidate(POWER_PRIORITY, OmiCv1IndicatorPattern.BOOTING))
            }
            if (state.powerLevel in setOf(OmiCv1PowerLevel.LOW, OmiCv1PowerLevel.CRITICAL)) {
                add(Candidate(POWER_PRIORITY, OmiCv1IndicatorPattern.LOW_POWER))
            }
            if (state.charging) add(Candidate(POWER_PRIORITY, OmiCv1IndicatorPattern.CHARGING))
            requestedStatus?.let { add(Candidate(STATUS_PRIORITY, it)) }
        }

        val fatalPrivacyOwnsOutput = state.fault == OmiCv1Fault.FATAL_PRIVACY &&
            privacyPattern(state) == null
        if (fatalPrivacyOwnsOutput) {
            return OmiCv1IndicatorDecision(
                selected = null,
                suppressed = candidates.mapTo(linkedSetOf()) { it.pattern },
                status = OmiCv1IndicatorDecisionStatus.FATAL_PRIVACY_OUTPUT_UNAVAILABLE,
            )
        }
        if (candidates.isEmpty()) {
            return OmiCv1IndicatorDecision(
                selected = null,
                suppressed = emptySet(),
                status = OmiCv1IndicatorDecisionStatus.NO_OUTPUT,
            )
        }
        val topPriority = candidates.maxOf(Candidate::priority)
        val top = candidates.filter { it.priority == topPriority }.mapTo(linkedSetOf()) { it.pattern }
        if (top.size > 1) {
            return OmiCv1IndicatorDecision(
                selected = null,
                suppressed = candidates.mapTo(linkedSetOf()) { it.pattern } - top,
                status = OmiCv1IndicatorDecisionStatus.UNRESOLVED_SAME_PRIORITY,
                unresolvedSamePriority = top,
            )
        }
        val selected = top.single()
        return OmiCv1IndicatorDecision(
            selected = selected,
            suppressed = candidates.mapTo(linkedSetOf()) { it.pattern } - selected,
            status = OmiCv1IndicatorDecisionStatus.SELECTED,
        )
    }

    private fun privacyPattern(state: OmiCv1HumanIoState): OmiCv1IndicatorPattern? =
        when (state.micTruth) {
            OmiCv1MicTruth.VERIFIED_OFF -> null
            OmiCv1MicTruth.UNKNOWN -> OmiCv1IndicatorPattern.PRIVACY_UNKNOWN
            OmiCv1MicTruth.ACQUIRING -> if (state.voiceTurn == OmiCv1VoiceTurn.STARTING) {
                OmiCv1IndicatorPattern.PRIVACY_VOICE_TURN
            } else {
                OmiCv1IndicatorPattern.PRIVACY_RECORDING
            }
            OmiCv1MicTruth.RELEASING -> if (state.voiceTurn == OmiCv1VoiceTurn.ENDING) {
                OmiCv1IndicatorPattern.PRIVACY_VOICE_TURN
            } else {
                OmiCv1IndicatorPattern.PRIVACY_RECORDING
            }
            OmiCv1MicTruth.ACQUIRED -> when {
                state.voiceTurn in setOf(OmiCv1VoiceTurn.ACTIVE, OmiCv1VoiceTurn.ENDING) ->
                    OmiCv1IndicatorPattern.PRIVACY_VOICE_TURN
                state.voiceTurn == OmiCv1VoiceTurn.STARTING &&
                    state.baseRecording == OmiCv1BaseRecording.INACTIVE ->
                    OmiCv1IndicatorPattern.PRIVACY_VOICE_TURN
                else -> OmiCv1IndicatorPattern.PRIVACY_RECORDING
            }
        }

    private fun maintenancePattern(state: OmiCv1HumanIoState): OmiCv1IndicatorPattern? =
        when (state.maintenance) {
            OmiCv1Maintenance.NORMAL,
            OmiCv1Maintenance.AWAITING_CONFIRMATION,
            -> null
            OmiCv1Maintenance.PAIRING -> OmiCv1IndicatorPattern.PAIRING
            OmiCv1Maintenance.UPDATING -> OmiCv1IndicatorPattern.UPDATING
            OmiCv1Maintenance.VALIDATING -> OmiCv1IndicatorPattern.VALIDATING
            OmiCv1Maintenance.RECOVERY_REQUIRED -> OmiCv1IndicatorPattern.RECOVERY_REQUIRED
        }

    private data class Candidate(val priority: Int, val pattern: OmiCv1IndicatorPattern)

    private const val PRIVACY_PRIORITY = 600
    private const val MAINTENANCE_PRIORITY = 400
    private const val RECOVERABLE_WARNING_PRIORITY = 300
    private const val POWER_PRIORITY = 200
    private const val STATUS_PRIORITY = 100

    private val STATUS_PATTERNS = setOf(
        OmiCv1IndicatorPattern.READY_LINK_STATUS,
        OmiCv1IndicatorPattern.DISCONNECTED_STATUS,
    )
}
