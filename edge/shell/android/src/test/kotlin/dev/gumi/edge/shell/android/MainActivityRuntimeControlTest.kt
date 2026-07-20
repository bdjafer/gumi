package dev.gumi.edge.shell.android

import dev.gumi.edge.runtime.host.RuntimeHostExecutionState
import dev.gumi.edge.runtime.host.RuntimeHostProjection
import dev.gumi.edge.runtime.host.RuntimeHostRestartPolicy
import dev.gumi.edge.shell.android.runtime.AndroidRuntimeOwnerProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainActivityRuntimeControlTest {
    @Test
    fun `operational ownership blocks diagnostic and duplicate runtime start`() {
        val state = androidRuntimeControlUiState(
            owner = AndroidRuntimeOwnerProjection(outstandingDeliveries = 1),
            host = RuntimeHostProjection(execution = RuntimeHostExecutionState.START_REQUESTED),
            launchStatus = "delivered",
            permissionsGranted = true,
            retryAvailable = false,
            diagnosticBusy = false,
        )

        assertTrue(state.operationalBoundaryBusy)
        assertFalse(state.startEnabled)
        assertEquals("START_REQUESTED", state.execution)
    }

    @Test
    fun `user stopped idle projection stays visible and permits only explicit start`() {
        val state = androidRuntimeControlUiState(
            owner = AndroidRuntimeOwnerProjection(),
            host = RuntimeHostProjection(
                execution = RuntimeHostExecutionState.STOPPED,
                restartPolicy = RuntimeHostRestartPolicy.USER_STOPPED,
            ),
            launchStatus = "stopped",
            permissionsGranted = true,
            retryAvailable = false,
            diagnosticBusy = false,
        )

        assertFalse(state.operationalBoundaryBusy)
        assertTrue(state.startEnabled)
        assertEquals("USER_STOPPED", state.restartPolicy)
    }
}
