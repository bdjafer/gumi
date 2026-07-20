package dev.gumi.edge.shell.application

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId

/** A semantic command envelope. Hosts decide how it is transported; they never encode device packets. */
data class ShellCommand(
    val id: CommandId,
    val correlationId: CorrelationId,
    val targetDeviceId: DeviceId,
    val issuedAtEpochMillis: Long,
    val intent: ShellIntent,
) {
    init {
        require(issuedAtEpochMillis >= 0) { "Command time cannot be negative" }
        if (intent is ShellIntent.StartVoiceTurn) {
            require(intent.admission.expiresAtEpochMillis > issuedAtEpochMillis) {
                "A VoiceTurn command requires an unexpired admission lease"
            }
        }
    }
}

sealed interface ShellIntent {
    data object RepeatStatus : ShellIntent

    data object StartRecording : ShellIntent

    data object StopRecording : ShellIntent

    data class StartVoiceTurn(val admission: VoiceTurnAdmission) : ShellIntent

    data object StopVoiceTurn : ShellIntent

    data object BeginPairing : ShellIntent

    data class PrepareUpdate(val artifactId: String) : ShellIntent {
        init {
            requireOpaqueValue("Update artifact ID", artifactId)
        }
    }

    data object RequestShutdown : ShellIntent

    data class ConfirmPhysicalAction(val confirmationLeaseId: String) : ShellIntent {
        init {
            requireOpaqueValue("Confirmation lease ID", confirmationLeaseId)
        }
    }
}

/** A reference to an admission decision, never a bearer credential or provider token. */
data class VoiceTurnAdmission(
    val leaseId: String,
    val expiresAtEpochMillis: Long,
) {
    init {
        requireOpaqueValue("VoiceTurn admission lease ID", leaseId)
        require(expiresAtEpochMillis >= 0) { "VoiceTurn admission expiry cannot be negative" }
    }
}

private fun requireOpaqueValue(label: String, value: String) {
    require(value.isNotBlank()) { "$label cannot be blank" }
    require(value == value.trim()) { "$label cannot have surrounding whitespace" }
    require(value.length <= 200) { "$label cannot exceed 200 characters" }
    require(value.none(Char::isISOControl)) { "$label cannot contain control characters" }
}
