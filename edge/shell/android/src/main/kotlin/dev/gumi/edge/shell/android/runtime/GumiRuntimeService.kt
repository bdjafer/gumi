package dev.gumi.edge.shell.android.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import dev.gumi.edge.runtime.host.RuntimeHostExecutionState
import dev.gumi.edge.runtime.host.RuntimeHostProjection
import dev.gumi.edge.runtime.host.RuntimeHostTransportState
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.shell.android.MainActivity
import dev.gumi.edge.shell.android.R
import java.util.concurrent.atomic.AtomicLong

/** Android composition root only; all lifecycle transitions remain owned by RuntimeHost. */
class GumiRuntimeService : Service() {
    private lateinit var processOwner: AndroidRuntimeProcessOwner
    private lateinit var endpoint: PlatformServiceEndpoint
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val application = application as GumiRuntimeApplication
        processOwner = application.runtimeOwner
        endpoint = PlatformServiceEndpoint(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val decoded = AndroidRuntimeServiceIntents.decode(intent)) {
            is AndroidRuntimeCommandDecodeResult.Invalid -> {
                endpoint.commandSettled(
                    startId,
                    stopService = !processOwner.recordInvalidDelivery(decoded.failure),
                )
            }

            is AndroidRuntimeCommandDecodeResult.Valid -> {
                when (val admission = processOwner.submit(decoded.request, startId, endpoint)) {
                    AndroidRuntimeAdmissionResult.Accepted -> Unit
                    is AndroidRuntimeAdmissionResult.Rejected -> endpoint.commandSettled(
                        startId,
                        stopService = !admission.serviceStillNeeded,
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        processOwner.endpointDestroyed(endpoint)
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        internal val ownerProjection get() = processOwner.projection
        internal val hostProjection get() = processOwner.hostProjection
    }
}

private class PlatformServiceEndpoint(
    private val service: GumiRuntimeService,
) : AndroidRuntimeServiceEndpoint {
    override val token: Long = endpointSequence.incrementAndGet()
    private var foregroundEntered = false

    @Synchronized
    override fun enterForeground(
        state: AndroidRuntimeNotificationState,
        correlationId: CorrelationId,
    ): AndroidPlatformForegroundStartResult {
        if (!AndroidRuntimePermissions.areGranted(service)) {
            return preflightDenied(
                androidRuntimeFailure(
                    category = FailureCategory.PERMISSION,
                    code = "ANDROID_FOREGROUND_RUNTIME_PERMISSION_DENIED",
                    retryable = false,
                    correlationId = correlationId,
                ),
            )
        }

        val notification = try {
            ensureNotificationChannel(correlationId)?.let { return preflightDenied(it) }
            buildNotification(state)
        } catch (_: RuntimeException) {
            return preflightDenied(
                androidRuntimeFailure(
                    category = FailureCategory.INTERNAL,
                    code = "ANDROID_NOTIFICATION_PREPARATION_FAILED",
                    retryable = true,
                    correlationId = correlationId,
                ),
            )
        }

        return try {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            foregroundEntered = true
            AndroidPlatformForegroundStartResult.Entered
        } catch (_: SecurityException) {
            promotionDeniedOrUnknown(
                androidRuntimeFailure(
                    category = FailureCategory.PERMISSION,
                    code = "ANDROID_FOREGROUND_SECURITY_DENIED",
                    retryable = false,
                    correlationId = correlationId,
                ),
            )
        } catch (_: IllegalArgumentException) {
            promotionDeniedOrUnknown(
                androidRuntimeFailure(
                    category = FailureCategory.INCOMPATIBLE,
                    code = "ANDROID_FOREGROUND_TYPE_INVALID",
                    retryable = false,
                    correlationId = correlationId,
                ),
            )
        } catch (_: RuntimeException) {
            AndroidPlatformForegroundStartResult.OutcomeUnknown(
                androidRuntimeFailure(
                    category = FailureCategory.INTERNAL,
                    code = "ANDROID_FOREGROUND_ENTRY_OUTCOME_UNKNOWN",
                    retryable = false,
                    correlationId = correlationId,
                ),
            )
        }
    }

    @Synchronized
    override fun leaveForeground(
        correlationId: CorrelationId,
    ): AndroidPlatformForegroundStopResult = try {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        foregroundEntered = false
        AndroidPlatformForegroundStopResult.Released
    } catch (_: RuntimeException) {
        AndroidPlatformForegroundStopResult.OutcomeUnknown(
            androidRuntimeFailure(
                category = FailureCategory.INTERNAL,
                code = "ANDROID_FOREGROUND_RELEASE_OUTCOME_UNKNOWN",
                retryable = false,
                correlationId = correlationId,
            ),
        )
    }

    override fun commandSettled(startId: Int, stopService: Boolean) {
        if (stopService) service.stopSelfResult(startId)
    }

    private fun preflightDenied(
        failure: ExpectedFailure,
    ): AndroidPlatformForegroundStartResult = if (foregroundEntered) {
        AndroidPlatformForegroundStartResult.OutcomeUnknown(failure)
    } else {
        AndroidPlatformForegroundStartResult.Denied(failure)
    }

    private fun promotionDeniedOrUnknown(
        failure: ExpectedFailure,
    ): AndroidPlatformForegroundStartResult = if (foregroundEntered) {
        AndroidPlatformForegroundStartResult.OutcomeUnknown(failure)
    } else {
        AndroidPlatformForegroundStartResult.Denied(failure)
    }

    private fun ensureNotificationChannel(correlationId: CorrelationId): ExpectedFailure? {
        val manager = service.getSystemService(NotificationManager::class.java)
            ?: return androidRuntimeFailure(
                category = FailureCategory.UNAVAILABLE,
                code = "ANDROID_NOTIFICATION_MANAGER_UNAVAILABLE",
                retryable = true,
                correlationId = correlationId,
            )
        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.runtime_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = service.getString(R.string.runtime_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            return androidRuntimeFailure(
                category = FailureCategory.PERMISSION,
                code = "ANDROID_RUNTIME_NOTIFICATION_VISIBILITY_DENIED",
                retryable = false,
                correlationId = correlationId,
            )
        }
        return null
    }

    private fun buildNotification(state: AndroidRuntimeNotificationState): Notification {
        val openIntent = PendingIntent.getActivity(
            service,
            OPEN_REQUEST_CODE,
            Intent(service, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopCommand = AndroidRuntimeServiceIntents.notificationStop(service)
        val stopIntent = PendingIntent.getService(
            service,
            stopCommand.requestCode,
            stopCommand.intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gumi_runtime)
            .setContentTitle(service.getString(R.string.runtime_notification_title))
            .setContentText(androidRuntimeNotificationText(state))
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(service, R.drawable.ic_gumi_runtime),
                    service.getString(R.string.runtime_notification_open_action),
                    openIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(service, R.drawable.ic_gumi_runtime),
                    service.getString(R.string.runtime_notification_stop_action),
                    stopIntent,
                ).build(),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestImmediateForegroundVisibility(builder)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "gumi_runtime_connected_device_v1"
        private const val NOTIFICATION_ID = 10_071
        private const val OPEN_REQUEST_CODE = 10_072
        private val endpointSequence = AtomicLong(0L)
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun requestImmediateForegroundVisibility(builder: Notification.Builder) {
    builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
}

internal fun androidRuntimeRequestsImmediateVisibility(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.S

/** RuntimeHost has no capture/backlog authority, so those facts never become inferred positives. */
internal fun androidRuntimeNotificationText(state: AndroidRuntimeNotificationState): String {
    val connection = when (state) {
        AndroidRuntimeNotificationState.Starting -> "checking"
        is AndroidRuntimeNotificationState.Running -> state.projection.connectionLabel()
    }
    return "Connection: $connection | capture: unverified | backlog: unavailable"
}

private fun RuntimeHostProjection.connectionLabel(): String = when {
    execution == RuntimeHostExecutionState.OUTCOME_UNKNOWN -> "outcome unknown"
    transport == RuntimeHostTransportState.READY -> "ready"
    transport == RuntimeHostTransportState.CONNECTING -> "connecting"
    transport == RuntimeHostTransportState.DEGRADED -> "degraded"
    else -> "disconnected"
}
