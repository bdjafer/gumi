package dev.gumi.edge.runtime.spool

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Deterministic transaction fake. It is deliberately not a production durability adapter. */
internal class InMemorySpoolStore(
    initial: SpoolState,
) : SpoolStore {
    enum class CommitFault {
        NONE,
        BEFORE_COMMIT,
        AFTER_COMMIT_RESPONSE_LOST,
    }

    private val mutex = Mutex()
    private var state = initial

    var nextCommitFault: CommitFault = CommitFault.NONE
    var afterUnknownCommit: ((SpoolState) -> SpoolState)? = null
    val committedStates = mutableListOf<SpoolState>()

    override suspend fun load(): SpoolStoreLoadResult = mutex.withLock {
        SpoolStoreLoadResult.Loaded(state)
    }

    override suspend fun commit(
        expectedRevision: ULong,
        next: SpoolState,
    ): SpoolStoreCommitResult = mutex.withLock {
        if (state.storeRevision != expectedRevision) {
            return@withLock SpoolStoreCommitResult.RevisionMismatch(state.storeRevision)
        }
        require(next.storeRevision == expectedRevision + 1uL) {
            "Test store accepts exact next-revision transactions only"
        }
        when (nextCommitFault.also { nextCommitFault = CommitFault.NONE }) {
            CommitFault.NONE -> {
                state = next
                committedStates += next
                SpoolStoreCommitResult.Committed
            }

            CommitFault.BEFORE_COMMIT -> SpoolStoreCommitResult.Unavailable(
                SpoolStoreFailure("TEST_STORE_BEFORE_COMMIT", retryable = true),
            )

            CommitFault.AFTER_COMMIT_RESPONSE_LOST -> {
                state = next
                committedStates += next
                afterUnknownCommit?.also { concurrentCommit ->
                    afterUnknownCommit = null
                    val concurrent = concurrentCommit(state)
                    require(concurrent.storeRevision == state.storeRevision + 1uL) {
                        "Simulated concurrent commit must advance exactly one revision"
                    }
                    state = concurrent
                    committedStates += concurrent
                }
                SpoolStoreCommitResult.OutcomeUnknown
            }
        }
    }

    suspend fun snapshot(): SpoolState = mutex.withLock { state }
}
