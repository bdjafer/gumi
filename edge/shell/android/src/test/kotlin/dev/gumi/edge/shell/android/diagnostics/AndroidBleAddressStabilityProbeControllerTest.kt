package dev.gumi.edge.shell.android.diagnostics

import dev.gumi.edge.platforms.android.ble.AndroidBleObservationComparison
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidBleAddressStabilityProbeControllerTest {
    @Test
    fun `one explicit baseline produces only fenced fresh generation verdicts`() {
        val controller = controller()
        val baseline = endpoint("opaque-a")
        val changed = endpoint("opaque-b")

        controller.beginGeneration(1)
        controller.observe(1, baseline)
        assertFalse(controller.state.value.baselineCaptureEnabled)
        assertFalse(controller.captureBaseline())
        controller.finishGeneration(1)
        assertTrue(controller.state.value.baselineCaptureEnabled)
        assertTrue(controller.captureBaseline())
        assertTrue(controller.state.value.baselineCaptured)
        assertNull(controller.state.value.verdict)

        controller.beginGeneration(2)
        assertEquals(BleAddressStabilityVerdict.INCONCLUSIVE, controller.state.value.verdict)

        controller.observe(1, changed)
        assertEquals(BleAddressStabilityVerdict.INCONCLUSIVE, controller.state.value.verdict)

        controller.observe(2, baseline)
        assertEquals(BleAddressStabilityVerdict.INCONCLUSIVE, controller.state.value.verdict)
        controller.finishGeneration(2)
        assertEquals(BleAddressStabilityVerdict.SAME, controller.state.value.verdict)

        controller.observe(2, changed)
        assertEquals(BleAddressStabilityVerdict.SAME, controller.state.value.verdict)

        controller.beginGeneration(3)
        controller.observe(3, baseline)
        controller.observe(3, changed)
        controller.finishGeneration(3)
        assertEquals(BleAddressStabilityVerdict.INCONCLUSIVE, controller.state.value.verdict)

        controller.beginGeneration(4)
        controller.observe(4, changed)
        controller.finishGeneration(4)
        assertEquals(BleAddressStabilityVerdict.CHANGED, controller.state.value.verdict)
    }

    @Test
    fun `zero multiple and unresolved current candidates are inconclusive`() {
        val controller = AndroidBleAddressStabilityProbeController { _, current ->
            if (current.ephemeralId == "unresolved") {
                AndroidBleObservationComparison.INCONCLUSIVE
            } else {
                AndroidBleObservationComparison.SAME
            }
        }
        val baseline = endpoint("baseline")

        controller.beginGeneration(1)
        assertFalse(controller.captureBaseline())
        controller.observe(1, baseline)
        controller.observe(1, endpoint("second"))
        controller.finishGeneration(1)
        assertFalse(controller.captureBaseline())

        controller.beginGeneration(2)
        controller.observe(2, baseline)
        controller.finishGeneration(2)
        assertTrue(controller.captureBaseline())

        controller.beginGeneration(3)
        controller.finishGeneration(3)
        assertEquals(BleAddressStabilityVerdict.INCONCLUSIVE, controller.state.value.verdict)

        controller.beginGeneration(4)
        controller.observe(3, endpoint("unresolved"))
        assertEquals(BleAddressStabilityVerdict.INCONCLUSIVE, controller.state.value.verdict)
        controller.observe(4, endpoint("unresolved"))
        controller.finishGeneration(4)
        assertEquals(BleAddressStabilityVerdict.INCONCLUSIVE, controller.state.value.verdict)

        controller.observe(4, endpoint("another"))
        assertEquals(BleAddressStabilityVerdict.INCONCLUSIVE, controller.state.value.verdict)
    }

    @Test
    fun `baseline cannot be replaced and reset erases the activity local run`() {
        val controller = controller()
        controller.beginGeneration(1)
        controller.observe(1, endpoint("first"))
        controller.finishGeneration(1)
        assertTrue(controller.captureBaseline())
        assertFalse(controller.captureBaseline())

        controller.beginGeneration(2)
        controller.observe(2, endpoint("second"))
        assertFalse(controller.captureBaseline())

        controller.reset()

        assertEquals(BleAddressStabilityProbeState(), controller.state.value)
        controller.beginGeneration(1)
        controller.observe(1, endpoint("new-run"))
        controller.finishGeneration(1)
        assertTrue(controller.captureBaseline())
    }

    @Test
    fun `generation tokens must increase`() {
        val controller = controller()
        controller.beginGeneration(1)

        assertFailsWith<IllegalArgumentException> { controller.beginGeneration(1) }
        assertFailsWith<IllegalArgumentException> { controller.beginGeneration(0) }
    }

    @Test
    fun `comparison failure is redacted to inconclusive`() {
        val controller = AndroidBleAddressStabilityProbeController { _, _ ->
            error("synthetic comparison failure containing private transport detail")
        }

        controller.beginGeneration(1)
        controller.observe(1, endpoint("baseline"))
        controller.finishGeneration(1)
        assertTrue(controller.captureBaseline())

        controller.beginGeneration(2)
        controller.observe(2, endpoint("current"))
        controller.finishGeneration(2)

        assertEquals(BleAddressStabilityVerdict.INCONCLUSIVE, controller.state.value.verdict)
        assertFalse("private transport detail" in controller.state.value.toString())
    }

    private fun controller() = AndroidBleAddressStabilityProbeController { baseline, current ->
        if (baseline.ephemeralId == current.ephemeralId) {
            AndroidBleObservationComparison.SAME
        } else {
            AndroidBleObservationComparison.CHANGED
        }
    }
}

private fun endpoint(opaqueReference: String) = EndpointCandidate(
    transport = TransportKind.BLE,
    ephemeralId = opaqueReference,
)
