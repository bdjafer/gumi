package dev.gumi.edge.shell.android.product

import dev.gumi.edge.shell.application.AxisObservation
import dev.gumi.edge.shell.application.BacklogStatus
import dev.gumi.edge.shell.application.ObservationFreshness
import dev.gumi.edge.shell.application.ProjectionAuthority
import dev.gumi.edge.shell.application.ShellControlAction
import dev.gumi.edge.shell.application.StorageState
import dev.gumi.edge.shell.application.StorageStatus
import dev.gumi.edge.shell.application.SyncState
import dev.gumi.edge.shell.application.SyncStatus
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PortableControlPlaneSurfaceTextTest {
    @Test
    fun `stale storage never renders historical capacity as current`() {
        val summary = storageSummary(
            observation(
                value = StorageStatus(
                    state = StorageState.FULL,
                    availableBytes = 42uL,
                    capacityBytes = 84uL,
                ),
                freshness = ObservationFreshness.STALE,
            ),
        )

        assertEquals("Storage report stale — current capacity unknown", summary)
        assertFalse("42" in summary)
        assertFalse("Full" in summary)
    }

    @Test
    fun `unavailable sync never exposes old backlog numbers`() {
        val summary = syncSummary(
            observation(
                value = SyncStatus(
                    state = SyncState.UPLOADING,
                    backlog = BacklogStatus(pendingItems = 99uL, pendingBytes = 123_456uL),
                ),
                freshness = ObservationFreshness.UNAVAILABLE,
            ),
        )

        assertEquals("Current transfer status and backlog unavailable", summary)
        assertFalse("99" in summary)
        assertFalse("123" in summary)
        assertFalse("Uploading" in summary)
    }

    @Test
    fun `fresh sync keeps local durability separate from cloud reachability`() {
        val summary = syncSummary(
            observation(
                value = SyncStatus(
                    state = SyncState.CLOUD_OFFLINE_SAVED_LOCALLY,
                    backlog = BacklogStatus(pendingItems = 8uL, pendingBytes = 1_310_720uL),
                ),
                freshness = ObservationFreshness.FRESH,
            ),
        )

        assertEquals("Cloud offline — saved locally • 8 queued item(s), 1,310,720 bytes", summary)
    }

    @Test
    fun `fresh backlog renders its oldest host timestamp without using wall clock inference`() {
        val summary = syncSummary(
            observation(
                value = SyncStatus(
                    state = SyncState.UPLOADING,
                    backlog = BacklogStatus(
                        pendingItems = 1uL,
                        pendingBytes = 512uL,
                        oldestItemAtEpochMillis = 0L,
                    ),
                ),
                freshness = ObservationFreshness.FRESH,
            ),
        )

        assertEquals(
            "Uploading durable backlog • 1 queued item(s), 512 bytes; " +
                "oldest queued item: 1970-01-01T00:00:00Z",
            summary,
        )
    }

    @Test
    fun `evidence summary exposes provenance and freshness together`() {
        val evidence = evidenceSummary(
            AxisObservation(
                value = Unit,
                authority = ProjectionAuthority.EDGE_INFERRED,
                observedAtEpochMillis = 12L,
                freshness = ObservationFreshness.FRESH,
            ),
        )

        assertEquals("Fresh observation • edge inferred", evidence)
    }

    @Test
    fun `every portable action has a distinct human label`() {
        val labels = ShellControlAction.entries.map(::actionLabel)

        assertEquals(ShellControlAction.entries.size, labels.toSet().size)
        assertContains(labels, "Start recording")
        assertContains(labels, "Start voice turn")
        assertContains(labels, "Stop capture")
    }

    @Test
    fun `disabled reason keeps the stable reason code visible`() {
        assertEquals(
            "the device link is not freshly ready (DEVICE_LINK_NOT_READY)",
            blockedReasonLabel("DEVICE_LINK_NOT_READY"),
        )
        assertEquals(
            "the portable control plane blocked this action (FUTURE_POLICY_GATE)",
            blockedReasonLabel("FUTURE_POLICY_GATE"),
        )
    }

    @Test
    fun `fleet summary does not collapse active and uncertain devices`() {
        assertEquals(
            "Managed devices: 4 • confirmed active: 1 • uncertain: 2",
            fleetScopeSummary(deviceCount = 4, activeCount = 1, uncertainCount = 2),
        )
    }

    private fun <T> observation(
        value: T,
        freshness: ObservationFreshness,
    ) = AxisObservation(
        value = value,
        authority = ProjectionAuthority.EDGE_INFERRED,
        observedAtEpochMillis = 10L,
        freshness = freshness,
    )
}
