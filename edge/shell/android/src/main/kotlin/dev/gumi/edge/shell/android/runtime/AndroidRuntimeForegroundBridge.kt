package dev.gumi.edge.shell.android.runtime

import dev.gumi.edge.runtime.host.RuntimeHostExecutionPort
import dev.gumi.edge.runtime.host.RuntimeHostForegroundStartResult
import dev.gumi.edge.runtime.host.RuntimeHostForegroundStopResult
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.host.RuntimeHostProjection
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory

internal sealed interface AndroidPlatformForegroundStartResult {
    data object Entered : AndroidPlatformForegroundStartResult

    /** The platform call provably did not cross into foreground execution. */
    data class Denied(val failure: ExpectedFailure) : AndroidPlatformForegroundStartResult

    /** The endpoint cannot prove whether the foreground call crossed its side-effect boundary. */
    data class OutcomeUnknown(val failure: ExpectedFailure) : AndroidPlatformForegroundStartResult
}

internal sealed interface AndroidPlatformForegroundStopResult {
    data object Released : AndroidPlatformForegroundStopResult

    data class Failed(val failure: ExpectedFailure) : AndroidPlatformForegroundStopResult

    data class OutcomeUnknown(val failure: ExpectedFailure) : AndroidPlatformForegroundStopResult
}

internal sealed interface AndroidRuntimeForegroundRefreshResult {
    data object Refreshed : AndroidRuntimeForegroundRefreshResult

    /** No exact live endpoint/lease pair exists, so no platform convergence was proved. */
    data class Unavailable(val failure: ExpectedFailure) : AndroidRuntimeForegroundRefreshResult

    data class Failed(val failure: ExpectedFailure) : AndroidRuntimeForegroundRefreshResult
}

internal sealed interface AndroidRuntimeNotificationState {
    data object Starting : AndroidRuntimeNotificationState

    data class Running(val projection: RuntimeHostProjection) : AndroidRuntimeNotificationState
}

/**
 * The actual Android Service endpoint. It contains platform calls but no runtime or device policy.
 * Every implementation must redact notification content and make foreground entry synchronous.
 */
internal interface AndroidRuntimeServiceEndpoint {
    val token: Long

    fun enterForeground(
        state: AndroidRuntimeNotificationState,
        correlationId: CorrelationId,
    ): AndroidPlatformForegroundStartResult

    fun leaveForeground(correlationId: CorrelationId): AndroidPlatformForegroundStopResult

    fun commandSettled(startId: Int, stopService: Boolean)
}

/**
 * Bridges Android's prompt onStartCommand foreground promotion to RuntimeHost's ordered execution
 * port. A provisional Android lease is never presented as a portable host lease until the exact
 * RuntimeHost operation claims the matching command identity.
 */
internal class AndroidRuntimeForegroundBridge : RuntimeHostExecutionPort {
    private val lock = Any()
    private val provisional = linkedMapOf<CommandId, ArrayDeque<BootstrapRecord>>()
    private var endpoint: AndroidRuntimeServiceEndpoint? = null
    private var hostLease: HostLease? = null
    private var foregroundMayBeHeld = false

    fun bootstrap(
        requestId: CommandId,
        correlationId: CorrelationId,
        serviceEndpoint: AndroidRuntimeServiceEndpoint,
    ): AndroidPlatformForegroundStartResult = synchronized(lock) {
        val currentEndpoint = endpoint
        if (currentEndpoint != null && currentEndpoint.token != serviceEndpoint.token &&
            (foregroundMayBeHeld || provisional.isNotEmpty() || hostLease != null)
        ) {
            return@synchronized AndroidPlatformForegroundStartResult.Denied(
                androidRuntimeFailure(
                    category = FailureCategory.REJECTED_POLICY,
                    code = "ANDROID_RUNTIME_SERVICE_ENDPOINT_CONFLICT",
                    retryable = true,
                    correlationId = correlationId,
                ),
            )
        }

        val sameEndpoint = currentEndpoint?.token == serviceEndpoint.token
        endpoint = serviceEndpoint
        val result = if (foregroundMayBeHeld && sameEndpoint) {
            AndroidPlatformForegroundStartResult.Entered
        } else {
            serviceEndpoint.enterForeground(AndroidRuntimeNotificationState.Starting, correlationId)
        }
        if (result is AndroidPlatformForegroundStartResult.Entered ||
            result is AndroidPlatformForegroundStartResult.OutcomeUnknown
        ) {
            foregroundMayBeHeld = true
        }
        provisional.getOrPut(requestId, ::ArrayDeque).addLast(
            BootstrapRecord(serviceEndpoint.token, result),
        )
        result
    }

    override suspend fun enterForeground(
        operation: RuntimeHostOperation,
    ): RuntimeHostForegroundStartResult = synchronized(lock) {
        val record = takeProvisional(operation.commandId)
            ?: return@synchronized RuntimeHostForegroundStartResult.Denied(
                operation,
                androidRuntimeFailure(
                    category = FailureCategory.REJECTED_POLICY,
                    code = "ANDROID_FOREGROUND_BOOTSTRAP_MISSING",
                    retryable = true,
                    correlationId = operation.correlationId,
                ),
            )

        when (val result = record.result) {
            AndroidPlatformForegroundStartResult.Entered -> {
                hostLease = HostLease(record.endpointToken)
                foregroundMayBeHeld = true
                RuntimeHostForegroundStartResult.Entered(operation)
            }

            is AndroidPlatformForegroundStartResult.Denied ->
                RuntimeHostForegroundStartResult.Denied(
                    operation,
                    result.failure.withCorrelation(operation.correlationId),
                )

            is AndroidPlatformForegroundStartResult.OutcomeUnknown -> {
                foregroundMayBeHeld = true
                RuntimeHostForegroundStartResult.OutcomeUnknown(
                    operation,
                    result.failure.withCorrelation(operation.correlationId),
                )
            }
        }
    }

    override suspend fun leaveForeground(
        operation: RuntimeHostOperation,
    ): RuntimeHostForegroundStopResult = synchronized(lock) {
        val target = endpoint
        provisional.clear()
        hostLease = null
        if (target == null) {
            foregroundMayBeHeld = true
            return@synchronized RuntimeHostForegroundStopResult.OutcomeUnknown(
                operation,
                androidRuntimeFailure(
                    category = FailureCategory.UNAVAILABLE,
                    code = "ANDROID_FOREGROUND_ENDPOINT_LOST",
                    retryable = false,
                    correlationId = operation.correlationId,
                ),
            )
        }

        when (val result = target.leaveForeground(operation.correlationId)) {
            AndroidPlatformForegroundStopResult.Released -> {
                foregroundMayBeHeld = false
                RuntimeHostForegroundStopResult.Released(operation)
            }

            is AndroidPlatformForegroundStopResult.Failed -> {
                foregroundMayBeHeld = true
                RuntimeHostForegroundStopResult.Failed(
                    operation,
                    result.failure.withCorrelation(operation.correlationId),
                )
            }

            is AndroidPlatformForegroundStopResult.OutcomeUnknown -> {
                foregroundMayBeHeld = true
                RuntimeHostForegroundStopResult.OutcomeUnknown(
                    operation,
                    result.failure.withCorrelation(operation.correlationId),
                )
            }
        }
    }

    /** Releases an unclaimed bootstrap only when no other start or host lease owns it. */
    fun abandon(
        requestId: CommandId,
        correlationId: CorrelationId,
    ): AndroidPlatformForegroundStopResult? = synchronized(lock) {
        takeProvisional(requestId)
        val target = if (hostLease == null && provisional.isEmpty() && foregroundMayBeHeld) {
            endpoint
        } else {
            null
        } ?: return@synchronized null

        target.leaveForeground(correlationId).also { result ->
            foregroundMayBeHeld = result !is AndroidPlatformForegroundStopResult.Released
        }
    }

    /** Claims a fresh bootstrap when RuntimeHost proves it already owns the logical host lease. */
    fun acknowledgeAlreadyForeground(requestId: CommandId): ExpectedFailure? = synchronized(lock) {
        val record = takeProvisional(requestId) ?: return@synchronized androidRuntimeFailure(
            category = FailureCategory.REJECTED_POLICY,
            code = "ANDROID_FOREGROUND_BOOTSTRAP_MISSING",
            retryable = true,
        )
        when (val result = record.result) {
            AndroidPlatformForegroundStartResult.Entered -> {
                hostLease = HostLease(record.endpointToken)
                foregroundMayBeHeld = true
                null
            }

            is AndroidPlatformForegroundStartResult.Denied -> result.failure
            is AndroidPlatformForegroundStartResult.OutcomeUnknown -> result.failure
        }
    }

    /** Best-effort notification refresh; failure remains local and never rewrites runtime truth. */
    fun refresh(
        projection: RuntimeHostProjection,
        correlationId: CorrelationId,
        serviceEndpoint: AndroidRuntimeServiceEndpoint,
    ): AndroidRuntimeForegroundRefreshResult = synchronized(lock) {
        val target = endpoint
            ?: return@synchronized AndroidRuntimeForegroundRefreshResult.Unavailable(
                androidRuntimeFailure(
                    category = FailureCategory.UNAVAILABLE,
                    code = "ANDROID_FOREGROUND_REFRESH_ENDPOINT_MISSING",
                    retryable = false,
                    correlationId = correlationId,
                ),
            )
        val lease = hostLease
            ?: return@synchronized AndroidRuntimeForegroundRefreshResult.Unavailable(
                androidRuntimeFailure(
                    category = FailureCategory.REJECTED_POLICY,
                    code = "ANDROID_FOREGROUND_REFRESH_LEASE_MISSING",
                    retryable = false,
                    correlationId = correlationId,
                ),
            )
        if (target.token != serviceEndpoint.token || lease.endpointToken != serviceEndpoint.token) {
            return@synchronized AndroidRuntimeForegroundRefreshResult.Unavailable(
                androidRuntimeFailure(
                    category = FailureCategory.REPLAYED,
                    code = "ANDROID_FOREGROUND_REFRESH_ENDPOINT_STALE",
                    retryable = false,
                    correlationId = correlationId,
                ),
            )
        }
        when (val result = target.enterForeground(
            AndroidRuntimeNotificationState.Running(projection),
            correlationId,
        )) {
            AndroidPlatformForegroundStartResult.Entered ->
                AndroidRuntimeForegroundRefreshResult.Refreshed

            is AndroidPlatformForegroundStartResult.Denied ->
                AndroidRuntimeForegroundRefreshResult.Failed(result.failure)

            is AndroidPlatformForegroundStartResult.OutcomeUnknown ->
                AndroidRuntimeForegroundRefreshResult.Failed(result.failure)
        }
    }

    /** Called from Service.onDestroy; a held lease becomes outcome-unknown, never released by fiat. */
    fun detach(serviceEndpoint: AndroidRuntimeServiceEndpoint) = synchronized(lock) {
        if (endpoint?.token == serviceEndpoint.token) {
            endpoint = null
            if (foregroundMayBeHeld) {
                val failure = androidRuntimeFailure(
                    category = FailureCategory.UNAVAILABLE,
                    code = "ANDROID_FOREGROUND_ENDPOINT_LOST",
                    retryable = false,
                )
                provisional.values.forEach { records ->
                    records.indices.forEach { index ->
                        val record = records[index]
                        if (record.endpointToken == serviceEndpoint.token) {
                            records[index] = record.copy(
                                result = AndroidPlatformForegroundStartResult.OutcomeUnknown(
                                    failure,
                                ),
                            )
                        }
                    }
                }
            } else {
                provisional.values.forEach { records ->
                    records.removeAll { it.endpointToken == serviceEndpoint.token }
                }
                provisional.entries.removeAll { it.value.isEmpty() }
            }
        }
    }

    fun mayNeedService(): Boolean = synchronized(lock) {
        foregroundMayBeHeld || provisional.isNotEmpty() || hostLease != null
    }

    private data class BootstrapRecord(
        val endpointToken: Long,
        val result: AndroidPlatformForegroundStartResult,
    )

    private data class HostLease(val endpointToken: Long)

    private fun takeProvisional(requestId: CommandId): BootstrapRecord? {
        val records = provisional[requestId] ?: return null
        val record = records.removeFirstOrNull()
        if (records.isEmpty()) provisional.remove(requestId)
        return record
    }
}

private fun ExpectedFailure.withCorrelation(correlationId: CorrelationId): ExpectedFailure =
    copy(correlationId = correlationId)
