package dev.gumi.edge.platforms.android.operational

import android.content.Context
import dev.gumi.edge.platforms.android.spool.AndroidEncryptedSpoolStorage
import dev.gumi.edge.platforms.android.spool.AndroidSpoolCleanupHandle
import dev.gumi.edge.platforms.android.spool.AndroidSpoolCloseException
import dev.gumi.edge.platforms.android.spool.AndroidSpoolOpenException
import dev.gumi.edge.platforms.android.spool.AndroidSpoolOpenOutcomeUnknownException
import dev.gumi.edge.platforms.android.spool.AndroidSpoolOpenResult
import dev.gumi.edge.platforms.android.spool.AndroidSpoolStorageConfiguration
import dev.gumi.edge.runtime.operational.OperationalBacklog
import dev.gumi.edge.runtime.operational.OperationalBacklogScope
import dev.gumi.edge.runtime.operational.OperationalLeaseResult
import dev.gumi.edge.runtime.operational.OperationalRuntimeOperation
import dev.gumi.edge.runtime.operational.OperationalStorageLease
import dev.gumi.edge.runtime.operational.OperationalStorageOpenResult
import dev.gumi.edge.runtime.operational.OperationalStoragePort
import dev.gumi.edge.runtime.operational.ProvisionedDeviceBinding
import dev.gumi.edge.runtime.spool.DurablePayloadStore
import dev.gumi.edge.runtime.spool.SpoolCoordinator
import dev.gumi.edge.runtime.spool.SpoolRecovery
import dev.gumi.edge.runtime.spool.SpoolResult
import dev.gumi.edge.runtime.spool.SpoolStore
import dev.gumi.edge.runtime.spool.SpoolStoreFailure
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android adapter that turns the encrypted spool into one operational lifetime lease.
 *
 * The platform store first performs its file/database reconciliation under its exclusive process and
 * OS lock. The portable coordinator then reloads the reconciled snapshot. A lease can escape only after
 * both boundaries succeed and backlog facts have been derived from that exact recovery snapshot.
 *
 * M1 deliberately has one process-wide spool. It is not partitioned by [ProvisionedDeviceBinding], so
 * concurrent bindings cannot own separate storage and the reported backlog is global to this edge host.
 */
class AndroidOperationalStoragePort internal constructor(
    private val opener: suspend () -> AndroidOperationalSpoolOpenResult,
    private val ioDispatcher: CoroutineDispatcher,
) : OperationalStoragePort {
    constructor(
        context: Context,
        configuration: AndroidSpoolStorageConfiguration,
    ) : this(
        opener = {
            when (
                val opened = AndroidEncryptedSpoolStorage.openAndReconcile(
                    context.applicationContext,
                    configuration,
                )
            ) {
                is AndroidSpoolOpenResult.Ready -> AndroidOperationalSpoolOpenResult.Ready(
                    AndroidEncryptedOperationalSpool(opened.storage),
                )

                is AndroidSpoolOpenResult.Degraded -> AndroidOperationalSpoolOpenResult.Degraded
                is AndroidSpoolOpenResult.OutcomeUnknown ->
                    AndroidOperationalSpoolOpenResult.OutcomeUnknown(
                        AndroidCleanupOperationalSpool(opened.cleanup),
                        opened.failureCode,
                    )
            }
        },
        ioDispatcher = Dispatchers.IO,
    )

    override suspend fun openAndReconcile(
        operation: OperationalRuntimeOperation,
        @Suppress("UNUSED_PARAMETER")
        binding: ProvisionedDeviceBinding,
    ): OperationalStorageOpenResult {
        val opened = try {
            opener()
        } catch (failure: AndroidSpoolOpenOutcomeUnknownException) {
            AndroidOperationalSpoolOpenResult.OutcomeUnknown(
                AndroidCleanupOperationalSpool(failure.cleanup),
                failure.failureCode,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AndroidSpoolOpenException) {
            return OperationalStorageOpenResult.Failed(
                operation,
                operationalFailure(
                    operation,
                    failure.failureCode,
                    categoryForStorageCode(failure.failureCode),
                    retryableStorageCode(failure.failureCode),
                ),
            )
        } catch (_: Exception) {
            return OperationalStorageOpenResult.Failed(
                operation,
                operationalFailure(
                    operation,
                    "ANDROID_OPERATIONAL_STORAGE_OPEN_FAILED",
                    FailureCategory.INTERNAL,
                    retryable = false,
                ),
            )
        }

        when (opened) {
            is AndroidOperationalSpoolOpenResult.Degraded -> {
                return OperationalStorageOpenResult.Failed(
                    operation,
                    operationalFailure(
                        operation,
                        "ANDROID_OPERATIONAL_STORAGE_RECONCILIATION_FAILED",
                        FailureCategory.CORRUPT,
                        retryable = false,
                    ),
                )
            }

            is AndroidOperationalSpoolOpenResult.OutcomeUnknown -> {
                val lease = AndroidOperationalStorageLease(
                    ownerSessionGeneration = operation.sessionGeneration,
                    storage = opened.storage,
                    ioDispatcher = ioDispatcher,
                    initialState = StorageLeaseState.QUIESCED,
                )
                return OperationalStorageOpenResult.OutcomeUnknown(
                    operation,
                    lease,
                    operationalFailure(
                        operation,
                        opened.failureCode,
                        categoryForStorageCode(opened.failureCode),
                        retryableStorageCode(opened.failureCode),
                    ),
                )
            }

            is AndroidOperationalSpoolOpenResult.Ready -> Unit
        }
        val coordinator = SpoolCoordinator(
            store = opened.storage.metadataStore,
            payloadStore = opened.storage.payloadStore,
        )
        val lease = AndroidOperationalStorageLease(
            ownerSessionGeneration = operation.sessionGeneration,
            storage = opened.storage,
            ioDispatcher = ioDispatcher,
        )
        val recovery = try {
            coordinator.recover()
        } catch (cancelled: CancellationException) {
            val settlement = settleFailedOpen(
                operation,
                lease,
                operationalFailure(
                    operation,
                    "ANDROID_OPERATIONAL_STORAGE_RECOVERY_CANCELLED",
                    FailureCategory.CANCELLED,
                    retryable = true,
                ),
            )
            if (settlement is OperationalStorageOpenResult.OutcomeUnknown) return settlement
            throw cancelled
        } catch (_: Exception) {
            return settleFailedOpen(
                operation,
                lease,
                operationalFailure(
                    operation,
                    "ANDROID_OPERATIONAL_STORAGE_RECOVERY_FAILED",
                    FailureCategory.INTERNAL,
                    retryable = false,
                ),
            )
        }

        val recovered = when (recovery) {
            is SpoolResult.Applied -> recovery.value
            is SpoolResult.Unavailable -> return settleFailedOpen(
                operation,
                lease,
                recovery.failure.toOperationalFailure(operation),
            )

            is SpoolResult.Duplicate,
            is SpoolResult.Rejected,
            -> return settleFailedOpen(
                operation,
                lease,
                operationalFailure(
                    operation,
                    "ANDROID_OPERATIONAL_STORAGE_RECOVERY_RESULT_INVALID",
                    FailureCategory.INTERNAL,
                    retryable = false,
                ),
            )
        }

        val backlog = try {
            recovered.toOperationalBacklog()
        } catch (_: IllegalArgumentException) {
            return settleFailedOpen(
                operation,
                lease,
                operationalFailure(
                    operation,
                    "ANDROID_OPERATIONAL_STORAGE_RECOVERY_INVALID",
                    FailureCategory.CORRUPT,
                    retryable = false,
                ),
            )
        }
        return OperationalStorageOpenResult.Ready(
            operation,
            lease,
            backlog,
            backlogScope = OperationalBacklogScope.EDGE_HOST,
        )
    }

    private suspend fun settleFailedOpen(
        operation: OperationalRuntimeOperation,
        lease: AndroidOperationalStorageLease,
        primaryFailure: ExpectedFailure,
    ): OperationalStorageOpenResult {
        val quiesced = lease.quiesce(operation)
        if (quiesced !is OperationalLeaseResult.Completed) {
            return OperationalStorageOpenResult.OutcomeUnknown(
                operation,
                lease,
                quiesced.failureOr(primaryFailure),
            )
        }
        return when (val closed = lease.close(operation)) {
            is OperationalLeaseResult.Completed -> OperationalStorageOpenResult.Failed(
                operation,
                primaryFailure,
            )

            is OperationalLeaseResult.Failed -> OperationalStorageOpenResult.OutcomeUnknown(
                operation,
                lease,
                closed.failure,
            )

            is OperationalLeaseResult.OutcomeUnknown -> OperationalStorageOpenResult.OutcomeUnknown(
                operation,
                lease,
                closed.failure,
            )
        }
    }
}

/** Test seam that never weakens the production open/reconciliation ordering. */
internal sealed interface AndroidOperationalSpoolOpenResult {
    data class Ready(val storage: AndroidOperationalReadySpool) : AndroidOperationalSpoolOpenResult

    data class OutcomeUnknown(
        val storage: AndroidOperationalSpool,
        val failureCode: String,
    ) : AndroidOperationalSpoolOpenResult

    data object Degraded : AndroidOperationalSpoolOpenResult
}

internal interface AndroidOperationalSpool {
    fun close()
}

internal interface AndroidOperationalReadySpool : AndroidOperationalSpool {
    val metadataStore: SpoolStore
    val payloadStore: DurablePayloadStore
}

private class AndroidEncryptedOperationalSpool(
    private val storage: AndroidEncryptedSpoolStorage,
) : AndroidOperationalReadySpool {
    override val metadataStore: SpoolStore = storage.metadataStore
    override val payloadStore: DurablePayloadStore = storage.payloadStore

    override fun close() = storage.close()
}

private class AndroidCleanupOperationalSpool(
    private val cleanup: AndroidSpoolCleanupHandle,
) : AndroidOperationalSpool {
    override fun close() = cleanup.close()
}

internal enum class StorageLeaseState {
    ACTIVE,
    QUIESCED,
    CLOSED,
    CLOSE_OUTCOME_UNKNOWN,
}

/**
 * No coordinator operation is exposed by this link/power slice, so quiescing is an exact admission
 * fence rather than a best-effort delay. A close uncertainty is terminal and replayed forever; it is
 * never converted into a false successful retry merely because the underlying close API is idempotent.
 */
internal class AndroidOperationalStorageLease(
    private val ownerSessionGeneration: ULong,
    private val storage: AndroidOperationalSpool,
    private val ioDispatcher: CoroutineDispatcher,
    initialState: StorageLeaseState = StorageLeaseState.ACTIVE,
) : OperationalStorageLease {
    private val mutex = Mutex()
    private var state = initialState
    private var closeFault: LeaseFault? = null

    override suspend fun quiesce(
        operation: OperationalRuntimeOperation,
    ): OperationalLeaseResult = withContext(NonCancellable) {
        mutex.withLock {
            staleOperationFailure(operation)?.let { return@withLock it }
            when (state) {
                StorageLeaseState.ACTIVE -> state = StorageLeaseState.QUIESCED
                StorageLeaseState.QUIESCED,
                StorageLeaseState.CLOSED,
                StorageLeaseState.CLOSE_OUTCOME_UNKNOWN,
                -> Unit
            }
            OperationalLeaseResult.Completed(operation)
        }
    }

    override suspend fun close(
        operation: OperationalRuntimeOperation,
    ): OperationalLeaseResult = withContext(NonCancellable) {
        mutex.withLock {
            staleOperationFailure(operation)?.let { return@withLock it }
            when (state) {
                StorageLeaseState.ACTIVE -> return@withLock OperationalLeaseResult.Failed(
                    operation,
                    operationalFailure(
                        operation,
                        "ANDROID_OPERATIONAL_STORAGE_CLOSE_BEFORE_QUIESCE",
                        FailureCategory.REJECTED_POLICY,
                        retryable = false,
                    ),
                )

                StorageLeaseState.CLOSED -> return@withLock OperationalLeaseResult.Completed(operation)
                StorageLeaseState.CLOSE_OUTCOME_UNKNOWN -> return@withLock OperationalLeaseResult
                    .OutcomeUnknown(operation, requireNotNull(closeFault).toFailure(operation))

                StorageLeaseState.QUIESCED -> Unit
            }

            try {
                withContext(ioDispatcher) { storage.close() }
                state = StorageLeaseState.CLOSED
                OperationalLeaseResult.Completed(operation)
            } catch (failure: AndroidSpoolCloseException) {
                rememberCloseUnknown(
                    operation,
                    failure.failureCode,
                    categoryForStorageCode(failure.failureCode),
                    retryable = false,
                )
            } catch (_: Exception) {
                rememberCloseUnknown(
                    operation,
                    "ANDROID_OPERATIONAL_STORAGE_CLOSE_FAILED",
                    FailureCategory.INTERNAL,
                    retryable = false,
                )
            }
        }
    }

    private fun staleOperationFailure(
        operation: OperationalRuntimeOperation,
    ): OperationalLeaseResult.Failed? = if (operation.sessionGeneration == ownerSessionGeneration) {
        null
    } else {
        OperationalLeaseResult.Failed(
            operation,
            operationalFailure(
                operation,
                "ANDROID_OPERATIONAL_STORAGE_STALE_LEASE_OPERATION",
                FailureCategory.REPLAYED,
                retryable = false,
            ),
        )
    }

    private fun rememberCloseUnknown(
        operation: OperationalRuntimeOperation,
        code: String,
        category: FailureCategory,
        retryable: Boolean,
    ): OperationalLeaseResult.OutcomeUnknown {
        val fault = LeaseFault(category, code, retryable)
        closeFault = fault
        state = StorageLeaseState.CLOSE_OUTCOME_UNKNOWN
        return OperationalLeaseResult.OutcomeUnknown(operation, fault.toFailure(operation))
    }
}

private data class LeaseFault(
    val category: FailureCategory,
    val code: String,
    val retryable: Boolean,
) {
    fun toFailure(operation: OperationalRuntimeOperation): ExpectedFailure = operationalFailure(
        operation,
        code,
        category,
        retryable,
    )
}

private fun SpoolRecovery.toOperationalBacklog(): OperationalBacklog {
    var pending = 0uL
    streams.forEach { stream ->
        require(stream.cloudDurableChunkCount <= stream.durableChunkCount) {
            "Cloud-durable count exceeded the durable chunk count"
        }
        val streamPending = stream.durableChunkCount - stream.cloudDurableChunkCount
        require(streamPending <= ULong.MAX_VALUE - pending) { "Pending chunk count overflow" }
        pending += streamPending
    }
    return OperationalBacklog(
        pendingChunkCount = pending,
        retainedPayloadBytes = retainedBytes,
    )
}

private fun SpoolStoreFailure.toOperationalFailure(
    operation: OperationalRuntimeOperation,
): ExpectedFailure = operationalFailure(
    operation,
    code,
    categoryForStorageCode(code),
    retryable,
)

private fun OperationalLeaseResult.failureOr(fallback: ExpectedFailure): ExpectedFailure = when (this) {
    is OperationalLeaseResult.Completed -> fallback
    is OperationalLeaseResult.Failed -> failure
    is OperationalLeaseResult.OutcomeUnknown -> failure
}

private fun operationalFailure(
    operation: OperationalRuntimeOperation,
    code: String,
    category: FailureCategory,
    retryable: Boolean,
): ExpectedFailure = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
    correlationId = operation.hostOperation.correlationId,
)

private fun categoryForStorageCode(code: String): FailureCategory = when {
    code.contains("CORRUPT") || code.contains("INVALID") || code.contains("MISSING") ->
        FailureCategory.CORRUPT

    code.contains("EXHAUSTED") || code.contains("QUOTA") -> FailureCategory.RESOURCE_EXHAUSTED
    code.contains("ACCESS_DENIED") || code.contains("AUTHENTICATION_REQUIRED") ->
        FailureCategory.PERMISSION

    code.contains("ALREADY_OPEN") -> FailureCategory.REJECTED_POLICY
    code.contains("KEY_UNAVAILABLE") || code.contains("KEY_INVALIDATED") -> FailureCategory.UNAVAILABLE
    else -> FailureCategory.UNAVAILABLE
}

private fun retryableStorageCode(code: String): Boolean =
    code.contains("BUSY") ||
        code.contains("IO_FAILED") ||
        code.contains("UNAVAILABLE") ||
        code.contains("EXHAUSTED") ||
        code.contains("ALREADY_OPEN")
