package dev.gumi.edge.runtime.device

import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-local ownership boundary that permits at most one supervisor for each stable device. */
class DeviceSupervisorDirectory(
    private val parentScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val supervisors = mutableMapOf<DeviceId, DeviceSupervisor>()
    private var closed = false

    suspend fun claim(
        deviceId: DeviceId,
        captureHardware: CaptureHardwarePort,
        mailboxCapacity: Int = DeviceSupervisor.DEFAULT_MAILBOX_CAPACITY,
    ): DeviceSupervisorClaimResult = mutex.withLock {
        if (closed) {
            return@withLock DeviceSupervisorClaimResult.Rejected(
                ExpectedFailure(
                    category = FailureCategory.CANCELLED,
                    code = FailureCode("DEVICE_SUPERVISOR_DIRECTORY_CLOSED"),
                    retryable = false,
                ),
            )
        }
        supervisors[deviceId]?.let { current ->
            if (current.projection.value.lifecycle != DeviceSupervisorLifecycle.CLOSED) {
                return@withLock DeviceSupervisorClaimResult.AlreadyOwned(current)
            }
        }

        val supervisor = DeviceSupervisor(
            deviceId = deviceId,
            parentScope = parentScope,
            captureHardware = captureHardware,
            mailboxCapacity = mailboxCapacity,
        )
        supervisors[deviceId] = supervisor
        DeviceSupervisorClaimResult.Claimed(supervisor)
    }

    suspend fun current(deviceId: DeviceId): DeviceSupervisor? = mutex.withLock {
        supervisors[deviceId]?.takeUnless { supervisor ->
            closed || supervisor.projection.value.lifecycle == DeviceSupervisorLifecycle.CLOSED
        }
    }

    suspend fun release(deviceId: DeviceId) {
        // A hardware effect may need non-cancellable cleanup before close completes. Keep the
        // exact entry visible so the same device cannot be reclaimed, but never hold the global
        // directory mutex while awaiting that cleanup: unrelated devices must remain operable.
        val releasing = mutex.withLock { supervisors[deviceId] } ?: return
        releasing.close()
        mutex.withLock {
            if (supervisors[deviceId] === releasing) {
                supervisors.remove(deviceId)
            }
        }
    }

    suspend fun close() {
        val claimed = mutex.withLock {
            closed = true
            supervisors.values.toList().also { supervisors.clear() }
        }
        claimed.forEach { it.close() }
    }
}

sealed interface DeviceSupervisorClaimResult {
    data class Claimed(
        val supervisor: DeviceSupervisor,
    ) : DeviceSupervisorClaimResult

    data class AlreadyOwned(
        val supervisor: DeviceSupervisor,
    ) : DeviceSupervisorClaimResult

    data class Rejected(
        val failure: ExpectedFailure,
    ) : DeviceSupervisorClaimResult
}
