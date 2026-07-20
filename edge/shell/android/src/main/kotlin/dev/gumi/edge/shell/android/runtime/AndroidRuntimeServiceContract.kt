package dev.gumi.edge.shell.android.runtime

import dev.gumi.edge.runtime.host.RuntimeHostRequest
import dev.gumi.edge.runtime.host.RuntimeHostStartOrigin
import dev.gumi.edge.runtime.host.RuntimeHostStopOrigin
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode

/** Explicit, package-private protocol accepted by [GumiRuntimeService]. */
internal object AndroidRuntimeServiceContract {
    const val ACTION_START_EXPLICIT =
        "dev.gumi.edge.shell.android.runtime.action.START_EXPLICIT"
    const val ACTION_START_AUTOMATIC_PRESENCE =
        "dev.gumi.edge.shell.android.runtime.action.START_AUTOMATIC_PRESENCE"
    const val ACTION_START_AUTOMATIC_RECOVERY =
        "dev.gumi.edge.shell.android.runtime.action.START_AUTOMATIC_RECOVERY"
    const val ACTION_STOP_EXPLICIT =
        "dev.gumi.edge.shell.android.runtime.action.STOP_EXPLICIT"
    const val ACTION_STOP_PREREQUISITE_LOST =
        "dev.gumi.edge.shell.android.runtime.action.STOP_PREREQUISITE_LOST"

    const val EXTRA_COMMAND_ID = "dev.gumi.edge.shell.android.runtime.extra.COMMAND_ID"
    const val EXTRA_CORRELATION_ID = "dev.gumi.edge.shell.android.runtime.extra.CORRELATION_ID"

    private val supportedActions = setOf(
        ACTION_START_EXPLICIT,
        ACTION_START_AUTOMATIC_PRESENCE,
        ACTION_START_AUTOMATIC_RECOVERY,
        ACTION_STOP_EXPLICIT,
        ACTION_STOP_PREREQUISITE_LOST,
    )

    fun decode(
        action: String?,
        commandId: String?,
        correlationId: String?,
    ): AndroidRuntimeCommandDecodeResult {
        if (action !in supportedActions) {
            return AndroidRuntimeCommandDecodeResult.Invalid(
                contractFailure("ANDROID_RUNTIME_INTENT_ACTION_INVALID"),
            )
        }
        val id = opaqueIdOrNull(commandId)?.let(::CommandId)
            ?: return AndroidRuntimeCommandDecodeResult.Invalid(
                contractFailure("ANDROID_RUNTIME_COMMAND_ID_INVALID"),
            )
        val correlation = opaqueIdOrNull(correlationId)?.let(::CorrelationId)
            ?: return AndroidRuntimeCommandDecodeResult.Invalid(
                contractFailure("ANDROID_RUNTIME_CORRELATION_ID_INVALID"),
            )

        val request = when (action) {
            ACTION_START_EXPLICIT -> RuntimeHostRequest.Start(
                id,
                correlation,
                RuntimeHostStartOrigin.EXPLICIT_USER,
            )

            ACTION_START_AUTOMATIC_PRESENCE -> RuntimeHostRequest.Start(
                id,
                correlation,
                RuntimeHostStartOrigin.AUTOMATIC_PRESENCE,
            )

            ACTION_START_AUTOMATIC_RECOVERY -> RuntimeHostRequest.Start(
                id,
                correlation,
                RuntimeHostStartOrigin.AUTOMATIC_RECOVERY,
            )

            ACTION_STOP_EXPLICIT -> RuntimeHostRequest.Stop(
                id,
                correlation,
                RuntimeHostStopOrigin.EXPLICIT_USER,
            )

            ACTION_STOP_PREREQUISITE_LOST -> RuntimeHostRequest.Stop(
                id,
                correlation,
                RuntimeHostStopOrigin.PREREQUISITE_LOST,
            )

            else -> error("supported action was not decoded")
        }
        return AndroidRuntimeCommandDecodeResult.Valid(request)
    }
}

internal sealed interface AndroidRuntimeCommandDecodeResult {
    data class Valid(val request: RuntimeHostRequest) : AndroidRuntimeCommandDecodeResult

    data class Invalid(val failure: ExpectedFailure) : AndroidRuntimeCommandDecodeResult
}

private fun opaqueIdOrNull(value: String?): String? = value?.takeIf {
    it.isNotBlank() && it == it.trim() && it.length <= 200 && it.none(Char::isISOControl)
}

internal fun androidRuntimeFailure(
    category: FailureCategory,
    code: String,
    retryable: Boolean,
    correlationId: CorrelationId? = null,
): ExpectedFailure = ExpectedFailure(
    category = category,
    code = FailureCode(code),
    retryable = retryable,
    correlationId = correlationId,
)

private fun contractFailure(code: String): ExpectedFailure = androidRuntimeFailure(
    category = FailureCategory.REJECTED_POLICY,
    code = code,
    retryable = false,
)
