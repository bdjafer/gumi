package dev.gumi.edge.runtime.spool

import dev.gumi.edge.sdk.OpaqueBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SpoolRejection {
    data class CaptureNotFound(val captureSessionId: CaptureSessionId) : SpoolRejection

    data class StreamNotFound(
        val captureSessionId: CaptureSessionId,
        val streamId: StreamId,
    ) : SpoolRejection

    data class IdentityConflict(val code: String) : SpoolRejection

    data class DescriptorMismatch(val field: String) : SpoolRejection

    data class PayloadLengthMismatch(
        val declaredBytes: ULong,
        val receivedBytes: ULong,
    ) : SpoolRejection

    data class SequenceOutsidePolicy(
        val requested: SequenceRange,
        val allowed: SequenceRange,
    ) : SpoolRejection

    data class SequenceOverlap(
        val requested: SequenceRange,
        val existingChunkId: ChunkId,
        val existing: SequenceRange,
    ) : SpoolRejection

    data class QuotaExceeded(
        val retainedBytes: ULong,
        val requestedBytes: ULong,
        val maximumRetainedBytes: ULong,
        val pressure: SpoolPressure,
    ) : SpoolRejection

    data class TerminalRangeConflict(val code: String) : SpoolRejection

    data class CloudAckMismatch(val field: String) : SpoolRejection

    data class IngestSessionNotBound(val captureSessionId: CaptureSessionId) : SpoolRejection

    data object StoreRevisionExhausted : SpoolRejection
}

sealed interface SpoolResult<out T> {
    data class Applied<T>(
        val value: T,
        val storeRevision: ULong,
    ) : SpoolResult<T>

    data class Duplicate<T>(
        val value: T,
        val storeRevision: ULong,
    ) : SpoolResult<T>

    data class Rejected(val rejection: SpoolRejection) : SpoolResult<Nothing>

    data class Unavailable(val failure: SpoolStoreFailure) : SpoolResult<Nothing>
}

/**
 * Portable state owner for local durability, source advancement, and remote acknowledgement facts.
 *
 * [persistChunk] accepts opaque raw media and crosses the injected [DurablePayloadStore] boundary
 * itself. The absolute [SourceAdvancePermit] is emitted only after the resulting payload reference,
 * chunk record, and recomputed checkpoint have crossed [SpoolStore]'s atomic commit boundary.
 */
class SpoolCoordinator(
    private val store: SpoolStore,
    private val payloadStore: DurablePayloadStore,
) {
    private val mutex = Mutex()

    suspend fun registerStream(descriptor: StreamDescriptor): SpoolResult<Unit> = mutate { state ->
        val capture = state.captures[descriptor.captureSessionId]
        val existing = capture?.streams?.get(descriptor.streamId)
        when {
            existing?.descriptor == descriptor -> MutationPlan.Done(
                SpoolResult.Duplicate(Unit, state.storeRevision),
            )

            existing != null -> MutationPlan.Done(
                SpoolResult.Rejected(
                    SpoolRejection.IdentityConflict("STREAM_ID_REUSED_WITH_DIFFERENT_DESCRIPTOR"),
                ),
            )

            state.storeRevision == ULong.MAX_VALUE -> revisionExhausted()
            else -> {
                val nextCapture = (capture ?: CaptureSpoolState(descriptor.captureSessionId)).copy(
                    streams = (capture?.streams ?: emptyMap()) + (
                        descriptor.streamId to StreamSpoolState(descriptor)
                    ),
                )
                MutationPlan.Write(
                    next = state.copy(
                        storeRevision = state.storeRevision + 1uL,
                        captures = state.captures + (descriptor.captureSessionId to nextCapture),
                    ),
                    value = Unit,
                )
            }
        }
    }

    /**
     * Crosses the payload durability boundary before metadata can advance source truth.
     *
     * A failed metadata commit may leave an unreferenced encrypted payload, but no source-advance
     * permit can escape until the payload reference and checkpoint commit atomically in [SpoolStore].
     */
    suspend fun persistChunk(
        descriptor: ChunkDescriptor,
        payload: OpaqueBytes,
    ): SpoolResult<ChunkPersistenceReceipt> {
        val receivedBytes = payload.size.toULong()
        if (receivedBytes != descriptor.payloadBytes) {
            return SpoolResult.Rejected(
                SpoolRejection.PayloadLengthMismatch(descriptor.payloadBytes, receivedBytes),
            )
        }
        preflightPayloadWrite(descriptor)?.let { return it }
        val durable = when (val written = payloadStore.writeAndFlush(descriptor, payload)) {
            is DurablePayloadWriteResult.Stored -> DurableChunk(descriptor, written.payloadRef)
            is DurablePayloadWriteResult.Unavailable -> return SpoolResult.Unavailable(written.failure)
        }
        return mutate { state -> planChunkPersistence(state, durable) }
    }

    /** Refuses unknown or already-impossible writes before they can consume encrypted blob space. */
    private suspend fun preflightPayloadWrite(
        descriptor: ChunkDescriptor,
    ): SpoolResult<Nothing>? = mutex.withLock {
        val state = when (val loaded = store.load()) {
            is SpoolStoreLoadResult.Loaded -> loaded.state
            is SpoolStoreLoadResult.Unavailable -> return@withLock SpoolResult.Unavailable(
                loaded.failure,
            )
        }
        val capture = state.captures[descriptor.captureSessionId]
            ?: return@withLock SpoolResult.Rejected(
                SpoolRejection.CaptureNotFound(descriptor.captureSessionId),
            )
        val stream = capture.streams[descriptor.streamId]
            ?: return@withLock SpoolResult.Rejected(
                SpoolRejection.StreamNotFound(descriptor.captureSessionId, descriptor.streamId),
            )
        stream.chunks[descriptor.chunkId]?.let { existing ->
            if (existing.chunk.descriptor != descriptor) {
                return@withLock SpoolResult.Rejected(
                    SpoolRejection.IdentityConflict("CHUNK_ID_REUSED_WITH_DIFFERENT_CONTENT_OR_METADATA"),
                )
            }
            return@withLock null // The payload adapter still verifies the retry's bytes and digest.
        }
        descriptorMismatch(stream.descriptor, descriptor)?.let {
            return@withLock SpoolResult.Rejected(it)
        }
        if (!stream.descriptor.sequencePolicy.allowedRange.contains(descriptor.sequenceRange)) {
            return@withLock SpoolResult.Rejected(
                SpoolRejection.SequenceOutsidePolicy(
                    requested = descriptor.sequenceRange,
                    allowed = stream.descriptor.sequencePolicy.allowedRange,
                ),
            )
        }
        if (stream.terminalRange?.contains(descriptor.sequenceRange) == false) {
            return@withLock SpoolResult.Rejected(
                SpoolRejection.TerminalRangeConflict("CHUNK_BEYOND_TERMINAL_RANGE"),
            )
        }
        stream.chunks.values.firstOrNull {
            it.chunk.descriptor.sequenceRange.overlaps(descriptor.sequenceRange)
        }?.let { overlap ->
            return@withLock SpoolResult.Rejected(
                SpoolRejection.SequenceOverlap(
                    requested = descriptor.sequenceRange,
                    existingChunkId = overlap.chunk.descriptor.chunkId,
                    existing = overlap.chunk.descriptor.sequenceRange,
                ),
            )
        }
        if (descriptor.payloadBytes > state.quota.maximumRetainedBytes - state.retainedBytes) {
            return@withLock SpoolResult.Rejected(
                SpoolRejection.QuotaExceeded(
                    retainedBytes = state.retainedBytes,
                    requestedBytes = descriptor.payloadBytes,
                    maximumRetainedBytes = state.quota.maximumRetainedBytes,
                    pressure = state.pressure,
                ),
            )
        }
        val streamBytes = stream.chunks.values.fold(0uL) { total, record ->
            checkedAdd(total, record.chunk.descriptor.payloadBytes)
        }
        if (descriptor.payloadBytes > stream.descriptor.maxTotalBytes - streamBytes) {
            return@withLock SpoolResult.Rejected(
                SpoolRejection.DescriptorMismatch("maxTotalBytes"),
            )
        }
        if (state.storeRevision == ULong.MAX_VALUE) {
            return@withLock SpoolResult.Rejected(SpoolRejection.StoreRevisionExhausted)
        }
        null
    }

    suspend fun declareTerminalRange(
        captureSessionId: CaptureSessionId,
        streamId: StreamId,
        terminalRange: SequenceRange,
    ): SpoolResult<FinalizationReadiness> = mutate { state ->
        val capture = state.captures[captureSessionId]
            ?: return@mutate missingCapture(captureSessionId)
        val stream = capture.streams[streamId]
            ?: return@mutate missingStream(captureSessionId, streamId)

        when {
            stream.terminalRange == terminalRange -> MutationPlan.Done(
                SpoolResult.Duplicate(finalizationReadiness(stream), state.storeRevision),
            )

            stream.terminalRange != null -> MutationPlan.Done(
                SpoolResult.Rejected(
                    SpoolRejection.TerminalRangeConflict("TERMINAL_RANGE_ALREADY_DECLARED"),
                ),
            )

            terminalRange.first != stream.descriptor.sequencePolicy.first ||
                !stream.descriptor.sequencePolicy.allowedRange.contains(terminalRange) ->
                MutationPlan.Done(
                    SpoolResult.Rejected(
                        SpoolRejection.TerminalRangeConflict("TERMINAL_RANGE_OUTSIDE_POLICY"),
                    ),
                )

            stream.chunks.values.any {
                !terminalRange.contains(it.chunk.descriptor.sequenceRange)
            } -> MutationPlan.Done(
                SpoolResult.Rejected(
                    SpoolRejection.TerminalRangeConflict("DURABLE_CHUNK_BEYOND_TERMINAL_RANGE"),
                ),
            )

            state.storeRevision == ULong.MAX_VALUE -> revisionExhausted()
            else -> {
                val nextStream = stream.copy(terminalRange = terminalRange)
                val next = replaceStream(state, capture, nextStream)
                MutationPlan.Write(
                    next = next.copy(storeRevision = state.storeRevision + 1uL),
                    value = finalizationReadiness(nextStream),
                )
            }
        }
    }

    /** Binds a local capture to exactly one remote ingest identity before any upload can start. */
    suspend fun bindIngestSession(
        captureSessionId: CaptureSessionId,
        ingestSessionId: IngestSessionId,
    ): SpoolResult<Unit> = mutate { state ->
        val capture = state.captures[captureSessionId]
            ?: return@mutate missingCapture(captureSessionId)
        when (capture.remoteIngest?.ingestSessionId) {
            ingestSessionId -> MutationPlan.Done(
                SpoolResult.Duplicate(Unit, state.storeRevision),
            )

            null -> {
                if (state.storeRevision == ULong.MAX_VALUE) return@mutate revisionExhausted()
                val nextCapture = capture.copy(
                    remoteIngest = RemoteIngestState(ingestSessionId, stateRevision = 0uL),
                )
                MutationPlan.Write(
                    next = state.copy(
                        storeRevision = state.storeRevision + 1uL,
                        captures = state.captures + (captureSessionId to nextCapture),
                    ),
                    value = Unit,
                )
            }

            else -> MutationPlan.Done(
                SpoolResult.Rejected(
                    SpoolRejection.IdentityConflict(
                        "CAPTURE_ALREADY_BOUND_TO_DIFFERENT_INGEST_SESSION",
                    ),
                ),
            )
        }
    }

    /**
     * Selects one eligible chunk by stable stream/range/chunk ordering. Fenced chunks are skipped;
     * if no eligible work remains their exact durable fences are returned for reconciliation.
     */
    internal suspend fun selectNextUpload(
        captureSessionId: CaptureSessionId,
    ): SpoolResult<PendingUploadSelection> = mutex.withLock {
        val state = when (val loaded = store.load()) {
            is SpoolStoreLoadResult.Loaded -> loaded.state
            is SpoolStoreLoadResult.Unavailable -> return@withLock SpoolResult.Unavailable(
                loaded.failure,
            )
        }
        val capture = state.captures[captureSessionId]
            ?: return@withLock SpoolResult.Rejected(
                SpoolRejection.CaptureNotFound(captureSessionId),
            )
        val remote = capture.remoteIngest
            ?: return@withLock SpoolResult.Rejected(
                SpoolRejection.IngestSessionNotBound(captureSessionId),
            )
        val ordered = capture.streams.values
            .sortedBy { it.descriptor.streamId.value }
            .flatMap { stream ->
                stream.chunks.values
                    .sortedWith(UPLOAD_RECORD_ORDER)
                    .map { stream.descriptor.streamId to it }
            }
            .filter { (_, record) -> record.cloudAck == null }
        val ready = ordered.firstOrNull { (_, record) ->
            record.uploadAttempt == null ||
                record.uploadAttempt.disposition == UploadAttemptDisposition.RETRY_AUTHORIZED
        }
        val selection = if (ready != null) {
            PendingUploadSelection.Ready(
                PendingChunkUpload(
                    ingestSessionId = remote.ingestSessionId,
                    chunk = ready.second.chunk,
                ),
            )
        } else {
            val blocked = ordered.mapNotNull { (streamId, record) ->
                record.uploadAttempt
                    ?.takeUnless { it.disposition == UploadAttemptDisposition.RETRY_AUTHORIZED }
                    ?.let { attempt ->
                    BlockedChunkUpload(
                        captureSessionId = captureSessionId,
                        streamId = streamId,
                        chunkId = record.chunk.descriptor.chunkId,
                        attempt = attempt,
                    )
                }
            }
            if (blocked.isEmpty()) PendingUploadSelection.Empty
            else PendingUploadSelection.Blocked(blocked)
        }
        SpoolResult.Applied(selection, state.storeRevision)
    }

    /** Writes the conservative outcome-unknown fence before an adapter may send bytes. */
    internal suspend fun beginUploadAttempt(
        expectation: CloudAckExpectation,
    ): SpoolResult<Unit> = mutate { state ->
        planBeginUploadAttempt(state, expectation)
    }

    /** Records a definitive publisher refusal without making the chunk automatically retryable. */
    internal suspend fun markUploadRejected(
        expectation: CloudAckExpectation,
    ): SpoolResult<Unit> = mutate { state ->
        planUploadAttemptDisposition(state, expectation, UploadAttemptDisposition.REJECTED)
    }

    /**
     * Safe only when the publisher port attests that no external request was attempted. The exact
     * descriptor binding remains retained so a later adapter version cannot silently change it.
     */
    internal suspend fun cancelUnattemptedUpload(
        expectation: CloudAckExpectation,
    ): SpoolResult<Unit> = mutate { state ->
        planUploadAttemptDisposition(state, expectation, UploadAttemptDisposition.RETRY_AUTHORIZED)
    }

    /**
     * Deliberate reconciliation boundary. Calling this acknowledges the duplicate-upload risk and
     * makes one exact previously fenced chunk selectable again without erasing its binding.
     */
    suspend fun authorizeUploadRetry(
        expectation: CloudAckExpectation,
    ): SpoolResult<Unit> = mutate { state ->
        planUploadAttemptDisposition(state, expectation, UploadAttemptDisposition.RETRY_AUTHORIZED)
    }

    suspend fun applyDurableCloudAck(
        expectation: CloudAckExpectation,
        ack: DurableCloudAck,
    ): SpoolResult<FinalizationReadiness> = mutate { state ->
        planCloudAck(state, expectation, ack)
    }

    /** Reloads durable truth after process death and returns idempotent absolute source cursors. */
    suspend fun recover(): SpoolResult<SpoolRecovery> = mutex.withLock {
        when (val loaded = store.load()) {
            is SpoolStoreLoadResult.Unavailable -> SpoolResult.Unavailable(loaded.failure)
            is SpoolStoreLoadResult.Loaded -> SpoolResult.Applied(
                value = loaded.state.toRecovery(),
                storeRevision = loaded.state.storeRevision,
            )
        }
    }

    private fun planChunkPersistence(
        state: SpoolState,
        chunk: DurableChunk,
    ): MutationPlan<ChunkPersistenceReceipt> {
        val descriptor = chunk.descriptor
        val capture = state.captures[descriptor.captureSessionId]
            ?: return missingCapture(descriptor.captureSessionId)
        val stream = capture.streams[descriptor.streamId]
            ?: return missingStream(descriptor.captureSessionId, descriptor.streamId)

        val existing = stream.chunks[descriptor.chunkId]
        if (existing != null) {
            return if (existing.chunk == chunk) {
                MutationPlan.Done(
                    SpoolResult.Duplicate(
                        ChunkPersistenceReceipt(
                            sourceAdvancePermit = stream.sourceAdvancePermit(),
                            capacity = state.capacity,
                        ),
                        state.storeRevision,
                    ),
                )
            } else {
                MutationPlan.Done(
                    SpoolResult.Rejected(
                        SpoolRejection.IdentityConflict("CHUNK_ID_REUSED_WITH_DIFFERENT_CONTENT_OR_METADATA"),
                    ),
                )
            }
        }

        descriptorMismatch(stream.descriptor, descriptor)?.let { mismatch ->
            return MutationPlan.Done(SpoolResult.Rejected(mismatch))
        }
        if (!stream.descriptor.sequencePolicy.allowedRange.contains(descriptor.sequenceRange)) {
            return MutationPlan.Done(
                SpoolResult.Rejected(
                    SpoolRejection.SequenceOutsidePolicy(
                        requested = descriptor.sequenceRange,
                        allowed = stream.descriptor.sequencePolicy.allowedRange,
                    ),
                ),
            )
        }
        stream.terminalRange?.let { terminal ->
            if (!terminal.contains(descriptor.sequenceRange)) {
                return MutationPlan.Done(
                    SpoolResult.Rejected(
                        SpoolRejection.TerminalRangeConflict("CHUNK_BEYOND_TERMINAL_RANGE"),
                    ),
                )
            }
        }
        stream.chunks.values.firstOrNull {
            it.chunk.descriptor.sequenceRange.overlaps(descriptor.sequenceRange)
        }?.let { overlap ->
            return MutationPlan.Done(
                SpoolResult.Rejected(
                    SpoolRejection.SequenceOverlap(
                        requested = descriptor.sequenceRange,
                        existingChunkId = overlap.chunk.descriptor.chunkId,
                        existing = overlap.chunk.descriptor.sequenceRange,
                    ),
                ),
            )
        }
        if (descriptor.payloadBytes > state.quota.maximumRetainedBytes - state.retainedBytes) {
            return MutationPlan.Done(
                SpoolResult.Rejected(
                    SpoolRejection.QuotaExceeded(
                        retainedBytes = state.retainedBytes,
                        requestedBytes = descriptor.payloadBytes,
                        maximumRetainedBytes = state.quota.maximumRetainedBytes,
                        pressure = state.pressure,
                    ),
                ),
            )
        }
        val streamBytes = stream.chunks.values.fold(0uL) { total, record ->
            checkedAdd(total, record.chunk.descriptor.payloadBytes)
        }
        if (descriptor.payloadBytes > stream.descriptor.maxTotalBytes - streamBytes) {
            return MutationPlan.Done(
                SpoolResult.Rejected(SpoolRejection.DescriptorMismatch("maxTotalBytes")),
            )
        }
        if (state.storeRevision == ULong.MAX_VALUE) return revisionExhausted()

        val nextChunks = stream.chunks + (
            descriptor.chunkId to DurableChunkRecord(chunk)
        )
        val nextStream = stream.copy(
            chunks = nextChunks,
            sourceDurableThrough = contiguousDurableThrough(stream.descriptor, nextChunks.values),
        )
        val next = replaceStream(state, capture, nextStream).copy(
            storeRevision = state.storeRevision + 1uL,
        )
        return MutationPlan.Write(
            next,
            ChunkPersistenceReceipt(
                sourceAdvancePermit = nextStream.sourceAdvancePermit(),
                capacity = next.capacity,
            ),
        )
    }

    private fun planCloudAck(
        state: SpoolState,
        expectation: CloudAckExpectation,
        ack: DurableCloudAck,
    ): MutationPlan<FinalizationReadiness> {
        val capture = state.captures[expectation.captureSessionId]
            ?: return missingCapture(expectation.captureSessionId)
        val stream = capture.streams[expectation.streamId]
            ?: return missingStream(expectation.captureSessionId, expectation.streamId)
        val record = stream.chunks[expectation.chunkId]
            ?: return MutationPlan.Done(
                SpoolResult.Rejected(SpoolRejection.IdentityConflict("ACK_CHUNK_NOT_DURABLE_LOCALLY")),
            )

        ackMismatch(expectation, ack, record.chunk.descriptor)?.let { mismatch ->
            return MutationPlan.Done(SpoolResult.Rejected(mismatch))
        }
        record.uploadAttempt?.let { attempt ->
            uploadAttemptMismatch(expectation, attempt)?.let { mismatch ->
                return MutationPlan.Done(SpoolResult.Rejected(mismatch))
            }
        }
        capture.remoteIngest?.let { remote ->
            if (remote.ingestSessionId != ack.ingestSessionId) {
                return MutationPlan.Done(
                    SpoolResult.Rejected(SpoolRejection.CloudAckMismatch("ingestSessionId")),
                )
            }
        }

        val applied = AppliedCloudAck(
            ingestSessionId = ack.ingestSessionId,
            descriptorDigest = ack.acknowledgedDescriptorDigest,
            stateRevision = ack.stateRevision,
        )
        val prior = record.cloudAck
        if (prior != null && (
                prior.ingestSessionId != applied.ingestSessionId ||
                    prior.descriptorDigest != applied.descriptorDigest
                )
        ) {
            return MutationPlan.Done(
                SpoolResult.Rejected(
                    SpoolRejection.IdentityConflict("CHUNK_ACK_REBOUND_TO_DIFFERENT_DESCRIPTOR"),
                ),
            )
        }
        // A revision orders the capture-level range snapshot; it does not invalidate this exact
        // chunk's durable acknowledgement. Never downgrade an already-recorded per-chunk revision.
        if (prior != null && ack.stateRevision <= prior.stateRevision) {
            return MutationPlan.Done(
                SpoolResult.Duplicate(finalizationReadiness(stream), state.storeRevision),
            )
        }
        if (state.storeRevision == ULong.MAX_VALUE) return revisionExhausted()

        val nextRecord = record.copy(cloudAck = applied, uploadAttempt = null)
        val nextStream = stream.copy(chunks = stream.chunks + (expectation.chunkId to nextRecord))
        val remoteRevision = maxOf(capture.remoteIngest?.stateRevision ?: 0uL, ack.stateRevision)
        val nextCapture = capture.copy(
            streams = capture.streams + (expectation.streamId to nextStream),
            remoteIngest = RemoteIngestState(ack.ingestSessionId, remoteRevision),
        )
        val next = state.copy(
            storeRevision = state.storeRevision + 1uL,
            captures = state.captures + (expectation.captureSessionId to nextCapture),
        )
        return MutationPlan.Write(next, finalizationReadiness(nextStream))
    }

    private fun planBeginUploadAttempt(
        state: SpoolState,
        expectation: CloudAckExpectation,
    ): MutationPlan<Unit> {
        val located = locateUploadRecord(state, expectation)
        if (located is UploadRecordLocation.Failed) return located.plan
        located as UploadRecordLocation.Found
        located.capture.remoteIngest?.let { remote ->
            if (remote.ingestSessionId != expectation.ingestSessionId) {
                return MutationPlan.Done(
                    SpoolResult.Rejected(SpoolRejection.CloudAckMismatch("ingestSessionId")),
                )
            }
        } ?: return MutationPlan.Done(
            SpoolResult.Rejected(
                SpoolRejection.IngestSessionNotBound(expectation.captureSessionId),
            ),
        )
        located.record.cloudAck?.let { ack ->
            return if (
                ack.ingestSessionId == expectation.ingestSessionId &&
                ack.descriptorDigest == expectation.descriptorDigest
            ) {
                MutationPlan.Done(SpoolResult.Duplicate(Unit, state.storeRevision))
            } else {
                MutationPlan.Done(
                    SpoolResult.Rejected(
                        SpoolRejection.IdentityConflict(
                            "CHUNK_ALREADY_ACKNOWLEDGED_WITH_DIFFERENT_UPLOAD_BINDING",
                        ),
                    ),
                )
            }
        }
        located.record.uploadAttempt?.let { attempt ->
            uploadAttemptMismatch(expectation, attempt)?.let { mismatch ->
                return MutationPlan.Done(SpoolResult.Rejected(mismatch))
            }
            if (attempt.disposition != UploadAttemptDisposition.RETRY_AUTHORIZED) {
                return MutationPlan.Done(SpoolResult.Duplicate(Unit, state.storeRevision))
            }
            if (state.storeRevision == ULong.MAX_VALUE) return revisionExhausted()
            return replaceUploadRecord(
                state,
                located,
                located.record.copy(
                    uploadAttempt = attempt.copy(
                        disposition = UploadAttemptDisposition.OUTCOME_UNKNOWN,
                    ),
                ),
                Unit,
            )
        }
        if (state.storeRevision == ULong.MAX_VALUE) return revisionExhausted()
        val nextRecord = located.record.copy(
            uploadAttempt = UploadAttemptFence(
                ingestSessionId = expectation.ingestSessionId,
                descriptorDigest = expectation.descriptorDigest,
                disposition = UploadAttemptDisposition.OUTCOME_UNKNOWN,
            ),
        )
        return replaceUploadRecord(state, located, nextRecord, Unit)
    }

    private fun planUploadAttemptDisposition(
        state: SpoolState,
        expectation: CloudAckExpectation,
        disposition: UploadAttemptDisposition,
    ): MutationPlan<Unit> {
        val located = locateUploadRecord(state, expectation)
        if (located is UploadRecordLocation.Failed) return located.plan
        located as UploadRecordLocation.Found
        val attempt = located.record.uploadAttempt
            ?: return MutationPlan.Done(
                SpoolResult.Rejected(
                    SpoolRejection.IdentityConflict("UPLOAD_ATTEMPT_FENCE_NOT_FOUND"),
                ),
            )
        uploadAttemptMismatch(expectation, attempt)?.let { mismatch ->
            return MutationPlan.Done(SpoolResult.Rejected(mismatch))
        }
        if (attempt.disposition == disposition) {
            return MutationPlan.Done(SpoolResult.Duplicate(Unit, state.storeRevision))
        }
        val transitionAllowed = when (disposition) {
            UploadAttemptDisposition.REJECTED ->
                attempt.disposition == UploadAttemptDisposition.OUTCOME_UNKNOWN

            UploadAttemptDisposition.RETRY_AUTHORIZED ->
                attempt.disposition == UploadAttemptDisposition.OUTCOME_UNKNOWN ||
                    attempt.disposition == UploadAttemptDisposition.REJECTED

            UploadAttemptDisposition.OUTCOME_UNKNOWN -> false
        }
        if (!transitionAllowed) {
            return MutationPlan.Done(
                SpoolResult.Rejected(
                    SpoolRejection.IdentityConflict("UPLOAD_ATTEMPT_DISPOSITION_TRANSITION_INVALID"),
                ),
            )
        }
        if (state.storeRevision == ULong.MAX_VALUE) return revisionExhausted()
        return replaceUploadRecord(
            state,
            located,
            located.record.copy(uploadAttempt = attempt.copy(disposition = disposition)),
            Unit,
        )
    }

    private fun locateUploadRecord(
        state: SpoolState,
        expectation: CloudAckExpectation,
    ): UploadRecordLocation {
        val capture = state.captures[expectation.captureSessionId]
            ?: return UploadRecordLocation.Failed(missingCapture(expectation.captureSessionId))
        val stream = capture.streams[expectation.streamId]
            ?: return UploadRecordLocation.Failed(
                missingStream(expectation.captureSessionId, expectation.streamId),
            )
        val record = stream.chunks[expectation.chunkId]
            ?: return UploadRecordLocation.Failed(
                MutationPlan.Done(
                    SpoolResult.Rejected(
                        SpoolRejection.IdentityConflict("UPLOAD_CHUNK_NOT_DURABLE_LOCALLY"),
                    ),
                ),
            )
        return UploadRecordLocation.Found(capture, stream, record)
    }

    private fun <T> replaceUploadRecord(
        state: SpoolState,
        located: UploadRecordLocation.Found,
        nextRecord: DurableChunkRecord,
        value: T,
    ): MutationPlan<T> {
        val chunkId = nextRecord.chunk.descriptor.chunkId
        val nextStream = located.stream.copy(
            chunks = located.stream.chunks + (chunkId to nextRecord),
        )
        val nextCapture = located.capture.copy(
            streams = located.capture.streams + (nextStream.descriptor.streamId to nextStream),
        )
        return MutationPlan.Write(
            next = state.copy(
                storeRevision = state.storeRevision + 1uL,
                captures = state.captures + (nextCapture.captureSessionId to nextCapture),
            ),
            value = value,
        )
    }

    private suspend fun <T> mutate(
        planner: (SpoolState) -> MutationPlan<T>,
    ): SpoolResult<T> = mutex.withLock {
        repeat(MAX_COMMIT_RECONCILIATIONS) {
            val state = when (val loaded = store.load()) {
                is SpoolStoreLoadResult.Loaded -> loaded.state
                is SpoolStoreLoadResult.Unavailable -> return@withLock SpoolResult.Unavailable(
                    loaded.failure,
                )
            }
            when (val plan = planner(state)) {
                is MutationPlan.Done -> return@withLock plan.result
                is MutationPlan.Write -> when (
                    val committed = store.commit(state.storeRevision, plan.next)
                ) {
                    SpoolStoreCommitResult.Committed -> return@withLock SpoolResult.Applied(
                        value = plan.value,
                        storeRevision = plan.next.storeRevision,
                    )

                    is SpoolStoreCommitResult.Unavailable -> return@withLock SpoolResult.Unavailable(
                        committed.failure,
                    )

                    is SpoolStoreCommitResult.RevisionMismatch,
                    SpoolStoreCommitResult.OutcomeUnknown,
                    -> Unit // Reload and reconcile exact durable identity.
                }
            }
        }
        SpoolResult.Unavailable(
            SpoolStoreFailure(code = "SPOOL_RECONCILIATION_EXHAUSTED", retryable = true),
        )
    }

    companion object {
        private const val MAX_COMMIT_RECONCILIATIONS = 8
    }
}

private sealed interface MutationPlan<out T> {
    data class Done<T>(val result: SpoolResult<T>) : MutationPlan<T>

    data class Write<T>(
        val next: SpoolState,
        val value: T,
    ) : MutationPlan<T>
}

private sealed interface UploadRecordLocation {
    data class Found(
        val capture: CaptureSpoolState,
        val stream: StreamSpoolState,
        val record: DurableChunkRecord,
    ) : UploadRecordLocation

    data class Failed(val plan: MutationPlan<Nothing>) : UploadRecordLocation
}

private val UPLOAD_RECORD_ORDER =
    compareBy<DurableChunkRecord> { it.chunk.descriptor.sequenceRange.first }
        .thenBy { it.chunk.descriptor.sequenceRange.last }
        .thenBy { it.chunk.descriptor.chunkId.value }

private fun descriptorMismatch(
    stream: StreamDescriptor,
    chunk: ChunkDescriptor,
): SpoolRejection.DescriptorMismatch? = when {
    chunk.captureSessionId != stream.captureSessionId ->
        SpoolRejection.DescriptorMismatch("captureSessionId")

    chunk.streamId != stream.streamId -> SpoolRejection.DescriptorMismatch("streamId")
    chunk.payloadBytes > stream.maxChunkBytes -> SpoolRejection.DescriptorMismatch("payloadBytes")
    chunk.payloadFormat != stream.payloadFormat -> SpoolRejection.DescriptorMismatch("payloadFormat")
    chunk.codecConfigurationId != stream.codec.configurationId ->
        SpoolRejection.DescriptorMismatch("codecConfigurationId")

    else -> null
}

private fun ackMismatch(
    expectation: CloudAckExpectation,
    ack: DurableCloudAck,
    descriptor: ChunkDescriptor,
): SpoolRejection.CloudAckMismatch? = when {
    expectation.ingestSessionId != ack.ingestSessionId ->
        SpoolRejection.CloudAckMismatch("ingestSessionId")

    expectation.streamId != ack.streamId -> SpoolRejection.CloudAckMismatch("streamId")
    expectation.chunkId != ack.acknowledgedChunkId ->
        SpoolRejection.CloudAckMismatch("acknowledgedChunkId")

    expectation.descriptorDigest != ack.acknowledgedDescriptorDigest ->
        SpoolRejection.CloudAckMismatch("acknowledgedDescriptorDigest")

    descriptor.contentDigest != ack.acknowledgedContentDigest ->
        SpoolRejection.CloudAckMismatch("acknowledgedContentDigest")

    descriptor.sequenceRange != ack.acknowledgedSequenceRange ->
        SpoolRejection.CloudAckMismatch("acknowledgedSequenceRange")

    else -> null
}

private fun uploadAttemptMismatch(
    expectation: CloudAckExpectation,
    attempt: UploadAttemptFence,
): SpoolRejection? = when {
    expectation.ingestSessionId != attempt.ingestSessionId ->
        SpoolRejection.CloudAckMismatch("ingestSessionId")

    expectation.descriptorDigest != attempt.descriptorDigest ->
        SpoolRejection.CloudAckMismatch("descriptorDigest")

    else -> null
}

private fun replaceStream(
    state: SpoolState,
    capture: CaptureSpoolState,
    stream: StreamSpoolState,
): SpoolState {
    val nextCapture = capture.copy(streams = capture.streams + (stream.descriptor.streamId to stream))
    return state.copy(captures = state.captures + (capture.captureSessionId to nextCapture))
}

private fun StreamSpoolState.sourceAdvancePermit(): SourceAdvancePermit? = sourceDurableThrough?.let {
    SourceAdvancePermit(
        captureSessionId = descriptor.captureSessionId,
        streamId = descriptor.streamId,
        durableThrough = it,
    )
}

private fun SpoolState.toRecovery(): SpoolRecovery {
    val streams = captures.values
        .sortedBy { it.captureSessionId.value }
        .flatMap { capture ->
            capture.streams.values.sortedBy { it.descriptor.streamId.value }.map { stream ->
                RecoveredStream(
                    descriptor = stream.descriptor,
                    durableChunkCount = stream.chunks.size.toULong(),
                    cloudDurableChunkCount = stream.chunks.values.count { it.cloudAck != null }.toULong(),
                    blockedUploadCount = stream.chunks.values.count {
                        it.uploadAttempt != null
                    }.toULong(),
                    sourceDurableThrough = stream.sourceDurableThrough,
                    terminalRange = stream.terminalRange,
                    discontinuityCount = stream.chunks.values.count {
                        it.chunk.descriptor.sourceDiscontinuityBefore != null
                    }.toULong(),
                    finalizationReadiness = finalizationReadiness(stream),
                )
            }
        }
    return SpoolRecovery(
        storeRevision = storeRevision,
        retainedBytes = retainedBytes,
        pressure = pressure,
        streams = streams,
        sourceAdvancePermits = streams.mapNotNull { stream ->
            stream.sourceDurableThrough?.let { durableThrough ->
                SourceAdvancePermit(
                    captureSessionId = stream.descriptor.captureSessionId,
                    streamId = stream.descriptor.streamId,
                    durableThrough = durableThrough,
                )
            }
        },
    )
}

private fun <T> missingCapture(captureSessionId: CaptureSessionId): MutationPlan<T> =
    MutationPlan.Done(
        SpoolResult.Rejected(SpoolRejection.CaptureNotFound(captureSessionId)),
    )

private fun <T> missingStream(
    captureSessionId: CaptureSessionId,
    streamId: StreamId,
): MutationPlan<T> = MutationPlan.Done(
    SpoolResult.Rejected(SpoolRejection.StreamNotFound(captureSessionId, streamId)),
)

private fun <T> revisionExhausted(): MutationPlan<T> =
    MutationPlan.Done(
        SpoolResult.Rejected(SpoolRejection.StoreRevisionExhausted),
    )

private fun checkedAdd(left: ULong, right: ULong): ULong {
    require(right <= ULong.MAX_VALUE - left) { "Unsigned 64-bit byte total overflow" }
    return left + right
}
