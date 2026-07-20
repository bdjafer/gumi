package dev.gumi.edge.shell.application

import dev.gumi.edge.sdk.DeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Portable meanings for the device's currently reported visible output.
 *
 * A concrete device adapter maps its own LEDs, screen, or other visible surface into these meanings.
 * Pattern timing, channel values, and firmware enums remain owned by that device capsule.
 */
enum class DeviceVisibleOutputSemantic {
    NO_SIGNAL,
    PRIVACY_RECORDING,
    PRIVACY_VOICE_TURN,
    PRIVACY_UNKNOWN,
    BOOTING,
    PAIRING,
    UPDATING,
    VALIDATING,
    RECOVERY_REQUIRED,
    WARNING,
    STATUS,
    UNKNOWN,
}

enum class DeviceVisibleOutputHealth {
    OPERATIONAL,
    DRIVE_FAILED,
    UNKNOWN,
}

data class DeviceVisibleOutput(
    val semantic: DeviceVisibleOutputSemantic,
    val health: DeviceVisibleOutputHealth,
) {
    init {
        when (health) {
            DeviceVisibleOutputHealth.OPERATIONAL -> require(
                semantic != DeviceVisibleOutputSemantic.UNKNOWN,
            ) { "An operational output report requires a known semantic" }

            DeviceVisibleOutputHealth.DRIVE_FAILED,
            DeviceVisibleOutputHealth.UNKNOWN,
            -> require(semantic == DeviceVisibleOutputSemantic.UNKNOWN) {
                "A failed or unknown output cannot claim a visible semantic"
            }
        }
    }
}

/** One device-bound output report. The observation must come from the current physical session. */
data class DeviceOutputTruth(
    val deviceId: DeviceId,
    val visible: AxisObservation<DeviceVisibleOutput>,
)

/**
 * Input port for physical output evidence. An empty map means unavailable, not that every light is off.
 *
 * Implementations publish immutable maps and retain device-specific protocol types behind this port.
 */
interface ShellDeviceOutputTruthPort {
    val outputTruth: StateFlow<Map<DeviceId, DeviceOutputTruth>>
}

/** Safe default for stock devices or adapters that cannot report their physical output. */
object UnavailableShellDeviceOutputTruthPort : ShellDeviceOutputTruthPort {
    private val unavailable = MutableStateFlow<Map<DeviceId, DeviceOutputTruth>>(emptyMap())
    override val outputTruth: StateFlow<Map<DeviceId, DeviceOutputTruth>> = unavailable.asStateFlow()
}
