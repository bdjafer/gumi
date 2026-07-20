package dev.gumi.edge.runtime.operational

import dev.gumi.edge.runtime.host.RuntimeHostRecoveryPort
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-owned runtime boundary for one provisioned device.
 *
 * The interface exists so a process registry can own heterogeneous device runtimes without importing
 * a concrete device driver. Implementations retain their own serialized device/session lifecycle.
 */
interface OperationalRuntimeNode : RuntimeHostRecoveryPort, OperationalPowerRefreshPort {
    val projection: StateFlow<OperationalRuntimeProjection>

    /** Permanently closes this process-local node after its recovery resources were cleaned. */
    suspend fun close()
}
