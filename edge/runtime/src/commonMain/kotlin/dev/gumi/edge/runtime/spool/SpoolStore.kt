package dev.gumi.edge.runtime.spool

import dev.gumi.edge.sdk.OpaqueBytes

/** Redacted operational failure from a durable metadata adapter. */
data class SpoolStoreFailure(
    val code: String,
    val retryable: Boolean,
) {
    init {
        require(code.matches(Regex("[A-Z][A-Z0-9_]{1,63}"))) {
            "Spool store failure code must be a stable uppercase identifier"
        }
    }
}

sealed interface SpoolStoreLoadResult {
    data class Loaded(val state: SpoolState) : SpoolStoreLoadResult

    data class Unavailable(val failure: SpoolStoreFailure) : SpoolStoreLoadResult
}

sealed interface SpoolStoreCommitResult {
    data object Committed : SpoolStoreCommitResult

    /** Another owner won the compare-and-set transaction. The coordinator must reload. */
    data class RevisionMismatch(val actualRevision: ULong) : SpoolStoreCommitResult

    data class Unavailable(val failure: SpoolStoreFailure) : SpoolStoreCommitResult

    /**
     * The adapter lost the response after entering its commit boundary. The coordinator must reload
     * and reconcile; it must never issue a source-advance permit based only on this result.
     */
    data object OutcomeUnknown : SpoolStoreCommitResult
}

/**
 * Transactional metadata boundary for the portable spool coordinator.
 *
 * A production implementation atomically and durably replaces [SpoolState] only when
 * [expectedRevision] matches. It may persist normalized rows rather than a serialized snapshot, but
 * the observable transaction is identical: the new chunk record, its opaque already-flushed payload
 * reference, and the recomputed source checkpoint commit together. Returning [Committed] before that
 * durability boundary is a protocol violation.
 */
interface SpoolStore {
    suspend fun load(): SpoolStoreLoadResult

    suspend fun commit(
        expectedRevision: ULong,
        next: SpoolState,
    ): SpoolStoreCommitResult
}

/**
 * Trusted host boundary that encrypts, writes, integrity-checks, and durably flushes media bytes.
 *
 * The coordinator calls this port itself; API callers cannot present a self-asserted durability
 * receipt. A repeated write for the same immutable [ChunkDescriptor] must either return the same
 * payload reference or fail with an identity/integrity error. Bytes may be orphaned if the later
 * metadata transaction fails; an adapter's reconciliation worker may collect such unreferenced
 * payloads, but it must never delete a referenced payload.
 */
interface DurablePayloadStore {
    suspend fun writeAndFlush(
        descriptor: ChunkDescriptor,
        payload: OpaqueBytes,
    ): DurablePayloadWriteResult

    /**
     * Returns the exact payload named by [chunk] only after rechecking the adapter's integrity
     * binding (reference, declared length, and content digest). A successful read is therefore safe
     * to hand to a publisher; callers never open or interpret a storage path themselves.
     */
    suspend fun readAndVerify(chunk: DurableChunk): DurablePayloadReadResult
}

sealed interface DurablePayloadWriteResult {
    data class Stored(val payloadRef: DurablePayloadRef) : DurablePayloadWriteResult

    data class Unavailable(val failure: SpoolStoreFailure) : DurablePayloadWriteResult
}

sealed interface DurablePayloadReadResult {
    data class Verified(val payload: OpaqueBytes) : DurablePayloadReadResult

    data class Unavailable(val failure: SpoolStoreFailure) : DurablePayloadReadResult
}
