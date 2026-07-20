package dev.gumi.edge.runtime.operational

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.ble.BleCentral
import dev.gumi.edge.sdk.capability.power.PowerStatus

/** Stable provisioning truth. It never contains or derives a transport address. */
data class ProvisionedDeviceBinding(
    val deviceId: DeviceId,
)

sealed interface ProvisionedDeviceBindingResult {
    val operation: OperationalRuntimeOperation

    data class Bound(
        override val operation: OperationalRuntimeOperation,
        val binding: ProvisionedDeviceBinding,
    ) : ProvisionedDeviceBindingResult

    /** Definitive failure: no binding or resource escaped this call. */
    data class Failed(
        override val operation: OperationalRuntimeOperation,
        val failure: ExpectedFailure,
    ) : ProvisionedDeviceBindingResult
}

fun interface ProvisionedDeviceBindingPort {
    suspend fun load(operation: OperationalRuntimeOperation): ProvisionedDeviceBindingResult
}

sealed interface OperationalLeaseResult {
    val operation: OperationalRuntimeOperation

    data class Completed(
        override val operation: OperationalRuntimeOperation,
    ) : OperationalLeaseResult

    data class Failed(
        override val operation: OperationalRuntimeOperation,
        val failure: ExpectedFailure,
    ) : OperationalLeaseResult

    /** The adapter cannot prove whether the requested lifetime boundary was released. */
    data class OutcomeUnknown(
        override val operation: OperationalRuntimeOperation,
        val failure: ExpectedFailure,
    ) : OperationalLeaseResult
}

/**
 * Lifetime ownership of reconciled durable media state.
 *
 * Coordinators using the storage must be stopped by [quiesce] before [close]. Both operations are
 * idempotent by [OperationalRuntimeOperation].
 */
interface OperationalStorageLease {
    suspend fun quiesce(operation: OperationalRuntimeOperation): OperationalLeaseResult

    suspend fun close(operation: OperationalRuntimeOperation): OperationalLeaseResult
}

sealed interface OperationalStorageOpenResult {
    val operation: OperationalRuntimeOperation

    data class Ready(
        override val operation: OperationalRuntimeOperation,
        val lease: OperationalStorageLease,
        val backlog: OperationalBacklog,
        val backlogScope: OperationalBacklogScope = OperationalBacklogScope.DEVICE,
    ) : OperationalStorageOpenResult

    /** Definitive failure: the adapter settled and released every partial storage resource. */
    data class Failed(
        override val operation: OperationalRuntimeOperation,
        val failure: ExpectedFailure,
    ) : OperationalStorageOpenResult

    /**
     * Opening crossed the lifetime-ownership boundary, but failure cleanup could not prove that the
     * storage resource was released. The lease is deliberately retained so the runtime's mandatory
     * cleanup path can retry it; no storage coordinator or payload port escapes this result.
     */
    data class OutcomeUnknown(
        override val operation: OperationalRuntimeOperation,
        val lease: OperationalStorageLease,
        val failure: ExpectedFailure,
    ) : OperationalStorageOpenResult
}

fun interface OperationalStoragePort {
    suspend fun openAndReconcile(
        operation: OperationalRuntimeOperation,
        binding: ProvisionedDeviceBinding,
    ): OperationalStorageOpenResult
}

/** Exclusive lifetime ownership of the host transport facility used for one device session. */
interface DeviceTransportLease {
    val bleCentral: BleCentral

    suspend fun release(operation: OperationalRuntimeOperation): OperationalLeaseResult
}

sealed interface DeviceTransportLeaseResult {
    val operation: OperationalRuntimeOperation

    data class Acquired(
        override val operation: OperationalRuntimeOperation,
        val lease: DeviceTransportLease,
    ) : DeviceTransportLeaseResult

    /** Definitive failure: no transport lease escaped this call. */
    data class Failed(
        override val operation: OperationalRuntimeOperation,
        val failure: ExpectedFailure,
    ) : DeviceTransportLeaseResult
}

fun interface DeviceTransportLeasePort {
    suspend fun acquire(
        operation: OperationalRuntimeOperation,
        binding: ProvisionedDeviceBinding,
    ): DeviceTransportLeaseResult
}

sealed interface OperationalEndpointResolutionResult {
    val operation: OperationalRuntimeOperation

    data class Resolved(
        override val operation: OperationalRuntimeOperation,
        /** Process-local and ephemeral; callers must not persist or project this value. */
        val endpoint: EndpointCandidate,
    ) : OperationalEndpointResolutionResult

    data class Failed(
        override val operation: OperationalRuntimeOperation,
        val failure: ExpectedFailure,
    ) : OperationalEndpointResolutionResult
}

/** Resolves a currently usable endpoint from authoritative provisioning, without changing identity. */
fun interface OperationalEndpointResolutionPort {
    suspend fun resolve(
        operation: OperationalRuntimeOperation,
        binding: ProvisionedDeviceBinding,
    ): OperationalEndpointResolutionResult
}

/**
 * One observational status refresh, fenced to the exact runtime owner and physical session.
 *
 * [commandId] and [correlationId] identify the caller's request. [expectedOwner] is ownership
 * evidence only; a refresh implementation must never relabel it with whichever session is current.
 */
data class OperationalPowerRefreshRequest(
    val commandId: CommandId,
    val correlationId: CorrelationId,
    val expectedOwner: OperationalRuntimeOperation,
)

sealed interface OperationalPowerRefreshResult {
    val request: OperationalPowerRefreshRequest
    val replayed: Boolean

    data class Completed(
        override val request: OperationalPowerRefreshRequest,
        val status: PowerStatus,
        override val replayed: Boolean = false,
    ) : OperationalPowerRefreshResult

    /** The runtime proves that no current-device status was accepted for this request. */
    data class Failed(
        override val request: OperationalPowerRefreshRequest,
        val failure: ExpectedFailure,
        override val replayed: Boolean = false,
    ) : OperationalPowerRefreshResult

    /** The read crossed the device boundary, but no current status can be established. */
    data class OutcomeUnknown(
        override val request: OperationalPowerRefreshRequest,
        val failure: ExpectedFailure,
        override val replayed: Boolean = false,
    ) : OperationalPowerRefreshResult
}

fun interface OperationalPowerRefreshPort {
    suspend fun refreshPower(
        request: OperationalPowerRefreshRequest,
    ): OperationalPowerRefreshResult
}
