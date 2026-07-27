package dev.gumi.edge.shell.application

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId

data class ShellCommandIdentity(
    val commandId: CommandId,
    val correlationId: CorrelationId,
)

/** Platform adapter supplies UUIDs or another collision-resistant source. */
fun interface ShellCommandIdentitySource {
    fun next(): ShellCommandIdentity
}

sealed interface ShellActionQualification {
    data class VoiceTurn(val admission: VoiceTurnAdmission) : ShellActionQualification

    data class FirmwareUpdate(val artifactId: String) : ShellActionQualification

    data class PhysicalConfirmation(val confirmationLeaseId: String) : ShellActionQualification
}

enum class ShellActionQualificationRequirement {
    VOICE_TURN_ADMISSION,
    REVIEWED_FIRMWARE_ARTIFACT,
    PHYSICAL_CONFIRMATION_LEASE,
}

sealed interface ShellProductActionResult {
    data class QualificationRequired(
        val deviceId: DeviceId,
        val action: ShellControlAction,
        val requirement: ShellActionQualificationRequirement,
    ) : ShellProductActionResult

    data class Invalid(
        val deviceId: DeviceId,
        val action: ShellControlAction,
        val reasonCode: String,
    ) : ShellProductActionResult

    data class Dispatched(
        /** Retain and retry this exact envelope when the outcome is unknown. */
        val command: ShellCommand,
        val result: ShellCommandResult,
    ) : ShellProductActionResult
}

/**
 * Reusable product callback adapter.
 *
 * Native Android, a Raspberry Pi UI, or another shell can forward the selected device and semantic
 * action here. Device packets, BLE, firmware details, and admission acquisition stay behind ports.
 */
class PortableControlPlaneActionAdapter(
    private val controlPlane: PortableControlPlane,
    private val clock: ShellClock,
    private val identities: ShellCommandIdentitySource,
) {
    suspend fun dispatch(
        deviceId: DeviceId,
        action: ShellControlAction,
        qualification: ShellActionQualification? = null,
    ): ShellProductActionResult {
        val now = clock.nowEpochMillis()
        if (now < 0) return invalid(deviceId, action, "SHELL_CLOCK_INVALID")
        val intent = when (action) {
            ShellControlAction.REPEAT_STATUS -> unqualified(
                qualification,
                ShellIntent.RepeatStatus,
            )

            ShellControlAction.START_RECORDING -> unqualified(
                qualification,
                ShellIntent.StartRecording,
            )

            ShellControlAction.STOP_CAPTURE -> unqualified(
                qualification,
                ShellIntent.StopRecording,
            )

            ShellControlAction.START_VOICE_TURN -> when (qualification) {
                null -> return qualificationRequired(
                    deviceId,
                    action,
                    ShellActionQualificationRequirement.VOICE_TURN_ADMISSION,
                )

                is ShellActionQualification.VoiceTurn ->
                    if (qualification.admission.expiresAtEpochMillis <= now) {
                        return invalid(deviceId, action, "VOICE_TURN_ADMISSION_EXPIRED")
                    } else {
                        ShellIntent.StartVoiceTurn(qualification.admission)
                    }

                else -> return invalid(deviceId, action, "ACTION_QUALIFICATION_MISMATCH")
            }

            ShellControlAction.STOP_VOICE_TURN -> unqualified(
                qualification,
                ShellIntent.StopVoiceTurn,
            )

            ShellControlAction.BEGIN_PAIRING -> unqualified(
                qualification,
                ShellIntent.BeginPairing,
            )

            ShellControlAction.PREPARE_UPDATE -> when (qualification) {
                null -> return qualificationRequired(
                    deviceId,
                    action,
                    ShellActionQualificationRequirement.REVIEWED_FIRMWARE_ARTIFACT,
                )

                is ShellActionQualification.FirmwareUpdate ->
                    ShellIntent.PrepareUpdate(qualification.artifactId)

                else -> return invalid(deviceId, action, "ACTION_QUALIFICATION_MISMATCH")
            }

            ShellControlAction.CONFIRM_PHYSICAL_ACTION -> when (qualification) {
                null -> return qualificationRequired(
                    deviceId,
                    action,
                    ShellActionQualificationRequirement.PHYSICAL_CONFIRMATION_LEASE,
                )

                is ShellActionQualification.PhysicalConfirmation ->
                    ShellIntent.ConfirmPhysicalAction(qualification.confirmationLeaseId)

                else -> return invalid(deviceId, action, "ACTION_QUALIFICATION_MISMATCH")
            }

            ShellControlAction.REQUEST_SHUTDOWN -> unqualified(
                qualification,
                ShellIntent.RequestShutdown,
            )
        }
        if (intent == null) return invalid(deviceId, action, "ACTION_QUALIFICATION_NOT_EXPECTED")
        val identity = identities.next()
        val command = ShellCommand(
            id = identity.commandId,
            correlationId = identity.correlationId,
            targetDeviceId = deviceId,
            issuedAtEpochMillis = now,
            intent = intent,
        )
        return ShellProductActionResult.Dispatched(command, controlPlane.submit(command))
    }

    /** Retries without minting a new idempotency or correlation identity. */
    suspend fun retry(command: ShellCommand): ShellProductActionResult.Dispatched =
        ShellProductActionResult.Dispatched(command, controlPlane.submit(command))

    private fun unqualified(
        qualification: ShellActionQualification?,
        intent: ShellIntent,
    ): ShellIntent? = if (qualification == null) intent else null

    private fun qualificationRequired(
        deviceId: DeviceId,
        action: ShellControlAction,
        requirement: ShellActionQualificationRequirement,
    ) = ShellProductActionResult.QualificationRequired(deviceId, action, requirement)

    private fun invalid(
        deviceId: DeviceId,
        action: ShellControlAction,
        reason: String,
    ) = ShellProductActionResult.Invalid(deviceId, action, reason)
}
