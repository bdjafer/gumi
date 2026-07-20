package dev.gumi.edge.shell.android.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal data class AndroidRuntimePendingCommand(
    val intent: Intent,
    val requestCode: Int,
)

internal data class AndroidRuntimePreparedCommand(
    val intent: Intent,
    val commandId: CommandId,
    val correlationId: CorrelationId,
    val foreground: Boolean,
)

internal object AndroidRuntimeServiceIntents {
    private val pendingIntentSequence = AtomicInteger(10_000)

    fun prepareExplicitStart(context: Context): AndroidRuntimePreparedCommand = commandIntent(
        context,
        AndroidRuntimeServiceContract.ACTION_START_EXPLICIT,
        nextIdentity("explicit-start"),
        foreground = true,
    )

    fun automaticPresenceStart(context: Context): Intent = commandIntent(
        context,
        AndroidRuntimeServiceContract.ACTION_START_AUTOMATIC_PRESENCE,
        nextIdentity("presence-start"),
        foreground = true,
    ).intent

    fun automaticRecoveryStart(context: Context): Intent = commandIntent(
        context,
        AndroidRuntimeServiceContract.ACTION_START_AUTOMATIC_RECOVERY,
        nextIdentity("recovery-start"),
        foreground = true,
    ).intent

    fun prepareExplicitStop(context: Context): AndroidRuntimePreparedCommand = commandIntent(
        context,
        AndroidRuntimeServiceContract.ACTION_STOP_EXPLICIT,
        nextIdentity("explicit-stop"),
        foreground = false,
    )

    fun notificationStop(context: Context): AndroidRuntimePendingCommand =
        AndroidRuntimePendingCommand(
            intent = prepareExplicitStop(context).intent,
            requestCode = pendingIntentSequence.updateAndGet { current ->
                if (current == Int.MAX_VALUE) 10_000 else current + 1
            },
        )

    fun decode(intent: Intent?): AndroidRuntimeCommandDecodeResult = try {
        AndroidRuntimeServiceContract.decode(
            action = intent?.action,
            commandId = intent?.getStringExtra(AndroidRuntimeServiceContract.EXTRA_COMMAND_ID),
            correlationId = intent?.getStringExtra(
                AndroidRuntimeServiceContract.EXTRA_CORRELATION_ID,
            ),
        )
    } catch (_: RuntimeException) {
        AndroidRuntimeCommandDecodeResult.Invalid(
            androidRuntimeFailure(
                category = FailureCategory.REJECTED_POLICY,
                code = "ANDROID_RUNTIME_INTENT_EXTRA_EXTRACTION_FAILED",
                retryable = false,
            ),
        )
    }

    private fun commandIntent(
        context: Context,
        action: String,
        identity: CommandIdentity,
        foreground: Boolean,
    ): AndroidRuntimePreparedCommand = AndroidRuntimePreparedCommand(
        intent = Intent(context, GumiRuntimeService::class.java)
            .setAction(action)
            .putExtra(AndroidRuntimeServiceContract.EXTRA_COMMAND_ID, identity.commandId.value)
            .putExtra(
                AndroidRuntimeServiceContract.EXTRA_CORRELATION_ID,
                identity.correlationId.value,
            ),
        commandId = identity.commandId,
        correlationId = identity.correlationId,
        foreground = foreground,
    )

    private fun nextIdentity(kind: String): CommandIdentity {
        return CommandIdentity(
            commandId = CommandId("android-runtime-$kind-${UUID.randomUUID()}"),
            correlationId = CorrelationId(
                "android-runtime-$kind-correlation-${UUID.randomUUID()}",
            ),
        )
    }

    private data class CommandIdentity(
        val commandId: CommandId,
        val correlationId: CorrelationId,
    )
}

internal sealed interface AndroidRuntimeServiceLaunchResult {
    data class Delivered(val component: ComponentName) : AndroidRuntimeServiceLaunchResult

    data class Rejected(val failure: ExpectedFailure) : AndroidRuntimeServiceLaunchResult

    data class OutcomeUnknown(val failure: ExpectedFailure) : AndroidRuntimeServiceLaunchResult
}

/** Activity/companion adapter boundary; it never bypasses the Service command protocol. */
internal object AndroidRuntimeServiceLauncher {
    fun prepareExplicitStart(context: Context): AndroidRuntimePreparedCommand =
        AndroidRuntimeServiceIntents.prepareExplicitStart(context)

    fun prepareExplicitStop(context: Context): AndroidRuntimePreparedCommand =
        AndroidRuntimeServiceIntents.prepareExplicitStop(context)

    /** Re-delivering this exact value preserves the command identity after an unknown launch. */
    fun deliver(
        context: Context,
        command: AndroidRuntimePreparedCommand,
    ): AndroidRuntimeServiceLaunchResult {
        if (command.foreground && !AndroidRuntimePermissions.areGranted(context)) {
            return AndroidRuntimeServiceLaunchResult.Rejected(
                launchFailure(
                    FailureCategory.PERMISSION,
                    "ANDROID_RUNTIME_PERMISSION_DENIED",
                    retryable = false,
                ),
            )
        }
        return launch(context, command.intent, foreground = command.foreground)
    }

    private fun launch(
        context: Context,
        intent: Intent,
        foreground: Boolean,
    ): AndroidRuntimeServiceLaunchResult = try {
        val component = if (foreground) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        if (component == null) {
            AndroidRuntimeServiceLaunchResult.Rejected(
                launchFailure(
                    FailureCategory.UNAVAILABLE,
                    "ANDROID_RUNTIME_SERVICE_COMPONENT_UNAVAILABLE",
                    retryable = true,
                ),
            )
        } else {
            AndroidRuntimeServiceLaunchResult.Delivered(component)
        }
    } catch (throwable: RuntimeException) {
        mapAndroidRuntimeLaunchFailure(throwable, foreground)
    }

    private fun launchFailure(
        category: FailureCategory,
        code: String,
        retryable: Boolean,
    ): ExpectedFailure = androidRuntimeFailure(category, code, retryable)
}

internal fun mapAndroidRuntimeLaunchFailure(
    throwable: RuntimeException,
    foreground: Boolean,
): AndroidRuntimeServiceLaunchResult = when (throwable) {
    is SecurityException -> AndroidRuntimeServiceLaunchResult.Rejected(
        androidRuntimeFailure(
            FailureCategory.PERMISSION,
            "ANDROID_RUNTIME_SERVICE_LAUNCH_SECURITY_DENIED",
            retryable = false,
        ),
    )

    is IllegalStateException -> AndroidRuntimeServiceLaunchResult.Rejected(
        androidRuntimeFailure(
            FailureCategory.REJECTED_POLICY,
            if (foreground) {
                "ANDROID_RUNTIME_FOREGROUND_START_NOT_ALLOWED"
            } else {
                "ANDROID_RUNTIME_STOP_DELIVERY_NOT_ALLOWED"
            },
            retryable = true,
        ),
    )

    else -> AndroidRuntimeServiceLaunchResult.OutcomeUnknown(
        androidRuntimeFailure(
            FailureCategory.INTERNAL,
            "ANDROID_RUNTIME_SERVICE_LAUNCH_OUTCOME_UNKNOWN",
            retryable = false,
        ),
    )
}
