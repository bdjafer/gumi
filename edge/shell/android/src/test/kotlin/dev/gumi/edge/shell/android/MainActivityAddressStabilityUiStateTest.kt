package dev.gumi.edge.shell.android

import dev.gumi.edge.shell.android.diagnostics.BleAddressStabilityProbeState
import dev.gumi.edge.shell.android.diagnostics.BleAddressStabilityVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainActivityAddressStabilityUiStateTest {
    @Test
    fun `uncaptured projection exposes no transport reference`() {
        val state = bleAddressStabilityUiState(
            BleAddressStabilityProbeState(
                baselineCaptureEnabled = true,
            ),
            scanning = false,
        )

        assertFalse(state.baselineCaptured)
        assertTrue(state.captureBaselineEnabled)
        assertNull(state.freshScanVerdict)
    }

    @Test
    fun `baseline capture is disabled until the current generation is stopped`() {
        val state = BleAddressStabilityProbeState(baselineCaptureEnabled = true)

        assertFalse(bleAddressStabilityUiState(state, scanning = true).captureBaselineEnabled)
        assertTrue(bleAddressStabilityUiState(state, scanning = false).captureBaselineEnabled)
    }

    @Test
    fun `comparison projection can render only the three allowlisted verdicts`() {
        val rendered = BleAddressStabilityVerdict.entries.map { verdict ->
            bleAddressStabilityUiState(
                BleAddressStabilityProbeState(
                    baselineCaptured = true,
                    verdict = verdict,
                ),
                scanning = false,
            ).freshScanVerdict?.name
        }

        assertEquals(listOf("SAME", "CHANGED", "INCONCLUSIVE"), rendered)
    }
}
