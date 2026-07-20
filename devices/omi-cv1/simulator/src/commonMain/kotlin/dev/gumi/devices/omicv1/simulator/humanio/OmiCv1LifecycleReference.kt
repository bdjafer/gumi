package dev.gumi.devices.omicv1.simulator.humanio

enum class OmiCv1HumanIoInputType(val wireName: String) {
    LINK_DISCONNECTED("link_disconnected"),
    START_BASE_RECORDING_REQUESTED("start_base_recording_requested"),
    STOP_BASE_RECORDING_REQUESTED("stop_base_recording_requested"),
    START_VOICE_TURN_REQUESTED("start_voice_turn_requested"),
    START_VOICE_TURN_OVERLAY_REQUESTED("start_voice_turn_overlay_requested"),
    END_VOICE_TURN_REQUESTED("end_voice_turn_requested"),
    VOICE_TURN_REFUSED("voice_turn_refused"),
    PRIVACY_GUARD_ASSERTED("privacy_guard_asserted"),
    PRIVACY_GUARD_FAILED("privacy_guard_failed"),
    MICROPHONE_ACQUIRED("microphone_acquired"),
    LOCAL_DURABILITY_READY("local_durability_ready"),
    REALTIME_ROUTE_READY("realtime_route_ready"),
    LOCAL_RECORDING_FINALIZED("local_recording_finalized"),
    REALTIME_ROUTE_CLOSED("realtime_route_closed"),
    STORAGE_STATE_CHANGED("storage_state_changed"),
    LAST_DURABLE_FRAME_COMMITTED("last_durable_frame_committed"),
    MICROPHONE_RELEASED("microphone_released"),
    RECOVERABLE_FAULT_RAISED("recoverable_fault_raised"),
    CHARGING_STATE_CHANGED("charging_state_changed"),
    STATUS_REPEAT_REQUESTED("status_repeat_requested"),
    WATCHDOG_RESET("watchdog_reset"),
    BOOT_STARTED("boot_started"),
    REQUIRED_SELF_TESTS_PASSED("required_self_tests_passed"),
}

data class OmiCv1HumanIoInput(
    val atMillis: Long,
    val type: OmiCv1HumanIoInputType,
    val value: String? = null,
    val reason: String? = null,
) {
    init {
        require(atMillis >= 0)
    }
}

enum class OmiCv1LifecycleEventType(val wireName: String) {
    LINK_STATE_CHANGED("link_state_changed"),
    BASE_RECORDING_STARTED("base_recording_started"),
    BASE_RECORDING_REFUSED("base_recording_refused"),
    SAFE_CAPTURE_STOP_REQUESTED("safe_capture_stop_requested"),
    BASE_RECORDING_STOPPED("base_recording_stopped"),
    VOICE_TURN_STARTED("voice_turn_started"),
    VOICE_TURN_ENDED("voice_turn_ended"),
    CAPTURE_DISCONTINUITY("capture_discontinuity"),
    BOOT_READY("boot_ready"),
}

data class OmiCv1LifecycleEvent(
    val atMillis: Long,
    val type: OmiCv1LifecycleEventType,
    val value: String? = null,
    val reason: String? = null,
    val recordingId: String? = null,
)

enum class OmiCv1ShellCapture(val wireName: String) {
    VERIFIED_IDLE("verified_idle"),
    RECORDING_LOCAL("recording_local"),
    MAY_STILL_BE_RECORDING("may_still_be_recording"),
    UNKNOWN("unknown"),
}

data class OmiCv1HumanIoShellProjection(
    val capture: OmiCv1ShellCapture,
    val label: String,
    val secondaryLabel: String? = null,
    val link: OmiCv1Link,
    val faultReason: String? = null,
)

data class OmiCv1IndicatorTransition(
    val atMillis: Long,
    val previous: OmiCv1IndicatorPattern?,
    val current: OmiCv1IndicatorPattern?,
)

data class OmiCv1HumanIoStep(
    val atMillis: Long,
    val state: OmiCv1HumanIoState,
    val semanticEvents: List<OmiCv1LifecycleEvent>,
    val shell: OmiCv1HumanIoShellProjection,
    /** Persistent pattern after this input; one-shot status requests are exposed by [outputDecision]. */
    val persistentIndicator: OmiCv1IndicatorPattern?,
    val indicatorTransition: OmiCv1IndicatorTransition?,
    val outputDecision: OmiCv1IndicatorDecision,
    val haptics: List<OmiCv1HapticPattern>,
)

/**
 * Pure owner for the lifecycle, capture-fault, and output-arbitration fixture slice. It models the
 * proposed custom-firmware contract only. It never claims that stock firmware follows these rules.
 */
class OmiCv1LifecycleReference(initialState: OmiCv1HumanIoState) {
    var state: OmiCv1HumanIoState = initialState
        private set

    private var currentMillis = 0L
    private var pendingCaptureStart = PendingCaptureStart.NONE
    private var privacyGuardAsserted = false
    private var microphoneAcquired = false
    private var localDurabilityReady = false
    private var realtimeRouteReady = false
    private var recordingStopPending = false
    private var recordingFinalized = false
    private var voiceEndReturnsToRecording = false
    private var durabilityStopPending = false
    private var durableBoundaryCommitted = false
    private var watchdogInterruptedRecordingId: String? = null
    private var persistentIndicator = persistentDecision().selected

    fun shellProjection(): OmiCv1HumanIoShellProjection = projectShell(state)

    fun currentIndicator(): OmiCv1IndicatorPattern? = persistentIndicator

    fun apply(input: OmiCv1HumanIoInput): OmiCv1HumanIoStep {
        require(input.atMillis >= currentMillis) { "Human-I/O inputs must be monotonic" }

        val events = mutableListOf<OmiCv1LifecycleEvent>()
        val haptics = mutableListOf<OmiCv1HapticPattern>()
        var requestedStatus: OmiCv1IndicatorPattern? = null

        when (input.type) {
            OmiCv1HumanIoInputType.LINK_DISCONNECTED -> {
                state = state.copy(link = OmiCv1Link.DISCONNECTED)
                events += event(
                    input,
                    OmiCv1LifecycleEventType.LINK_STATE_CHANGED,
                    value = OmiCv1Link.DISCONNECTED.wireName,
                )
            }

            OmiCv1HumanIoInputType.START_BASE_RECORDING_REQUESTED -> {
                require(state.micTruth == OmiCv1MicTruth.VERIFIED_OFF)
                require(state.baseRecording == OmiCv1BaseRecording.INACTIVE)
                require(state.maintenance == OmiCv1Maintenance.NORMAL)
                beginCaptureStart(PendingCaptureStart.BASE_RECORDING)
            }

            OmiCv1HumanIoInputType.STOP_BASE_RECORDING_REQUESTED -> {
                require(state.micTruth == OmiCv1MicTruth.ACQUIRED)
                require(state.baseRecording == OmiCv1BaseRecording.ACTIVE)
                require(state.voiceTurn == OmiCv1VoiceTurn.INACTIVE)
                require(!recordingStopPending)
                recordingStopPending = true
                recordingFinalized = false
                state = state.copy(micTruth = OmiCv1MicTruth.RELEASING)
            }

            OmiCv1HumanIoInputType.START_VOICE_TURN_REQUESTED -> {
                require(state.micTruth == OmiCv1MicTruth.VERIFIED_OFF)
                require(state.baseRecording == OmiCv1BaseRecording.INACTIVE)
                require(state.voiceTurn == OmiCv1VoiceTurn.INACTIVE)
                require(state.maintenance == OmiCv1Maintenance.NORMAL)
                beginCaptureStart(PendingCaptureStart.VOICE_TURN_FROM_IDLE)
            }

            OmiCv1HumanIoInputType.START_VOICE_TURN_OVERLAY_REQUESTED -> {
                require(state.micTruth == OmiCv1MicTruth.ACQUIRED)
                require(state.baseRecording == OmiCv1BaseRecording.ACTIVE)
                require(state.voiceTurn == OmiCv1VoiceTurn.INACTIVE)
                require(state.maintenance == OmiCv1Maintenance.NORMAL)
                beginCaptureStart(PendingCaptureStart.VOICE_TURN_OVER_RECORDING)
                state = state.copy(voiceTurn = OmiCv1VoiceTurn.STARTING)
            }

            OmiCv1HumanIoInputType.END_VOICE_TURN_REQUESTED -> {
                when {
                    pendingCaptureStart == PendingCaptureStart.VOICE_TURN_FROM_IDLE -> {
                        val releaseRequired = state.micTruth != OmiCv1MicTruth.VERIFIED_OFF
                        clearPendingCaptureStart()
                        state = state.copy(
                            micTruth = if (releaseRequired) {
                                OmiCv1MicTruth.RELEASING
                            } else {
                                OmiCv1MicTruth.VERIFIED_OFF
                            },
                            voiceTurn = if (releaseRequired) {
                                OmiCv1VoiceTurn.ENDING
                            } else {
                                OmiCv1VoiceTurn.INACTIVE
                            },
                        )
                    }

                    pendingCaptureStart == PendingCaptureStart.VOICE_TURN_OVER_RECORDING -> {
                        clearPendingCaptureStart()
                        state = state.copy(voiceTurn = OmiCv1VoiceTurn.INACTIVE)
                    }

                    else -> {
                        require(state.voiceTurn == OmiCv1VoiceTurn.ACTIVE)
                        voiceEndReturnsToRecording = state.baseRecording == OmiCv1BaseRecording.ACTIVE
                        state = state.copy(
                            micTruth = if (voiceEndReturnsToRecording) {
                                OmiCv1MicTruth.ACQUIRED
                            } else {
                                OmiCv1MicTruth.RELEASING
                            },
                            voiceTurn = OmiCv1VoiceTurn.ENDING,
                        )
                    }
                }
            }

            OmiCv1HumanIoInputType.VOICE_TURN_REFUSED -> {
                require(state.voiceTurn == OmiCv1VoiceTurn.INACTIVE)
                require(
                    state.micTruth == OmiCv1MicTruth.VERIFIED_OFF &&
                        state.baseRecording == OmiCv1BaseRecording.INACTIVE ||
                        state.micTruth == OmiCv1MicTruth.ACQUIRED &&
                        state.baseRecording == OmiCv1BaseRecording.ACTIVE,
                ) { "Voice refusal must preserve either Idle or the active base recording" }
                haptics += OmiCv1HapticPattern.REFUSED
                requestedStatus = OmiCv1IndicatorPattern.DISCONNECTED_STATUS
            }

            OmiCv1HumanIoInputType.PRIVACY_GUARD_ASSERTED -> {
                require(
                    pendingCaptureStart == PendingCaptureStart.BASE_RECORDING ||
                        pendingCaptureStart == PendingCaptureStart.VOICE_TURN_FROM_IDLE,
                ) { "Only a microphone-off capture start asserts a new privacy guard" }
                require(!privacyGuardAsserted)
                privacyGuardAsserted = true
                state = state.copy(
                    micTruth = OmiCv1MicTruth.ACQUIRING,
                    voiceTurn = if (pendingCaptureStart == PendingCaptureStart.VOICE_TURN_FROM_IDLE) {
                        OmiCv1VoiceTurn.STARTING
                    } else {
                        OmiCv1VoiceTurn.INACTIVE
                    },
                )
            }

            OmiCv1HumanIoInputType.PRIVACY_GUARD_FAILED -> {
                require(pendingCaptureStart == PendingCaptureStart.BASE_RECORDING) {
                    "A privacy-guard failure without a pending acquisition is not this fixture path"
                }
                clearPendingCaptureStart()
                state = state.copy(
                    fault = OmiCv1Fault.FATAL_PRIVACY,
                    faultReason = "privacy_output_unavailable",
                )
                events += event(
                    input,
                    OmiCv1LifecycleEventType.BASE_RECORDING_REFUSED,
                    reason = "privacy_output_unavailable",
                )
                haptics += OmiCv1HapticPattern.FAULT
            }

            OmiCv1HumanIoInputType.MICROPHONE_ACQUIRED -> {
                require(privacyGuardAsserted)
                require(state.micTruth == OmiCv1MicTruth.ACQUIRING)
                microphoneAcquired = true
                state = state.copy(micTruth = OmiCv1MicTruth.ACQUIRED)
                completePendingCaptureStart(input, events, haptics)
            }

            OmiCv1HumanIoInputType.LOCAL_DURABILITY_READY -> {
                require(
                    pendingCaptureStart == PendingCaptureStart.BASE_RECORDING ||
                        pendingCaptureStart == PendingCaptureStart.VOICE_TURN_FROM_IDLE,
                )
                localDurabilityReady = true
                completePendingCaptureStart(input, events, haptics)
            }

            OmiCv1HumanIoInputType.REALTIME_ROUTE_READY -> {
                require(
                    pendingCaptureStart == PendingCaptureStart.VOICE_TURN_FROM_IDLE ||
                        pendingCaptureStart == PendingCaptureStart.VOICE_TURN_OVER_RECORDING,
                )
                realtimeRouteReady = true
                completePendingCaptureStart(input, events, haptics)
            }

            OmiCv1HumanIoInputType.LOCAL_RECORDING_FINALIZED -> {
                require(recordingStopPending)
                recordingFinalized = true
            }

            OmiCv1HumanIoInputType.REALTIME_ROUTE_CLOSED -> {
                require(voiceEndReturnsToRecording)
                require(state.voiceTurn == OmiCv1VoiceTurn.ENDING)
                voiceEndReturnsToRecording = false
                state = state.copy(voiceTurn = OmiCv1VoiceTurn.INACTIVE)
                events += event(input, OmiCv1LifecycleEventType.VOICE_TURN_ENDED)
            }

            OmiCv1HumanIoInputType.STORAGE_STATE_CHANGED -> {
                require(input.value == OmiCv1Storage.FULL.wireName) {
                    "The current reference slice only owns the qualified storage-full transition"
                }
                require(state.micTruth == OmiCv1MicTruth.ACQUIRED)
                require(state.baseRecording == OmiCv1BaseRecording.ACTIVE)
                require(!durabilityStopPending)
                state = state.copy(
                    storage = OmiCv1Storage.FULL,
                    fault = OmiCv1Fault.RECOVERABLE,
                    faultReason = "local_durability_unavailable",
                )
                durabilityStopPending = true
                durableBoundaryCommitted = false
                events += event(
                    input,
                    OmiCv1LifecycleEventType.SAFE_CAPTURE_STOP_REQUESTED,
                    reason = "local_durability_unavailable",
                )
            }

            OmiCv1HumanIoInputType.LAST_DURABLE_FRAME_COMMITTED -> {
                require(durabilityStopPending)
                durableBoundaryCommitted = true
            }

            OmiCv1HumanIoInputType.MICROPHONE_RELEASED -> {
                when {
                    durabilityStopPending -> {
                        require(durableBoundaryCommitted) {
                            "Storage-fault release must follow the last qualified durable boundary"
                        }
                        durabilityStopPending = false
                        durableBoundaryCommitted = false
                        state = state.copy(
                            micTruth = OmiCv1MicTruth.VERIFIED_OFF,
                            baseRecording = OmiCv1BaseRecording.INACTIVE,
                            baseRecordingId = null,
                            voiceTurn = OmiCv1VoiceTurn.INACTIVE,
                        )
                        events += event(
                            input,
                            OmiCv1LifecycleEventType.BASE_RECORDING_STOPPED,
                            reason = "local_durability_unavailable",
                        )
                        haptics += OmiCv1HapticPattern.FAULT
                    }

                    recordingStopPending -> {
                        require(recordingFinalized) {
                            "Recording release must follow local recording finalization"
                        }
                        recordingStopPending = false
                        recordingFinalized = false
                        state = state.copy(
                            micTruth = OmiCv1MicTruth.VERIFIED_OFF,
                            baseRecording = OmiCv1BaseRecording.INACTIVE,
                            baseRecordingId = null,
                        )
                        events += event(input, OmiCv1LifecycleEventType.BASE_RECORDING_STOPPED)
                        haptics += OmiCv1HapticPattern.RECORDING_STOPPED
                    }

                    state.voiceTurn == OmiCv1VoiceTurn.ENDING && !voiceEndReturnsToRecording -> {
                        state = state.copy(
                            micTruth = OmiCv1MicTruth.VERIFIED_OFF,
                            voiceTurn = OmiCv1VoiceTurn.INACTIVE,
                        )
                        events += event(input, OmiCv1LifecycleEventType.VOICE_TURN_ENDED)
                    }

                    else -> error("Microphone release has no pending capture stop")
                }
            }

            OmiCv1HumanIoInputType.RECOVERABLE_FAULT_RAISED -> {
                require(!input.reason.isNullOrBlank())
                state = state.copy(
                    fault = OmiCv1Fault.RECOVERABLE,
                    faultReason = input.reason,
                )
                haptics += OmiCv1HapticPattern.WARNING
            }

            OmiCv1HumanIoInputType.CHARGING_STATE_CHANGED -> {
                state = state.copy(charging = requireNotNull(input.value).toBooleanStrict())
            }

            OmiCv1HumanIoInputType.STATUS_REPEAT_REQUESTED -> {
                requestedStatus = if (state.link == OmiCv1Link.READY) {
                    OmiCv1IndicatorPattern.READY_LINK_STATUS
                } else {
                    OmiCv1IndicatorPattern.DISCONNECTED_STATUS
                }
            }

            OmiCv1HumanIoInputType.WATCHDOG_RESET -> {
                require(watchdogInterruptedRecordingId == null)
                watchdogInterruptedRecordingId = state.baseRecordingId
                    ?: if (state.baseRecording == OmiCv1BaseRecording.ACTIVE) {
                        "recording-identity-unavailable"
                    } else {
                        null
                    }
            }

            OmiCv1HumanIoInputType.BOOT_STARTED -> {
                val interrupted = watchdogInterruptedRecordingId
                state = state.copy(
                    power = OmiCv1Power.BOOTING,
                    micTruth = OmiCv1MicTruth.VERIFIED_OFF,
                    baseRecording = OmiCv1BaseRecording.INACTIVE,
                    baseRecordingId = null,
                    voiceTurn = OmiCv1VoiceTurn.INACTIVE,
                    fault = OmiCv1Fault.NONE,
                    faultReason = null,
                )
                if (interrupted != null) {
                    events += event(
                        input,
                        OmiCv1LifecycleEventType.CAPTURE_DISCONTINUITY,
                        recordingId = interrupted,
                    )
                }
                watchdogInterruptedRecordingId = null
            }

            OmiCv1HumanIoInputType.REQUIRED_SELF_TESTS_PASSED -> {
                require(state.power == OmiCv1Power.BOOTING)
                require(state.micTruth == OmiCv1MicTruth.VERIFIED_OFF)
                state = state.copy(power = OmiCv1Power.OPERATIONAL)
                events += event(input, OmiCv1LifecycleEventType.BOOT_READY)
                haptics += OmiCv1HapticPattern.READY
            }
        }

        currentMillis = input.atMillis
        val priorIndicator = persistentIndicator
        val newPersistent = persistentDecision().selected
        val transition = if (newPersistent != priorIndicator) {
            OmiCv1IndicatorTransition(input.atMillis, priorIndicator, newPersistent)
        } else {
            null
        }
        persistentIndicator = newPersistent
        val outputDecision = OmiCv1FeedbackArbiter.decide(state, requestedStatus)
        verifyPrivacyInvariant(outputDecision)

        return OmiCv1HumanIoStep(
            atMillis = input.atMillis,
            state = state,
            semanticEvents = events,
            shell = projectShell(state),
            persistentIndicator = persistentIndicator,
            indicatorTransition = transition,
            outputDecision = outputDecision,
            haptics = haptics,
        )
    }

    private fun beginCaptureStart(start: PendingCaptureStart) {
        require(pendingCaptureStart == PendingCaptureStart.NONE) {
            "Only one capture acquisition may be pending"
        }
        pendingCaptureStart = start
        privacyGuardAsserted = false
        microphoneAcquired = start == PendingCaptureStart.VOICE_TURN_OVER_RECORDING
        localDurabilityReady = start == PendingCaptureStart.VOICE_TURN_OVER_RECORDING
        realtimeRouteReady = false
    }

    private fun clearPendingCaptureStart() {
        pendingCaptureStart = PendingCaptureStart.NONE
        privacyGuardAsserted = false
        microphoneAcquired = false
        localDurabilityReady = false
        realtimeRouteReady = false
    }

    private fun completePendingCaptureStart(
        input: OmiCv1HumanIoInput,
        events: MutableList<OmiCv1LifecycleEvent>,
        haptics: MutableList<OmiCv1HapticPattern>,
    ) {
        when (pendingCaptureStart) {
            PendingCaptureStart.NONE -> Unit
            PendingCaptureStart.BASE_RECORDING -> {
                if (!privacyGuardAsserted || !microphoneAcquired || !localDurabilityReady) return
                state = state.copy(baseRecording = OmiCv1BaseRecording.ACTIVE)
                events += event(input, OmiCv1LifecycleEventType.BASE_RECORDING_STARTED)
                haptics += OmiCv1HapticPattern.RECORDING_STARTED
                clearPendingCaptureStart()
            }

            PendingCaptureStart.VOICE_TURN_FROM_IDLE -> {
                if (
                    !privacyGuardAsserted ||
                    !microphoneAcquired ||
                    !localDurabilityReady ||
                    !realtimeRouteReady
                ) return
                state = state.copy(voiceTurn = OmiCv1VoiceTurn.ACTIVE)
                events += event(input, OmiCv1LifecycleEventType.VOICE_TURN_STARTED)
                haptics += OmiCv1HapticPattern.VOICE_READY
                clearPendingCaptureStart()
            }

            PendingCaptureStart.VOICE_TURN_OVER_RECORDING -> {
                if (!realtimeRouteReady) return
                state = state.copy(voiceTurn = OmiCv1VoiceTurn.ACTIVE)
                events += event(input, OmiCv1LifecycleEventType.VOICE_TURN_STARTED)
                haptics += OmiCv1HapticPattern.VOICE_READY
                clearPendingCaptureStart()
            }
        }
    }

    private fun persistentDecision(): OmiCv1IndicatorDecision = OmiCv1FeedbackArbiter.decide(state)

    private fun verifyPrivacyInvariant(decision: OmiCv1IndicatorDecision) {
        if (state.micTruth == OmiCv1MicTruth.VERIFIED_OFF) return
        require(decision.selected in PRIVACY_PATTERNS) {
            "Every state where the microphone may be active must retain a privacy indicator"
        }
        require(decision.status == OmiCv1IndicatorDecisionStatus.SELECTED)
    }

    private fun event(
        input: OmiCv1HumanIoInput,
        type: OmiCv1LifecycleEventType,
        value: String? = null,
        reason: String? = null,
        recordingId: String? = null,
    ) = OmiCv1LifecycleEvent(input.atMillis, type, value, reason, recordingId)

    private fun projectShell(state: OmiCv1HumanIoState): OmiCv1HumanIoShellProjection {
        val capture = when {
            state.micTruth == OmiCv1MicTruth.ACQUIRED &&
                state.baseRecording == OmiCv1BaseRecording.ACTIVE &&
                state.link == OmiCv1Link.DISCONNECTED -> OmiCv1ShellCapture.MAY_STILL_BE_RECORDING
            state.micTruth == OmiCv1MicTruth.ACQUIRED &&
                state.baseRecording == OmiCv1BaseRecording.ACTIVE -> OmiCv1ShellCapture.RECORDING_LOCAL
            state.micTruth == OmiCv1MicTruth.VERIFIED_OFF &&
                state.link == OmiCv1Link.READY -> OmiCv1ShellCapture.VERIFIED_IDLE
            else -> OmiCv1ShellCapture.UNKNOWN
        }
        val label = when (capture) {
            OmiCv1ShellCapture.VERIFIED_IDLE -> "Microphone off — device confirmed"
            OmiCv1ShellCapture.RECORDING_LOCAL -> "Recording locally"
            OmiCv1ShellCapture.MAY_STILL_BE_RECORDING ->
                "Device disconnected; recording may continue locally"
            OmiCv1ShellCapture.UNKNOWN ->
                "Microphone state unknown — check the device privacy light"
        }
        return OmiCv1HumanIoShellProjection(
            capture = capture,
            label = label,
            secondaryLabel = if (
                state.acousticDetector == OmiCv1AcousticDetector.ARMED &&
                state.micTruth == OmiCv1MicTruth.VERIFIED_OFF
            ) {
                "Microphone stream off; acoustic detector armed"
            } else {
                null
            },
            link = state.link,
            faultReason = state.faultReason,
        )
    }

    private companion object {
        val PRIVACY_PATTERNS = setOf(
            OmiCv1IndicatorPattern.PRIVACY_RECORDING,
            OmiCv1IndicatorPattern.PRIVACY_VOICE_TURN,
            OmiCv1IndicatorPattern.PRIVACY_UNKNOWN,
        )
    }
}

private enum class PendingCaptureStart {
    NONE,
    BASE_RECORDING,
    VOICE_TURN_FROM_IDLE,
    VOICE_TURN_OVER_RECORDING,
}
