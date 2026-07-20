package dev.gumi.edge.runtime.realtime

import dev.gumi.edge.runtime.spool.DurablePayloadRef
import dev.gumi.edge.runtime.spool.Sha256Digest
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure

@JvmInline
value class VoiceTurnSessionId(val value: String) {
    init {
        requireVoiceTurnId("VoiceTurn session ID", value)
    }

    override fun toString(): String = value
}

@JvmInline
value class VoiceTurnId(val value: String) {
    init {
        requireVoiceTurnId("VoiceTurn ID", value)
    }

    override fun toString(): String = value
}

/** Reference to the shell/cloud admission decision. It is not a bearer credential. */
@JvmInline
value class VoiceTurnAdmissionLeaseId(val value: String) {
    init {
        requireVoiceTurnId("VoiceTurn admission lease ID", value)
    }

    override fun toString(): String = value
}

/** Stable identity for one remote connection or resume attempt. */
@JvmInline
value class VoiceTurnRemoteAttemptId(val value: String) {
    init {
        requireVoiceTurnId("VoiceTurn remote attempt ID", value)
    }

    override fun toString(): String = value
}

/** Non-secret provider/session correlation. It must never contain a credential or URL. */
@JvmInline
value class VoiceTurnRemoteSessionRef(val value: String) {
    init {
        requireVoiceTurnId("VoiceTurn remote session reference", value, maximumLength = 512)
    }

    override fun toString(): String = "<redacted-voice-turn-remote-session-ref>"
}

/** Opaque durable reference to the separately stored terminal result. */
@JvmInline
value class VoiceTurnResultRef(val value: String) {
    init {
        requireVoiceTurnId("VoiceTurn result reference", value, maximumLength = 512)
    }

    override fun toString(): String = "<redacted-voice-turn-result-ref>"
}

@JvmInline
value class VoiceTurnRemoteResultId(val value: String) {
    init {
        requireVoiceTurnId("VoiceTurn remote result ID", value)
    }

    override fun toString(): String = value
}

@JvmInline
value class VoiceTurnFrameFormatId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) {
            "VoiceTurn frame format ID must be a stable lowercase identifier"
        }
    }

    override fun toString(): String = value
}

/**
 * A monotonic host-clock mark remains comparable only within [hostBootId]. A restart never fabricates
 * cross-boot latency; the marks still preserve correlation and stage provenance.
 */
data class VoiceTurnClockMark(
    val hostBootId: String,
    val monotonicMillis: Long,
) {
    init {
        requireVoiceTurnId("Host boot ID", hostBootId)
        require(monotonicMillis >= 0) { "VoiceTurn monotonic time cannot be negative" }
    }
}

data class VoiceTurnIdentity(
    val sessionId: VoiceTurnSessionId,
    val turnId: VoiceTurnId,
    val deviceId: DeviceId,
    val startCommandId: CommandId,
    val correlationId: CorrelationId,
    val admissionLeaseId: VoiceTurnAdmissionLeaseId,
)

/**
 * Immutable admission-bound facts. Provider credentials, transport endpoints, and media bytes do
 * not belong here. The runtime rechecks the lease expiry even if the shell already checked it.
 */
data class VoiceTurnAdmission(
    val identity: VoiceTurnIdentity,
    val issuedAtEpochMillis: Long,
    val admissionExpiresAtEpochMillis: Long,
    val admittedAtEpochMillis: Long,
    val admittedAt: VoiceTurnClockMark,
    val bufferPolicy: VoiceTurnBufferPolicy = VoiceTurnBufferPolicy(),
) {
    init {
        require(issuedAtEpochMillis >= 0) { "VoiceTurn command time cannot be negative" }
        require(admittedAtEpochMillis >= issuedAtEpochMillis) {
            "VoiceTurn admission cannot predate its command"
        }
        require(admissionExpiresAtEpochMillis > admittedAtEpochMillis) {
            "VoiceTurn admission lease must be unexpired at runtime admission"
        }
    }
}

data class VoiceTurnBufferPolicy(
    val firstFrameOrdinal: ULong = 0uL,
    val maximumFrameBytes: UInt = 64u * 1024u,
    val maximumFrameCount: UInt = 3_000u,
    val maximumRetainedBytes: ULong = 64uL * 1024uL * 1024uL,
    val maximumRemoteGenerations: UInt = 32u,
) {
    init {
        require(maximumFrameBytes > 0u) { "VoiceTurn frame limit must be positive" }
        require(maximumFrameCount > 0u) { "VoiceTurn frame-count limit must be positive" }
        require(maximumRetainedBytes >= maximumFrameBytes.toULong()) {
            "VoiceTurn retained-byte limit cannot be smaller than one maximum frame"
        }
        require(maximumRemoteGenerations > 0u) {
            "VoiceTurn remote-generation limit must be positive"
        }
        require(
            firstFrameOrdinal <= ULong.MAX_VALUE - (maximumFrameCount - 1u).toULong(),
        ) { "VoiceTurn frame ordinal policy overflows" }
    }

    val lastFrameOrdinal: ULong = firstFrameOrdinal + (maximumFrameCount - 1u).toULong()
}

data class VoiceTurnFrameDescriptor(
    val sessionId: VoiceTurnSessionId,
    val turnId: VoiceTurnId,
    val ordinal: ULong,
    val formatId: VoiceTurnFrameFormatId,
    val payloadBytes: UInt,
    val receivedAt: VoiceTurnClockMark,
) {
    init {
        require(payloadBytes > 0u) { "VoiceTurn frames cannot be empty" }
    }
}

/** Proof-shaped metadata returned only after the frame store flushes encrypted bytes durably. */
data class DurableVoiceTurnFrame(
    val descriptor: VoiceTurnFrameDescriptor,
    val payloadRef: DurablePayloadRef,
    val contentDigest: Sha256Digest,
)

enum class VoiceTurnLocalInputStatus {
    OPEN,
    ENDED,
    CANCELLED,
}

enum class VoiceTurnRemoteOperation {
    CONNECT,
    SEND_FRAME,
    FINISH_INPUT,
    AWAIT_PROVIDER_RESULT,
    CANCEL,
}

enum class VoiceTurnOutcomeBoundary {
    REMOTE_TRANSPORT,
    PROVIDER,
}

data class VoiceTurnRemoteUnknown(
    val operation: VoiceTurnRemoteOperation,
    val boundary: VoiceTurnOutcomeBoundary,
    val failure: ExpectedFailure,
)

data class VoiceTurnConnectionAttempt(
    val id: VoiceTurnRemoteAttemptId,
    val generation: ULong,
    val startedAt: VoiceTurnClockMark,
)

sealed interface VoiceTurnRemotePhase {
    data object Disconnected : VoiceTurnRemotePhase

    /** The external call may be in flight or may have lost its response. Never replay it in-place. */
    data class ConnectAttemptFenced(
        val attempt: VoiceTurnConnectionAttempt,
        val resumeAfterOrdinal: ULong?,
    ) : VoiceTurnRemotePhase

    data class Ready(
        val attempt: VoiceTurnConnectionAttempt,
        val remoteSessionRef: VoiceTurnRemoteSessionRef,
    ) : VoiceTurnRemotePhase

    /** The exact frame identity may already have crossed the remote acceptance boundary. */
    data class FrameAttemptFenced(
        val attempt: VoiceTurnConnectionAttempt,
        val remoteSessionRef: VoiceTurnRemoteSessionRef,
        val frameOrdinal: ULong,
        val contentDigest: Sha256Digest,
    ) : VoiceTurnRemotePhase

    /** The provider may already have observed end-of-input. */
    data class FinishAttemptFenced(
        val attempt: VoiceTurnConnectionAttempt,
        val remoteSessionRef: VoiceTurnRemoteSessionRef,
    ) : VoiceTurnRemotePhase

    data class AwaitingResult(
        val attempt: VoiceTurnConnectionAttempt,
        val remoteSessionRef: VoiceTurnRemoteSessionRef,
    ) : VoiceTurnRemotePhase

    data class RetryableFailure(
        val generation: ULong,
        val operation: VoiceTurnRemoteOperation,
        val boundary: VoiceTurnOutcomeBoundary,
        val failure: ExpectedFailure,
    ) : VoiceTurnRemotePhase

    data class OutcomeUnknown(
        val generation: ULong,
        val unknown: VoiceTurnRemoteUnknown,
    ) : VoiceTurnRemotePhase

    data class CancelAttemptFenced(
        val generation: ULong,
        val cancellation: VoiceTurnCancellation,
        val remoteSessionRef: VoiceTurnRemoteSessionRef?,
    ) : VoiceTurnRemotePhase

    data object Closed : VoiceTurnRemotePhase
}

data class VoiceTurnCancellation(
    val commandId: CommandId,
    val correlationId: CorrelationId,
    val requestedAt: VoiceTurnClockMark,
)

enum class VoiceTurnCancellationRemoteDisposition {
    NOT_NEEDED,
    CONFIRMED,
    REJECTED,
    OUTCOME_UNKNOWN,
}

data class VoiceTurnRemoteTerminalResult(
    val sessionId: VoiceTurnSessionId,
    val turnId: VoiceTurnId,
    val correlationId: CorrelationId,
    val remoteResultId: VoiceTurnRemoteResultId,
    val resultRef: VoiceTurnResultRef,
    val observedAt: VoiceTurnClockMark,
)

sealed interface VoiceTurnTerminalOutcome {
    val correlationId: CorrelationId
    val terminalAt: VoiceTurnClockMark

    data class Completed(
        val result: VoiceTurnRemoteTerminalResult,
    ) : VoiceTurnTerminalOutcome {
        override val correlationId: CorrelationId = result.correlationId
        override val terminalAt: VoiceTurnClockMark = result.observedAt
    }

    data class Cancelled(
        val cancellation: VoiceTurnCancellation,
        val remoteDisposition: VoiceTurnCancellationRemoteDisposition,
        val failure: ExpectedFailure? = null,
        override val terminalAt: VoiceTurnClockMark,
    ) : VoiceTurnTerminalOutcome {
        override val correlationId: CorrelationId = cancellation.correlationId
    }

    data class Failed(
        override val correlationId: CorrelationId,
        val operation: VoiceTurnRemoteOperation,
        val boundary: VoiceTurnOutcomeBoundary,
        val failure: ExpectedFailure,
        override val terminalAt: VoiceTurnClockMark,
    ) : VoiceTurnTerminalOutcome
}

data class VoiceTurnTimeline(
    val admittedAt: VoiceTurnClockMark,
    val firstFrameAt: VoiceTurnClockMark? = null,
    val lastFrameAt: VoiceTurnClockMark? = null,
    val inputEndedAt: VoiceTurnClockMark? = null,
    val remoteReadyAt: VoiceTurnClockMark? = null,
    val lastRemoteAcknowledgementAt: VoiceTurnClockMark? = null,
    val terminalAt: VoiceTurnClockMark? = null,
)

/**
 * Durable control state. It contains only opaque payload/result references, never audio bytes,
 * provider response bodies, credentials, endpoints, or UI text.
 */
data class VoiceTurnState(
    val revision: ULong,
    val admission: VoiceTurnAdmission,
    val frames: List<DurableVoiceTurnFrame> = emptyList(),
    val retainedBytes: ULong = 0uL,
    val localInputStatus: VoiceTurnLocalInputStatus = VoiceTurnLocalInputStatus.OPEN,
    val remoteGeneration: ULong = 0uL,
    val connectionAttempts: List<VoiceTurnConnectionAttempt> = emptyList(),
    val remoteAcknowledgedThrough: ULong? = null,
    val remotePhase: VoiceTurnRemotePhase = VoiceTurnRemotePhase.Disconnected,
    val cancellation: VoiceTurnCancellation? = null,
    val terminal: VoiceTurnTerminalOutcome? = null,
    val timeline: VoiceTurnTimeline = VoiceTurnTimeline(admission.admittedAt),
) {
    init {
        val identity = admission.identity
        val policy = admission.bufferPolicy
        require(frames.size <= policy.maximumFrameCount.toInt()) {
            "VoiceTurn durable frame count exceeds policy"
        }
        require(connectionAttempts.size <= policy.maximumRemoteGenerations.toInt()) {
            "VoiceTurn remote attempt count exceeds policy"
        }
        require(connectionAttempts.map { it.id }.distinct().size == connectionAttempts.size) {
            "VoiceTurn remote attempt IDs must be unique"
        }
        require(connectionAttempts.map { it.generation }.distinct().size == connectionAttempts.size) {
            "VoiceTurn remote generations must be unique"
        }
        require(connectionAttempts.all { it.generation in 1uL..remoteGeneration }) {
            "VoiceTurn attempt generation is outside durable state"
        }
        frames.forEachIndexed { index, frame ->
            require(frame.descriptor.sessionId == identity.sessionId) {
                "VoiceTurn frame session identity mismatch"
            }
            require(frame.descriptor.turnId == identity.turnId) {
                "VoiceTurn frame turn identity mismatch"
            }
            require(frame.descriptor.ordinal == policy.firstFrameOrdinal + index.toULong()) {
                "VoiceTurn durable frames must be contiguous and ordered"
            }
            require(frame.descriptor.payloadBytes <= policy.maximumFrameBytes) {
                "VoiceTurn durable frame exceeds byte policy"
            }
        }
        require(retainedBytes == frames.sumOf { it.descriptor.payloadBytes.toULong() }) {
            "VoiceTurn retained-byte accounting must equal durable frame metadata"
        }
        require(retainedBytes <= policy.maximumRetainedBytes) {
            "VoiceTurn retained bytes exceed policy"
        }
        require(
            remoteAcknowledgedThrough == null ||
                frames.any { it.descriptor.ordinal == remoteAcknowledgedThrough },
        ) { "Remote acknowledgement must name a locally durable frame" }
        require(cancellation == null || localInputStatus == VoiceTurnLocalInputStatus.CANCELLED) {
            "VoiceTurn cancellation must close local input"
        }
        require(terminal == null || remotePhase == VoiceTurnRemotePhase.Closed) {
            "Terminal VoiceTurn state must close the remote phase"
        }
        require(terminal?.correlationId in setOf(null, identity.correlationId, cancellation?.correlationId)) {
            "Terminal VoiceTurn correlation is not admission/cancellation bound"
        }
        require(timeline.admittedAt == admission.admittedAt) {
            "VoiceTurn timeline must preserve the admission mark"
        }
        require(timeline.terminalAt == terminal?.terminalAt) {
            "VoiceTurn timeline terminal mark must match the terminal outcome"
        }
    }
}

enum class VoiceTurnStatus {
    CAPTURING_LOCALLY,
    INPUT_ENDED,
    CONNECT_ATTEMPT_FENCED,
    REMOTE_READY,
    FRAME_ATTEMPT_FENCED,
    FINISH_ATTEMPT_FENCED,
    AWAITING_RESULT,
    REMOTE_RETRYABLE_FAILURE,
    REMOTE_OUTCOME_UNKNOWN,
    CANCELLING_REMOTE,
    COMPLETED,
    CANCELLED,
    CANCELLED_REMOTE_OUTCOME_UNKNOWN,
    FAILED,
}

data class VoiceTurnLocalRecordProjection(
    val durableFrameCount: UInt,
    val durableThrough: ULong?,
    val retainedBytes: ULong,
    val inputStatus: VoiceTurnLocalInputStatus,
)

data class VoiceTurnRemoteProjection(
    val generation: ULong,
    val acknowledgedThrough: ULong?,
    val activeAttemptId: VoiceTurnRemoteAttemptId?,
    val operation: VoiceTurnRemoteOperation?,
    val outcomeBoundary: VoiceTurnOutcomeBoundary?,
    val failure: ExpectedFailure?,
)

/** Safe shell/RPC projection: stable identity, correlation, counts, status, and opaque terminal refs. */
data class VoiceTurnProjection(
    val revision: ULong,
    val identity: VoiceTurnIdentity,
    val status: VoiceTurnStatus,
    val localRecord: VoiceTurnLocalRecordProjection,
    val remote: VoiceTurnRemoteProjection,
    val terminal: VoiceTurnTerminalOutcome?,
    val timeline: VoiceTurnTimeline,
)

internal fun VoiceTurnState.project(): VoiceTurnProjection {
    val remoteFailure = when (val phase = remotePhase) {
        is VoiceTurnRemotePhase.RetryableFailure -> phase.failure
        is VoiceTurnRemotePhase.OutcomeUnknown -> phase.unknown.failure
        else -> null
    }
    val operation = when (val phase = remotePhase) {
        is VoiceTurnRemotePhase.ConnectAttemptFenced -> VoiceTurnRemoteOperation.CONNECT
        is VoiceTurnRemotePhase.FrameAttemptFenced -> VoiceTurnRemoteOperation.SEND_FRAME
        is VoiceTurnRemotePhase.FinishAttemptFenced -> VoiceTurnRemoteOperation.FINISH_INPUT
        is VoiceTurnRemotePhase.AwaitingResult -> VoiceTurnRemoteOperation.AWAIT_PROVIDER_RESULT
        is VoiceTurnRemotePhase.RetryableFailure -> phase.operation
        is VoiceTurnRemotePhase.OutcomeUnknown -> phase.unknown.operation
        is VoiceTurnRemotePhase.CancelAttemptFenced -> VoiceTurnRemoteOperation.CANCEL
        else -> null
    }
    val boundary = when (val phase = remotePhase) {
        is VoiceTurnRemotePhase.RetryableFailure -> phase.boundary
        is VoiceTurnRemotePhase.OutcomeUnknown -> phase.unknown.boundary
        else -> null
    }
    val attemptId = when (val phase = remotePhase) {
        is VoiceTurnRemotePhase.ConnectAttemptFenced -> phase.attempt.id
        is VoiceTurnRemotePhase.Ready -> phase.attempt.id
        is VoiceTurnRemotePhase.FrameAttemptFenced -> phase.attempt.id
        is VoiceTurnRemotePhase.FinishAttemptFenced -> phase.attempt.id
        is VoiceTurnRemotePhase.AwaitingResult -> phase.attempt.id
        else -> connectionAttempts.lastOrNull()?.id
    }
    val status = when (val terminalOutcome = terminal) {
        is VoiceTurnTerminalOutcome.Completed -> VoiceTurnStatus.COMPLETED
        is VoiceTurnTerminalOutcome.Failed -> VoiceTurnStatus.FAILED
        is VoiceTurnTerminalOutcome.Cancelled -> if (
            terminalOutcome.remoteDisposition == VoiceTurnCancellationRemoteDisposition.OUTCOME_UNKNOWN
        ) {
            VoiceTurnStatus.CANCELLED_REMOTE_OUTCOME_UNKNOWN
        } else {
            VoiceTurnStatus.CANCELLED
        }

        null -> when (remotePhase) {
            VoiceTurnRemotePhase.Disconnected -> if (
                localInputStatus == VoiceTurnLocalInputStatus.ENDED
            ) {
                VoiceTurnStatus.INPUT_ENDED
            } else {
                VoiceTurnStatus.CAPTURING_LOCALLY
            }

            is VoiceTurnRemotePhase.ConnectAttemptFenced -> VoiceTurnStatus.CONNECT_ATTEMPT_FENCED
            is VoiceTurnRemotePhase.Ready -> if (
                localInputStatus == VoiceTurnLocalInputStatus.ENDED
            ) {
                VoiceTurnStatus.INPUT_ENDED
            } else {
                VoiceTurnStatus.REMOTE_READY
            }

            is VoiceTurnRemotePhase.FrameAttemptFenced -> VoiceTurnStatus.FRAME_ATTEMPT_FENCED
            is VoiceTurnRemotePhase.FinishAttemptFenced -> VoiceTurnStatus.FINISH_ATTEMPT_FENCED
            is VoiceTurnRemotePhase.AwaitingResult -> VoiceTurnStatus.AWAITING_RESULT
            is VoiceTurnRemotePhase.RetryableFailure -> VoiceTurnStatus.REMOTE_RETRYABLE_FAILURE
            is VoiceTurnRemotePhase.OutcomeUnknown -> VoiceTurnStatus.REMOTE_OUTCOME_UNKNOWN
            is VoiceTurnRemotePhase.CancelAttemptFenced -> VoiceTurnStatus.CANCELLING_REMOTE
            VoiceTurnRemotePhase.Closed -> error("Closed VoiceTurn without terminal outcome")
        }
    }
    return VoiceTurnProjection(
        revision = revision,
        identity = admission.identity,
        status = status,
        localRecord = VoiceTurnLocalRecordProjection(
            durableFrameCount = frames.size.toUInt(),
            durableThrough = frames.lastOrNull()?.descriptor?.ordinal,
            retainedBytes = retainedBytes,
            inputStatus = localInputStatus,
        ),
        remote = VoiceTurnRemoteProjection(
            generation = remoteGeneration,
            acknowledgedThrough = remoteAcknowledgedThrough,
            activeAttemptId = attemptId,
            operation = operation,
            outcomeBoundary = boundary,
            failure = remoteFailure,
        ),
        terminal = terminal,
        timeline = timeline,
    )
}

private fun requireVoiceTurnId(
    label: String,
    value: String,
    maximumLength: Int = 200,
) {
    require(value.isNotBlank()) { "$label cannot be blank" }
    require(value == value.trim()) { "$label cannot have surrounding whitespace" }
    require(value.length <= maximumLength) { "$label cannot exceed $maximumLength characters" }
    require(value.none(Char::isISOControl)) { "$label cannot contain control characters" }
}
