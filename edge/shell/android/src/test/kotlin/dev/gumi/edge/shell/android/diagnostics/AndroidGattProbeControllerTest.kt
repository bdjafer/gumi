package dev.gumi.edge.shell.android.diagnostics

import dev.gumi.devices.omicv1.OmiCv1GattProfile
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.ble.BleGattInspection
import dev.gumi.edge.sdk.ble.BleGattInspectionRequest
import dev.gumi.edge.sdk.ble.BleGattInspector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidGattProbeControllerTest {
    @Test
    fun `cancel retains operation lease and rejects retry through slow inspector release`() = runTest {
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = "ble:redacted-gatt-test",
        )
        val release = CompletableDeferred<Unit>()
        val inspector = SlowReleaseGattInspector(release)
        val gate = DiagnosticOperationGate()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = AndroidGattProbeController(
            inspector = inspector,
            scope = scope,
            operationGate = gate,
        )

        controller.inspect(endpoint)
        runCurrent()
        assertEquals(1, inspector.calls)

        controller.cancel()
        runCurrent()
        assertTrue(controller.state.value.inspecting)
        assertTrue(controller.state.value.cancelling)
        assertTrue(gate.state.value.cancelling)
        assertNull(gate.tryAcquire(DiagnosticOperation.DRIVER_NEGOTIATION))

        controller.inspect(endpoint)
        runCurrent()
        assertEquals(1, inspector.calls)

        release.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.inspecting)
        assertFalse(controller.state.value.cancelling)
        assertFalse(gate.state.value.busy)

        controller.inspect(endpoint)
        runCurrent()
        assertEquals(2, inspector.calls)
        controller.cancel()
        runCurrent()
        assertFalse(controller.state.value.inspecting)
        assertFalse(gate.state.value.busy)
        scope.cancel()
    }
}

private class SlowReleaseGattInspector(
    private val release: CompletableDeferred<Unit>,
) : BleGattInspector {
    var calls = 0
        private set

    override suspend fun inspect(
        endpoint: EndpointCandidate,
        request: BleGattInspectionRequest,
    ): BleGattInspection {
        assertEquals(OmiCv1GattProfile.readOnlyInspectionRequest, request)
        calls += 1
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { release.await() }
        }
    }
}
