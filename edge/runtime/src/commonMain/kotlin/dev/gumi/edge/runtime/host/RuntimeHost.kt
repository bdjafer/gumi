package dev.gumi.edge.runtime.host

import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single portable owner for host start/stop, prompt foreground acquisition, and durable recovery.
 *
 * Commands and effect completions cross one bounded mailbox. Slow platform effects run outside the
 * consumer, echo an exact [RuntimeHostOperation], and cannot mutate projection state directly.
 */
class RuntimeHost(
    parentScope: CoroutineScope,
    private val prerequisites: RuntimeHostPrerequisitePort,
    private val execution: RuntimeHostExecutionPort,
    private val recovery: RuntimeHostRecoveryPort,
    mailboxCapacity: Int = DEFAULT_MAILBOX_CAPACITY,
    initialRestartPolicy: RuntimeHostRestartPolicy = RuntimeHostRestartPolicy.AUTOMATIC_ALLOWED,
    terminalCommandLimit: Int = RuntimeHostProjection.DEFAULT_TERMINAL_COMMAND_LIMIT,
) {
    init {
        require(mailboxCapacity > 0) { "Runtime host mailbox capacity must be positive" }
        require(terminalCommandLimit > 0) { "Runtime host terminal command limit must be positive" }
    }

    private val admissionMutex = Mutex()
    private val supervisorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisorJob)
    private val mailbox = Channel<MailboxItem>(mailboxCapacity)
    private var accepting = true
    private var shutdownReply: CompletableDeferred<Unit>? = null
    private var generation = 0uL
    private var active: ActiveState? = null
    private var activeEffect: Job? = null
    private var foregroundMayBeHeld = false
    private var recoveryMayBeHeld = false
    private var recoveryOwnerOperation: RuntimeHostOperation? = null

    private val mutableProjection = MutableStateFlow(
        RuntimeHostProjection(
            restartPolicy = initialRestartPolicy,
            terminalCommandLimit = terminalCommandLimit,
        ),
    )
    val projection: StateFlow<RuntimeHostProjection> = mutableProjection.asStateFlow()

    private val consumerJob = scope.launch { consumeMailbox() }
    private val recoveryEventJob = scope.launch {
        recovery.events.collect { event -> mailbox.send(MailboxItem.RecoveryEvent(event)) }
    }

    suspend fun start(request: RuntimeHostRequest.Start): RuntimeHostCommandResult = submit(request)

    suspend fun stop(request: RuntimeHostRequest.Stop): RuntimeHostCommandResult = submit(request)

    /**
     * Forced process-owner teardown. It is cancellation-resistant and returns only after the mailbox
     * consumer and every owned effect settle. It never claims cleanup that the process cannot prove.
     * A graceful owner must call [stop] with [RuntimeHostStopOrigin.OWNER_SHUTDOWN] before close while
     * its adapters can still perform recovery cleanup and foreground release.
     */
    suspend fun close(): Unit = withContext(NonCancellable) {
        val reply = admissionMutex.withLock {
            shutdownReply?.let { return@withLock it }
            accepting = false
            CompletableDeferred<Unit>().also {
                shutdownReply = it
                mailbox.send(MailboxItem.Shutdown(it))
            }
        }
        reply.await()
        consumerJob.join()
        supervisorJob.cancelAndJoin()
    }

    private suspend fun submit(request: RuntimeHostRequest): RuntimeHostCommandResult {
        val reply = CompletableDeferred<RuntimeHostCommandResult>()
        val rejected = admissionMutex.withLock {
            if (!accepting) {
                rejectedAdmission(request, "RUNTIME_HOST_CLOSED", retryable = false)
            } else {
                val admission = mailbox.trySend(MailboxItem.Command(request, reply))
                if (admission.isSuccess) {
                    null
                } else if (admission.isClosed) {
                    accepting = false
                    rejectedAdmission(request, "RUNTIME_HOST_CLOSED", retryable = false)
                } else {
                    rejectedAdmission(request, "RUNTIME_HOST_MAILBOX_FULL", retryable = true)
                }
            }
        }
        if (rejected != null) return rejected
        return reply.await()
    }

    private fun rejectedAdmission(
        request: RuntimeHostRequest,
        code: String,
        retryable: Boolean,
    ): RuntimeHostCommandResult = RuntimeHostCommandResult(
        record = RuntimeHostCommandRecord(
            request,
            RuntimeHostCommandOutcome.Rejected(
                hostFailure(
                    category = FailureCategory.RESOURCE_EXHAUSTED,
                    code = code,
                    retryable = retryable,
                    correlationId = request.correlationId,
                ),
            ),
        ),
        replayed = false,
    )

    private suspend fun consumeMailbox() {
        for (item in mailbox) {
            when (item) {
                is MailboxItem.Command -> when (val request = item.request) {
                    is RuntimeHostRequest.Start -> handleStart(request, item.reply)
                    is RuntimeHostRequest.Stop -> handleStop(request, item.reply)
                }

                is MailboxItem.PrerequisitesCompleted -> handlePrerequisites(item)
                is MailboxItem.ForegroundCompleted -> handleForeground(item)
                is MailboxItem.RehydrationCompleted -> handleRehydration(item)
                is MailboxItem.CleanupCompleted -> handleCleanup(item)
                is MailboxItem.ReleaseCompleted -> handleRelease(item)
                is MailboxItem.RecoveryEvent -> handleRecoveryEvent(item.event)
                is MailboxItem.Shutdown -> {
                    handleShutdown(item.reply)
                    return
                }
            }
        }
    }

    private fun handleStart(
        request: RuntimeHostRequest.Start,
        reply: CompletableDeferred<RuntimeHostCommandResult>,
    ) {
        if (replayOrConflict(request, reply)) return
        val currentProjection = mutableProjection.value
        if (currentProjection.restartPolicy == RuntimeHostRestartPolicy.USER_STOPPED &&
            request.origin != RuntimeHostStartOrigin.EXPLICIT_USER
        ) {
            completeImmediate(
                request,
                RuntimeHostCommandOutcome.Suppressed(
                    hostFailure(
                        category = FailureCategory.REJECTED_POLICY,
                        code = "RUNTIME_HOST_USER_STOP_SUPPRESSES_AUTOSTART",
                        retryable = false,
                        correlationId = request.correlationId,
                    ),
                ),
                reply,
            )
            return
        }
        active?.let {
            completeImmediate(
                request,
                RuntimeHostCommandOutcome.Rejected(
                    hostFailure(
                        category = FailureCategory.REJECTED_POLICY,
                        code = "RUNTIME_HOST_TRANSITION_IN_PROGRESS",
                        retryable = true,
                        correlationId = request.correlationId,
                    ),
                ),
                reply,
            )
            return
        }
        if (currentProjection.execution == RuntimeHostExecutionState.FOREGROUND) {
            completeImmediate(
                request,
                RuntimeHostCommandOutcome.NoOp("RUNTIME_HOST_ALREADY_FOREGROUND"),
                reply,
            )
            return
        }
        if (currentProjection.execution == RuntimeHostExecutionState.OUTCOME_UNKNOWN) {
            completeImmediate(
                request,
                RuntimeHostCommandOutcome.Rejected(
                    hostFailure(
                        category = FailureCategory.REJECTED_POLICY,
                        code = "RUNTIME_HOST_EXECUTION_RECONCILIATION_REQUIRED",
                        retryable = false,
                        correlationId = request.correlationId,
                    ),
                ),
                reply,
            )
            return
        }
        val operation = allocateOperation(request) ?: run {
            completeImmediate(
                request,
                RuntimeHostCommandOutcome.Rejected(
                    hostFailure(
                        category = FailureCategory.RESOURCE_EXHAUSTED,
                        code = "RUNTIME_HOST_GENERATION_EXHAUSTED",
                        retryable = false,
                        correlationId = request.correlationId,
                    ),
                ),
                reply,
            )
            return
        }
        active = ActiveState(
            request = request,
            operation = operation,
            phase = RuntimeHostOperationPhase.CHECKING_PREREQUISITES,
            waiters = mutableListOf(Waiter(reply, replayed = false)),
        )
        updateProjection {
            it.copy(
                execution = RuntimeHostExecutionState.START_REQUESTED,
                restartPolicy = if (request.origin == RuntimeHostStartOrigin.EXPLICIT_USER) {
                    RuntimeHostRestartPolicy.AUTOMATIC_ALLOWED
                } else {
                    it.restartPolicy
                },
                activeOperation = active!!.toProjection(),
                lastFailure = null,
            )
        }
        launchPrerequisiteInspection(operation)
    }

    private fun handleStop(
        request: RuntimeHostRequest.Stop,
        reply: CompletableDeferred<RuntimeHostCommandResult>,
    ) {
        if (replayOrConflict(request, reply)) return
        val userStop = request.origin == RuntimeHostStopOrigin.EXPLICIT_USER
        if (userStop) {
            updateProjection { it.copy(restartPolicy = RuntimeHostRestartPolicy.USER_STOPPED) }
        }
        // Fence ongoing events before any collector cancellation or resource release can race us.
        recoveryOwnerOperation = null
        val priorActive = active
        if (priorActive?.request is RuntimeHostRequest.Stop) {
            completeImmediate(
                request,
                RuntimeHostCommandOutcome.NoOp("RUNTIME_HOST_ALREADY_STOPPING"),
                reply,
            )
            return
        }

        val priorPhase = priorActive?.phase
        val priorEffect = activeEffect
        if (priorActive != null) {
            completeActive(
                priorActive,
                RuntimeHostCommandOutcome.Cancelled(
                    hostFailure(
                        category = FailureCategory.CANCELLED,
                        code = "RUNTIME_HOST_START_CANCELLED_BY_STOP",
                        retryable = true,
                        correlationId = priorActive.request.correlationId,
                    ),
                ),
            )
        }

        val recoveryCleanupRequired = recoveryMayBeHeld || priorPhase in setOf(
            RuntimeHostOperationPhase.REHYDRATING,
            RuntimeHostOperationPhase.CLEANING_RECOVERY,
            RuntimeHostOperationPhase.RELEASING_FOREGROUND,
        )
        val foregroundReleaseRequired = foregroundMayBeHeld || priorPhase in setOf(
            RuntimeHostOperationPhase.ACQUIRING_FOREGROUND,
            RuntimeHostOperationPhase.REHYDRATING,
            RuntimeHostOperationPhase.CLEANING_RECOVERY,
            RuntimeHostOperationPhase.RELEASING_FOREGROUND,
        )
        if (!recoveryCleanupRequired && !foregroundReleaseRequired) {
            priorEffect?.cancel()
            updateProjection {
                it.copy(
                    execution = RuntimeHostExecutionState.STOPPED,
                    transport = RuntimeHostTransportState.DISCONNECTED,
                    activeOperation = null,
                    lastFailure = null,
                )
            }
            completeImmediate(request, RuntimeHostCommandOutcome.Stopped, reply)
            return
        }

        val operation = allocateOperation(request) ?: run {
            completeImmediate(
                request,
                RuntimeHostCommandOutcome.Rejected(
                    hostFailure(
                        category = FailureCategory.RESOURCE_EXHAUSTED,
                        code = "RUNTIME_HOST_GENERATION_EXHAUSTED",
                        retryable = false,
                        correlationId = request.correlationId,
                    ),
                ),
                reply,
            )
            return
        }
        val stop = ActiveState(
            request = request,
            operation = operation,
            phase = if (recoveryCleanupRequired) {
                RuntimeHostOperationPhase.CLEANING_RECOVERY
            } else {
                RuntimeHostOperationPhase.RELEASING_FOREGROUND
            },
            waiters = mutableListOf(Waiter(reply, replayed = false)),
            releaseRequired = foregroundReleaseRequired,
            cleanupReason = RuntimeHostCleanupReason.STOP_REQUESTED,
        )
        active = stop
        updateProjection {
            it.copy(
                execution = RuntimeHostExecutionState.STOP_REQUESTED,
                recovery = RuntimeHostRecoveryState.RECONCILIATION_REQUIRED,
                activeOperation = stop.toProjection(),
                lastFailure = null,
            )
        }
        launchCleanupAfterCancellation(stop, priorEffect, recoveryCleanupRequired)
    }

    private fun handlePrerequisites(item: MailboxItem.PrerequisitesCompleted) {
        val current = activeFor(item.requestedOperation) ?: return markStaleCompletion()
        val start = current.request as? RuntimeHostRequest.Start ?: return markStaleCompletion()
        if (item.result.operation != item.requestedOperation) {
            markStaleCompletion()
            completeActive(
                current,
                RuntimeHostCommandOutcome.Failed(
                    staleEffectFailure(start, "RUNTIME_HOST_STALE_PREREQUISITE_COMPLETION"),
                ),
            )
            updateProjection {
                it.copy(execution = RuntimeHostExecutionState.START_DENIED, activeOperation = null)
            }
            return
        }
        when (val result = item.result) {
            is RuntimeHostPrerequisiteResult.Failed -> {
                updateProjection {
                    it.copy(
                        execution = RuntimeHostExecutionState.START_DENIED,
                        activeOperation = null,
                        lastFailure = result.failure,
                    )
                }
                completeActive(current, RuntimeHostCommandOutcome.Rejected(result.failure))
            }

            is RuntimeHostPrerequisiteResult.Observed -> {
                val observed = result.prerequisites
                updateProjection {
                    it.copy(
                        association = observed.association,
                        presence = observed.presence,
                        permission = observed.permission,
                    )
                }
                prerequisiteFailure(start, observed)?.let { failure ->
                    updateProjection {
                        it.copy(
                            execution = RuntimeHostExecutionState.START_DENIED,
                            activeOperation = null,
                            lastFailure = failure,
                        )
                    }
                    completeActive(current, RuntimeHostCommandOutcome.Rejected(failure))
                    return
                }
                current.phase = RuntimeHostOperationPhase.ACQUIRING_FOREGROUND
                updateProjection {
                    it.copy(
                        execution = RuntimeHostExecutionState.START_REQUESTED,
                        activeOperation = current.toProjection(),
                    )
                }
                launchForegroundEnter(current.operation)
            }
        }
    }

    private fun handleForeground(item: MailboxItem.ForegroundCompleted) {
        val current = activeFor(item.requestedOperation) ?: return markStaleCompletion()
        val start = current.request as? RuntimeHostRequest.Start ?: return markStaleCompletion()
        if (item.result.operation != item.requestedOperation) {
            markStaleCompletion()
            foregroundMayBeHeld = true
            beginStartFailureCleanup(
                current,
                staleEffectFailure(start, "RUNTIME_HOST_STALE_FOREGROUND_COMPLETION"),
                recoveryCleanupRequired = false,
            )
            return
        }
        when (val result = item.result) {
            is RuntimeHostForegroundStartResult.Entered -> {
                foregroundMayBeHeld = true
                current.phase = RuntimeHostOperationPhase.REHYDRATING
                updateProjection {
                    it.copy(
                        execution = RuntimeHostExecutionState.FOREGROUND,
                        transport = RuntimeHostTransportState.CONNECTING,
                        recovery = RuntimeHostRecoveryState.REHYDRATING,
                        activeOperation = current.toProjection(),
                        lastFailure = null,
                    )
                }
                launchRehydration(current.operation)
            }

            is RuntimeHostForegroundStartResult.Denied -> {
                updateProjection {
                    it.copy(
                        execution = RuntimeHostExecutionState.START_DENIED,
                        transport = RuntimeHostTransportState.DISCONNECTED,
                        recovery = RuntimeHostRecoveryState.CLEAN,
                        activeOperation = null,
                        lastFailure = result.failure,
                    )
                }
                completeActive(current, RuntimeHostCommandOutcome.Rejected(result.failure))
            }

            is RuntimeHostForegroundStartResult.OutcomeUnknown -> {
                foregroundMayBeHeld = true
                beginStartFailureCleanup(
                    current,
                    result.failure,
                    recoveryCleanupRequired = false,
                )
            }
        }
    }

    private fun handleRehydration(item: MailboxItem.RehydrationCompleted) {
        val current = activeFor(item.requestedOperation) ?: return markStaleCompletion()
        val start = current.request as? RuntimeHostRequest.Start ?: return markStaleCompletion()
        if (item.result.operation != item.requestedOperation) {
            markStaleCompletion()
            recoveryMayBeHeld = true
            beginStartFailureCleanup(
                current,
                staleEffectFailure(start, "RUNTIME_HOST_STALE_RECOVERY_COMPLETION"),
                recoveryCleanupRequired = true,
            )
            return
        }
        when (val result = item.result) {
            is RuntimeHostRehydrationResult.Rehydrated -> {
                recoveryMayBeHeld = true
                recoveryOwnerOperation = current.operation
                val pendingEvent = current.pendingRecoveryEvent
                val recoveryState = if (pendingEvent != null) {
                    mutableProjection.value.recovery
                } else if (result.transport == RuntimeHostTransportState.DEGRADED) {
                    RuntimeHostRecoveryState.RECONCILIATION_REQUIRED
                } else {
                    RuntimeHostRecoveryState.CLEAN
                }
                updateProjection {
                    it.copy(
                        execution = RuntimeHostExecutionState.FOREGROUND,
                        transport = if (pendingEvent != null) it.transport else result.transport,
                        recovery = recoveryState,
                        activeOperation = null,
                        lastFailure = if (pendingEvent != null) it.lastFailure else null,
                    )
                }
                completeActive(current, RuntimeHostCommandOutcome.Started(recoveryState))
            }

            is RuntimeHostRehydrationResult.ReconciliationRequired -> {
                recoveryMayBeHeld = true
                recoveryOwnerOperation = current.operation
                val pendingEvent = current.pendingRecoveryEvent
                val recoveryState = if (pendingEvent != null) {
                    mutableProjection.value.recovery
                } else {
                    RuntimeHostRecoveryState.RECONCILIATION_REQUIRED
                }
                updateProjection {
                    it.copy(
                        execution = RuntimeHostExecutionState.FOREGROUND,
                        transport = if (pendingEvent != null) {
                            it.transport
                        } else {
                            RuntimeHostTransportState.DEGRADED
                        },
                        recovery = recoveryState,
                        activeOperation = null,
                        lastFailure = if (pendingEvent != null) it.lastFailure else result.failure,
                    )
                }
                completeActive(
                    current,
                    RuntimeHostCommandOutcome.Started(recoveryState),
                )
            }

            is RuntimeHostRehydrationResult.Failed -> {
                recoveryOwnerOperation = null
                recoveryMayBeHeld = true
                beginStartFailureCleanup(current, result.failure, recoveryCleanupRequired = true)
            }

            is RuntimeHostRehydrationResult.OutcomeUnknown -> {
                recoveryOwnerOperation = null
                recoveryMayBeHeld = true
                current.cleanupOutcomeUnknown = true
                beginStartFailureCleanup(current, result.failure, recoveryCleanupRequired = true)
            }
        }
    }

    private fun handleCleanup(item: MailboxItem.CleanupCompleted) {
        val current = activeFor(item.requestedOperation) ?: return markStaleCompletion()
        if (item.result.operation != item.requestedOperation) {
            markStaleCompletion()
            recordCleanupFailure(
                current,
                hostFailure(
                    category = FailureCategory.REPLAYED,
                    code = "RUNTIME_HOST_STALE_CLEANUP_COMPLETION",
                    retryable = false,
                    correlationId = current.request.correlationId,
                ),
                outcomeUnknown = true,
            )
        } else {
            when (val result = item.result) {
                is RuntimeHostCleanupResult.Cleaned -> recoveryMayBeHeld = false
                is RuntimeHostCleanupResult.Failed -> recordCleanupFailure(
                    current,
                    result.failure,
                    outcomeUnknown = false,
                )

                is RuntimeHostCleanupResult.OutcomeUnknown -> recordCleanupFailure(
                    current,
                    result.failure,
                    outcomeUnknown = true,
                )
            }
        }
        if (current.releaseRequired) {
            current.phase = RuntimeHostOperationPhase.RELEASING_FOREGROUND
            updateProjection { it.copy(activeOperation = current.toProjection()) }
            launchForegroundRelease(current.operation)
        } else {
            finishCleanup(current)
        }
    }

    private fun handleRelease(item: MailboxItem.ReleaseCompleted) {
        val current = activeFor(item.requestedOperation) ?: return markStaleCompletion()
        if (item.result.operation != item.requestedOperation) {
            markStaleCompletion()
            foregroundMayBeHeld = true
            recordCleanupFailure(
                current,
                hostFailure(
                    category = FailureCategory.REPLAYED,
                    code = "RUNTIME_HOST_STALE_RELEASE_COMPLETION",
                    retryable = false,
                    correlationId = current.request.correlationId,
                ),
                outcomeUnknown = true,
            )
        } else {
            when (val result = item.result) {
                is RuntimeHostForegroundStopResult.Released -> foregroundMayBeHeld = false
                is RuntimeHostForegroundStopResult.Failed -> {
                    foregroundMayBeHeld = true
                    recordCleanupFailure(current, result.failure, outcomeUnknown = false)
                }

                is RuntimeHostForegroundStopResult.OutcomeUnknown -> {
                    foregroundMayBeHeld = true
                    recordCleanupFailure(current, result.failure, outcomeUnknown = true)
                }
            }
        }
        finishCleanup(current)
    }

    private fun beginStartFailureCleanup(
        current: ActiveState,
        failure: ExpectedFailure,
        recoveryCleanupRequired: Boolean,
    ) {
        recoveryOwnerOperation = null
        current.pendingOutcome = RuntimeHostCommandOutcome.Failed(failure)
        current.releaseRequired = true
        current.cleanupReason = RuntimeHostCleanupReason.START_FAILED
        current.phase = if (recoveryCleanupRequired) {
            RuntimeHostOperationPhase.CLEANING_RECOVERY
        } else {
            RuntimeHostOperationPhase.RELEASING_FOREGROUND
        }
        updateProjection {
            it.copy(
                execution = RuntimeHostExecutionState.OUTCOME_UNKNOWN,
                transport = RuntimeHostTransportState.DEGRADED,
                recovery = RuntimeHostRecoveryState.RECONCILIATION_REQUIRED,
                activeOperation = current.toProjection(),
                lastFailure = failure,
            )
        }
        if (recoveryCleanupRequired) launchRecoveryCleanup(current)
        else launchForegroundRelease(current.operation)
    }

    private fun finishCleanup(current: ActiveState) {
        recoveryOwnerOperation = null
        val cleanupFailure = current.cleanupFailures.firstOrNull()
        val outcome = when (val pending = current.pendingOutcome) {
            is RuntimeHostCommandOutcome.Failed -> pending.copy(
                cleanupFailures = pending.cleanupFailures + current.cleanupFailures,
            )

            null -> if (cleanupFailure == null) {
                RuntimeHostCommandOutcome.Stopped
            } else {
                RuntimeHostCommandOutcome.Failed(
                    failure = cleanupFailure,
                    cleanupFailures = current.cleanupFailures.drop(1),
                )
            }

            else -> pending
        }
        val recoveryState = when {
            current.cleanupOutcomeUnknown -> RuntimeHostRecoveryState.RECONCILIATION_REQUIRED
            current.cleanupFailures.isNotEmpty() -> RuntimeHostRecoveryState.FAULTED
            else -> RuntimeHostRecoveryState.CLEAN
        }
        updateProjection {
            it.copy(
                execution = if (foregroundMayBeHeld) {
                    RuntimeHostExecutionState.OUTCOME_UNKNOWN
                } else {
                    RuntimeHostExecutionState.STOPPED
                },
                transport = RuntimeHostTransportState.DISCONNECTED,
                recovery = recoveryState,
                activeOperation = null,
                lastFailure = outcome.failureOrNull(),
            )
        }
        completeActive(current, outcome)
    }

    private fun launchPrerequisiteInspection(operation: RuntimeHostOperation) {
        activeEffect = scope.launch {
            val result = try {
                prerequisites.inspect(operation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                RuntimeHostPrerequisiteResult.Failed(
                    operation,
                    hostFailure(
                        category = FailureCategory.INTERNAL,
                        code = "RUNTIME_HOST_PREREQUISITE_PORT_FAILED",
                        retryable = true,
                        correlationId = operation.correlationId,
                    ),
                )
            }
            mailbox.send(MailboxItem.PrerequisitesCompleted(operation, result))
        }
    }

    private fun launchForegroundEnter(operation: RuntimeHostOperation) {
        activeEffect = scope.launch {
            val result = try {
                execution.enterForeground(operation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                RuntimeHostForegroundStartResult.OutcomeUnknown(
                    operation,
                    hostFailure(
                        category = FailureCategory.INTERNAL,
                        code = "RUNTIME_HOST_FOREGROUND_START_FAILED",
                        retryable = false,
                        correlationId = operation.correlationId,
                    ),
                )
            }
            mailbox.send(MailboxItem.ForegroundCompleted(operation, result))
        }
    }

    private fun launchRehydration(operation: RuntimeHostOperation) {
        activeEffect = scope.launch {
            val result = try {
                recovery.rehydrateAndReconcile(operation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                RuntimeHostRehydrationResult.OutcomeUnknown(
                    operation,
                    hostFailure(
                        category = FailureCategory.INTERNAL,
                        code = "RUNTIME_HOST_RECOVERY_PORT_FAILED",
                        retryable = false,
                        correlationId = operation.correlationId,
                    ),
                )
            }
            mailbox.send(MailboxItem.RehydrationCompleted(operation, result))
        }
    }

    private fun launchCleanupAfterCancellation(
        current: ActiveState,
        prior: Job?,
        recoveryCleanupRequired: Boolean,
    ) {
        activeEffect = scope.launch {
            prior?.cancelAndJoin()
            if (recoveryCleanupRequired) {
                val result = invokeRecoveryCleanup(current)
                mailbox.send(MailboxItem.CleanupCompleted(current.operation, result))
            } else if (current.releaseRequired) {
                val result = invokeForegroundRelease(current.operation)
                mailbox.send(MailboxItem.ReleaseCompleted(current.operation, result))
            }
        }
    }

    private fun launchRecoveryCleanup(current: ActiveState) {
        activeEffect = scope.launch {
            val result = invokeRecoveryCleanup(current)
            mailbox.send(MailboxItem.CleanupCompleted(current.operation, result))
        }
    }

    private suspend fun invokeRecoveryCleanup(current: ActiveState): RuntimeHostCleanupResult = try {
        recovery.cleanup(
            RuntimeHostCleanupRequest(
                operation = current.operation,
                reason = requireNotNull(current.cleanupReason),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        RuntimeHostCleanupResult.OutcomeUnknown(
            current.operation,
            hostFailure(
                category = FailureCategory.INTERNAL,
                code = "RUNTIME_HOST_CLEANUP_PORT_FAILED",
                retryable = false,
                correlationId = current.operation.correlationId,
            ),
        )
    }

    private fun launchForegroundRelease(operation: RuntimeHostOperation) {
        activeEffect = scope.launch {
            val result = invokeForegroundRelease(operation)
            mailbox.send(MailboxItem.ReleaseCompleted(operation, result))
        }
    }

    private suspend fun invokeForegroundRelease(
        operation: RuntimeHostOperation,
    ): RuntimeHostForegroundStopResult = try {
        execution.leaveForeground(operation)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        RuntimeHostForegroundStopResult.OutcomeUnknown(
            operation,
            hostFailure(
                category = FailureCategory.INTERNAL,
                code = "RUNTIME_HOST_FOREGROUND_RELEASE_FAILED",
                retryable = false,
                correlationId = operation.correlationId,
            ),
        )
    }

    private fun replayOrConflict(
        request: RuntimeHostRequest,
        reply: CompletableDeferred<RuntimeHostCommandResult>,
    ): Boolean {
        mutableProjection.value.terminalCommands[request.id]?.let { record ->
            if (record.request == request) {
                reply.complete(RuntimeHostCommandResult(record, replayed = true))
            } else {
                completeConflict(request, reply)
            }
            return true
        }
        active?.takeIf { it.request.id == request.id }?.let { current ->
            if (current.request == request) {
                current.waiters += Waiter(reply, replayed = true)
            } else {
                completeConflict(request, reply)
            }
            return true
        }
        return false
    }

    private fun completeConflict(
        request: RuntimeHostRequest,
        reply: CompletableDeferred<RuntimeHostCommandResult>,
    ) {
        val failure = hostFailure(
            category = FailureCategory.REPLAYED,
            code = "RUNTIME_HOST_COMMAND_ID_CONFLICT",
            retryable = false,
            correlationId = request.correlationId,
        )
        updateProjection { it.copy(lastFailure = failure) }
        reply.complete(
            RuntimeHostCommandResult(
                RuntimeHostCommandRecord(request, RuntimeHostCommandOutcome.Rejected(failure)),
                replayed = false,
            ),
        )
    }

    private fun completeImmediate(
        request: RuntimeHostRequest,
        outcome: RuntimeHostCommandOutcome,
        reply: CompletableDeferred<RuntimeHostCommandResult>,
    ) {
        val record = recordTerminal(request, outcome)
        reply.complete(RuntimeHostCommandResult(record, replayed = false))
    }

    private fun completeActive(
        current: ActiveState,
        outcome: RuntimeHostCommandOutcome,
    ) {
        if (active === current) active = null
        val record = recordTerminal(current.request, outcome)
        current.waiters.forEach { waiter ->
            waiter.reply.complete(RuntimeHostCommandResult(record, waiter.replayed))
        }
    }

    private fun recordTerminal(
        request: RuntimeHostRequest,
        outcome: RuntimeHostCommandOutcome,
    ): RuntimeHostCommandRecord {
        val record = RuntimeHostCommandRecord(request, outcome)
        updateProjection { projection ->
            val terminal = LinkedHashMap(projection.terminalCommands)
            terminal[request.id] = record
            var evicted = projection.evictedTerminalCommandCount
            while (terminal.size > projection.terminalCommandLimit) {
                val first = terminal.keys.first()
                terminal.remove(first)
                if (evicted != ULong.MAX_VALUE) evicted += 1uL
            }
            projection.copy(
                activeOperation = if (active == null) null else projection.activeOperation,
                terminalCommands = terminal,
                evictedTerminalCommandCount = evicted,
                lastCommand = record,
                lastFailure = if (
                    outcome is RuntimeHostCommandOutcome.Started &&
                    outcome.recovery != RuntimeHostRecoveryState.CLEAN
                ) {
                    projection.lastFailure
                } else {
                    outcome.failureOrNull()
                },
            )
        }
        return record
    }

    private fun allocateOperation(request: RuntimeHostRequest): RuntimeHostOperation? {
        if (generation == ULong.MAX_VALUE) return null
        generation += 1uL
        return RuntimeHostOperation(request.id, request.correlationId, generation)
    }

    private fun activeFor(operation: RuntimeHostOperation): ActiveState? =
        active?.takeIf { it.operation == operation }

    private fun markStaleCompletion() {
        updateProjection {
            it.copy(
                staleCompletionCount = if (it.staleCompletionCount == ULong.MAX_VALUE) {
                    ULong.MAX_VALUE
                } else {
                    it.staleCompletionCount + 1uL
                },
            )
        }
    }

    private fun recordCleanupFailure(
        current: ActiveState,
        failure: ExpectedFailure,
        outcomeUnknown: Boolean,
    ) {
        current.cleanupFailures += failure
        current.cleanupOutcomeUnknown = current.cleanupOutcomeUnknown || outcomeUnknown
    }

    private fun handleRecoveryEvent(event: RuntimeHostRecoveryEvent) {
        val inFlightOwner = active?.takeIf {
            it.operation == event.operation && it.phase == RuntimeHostOperationPhase.REHYDRATING
        }
        if (recoveryOwnerOperation != event.operation && inFlightOwner == null) {
            markStaleCompletion()
            return
        }
        if (inFlightOwner != null) inFlightOwner.pendingRecoveryEvent = event
        updateProjection {
            it.copy(
                execution = RuntimeHostExecutionState.FOREGROUND,
                transport = when {
                    event is RuntimeHostRecoveryEvent.TransportDisconnected ->
                        RuntimeHostTransportState.DISCONNECTED
                    it.transport == RuntimeHostTransportState.DISCONNECTED -> it.transport
                    else -> RuntimeHostTransportState.DEGRADED
                },
                recovery = when {
                    event is RuntimeHostRecoveryEvent.Faulted -> RuntimeHostRecoveryState.FAULTED
                    it.recovery == RuntimeHostRecoveryState.FAULTED -> it.recovery
                    else -> RuntimeHostRecoveryState.RECONCILIATION_REQUIRED
                },
                lastFailure = event.failure,
            )
        }
    }

    private fun handleShutdown(reply: CompletableDeferred<Unit>) {
        accepting = false
        recoveryOwnerOperation = null
        recoveryEventJob.cancel()
        val current = active
        val phase = current?.phase
        activeEffect?.cancel()
        if (current != null) {
            completeActive(
                current,
                RuntimeHostCommandOutcome.Cancelled(
                    hostFailure(
                        category = FailureCategory.CANCELLED,
                        code = "RUNTIME_HOST_OWNER_CLOSED",
                        retryable = true,
                        correlationId = current.request.correlationId,
                    ),
                ),
            )
        }
        val executionMayRemain = foregroundMayBeHeld || phase in setOf(
            RuntimeHostOperationPhase.ACQUIRING_FOREGROUND,
            RuntimeHostOperationPhase.REHYDRATING,
            RuntimeHostOperationPhase.CLEANING_RECOVERY,
            RuntimeHostOperationPhase.RELEASING_FOREGROUND,
        )
        val recoveryMayRemain = recoveryMayBeHeld || phase in setOf(
            RuntimeHostOperationPhase.REHYDRATING,
            RuntimeHostOperationPhase.CLEANING_RECOVERY,
        )
        updateProjection {
            it.copy(
                execution = if (executionMayRemain) {
                    RuntimeHostExecutionState.OUTCOME_UNKNOWN
                } else if (current != null) {
                    RuntimeHostExecutionState.STOPPED
                } else {
                    it.execution
                },
                transport = if (executionMayRemain) {
                    it.transport
                } else {
                    RuntimeHostTransportState.DISCONNECTED
                },
                recovery = if (recoveryMayRemain) {
                    RuntimeHostRecoveryState.RECONCILIATION_REQUIRED
                } else {
                    it.recovery
                },
                activeOperation = null,
            )
        }
        mailbox.close()
        reply.complete(Unit)
    }

    private fun updateProjection(transform: (RuntimeHostProjection) -> RuntimeHostProjection) {
        val prior = mutableProjection.value
        mutableProjection.value = transform(prior).copy(sequence = prior.sequence + 1L)
    }

    private fun ActiveState.toProjection(): RuntimeHostActiveOperation = RuntimeHostActiveOperation(
        request = request,
        operation = operation,
        phase = phase,
    )

    companion object {
        const val DEFAULT_MAILBOX_CAPACITY: Int = 64
    }
}

private data class ActiveState(
    val request: RuntimeHostRequest,
    val operation: RuntimeHostOperation,
    var phase: RuntimeHostOperationPhase,
    val waiters: MutableList<Waiter>,
    var pendingOutcome: RuntimeHostCommandOutcome? = null,
    var releaseRequired: Boolean = false,
    var cleanupReason: RuntimeHostCleanupReason? = null,
    val cleanupFailures: MutableList<ExpectedFailure> = mutableListOf(),
    var cleanupOutcomeUnknown: Boolean = false,
    var pendingRecoveryEvent: RuntimeHostRecoveryEvent? = null,
)

private data class Waiter(
    val reply: CompletableDeferred<RuntimeHostCommandResult>,
    val replayed: Boolean,
)

private sealed interface MailboxItem {
    data class Command(
        val request: RuntimeHostRequest,
        val reply: CompletableDeferred<RuntimeHostCommandResult>,
    ) : MailboxItem

    data class PrerequisitesCompleted(
        val requestedOperation: RuntimeHostOperation,
        val result: RuntimeHostPrerequisiteResult,
    ) : MailboxItem

    data class ForegroundCompleted(
        val requestedOperation: RuntimeHostOperation,
        val result: RuntimeHostForegroundStartResult,
    ) : MailboxItem

    data class RehydrationCompleted(
        val requestedOperation: RuntimeHostOperation,
        val result: RuntimeHostRehydrationResult,
    ) : MailboxItem

    data class CleanupCompleted(
        val requestedOperation: RuntimeHostOperation,
        val result: RuntimeHostCleanupResult,
    ) : MailboxItem

    data class ReleaseCompleted(
        val requestedOperation: RuntimeHostOperation,
        val result: RuntimeHostForegroundStopResult,
    ) : MailboxItem

    data class RecoveryEvent(val event: RuntimeHostRecoveryEvent) : MailboxItem

    data class Shutdown(val reply: CompletableDeferred<Unit>) : MailboxItem
}

private fun prerequisiteFailure(
    request: RuntimeHostRequest.Start,
    observed: RuntimeHostPrerequisites,
): ExpectedFailure? = when {
    observed.association != RuntimeHostAssociationState.ASSOCIATED -> hostFailure(
        category = FailureCategory.UNAUTHORIZED,
        code = "RUNTIME_HOST_ASSOCIATION_MISSING",
        retryable = false,
        correlationId = request.correlationId,
    )

    observed.permission != RuntimeHostPermissionState.GRANTED -> hostFailure(
        category = FailureCategory.PERMISSION,
        code = "RUNTIME_HOST_PERMISSION_DENIED",
        retryable = false,
        correlationId = request.correlationId,
    )

    observed.presence == RuntimeHostPresenceState.ABSENT -> hostFailure(
        category = FailureCategory.UNAVAILABLE,
        code = "RUNTIME_HOST_DEVICE_ABSENT",
        retryable = true,
        correlationId = request.correlationId,
    )

    request.origin != RuntimeHostStartOrigin.EXPLICIT_USER &&
        observed.presence != RuntimeHostPresenceState.PRESENT -> hostFailure(
        category = FailureCategory.REJECTED_POLICY,
        code = "RUNTIME_HOST_AUTOSTART_REQUIRES_PRESENT_EVIDENCE",
        retryable = true,
        correlationId = request.correlationId,
    )

    else -> null
}

private fun staleEffectFailure(
    request: RuntimeHostRequest,
    code: String,
): ExpectedFailure = hostFailure(
    category = FailureCategory.REPLAYED,
    code = code,
    retryable = false,
    correlationId = request.correlationId,
)

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

private fun hostFailure(
    category: FailureCategory,
    code: String,
    retryable: Boolean,
    correlationId: dev.gumi.edge.sdk.CorrelationId,
): ExpectedFailure = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
    correlationId = correlationId,
)
