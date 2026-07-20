package dev.gumi.edge.runtime.realtime

import dev.gumi.edge.runtime.spool.DurablePayloadRef
import dev.gumi.edge.runtime.spool.Sha256Digest
import dev.gumi.edge.sdk.OpaqueBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class InMemoryVoiceTurnStateStore : VoiceTurnStateStore {
    enum class CommitFault {
        NONE,
        BEFORE_COMMIT,
        AFTER_COMMIT_RESPONSE_LOST,
    }

    private val mutex = Mutex()
    private val states = linkedMapOf<VoiceTurnId, VoiceTurnState>()
    var nextCommitFault: CommitFault = CommitFault.NONE
    var commitCount: Int = 0

    override suspend fun load(turnId: VoiceTurnId): VoiceTurnStoreLoadResult = mutex.withLock {
        states[turnId]?.let(VoiceTurnStoreLoadResult::Loaded) ?: VoiceTurnStoreLoadResult.Missing
    }

    override suspend fun commit(
        expectedRevision: ULong?,
        next: VoiceTurnState,
    ): VoiceTurnStoreCommitResult = mutex.withLock {
        val identity = next.admission.identity
        states.values.firstOrNull { existing ->
            existing.admission.identity.turnId != identity.turnId &&
                (
                    existing.admission.identity.sessionId == identity.sessionId ||
                        existing.admission.identity.admissionLeaseId == identity.admissionLeaseId ||
                        existing.admission.identity.startCommandId == identity.startCommandId
                    )
        }?.let { conflicting ->
            val code = when {
                conflicting.admission.identity.sessionId == identity.sessionId ->
                    "VOICE_TURN_SESSION_ALREADY_BOUND"

                conflicting.admission.identity.admissionLeaseId == identity.admissionLeaseId ->
                    "VOICE_TURN_ADMISSION_ALREADY_BOUND"

                else -> "VOICE_TURN_START_COMMAND_ALREADY_BOUND"
            }
            return@withLock VoiceTurnStoreCommitResult.IdentityConflict(code)
        }
        val current = states[identity.turnId]
        if (current?.revision != expectedRevision) {
            return@withLock VoiceTurnStoreCommitResult.RevisionMismatch(current?.revision)
        }
        require(
            if (expectedRevision == null) {
                next.revision == 0uL
            } else {
                next.revision == expectedRevision + 1uL
            },
        ) { "Test store accepts exact next-revision commits only" }
        commitCount += 1
        when (nextCommitFault.also { nextCommitFault = CommitFault.NONE }) {
            CommitFault.NONE -> {
                states[identity.turnId] = next
                VoiceTurnStoreCommitResult.Committed
            }

            CommitFault.BEFORE_COMMIT -> VoiceTurnStoreCommitResult.Unavailable(
                VoiceTurnStoreFailure("TEST_VOICE_TURN_STORE_UNAVAILABLE", retryable = true),
            )

            CommitFault.AFTER_COMMIT_RESPONSE_LOST -> {
                states[identity.turnId] = next
                VoiceTurnStoreCommitResult.OutcomeUnknown
            }
        }
    }

    suspend fun state(turnId: VoiceTurnId): VoiceTurnState? = mutex.withLock { states[turnId] }
}

internal class InMemoryVoiceTurnFrameStore : VoiceTurnFrameStore {
    private data class Stored(
        val frame: DurableVoiceTurnFrame,
        val payload: OpaqueBytes,
    )

    private val stored = linkedMapOf<Pair<VoiceTurnId, ULong>, Stored>()
    var writeCount: Int = 0
    var readCount: Int = 0
    var nextWriteFailure: VoiceTurnStoreFailure? = null
    var nextReadFailure: VoiceTurnStoreFailure? = null

    override suspend fun writeAndFlush(
        descriptor: VoiceTurnFrameDescriptor,
        payload: OpaqueBytes,
    ): VoiceTurnFrameWriteResult {
        writeCount += 1
        nextWriteFailure?.also {
            nextWriteFailure = null
            return VoiceTurnFrameWriteResult.Unavailable(it)
        }
        val digest = testDigest(payload)
        val frame = DurableVoiceTurnFrame(
            descriptor = descriptor,
            payloadRef = DurablePayloadRef(
                "test-encrypted-voice-turn/${descriptor.turnId.value}/${descriptor.ordinal}",
            ),
            contentDigest = digest,
        )
        val key = descriptor.turnId to descriptor.ordinal
        val prior = stored[key]
        if (prior != null && (prior.frame != frame || prior.payload != payload)) {
            return VoiceTurnFrameWriteResult.Unavailable(
                VoiceTurnStoreFailure("TEST_VOICE_TURN_FRAME_IDENTITY_CONFLICT", false),
            )
        }
        stored[key] = Stored(frame, payload)
        return VoiceTurnFrameWriteResult.Stored(frame)
    }

    override suspend fun readAndVerify(frame: DurableVoiceTurnFrame): VoiceTurnFrameReadResult {
        readCount += 1
        nextReadFailure?.also {
            nextReadFailure = null
            return VoiceTurnFrameReadResult.Unavailable(it)
        }
        val storedFrame = stored[frame.descriptor.turnId to frame.descriptor.ordinal]
            ?: return VoiceTurnFrameReadResult.Unavailable(
                VoiceTurnStoreFailure("TEST_VOICE_TURN_FRAME_MISSING", false),
            )
        if (storedFrame.frame != frame || testDigest(storedFrame.payload) != frame.contentDigest) {
            return VoiceTurnFrameReadResult.Unavailable(
                VoiceTurnStoreFailure("TEST_VOICE_TURN_FRAME_CORRUPT", false),
            )
        }
        return VoiceTurnFrameReadResult.Verified(storedFrame.payload)
    }

    private fun testDigest(payload: OpaqueBytes): Sha256Digest {
        var accumulator = 0uL
        payload.copyBytes().forEachIndexed { index, byte ->
            accumulator = (accumulator * 257uL) xor (byte.toUByte().toULong() + index.toULong())
        }
        val block = accumulator.toString(16).padStart(16, '0')
        return Sha256Digest("sha256:${block.repeat(4)}")
    }
}

internal class ScriptedVoiceTurnRealtimePort : VoiceTurnRealtimePort {
    val connectRequests = mutableListOf<VoiceTurnRemoteConnectRequest>()
    val frameRequests = mutableListOf<VoiceTurnRemoteFrameRequest>()
    val framePayloads = mutableListOf<OpaqueBytes>()
    val finishRequests = mutableListOf<VoiceTurnRemoteFinishRequest>()
    val cancelRequests = mutableListOf<VoiceTurnRemoteCancelRequest>()

    var connectHandler: suspend (VoiceTurnRemoteConnectRequest) -> VoiceTurnRemoteConnectResult =
        { request ->
            VoiceTurnRemoteConnectResult.Ready(
                remoteSessionRef = VoiceTurnRemoteSessionRef(
                    "remote-${request.attempt.generation}",
                ),
                resumeAck = VoiceTurnRemoteResumeAck(
                    sessionId = request.identity.sessionId,
                    turnId = request.identity.turnId,
                    generation = request.attempt.generation,
                    acceptedThroughOrdinal = request.resumeAfterOrdinal,
                ),
            )
        }

    var frameHandler: suspend (
        VoiceTurnRemoteFrameRequest,
        OpaqueBytes,
    ) -> VoiceTurnRemoteFrameResult = { request, _ ->
        VoiceTurnRemoteFrameResult.Accepted(
            VoiceTurnRemoteFrameAck(
                sessionId = request.identity.sessionId,
                turnId = request.identity.turnId,
                generation = request.generation,
                ordinal = request.descriptor.ordinal,
                contentDigest = request.contentDigest,
            ),
        )
    }

    var finishHandler: suspend (VoiceTurnRemoteFinishRequest) -> VoiceTurnRemoteFinishResult =
        { VoiceTurnRemoteFinishResult.AwaitingResult }

    var cancelHandler: suspend (VoiceTurnRemoteCancelRequest) -> VoiceTurnRemoteCancelResult =
        { VoiceTurnRemoteCancelResult.Confirmed }

    override suspend fun connect(
        request: VoiceTurnRemoteConnectRequest,
    ): VoiceTurnRemoteConnectResult {
        connectRequests += request
        return connectHandler(request)
    }

    override suspend fun sendFrame(
        request: VoiceTurnRemoteFrameRequest,
        payload: OpaqueBytes,
    ): VoiceTurnRemoteFrameResult {
        frameRequests += request
        framePayloads += payload
        return frameHandler(request, payload)
    }

    override suspend fun finish(
        request: VoiceTurnRemoteFinishRequest,
    ): VoiceTurnRemoteFinishResult {
        finishRequests += request
        return finishHandler(request)
    }

    override suspend fun cancel(
        request: VoiceTurnRemoteCancelRequest,
    ): VoiceTurnRemoteCancelResult {
        cancelRequests += request
        return cancelHandler(request)
    }
}

internal class IncrementingVoiceTurnClock(
    private val bootId: String = "test-boot",
    startMillis: Long = 1_000L,
) : VoiceTurnRuntimeClock {
    private var next = startMillis

    override fun now(): VoiceTurnClockMark = VoiceTurnClockMark(bootId, next++)
}
