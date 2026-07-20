package dev.gumi.edge.shell.application

/** Digital-shell colors only. Physical LED channels remain device-owned and HIL-calibrated. */
@JvmInline
value class ShellArgb(val value: UInt) {
    val alpha: UInt get() = value shr 24
}

data class ShellPalette(
    val background: ShellArgb,
    val surface: ShellArgb,
    val content: ShellArgb,
    val muted: ShellArgb,
    val verifiedOff: ShellArgb,
    val privacyActive: ShellArgb,
    val privacyUncertain: ShellArgb,
    val information: ShellArgb,
) {
    init {
        require(
            listOf(
                background,
                surface,
                content,
                muted,
                verifiedOff,
                privacyActive,
                privacyUncertain,
                information,
            ).all { it.alpha == 0xFFu },
        ) { "Shell palette colors must be fully opaque" }
    }
}

/** Stable high-contrast dark palette; tone is always paired with text and an icon. */
val GumiDarkShellPalette = ShellPalette(
    background = ShellArgb(0xFF101418u),
    surface = ShellArgb(0xFF1A2026u),
    content = ShellArgb(0xFFF4F7F9u),
    muted = ShellArgb(0xFFB8C1C9u),
    verifiedOff = ShellArgb(0xFF68D9A7u),
    privacyActive = ShellArgb(0xFFFF6268u),
    privacyUncertain = ShellArgb(0xFFFFC857u),
    information = ShellArgb(0xFF79BAFFu),
)

enum class ShellSemanticTone {
    VERIFIED_OFF,
    PRIVACY_ACTIVE,
    PRIVACY_UNCERTAIN,
    INFORMATION,
    NEUTRAL,
}

enum class ShellLiveAnnouncement {
    NONE,
    POLITE,
    ASSERTIVE,
}

data class CaptureVisualPresentation(
    val tone: ShellSemanticTone,
    /** Portable semantic icon key, not a platform resource identifier. */
    val iconKey: String,
    val label: String,
    val accessibilityLabel: String,
    val liveAnnouncement: ShellLiveAnnouncement,
) {
    init {
        require(iconKey.matches(Regex("[a-z][a-z0-9-]{1,63}"))) {
            "Shell icon key must be a stable lowercase identifier"
        }
        require(label.isNotBlank()) { "Visual label cannot be blank" }
        require(accessibilityLabel.isNotBlank()) { "Accessibility label cannot be blank" }
    }
}

object ShellVisualProjector {
    fun capture(presentation: CapturePresentation): CaptureVisualPresentation {
        val semantics = when (presentation.kind) {
            CapturePresentationKind.VERIFIED_OFF -> Triple(
                ShellSemanticTone.VERIFIED_OFF,
                "microphone-off-verified",
                ShellLiveAnnouncement.POLITE,
            )

            CapturePresentationKind.RECORDING,
            CapturePresentationKind.RECORDING_STARTING_VOICE_TURN,
            CapturePresentationKind.VOICE_TURN,
            CapturePresentationKind.RECORDING_WITH_VOICE_TURN,
            CapturePresentationKind.RECORDING_ENDING_VOICE_TURN,
            -> Triple(
                ShellSemanticTone.PRIVACY_ACTIVE,
                "microphone-active",
                ShellLiveAnnouncement.ASSERTIVE,
            )

            CapturePresentationKind.STARTING,
            CapturePresentationKind.STOPPING,
            CapturePresentationKind.MAY_BE_RECORDING,
            CapturePresentationKind.UNKNOWN,
            -> Triple(
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                "microphone-uncertain",
                ShellLiveAnnouncement.ASSERTIVE,
            )
        }
        return CaptureVisualPresentation(
            tone = semantics.first,
            iconKey = semantics.second,
            label = presentation.label,
            accessibilityLabel = presentation.accessibilityLabel,
            liveAnnouncement = semantics.third,
        )
    }

    fun colorFor(tone: ShellSemanticTone, palette: ShellPalette = GumiDarkShellPalette): ShellArgb =
        when (tone) {
            ShellSemanticTone.VERIFIED_OFF -> palette.verifiedOff
            ShellSemanticTone.PRIVACY_ACTIVE -> palette.privacyActive
            ShellSemanticTone.PRIVACY_UNCERTAIN -> palette.privacyUncertain
            ShellSemanticTone.INFORMATION -> palette.information
            ShellSemanticTone.NEUTRAL -> palette.content
        }
}

enum class ShellControlAction {
    REPEAT_STATUS,
    START_RECORDING,
    STOP_CAPTURE,
    START_VOICE_TURN,
    STOP_VOICE_TURN,
    BEGIN_PAIRING,
    PREPARE_UPDATE,
    CONFIRM_PHYSICAL_ACTION,
    REQUEST_SHUTDOWN,
}

data class ShellActionAvailability(
    val enabled: Boolean,
    /** Stable machine-readable reason when disabled; UI renders localized explanatory text. */
    val blockedReasonCode: String? = null,
    /** The runtime must still revalidate every enabled action. */
    val requiresRuntimeRevalidation: Boolean = true,
) {
    init {
        require(enabled == (blockedReasonCode == null)) {
            "Enabled actions forbid a blocked reason and disabled actions require one"
        }
        require(
            blockedReasonCode == null ||
                blockedReasonCode.matches(Regex("[A-Z][A-Z0-9_]{1,63}")),
        ) { "Action block reason must be a stable uppercase identifier" }
    }

    companion object {
        fun enabled() = ShellActionAvailability(enabled = true)

        fun blocked(code: String) = ShellActionAvailability(
            enabled = false,
            blockedReasonCode = code,
        )
    }
}

data class DeviceControlPresentation(
    val device: ShellProjection,
    val captureVisual: CaptureVisualPresentation,
    val actions: Map<ShellControlAction, ShellActionAvailability>,
) {
    init {
        require(actions.keys == ShellControlAction.entries.toSet()) {
            "Every control action requires an explicit availability decision"
        }
    }
}

/** Advisory UI policy only. Concrete runtime/device owners remain the authorization boundary. */
object ShellControlProjector {
    fun project(device: ShellProjection): DeviceControlPresentation {
        val captureKind = device.capture.value.kind
        val exactVerifiedOff = captureKind == CapturePresentationKind.VERIFIED_OFF &&
            device.capture.value.assurance == CaptureAssurance.VERIFIED_OFF
        val maintenanceFresh = device.maintenance.freshness == ObservationFreshness.FRESH
        val maintenance = device.maintenance.value
        val normal = maintenanceFresh && maintenance == MaintenanceState.NORMAL
        val linkReady = device.link.freshness == ObservationFreshness.FRESH &&
            device.link.value == LinkState.READY
        val storageHealthy = device.storage.freshness == ObservationFreshness.FRESH &&
            device.storage.value.state == StorageState.HEALTHY
        val fatalPrivacy = device.fault.value.severity == FaultSeverity.FATAL_PRIVACY
        val pending = device.pendingCommandId != null
        val startBlock = firstBlock(
            "CAPTURE_TRUTH_NOT_VERIFIED_OFF" to !exactVerifiedOff,
            "MAINTENANCE_EXCLUDES_CAPTURE" to !normal,
            "DEVICE_LINK_NOT_READY" to !linkReady,
            "LOCAL_STORAGE_NOT_HEALTHY" to !storageHealthy,
            "FATAL_PRIVACY_FAULT" to fatalPrivacy,
            "COMMAND_ALREADY_PENDING" to pending,
        )
        val recordingBase = captureKind == CapturePresentationKind.RECORDING
        val voiceTurnAlreadyActive = captureKind in setOf(
            CapturePresentationKind.RECORDING_STARTING_VOICE_TURN,
            CapturePresentationKind.VOICE_TURN,
            CapturePresentationKind.RECORDING_WITH_VOICE_TURN,
            CapturePresentationKind.RECORDING_ENDING_VOICE_TURN,
        )
        val voiceTurnStartBlock = firstBlock(
            "VOICE_TURN_ALREADY_ACTIVE" to voiceTurnAlreadyActive,
            "CAPTURE_TRUTH_NOT_ACTIONABLE" to !(exactVerifiedOff || recordingBase),
            "MAINTENANCE_EXCLUDES_CAPTURE" to !normal,
            "DEVICE_LINK_NOT_READY" to !linkReady,
            "LOCAL_STORAGE_NOT_HEALTHY" to !storageHealthy,
            "FATAL_PRIVACY_FAULT" to fatalPrivacy,
            "COMMAND_ALREADY_PENDING" to pending,
        )
        val maintenanceBlock = firstBlock(
            "CAPTURE_TRUTH_NOT_VERIFIED_OFF" to !exactVerifiedOff,
            "MAINTENANCE_NOT_NORMAL" to !normal,
            "DEVICE_LINK_NOT_READY" to !linkReady,
            "COMMAND_ALREADY_PENDING" to pending,
        )
        val stopVoice = captureKind in setOf(
            CapturePresentationKind.VOICE_TURN,
            CapturePresentationKind.RECORDING_WITH_VOICE_TURN,
            CapturePresentationKind.RECORDING_STARTING_VOICE_TURN,
            CapturePresentationKind.RECORDING_ENDING_VOICE_TURN,
        )
        val captureMayBeActive = device.capture.value.assurance != CaptureAssurance.VERIFIED_OFF
        val confirmation = maintenanceFresh &&
            maintenance == MaintenanceState.AWAITING_PHYSICAL_CONFIRMATION
        val shutdownBlocked = maintenance in setOf(
            MaintenanceState.UPDATING,
            MaintenanceState.VALIDATING,
            MaintenanceState.RECOVERY_REQUIRED,
            MaintenanceState.SHUTTING_DOWN,
        ) || !maintenanceFresh

        val actions = linkedMapOf(
            ShellControlAction.REPEAT_STATUS to ShellActionAvailability.enabled(),
            ShellControlAction.START_RECORDING to availability(startBlock),
            ShellControlAction.STOP_CAPTURE to availability(
                if (captureMayBeActive) null else "CAPTURE_ALREADY_VERIFIED_OFF",
            ),
            ShellControlAction.START_VOICE_TURN to availability(voiceTurnStartBlock),
            ShellControlAction.STOP_VOICE_TURN to availability(
                if (stopVoice) null else "VOICE_TURN_NOT_ACTIVE",
            ),
            ShellControlAction.BEGIN_PAIRING to availability(maintenanceBlock),
            ShellControlAction.PREPARE_UPDATE to availability(
                maintenanceBlock ?: when {
                    device.power.freshness != ObservationFreshness.FRESH -> "POWER_STATE_NOT_FRESH"
                    device.power.value.level != PowerLevel.NORMAL -> "POWER_POLICY_NOT_SATISFIED"
                    device.update.freshness != ObservationFreshness.FRESH -> "UPDATE_STATE_NOT_FRESH"
                    device.update.value.stage != UpdateStage.IDLE -> "UPDATE_ALREADY_ACTIVE"
                    !storageHealthy -> "LOCAL_STORAGE_NOT_HEALTHY"
                    else -> null
                },
            ),
            ShellControlAction.CONFIRM_PHYSICAL_ACTION to availability(
                if (confirmation && !pending) null else {
                    if (pending) "COMMAND_ALREADY_PENDING" else "NO_PHYSICAL_CONFIRMATION_PENDING"
                },
            ),
            ShellControlAction.REQUEST_SHUTDOWN to availability(
                if (shutdownBlocked) "SHUTDOWN_CURRENTLY_UNSAFE" else null,
            ),
        )
        return DeviceControlPresentation(
            device = device,
            captureVisual = ShellVisualProjector.capture(device.capture.value),
            actions = actions,
        )
    }

    private fun firstBlock(vararg candidates: Pair<String, Boolean>): String? =
        candidates.firstOrNull { it.second }?.first

    private fun availability(blockedReason: String?): ShellActionAvailability =
        if (blockedReason == null) {
            ShellActionAvailability.enabled()
        } else {
            ShellActionAvailability.blocked(blockedReason)
        }
}
