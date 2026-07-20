package dev.gumi.edge.shell.application

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ShellAttachmentKind {
    ATTACHED,
    ATTACHING,
    DETACHED,
    DEGRADED,
    UNKNOWN,
}

data class ShellAttachmentPresentation(
    val kind: ShellAttachmentKind,
    val label: String,
    val iconKey: String,
    val tone: ShellSemanticTone,
)

enum class ShellPhysicalOutputKind {
    PRIVACY_ACTIVE_CONFIRMED,
    PRIVACY_UNKNOWN_CONFIRMED,
    NO_SIGNAL_CONFIRMED,
    STATUS_CONFIRMED,
    UNVERIFIED,
    FAILED,
    CONTRADICTORY,
}

data class ShellPhysicalOutputPresentation(
    val kind: ShellPhysicalOutputKind,
    val label: String,
    val accessibilityLabel: String = label,
    val iconKey: String,
    val tone: ShellSemanticTone,
    val liveAnnouncement: ShellLiveAnnouncement,
) {
    init {
        require(label.isNotBlank()) { "Physical output label cannot be blank" }
        require(accessibilityLabel.isNotBlank()) {
            "Physical output accessibility label cannot be blank"
        }
    }
}

enum class ShellFaultKind {
    CLEAR,
    WARNING,
    RECOVERABLE,
    PRIVACY_CRITICAL,
    UNKNOWN,
}

data class ShellFaultPresentation(
    val kind: ShellFaultKind,
    val label: String,
    val iconKey: String,
    val tone: ShellSemanticTone,
    val liveAnnouncement: ShellLiveAnnouncement,
    val failure: ExpectedFailure? = null,
)

enum class ShellCaptureWorkflowState {
    NO_MANAGED_DEVICES,
    ALL_VERIFIED_OFF,
    STARTING,
    ONE_ACTIVE,
    UNCERTAIN,
    COLLISION_RISK,
}

data class ShellCaptureAdmissionReservation(
    val commandId: CommandId,
    val targetDeviceId: DeviceId,
)

data class ShellCaptureWorkflowPresentation(
    val state: ShellCaptureWorkflowState,
    val label: String,
    val iconKey: String,
    val tone: ShellSemanticTone,
    val liveAnnouncement: ShellLiveAnnouncement,
    val activeDeviceIds: Set<DeviceId>,
    val uncertainDeviceIds: Set<DeviceId>,
    val admissionReservation: ShellCaptureAdmissionReservation? = null,
) {
    val soleActiveDeviceId: DeviceId?
        get() = activeDeviceIds.singleOrNull().takeIf { uncertainDeviceIds.isEmpty() }
}

data class ShellProductDevicePresentation(
    val selected: Boolean,
    val captureAdmissionReserved: Boolean,
    val attachment: ShellAttachmentPresentation,
    val control: DeviceControlPresentation,
    val physicalOutput: ShellPhysicalOutputPresentation,
    val fault: ShellFaultPresentation,
)

data class PortableControlPlanePresentation(
    val selectedDeviceId: DeviceId?,
    val workflow: ShellCaptureWorkflowPresentation,
    val devices: List<ShellProductDevicePresentation>,
) {
    init {
        require(devices.count { it.selected } <= 1) {
            "At most one device may be selected"
        }
        require(
            selectedDeviceId == devices.singleOrNull { it.selected }?.control?.device?.deviceId,
        ) { "Selected device identity must match the selected presentation" }
    }
}

data class ShellOutputFreshnessPolicy(
    val maxAgeMillis: Long = 30_000,
) {
    init {
        require(maxAgeMillis >= 0) { "Output freshness window cannot be negative" }
    }
}

/**
 * Pure product projector. It never turns an application request or device-specific pattern into
 * physical-output evidence.
 */
object PortableControlPlaneProjector {
    fun project(
        fleet: FleetShellProjection,
        outputTruth: Map<DeviceId, DeviceOutputTruth> = emptyMap(),
        preferredDeviceId: DeviceId? = null,
        outputFreshnessPolicy: ShellOutputFreshnessPolicy = ShellOutputFreshnessPolicy(),
        admissionReservation: ShellCaptureAdmissionReservation? = null,
    ): PortableControlPlanePresentation {
        require(outputTruth.all { (key, report) -> key == report.deviceId }) {
            "Output truth map keys must match their stable device identities"
        }

        val workflow = workflow(fleet, admissionReservation)
        val selectedId = resolveSelection(fleet.devices, workflow, preferredDeviceId)
        val devices = fleet.devices.map { device ->
            val output = physicalOutput(
                device = device,
                report = outputTruth[device.deviceId],
                policy = outputFreshnessPolicy,
            )
            val baseControl = ShellControlProjector.project(device)
            val adjustedControl = baseControl.copy(
                actions = adjustActions(baseControl, workflow, output),
            )
            ShellProductDevicePresentation(
                selected = device.deviceId == selectedId,
                captureAdmissionReserved = admissionReservation?.targetDeviceId == device.deviceId,
                attachment = attachment(device.link),
                control = adjustedControl,
                physicalOutput = output,
                fault = fault(device.fault, output),
            )
        }
        return PortableControlPlanePresentation(
            selectedDeviceId = selectedId,
            workflow = workflow,
            devices = devices,
        )
    }

    private fun workflow(
        fleet: FleetShellProjection,
        reservation: ShellCaptureAdmissionReservation?,
    ): ShellCaptureWorkflowPresentation {
        val active = fleet.capture.activeDeviceIds
        val uncertain = fleet.capture.uncertainDeviceIds
        val baseState = when {
            fleet.devices.isEmpty() -> ShellCaptureWorkflowState.NO_MANAGED_DEVICES
            active.size > 1 || active.isNotEmpty() && uncertain.isNotEmpty() ->
                ShellCaptureWorkflowState.COLLISION_RISK

            active.size == 1 -> ShellCaptureWorkflowState.ONE_ACTIVE
            uncertain.isNotEmpty() -> ShellCaptureWorkflowState.UNCERTAIN
            else -> ShellCaptureWorkflowState.ALL_VERIFIED_OFF
        }
        val reservationTargetIsManaged = reservation?.targetDeviceId?.let { target ->
            fleet.devices.any { it.deviceId == target }
        } ?: true
        val state = when {
            reservation == null -> baseState
            !reservationTargetIsManaged -> ShellCaptureWorkflowState.COLLISION_RISK
            baseState == ShellCaptureWorkflowState.ALL_VERIFIED_OFF ->
                ShellCaptureWorkflowState.STARTING

            baseState == ShellCaptureWorkflowState.ONE_ACTIVE &&
                active.single() == reservation.targetDeviceId -> baseState

            baseState == ShellCaptureWorkflowState.UNCERTAIN &&
                uncertain == setOf(reservation.targetDeviceId) -> baseState

            else -> ShellCaptureWorkflowState.COLLISION_RISK
        }
        val semantics = when (state) {
            ShellCaptureWorkflowState.NO_MANAGED_DEVICES -> WorkflowSemantics(
                "No managed capture device",
                "devices-none",
                ShellSemanticTone.NEUTRAL,
                ShellLiveAnnouncement.NONE,
            )

            ShellCaptureWorkflowState.ALL_VERIFIED_OFF -> WorkflowSemantics(
                "All microphones off — device confirmed",
                "microphones-off-verified",
                ShellSemanticTone.VERIFIED_OFF,
                ShellLiveAnnouncement.POLITE,
            )

            ShellCaptureWorkflowState.STARTING -> WorkflowSemantics(
                "Starting capture — awaiting device evidence",
                "microphone-starting",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.ASSERTIVE,
            )

            ShellCaptureWorkflowState.ONE_ACTIVE -> WorkflowSemantics(
                "One microphone active",
                "microphone-active",
                ShellSemanticTone.PRIVACY_ACTIVE,
                ShellLiveAnnouncement.ASSERTIVE,
            )

            ShellCaptureWorkflowState.UNCERTAIN -> WorkflowSemantics(
                "Microphone state uncertain — treat as recording",
                "microphone-uncertain",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.ASSERTIVE,
            )

            ShellCaptureWorkflowState.COLLISION_RISK -> WorkflowSemantics(
                "More than one microphone may be active — stop or verify each device",
                "microphones-collision-risk",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.ASSERTIVE,
            )
        }
        return ShellCaptureWorkflowPresentation(
            state = state,
            label = semantics.label,
            iconKey = semantics.iconKey,
            tone = semantics.tone,
            liveAnnouncement = semantics.announcement,
            activeDeviceIds = active,
            uncertainDeviceIds = uncertain,
            admissionReservation = reservation,
        )
    }

    private fun resolveSelection(
        devices: List<ShellProjection>,
        workflow: ShellCaptureWorkflowPresentation,
        preferredDeviceId: DeviceId?,
    ): DeviceId? {
        if (preferredDeviceId != null && devices.any { it.deviceId == preferredDeviceId }) {
            return preferredDeviceId
        }
        workflow.soleActiveDeviceId?.let { return it }
        workflow.admissionReservation?.targetDeviceId?.let { reserved ->
            if (devices.any { it.deviceId == reserved }) return reserved
        }
        return devices.firstOrNull {
            it.link.freshness == ObservationFreshness.FRESH && it.link.value == LinkState.READY
        }?.deviceId ?: devices.firstOrNull()?.deviceId
    }

    private fun attachment(link: AxisObservation<LinkState>): ShellAttachmentPresentation {
        if (link.freshness != ObservationFreshness.FRESH) {
            return ShellAttachmentPresentation(
                ShellAttachmentKind.UNKNOWN,
                "Connection state unavailable",
                "connection-unknown",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
            )
        }
        return when (link.value) {
            LinkState.READY -> ShellAttachmentPresentation(
                ShellAttachmentKind.ATTACHED,
                "Connected",
                "device-connected",
                ShellSemanticTone.INFORMATION,
            )

            LinkState.CONNECTING -> ShellAttachmentPresentation(
                ShellAttachmentKind.ATTACHING,
                "Connecting",
                "device-connecting",
                ShellSemanticTone.INFORMATION,
            )

            LinkState.AUTHENTICATING -> ShellAttachmentPresentation(
                ShellAttachmentKind.ATTACHING,
                "Authenticating device",
                "device-authenticating",
                ShellSemanticTone.INFORMATION,
            )

            LinkState.DISCONNECTED -> ShellAttachmentPresentation(
                ShellAttachmentKind.DETACHED,
                "Disconnected",
                "device-disconnected",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
            )

            LinkState.DEGRADED -> ShellAttachmentPresentation(
                ShellAttachmentKind.DEGRADED,
                "Connection degraded",
                "device-connection-degraded",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
            )
        }
    }

    private fun physicalOutput(
        device: ShellProjection,
        report: DeviceOutputTruth?,
        policy: ShellOutputFreshnessPolicy,
    ): ShellPhysicalOutputPresentation {
        val observation = report?.visible
        if (observation == null || !isCurrentDeviceOutput(device, observation, policy)) {
            return output(
                ShellPhysicalOutputKind.UNVERIFIED,
                "Physical privacy output not reported",
                "physical-output-unverified",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.NONE,
            )
        }

        val visible = observation.value
        if (visible.health == DeviceVisibleOutputHealth.DRIVE_FAILED) {
            return output(
                ShellPhysicalOutputKind.FAILED,
                "Physical privacy output drive failed",
                "physical-output-failed",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.ASSERTIVE,
            )
        }
        if (visible.health != DeviceVisibleOutputHealth.OPERATIONAL) {
            return output(
                ShellPhysicalOutputKind.UNVERIFIED,
                "Physical privacy output state unknown",
                "physical-output-unverified",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.NONE,
            )
        }

        val privacySemantic = visible.semantic in PRIVACY_OUTPUTS
        val captureAssurance = device.capture.value.assurance
        if (captureAssurance != CaptureAssurance.VERIFIED_OFF && !privacySemantic) {
            return output(
                ShellPhysicalOutputKind.CONTRADICTORY,
                "Physical privacy output contradicts microphone state — treat as recording",
                "physical-output-contradiction",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.ASSERTIVE,
            )
        }
        if (captureAssurance == CaptureAssurance.VERIFIED_OFF && privacySemantic) {
            return output(
                ShellPhysicalOutputKind.CONTRADICTORY,
                "Privacy signal remains active while microphone is device-confirmed off",
                "physical-output-contradiction",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.ASSERTIVE,
            )
        }

        return when (visible.semantic) {
            DeviceVisibleOutputSemantic.PRIVACY_RECORDING,
            DeviceVisibleOutputSemantic.PRIVACY_VOICE_TURN,
            -> output(
                ShellPhysicalOutputKind.PRIVACY_ACTIVE_CONFIRMED,
                "Physical privacy signal reported active",
                "physical-privacy-active",
                ShellSemanticTone.PRIVACY_ACTIVE,
                ShellLiveAnnouncement.ASSERTIVE,
            )

            DeviceVisibleOutputSemantic.PRIVACY_UNKNOWN -> output(
                ShellPhysicalOutputKind.PRIVACY_UNKNOWN_CONFIRMED,
                "Physical privacy signal reports an unknown microphone state",
                "physical-privacy-unknown",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.ASSERTIVE,
            )

            DeviceVisibleOutputSemantic.NO_SIGNAL -> output(
                ShellPhysicalOutputKind.NO_SIGNAL_CONFIRMED,
                "Physical status output reported inactive",
                "physical-output-inactive",
                ShellSemanticTone.NEUTRAL,
                ShellLiveAnnouncement.NONE,
            )

            DeviceVisibleOutputSemantic.BOOTING,
            DeviceVisibleOutputSemantic.PAIRING,
            DeviceVisibleOutputSemantic.UPDATING,
            DeviceVisibleOutputSemantic.VALIDATING,
            DeviceVisibleOutputSemantic.RECOVERY_REQUIRED,
            DeviceVisibleOutputSemantic.WARNING,
            DeviceVisibleOutputSemantic.STATUS,
            -> output(
                ShellPhysicalOutputKind.STATUS_CONFIRMED,
                "Physical status output reported",
                "physical-status-output",
                ShellSemanticTone.INFORMATION,
                ShellLiveAnnouncement.NONE,
            )

            DeviceVisibleOutputSemantic.UNKNOWN -> error(
                "An operational output report cannot have an unknown semantic",
            )
        }
    }

    private fun isCurrentDeviceOutput(
        device: ShellProjection,
        output: AxisObservation<DeviceVisibleOutput>,
        policy: ShellOutputFreshnessPolicy,
    ): Boolean {
        val outputSession = output.connectionSessionGeneration
        val linkSession = device.link.connectionSessionGeneration
        return output.authority == ProjectionAuthority.DEVICE_REPORTED &&
            output.freshness == ObservationFreshness.FRESH &&
            output.observedAtEpochMillis <= device.projectedAtEpochMillis &&
            device.projectedAtEpochMillis - output.observedAtEpochMillis <= policy.maxAgeMillis &&
            device.link.freshness == ObservationFreshness.FRESH &&
            device.link.value in setOf(LinkState.READY, LinkState.DEGRADED) &&
            outputSession != null && outputSession == linkSession
    }

    private fun fault(
        observation: AxisObservation<FaultStatus>,
        output: ShellPhysicalOutputPresentation,
    ): ShellFaultPresentation {
        if (output.kind in setOf(ShellPhysicalOutputKind.FAILED, ShellPhysicalOutputKind.CONTRADICTORY)) {
            return faultPresentation(
                ShellFaultKind.PRIVACY_CRITICAL,
                "Physical privacy output requires immediate attention",
                "privacy-fault-critical",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.ASSERTIVE,
                observation.value.failure,
            )
        }
        if (observation.freshness != ObservationFreshness.FRESH) {
            if (observation.value.severity == FaultSeverity.FATAL_PRIVACY) {
                return faultPresentation(
                    ShellFaultKind.PRIVACY_CRITICAL,
                    "Privacy fault has not been proven cleared",
                    "privacy-fault-critical",
                    ShellSemanticTone.PRIVACY_UNCERTAIN,
                    ShellLiveAnnouncement.ASSERTIVE,
                    observation.value.failure,
                )
            }
            return faultPresentation(
                ShellFaultKind.UNKNOWN,
                "Device fault state unavailable",
                "fault-unknown",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.NONE,
                observation.value.failure,
            )
        }
        return when (observation.value.severity) {
            FaultSeverity.NONE -> faultPresentation(
                ShellFaultKind.CLEAR,
                "No device fault reported",
                "fault-clear",
                ShellSemanticTone.NEUTRAL,
                ShellLiveAnnouncement.NONE,
            )

            FaultSeverity.WARNING -> faultPresentation(
                ShellFaultKind.WARNING,
                "Device warning",
                "fault-warning",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.POLITE,
                observation.value.failure,
            )

            FaultSeverity.RECOVERABLE -> faultPresentation(
                ShellFaultKind.RECOVERABLE,
                "Device action required",
                "fault-recoverable",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.ASSERTIVE,
                observation.value.failure,
            )

            FaultSeverity.FATAL_PRIVACY -> faultPresentation(
                ShellFaultKind.PRIVACY_CRITICAL,
                "Critical privacy fault — treat microphone state as unknown",
                "privacy-fault-critical",
                ShellSemanticTone.PRIVACY_UNCERTAIN,
                ShellLiveAnnouncement.ASSERTIVE,
                observation.value.failure,
            )
        }
    }

    private fun adjustActions(
        base: DeviceControlPresentation,
        workflow: ShellCaptureWorkflowPresentation,
        output: ShellPhysicalOutputPresentation,
    ): Map<ShellControlAction, ShellActionAvailability> = base.actions.mapValues { (action, local) ->
        val reservation = workflow.admissionReservation
        if (
            action == ShellControlAction.STOP_CAPTURE &&
            reservation?.targetDeviceId == base.device.deviceId
        ) {
            return@mapValues ShellActionAvailability.enabled()
        }
        if (!local.enabled) return@mapValues local

        val outputUnsafe = output.kind in setOf(
            ShellPhysicalOutputKind.FAILED,
            ShellPhysicalOutputKind.CONTRADICTORY,
        )
        when (action) {
            ShellControlAction.START_RECORDING -> when {
                outputUnsafe -> ShellActionAvailability.blocked("PHYSICAL_OUTPUT_NOT_TRUSTWORTHY")
                reservation != null -> ShellActionAvailability.blocked("CAPTURE_ADMISSION_RESERVED")
                workflow.state != ShellCaptureWorkflowState.ALL_VERIFIED_OFF ->
                    ShellActionAvailability.blocked("FLEET_CAPTURE_NOT_QUIESCENT")

                else -> local
            }

            ShellControlAction.START_VOICE_TURN -> when {
                outputUnsafe -> ShellActionAvailability.blocked("PHYSICAL_OUTPUT_NOT_TRUSTWORTHY")
                base.device.capture.value.kind == CapturePresentationKind.RECORDING &&
                    workflow.state == ShellCaptureWorkflowState.ONE_ACTIVE &&
                    workflow.soleActiveDeviceId == base.device.deviceId -> local

                reservation != null -> ShellActionAvailability.blocked("CAPTURE_ADMISSION_RESERVED")
                base.device.capture.value.kind == CapturePresentationKind.VERIFIED_OFF &&
                    workflow.state == ShellCaptureWorkflowState.ALL_VERIFIED_OFF -> local

                else -> ShellActionAvailability.blocked("FLEET_CAPTURE_NOT_QUIESCENT")
            }

            else -> local
        }
    }

    private fun output(
        kind: ShellPhysicalOutputKind,
        label: String,
        iconKey: String,
        tone: ShellSemanticTone,
        announcement: ShellLiveAnnouncement,
    ) = ShellPhysicalOutputPresentation(
        kind = kind,
        label = label,
        iconKey = iconKey,
        tone = tone,
        liveAnnouncement = announcement,
    )

    private fun faultPresentation(
        kind: ShellFaultKind,
        label: String,
        iconKey: String,
        tone: ShellSemanticTone,
        announcement: ShellLiveAnnouncement,
        failure: ExpectedFailure? = null,
    ) = ShellFaultPresentation(kind, label, iconKey, tone, announcement, failure)

    private data class WorkflowSemantics(
        val label: String,
        val iconKey: String,
        val tone: ShellSemanticTone,
        val announcement: ShellLiveAnnouncement,
    )

    private val PRIVACY_OUTPUTS = setOf(
        DeviceVisibleOutputSemantic.PRIVACY_RECORDING,
        DeviceVisibleOutputSemantic.PRIVACY_VOICE_TURN,
        DeviceVisibleOutputSemantic.PRIVACY_UNKNOWN,
    )
}

interface PortableControlPlane {
    val presentation: StateFlow<PortableControlPlanePresentation>

    /** Null clears the preference and restores deterministic automatic focus. */
    suspend fun select(deviceId: DeviceId?): ShellSelectionResult

    /** Dispatches an existing idempotent command envelope after advisory product checks. */
    suspend fun submit(command: ShellCommand): ShellCommandResult
}

sealed interface ShellSelectionResult {
    data class Applied(
        val preferredDeviceId: DeviceId?,
        val resolvedDeviceId: DeviceId?,
    ) : ShellSelectionResult

    data class NotManaged(val deviceId: DeviceId) : ShellSelectionResult
}

/**
 * Lifecycle-neutral product façade. The host owns [scope]; cancelling it stops projection collection.
 * Runtime/device owners still revalidate every command and remain the effect authority.
 */
class DefaultPortableControlPlane(
    scope: CoroutineScope,
    private val shell: ShellApplication,
    private val outputPort: ShellDeviceOutputTruthPort = UnavailableShellDeviceOutputTruthPort,
    private val outputFreshnessPolicy: ShellOutputFreshnessPolicy = ShellOutputFreshnessPolicy(),
) : PortableControlPlane {
    private val preferredDevice = MutableStateFlow<DeviceId?>(null)
    private val admissionMutex = Mutex()
    private var admittedCapture: AdmittedCapture? = null
    private val admissionReservation = MutableStateFlow<ShellCaptureAdmissionReservation?>(null)

    init {
        scope.launch {
            shell.projection.collect { fleet ->
                admissionMutex.withLock { reconcileAdmission(fleet) }
            }
        }
    }

    override val presentation: StateFlow<PortableControlPlanePresentation> = combine(
        shell.projection,
        outputPort.outputTruth,
        preferredDevice,
        admissionReservation,
    ) { fleet, output, preferred, reservation ->
        current(fleet, output, preferred, reservation)
    }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = current(
                shell.projection.value,
                outputPort.outputTruth.value,
                preferredDevice.value,
                admissionReservation.value,
            ),
        )

    override suspend fun select(deviceId: DeviceId?): ShellSelectionResult {
        if (deviceId != null && shell.projection.value.devices.none { it.deviceId == deviceId }) {
            return ShellSelectionResult.NotManaged(deviceId)
        }
        preferredDevice.value = deviceId
        val resolved = current(
            shell.projection.value,
            outputPort.outputTruth.value,
            deviceId,
            admissionReservation.value,
        ).selectedDeviceId
        return ShellSelectionResult.Applied(deviceId, resolved)
    }

    override suspend fun submit(command: ShellCommand): ShellCommandResult {
        admissionMutex.withLock {
            val fleet = shell.projection.value
            reconcileAdmission(fleet)
            val existing = admittedCapture
            if (existing?.command == command) {
                existing.inFlightDispatches += 1
                return@withLock
            }

            val current = current(
                fleet,
                outputPort.outputTruth.value,
                preferredDevice.value,
                admissionReservation.value,
            )
            val device = current.devices.firstOrNull {
                it.control.device.deviceId == command.targetDeviceId
            } ?: return rejected(command, "DEVICE_NOT_MANAGED")
            val action = command.intent.controlAction
            val availability = device.control.actions.getValue(action)
            if (!availability.enabled) {
                return rejected(
                    command,
                    requireNotNull(availability.blockedReasonCode),
                    action,
                )
            }
            if (
                action in CAPTURE_START_ACTIONS &&
                device.control.device.capture.value.kind == CapturePresentationKind.VERIFIED_OFF
            ) {
                val admitted = AdmittedCapture(command, inFlightDispatches = 1)
                admittedCapture = admitted
                admissionReservation.value = admitted.public
            }
        }

        var definitelyDidNotAcquire = false
        try {
            val result = shell.submit(command)
            definitelyDidNotAcquire = result.definitivelyDidNotAcquire
            return result
        } finally {
            admissionMutex.withLock {
                settleAdmission(command, definitelyDidNotAcquire)
            }
        }
    }

    private fun current(
        fleet: FleetShellProjection,
        output: Map<DeviceId, DeviceOutputTruth>,
        preferred: DeviceId?,
        reservation: ShellCaptureAdmissionReservation?,
    ) = PortableControlPlaneProjector.project(
        fleet = fleet,
        outputTruth = output,
        preferredDeviceId = preferred,
        outputFreshnessPolicy = outputFreshnessPolicy,
        admissionReservation = reservation,
    )

    private fun reconcileAdmission(fleet: FleetShellProjection) {
        val admitted = admittedCapture ?: return
        val target = fleet.devices.firstOrNull { it.deviceId == admitted.command.targetDeviceId }
            ?: return
        val leftVerifiedOff = target.capture.value.assurance != CaptureAssurance.VERIFIED_OFF ||
            target.pendingCommandId == admitted.command.id
        if (leftVerifiedOff) admitted.observedEffect = true
        if (
            admitted.observedEffect &&
            target.capture.value.assurance == CaptureAssurance.VERIFIED_OFF &&
            target.pendingCommandId == null
        ) {
            clearAdmission()
        }
    }

    private fun clearAdmission() {
        admittedCapture = null
        admissionReservation.value = null
    }

    private fun settleAdmission(
        command: ShellCommand,
        definitelyDidNotAcquire: Boolean,
    ) {
        val admitted = admittedCapture ?: return
        if (admitted.command != command) return
        check(admitted.inFlightDispatches > 0) {
            "Capture admission dispatch count cannot underflow"
        }
        admitted.inFlightDispatches -= 1
        if (!definitelyDidNotAcquire) admitted.possibleEffect = true
        if (admitted.inFlightDispatches == 0 && !admitted.possibleEffect) clearAdmission()
    }

    private fun rejected(
        command: ShellCommand,
        reason: String,
        action: ShellControlAction? = null,
    ) = ShellCommandResult.Terminal(
        commandId = command.id,
        outcome = ShellTerminalOutcome.REJECTED,
        failure = ExpectedFailure(
            category = FailureCategory.REJECTED_POLICY,
            code = FailureCode("SHELL_PRODUCT_ACTION_BLOCKED"),
            retryable = false,
            correlationId = command.correlationId,
            redactedEvidence = buildMap {
                put("reason", reason)
                action?.let { put("action", it.name) }
            },
        ),
    )

    private data class AdmittedCapture(
        val command: ShellCommand,
        var observedEffect: Boolean = false,
        var inFlightDispatches: Int = 0,
        var possibleEffect: Boolean = false,
    ) {
        val public = ShellCaptureAdmissionReservation(command.id, command.targetDeviceId)
    }

    private companion object {
        val CAPTURE_START_ACTIONS = setOf(
            ShellControlAction.START_RECORDING,
            ShellControlAction.START_VOICE_TURN,
        )
    }
}

private val ShellCommandResult.definitivelyDidNotAcquire: Boolean
    get() = this is ShellCommandResult.Terminal && outcome in setOf(
        ShellTerminalOutcome.REFUSED,
        ShellTerminalOutcome.REJECTED,
    )

private val ShellIntent.controlAction: ShellControlAction
    get() = when (this) {
        ShellIntent.RepeatStatus -> ShellControlAction.REPEAT_STATUS
        ShellIntent.StartRecording -> ShellControlAction.START_RECORDING
        ShellIntent.StopRecording -> ShellControlAction.STOP_CAPTURE
        is ShellIntent.StartVoiceTurn -> ShellControlAction.START_VOICE_TURN
        ShellIntent.StopVoiceTurn -> ShellControlAction.STOP_VOICE_TURN
        ShellIntent.BeginPairing -> ShellControlAction.BEGIN_PAIRING
        is ShellIntent.PrepareUpdate -> ShellControlAction.PREPARE_UPDATE
        ShellIntent.RequestShutdown -> ShellControlAction.REQUEST_SHUTDOWN
        is ShellIntent.ConfirmPhysicalAction -> ShellControlAction.CONFIRM_PHYSICAL_ACTION
    }
