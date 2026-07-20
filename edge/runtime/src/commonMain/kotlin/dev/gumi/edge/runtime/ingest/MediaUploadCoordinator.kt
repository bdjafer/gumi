package dev.gumi.edge.runtime.ingest

import dev.gumi.edge.runtime.spool.BlockedChunkUpload
import dev.gumi.edge.runtime.spool.CaptureSessionId
import dev.gumi.edge.runtime.spool.ChunkId
import dev.gumi.edge.runtime.spool.CloudAckExpectation
import dev.gumi.edge.runtime.spool.DurableCloudAck
import dev.gumi.edge.runtime.spool.DurablePayloadReadResult
import dev.gumi.edge.runtime.spool.DurablePayloadStore
import dev.gumi.edge.runtime.spool.FinalizationReadiness
import dev.gumi.edge.runtime.spool.IngestSessionId
import dev.gumi.edge.runtime.spool.PendingUploadSelection
import dev.gumi.edge.runtime.spool.SpoolCoordinator
import dev.gumi.edge.runtime.spool.SpoolRejection
import dev.gumi.edge.runtime.spool.SpoolResult
import dev.gumi.edge.runtime.spool.SpoolStoreFailure
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface MediaUploadStepResult {
    data object NothingPending : MediaUploadStepResult

    data class Blocked(val chunks: List<BlockedChunkUpload>) : MediaUploadStepResult

    data class Acknowledged(
        val expectation: CloudAckExpectation,
        val ack: DurableCloudAck,
        val finalizationReadiness: FinalizationReadiness,
    ) : MediaUploadStepResult

    data class PreparationRejected(
        val chunkId: ChunkId,
        val failure: ExpectedFailure,
    ) : MediaUploadStepResult

    data class PayloadUnavailable(
        val chunkId: ChunkId,
        val failure: SpoolStoreFailure,
    ) : MediaUploadStepResult

    /** The adapter proved no external effect; a later scheduler pass may select this chunk again. */
    data class NotAttempted(
        val expectation: CloudAckExpectation,
        val failure: ExpectedFailure,
    ) : MediaUploadStepResult

    /** A definitive refusal is durably fenced until [authorizeRetry] is deliberately called. */
    data class Rejected(
        val expectation: CloudAckExpectation,
        val failure: ExpectedFailure,
    ) : MediaUploadStepResult

    /** Remote durability is ambiguous and automatic retry is durably fenced. */
    data class OutcomeUnknown(
        val expectation: CloudAckExpectation,
        val failure: ExpectedFailure,
    ) : MediaUploadStepResult

    data class SpoolRejected(val rejection: SpoolRejection) : MediaUploadStepResult

    data class SpoolUnavailable(val failure: SpoolStoreFailure) : MediaUploadStepResult

    /** A concurrent/restarted owner already resolved or fenced the selected exact attempt. */
    data class SelectionChanged(val expectation: CloudAckExpectation) : MediaUploadStepResult
}

/**
 * Portable one-step upload owner. One invocation selects at most one chunk and invokes the external
 * port at most once. The process-local mutex serializes attempts; the durable spool fence preserves
 * the no-blind-retry invariant across process death and multiple owners.
 */
class MediaUploadCoordinator(
    private val spool: SpoolCoordinator,
    private val payloadStore: DurablePayloadStore,
    private val ingest: MediaIngestPort,
) {
    private val mutex = Mutex()

    suspend fun uploadNext(
        captureSessionId: CaptureSessionId,
        ingestSessionId: IngestSessionId,
    ): MediaUploadStepResult = mutex.withLock {
        when (val binding = spool.bindIngestSession(captureSessionId, ingestSessionId)) {
            is SpoolResult.Applied,
            is SpoolResult.Duplicate,
            -> Unit

            is SpoolResult.Rejected -> return@withLock MediaUploadStepResult.SpoolRejected(
                binding.rejection,
            )

            is SpoolResult.Unavailable -> return@withLock MediaUploadStepResult.SpoolUnavailable(
                binding.failure,
            )
        }

        val pending = when (val selected = spool.selectNextUpload(captureSessionId)) {
            is SpoolResult.Applied -> selected.value
            is SpoolResult.Duplicate -> selected.value
            is SpoolResult.Rejected -> return@withLock MediaUploadStepResult.SpoolRejected(
                selected.rejection,
            )

            is SpoolResult.Unavailable -> return@withLock MediaUploadStepResult.SpoolUnavailable(
                selected.failure,
            )
        }
        when (pending) {
            PendingUploadSelection.Empty -> return@withLock MediaUploadStepResult.NothingPending
            is PendingUploadSelection.Blocked -> return@withLock MediaUploadStepResult.Blocked(
                pending.chunks,
            )

            is PendingUploadSelection.Ready -> Unit
        }
        val candidate = pending.upload
        val descriptor = candidate.chunk.descriptor
        val prepared = when (
            val preparation = ingest.prepareChunk(candidate.ingestSessionId, descriptor)
        ) {
            is MediaIngestPreparationResult.Prepared -> preparation.upload
            is MediaIngestPreparationResult.Rejected -> {
                return@withLock MediaUploadStepResult.PreparationRejected(
                    chunkId = descriptor.chunkId,
                    failure = preparation.failure,
                )
            }
        }
        if (prepared.ingestSessionId != candidate.ingestSessionId ||
            prepared.descriptor != descriptor
        ) {
            return@withLock MediaUploadStepResult.PreparationRejected(
                chunkId = descriptor.chunkId,
                failure = failure(
                    category = FailureCategory.INTERNAL,
                    code = "INGEST_PREPARATION_IDENTITY_MISMATCH",
                    retryable = false,
                ),
            )
        }
        val payload = when (val read = payloadStore.readAndVerify(candidate.chunk)) {
            is DurablePayloadReadResult.Verified -> read.payload
            is DurablePayloadReadResult.Unavailable -> {
                return@withLock MediaUploadStepResult.PayloadUnavailable(
                    descriptor.chunkId,
                    read.failure,
                )
            }
        }
        val expectation = prepared.ackExpectation
        when (val fenced = spool.beginUploadAttempt(expectation)) {
            is SpoolResult.Applied -> Unit
            is SpoolResult.Duplicate -> {
                return@withLock MediaUploadStepResult.SelectionChanged(expectation)
            }

            is SpoolResult.Rejected -> return@withLock MediaUploadStepResult.SpoolRejected(
                fenced.rejection,
            )

            is SpoolResult.Unavailable -> return@withLock MediaUploadStepResult.SpoolUnavailable(
                fenced.failure,
            )
        }

        val result = try {
            ingest.uploadChunk(prepared, payload)
        } catch (cancelled: CancellationException) {
            throw cancelled // The prewritten conservative fence intentionally survives cancellation.
        } catch (_: Throwable) {
            MediaIngestUploadResult.OutcomeUnknown(
                failure(
                    category = FailureCategory.INTERNAL,
                    code = "INGEST_PORT_THREW",
                    retryable = false,
                ),
            )
        }
        when (result) {
            is MediaIngestUploadResult.DurablyAcknowledged -> applyAck(
                expectation,
                result.ack,
            )

            is MediaIngestUploadResult.OutcomeUnknown -> MediaUploadStepResult.OutcomeUnknown(
                expectation,
                result.failure,
            )

            is MediaIngestUploadResult.Rejected -> when (
                val persisted = spool.markUploadRejected(expectation)
            ) {
                is SpoolResult.Applied,
                is SpoolResult.Duplicate,
                -> MediaUploadStepResult.Rejected(expectation, result.failure)

                is SpoolResult.Rejected -> MediaUploadStepResult.SpoolRejected(persisted.rejection)
                is SpoolResult.Unavailable -> MediaUploadStepResult.SpoolUnavailable(persisted.failure)
            }

            is MediaIngestUploadResult.NotAttempted -> when (
                val reset = spool.cancelUnattemptedUpload(expectation)
            ) {
                is SpoolResult.Applied,
                is SpoolResult.Duplicate,
                -> MediaUploadStepResult.NotAttempted(expectation, result.failure)

                is SpoolResult.Rejected -> MediaUploadStepResult.SpoolRejected(reset.rejection)
                is SpoolResult.Unavailable -> MediaUploadStepResult.SpoolUnavailable(reset.failure)
            }
        }
    }

    suspend fun authorizeRetry(expectation: CloudAckExpectation): SpoolResult<Unit> =
        spool.authorizeUploadRetry(expectation)

    private suspend fun applyAck(
        expectation: CloudAckExpectation,
        ack: DurableCloudAck,
    ): MediaUploadStepResult = when (val applied = spool.applyDurableCloudAck(expectation, ack)) {
        is SpoolResult.Applied -> MediaUploadStepResult.Acknowledged(
            expectation,
            ack,
            applied.value,
        )

        is SpoolResult.Duplicate -> MediaUploadStepResult.Acknowledged(
            expectation,
            ack,
            applied.value,
        )

        is SpoolResult.Rejected -> MediaUploadStepResult.SpoolRejected(applied.rejection)
        is SpoolResult.Unavailable -> MediaUploadStepResult.SpoolUnavailable(applied.failure)
    }
}

private fun failure(
    category: FailureCategory,
    code: String,
    retryable: Boolean,
): ExpectedFailure = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
)
