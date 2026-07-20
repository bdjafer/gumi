package dev.gumi.edge.shell.android.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticOperationGateTest {
    @Test
    fun `cross operation lease remains exclusive through cancellation and stale release`() {
        val gate = DiagnosticOperationGate()
        val firmwareLease = requireNotNull(
            gate.tryAcquire(DiagnosticOperation.FIRMWARE_IMAGE_STATE),
        )

        assertEquals(DiagnosticOperation.FIRMWARE_IMAGE_STATE, gate.state.value.activeOperation)
        assertTrue(gate.state.value.busy)
        assertFalse(gate.state.value.cancelling)
        firmwareLease.markCancelling()
        assertTrue(gate.state.value.cancelling)
        assertNull(gate.tryAcquire(DiagnosticOperation.DRIVER_NEGOTIATION))

        firmwareLease.close()
        val driverLease = requireNotNull(
            gate.tryAcquire(DiagnosticOperation.DRIVER_NEGOTIATION),
        )
        assertEquals(DiagnosticOperation.DRIVER_NEGOTIATION, gate.state.value.activeOperation)

        firmwareLease.close()
        assertEquals(DiagnosticOperation.DRIVER_NEGOTIATION, gate.state.value.activeOperation)
        assertFalse(gate.state.value.cancelling)

        driverLease.close()
        assertFalse(gate.state.value.busy)
        assertNull(gate.state.value.activeOperation)
    }
}
