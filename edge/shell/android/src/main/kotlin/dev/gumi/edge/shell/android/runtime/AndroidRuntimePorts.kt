package dev.gumi.edge.shell.android.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.gumi.edge.runtime.host.RuntimeHostAssociationState
import dev.gumi.edge.runtime.host.RuntimeHostCleanupRequest
import dev.gumi.edge.runtime.host.RuntimeHostCleanupResult
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.host.RuntimeHostPermissionState
import dev.gumi.edge.runtime.host.RuntimeHostPrerequisitePort
import dev.gumi.edge.runtime.host.RuntimeHostPrerequisiteResult
import dev.gumi.edge.runtime.host.RuntimeHostPrerequisites
import dev.gumi.edge.runtime.host.RuntimeHostPresenceState
import dev.gumi.edge.runtime.host.RuntimeHostRecoveryPort
import dev.gumi.edge.runtime.host.RuntimeHostRehydrationResult
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory

/** Association/presence is a replaceable port; transport discovery is not ownership evidence. */
internal interface AndroidRuntimeAssociationEvidencePort {
    fun snapshot(): AndroidRuntimeAssociationEvidence
}

internal data class AndroidRuntimeAssociationEvidence(
    val association: RuntimeHostAssociationState,
    val presence: RuntimeHostPresenceState,
)

/**
 * Companion association is intentionally not fabricated by this tranche. Until that adapter is
 * composed, the operational host fails closed before acquiring a foreground execution lease.
 */
internal object UnavailableAndroidRuntimeAssociationEvidence :
    AndroidRuntimeAssociationEvidencePort {
    override fun snapshot(): AndroidRuntimeAssociationEvidence = AndroidRuntimeAssociationEvidence(
        association = RuntimeHostAssociationState.UNKNOWN,
        presence = RuntimeHostPresenceState.UNKNOWN,
    )
}

internal class AndroidRuntimePrerequisitePort(
    private val context: Context,
    private val associationEvidence: AndroidRuntimeAssociationEvidencePort,
) : RuntimeHostPrerequisitePort {
    override suspend fun inspect(operation: RuntimeHostOperation): RuntimeHostPrerequisiteResult =
        try {
            val evidence = associationEvidence.snapshot()
            RuntimeHostPrerequisiteResult.Observed(
                operation,
                RuntimeHostPrerequisites(
                    association = evidence.association,
                    presence = evidence.presence,
                    permission = if (AndroidRuntimePermissions.areGranted(context)) {
                        RuntimeHostPermissionState.GRANTED
                    } else {
                        RuntimeHostPermissionState.DENIED
                    },
                ),
            )
        } catch (_: RuntimeException) {
            RuntimeHostPrerequisiteResult.Failed(
                operation,
                androidRuntimeFailure(
                    category = FailureCategory.INTERNAL,
                    code = "ANDROID_RUNTIME_PREREQUISITE_INSPECTION_FAILED",
                    retryable = true,
                    correlationId = operation.correlationId,
                ),
            )
        }
}

internal object AndroidRuntimePermissions {
    fun required(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun areGranted(context: Context): Boolean = required().all { permission ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Safe offline boundary: no transport or durable recovery resource is opened. This prevents the
 * Android service scaffold from being mistaken for a qualified operational pipeline.
 */
internal object UnavailableAndroidRuntimeRecoveryPort : RuntimeHostRecoveryPort {
    override suspend fun rehydrateAndReconcile(
        operation: RuntimeHostOperation,
    ): RuntimeHostRehydrationResult = RuntimeHostRehydrationResult.Failed(
        operation,
        unsupportedRecoveryFailure(operation),
    )

    override suspend fun cleanup(request: RuntimeHostCleanupRequest): RuntimeHostCleanupResult =
        RuntimeHostCleanupResult.Cleaned(request.operation)

    private fun unsupportedRecoveryFailure(operation: RuntimeHostOperation): ExpectedFailure =
        androidRuntimeFailure(
            category = FailureCategory.UNAVAILABLE,
            code = "ANDROID_RUNTIME_RECOVERY_NOT_COMPOSED",
            retryable = false,
            correlationId = operation.correlationId,
        )
}
