package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

internal fun interface MonotonicMillisClock {
    fun now(): Long
}

internal interface OmiCv1ApplicationImage0UpdateSession {
    suspend fun upload(
        imageBytes: ByteArray,
        mode: OmiCv1ApplicationUploadMode,
        onProgress: (sent: Int, total: Int) -> Unit,
    )
    suspend fun inspect(): FirmwareImageStateInspection
    suspend fun eraseInactiveApplicationSlot()
    suspend fun confirm(mcubootImageHash: FirmwareImageHash)
    suspend fun requestReset(): Boolean
    fun cancel()
    fun release()
}

internal fun interface OmiCv1ApplicationImage0UpdateSessionFactory {
    fun open(endpoint: EndpointCandidate): OmiCv1ApplicationImage0UpdateSession
}

internal class OmiCv1ApplicationImage0UpdateExecutor(
    private val sessions: OmiCv1ApplicationImage0UpdateSessionFactory,
    private val clock: MonotonicMillisClock,
) {
    suspend fun execute(
        authorization: OmiCv1ApplicationUpdateAuthorization,
        onProgress: (OmiCv1ApplicationUpdateProgress) -> Unit,
    ): OmiCv1ApplicationUpdatePendingValidation {
        val plan = authorization.consume(clock.now())
        onProgress(OmiCv1ApplicationUpdateProgress(OmiCv1ApplicationUpdateStage.PREPARING))
        val session = sessions.open(plan.endpoint)
        val released = AtomicBoolean(false)
        fun releaseOnce() {
            if (released.compareAndSet(false, true)) session.release()
        }

        return try {
            onProgress(
                OmiCv1ApplicationUpdateProgress(
                    OmiCv1ApplicationUpdateStage.VERIFYING_PREFLIGHT_STATE,
                ),
            )
            OmiCv1ApplicationUpdatePlanner.requirePreflightState(session.inspect(), plan)

            onProgress(
                OmiCv1ApplicationUpdateProgress(
                    stage = OmiCv1ApplicationUpdateStage.UPLOADING,
                    bytesSent = 0,
                    totalBytes = plan.artifactEvidence.fileSizeBytes,
                ),
            )
            session.upload(plan.copyImageBytes(), plan.release.uploadMode) { sent, total ->
                onProgress(
                    OmiCv1ApplicationUpdateProgress(
                        stage = OmiCv1ApplicationUpdateStage.UPLOADING,
                        bytesSent = sent,
                        totalBytes = total,
                    ),
                )
            }

            onProgress(OmiCv1ApplicationUpdateProgress(OmiCv1ApplicationUpdateStage.VERIFYING_STAGED_STATE))
            val staged = session.inspect()
            try {
                OmiCv1ApplicationUpdatePlanner.requireStagedState(staged, plan)
            } catch (error: OmiCv1ApplicationUpdateException) {
                throw OmiCv1ApplicationUpdateException(
                    OmiCv1ApplicationUpdateFailureCode.STAGED_STATE_REJECTED,
                    error.message ?: "Staged image state was rejected",
                    error,
                )
            }

            onProgress(OmiCv1ApplicationUpdateProgress(OmiCv1ApplicationUpdateStage.CONFIRMING))
            session.confirm(plan.release.target.mcubootImageHash)

            onProgress(OmiCv1ApplicationUpdateProgress(OmiCv1ApplicationUpdateStage.VERIFYING_CONFIRMED_STATE))
            val confirmed = session.inspect()
            try {
                OmiCv1ApplicationUpdatePlanner.requireConfirmedState(confirmed, plan)
            } catch (error: OmiCv1ApplicationUpdateException) {
                throw OmiCv1ApplicationUpdateException(
                    OmiCv1ApplicationUpdateFailureCode.CONFIRMED_STATE_REJECTED,
                    error.message ?: "Confirmed image state was rejected",
                    error,
                )
            }

            onProgress(OmiCv1ApplicationUpdateProgress(OmiCv1ApplicationUpdateStage.REQUESTING_REBOOT))
            val resetResponseObserved = session.requestReset()
            onProgress(
                OmiCv1ApplicationUpdateProgress(
                    OmiCv1ApplicationUpdateStage.AWAITING_POST_REBOOT_VALIDATION,
                ),
            )
            OmiCv1ApplicationUpdatePendingValidation(
                planId = plan.planId,
                endpoint = plan.endpoint,
                expectedApplicationHash = plan.release.target.mcubootImageHash,
                expectedNetworkHash = plan.release.source.networkHash,
                expectedMcubootVersion = plan.release.target.mcubootVersion,
                networkEvidencePolicy = plan.release.source.networkEvidencePolicy,
                resetResponseObserved = resetResponseObserved,
            )
        } catch (error: CancellationException) {
            session.cancel()
            throw error
        } catch (error: OmiCv1ApplicationUpdateException) {
            throw error
        } catch (error: Exception) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED,
                "Application image-0 update transport failed (${error::class.simpleName})",
                error,
            )
        } finally {
            releaseOnce()
        }
    }
}
