package dev.gumi.edge.runtime.realtime

import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.OpaqueBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class VoiceTurnFrameInput(
    val turnId: VoiceTurnId,
    val ordinal: ULong,
    val formatId: VoiceTurnFrameFormatId,
    val payload: OpaqueBytes,
    val receivedAt: VoiceTurnClockMark,
)

enum class VoiceTurnNoWorkReason {
    WAITING_FOR_LOCAL_FRAME,
    READY_TO_FINISH,
    AWAITING_REMOTE_RESULT,
    TERMINAL,
}

sealed interface VoiceTurnOperationResult {
    data class Applied(val projection: VoiceTurnProjection) : VoiceTurnOperationResult

    data class Duplicate(val projection: VoiceTurnProjection) : VoiceTurnOperationResult

    data class NoWork(
        val projection: VoiceTurnProjection,
        val reason: VoiceTurnNoWorkReason,
    ) : VoiceTurnOperationResult

    data class Stale(val projection: VoiceTurnProjection) : VoiceTurnOperationResult

    data class Rejected(
        val projection: VoiceTurnProjection?,
        val failure: ExpectedFailure,
    ) : VoiceTurnOperationResult

    data class Unavailable(val failure: ExpectedFailure) : VoiceTurnOperationResult

    data class OutcomeUnknown(
        val projection: VoiceTurnProjection,
        val unknown: VoiceTurnRemoteUnknown,
    ) : VoiceTurnOperationResult
}

sealed interface VoiceTurnReadResult {
    data class Found(val projection: VoiceTurnProjection) : VoiceTurnReadResult

    data object Missing : VoiceTurnReadResult

    data class Unavailable(val failure: ExpectedFailure) : VoiceTurnReadResult
}

/**
 * Portable owner for one or more admission-bound VoiceTurns.
 *
 * Local mutation is serialized only around CAS transactions. Frame-store and realtime calls run
 * outside that mutex, so a cancellation can durably fence a turn while connect/send is suspended.
 * Every external call has a conservative durable attempt marker written before it. A lost caller,
 * process, or response therefore projects uncertainty and requires a new connection generation;
 * it never causes an in-place blind replay.
 */
class VoiceTurnCoordinator(
    private val stateStore: VoiceTurnStateStore,
    private val frameStore: VoiceTurnFrameStore,
    private val realtime: VoiceTurnRealtimePort,
    private val clock: VoiceTurnRuntimeClock,
) {
    private val mutationMutex = Mutex()

    suspend fun admit(admission: VoiceTurnAdmission): VoiceTurnOperationResult =
        mutationMutex.withLock {
            repeat(MAX_COMMIT_RECONCILIATIONS) {
                when (val loaded = stateStore.load(admission.identity.turnId)) {
                    is VoiceTurnStoreLoadResult.Loaded -> {
                        return@withLock if (loaded.state.admission == admission) {
                            VoiceTurnOperationResult.Duplicate(loaded.state.project())
                        } else {
                            rejected(
                                loaded.state,
                                FailureCategory.REPLAYED,
                                "VOICE_TURN_IDENTITY_CONFLICT",
                                retryable = false,
                            )
                        }
                    }

                    VoiceTurnStoreLoadResult.Missing -> {
                        val initial = VoiceTurnState(revision = 0uL, admission = admission)
                        when (val committed = stateStore.commit(null, initial)) {
                            VoiceTurnStoreCommitResult.Committed -> {
                                return@withLock VoiceTurnOperationResult.Applied(initial.project())
                            }

                            is VoiceTurnStoreCommitResult.IdentityConflict -> {
                                return@withLock VoiceTurnOperationResult.Rejected(
                                    projection = null,
                                    failure = failure(
                                        correlationId = admission.identity.correlationId,
                                        category = FailureCategory.REPLAYED,
                                        code = committed.code,
                                        retryable = false,
                                    ),
                                )
                            }

                            is VoiceTurnStoreCommitResult.Unavailable -> {
                                return@withLock unavailable(
                                    admission.identity.correlationId,
                                    committed.failure,
                                )
                            }

                            is VoiceTurnStoreCommitResult.RevisionMismatch,
                            VoiceTurnStoreCommitResult.OutcomeUnknown,
                            -> Unit
                        }
                    }

                    is VoiceTurnStoreLoadResult.Unavailable -> {
                        return@withLock unavailable(
                            admission.identity.correlationId,
                            loaded.failure,
                        )
                    }
                }
            }
            VoiceTurnOperationResult.Unavailable(
                failure(
                    correlationId = admission.identity.correlationId,
                    category = FailureCategory.UNAVAILABLE,
                    code = "VOICE_TURN_STORE_RECONCILIATION_EXHAUSTED",
                    retryable = true,
                ),
            )
        }

    suspend fun read(turnId: VoiceTurnId): VoiceTurnReadResult = when (
        val loaded = stateStore.load(turnId)
    ) {
        is VoiceTurnStoreLoadResult.Loaded -> VoiceTurnReadResult.Found(loaded.state.project())
        VoiceTurnStoreLoadResult.Missing -> VoiceTurnReadResult.Missing
        is VoiceTurnStoreLoadResult.Unavailable -> VoiceTurnReadResult.Unavailable(
            failure(
                correlationId = null,
                category = FailureCategory.UNAVAILABLE,
                code = loaded.failure.code,
                retryable = loaded.failure.retryable,
            ),
        )
    }

    /** Crosses encrypted frame durability before the ordered metadata record can advance. */
    suspend fun appendFrame(input: VoiceTurnFrameInput): VoiceTurnOperationResult {
        val preflight = loadState(input.turnId)
        val state = when (preflight) {
            is StateLoad.Loaded -> preflight.state
            StateLoad.Missing -> return missing(input.turnId)
            is StateLoad.Unavailable -> return preflight.result
        }
        preflightFrame(
            state = state,
            ordinal = input.ordinal,
            formatId = input.formatId,
            payloadBytes = input.payload.size.toUInt(),
            receivedAt = input.receivedAt,
        )?.let { return it }
        val descriptor = VoiceTurnFrameDescriptor(
            sessionId = state.admission.identity.sessionId,
            turnId = input.turnId,
            ordinal = input.ordinal,
            formatId = input.formatId,
            payloadBytes = input.payload.size.toUInt(),
            receivedAt = input.receivedAt,
        )
        val durable = try {
            when (val written = frameStore.writeAndFlush(descriptor, input.payload)) {
                is VoiceTurnFrameWriteResult.Stored -> written.frame
                is VoiceTurnFrameWriteResult.Unavailable -> {
                    return unavailable(state.admission.identity.correlationId, written.failure)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return VoiceTurnOperationResult.Unavailable(
                failure(
                    correlationId = state.admission.identity.correlationId,
                    category = FailureCategory.INTERNAL,
                    code = "VOICE_TURN_FRAME_STORE_THREW",
                    retryable = true,
                ),
            )
        }
        if (durable.descriptor != descriptor) {
            return VoiceTurnOperationResult.Unavailable(
                failure(
                    correlationId = state.admission.identity.correlationId,
                    category = FailureCategory.CORRUPT,
                    code = "VOICE_TURN_FRAME_STORE_IDENTITY_MISMATCH",
                    retryable = false,
                ),
            )
        }
        return mutate(input.turnId) { current -> planFrame(current, durable) }
    }

    suspend fun endInput(
        turnId: VoiceTurnId,
        endedAt: VoiceTurnClockMark,
    ): VoiceTurnOperationResult = mutate(turnId) { state ->
        when {
            state.terminal != null -> MutationPlan.Done(
                VoiceTurnOperationResult.NoWork(
                    state.project(),
                    VoiceTurnNoWorkReason.TERMINAL,
                ),
            )

            state.localInputStatus == VoiceTurnLocalInputStatus.ENDED -> MutationPlan.Done(
                VoiceTurnOperationResult.Duplicate(state.project()),
            )

            state.localInputStatus == VoiceTurnLocalInputStatus.CANCELLED -> MutationPlan.Done(
                rejected(
                    state,
                    FailureCategory.CANCELLED,
                    "VOICE_TURN_LOCAL_INPUT_CANCELLED",
                    retryable = false,
                ),
            )

            else -> MutationPlan.Write(
                state.next(
                    localInputStatus = VoiceTurnLocalInputStatus.ENDED,
                    timeline = state.timeline.copy(inputEndedAt = endedAt),
                ),
            )
        }
    }

    /**
     * Starts exactly one stable remote generation. A different attempt ID is required after an
     * ambiguous generation; exact replay returns the durable fence without calling the port again.
     */
    suspend fun connect(
        turnId: VoiceTurnId,
        attemptId: VoiceTurnRemoteAttemptId,
        startedAt: VoiceTurnClockMark,
    ): VoiceTurnOperationResult {
        val prepared = mutate(turnId) { state -> planConnect(state, attemptId, startedAt) }
        if (prepared !is VoiceTurnOperationResult.Applied) return prepared
        val state = exactConnectState(turnId, attemptId) ?: return currentStaleOrUnavailable(turnId)
        val phase = state.remotePhase as VoiceTurnRemotePhase.ConnectAttemptFenced
        val request = VoiceTurnRemoteConnectRequest(
            identity = state.admission.identity,
            attempt = phase.attempt,
            resumeAfterOrdinal = phase.resumeAfterOrdinal,
            localInputEnded = state.localInputStatus == VoiceTurnLocalInputStatus.ENDED,
        )
        val result = try {
            realtime.connect(request)
        } catch (cancelled: CancellationException) {
            throw cancelled // The prewritten connect fence deliberately survives caller loss.
        } catch (_: Throwable) {
            VoiceTurnRemoteConnectResult.OutcomeUnknown(
                boundary = VoiceTurnOutcomeBoundary.REMOTE_TRANSPORT,
                failure = failure(
                    correlationId = state.admission.identity.correlationId,
                    category = FailureCategory.INTERNAL,
                    code = "VOICE_TURN_REMOTE_CONNECT_THREW",
                    retryable = true,
                ),
            )
        }
        val observedAt = safeClockAfterExternalEffect(state, VoiceTurnRemoteOperation.CONNECT)
            ?: return markClockUnknown(turnId, phase.attempt.generation, VoiceTurnRemoteOperation.CONNECT)
        return applyConnectResult(turnId, phase.attempt, result, observedAt)
    }

    /** Delivers at most one already-durable frame. */
    suspend fun sendNext(turnId: VoiceTurnId): VoiceTurnOperationResult {
        val selected = selectNextFrame(turnId)
        val candidate = when (selected) {
            is FrameSelection.Ready -> selected
            is FrameSelection.Result -> return selected.result
        }
        val payload = try {
            when (val read = frameStore.readAndVerify(candidate.frame)) {
                is VoiceTurnFrameReadResult.Verified -> read.payload
                is VoiceTurnFrameReadResult.Unavailable -> {
                    return unavailable(
                        candidate.state.admission.identity.correlationId,
                        read.failure,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return VoiceTurnOperationResult.Unavailable(
                failure(
                    correlationId = candidate.state.admission.identity.correlationId,
                    category = FailureCategory.INTERNAL,
                    code = "VOICE_TURN_FRAME_READ_THREW",
                    retryable = true,
                ),
            )
        }
        if (payload.size.toUInt() != candidate.frame.descriptor.payloadBytes) {
            return VoiceTurnOperationResult.Unavailable(
                failure(
                    correlationId = candidate.state.admission.identity.correlationId,
                    category = FailureCategory.CORRUPT,
                    code = "VOICE_TURN_FRAME_READ_LENGTH_MISMATCH",
                    retryable = false,
                ),
            )
        }
        val fenced = mutate(turnId) { state -> planFrameAttempt(state, candidate.frame) }
        if (fenced !is VoiceTurnOperationResult.Applied) return fenced
        val state = exactFrameAttemptState(
            turnId,
            candidate.attempt.generation,
            candidate.frame.descriptor.ordinal,
        ) ?: return currentStaleOrUnavailable(turnId)
        val phase = state.remotePhase as VoiceTurnRemotePhase.FrameAttemptFenced
        val request = VoiceTurnRemoteFrameRequest(
            identity = state.admission.identity,
            generation = phase.attempt.generation,
            remoteSessionRef = phase.remoteSessionRef,
            descriptor = candidate.frame.descriptor,
            contentDigest = candidate.frame.contentDigest,
        )
        val result = try {
            realtime.sendFrame(request, payload)
        } catch (cancelled: CancellationException) {
            throw cancelled // The exact frame attempt remains fenced for resume reconciliation.
        } catch (_: Throwable) {
            VoiceTurnRemoteFrameResult.OutcomeUnknown(
                boundary = VoiceTurnOutcomeBoundary.REMOTE_TRANSPORT,
                failure = failure(
                    correlationId = state.admission.identity.correlationId,
                    category = FailureCategory.INTERNAL,
                    code = "VOICE_TURN_REMOTE_FRAME_THREW",
                    retryable = true,
                ),
            )
        }
        val observedAt = safeClockAfterExternalEffect(state, VoiceTurnRemoteOperation.SEND_FRAME)
            ?: return markClockUnknown(
                turnId,
                phase.attempt.generation,
                VoiceTurnRemoteOperation.SEND_FRAME,
            )
        return applyFrameResult(turnId, phase, candidate.frame, result, observedAt)
    }

    /** Sends end-of-input only after every locally durable frame has an exact remote acknowledgement. */
    suspend fun finishRemote(turnId: VoiceTurnId): VoiceTurnOperationResult {
        val prepared = mutate(turnId) { state -> planFinish(state) }
        if (prepared !is VoiceTurnOperationResult.Applied) return prepared
        val state = exactFinishState(turnId) ?: return currentStaleOrUnavailable(turnId)
        val phase = state.remotePhase as VoiceTurnRemotePhase.FinishAttemptFenced
        val request = VoiceTurnRemoteFinishRequest(
            identity = state.admission.identity,
            generation = phase.attempt.generation,
            remoteSessionRef = phase.remoteSessionRef,
            terminalFrameOrdinal = state.frames.lastOrNull()?.descriptor?.ordinal,
        )
        val result = try {
            realtime.finish(request)
        } catch (cancelled: CancellationException) {
            throw cancelled // The finish fence survives; reconnect must reconcile it.
        } catch (_: Throwable) {
            VoiceTurnRemoteFinishResult.OutcomeUnknown(
                boundary = VoiceTurnOutcomeBoundary.REMOTE_TRANSPORT,
                failure = failure(
                    correlationId = state.admission.identity.correlationId,
                    category = FailureCategory.INTERNAL,
                    code = "VOICE_TURN_REMOTE_FINISH_THREW",
                    retryable = true,
                ),
            )
        }
        val observedAt = safeClockAfterExternalEffect(state, VoiceTurnRemoteOperation.FINISH_INPUT)
            ?: return markClockUnknown(
                turnId,
                phase.attempt.generation,
                VoiceTurnRemoteOperation.FINISH_INPUT,
            )
        return applyFinishResult(turnId, phase, result, observedAt)
    }

    /**
     * Local cancellation closes frame admission before invoking the remote port. It therefore does
     * not wait behind a suspended connect/send/finish call, and their late completions are stale.
     */
    suspend fun cancel(
        turnId: VoiceTurnId,
        cancellation: VoiceTurnCancellation,
    ): VoiceTurnOperationResult {
        val prepared = mutate(turnId) { state -> planCancellation(state, cancellation) }
        if (prepared !is VoiceTurnOperationResult.Applied) return prepared
        val loaded = loadState(turnId)
        val state = (loaded as? StateLoad.Loaded)?.state
            ?: return if (loaded is StateLoad.Unavailable) loaded.result else missing(turnId)
        if (state.terminal != null) return VoiceTurnOperationResult.Applied(state.project())
        val phase = state.remotePhase as? VoiceTurnRemotePhase.CancelAttemptFenced
            ?: return VoiceTurnOperationResult.Stale(state.project())
        val request = VoiceTurnRemoteCancelRequest(
            identity = state.admission.identity,
            generation = phase.generation,
            remoteSessionRef = phase.remoteSessionRef,
            cancellation = phase.cancellation,
        )
        val result = try {
            realtime.cancel(request)
        } catch (cancelled: CancellationException) {
            throw cancelled // The durable cancellation fence remains externally unresolved.
        } catch (_: Throwable) {
            VoiceTurnRemoteCancelResult.OutcomeUnknown(
                boundary = VoiceTurnOutcomeBoundary.REMOTE_TRANSPORT,
                failure = failure(
                    correlationId = cancellation.correlationId,
                    category = FailureCategory.INTERNAL,
                    code = "VOICE_TURN_REMOTE_CANCEL_THREW",
                    retryable = true,
                ),
            )
        }
        val observedAt = safeClockAfterExternalEffect(state, VoiceTurnRemoteOperation.CANCEL)
            ?: cancellation.requestedAt
        return applyCancelResult(turnId, phase, result, observedAt)
    }

    /** Applies one normalized async provider/remote event, rejecting stale or future generations. */
    suspend fun applyRemoteEvent(
        turnId: VoiceTurnId,
        event: VoiceTurnRemoteEvent,
    ): VoiceTurnOperationResult = mutate(turnId) { state -> planRemoteEvent(state, event) }
        .asOutcomeUnknownIfNeeded()

    private fun preflightFrame(
        state: VoiceTurnState,
        ordinal: ULong,
        formatId: VoiceTurnFrameFormatId,
        payloadBytes: UInt,
        receivedAt: VoiceTurnClockMark,
    ): VoiceTurnOperationResult? {
        val policy = state.admission.bufferPolicy
        if (state.terminal != null || state.localInputStatus != VoiceTurnLocalInputStatus.OPEN) {
            return rejected(
                state,
                FailureCategory.REJECTED_POLICY,
                "VOICE_TURN_LOCAL_INPUT_CLOSED",
                retryable = false,
            )
        }
        if (payloadBytes == 0u || payloadBytes > policy.maximumFrameBytes) {
            return rejected(
                state,
                FailureCategory.RESOURCE_EXHAUSTED,
                "VOICE_TURN_FRAME_SIZE_OUTSIDE_POLICY",
                retryable = false,
            )
        }
        if (ordinal !in policy.firstFrameOrdinal..policy.lastFrameOrdinal) {
            return rejected(
                state,
                FailureCategory.RESOURCE_EXHAUSTED,
                "VOICE_TURN_FRAME_ORDINAL_OUTSIDE_POLICY",
                retryable = false,
            )
        }
        state.frames.getOrNull((ordinal - policy.firstFrameOrdinal).toInt())?.let { existing ->
            if (
                existing.descriptor.formatId != formatId ||
                existing.descriptor.payloadBytes != payloadBytes ||
                existing.descriptor.receivedAt != receivedAt
            ) {
                return rejected(
                    state,
                    FailureCategory.REPLAYED,
                    "VOICE_TURN_FRAME_IDENTITY_CONFLICT",
                    retryable = false,
                )
            }
            return null // The frame store still verifies the retry's exact bytes and digest.
        }
        val nextOrdinal = policy.firstFrameOrdinal + state.frames.size.toULong()
        if (ordinal != nextOrdinal) {
            return rejected(
                state,
                FailureCategory.REJECTED_POLICY,
                "VOICE_TURN_FRAME_OUT_OF_ORDER",
                retryable = true,
                evidence = mapOf("expectedOrdinal" to nextOrdinal.toString()),
            )
        }
        if (state.retainedBytes > policy.maximumRetainedBytes - payloadBytes.toULong()) {
            return rejected(
                state,
                FailureCategory.RESOURCE_EXHAUSTED,
                "VOICE_TURN_BUFFER_EXHAUSTED",
                retryable = false,
            )
        }
        return null
    }

    private fun planFrame(
        state: VoiceTurnState,
        durable: DurableVoiceTurnFrame,
    ): MutationPlan {
        preflightFrame(
            state = state,
            ordinal = durable.descriptor.ordinal,
            formatId = durable.descriptor.formatId,
            payloadBytes = durable.descriptor.payloadBytes,
            receivedAt = durable.descriptor.receivedAt,
        )?.let { return MutationPlan.Done(it) }
        val policy = state.admission.bufferPolicy
        val existing = state.frames.getOrNull(
            (durable.descriptor.ordinal - policy.firstFrameOrdinal).toInt(),
        )
        if (existing != null) {
            return MutationPlan.Done(
                if (existing == durable) {
                    VoiceTurnOperationResult.Duplicate(state.project())
                } else {
                    rejected(
                        state,
                        FailureCategory.REPLAYED,
                        "VOICE_TURN_FRAME_CONTENT_CONFLICT",
                        retryable = false,
                    )
                },
            )
        }
        return MutationPlan.Write(
            state.next(
                frames = state.frames + durable,
                retainedBytes = state.retainedBytes + durable.descriptor.payloadBytes.toULong(),
                timeline = state.timeline.copy(
                    firstFrameAt = state.timeline.firstFrameAt ?: durable.descriptor.receivedAt,
                    lastFrameAt = durable.descriptor.receivedAt,
                ),
            ),
        )
    }

    private fun planConnect(
        state: VoiceTurnState,
        attemptId: VoiceTurnRemoteAttemptId,
        startedAt: VoiceTurnClockMark,
    ): MutationPlan {
        if (state.terminal != null) {
            return MutationPlan.Done(
                VoiceTurnOperationResult.NoWork(
                    state.project(),
                    VoiceTurnNoWorkReason.TERMINAL,
                ),
            )
        }
        if (state.localInputStatus == VoiceTurnLocalInputStatus.CANCELLED) {
            return MutationPlan.Done(
                rejected(
                    state,
                    FailureCategory.CANCELLED,
                    "VOICE_TURN_CANCELLED",
                    retryable = false,
                ),
            )
        }
        state.connectionAttempts.firstOrNull { it.id == attemptId }?.let { prior ->
            return MutationPlan.Done(
                if (prior.generation == state.remoteGeneration) {
                    VoiceTurnOperationResult.Duplicate(state.project())
                } else {
                    rejected(
                        state,
                        FailureCategory.REPLAYED,
                        "VOICE_TURN_REMOTE_ATTEMPT_ID_REPLAYED",
                        retryable = false,
                    )
                },
            )
        }
        if (state.remotePhase is VoiceTurnRemotePhase.Ready) {
            return MutationPlan.Done(
                rejected(
                    state,
                    FailureCategory.REJECTED_POLICY,
                    "VOICE_TURN_REMOTE_ALREADY_READY",
                    retryable = false,
                ),
            )
        }
        if (state.remotePhase is VoiceTurnRemotePhase.CancelAttemptFenced) {
            return MutationPlan.Done(
                rejected(
                    state,
                    FailureCategory.CANCELLED,
                    "VOICE_TURN_CANCELLATION_FENCED",
                    retryable = false,
                ),
            )
        }
        val policy = state.admission.bufferPolicy
        if (
            state.remoteGeneration == ULong.MAX_VALUE ||
            state.connectionAttempts.size >= policy.maximumRemoteGenerations.toInt()
        ) {
            return MutationPlan.Done(
                rejected(
                    state,
                    FailureCategory.RESOURCE_EXHAUSTED,
                    "VOICE_TURN_REMOTE_GENERATIONS_EXHAUSTED",
                    retryable = false,
                ),
            )
        }
        val attempt = VoiceTurnConnectionAttempt(
            id = attemptId,
            generation = state.remoteGeneration + 1uL,
            startedAt = startedAt,
        )
        return MutationPlan.Write(
            state.next(
                remoteGeneration = attempt.generation,
                connectionAttempts = state.connectionAttempts + attempt,
                remotePhase = VoiceTurnRemotePhase.ConnectAttemptFenced(
                    attempt = attempt,
                    resumeAfterOrdinal = state.remoteAcknowledgedThrough,
                ),
            ),
        )
    }

    private suspend fun applyConnectResult(
        turnId: VoiceTurnId,
        attempt: VoiceTurnConnectionAttempt,
        result: VoiceTurnRemoteConnectResult,
        observedAt: VoiceTurnClockMark,
    ): VoiceTurnOperationResult = mutate(turnId) { state ->
        if (state.remoteGeneration > attempt.generation || state.terminal != null) {
            return@mutate MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        }
        val phase = state.remotePhase as? VoiceTurnRemotePhase.ConnectAttemptFenced
        if (phase?.attempt != attempt) {
            return@mutate MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        }
        when (result) {
            is VoiceTurnRemoteConnectResult.Ready -> {
                val mismatch = resumeAckMismatch(state, attempt, result.resumeAck)
                if (mismatch != null) {
                    return@mutate MutationPlan.Write(
                        state.remoteUnknown(
                            operation = VoiceTurnRemoteOperation.CONNECT,
                            boundary = VoiceTurnOutcomeBoundary.REMOTE_TRANSPORT,
                            failure = mismatch,
                        ),
                    )
                }
                MutationPlan.Write(
                    state.next(
                        remoteAcknowledgedThrough = result.resumeAck.acceptedThroughOrdinal,
                        remotePhase = VoiceTurnRemotePhase.Ready(
                            attempt,
                            result.remoteSessionRef,
                        ),
                        timeline = state.timeline.copy(remoteReadyAt = observedAt),
                    ),
                )
            }

            is VoiceTurnRemoteConnectResult.NotAttempted -> MutationPlan.Write(
                state.next(remotePhase = VoiceTurnRemotePhase.Disconnected),
            )

            is VoiceTurnRemoteConnectResult.Rejected -> planRemoteFailure(
                state = state,
                generation = attempt.generation,
                operation = VoiceTurnRemoteOperation.CONNECT,
                boundary = result.boundary,
                rawFailure = result.failure,
                observedAt = observedAt,
            )

            is VoiceTurnRemoteConnectResult.OutcomeUnknown -> MutationPlan.Write(
                state.remoteUnknown(
                    operation = VoiceTurnRemoteOperation.CONNECT,
                    boundary = result.boundary,
                    failure = normalizeFailure(
                        result.failure,
                        state.admission.identity.correlationId,
                    ),
                ),
            )
        }
    }.let { persisted ->
        when (result) {
            is VoiceTurnRemoteConnectResult.NotAttempted ->
                persisted.asRemoteRejection(result.failure)

            is VoiceTurnRemoteConnectResult.Rejected ->
                persisted.asRemoteRejection(result.failure)

            is VoiceTurnRemoteConnectResult.OutcomeUnknown ->
                persisted.asOutcomeUnknownIfNeeded()

            is VoiceTurnRemoteConnectResult.Ready -> persisted
        }
    }

    private suspend fun selectNextFrame(turnId: VoiceTurnId): FrameSelection {
        val loaded = loadState(turnId)
        val state = when (loaded) {
            is StateLoad.Loaded -> loaded.state
            StateLoad.Missing -> return FrameSelection.Result(missing(turnId))
            is StateLoad.Unavailable -> return FrameSelection.Result(loaded.result)
        }
        if (state.terminal != null) {
            return FrameSelection.Result(
                VoiceTurnOperationResult.NoWork(
                    state.project(),
                    VoiceTurnNoWorkReason.TERMINAL,
                ),
            )
        }
        val phase = state.remotePhase as? VoiceTurnRemotePhase.Ready
            ?: return FrameSelection.Result(
                rejected(
                    state,
                    FailureCategory.REJECTED_POLICY,
                    "VOICE_TURN_REMOTE_NOT_READY",
                    retryable = true,
                ),
            )
        val next = state.frames.firstOrNull { frame ->
            state.remoteAcknowledgedThrough == null ||
                frame.descriptor.ordinal > state.remoteAcknowledgedThrough
        }
        if (next == null) {
            return FrameSelection.Result(
                VoiceTurnOperationResult.NoWork(
                    state.project(),
                    if (state.localInputStatus == VoiceTurnLocalInputStatus.ENDED) {
                        VoiceTurnNoWorkReason.READY_TO_FINISH
                    } else {
                        VoiceTurnNoWorkReason.WAITING_FOR_LOCAL_FRAME
                    },
                ),
            )
        }
        return FrameSelection.Ready(state, phase.attempt, next)
    }

    private fun planFrameAttempt(
        state: VoiceTurnState,
        frame: DurableVoiceTurnFrame,
    ): MutationPlan {
        if (state.terminal != null || state.localInputStatus == VoiceTurnLocalInputStatus.CANCELLED) {
            return MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        }
        val phase = state.remotePhase as? VoiceTurnRemotePhase.Ready
            ?: return MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        val expected = state.frames.firstOrNull {
            state.remoteAcknowledgedThrough == null ||
                it.descriptor.ordinal > state.remoteAcknowledgedThrough
        }
        if (expected != frame) {
            return MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        }
        return MutationPlan.Write(
            state.next(
                remotePhase = VoiceTurnRemotePhase.FrameAttemptFenced(
                    attempt = phase.attempt,
                    remoteSessionRef = phase.remoteSessionRef,
                    frameOrdinal = frame.descriptor.ordinal,
                    contentDigest = frame.contentDigest,
                ),
            ),
        )
    }

    private suspend fun applyFrameResult(
        turnId: VoiceTurnId,
        fenced: VoiceTurnRemotePhase.FrameAttemptFenced,
        frame: DurableVoiceTurnFrame,
        result: VoiceTurnRemoteFrameResult,
        observedAt: VoiceTurnClockMark,
    ): VoiceTurnOperationResult = mutate(turnId) { state ->
        if (state.remoteGeneration > fenced.attempt.generation || state.terminal != null) {
            return@mutate MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        }
        if (state.remotePhase != fenced) {
            return@mutate MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        }
        when (result) {
            is VoiceTurnRemoteFrameResult.Accepted -> {
                if (!result.ack.matches(state, fenced, frame)) {
                    MutationPlan.Write(
                        state.remoteUnknown(
                            VoiceTurnRemoteOperation.SEND_FRAME,
                            VoiceTurnOutcomeBoundary.REMOTE_TRANSPORT,
                            failure(
                                state.admission.identity.correlationId,
                                FailureCategory.CORRUPT,
                                "VOICE_TURN_REMOTE_FRAME_ACK_MISMATCH",
                                retryable = true,
                            ),
                        ),
                    )
                } else {
                    MutationPlan.Write(
                        state.next(
                            remoteAcknowledgedThrough = frame.descriptor.ordinal,
                            remotePhase = VoiceTurnRemotePhase.Ready(
                                fenced.attempt,
                                fenced.remoteSessionRef,
                            ),
                            timeline = state.timeline.copy(
                                lastRemoteAcknowledgementAt = observedAt,
                            ),
                        ),
                    )
                }
            }

            is VoiceTurnRemoteFrameResult.NotAttempted -> MutationPlan.Write(
                state.next(
                    remotePhase = VoiceTurnRemotePhase.Ready(
                        fenced.attempt,
                        fenced.remoteSessionRef,
                    ),
                ),
            )

            is VoiceTurnRemoteFrameResult.Rejected -> planRemoteFailure(
                state = state,
                generation = fenced.attempt.generation,
                operation = VoiceTurnRemoteOperation.SEND_FRAME,
                boundary = result.boundary,
                rawFailure = result.failure,
                observedAt = observedAt,
            )

            is VoiceTurnRemoteFrameResult.OutcomeUnknown -> MutationPlan.Write(
                state.remoteUnknown(
                    VoiceTurnRemoteOperation.SEND_FRAME,
                    result.boundary,
                    normalizeFailure(
                        result.failure,
                        state.admission.identity.correlationId,
                    ),
                ),
            )
        }
    }.let { persisted ->
        when (result) {
            is VoiceTurnRemoteFrameResult.NotAttempted ->
                persisted.asRemoteRejection(result.failure)

            is VoiceTurnRemoteFrameResult.Rejected ->
                persisted.asRemoteRejection(result.failure)

            is VoiceTurnRemoteFrameResult.OutcomeUnknown ->
                persisted.asOutcomeUnknownIfNeeded()

            is VoiceTurnRemoteFrameResult.Accepted -> persisted
        }
    }

    private fun planFinish(state: VoiceTurnState): MutationPlan {
        if (state.terminal != null) {
            return MutationPlan.Done(
                VoiceTurnOperationResult.NoWork(
                    state.project(),
                    VoiceTurnNoWorkReason.TERMINAL,
                ),
            )
        }
        if (state.remotePhase is VoiceTurnRemotePhase.AwaitingResult) {
            return MutationPlan.Done(
                VoiceTurnOperationResult.NoWork(
                    state.project(),
                    VoiceTurnNoWorkReason.AWAITING_REMOTE_RESULT,
                ),
            )
        }
        if (state.localInputStatus != VoiceTurnLocalInputStatus.ENDED) {
            return MutationPlan.Done(
                rejected(
                    state,
                    FailureCategory.REJECTED_POLICY,
                    "VOICE_TURN_INPUT_STILL_OPEN",
                    retryable = true,
                ),
            )
        }
        val phase = state.remotePhase as? VoiceTurnRemotePhase.Ready
            ?: return MutationPlan.Done(
                rejected(
                    state,
                    FailureCategory.REJECTED_POLICY,
                    "VOICE_TURN_REMOTE_NOT_READY",
                    retryable = true,
                ),
            )
        val terminalFrame = state.frames.lastOrNull()?.descriptor?.ordinal
        if (state.remoteAcknowledgedThrough != terminalFrame) {
            return MutationPlan.Done(
                rejected(
                    state,
                    FailureCategory.REJECTED_POLICY,
                    "VOICE_TURN_REMOTE_FRAMES_PENDING",
                    retryable = true,
                ),
            )
        }
        return MutationPlan.Write(
            state.next(
                remotePhase = VoiceTurnRemotePhase.FinishAttemptFenced(
                    phase.attempt,
                    phase.remoteSessionRef,
                ),
            ),
        )
    }

    private suspend fun applyFinishResult(
        turnId: VoiceTurnId,
        fenced: VoiceTurnRemotePhase.FinishAttemptFenced,
        result: VoiceTurnRemoteFinishResult,
        observedAt: VoiceTurnClockMark,
    ): VoiceTurnOperationResult = mutate(turnId) { state ->
        if (state.remoteGeneration > fenced.attempt.generation || state.terminal != null) {
            return@mutate MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        }
        if (state.remotePhase != fenced) {
            return@mutate MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        }
        when (result) {
            VoiceTurnRemoteFinishResult.AwaitingResult -> MutationPlan.Write(
                state.next(
                    remotePhase = VoiceTurnRemotePhase.AwaitingResult(
                        fenced.attempt,
                        fenced.remoteSessionRef,
                    ),
                ),
            )

            is VoiceTurnRemoteFinishResult.NotAttempted -> MutationPlan.Write(
                state.next(
                    remotePhase = VoiceTurnRemotePhase.Ready(
                        fenced.attempt,
                        fenced.remoteSessionRef,
                    ),
                ),
            )

            is VoiceTurnRemoteFinishResult.Rejected -> planRemoteFailure(
                state = state,
                generation = fenced.attempt.generation,
                operation = VoiceTurnRemoteOperation.FINISH_INPUT,
                boundary = result.boundary,
                rawFailure = result.failure,
                observedAt = observedAt,
            )

            is VoiceTurnRemoteFinishResult.OutcomeUnknown -> MutationPlan.Write(
                state.remoteUnknown(
                    VoiceTurnRemoteOperation.FINISH_INPUT,
                    result.boundary,
                    normalizeFailure(
                        result.failure,
                        state.admission.identity.correlationId,
                    ),
                ),
            )
        }
    }.let { persisted ->
        when (result) {
            is VoiceTurnRemoteFinishResult.NotAttempted ->
                persisted.asRemoteRejection(result.failure)

            is VoiceTurnRemoteFinishResult.Rejected ->
                persisted.asRemoteRejection(result.failure)

            is VoiceTurnRemoteFinishResult.OutcomeUnknown ->
                persisted.asOutcomeUnknownIfNeeded()

            VoiceTurnRemoteFinishResult.AwaitingResult -> persisted
        }
    }

    private fun planCancellation(
        state: VoiceTurnState,
        cancellation: VoiceTurnCancellation,
    ): MutationPlan {
        state.terminal?.let { terminal ->
            return MutationPlan.Done(
                if (
                    terminal is VoiceTurnTerminalOutcome.Cancelled &&
                    terminal.cancellation == cancellation
                ) {
                    VoiceTurnOperationResult.Duplicate(state.project())
                } else {
                    VoiceTurnOperationResult.NoWork(
                        state.project(),
                        VoiceTurnNoWorkReason.TERMINAL,
                    )
                },
            )
        }
        state.cancellation?.let { prior ->
            return MutationPlan.Done(
                if (prior == cancellation) {
                    VoiceTurnOperationResult.Duplicate(state.project())
                } else {
                    rejected(
                        state,
                        FailureCategory.REPLAYED,
                        "VOICE_TURN_CANCELLATION_CONFLICT",
                        retryable = false,
                    )
                },
            )
        }
        val noRemoteEffect = state.remoteGeneration == 0uL &&
            state.remotePhase == VoiceTurnRemotePhase.Disconnected
        if (noRemoteEffect) {
            val terminal = VoiceTurnTerminalOutcome.Cancelled(
                cancellation = cancellation,
                remoteDisposition = VoiceTurnCancellationRemoteDisposition.NOT_NEEDED,
                terminalAt = cancellation.requestedAt,
            )
            return MutationPlan.Write(
                state.next(
                    localInputStatus = VoiceTurnLocalInputStatus.CANCELLED,
                    cancellation = cancellation,
                    remotePhase = VoiceTurnRemotePhase.Closed,
                    terminal = terminal,
                    timeline = state.timeline.copy(terminalAt = terminal.terminalAt),
                ),
            )
        }
        return MutationPlan.Write(
            state.next(
                localInputStatus = VoiceTurnLocalInputStatus.CANCELLED,
                cancellation = cancellation,
                remotePhase = VoiceTurnRemotePhase.CancelAttemptFenced(
                    generation = state.remoteGeneration,
                    cancellation = cancellation,
                    remoteSessionRef = state.remotePhase.remoteSessionRefOrNull(),
                ),
            ),
        )
    }

    private suspend fun applyCancelResult(
        turnId: VoiceTurnId,
        fenced: VoiceTurnRemotePhase.CancelAttemptFenced,
        result: VoiceTurnRemoteCancelResult,
        observedAt: VoiceTurnClockMark,
    ): VoiceTurnOperationResult = mutate(turnId) { state ->
        if (state.remotePhase != fenced || state.terminal != null) {
            return@mutate MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        }
        val expectedCorrelation = fenced.cancellation.correlationId
        val terminal = when (result) {
            VoiceTurnRemoteCancelResult.Confirmed -> VoiceTurnTerminalOutcome.Cancelled(
                cancellation = fenced.cancellation,
                remoteDisposition = VoiceTurnCancellationRemoteDisposition.CONFIRMED,
                terminalAt = observedAt,
            )

            VoiceTurnRemoteCancelResult.NotNeeded -> VoiceTurnTerminalOutcome.Cancelled(
                cancellation = fenced.cancellation,
                remoteDisposition = VoiceTurnCancellationRemoteDisposition.NOT_NEEDED,
                terminalAt = observedAt,
            )

            is VoiceTurnRemoteCancelResult.Rejected -> VoiceTurnTerminalOutcome.Cancelled(
                cancellation = fenced.cancellation,
                remoteDisposition = VoiceTurnCancellationRemoteDisposition.REJECTED,
                failure = normalizeFailure(result.failure, expectedCorrelation),
                terminalAt = observedAt,
            )

            is VoiceTurnRemoteCancelResult.OutcomeUnknown -> VoiceTurnTerminalOutcome.Cancelled(
                cancellation = fenced.cancellation,
                remoteDisposition = VoiceTurnCancellationRemoteDisposition.OUTCOME_UNKNOWN,
                failure = normalizeFailure(result.failure, expectedCorrelation),
                terminalAt = observedAt,
            )
        }
        MutationPlan.Write(
            state.next(
                remotePhase = VoiceTurnRemotePhase.Closed,
                terminal = terminal,
                timeline = state.timeline.copy(terminalAt = terminal.terminalAt),
            ),
        )
    }.let { applied ->
        val terminal = (applied as? VoiceTurnOperationResult.Applied)?.projection?.terminal
        if (
            terminal is VoiceTurnTerminalOutcome.Cancelled &&
            terminal.remoteDisposition == VoiceTurnCancellationRemoteDisposition.OUTCOME_UNKNOWN
        ) {
            val unknown = VoiceTurnRemoteUnknown(
                operation = VoiceTurnRemoteOperation.CANCEL,
                boundary = (result as? VoiceTurnRemoteCancelResult.OutcomeUnknown)?.boundary
                    ?: VoiceTurnOutcomeBoundary.REMOTE_TRANSPORT,
                failure = terminal.failure!!,
            )
            VoiceTurnOperationResult.OutcomeUnknown(applied.projection, unknown)
        } else {
            applied
        }
    }

    private fun planRemoteEvent(
        state: VoiceTurnState,
        event: VoiceTurnRemoteEvent,
    ): MutationPlan {
        if (event.generation < state.remoteGeneration) {
            return MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        }
        if (event.generation > state.remoteGeneration) {
            return MutationPlan.Done(
                rejected(
                    state,
                    FailureCategory.CORRUPT,
                    "VOICE_TURN_REMOTE_EVENT_FUTURE_GENERATION",
                    retryable = false,
                ),
            )
        }
        state.terminal?.let { terminal ->
            val duplicate = event is VoiceTurnRemoteEvent.TerminalResult &&
                terminal is VoiceTurnTerminalOutcome.Completed &&
                terminal.result == event.result
            return MutationPlan.Done(
                if (duplicate) {
                    VoiceTurnOperationResult.Duplicate(state.project())
                } else {
                    VoiceTurnOperationResult.Stale(state.project())
                },
            )
        }
        return when (event) {
            is VoiceTurnRemoteEvent.TerminalResult -> {
                if (!event.result.matches(state)) {
                    MutationPlan.Write(
                        state.remoteUnknown(
                            VoiceTurnRemoteOperation.AWAIT_PROVIDER_RESULT,
                            VoiceTurnOutcomeBoundary.PROVIDER,
                            failure(
                                state.admission.identity.correlationId,
                                FailureCategory.CORRUPT,
                                "VOICE_TURN_REMOTE_RESULT_IDENTITY_MISMATCH",
                                retryable = true,
                            ),
                        ),
                    )
                } else if (
                    state.remotePhase !is VoiceTurnRemotePhase.AwaitingResult &&
                    state.remotePhase !is VoiceTurnRemotePhase.FinishAttemptFenced &&
                    state.remotePhase !is VoiceTurnRemotePhase.OutcomeUnknown
                ) {
                    MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
                } else {
                    val terminal = VoiceTurnTerminalOutcome.Completed(event.result)
                    MutationPlan.Write(
                        state.next(
                            remotePhase = VoiceTurnRemotePhase.Closed,
                            terminal = terminal,
                            timeline = state.timeline.copy(terminalAt = terminal.terminalAt),
                        ),
                    )
                }
            }

            is VoiceTurnRemoteEvent.ProviderFailure -> planRemoteFailure(
                state = state,
                generation = event.generation,
                operation = VoiceTurnRemoteOperation.AWAIT_PROVIDER_RESULT,
                boundary = VoiceTurnOutcomeBoundary.PROVIDER,
                rawFailure = if (event.terminal) {
                    event.failure.copy(retryable = false)
                } else {
                    event.failure
                },
                observedAt = event.observedAt,
            )

            is VoiceTurnRemoteEvent.Disconnected -> {
                val operation = state.remotePhase.currentOperation()
                val normalized = normalizeFailure(
                    event.failure,
                    state.admission.identity.correlationId,
                )
                if (event.outcomeUnknown) {
                    MutationPlan.Write(
                        state.remoteUnknown(operation, event.boundary, normalized),
                    )
                } else {
                    planRemoteFailure(
                        state = state,
                        generation = event.generation,
                        operation = operation,
                        boundary = event.boundary,
                        rawFailure = normalized,
                        observedAt = event.observedAt,
                    )
                }
            }

            is VoiceTurnRemoteEvent.CancellationConfirmed -> {
                val phase = state.remotePhase as? VoiceTurnRemotePhase.CancelAttemptFenced
                    ?: return MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
                if (event.cancellationCommandId != phase.cancellation.commandId) {
                    return MutationPlan.Done(
                        rejected(
                            state,
                            FailureCategory.CORRUPT,
                            "VOICE_TURN_CANCEL_CONFIRMATION_MISMATCH",
                            retryable = false,
                        ),
                    )
                }
                val terminal = VoiceTurnTerminalOutcome.Cancelled(
                    cancellation = phase.cancellation,
                    remoteDisposition = VoiceTurnCancellationRemoteDisposition.CONFIRMED,
                    terminalAt = event.observedAt,
                )
                MutationPlan.Write(
                    state.next(
                        remotePhase = VoiceTurnRemotePhase.Closed,
                        terminal = terminal,
                        timeline = state.timeline.copy(terminalAt = terminal.terminalAt),
                    ),
                )
            }
        }
    }

    private fun planRemoteFailure(
        state: VoiceTurnState,
        generation: ULong,
        operation: VoiceTurnRemoteOperation,
        boundary: VoiceTurnOutcomeBoundary,
        rawFailure: ExpectedFailure,
        observedAt: VoiceTurnClockMark,
    ): MutationPlan {
        val normalized = normalizeFailure(
            rawFailure,
            state.admission.identity.correlationId,
        )
        return if (normalized.retryable) {
            MutationPlan.Write(
                state.next(
                    remotePhase = VoiceTurnRemotePhase.RetryableFailure(
                        generation,
                        operation,
                        boundary,
                        normalized,
                    ),
                ),
            )
        } else {
            val terminal = VoiceTurnTerminalOutcome.Failed(
                correlationId = state.admission.identity.correlationId,
                operation = operation,
                boundary = boundary,
                failure = normalized,
                terminalAt = observedAt,
            )
            MutationPlan.Write(
                state.next(
                    remotePhase = VoiceTurnRemotePhase.Closed,
                    terminal = terminal,
                    timeline = state.timeline.copy(terminalAt = terminal.terminalAt),
                ),
            )
        }
    }

    private suspend fun markClockUnknown(
        turnId: VoiceTurnId,
        generation: ULong,
        operation: VoiceTurnRemoteOperation,
    ): VoiceTurnOperationResult = mutate(turnId) { state ->
        if (state.remoteGeneration != generation || state.terminal != null) {
            MutationPlan.Done(VoiceTurnOperationResult.Stale(state.project()))
        } else {
            MutationPlan.Write(
                state.remoteUnknown(
                    operation,
                    VoiceTurnOutcomeBoundary.REMOTE_TRANSPORT,
                    failure(
                        state.admission.identity.correlationId,
                        FailureCategory.INTERNAL,
                        "VOICE_TURN_STAGE_CLOCK_FAILED",
                        retryable = true,
                    ),
                ),
            )
        }
    }.asOutcomeUnknownIfNeeded()

    private fun safeClockAfterExternalEffect(
        state: VoiceTurnState,
        operation: VoiceTurnRemoteOperation,
    ): VoiceTurnClockMark? = try {
        clock.now()
    } catch (_: Throwable) {
        @Suppress("UNUSED_VARIABLE")
        val deliberatelyUnused = operation to state.revision
        null
    }

    private fun resumeAckMismatch(
        state: VoiceTurnState,
        attempt: VoiceTurnConnectionAttempt,
        ack: VoiceTurnRemoteResumeAck,
    ): ExpectedFailure? {
        val identity = state.admission.identity
        val accepted = ack.acceptedThroughOrdinal
        val prior = state.remoteAcknowledgedThrough
        val mismatch = ack.sessionId != identity.sessionId ||
            ack.turnId != identity.turnId ||
            ack.generation != attempt.generation ||
            (prior != null && (accepted == null || accepted < prior)) ||
            (accepted != null && state.frames.none { it.descriptor.ordinal == accepted })
        return if (mismatch) {
            failure(
                identity.correlationId,
                FailureCategory.CORRUPT,
                "VOICE_TURN_REMOTE_RESUME_ACK_MISMATCH",
                retryable = true,
            )
        } else {
            null
        }
    }

    private suspend fun exactConnectState(
        turnId: VoiceTurnId,
        attemptId: VoiceTurnRemoteAttemptId,
    ): VoiceTurnState? = (loadState(turnId) as? StateLoad.Loaded)?.state?.takeIf { state ->
        (state.remotePhase as? VoiceTurnRemotePhase.ConnectAttemptFenced)?.attempt?.id == attemptId
    }

    private suspend fun exactFrameAttemptState(
        turnId: VoiceTurnId,
        generation: ULong,
        ordinal: ULong,
    ): VoiceTurnState? = (loadState(turnId) as? StateLoad.Loaded)?.state?.takeIf { state ->
        val phase = state.remotePhase as? VoiceTurnRemotePhase.FrameAttemptFenced
        phase?.attempt?.generation == generation && phase.frameOrdinal == ordinal
    }

    private suspend fun exactFinishState(turnId: VoiceTurnId): VoiceTurnState? =
        (loadState(turnId) as? StateLoad.Loaded)?.state?.takeIf {
            it.remotePhase is VoiceTurnRemotePhase.FinishAttemptFenced
        }

    private suspend fun currentStaleOrUnavailable(turnId: VoiceTurnId): VoiceTurnOperationResult =
        when (val loaded = loadState(turnId)) {
            is StateLoad.Loaded -> VoiceTurnOperationResult.Stale(loaded.state.project())
            StateLoad.Missing -> missing(turnId)
            is StateLoad.Unavailable -> loaded.result
        }

    private suspend fun loadState(turnId: VoiceTurnId): StateLoad = when (
        val loaded = stateStore.load(turnId)
    ) {
        is VoiceTurnStoreLoadResult.Loaded -> StateLoad.Loaded(loaded.state)
        VoiceTurnStoreLoadResult.Missing -> StateLoad.Missing
        is VoiceTurnStoreLoadResult.Unavailable -> StateLoad.Unavailable(
            unavailable(null, loaded.failure),
        )
    }

    private suspend fun mutate(
        turnId: VoiceTurnId,
        planner: (VoiceTurnState) -> MutationPlan,
    ): VoiceTurnOperationResult = mutationMutex.withLock {
        repeat(MAX_COMMIT_RECONCILIATIONS) {
            val state = when (val loaded = stateStore.load(turnId)) {
                is VoiceTurnStoreLoadResult.Loaded -> loaded.state
                VoiceTurnStoreLoadResult.Missing -> return@withLock missing(turnId)
                is VoiceTurnStoreLoadResult.Unavailable -> {
                    return@withLock unavailable(null, loaded.failure)
                }
            }
            val plan = try {
                planner(state)
            } catch (_: VoiceTurnRevisionExhausted) {
                return@withLock rejected(
                    state,
                    FailureCategory.RESOURCE_EXHAUSTED,
                    "VOICE_TURN_REVISION_EXHAUSTED",
                    retryable = false,
                )
            }
            when (plan) {
                is MutationPlan.Done -> return@withLock plan.result
                is MutationPlan.Write -> {
                    require(plan.next.revision == state.revision + 1uL) {
                        "VoiceTurn mutation must advance exactly one revision"
                    }
                    when (val committed = stateStore.commit(state.revision, plan.next)) {
                        VoiceTurnStoreCommitResult.Committed -> {
                            return@withLock VoiceTurnOperationResult.Applied(plan.next.project())
                        }

                        is VoiceTurnStoreCommitResult.IdentityConflict -> {
                            return@withLock VoiceTurnOperationResult.Rejected(
                                projection = state.project(),
                                failure = failure(
                                    state.admission.identity.correlationId,
                                    FailureCategory.REPLAYED,
                                    committed.code,
                                    retryable = false,
                                ),
                            )
                        }

                        is VoiceTurnStoreCommitResult.Unavailable -> {
                            return@withLock unavailable(
                                state.admission.identity.correlationId,
                                committed.failure,
                            )
                        }

                        is VoiceTurnStoreCommitResult.RevisionMismatch,
                        VoiceTurnStoreCommitResult.OutcomeUnknown,
                        -> Unit // Reload and reconcile the exact immutable intent.
                    }
                }
            }
        }
        VoiceTurnOperationResult.Unavailable(
            failure(
                correlationId = null,
                category = FailureCategory.UNAVAILABLE,
                code = "VOICE_TURN_STORE_RECONCILIATION_EXHAUSTED",
                retryable = true,
            ),
        )
    }

    private fun VoiceTurnState.next(
        frames: List<DurableVoiceTurnFrame> = this.frames,
        retainedBytes: ULong = this.retainedBytes,
        localInputStatus: VoiceTurnLocalInputStatus = this.localInputStatus,
        remoteGeneration: ULong = this.remoteGeneration,
        connectionAttempts: List<VoiceTurnConnectionAttempt> = this.connectionAttempts,
        remoteAcknowledgedThrough: ULong? = this.remoteAcknowledgedThrough,
        remotePhase: VoiceTurnRemotePhase = this.remotePhase,
        cancellation: VoiceTurnCancellation? = this.cancellation,
        terminal: VoiceTurnTerminalOutcome? = this.terminal,
        timeline: VoiceTurnTimeline = this.timeline,
    ): VoiceTurnState {
        if (revision == ULong.MAX_VALUE) throw VoiceTurnRevisionExhausted()
        return copy(
            revision = revision + 1uL,
            frames = frames,
            retainedBytes = retainedBytes,
            localInputStatus = localInputStatus,
            remoteGeneration = remoteGeneration,
            connectionAttempts = connectionAttempts,
            remoteAcknowledgedThrough = remoteAcknowledgedThrough,
            remotePhase = remotePhase,
            cancellation = cancellation,
            terminal = terminal,
            timeline = timeline,
        )
    }

    private fun VoiceTurnState.remoteUnknown(
        operation: VoiceTurnRemoteOperation,
        boundary: VoiceTurnOutcomeBoundary,
        failure: ExpectedFailure,
    ): VoiceTurnState = next(
        remotePhase = VoiceTurnRemotePhase.OutcomeUnknown(
            generation = remoteGeneration,
            unknown = VoiceTurnRemoteUnknown(operation, boundary, failure),
        ),
    )

    private fun normalizeFailure(
        raw: ExpectedFailure,
        expectedCorrelation: CorrelationId,
    ): ExpectedFailure = if (
        raw.correlationId != null && raw.correlationId != expectedCorrelation
    ) {
        failure(
            correlationId = expectedCorrelation,
            category = FailureCategory.CORRUPT,
            code = "VOICE_TURN_FAILURE_CORRELATION_MISMATCH",
            retryable = false,
        )
    } else {
        raw.copy(correlationId = expectedCorrelation)
    }

    private fun rejected(
        state: VoiceTurnState,
        category: FailureCategory,
        code: String,
        retryable: Boolean,
        evidence: Map<String, String> = emptyMap(),
    ): VoiceTurnOperationResult.Rejected = VoiceTurnOperationResult.Rejected(
        projection = state.project(),
        failure = failure(
            correlationId = state.admission.identity.correlationId,
            category = category,
            code = code,
            retryable = retryable,
            evidence = evidence,
        ),
    )

    private fun unavailable(
        correlationId: CorrelationId?,
        storeFailure: VoiceTurnStoreFailure,
    ): VoiceTurnOperationResult.Unavailable = VoiceTurnOperationResult.Unavailable(
        failure(
            correlationId = correlationId,
            category = FailureCategory.UNAVAILABLE,
            code = storeFailure.code,
            retryable = storeFailure.retryable,
        ),
    )

    private fun missing(turnId: VoiceTurnId): VoiceTurnOperationResult.Rejected =
        VoiceTurnOperationResult.Rejected(
            projection = null,
            failure = failure(
                correlationId = null,
                category = FailureCategory.UNAVAILABLE,
                code = "VOICE_TURN_NOT_FOUND",
                retryable = false,
                evidence = mapOf("turn" to stableEvidence(turnId.value)),
            ),
        )

    private fun VoiceTurnOperationResult.asOutcomeUnknownIfNeeded(): VoiceTurnOperationResult {
        val applied = this as? VoiceTurnOperationResult.Applied ?: return this
        val phase = applied.projection.remote
        if (applied.projection.status != VoiceTurnStatus.REMOTE_OUTCOME_UNKNOWN) return this
        return VoiceTurnOperationResult.OutcomeUnknown(
            projection = applied.projection,
            unknown = VoiceTurnRemoteUnknown(
                operation = requireNotNull(phase.operation),
                boundary = requireNotNull(phase.outcomeBoundary),
                failure = requireNotNull(phase.failure),
            ),
        )
    }

    private fun VoiceTurnOperationResult.asRemoteRejection(
        rawFailure: ExpectedFailure,
    ): VoiceTurnOperationResult {
        val applied = this as? VoiceTurnOperationResult.Applied ?: return this
        val projectedFailure = applied.projection.remote.failure
            ?: (applied.projection.terminal as? VoiceTurnTerminalOutcome.Failed)?.failure
            ?: normalizeFailure(rawFailure, applied.projection.identity.correlationId)
        return VoiceTurnOperationResult.Rejected(applied.projection, projectedFailure)
    }

    private fun VoiceTurnRemoteFrameAck.matches(
        state: VoiceTurnState,
        fenced: VoiceTurnRemotePhase.FrameAttemptFenced,
        frame: DurableVoiceTurnFrame,
    ): Boolean {
        val identity = state.admission.identity
        return sessionId == identity.sessionId &&
            turnId == identity.turnId &&
            generation == fenced.attempt.generation &&
            ordinal == frame.descriptor.ordinal &&
            contentDigest == frame.contentDigest
    }

    private fun VoiceTurnRemoteTerminalResult.matches(state: VoiceTurnState): Boolean {
        val identity = state.admission.identity
        return sessionId == identity.sessionId &&
            turnId == identity.turnId &&
            correlationId == identity.correlationId
    }

    private fun VoiceTurnRemotePhase.attemptOrNull(): VoiceTurnConnectionAttempt? = when (this) {
        is VoiceTurnRemotePhase.ConnectAttemptFenced -> attempt
        is VoiceTurnRemotePhase.Ready -> attempt
        is VoiceTurnRemotePhase.FrameAttemptFenced -> attempt
        is VoiceTurnRemotePhase.FinishAttemptFenced -> attempt
        is VoiceTurnRemotePhase.AwaitingResult -> attempt
        else -> null
    }

    private fun VoiceTurnRemotePhase.remoteSessionRefOrNull(): VoiceTurnRemoteSessionRef? = when (this) {
        is VoiceTurnRemotePhase.Ready -> remoteSessionRef
        is VoiceTurnRemotePhase.FrameAttemptFenced -> remoteSessionRef
        is VoiceTurnRemotePhase.FinishAttemptFenced -> remoteSessionRef
        is VoiceTurnRemotePhase.AwaitingResult -> remoteSessionRef
        else -> null
    }

    private fun VoiceTurnRemotePhase.currentOperation(): VoiceTurnRemoteOperation = when (this) {
        VoiceTurnRemotePhase.Disconnected,
        is VoiceTurnRemotePhase.ConnectAttemptFenced,
        is VoiceTurnRemotePhase.Ready,
        -> VoiceTurnRemoteOperation.CONNECT

        is VoiceTurnRemotePhase.FrameAttemptFenced -> VoiceTurnRemoteOperation.SEND_FRAME
        is VoiceTurnRemotePhase.FinishAttemptFenced -> VoiceTurnRemoteOperation.FINISH_INPUT
        is VoiceTurnRemotePhase.AwaitingResult -> VoiceTurnRemoteOperation.AWAIT_PROVIDER_RESULT
        is VoiceTurnRemotePhase.RetryableFailure -> operation
        is VoiceTurnRemotePhase.OutcomeUnknown -> unknown.operation
        is VoiceTurnRemotePhase.CancelAttemptFenced -> VoiceTurnRemoteOperation.CANCEL
        VoiceTurnRemotePhase.Closed -> VoiceTurnRemoteOperation.AWAIT_PROVIDER_RESULT
    }

    private sealed interface MutationPlan {
        data class Done(val result: VoiceTurnOperationResult) : MutationPlan

        data class Write(val next: VoiceTurnState) : MutationPlan
    }

    private sealed interface StateLoad {
        data class Loaded(val state: VoiceTurnState) : StateLoad

        data object Missing : StateLoad

        data class Unavailable(val result: VoiceTurnOperationResult.Unavailable) : StateLoad
    }

    private sealed interface FrameSelection {
        data class Ready(
            val state: VoiceTurnState,
            val attempt: VoiceTurnConnectionAttempt,
            val frame: DurableVoiceTurnFrame,
        ) : FrameSelection

        data class Result(val result: VoiceTurnOperationResult) : FrameSelection
    }

    private class VoiceTurnRevisionExhausted : IllegalStateException()

    companion object {
        private const val MAX_COMMIT_RECONCILIATIONS = 8
    }
}

private fun failure(
    correlationId: CorrelationId?,
    category: FailureCategory,
    code: String,
    retryable: Boolean,
    evidence: Map<String, String> = emptyMap(),
): ExpectedFailure = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
    correlationId = correlationId,
    redactedEvidence = evidence,
)

private fun stableEvidence(value: String): String {
    var hash = 1125899906842597L
    value.forEach { character -> hash = 31L * hash + character.code }
    return hash.toULong().toString(16)
}
