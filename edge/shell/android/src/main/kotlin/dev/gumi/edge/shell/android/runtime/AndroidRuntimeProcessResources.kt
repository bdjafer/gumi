package dev.gumi.edge.shell.android.runtime

import dev.gumi.edge.runtime.operational.OperationalRuntimeRegistry
import dev.gumi.edge.runtime.operational.ProcessGlobalOperationalStorageOwner
import dev.gumi.edge.runtime.operational.ProcessGlobalStorageCloseResult
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import kotlin.coroutines.cancellation.CancellationException

internal sealed interface AndroidRuntimeProcessResourceCloseResult {
    data object Closed : AndroidRuntimeProcessResourceCloseResult

    data class Failed(val failure: ExpectedFailure) : AndroidRuntimeProcessResourceCloseResult

    data class OutcomeUnknown(val failure: ExpectedFailure) : AndroidRuntimeProcessResourceCloseResult
}

/** Process resources below RuntimeHost, closed after host cleanup and before forced host teardown. */
internal fun interface AndroidRuntimeProcessResources {
    suspend fun close(): AndroidRuntimeProcessResourceCloseResult
}

internal object EmptyAndroidRuntimeProcessResources : AndroidRuntimeProcessResources {
    override suspend fun close(): AndroidRuntimeProcessResourceCloseResult =
        AndroidRuntimeProcessResourceCloseResult.Closed
}

/**
 * Exact scalable operational teardown order: device registry first, then its process-global spool.
 * No device implementation is imported here; concrete registrations remain a composition-root concern.
 */
internal class AndroidOperationalProcessResources(
    private val registry: OperationalRuntimeRegistry,
    private val storage: ProcessGlobalOperationalStorageOwner,
) : AndroidRuntimeProcessResources {
    override suspend fun close(): AndroidRuntimeProcessResourceCloseResult {
        try {
            registry.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            return AndroidRuntimeProcessResourceCloseResult.Failed(
                androidRuntimeFailure(
                    FailureCategory.REJECTED_POLICY,
                    "ANDROID_OPERATIONAL_REGISTRY_STILL_ACTIVE",
                    retryable = true,
                ),
            )
        } catch (_: Throwable) {
            return AndroidRuntimeProcessResourceCloseResult.OutcomeUnknown(
                androidRuntimeFailure(
                    FailureCategory.INTERNAL,
                    "ANDROID_OPERATIONAL_REGISTRY_CLOSE_OUTCOME_UNKNOWN",
                    retryable = false,
                ),
            )
        }

        return when (val result = storage.close()) {
            is ProcessGlobalStorageCloseResult.Closed ->
                AndroidRuntimeProcessResourceCloseResult.Closed

            is ProcessGlobalStorageCloseResult.Failed ->
                AndroidRuntimeProcessResourceCloseResult.Failed(result.failure)

            is ProcessGlobalStorageCloseResult.OutcomeUnknown ->
                AndroidRuntimeProcessResourceCloseResult.OutcomeUnknown(result.failure)
        }
    }
}
