package dev.gumi.edge.sdk.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FirmwareMaintenanceWorkflowTest {
    @Test
    fun `complete workflow retains exact evidence chain`() {
        val artifact = artifact()
        var state = FirmwareMaintenanceState(artifact)

        state = state.accept(
            FirmwareMaintenanceEvent.SafetyReviewAccepted(
                FirmwareSafetyReviewReceipt("gumi-safety-v1", 1),
            ),
        )
        state = state.accept(
            FirmwareMaintenanceEvent.PreflightPassed(
                FirmwarePreflightReceipt("preflight-1", artifact.sha256, 2),
            ),
        )
        state = state.accept(
            FirmwareMaintenanceEvent.ExactArtifactAuthorized(
                FirmwareArtifactAuthorization(artifact.artifactId, artifact.sha256, 3),
            ),
        )
        state = state.accept(FirmwareMaintenanceEvent.ApplyStarted)
        state = state.accept(FirmwareMaintenanceEvent.TransferAdvanced(512uL))
        state = state.accept(FirmwareMaintenanceEvent.TransferAdvanced(1024uL))
        state = state.accept(FirmwareMaintenanceEvent.ApplyAcceptedForRestart)
        state = state.accept(FirmwareMaintenanceEvent.ValidationStarted)
        state = state.accept(
            FirmwareMaintenanceEvent.ValidationPassed(
                FirmwareValidationReceipt("post-reboot-1", 4),
            ),
        )

        assertEquals(FirmwareMaintenanceStage.COMPLETE, state.stage)
        assertEquals(1024uL, state.transferredBytes)
        assertEquals("preflight-1", state.preflight?.evidenceId)
        assertEquals("post-reboot-1", state.validation?.evidenceId)
    }

    @Test
    fun `wrong artifact authorization is rejected without changing state`() {
        val artifact = artifact()
        var state = FirmwareMaintenanceState(artifact)
        state = state.accept(
            FirmwareMaintenanceEvent.SafetyReviewAccepted(
                FirmwareSafetyReviewReceipt("gumi-safety-v1", 1),
            ),
        )
        state = state.accept(
            FirmwareMaintenanceEvent.PreflightPassed(
                FirmwarePreflightReceipt("preflight-1", artifact.sha256, 2),
            ),
        )

        val result = assertIs<FirmwareMaintenanceTransition.Rejected>(
            FirmwareMaintenanceWorkflow.reduce(
                state,
                FirmwareMaintenanceEvent.ExactArtifactAuthorized(
                    FirmwareArtifactAuthorization(
                        artifact.artifactId,
                        "b".repeat(64),
                        3,
                    ),
                ),
            ),
        )

        assertEquals("AUTHORIZED_ARTIFACT_MISMATCH", result.reasonCode)
        assertEquals(state, result.state)
    }

    @Test
    fun `transfer progress is monotonic and bounded`() {
        val applying = readyToApply().accept(FirmwareMaintenanceEvent.ApplyStarted)
        val advanced = applying.accept(FirmwareMaintenanceEvent.TransferAdvanced(800uL))

        val regressed = assertIs<FirmwareMaintenanceTransition.Rejected>(
            FirmwareMaintenanceWorkflow.reduce(
                advanced,
                FirmwareMaintenanceEvent.TransferAdvanced(799uL),
            ),
        )
        val oversized = assertIs<FirmwareMaintenanceTransition.Rejected>(
            FirmwareMaintenanceWorkflow.reduce(
                advanced,
                FirmwareMaintenanceEvent.TransferAdvanced(1025uL),
            ),
        )

        assertEquals("TRANSFER_PROGRESS_REGRESSED", regressed.reasonCode)
        assertEquals("TRANSFER_PROGRESS_EXCEEDS_ARTIFACT", oversized.reasonCode)
        val incomplete = assertIs<FirmwareMaintenanceTransition.Rejected>(
            FirmwareMaintenanceWorkflow.reduce(
                advanced,
                FirmwareMaintenanceEvent.ApplyAcceptedForRestart,
            ),
        )
        assertEquals("TRANSFER_INCOMPLETE", incomplete.reasonCode)
    }

    @Test
    fun `evidence timestamps cannot regress across authority boundaries`() {
        val artifact = artifact()
        val reviewed = FirmwareMaintenanceState(artifact).accept(
            FirmwareMaintenanceEvent.SafetyReviewAccepted(
                FirmwareSafetyReviewReceipt("gumi-safety-v1", 10),
            ),
        )
        val stalePreflight = assertIs<FirmwareMaintenanceTransition.Rejected>(
            FirmwareMaintenanceWorkflow.reduce(
                reviewed,
                FirmwareMaintenanceEvent.PreflightPassed(
                    FirmwarePreflightReceipt("stale", artifact.sha256, 9),
                ),
            ),
        )
        assertEquals("PREFLIGHT_TIME_REGRESSED", stalePreflight.reasonCode)

        val preflight = reviewed.accept(
            FirmwareMaintenanceEvent.PreflightPassed(
                FirmwarePreflightReceipt("fresh", artifact.sha256, 11),
            ),
        )
        val staleAuthorization = assertIs<FirmwareMaintenanceTransition.Rejected>(
            FirmwareMaintenanceWorkflow.reduce(
                preflight,
                FirmwareMaintenanceEvent.ExactArtifactAuthorized(
                    FirmwareArtifactAuthorization(artifact.artifactId, artifact.sha256, 10),
                ),
            ),
        )
        assertEquals(
            "ARTIFACT_AUTHORIZATION_TIME_REGRESSED",
            staleAuthorization.reasonCode,
        )
    }

    @Test
    fun `failure is terminal and keeps outcome uncertainty explicit`() {
        val failed = assertIs<FirmwareMaintenanceTransition.Accepted>(
            FirmwareMaintenanceWorkflow.reduce(
                FirmwareMaintenanceState(artifact()),
                FirmwareMaintenanceEvent.Failed(
                    FirmwareMaintenanceFailure("TRANSPORT_OUTCOME_UNKNOWN", true),
                ),
            ),
        ).state

        assertEquals(FirmwareMaintenanceStage.FAILED, failed.stage)
        assertEquals(true, failed.failure?.outcomeMayBeUnknown)
        val retry = assertIs<FirmwareMaintenanceTransition.Rejected>(
            FirmwareMaintenanceWorkflow.reduce(
                failed,
                FirmwareMaintenanceEvent.SafetyReviewAccepted(
                    FirmwareSafetyReviewReceipt("gumi-safety-v1", 5),
                ),
            ),
        )
        assertEquals("MAINTENANCE_ATTEMPT_TERMINAL", retry.reasonCode)
    }

    private fun readyToApply(): FirmwareMaintenanceState {
        val artifact = artifact()
        var state = FirmwareMaintenanceState(artifact)
        state = state.accept(
            FirmwareMaintenanceEvent.SafetyReviewAccepted(
                FirmwareSafetyReviewReceipt("gumi-safety-v1", 1),
            ),
        )
        state = state.accept(
            FirmwareMaintenanceEvent.PreflightPassed(
                FirmwarePreflightReceipt("preflight-1", artifact.sha256, 2),
            ),
        )
        return state.accept(
            FirmwareMaintenanceEvent.ExactArtifactAuthorized(
                FirmwareArtifactAuthorization(artifact.artifactId, artifact.sha256, 3),
            ),
        )
    }

    private fun FirmwareMaintenanceState.accept(
        event: FirmwareMaintenanceEvent,
    ): FirmwareMaintenanceState = assertIs<FirmwareMaintenanceTransition.Accepted>(
        FirmwareMaintenanceWorkflow.reduce(this, event),
    ).state

    private fun artifact() = FirmwareArtifactIdentity(
        artifactId = "functional-recording-0003-image-0",
        version = "gumi-functional-recording-0003",
        sha256 = "a".repeat(64),
        imageNumber = 0,
        byteCount = 1024uL,
    )
}
