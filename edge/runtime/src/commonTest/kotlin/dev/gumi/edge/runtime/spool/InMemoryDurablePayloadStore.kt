package dev.gumi.edge.runtime.spool

import dev.gumi.edge.sdk.OpaqueBytes

/** Deterministic fake for the trusted encrypted-write-and-flush boundary. */
internal class InMemoryDurablePayloadStore : DurablePayloadStore {
    private data class StoredPayload(
        val descriptor: ChunkDescriptor,
        val payload: OpaqueBytes,
    )

    private val payloads = mutableMapOf<DurablePayloadRef, StoredPayload>()
    var nextFailure: SpoolStoreFailure? = null
    var nextReadFailure: SpoolStoreFailure? = null
    var writeCount: Int = 0
    var readCount: Int = 0

    override suspend fun writeAndFlush(
        descriptor: ChunkDescriptor,
        payload: OpaqueBytes,
    ): DurablePayloadWriteResult {
        writeCount += 1
        nextFailure?.also {
            nextFailure = null
            return DurablePayloadWriteResult.Unavailable(it)
        }
        require(payload.size.toULong() == descriptor.payloadBytes)
        val payloadRef = DurablePayloadRef("test-encrypted-payload/${descriptor.chunkId.value}")
        val existing = payloads[payloadRef]
        if (existing != null && (existing.descriptor != descriptor || existing.payload != payload)) {
            return DurablePayloadWriteResult.Unavailable(
                SpoolStoreFailure("TEST_PAYLOAD_IDENTITY_CONFLICT", retryable = false),
            )
        }
        payloads[payloadRef] = StoredPayload(descriptor, payload)
        return DurablePayloadWriteResult.Stored(payloadRef)
    }

    override suspend fun readAndVerify(chunk: DurableChunk): DurablePayloadReadResult {
        readCount += 1
        nextReadFailure?.also {
            nextReadFailure = null
            return DurablePayloadReadResult.Unavailable(it)
        }
        val stored = payloads[chunk.payloadRef]
            ?: return DurablePayloadReadResult.Unavailable(
                SpoolStoreFailure("TEST_PAYLOAD_NOT_FOUND", retryable = false),
            )
        if (stored.descriptor != chunk.descriptor ||
            stored.payload.size.toULong() != chunk.descriptor.payloadBytes
        ) {
            return DurablePayloadReadResult.Unavailable(
                SpoolStoreFailure("TEST_PAYLOAD_INTEGRITY_MISMATCH", retryable = false),
            )
        }
        return DurablePayloadReadResult.Verified(stored.payload)
    }
}
