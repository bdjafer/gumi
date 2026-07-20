package dev.gumi.edge.shell.android.runtime

import dev.gumi.edge.runtime.host.RuntimeHost
import dev.gumi.edge.runtime.host.RuntimeHostCommandOutcome
import dev.gumi.edge.runtime.host.RuntimeHostCommandResult
import dev.gumi.edge.runtime.host.RuntimeHostExecutionState
import dev.gumi.edge.runtime.host.RuntimeHostProjection
import dev.gumi.edge.runtime.host.RuntimeHostRequest
import dev.gumi.edge.runtime.host.RuntimeHostRecoveryState
import dev.gumi.edge.runtime.host.RuntimeHostRestartPolicy
import dev.gumi.edge.runtime.host.RuntimeHostStartOrigin
import dev.gumi.edge.runtime.host.RuntimeHostStopOrigin
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class AndroidRuntimeOwnerLifecycle {
    ACTIVE,
    SHUTTING_DOWN,
    CLOSED,
}

internal data class AndroidRuntimeOwnerProjection(
    val lifecycle: AndroidRuntimeOwnerLifecycle = AndroidRuntimeOwnerLifecycle.ACTIVE,
    val outstandingDeliveries: Int = 0,
    val sequence: Long = 0,
    val lastResult: RuntimeHostCommandResult? = null,
    val lastFailure: ExpectedFailure? = null,
    val lastPlatformFailure: ExpectedFailure? = null,
)

internal sealed interface AndroidRuntimeAdmissionResult {
    data object Accepted : AndroidRuntimeAdmissionResult

    data class Rejected(
        val failure: ExpectedFailure,
        val serviceStillNeeded: Boolean,
    ) : AndroidRuntimeAdmissionResult
}

internal interface AndroidRuntimeHostController {
    val projection: StateFlow<RuntimeHostProjection>

    suspend fun start(request: RuntimeHostRequest.Start): RuntimeHostCommandResult

    suspend fun stop(request: RuntimeHostRequest.Stop): RuntimeHostCommandResult

    suspend fun close()
}

internal class PortableAndroidRuntimeHostController(
    private val host: RuntimeHost,
) : AndroidRuntimeHostController {
    override val projection: StateFlow<RuntimeHostProjection> = host.projection

    override suspend fun start(request: RuntimeHostRequest.Start): RuntimeHostCommandResult =
        host.start(request)

    override suspend fun stop(request: RuntimeHostRequest.Stop): RuntimeHostCommandResult =
        host.stop(request)

    override suspend fun close() = host.close()
}

/**
 * One application-process owner for all Service deliveries. Starts dispatch independently so a stop
 * can preempt a suspended start. Stops coalesce behind one serialized cleanup barrier while retaining
 * every command identity and user-stop semantic. RuntimeHost remains the transition authority.
 */
internal class AndroidRuntimeProcessOwner(
    parentScope: CoroutineScope,
    private val host: AndroidRuntimeHostController,
    private val foreground: AndroidRuntimeForegroundBridge,
    private val processResources: AndroidRuntimeProcessResources =
        EmptyAndroidRuntimeProcessResources,
    private val deliveryCapacity: Int = DEFAULT_DELIVERY_CAPACITY,
) {
    init {
        require(deliveryCapacity > 0) { "Android runtime delivery capacity must be positive" }
    }

    private val lock = Any()
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val deliveryJobs = linkedSetOf<Job>()
    private val stopMutex = Mutex()
    private var accepting = true
    private var outstanding = 0
    private var stopBatch: StopBatch? = null
    private var shutdown: CompletableDeferred<Unit>? = null
    private var shutdownSequence = 0uL
    private var internalStopSequence = 0uL

    private val mutableProjection = MutableStateFlow(AndroidRuntimeOwnerProjection())
    val projection: StateFlow<AndroidRuntimeOwnerProjection> = mutableProjection.asStateFlow()
    val hostProjection: StateFlow<RuntimeHostProjection> = host.projection

    fun submit(
        request: RuntimeHostRequest,
        startId: Int,
        endpoint: AndroidRuntimeServiceEndpoint,
    ): AndroidRuntimeAdmissionResult = when (request) {
        is RuntimeHostRequest.Start -> submitStart(request, startId, endpoint)
        is RuntimeHostRequest.Stop -> submitStop(request, startId, endpoint)
    }

    private fun submitStart(
        request: RuntimeHostRequest.Start,
        startId: Int,
        endpoint: AndroidRuntimeServiceEndpoint,
    ): AndroidRuntimeAdmissionResult {
        val job: Job
        synchronized(lock) {
            val rejection = when {
                !accepting || !ownerJob.isActive -> admissionFailure(
                    code = "ANDROID_RUNTIME_OWNER_CLOSED",
                    retryable = false,
                    correlationId = request.correlationId,
                )

                request.origin != RuntimeHostStartOrigin.EXPLICIT_USER &&
                    host.projection.value.restartPolicy == RuntimeHostRestartPolicy.USER_STOPPED ->
                    androidRuntimeFailure(
                        category = FailureCategory.REJECTED_POLICY,
                        code = "ANDROID_RUNTIME_AUTOSTART_SUPPRESSED_BY_USER_STOP",
                        retryable = false,
                        correlationId = request.correlationId,
                    )

                outstanding >= deliveryCapacity -> admissionFailure(
                    code = "ANDROID_RUNTIME_DELIVERY_CAPACITY_EXHAUSTED",
                    retryable = true,
                    correlationId = request.correlationId,
                )

                else -> null
            }
            if (rejection != null) {
                recordFailure(rejection)
                return AndroidRuntimeAdmissionResult.Rejected(
                    rejection,
                    serviceStillNeeded = !serviceCanStopAfterStart(),
                )
            }

            val bootstrap = foreground.bootstrap(request.id, request.correlationId, endpoint)
            if (bootstrap is AndroidPlatformForegroundStartResult.Denied) {
                foreground.abandon(request.id, request.correlationId)
                recordFailure(bootstrap.failure)
                return AndroidRuntimeAdmissionResult.Rejected(
                    bootstrap.failure,
                    serviceStillNeeded = !serviceCanStopAfterStart(),
                )
            }

            outstanding += 1
            updateProjection { it.copy(outstandingDeliveries = outstanding) }
            job = scope.launch(start = CoroutineStart.LAZY) {
                handleStartDelivery(request, startId, endpoint)
            }
            deliveryJobs += job
            job.invokeOnCompletion {
                synchronized(lock) {
                    deliveryJobs -= job
                    outstanding -= 1
                    updateProjection { it.copy(outstandingDeliveries = outstanding) }
                }
            }
        }
        job.start()
        return AndroidRuntimeAdmissionResult.Accepted
    }

    /**
     * Stop effects are serialized and coalesced behind one batch. Every accepted stop keeps its own
     * command identity, so an explicit user stop always reaches RuntimeHost and establishes
     * USER_STOPPED even when an internal prerequisite-loss stop arrived first.
     */
    private fun submitStop(
        request: RuntimeHostRequest.Stop,
        startId: Int,
        endpoint: AndroidRuntimeServiceEndpoint,
    ): AndroidRuntimeAdmissionResult {
        val jobToStart: Job
        synchronized(lock) {
            if (!accepting || !ownerJob.isActive) {
                val failure = admissionFailure(
                    code = "ANDROID_RUNTIME_OWNER_CLOSED",
                    retryable = false,
                    correlationId = request.correlationId,
                )
                recordFailure(failure)
                return AndroidRuntimeAdmissionResult.Rejected(
                    failure,
                    serviceStillNeeded = !serviceCanStopAfterStart(),
                )
            }

            val delivery = StopDelivery(request, startId, endpoint)
            outstanding += 1
            updateProjection { it.copy(outstandingDeliveries = outstanding) }
            stopBatch?.let { batch ->
                batch.pending.addLast(delivery)
                batch.all += delivery
                return AndroidRuntimeAdmissionResult.Accepted
            }

            val batch = StopBatch(
                pending = ArrayDeque(listOf(delivery)),
                all = mutableListOf(delivery),
            )
            stopBatch = batch
            val job = scope.launch(start = CoroutineStart.LAZY) { handleStopBatch(batch) }
            deliveryJobs += job
            job.invokeOnCompletion {
                synchronized(lock) { deliveryJobs -= job }
            }
            jobToStart = job
        }
        jobToStart.start()
        return AndroidRuntimeAdmissionResult.Accepted
    }

    /** Records only the stable failure and returns whether this Service instance must remain alive. */
    fun recordInvalidDelivery(failure: ExpectedFailure): Boolean {
        recordFailure(failure)
        return !serviceCanStopAfterStart()
    }

    fun endpointDestroyed(endpoint: AndroidRuntimeServiceEndpoint) {
        foreground.detach(endpoint)
        val requiresReconciliation = foreground.mayNeedService() ||
            host.projection.value.execution !in setOf(
                RuntimeHostExecutionState.STOPPED,
                RuntimeHostExecutionState.START_DENIED,
            )
        if (requiresReconciliation) {
            recordPlatformFailure(
                androidRuntimeFailure(
                    category = FailureCategory.UNAVAILABLE,
                    code = "ANDROID_RUNTIME_SERVICE_ENDPOINT_DESTROYED",
                    retryable = false,
                ),
            )
            submit(internalStopRequest("endpoint-destroyed"), 0, endpoint)
        }
    }

    fun serviceStillNeeded(): Boolean = !serviceCanStopAfterStart()

    /**
     * Graceful application-owner shutdown. Admission closes first, then an owner-shutdown stop is
     * settled while adapters remain live, accepted deliveries converge, and only then forced close
     * is invoked. Caller cancellation cannot cut that sequence in half.
     */
    suspend fun shutdown(): Unit = withContext(NonCancellable) {
        val (completion, performShutdown) = synchronized(lock) {
            shutdown?.let { return@synchronized it to false }
            accepting = false
            updateProjection { it.copy(lifecycle = AndroidRuntimeOwnerLifecycle.SHUTTING_DOWN) }
            CompletableDeferred<Unit>().also { shutdown = it } to true
        }
        if (!performShutdown) {
            completion.await()
            return@withContext
        }

        try {
            stopMutex.withLock { host.stop(ownerShutdownRequest("initial")) }
            val accepted = synchronized(lock) { deliveryJobs.toList() }
            accepted.joinAll()
            if (host.projection.value.execution != RuntimeHostExecutionState.STOPPED) {
                stopMutex.withLock { host.stop(ownerShutdownRequest("settled")) }
            }
            val resourceResult = try {
                processResources.close()
            } catch (cancelled: CancellationException) {
                AndroidRuntimeProcessResourceCloseResult.OutcomeUnknown(
                    androidRuntimeFailure(
                        category = FailureCategory.CANCELLED,
                        code = "ANDROID_RUNTIME_PROCESS_RESOURCE_CLOSE_CANCELLED",
                        retryable = false,
                    ),
                )
            } catch (_: Throwable) {
                AndroidRuntimeProcessResourceCloseResult.OutcomeUnknown(
                    androidRuntimeFailure(
                        category = FailureCategory.INTERNAL,
                        code = "ANDROID_RUNTIME_PROCESS_RESOURCE_CLOSE_OUTCOME_UNKNOWN",
                        retryable = false,
                    ),
                )
            }
            when (resourceResult) {
                AndroidRuntimeProcessResourceCloseResult.Closed -> Unit
                is AndroidRuntimeProcessResourceCloseResult.Failed ->
                    recordPlatformFailure(resourceResult.failure)

                is AndroidRuntimeProcessResourceCloseResult.OutcomeUnknown ->
                    recordPlatformFailure(resourceResult.failure)
            }
            host.close()
            ownerJob.cancelAndJoin()
            synchronized(lock) {
                updateProjection { it.copy(lifecycle = AndroidRuntimeOwnerLifecycle.CLOSED) }
            }
            completion.complete(Unit)
        } catch (throwable: Throwable) {
            completion.completeExceptionally(throwable)
            throw throwable
        }
        completion.await()
    }

    private suspend fun handleStartDelivery(
        request: RuntimeHostRequest.Start,
        startId: Int,
        endpoint: AndroidRuntimeServiceEndpoint,
    ) {
        var stopService = false
        try {
            var result = host.start(request)
            var localFailure: ExpectedFailure? = null
            var platformConverged = false
            val outcomeWantsForeground = result.keepsForegroundService()
            var keepsForeground = outcomeWantsForeground &&
                host.projection.value.execution == RuntimeHostExecutionState.FOREGROUND
            if (outcomeWantsForeground && !keepsForeground && result.replayed) {
                localFailure = androidRuntimeFailure(
                    category = FailureCategory.REPLAYED,
                    code = "ANDROID_RUNTIME_REPLAY_NOT_CURRENT_EXECUTION",
                    retryable = false,
                    correlationId = request.correlationId,
                )
            }
            if (keepsForeground) {
                if (result.replayed || result.isAlreadyForegroundNoOp()) {
                    foreground.acknowledgeAlreadyForeground(request.id)?.let { failure ->
                        localFailure = failure
                        result = stopMutex.withLock {
                            host.stop(internalStopRequest("bootstrap-reconciliation"))
                        }
                        keepsForeground = false
                        stopService = serviceCanStopAfterStart()
                    }
                }
                if (keepsForeground) {
                    when (val refresh = foreground.refresh(
                        host.projection.value,
                        request.correlationId,
                        endpoint,
                    )) {
                        AndroidRuntimeForegroundRefreshResult.Refreshed -> platformConverged = true
                        is AndroidRuntimeForegroundRefreshResult.Failed -> {
                            localFailure = refresh.failure
                            result = stopMutex.withLock {
                                host.stop(internalStopRequest("foreground-refresh-failed"))
                            }
                            stopService = serviceCanStopAfterStart()
                        }

                        is AndroidRuntimeForegroundRefreshResult.Unavailable -> {
                            localFailure = refresh.failure
                            result = stopMutex.withLock {
                                host.stop(internalStopRequest("foreground-refresh-unavailable"))
                            }
                            stopService = serviceCanStopAfterStart()
                        }
                    }
                }
            } else {
                platformConverged = foreground.abandon(
                    request.id,
                    request.correlationId,
                ) is AndroidPlatformForegroundStopResult.Released
                stopService = serviceCanStopAfterStart()
            }
            recordResult(result)
            if (localFailure != null) {
                recordPlatformFailure(localFailure)
            } else if (platformConverged) {
                clearPlatformFailure()
            }
        } catch (throwable: Throwable) {
            val failure = androidRuntimeFailure(
                category = FailureCategory.INTERNAL,
                code = "ANDROID_RUNTIME_DELIVERY_FAILED",
                retryable = false,
                correlationId = request.correlationId,
            )
            recordFailure(failure)
            foreground.abandon(request.id, request.correlationId)
            stopService = serviceCanStopAfterStart()
            if (throwable is CancellationException || throwable is Error) throw throwable
        } finally {
            endpoint.commandSettled(startId, stopService)
        }
    }

    private suspend fun handleStopBatch(batch: StopBatch) {
        try {
            while (true) {
                val delivery = synchronized(lock) {
                    batch.pending.removeFirstOrNull().also { next ->
                        if (next == null && stopBatch === batch) stopBatch = null
                    }
                } ?: break
                try {
                    val result = stopMutex.withLock { host.stop(delivery.request) }
                    recordResult(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    val failure = androidRuntimeFailure(
                        category = FailureCategory.INTERNAL,
                        code = "ANDROID_RUNTIME_STOP_DELIVERY_FAILED",
                        retryable = false,
                        correlationId = delivery.request.correlationId,
                    )
                    recordFailure(failure)
                    if (throwable is Error) throw throwable
                }
            }
        } finally {
            val deliveries = synchronized(lock) {
                if (stopBatch === batch) stopBatch = null
                batch.all.toList().also {
                    outstanding -= it.size
                    updateProjection { projection ->
                        projection.copy(outstandingDeliveries = outstanding)
                    }
                }
            }
            val terminalByEndpoint = deliveries
                .groupBy { it.endpoint.token }
                .values
                .mapNotNull { endpointDeliveries -> endpointDeliveries.maxByOrNull { it.startId } }
                .toSet()
            val converged = serviceCanStopAfterStop()
            deliveries.forEach { delivery ->
                delivery.endpoint.commandSettled(
                    delivery.startId,
                    stopService = converged && delivery in terminalByEndpoint,
                )
            }
        }
    }

    private fun serviceCanStopAfterStart(): Boolean {
        if (synchronized(lock) { stopBatch != null }) return false
        val projection = host.projection.value
        return foreground.mayNeedService().not() &&
            projection.execution in setOf(
                RuntimeHostExecutionState.STOPPED,
                RuntimeHostExecutionState.START_DENIED,
            ) &&
            projection.recovery == RuntimeHostRecoveryState.CLEAN
    }

    private fun serviceCanStopAfterStop(): Boolean {
        val projection = host.projection.value
        return foreground.mayNeedService().not() &&
            projection.execution == RuntimeHostExecutionState.STOPPED &&
            projection.recovery == RuntimeHostRecoveryState.CLEAN
    }

    private fun ownerShutdownRequest(suffix: String): RuntimeHostRequest.Stop {
        val sequence = synchronized(lock) {
            shutdownSequence += 1uL
            shutdownSequence
        }
        return RuntimeHostRequest.Stop(
            id = CommandId("android-owner-shutdown-$suffix-$sequence"),
            correlationId = CorrelationId("android-owner-shutdown-$suffix-$sequence"),
            origin = RuntimeHostStopOrigin.OWNER_SHUTDOWN,
        )
    }

    private fun internalStopRequest(suffix: String): RuntimeHostRequest.Stop {
        val sequence = synchronized(lock) {
            internalStopSequence += 1uL
            internalStopSequence
        }
        return RuntimeHostRequest.Stop(
            id = CommandId("android-internal-stop-$suffix-$sequence"),
            correlationId = CorrelationId("android-internal-stop-$suffix-$sequence"),
            origin = RuntimeHostStopOrigin.PREREQUISITE_LOST,
        )
    }

    private fun recordResult(result: RuntimeHostCommandResult) = synchronized(lock) {
        updateProjection {
            it.copy(
                lastResult = result,
                lastFailure = result.record.outcome.failureOrNull(),
            )
        }
    }

    private fun recordFailure(failure: ExpectedFailure) = synchronized(lock) {
        updateProjection { it.copy(lastFailure = failure) }
    }

    private fun recordPlatformFailure(failure: ExpectedFailure) = synchronized(lock) {
        updateProjection { it.copy(lastPlatformFailure = failure) }
    }

    private fun clearPlatformFailure() = synchronized(lock) {
        updateProjection { it.copy(lastPlatformFailure = null) }
    }

    private fun updateProjection(
        transform: (AndroidRuntimeOwnerProjection) -> AndroidRuntimeOwnerProjection,
    ) {
        val current = mutableProjection.value
        mutableProjection.value = transform(current).copy(sequence = current.sequence + 1L)
    }

    private fun admissionFailure(
        code: String,
        retryable: Boolean,
        correlationId: CorrelationId,
    ): ExpectedFailure = androidRuntimeFailure(
        category = FailureCategory.RESOURCE_EXHAUSTED,
        code = code,
        retryable = retryable,
        correlationId = correlationId,
    )

    companion object {
        const val DEFAULT_DELIVERY_CAPACITY: Int = 32
    }

    private data class StopDelivery(
        val request: RuntimeHostRequest.Stop,
        val startId: Int,
        val endpoint: AndroidRuntimeServiceEndpoint,
    )

    private data class StopBatch(
        val pending: ArrayDeque<StopDelivery>,
        val all: MutableList<StopDelivery>,
    )
}

private fun RuntimeHostCommandResult.keepsForegroundService(): Boolean = when (val outcome = record.outcome) {
    is RuntimeHostCommandOutcome.Started -> true
    is RuntimeHostCommandOutcome.NoOp ->
        outcome.code == "RUNTIME_HOST_ALREADY_FOREGROUND"

    is RuntimeHostCommandOutcome.Cancelled,
    is RuntimeHostCommandOutcome.Failed,
    is RuntimeHostCommandOutcome.Rejected,
    is RuntimeHostCommandOutcome.Suppressed,
    RuntimeHostCommandOutcome.Stopped,
    -> false
}

private fun RuntimeHostCommandResult.isAlreadyForegroundNoOp(): Boolean =
    (record.outcome as? RuntimeHostCommandOutcome.NoOp)?.code ==
        "RUNTIME_HOST_ALREADY_FOREGROUND"

private fun RuntimeHostCommandOutcome.failureOrNull(): ExpectedFailure? = when (this) {
    is RuntimeHostCommandOutcome.Cancelled -> failure
    is RuntimeHostCommandOutcome.Failed -> cleanupFailures.lastOrNull() ?: failure
    is RuntimeHostCommandOutcome.Rejected -> failure
    is RuntimeHostCommandOutcome.Suppressed -> failure
    is RuntimeHostCommandOutcome.NoOp,
    is RuntimeHostCommandOutcome.Started,
    RuntimeHostCommandOutcome.Stopped,
    -> null
}
