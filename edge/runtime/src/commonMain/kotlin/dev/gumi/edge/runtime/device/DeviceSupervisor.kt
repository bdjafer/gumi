package dev.gumi.edge.runtime.device

import dev.gumi.edge.runtime.capture.CaptureEffect
import dev.gumi.edge.runtime.capture.CaptureInput
import dev.gumi.edge.runtime.capture.CaptureProof
import dev.gumi.edge.runtime.capture.CaptureProofSource
import dev.gumi.edge.runtime.capture.CaptureReducer
import dev.gumi.edge.runtime.capture.CaptureReduction
import dev.gumi.edge.runtime.capture.CaptureReductionOutcome
import dev.gumi.edge.runtime.capture.CaptureState
import dev.gumi.edge.runtime.capture.CaptureTruth
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single serialized state owner for one stable [DeviceId].
 *
 * Slow hardware effects run outside the mailbox consumer. Their correlated completions re-enter
 * the same bounded mailbox before they are allowed to change capture state.
 */
class DeviceSupervisor internal constructor(
    val deviceId: DeviceId,
    parentScope: CoroutineScope,
    private val captureHardware: CaptureHardwarePort,
    mailboxCapacity: Int = DEFAULT_MAILBOX_CAPACITY,
    initialCaptureState: CaptureState = CaptureState(),
) {
    init {
        require(mailboxCapacity > 0) { "Device supervisor mailbox capacity must be positive" }
    }

    private val lifecycleMutex = Mutex()
    private val supervisorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisorJob)
    private val mailbox = Channel<MailboxItem>(capacity = mailboxCapacity)
    private var effectTail: Job? = null
    private var lastAllocatedCausalGeneration: ULong = 0u

    private val mutableProjection = MutableStateFlow(
        DeviceSupervisorProjection(
            deviceId = deviceId,
            capture = initialCaptureState,
        ),
    )

    init {
        // A mailbox consumer can stop before a non-cancellable hardware cleanup child. CLOSED must
        // mean the entire supervisor job is quiescent, not merely that its consumer exited.
        supervisorJob.invokeOnCompletion {
            mutableProjection.update { projection ->
                projection.copy(lifecycle = DeviceSupervisorLifecycle.CLOSED)
            }
        }
    }

    val projection: StateFlow<DeviceSupervisorProjection> = mutableProjection.asStateFlow()

    suspend fun start(): DeviceSupervisorStartResult = lifecycleMutex.withLock {
        when (mutableProjection.value.lifecycle) {
            DeviceSupervisorLifecycle.NEW -> {
                mutableProjection.update { it.copy(lifecycle = DeviceSupervisorLifecycle.RUNNING) }
                scope.launch { consumeMailbox() }.also { job ->
                    job.invokeOnCompletion {
                        mailbox.close()
                        mutableProjection.update { projection ->
                            if (projection.lifecycle == DeviceSupervisorLifecycle.RUNNING) {
                                projection.copy(lifecycle = DeviceSupervisorLifecycle.CLOSING)
                            } else {
                                projection
                            }
                        }
                        supervisorJob.cancel()
                    }
                }
                DeviceSupervisorStartResult.Started
            }

            DeviceSupervisorLifecycle.RUNNING -> DeviceSupervisorStartResult.AlreadyRunning
            DeviceSupervisorLifecycle.CLOSING,
            DeviceSupervisorLifecycle.CLOSED,
            -> DeviceSupervisorStartResult.Rejected(
                supervisorFailure(
                    category = FailureCategory.CANCELLED,
                    code = "DEVICE_SUPERVISOR_CLOSED",
                    retryable = false,
                ),
            )
        }
    }

    suspend fun submit(
        command: DeviceSupervisorCommand,
    ): DeviceSupervisorSubmissionResult = enqueue(
        item = MailboxItem.Command(command),
        correlationId = command.correlationId(),
    )

    suspend fun publish(
        event: DeviceSupervisorEvent,
    ): DeviceSupervisorSubmissionResult = enqueue(
        item = MailboxItem.Event(event),
        correlationId = event.correlationId(),
    )

    /**
     * Idempotently terminates the consumer and all in-flight effects owned by this device.
     *
     * Closing never asserts that the microphone is off and never rewrites capture truth. A host
     * that can still communicate with hardware should acquire Idle before relinquishing ownership.
     */
    suspend fun close() {
        lifecycleMutex.withLock {
            when (mutableProjection.value.lifecycle) {
                DeviceSupervisorLifecycle.NEW -> {
                    mutableProjection.update { it.copy(lifecycle = DeviceSupervisorLifecycle.CLOSED) }
                    mailbox.close()
                    supervisorJob.cancel()
                }

                DeviceSupervisorLifecycle.CLOSED -> supervisorJob.cancel()
                DeviceSupervisorLifecycle.CLOSING -> Unit
                DeviceSupervisorLifecycle.RUNNING -> {
                    mutableProjection.update { it.copy(lifecycle = DeviceSupervisorLifecycle.CLOSING) }
                    mailbox.close()
                    supervisorJob.cancel()
                }
            }
        }

        supervisorJob.cancelAndJoin()
        mutableProjection.update { it.copy(lifecycle = DeviceSupervisorLifecycle.CLOSED) }
    }

    private suspend fun enqueue(
        item: MailboxItem,
        correlationId: CorrelationId?,
    ): DeviceSupervisorSubmissionResult = lifecycleMutex.withLock {
        when (mutableProjection.value.lifecycle) {
            DeviceSupervisorLifecycle.NEW -> DeviceSupervisorSubmissionResult.Rejected(
                supervisorFailure(
                    category = FailureCategory.REJECTED_POLICY,
                    code = "DEVICE_SUPERVISOR_NOT_STARTED",
                    retryable = true,
                    correlationId = correlationId,
                ),
            )

            DeviceSupervisorLifecycle.CLOSING,
            DeviceSupervisorLifecycle.CLOSED,
            -> DeviceSupervisorSubmissionResult.Rejected(
                supervisorFailure(
                    category = FailureCategory.CANCELLED,
                    code = "DEVICE_SUPERVISOR_CLOSED",
                    retryable = false,
                    correlationId = correlationId,
                ),
            )

            DeviceSupervisorLifecycle.RUNNING -> {
                val result = mailbox.trySend(item)
                if (result.isSuccess) {
                    DeviceSupervisorSubmissionResult.Accepted
                } else {
                    DeviceSupervisorSubmissionResult.Rejected(
                        supervisorFailure(
                            category = if (result.isClosed) {
                                FailureCategory.CANCELLED
                            } else {
                                FailureCategory.RESOURCE_EXHAUSTED
                            },
                            code = if (result.isClosed) {
                                "DEVICE_SUPERVISOR_CLOSED"
                            } else {
                                "DEVICE_SUPERVISOR_MAILBOX_FULL"
                            },
                            retryable = !result.isClosed,
                            correlationId = correlationId,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun consumeMailbox() {
        for (item in mailbox) {
            when (item) {
                is MailboxItem.Command -> handleCommand(item.command)
                is MailboxItem.Event -> handleEvent(item.event)
                is MailboxItem.ModeCompleted -> handleModeCompletion(item)
                is MailboxItem.ReleaseCompleted -> handleReleaseCompletion(item)
                is MailboxItem.EffectThrew -> handleEffectFailure(item)
            }
        }
    }

    private fun handleCommand(command: DeviceSupervisorCommand) {
        when (command) {
            is DeviceSupervisorCommand.Capture -> {
                invalidateCaptureEvidenceIfLinkCannotSupportIt()
                reduce(CaptureInput.Command(command.command))
            }
        }
    }

    private fun handleEvent(event: DeviceSupervisorEvent) {
        when (event) {
            is DeviceSupervisorEvent.LinkChanged -> handleLinkObservation(event)

            is DeviceSupervisorEvent.CaptureStateObserved -> handleCaptureObservation(event)

            is DeviceSupervisorEvent.FatalCaptureFault -> reduce(
                CaptureInput.FatalFault(
                    failure = event.failure,
                    recoveryCorrelationId = event.recoveryCorrelationId,
                    releaseMustFollowCausalGeneration = lastAllocatedCausalGeneration,
                    connectionSessionGeneration = event.connectionSessionGeneration,
                ),
            )
        }
    }

    private fun handleLinkObservation(event: DeviceSupervisorEvent.LinkChanged) {
        val before = mutableProjection.value
        val currentGeneration = before.connectionSessionGeneration
        if (currentGeneration != null && event.connectionSessionGeneration < currentGeneration) {
            val failure = supervisorFailure(
                category = FailureCategory.REPLAYED,
                code = "STALE_CONNECTION_SESSION_GENERATION",
                retryable = false,
            )
            mutableProjection.update {
                it.copy(sequence = it.sequence + 1, lastFailure = failure)
            }
            return
        }

        mutableProjection.update { projection ->
            projection.copy(
                link = event.state,
                connectionSessionGeneration = event.connectionSessionGeneration,
                sequence = projection.sequence + 1,
                lastOutcome = DeviceSupervisorOutcome.LinkObserved(event.state),
                lastFailure = null,
            )
        }

        val acquired = before.capture.truth as? CaptureTruth.Acquired
        val evidenceCrossedSessions = acquired != null &&
            acquired.proof.connectionSessionGeneration != event.connectionSessionGeneration
        if (!evidenceCrossedSessions) return

        val transition = before.capture.transition
        if (transition != null) {
            val failure = supervisorFailure(
                category = FailureCategory.DISCONNECTED,
                code = "CAPTURE_SESSION_CHANGED_DURING_TRANSITION",
                retryable = true,
                correlationId = transition.command.correlationId,
            )
            reduce(
                CaptureInput.FatalFault(
                    failure = failure,
                    recoveryCorrelationId = transition.command.correlationId,
                    releaseMustFollowCausalGeneration = lastAllocatedCausalGeneration,
                    connectionSessionGeneration = event.connectionSessionGeneration,
                ),
            )
        } else {
            mutableProjection.update {
                it.copy(
                    capture = it.capture.copy(
                        truth = CaptureTruth.Unverified(
                            lastReportedMode = acquired.mode,
                            lastProof = acquired.proof,
                        ),
                        resumeAfterVoiceTurn = null,
                    ),
                    sequence = it.sequence + 1,
                )
            }
        }
    }

    private fun handleCaptureObservation(event: DeviceSupervisorEvent.CaptureStateObserved) {
        val projection = mutableProjection.value
        if (
            projection.link != DeviceLinkState.CONNECTED ||
            projection.connectionSessionGeneration != event.connectionSessionGeneration
        ) {
            val failure = supervisorFailure(
                category = FailureCategory.REPLAYED,
                code = "CAPTURE_OBSERVATION_SESSION_MISMATCH",
                retryable = true,
            )
            mutableProjection.update {
                it.copy(
                    sequence = it.sequence + 1,
                    lastOutcome = DeviceSupervisorOutcome.CaptureObservationRejected(failure),
                    lastFailure = failure,
                )
            }
            return
        }
        reduce(
            CaptureInput.HardwareStateObserved(
                mode = event.mode,
                proof = CaptureProof(
                    connectionSessionGeneration = event.connectionSessionGeneration,
                    causalGeneration = allocateCausalGeneration(),
                    source = CaptureProofSource.DEVICE_OBSERVATION,
                ),
            ),
        )
    }

    private fun invalidateCaptureEvidenceIfLinkCannotSupportIt() {
        val projection = mutableProjection.value
        val acquired = projection.capture.truth as? CaptureTruth.Acquired ?: return
        if (
            projection.link == DeviceLinkState.CONNECTED &&
            projection.connectionSessionGeneration == acquired.proof.connectionSessionGeneration
        ) {
            return
        }
        mutableProjection.update {
            it.copy(
                capture = it.capture.copy(
                    truth = CaptureTruth.Unverified(
                        lastReportedMode = acquired.mode,
                        lastProof = acquired.proof,
                    ),
                    transition = null,
                    resumeAfterVoiceTurn = null,
                ),
                sequence = it.sequence + 1,
            )
        }
    }

    private fun handleModeCompletion(item: MailboxItem.ModeCompleted) {
        when (val completion = item.completion) {
            is CaptureModeCompletion.Acquired -> reduce(
                CaptureInput.HardwareAcquired(
                    completion.correlationId,
                    completion.mode,
                    CaptureProof(
                        connectionSessionGeneration = item.connectionSessionGeneration,
                        causalGeneration = item.causalGeneration,
                        source = CaptureProofSource.MODE_ACQUISITION,
                    ),
                ),
            )

            is CaptureModeCompletion.Refused -> reduce(
                CaptureInput.HardwareRefused(completion.correlationId, completion.failure),
            )

            is CaptureModeCompletion.Fatal -> reduce(
                CaptureInput.FatalFault(
                    completion.failure,
                    completion.correlationId,
                    releaseMustFollowCausalGeneration = item.causalGeneration,
                    connectionSessionGeneration = item.connectionSessionGeneration,
                ),
            )
        }
    }

    private fun handleReleaseCompletion(item: MailboxItem.ReleaseCompleted) {
        when (val completion = item.completion) {
            is EmergencyCaptureReleaseCompletion.Released -> reduce(
                CaptureInput.HardwareReleaseConfirmed(
                    completion.recoveryCorrelationId,
                    CaptureProof(
                        connectionSessionGeneration = item.connectionSessionGeneration,
                        causalGeneration = item.causalGeneration,
                        source = CaptureProofSource.EMERGENCY_RELEASE,
                    ),
                ),
            )

            is EmergencyCaptureReleaseCompletion.Failed -> updateEffectFailure(
                effect = item.effect,
                failure = completion.failure,
                failSafeOutcome = null,
            )
        }
    }

    private fun handleEffectFailure(item: MailboxItem.EffectThrew) {
        val effect = item.effect
        val failure = item.failure
        when (effect) {
            is CaptureEffect.RequestHardwareMode -> {
                val reduction = CaptureReducer.reduce(
                    mutableProjection.value.capture,
                    CaptureInput.FatalFault(
                        failure,
                        effect.correlationId,
                        releaseMustFollowCausalGeneration = item.causalGeneration,
                        connectionSessionGeneration = item.connectionSessionGeneration,
                    ),
                )
                updateEffectFailure(effect, failure, reduction.outcome, reduction)
            }

            is CaptureEffect.RequestEmergencyRelease -> updateEffectFailure(
                effect = effect,
                failure = failure,
                failSafeOutcome = null,
            )
        }
    }

    private fun updateEffectFailure(
        effect: CaptureEffect,
        failure: ExpectedFailure,
        failSafeOutcome: CaptureReductionOutcome?,
        reduction: CaptureReduction? = null,
    ) {
        mutableProjection.update {
            it.copy(
                capture = reduction?.state ?: it.capture,
                sequence = it.sequence + 1,
                lastOutcome = DeviceSupervisorOutcome.CaptureEffectFailed(
                    effect = effect,
                    failure = failure,
                    failSafeOutcome = failSafeOutcome,
                ),
                lastFailure = failure,
            )
        }
        reduction?.effects?.forEach(::launchEffect)
    }

    private fun reduce(input: CaptureInput) {
        val reduction = CaptureReducer.reduce(mutableProjection.value.capture, input)
        mutableProjection.update {
            it.copy(
                capture = reduction.state,
                sequence = it.sequence + 1,
                lastOutcome = DeviceSupervisorOutcome.CaptureReduced(reduction.outcome),
                lastFailure = null,
            )
        }
        reduction.effects.forEach(::launchEffect)
    }

    private fun launchEffect(effect: CaptureEffect) {
        val connectionSessionGeneration = when (
            val truth = mutableProjection.value.capture.truth
        ) {
            is CaptureTruth.Acquired ->
                truth.proof.connectionSessionGeneration
            is CaptureTruth.Unknown ->
                truth.connectionSessionGeneration
            is CaptureTruth.Unverified ->
                truth.lastProof?.connectionSessionGeneration
        } ?: mutableProjection.value.connectionSessionGeneration ?: return
        val causalGeneration = allocateCausalGeneration()
        val predecessor = effectTail
        if (effect is CaptureEffect.RequestEmergencyRelease) {
            predecessor?.cancel(
                CancellationException("Emergency release superseded an older capture effect"),
            )
        }

        val job = scope.launch {
            predecessor?.join()
            val item = try {
                when (effect) {
                    is CaptureEffect.RequestHardwareMode -> MailboxItem.ModeCompleted(
                        effect = effect,
                        causalGeneration = causalGeneration,
                        connectionSessionGeneration = connectionSessionGeneration,
                        completion = captureHardware.requestMode(
                            CaptureModeRequest(effect.correlationId, effect.targetMode),
                        ),
                    )

                    is CaptureEffect.RequestEmergencyRelease -> MailboxItem.ReleaseCompleted(
                        effect = effect,
                        causalGeneration = causalGeneration,
                        connectionSessionGeneration = connectionSessionGeneration,
                        completion = captureHardware.emergencyRelease(
                            EmergencyCaptureReleaseRequest(effect.recoveryCorrelationId),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                MailboxItem.EffectThrew(
                    effect = effect,
                    causalGeneration = causalGeneration,
                    connectionSessionGeneration = connectionSessionGeneration,
                    failure = supervisorFailure(
                        category = FailureCategory.INTERNAL,
                        code = "CAPTURE_HARDWARE_EFFECT_FAILED",
                        retryable = true,
                        correlationId = effect.correlationId(),
                    ),
                )
            }
            mailbox.send(item)
        }
        effectTail = job
    }

    private fun allocateCausalGeneration(): ULong {
        check(lastAllocatedCausalGeneration != ULong.MAX_VALUE) {
            "Capture causal generation exhausted"
        }
        lastAllocatedCausalGeneration += 1uL
        return lastAllocatedCausalGeneration
    }

    private sealed interface MailboxItem {
        data class Command(val command: DeviceSupervisorCommand) : MailboxItem

        data class Event(val event: DeviceSupervisorEvent) : MailboxItem

        data class ModeCompleted(
            val effect: CaptureEffect.RequestHardwareMode,
            val causalGeneration: ULong,
            val connectionSessionGeneration: ULong,
            val completion: CaptureModeCompletion,
        ) : MailboxItem

        data class ReleaseCompleted(
            val effect: CaptureEffect.RequestEmergencyRelease,
            val causalGeneration: ULong,
            val connectionSessionGeneration: ULong,
            val completion: EmergencyCaptureReleaseCompletion,
        ) : MailboxItem

        data class EffectThrew(
            val effect: CaptureEffect,
            val causalGeneration: ULong,
            val connectionSessionGeneration: ULong,
            val failure: ExpectedFailure,
        ) : MailboxItem
    }

    companion object {
        const val DEFAULT_MAILBOX_CAPACITY: Int = 64
    }
}

private fun DeviceSupervisorCommand.correlationId(): CorrelationId = when (this) {
    is DeviceSupervisorCommand.Capture -> command.correlationId
}

private fun DeviceSupervisorEvent.correlationId(): CorrelationId? = when (this) {
    is DeviceSupervisorEvent.FatalCaptureFault -> recoveryCorrelationId
    is DeviceSupervisorEvent.CaptureStateObserved -> null
    is DeviceSupervisorEvent.LinkChanged -> null
}

private fun CaptureEffect.correlationId(): CorrelationId = when (this) {
    is CaptureEffect.RequestEmergencyRelease -> recoveryCorrelationId
    is CaptureEffect.RequestHardwareMode -> correlationId
}

private fun supervisorFailure(
    category: FailureCategory,
    code: String,
    retryable: Boolean,
    correlationId: CorrelationId? = null,
) = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
    correlationId = correlationId,
)
