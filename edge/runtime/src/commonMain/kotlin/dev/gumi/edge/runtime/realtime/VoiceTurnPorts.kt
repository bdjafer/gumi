package dev.gumi.edge.runtime.realtime

import dev.gumi.edge.runtime.spool.Sha256Digest
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.OpaqueBytes

/** Host adapter for deterministic, boot-scoped monotonic stage marks. */
fun interface VoiceTurnRuntimeClock {
    fun now(): VoiceTurnClockMark
}

data class VoiceTurnStoreFailure(
    val code: String,
    val retryable: Boolean,
) {
    init {
        require(code.matches(Regex("[A-Z][A-Z0-9_]{1,63}"))) {
            "VoiceTurn store failure code must be a stable uppercase identifier"
        }
    }
}

sealed interface VoiceTurnStoreLoadResult {
    data object Missing : VoiceTurnStoreLoadResult

    data class Loaded(val state: VoiceTurnState) : VoiceTurnStoreLoadResult

    data class Unavailable(val failure: VoiceTurnStoreFailure) : VoiceTurnStoreLoadResult
}

sealed interface VoiceTurnStoreCommitResult {
    data object Committed : VoiceTurnStoreCommitResult

    data class RevisionMismatch(val actualRevision: ULong?) : VoiceTurnStoreCommitResult

    /** A unique session, admission lease, or start command is already bound to another turn. */
    data class IdentityConflict(val code: String) : VoiceTurnStoreCommitResult {
        init {
            require(code.matches(Regex("[A-Z][A-Z0-9_]{1,63}")))
        }
    }

    data class Unavailable(val failure: VoiceTurnStoreFailure) : VoiceTurnStoreCommitResult

    /** The write may have committed; callers must reload and reconcile exact immutable identity. */
    data object OutcomeUnknown : VoiceTurnStoreCommitResult
}

/**
 * Durable CAS boundary for one VoiceTurn record.
 *
 * A production adapter must atomically enforce uniqueness of turn ID, session ID, admission lease
 * ID, and start command ID. For creation [expectedRevision] is null and [next.revision] is zero.
 * Every update increments the exact prior revision by one. The stored state must be encrypted or
 * otherwise protected according to the host's local-data policy.
 */
interface VoiceTurnStateStore {
    suspend fun load(turnId: VoiceTurnId): VoiceTurnStoreLoadResult

    suspend fun commit(
        expectedRevision: ULong?,
        next: VoiceTurnState,
    ): VoiceTurnStoreCommitResult
}

sealed interface VoiceTurnFrameWriteResult {
    data class Stored(val frame: DurableVoiceTurnFrame) : VoiceTurnFrameWriteResult

    data class Unavailable(val failure: VoiceTurnStoreFailure) : VoiceTurnFrameWriteResult
}

sealed interface VoiceTurnFrameReadResult {
    data class Verified(val payload: OpaqueBytes) : VoiceTurnFrameReadResult

    data class Unavailable(val failure: VoiceTurnStoreFailure) : VoiceTurnFrameReadResult
}

/**
 * Trusted local encrypted-byte boundary. A stable descriptor retry must return the same durable ref
 * and digest or fail identity/integrity validation. Returning Stored means bytes were durably
 * flushed; metadata still has to cross [VoiceTurnStateStore] before the frame is admitted.
 */
interface VoiceTurnFrameStore {
    suspend fun writeAndFlush(
        descriptor: VoiceTurnFrameDescriptor,
        payload: OpaqueBytes,
    ): VoiceTurnFrameWriteResult

    suspend fun readAndVerify(frame: DurableVoiceTurnFrame): VoiceTurnFrameReadResult
}

data class VoiceTurnRemoteConnectRequest(
    val identity: VoiceTurnIdentity,
    val attempt: VoiceTurnConnectionAttempt,
    /** Last contiguous frame the edge has already accepted as remotely consumed. */
    val resumeAfterOrdinal: ULong?,
    val localInputEnded: Boolean,
)

data class VoiceTurnRemoteResumeAck(
    val sessionId: VoiceTurnSessionId,
    val turnId: VoiceTurnId,
    val generation: ULong,
    /** Null means no frame has been remotely accepted. */
    val acceptedThroughOrdinal: ULong?,
)

sealed interface VoiceTurnRemoteConnectResult {
    data class Ready(
        val remoteSessionRef: VoiceTurnRemoteSessionRef,
        val resumeAck: VoiceTurnRemoteResumeAck,
    ) : VoiceTurnRemoteConnectResult

    /** The adapter proves no external acceptance boundary was crossed. */
    data class NotAttempted(val failure: ExpectedFailure) : VoiceTurnRemoteConnectResult

    /** A definitive provider/remote refusal. */
    data class Rejected(
        val boundary: VoiceTurnOutcomeBoundary,
        val failure: ExpectedFailure,
    ) : VoiceTurnRemoteConnectResult

    /** The adapter cannot prove whether the remote session was established. */
    data class OutcomeUnknown(
        val boundary: VoiceTurnOutcomeBoundary,
        val failure: ExpectedFailure,
    ) : VoiceTurnRemoteConnectResult
}

data class VoiceTurnRemoteFrameRequest(
    val identity: VoiceTurnIdentity,
    val generation: ULong,
    val remoteSessionRef: VoiceTurnRemoteSessionRef,
    val descriptor: VoiceTurnFrameDescriptor,
    val contentDigest: Sha256Digest,
)

data class VoiceTurnRemoteFrameAck(
    val sessionId: VoiceTurnSessionId,
    val turnId: VoiceTurnId,
    val generation: ULong,
    val ordinal: ULong,
    val contentDigest: Sha256Digest,
)

sealed interface VoiceTurnRemoteFrameResult {
    data class Accepted(val ack: VoiceTurnRemoteFrameAck) : VoiceTurnRemoteFrameResult

    data class NotAttempted(val failure: ExpectedFailure) : VoiceTurnRemoteFrameResult

    data class Rejected(
        val boundary: VoiceTurnOutcomeBoundary,
        val failure: ExpectedFailure,
    ) : VoiceTurnRemoteFrameResult

    data class OutcomeUnknown(
        val boundary: VoiceTurnOutcomeBoundary,
        val failure: ExpectedFailure,
    ) : VoiceTurnRemoteFrameResult
}

data class VoiceTurnRemoteFinishRequest(
    val identity: VoiceTurnIdentity,
    val generation: ULong,
    val remoteSessionRef: VoiceTurnRemoteSessionRef,
    val terminalFrameOrdinal: ULong?,
)

sealed interface VoiceTurnRemoteFinishResult {
    /** End-of-input was accepted; the async terminal result is now expected. */
    data object AwaitingResult : VoiceTurnRemoteFinishResult

    data class NotAttempted(val failure: ExpectedFailure) : VoiceTurnRemoteFinishResult

    data class Rejected(
        val boundary: VoiceTurnOutcomeBoundary,
        val failure: ExpectedFailure,
    ) : VoiceTurnRemoteFinishResult

    data class OutcomeUnknown(
        val boundary: VoiceTurnOutcomeBoundary,
        val failure: ExpectedFailure,
    ) : VoiceTurnRemoteFinishResult
}

data class VoiceTurnRemoteCancelRequest(
    val identity: VoiceTurnIdentity,
    val generation: ULong,
    val remoteSessionRef: VoiceTurnRemoteSessionRef?,
    /** Stable existing shell stop-command identity; adapters use it as cancellation idempotency. */
    val cancellation: VoiceTurnCancellation,
)

sealed interface VoiceTurnRemoteCancelResult {
    data object Confirmed : VoiceTurnRemoteCancelResult

    /** The adapter proves no remote session/effect existed, so no cancellation was needed. */
    data object NotNeeded : VoiceTurnRemoteCancelResult

    data class Rejected(val failure: ExpectedFailure) : VoiceTurnRemoteCancelResult

    data class OutcomeUnknown(
        val boundary: VoiceTurnOutcomeBoundary,
        val failure: ExpectedFailure,
    ) : VoiceTurnRemoteCancelResult
}

sealed interface VoiceTurnRemoteEvent {
    val generation: ULong

    data class TerminalResult(
        override val generation: ULong,
        val result: VoiceTurnRemoteTerminalResult,
    ) : VoiceTurnRemoteEvent

    /** Normalized provider failure; terminal=false permits a stable reconnect of the same turn. */
    data class ProviderFailure(
        override val generation: ULong,
        val failure: ExpectedFailure,
        val terminal: Boolean,
        val observedAt: VoiceTurnClockMark,
    ) : VoiceTurnRemoteEvent

    data class Disconnected(
        override val generation: ULong,
        val boundary: VoiceTurnOutcomeBoundary,
        val outcomeUnknown: Boolean,
        val failure: ExpectedFailure,
        val observedAt: VoiceTurnClockMark,
    ) : VoiceTurnRemoteEvent

    data class CancellationConfirmed(
        override val generation: ULong,
        val cancellationCommandId: dev.gumi.edge.sdk.CommandId,
        val observedAt: VoiceTurnClockMark,
    ) : VoiceTurnRemoteEvent
}

/**
 * Provider-neutral realtime effect port.
 *
 * Implementations must use session/turn/frame/cancellation identities as idempotency and resume
 * keys, normalize provider data into the result types above, and never return credentials or raw
 * provider bodies. Each method makes at most one external attempt. Async results enter through
 * [VoiceTurnCoordinator.applyRemoteEvent] and are fenced by generation.
 */
interface VoiceTurnRealtimePort {
    suspend fun connect(request: VoiceTurnRemoteConnectRequest): VoiceTurnRemoteConnectResult

    suspend fun sendFrame(
        request: VoiceTurnRemoteFrameRequest,
        payload: OpaqueBytes,
    ): VoiceTurnRemoteFrameResult

    suspend fun finish(request: VoiceTurnRemoteFinishRequest): VoiceTurnRemoteFinishResult

    suspend fun cancel(request: VoiceTurnRemoteCancelRequest): VoiceTurnRemoteCancelResult
}
