package dev.gumi.edge.runtime.ingest

import dev.gumi.edge.runtime.spool.ChunkDescriptor
import dev.gumi.edge.runtime.spool.CloudAckExpectation
import dev.gumi.edge.runtime.spool.DurableCloudAck
import dev.gumi.edge.runtime.spool.IngestSessionId
import dev.gumi.edge.runtime.spool.Sha256Digest
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.OpaqueBytes

/**
 * Provider-neutral upload binding prepared from the publisher's canonical descriptor mapping.
 *
 * This is semantic runtime data, not an HTTP/OpenAPI DTO. The adapter owns canonical JSON, request
 * headers, credentials, URLs, and normalized response mapping. It must send the same descriptor
 * mapping whose digest it returned here.
 */
data class PreparedMediaChunkUpload(
    val ingestSessionId: IngestSessionId,
    val descriptor: ChunkDescriptor,
    val canonicalDescriptorDigest: Sha256Digest,
) {
    val ackExpectation: CloudAckExpectation = CloudAckExpectation(
        ingestSessionId = ingestSessionId,
        captureSessionId = descriptor.captureSessionId,
        streamId = descriptor.streamId,
        chunkId = descriptor.chunkId,
        descriptorDigest = canonicalDescriptorDigest,
    )
}

sealed interface MediaIngestPreparationResult {
    data class Prepared(val upload: PreparedMediaChunkUpload) : MediaIngestPreparationResult

    /** Pure local mapping or policy failure; preparation must not perform an external side effect. */
    data class Rejected(val failure: ExpectedFailure) : MediaIngestPreparationResult
}

sealed interface MediaIngestUploadResult {
    /** The adapter has normalized a publisher response that proves remote durable acceptance. */
    data class DurablyAcknowledged(val ack: DurableCloudAck) : MediaIngestUploadResult

    /** The publisher definitively refused this exact request. Automatic retry remains forbidden. */
    data class Rejected(val failure: ExpectedFailure) : MediaIngestUploadResult

    /** No request crossed an external acceptance boundary, so the local attempt fence may be reset. */
    data class NotAttempted(val failure: ExpectedFailure) : MediaIngestUploadResult

    /** The adapter cannot prove whether the publisher durably accepted the request. */
    data class OutcomeUnknown(val failure: ExpectedFailure) : MediaIngestUploadResult
}

/**
 * Publisher-facing media-ingest port. Implementations translate semantic runtime types to a
 * provider contract and normalize its durable acknowledgement back into [DurableCloudAck].
 * Expected transport/provider failures must be returned as values; credentials and response bodies
 * must never cross this boundary.
 */
interface MediaIngestPort {
    /** Pure deterministic mapping: this call must not contact the publisher. */
    fun prepareChunk(
        ingestSessionId: IngestSessionId,
        descriptor: ChunkDescriptor,
    ): MediaIngestPreparationResult

    /** Makes at most one external attempt for [prepared]. */
    suspend fun uploadChunk(
        prepared: PreparedMediaChunkUpload,
        payload: OpaqueBytes,
    ): MediaIngestUploadResult
}
