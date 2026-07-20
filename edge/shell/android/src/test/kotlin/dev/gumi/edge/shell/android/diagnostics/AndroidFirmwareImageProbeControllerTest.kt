package dev.gumi.edge.shell.android.diagnostics

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspector
import dev.gumi.edge.sdk.firmware.FirmwareImageStateReadDisclosure
import dev.gumi.edge.sdk.firmware.FirmwareProtocolReadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidFirmwareImageProbeControllerTest {
    @Test
    fun `each disclosed read logs matching monotonic start and success attempt IDs`() = runTest {
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = "ble:redacted-firmware-test",
        )
        val logs = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var attemptId = 30L
        val controller = AndroidFirmwareImageProbeController(
            inspector = FixedFirmwareInspector(endpoint),
            scope = scope,
            log = logs::add,
            nextAttemptId = { ++attemptId },
            assessment = FirmwareImageStateAssessment { "MATCHES_PUBLISHED_V3012" },
        )

        controller.inspect(endpoint)
        advanceUntilIdle()
        assertEquals(endpoint.ephemeralId, controller.state.value.successfulEndpointEphemeralId)
        controller.inspect(endpoint)
        assertTrue(controller.state.value.inspecting)
        assertNull(controller.state.value.inspection)
        assertNull(controller.state.value.successfulEndpointEphemeralId)
        advanceUntilIdle()

        assertFalse(controller.state.value.inspecting)
        assertNull(controller.state.value.error)
        assertEquals(1, controller.state.value.inspection?.slots?.size)
        assertEquals("MATCHES_PUBLISHED_V3012", controller.state.value.assessmentStatus)
        assertEquals(
            listOf(
                "MCU image-state semantic read attempt started: attempt=31",
                "MCU image-state semantic read attempt started: attempt=32",
            ),
            logs.filter { it.startsWith("MCU image-state semantic read attempt started:") },
        )
        assertEquals(
            listOf(
                "MCU image-state semantic read complete: attempt=31, " +
                    "protocol=mcumgr-smp, slots=1, splitStatus=null, " +
                    "assessment=MATCHES_PUBLISHED_V3012",
                "MCU image-state semantic read complete: attempt=32, " +
                    "protocol=mcumgr-smp, slots=1, splitStatus=null, " +
                    "assessment=MATCHES_PUBLISHED_V3012",
            ),
            logs.filter { it.startsWith("MCU image-state semantic read complete:") },
        )
        assertEquals(
            listOf(
                "Image 0 slot 0: attempt=31, version=0.0.0+0, hash=null, " +
                    "bootable=true, pending=false, confirmed=true, active=true, " +
                    "permanent=false, compressed=false",
                "Image 0 slot 0: attempt=32, version=0.0.0+0, hash=null, " +
                    "bootable=true, pending=false, confirmed=true, active=true, " +
                    "permanent=false, compressed=false",
            ),
            logs.filter { it.startsWith("Image 0 slot 0:") },
        )
        assertFalse(logs.any { endpoint.ephemeralId in it })
        scope.cancel()
    }

    @Test
    fun `new scan generation invalidates prior firmware audio authority`() = runTest {
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = "ble:redacted-generation-test",
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = AndroidFirmwareImageProbeController(
            inspector = FixedFirmwareInspector(endpoint),
            scope = scope,
            log = {},
            assessment = FirmwareImageStateAssessment { "MATCHES_PUBLISHED_V3012" },
        )

        controller.inspect(endpoint)
        advanceUntilIdle()
        assertEquals(endpoint.ephemeralId, controller.state.value.successfulEndpointEphemeralId)

        controller.invalidateForNewScan()

        assertEquals(FirmwareImageProbeState(), controller.state.value)
        scope.cancel()
    }

    @Test
    fun `sixty second timeout cancels inspector release path and emits stable local error`() =
        runTest {
            val endpoint = EndpointCandidate(
                transport = TransportKind.BLE,
                ephemeralId = "ble:redacted-timeout-test",
            )
            val inspector = HangingFirmwareInspector()
            val logs = mutableListOf<String>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val controller = AndroidFirmwareImageProbeController(
                inspector = inspector,
                scope = scope,
                log = logs::add,
                nextAttemptId = { 41L },
            )

            controller.inspect(endpoint)
            runCurrent()
            assertTrue(controller.state.value.inspecting)

            advanceTimeBy(59_999)
            runCurrent()
            assertTrue(controller.state.value.inspecting)
            assertFalse(inspector.released)

            advanceTimeBy(1)
            runCurrent()

            assertFalse(controller.state.value.inspecting)
            assertTrue(inspector.released)
            assertNull(controller.state.value.inspection)
            assertEquals(
                "MCU_IMAGE_STATE_TIMEOUT: no response within 60 seconds",
                controller.state.value.error,
            )
            assertEquals(
                listOf(
                    "MCU image-state semantic read attempt started: attempt=41",
                    "MCU image-state semantic read failed: " +
                        "attempt=41, code=MCU_IMAGE_STATE_TIMEOUT",
                ),
                logs,
            )
            assertFalse(logs.any { it.startsWith("MCU image-state semantic read complete:") })
            scope.cancel()
        }

    @Test
    fun `cancel retains lease and rejects retry until slow inspector release completes`() = runTest {
        val endpoint = EndpointCandidate(
            transport = TransportKind.BLE,
            ephemeralId = "ble:redacted-cancel-test",
        )
        val release = CompletableDeferred<Unit>()
        val inspector = SlowReleaseFirmwareInspector(release)
        val gate = DiagnosticOperationGate()
        val logs = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var attemptId = 50L
        val controller = AndroidFirmwareImageProbeController(
            inspector = inspector,
            scope = scope,
            log = logs::add,
            nextAttemptId = { ++attemptId },
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

        controller.inspect(endpoint)
        runCurrent()
        assertEquals(1, inspector.calls)
        assertEquals(1, logs.count { it.contains("attempt started") })

        release.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.inspecting)
        assertFalse(controller.state.value.cancelling)
        assertFalse(gate.state.value.busy)
        assertNull(controller.state.value.inspection)

        controller.inspect(endpoint)
        runCurrent()
        assertEquals(2, inspector.calls)
        assertEquals(2, logs.count { it.contains("attempt started") })
        controller.cancel()
        runCurrent()
        assertFalse(controller.state.value.inspecting)
        assertFalse(gate.state.value.busy)
        assertFalse(logs.any { endpoint.ephemeralId in it })
        scope.cancel()
    }
}

private class FixedFirmwareInspector(
    private val expectedEndpoint: EndpointCandidate,
) : FirmwareImageStateInspector {
    override val disclosure = FirmwareImageStateReadDisclosure(
        protocol = "mcumgr-smp",
        requestedAttMtu = 23,
        writesRequestCharacteristic = true,
        writesNotificationDescriptor = true,
        protocolReads = listOf(
            FirmwareProtocolReadRequest(0, 6, "MCU Manager parameters"),
            FirmwareProtocolReadRequest(1, 0, "MCUboot image state"),
        ),
        persistentDeviceMutationExpected = false,
    )

    override suspend fun inspect(endpoint: EndpointCandidate): FirmwareImageStateInspection {
        assertEquals(expectedEndpoint, endpoint)
        return FirmwareImageStateInspection(
            endpoint = endpoint,
            protocol = "mcumgr-smp",
            slots = listOf(
                FirmwareImageSlot(
                    imageNumber = 0,
                    slotNumber = 0,
                    version = "0.0.0+0",
                    hash = null,
                    bootable = true,
                    pending = false,
                    confirmed = true,
                    active = true,
                    permanent = false,
                    compressed = false,
                ),
            ),
            splitStatus = null,
        )
    }
}

private class HangingFirmwareInspector : FirmwareImageStateInspector {
    var released = false

    override val disclosure = FirmwareImageStateReadDisclosure(
        protocol = "mcumgr-smp",
        requestedAttMtu = 23,
        writesRequestCharacteristic = true,
        writesNotificationDescriptor = true,
        protocolReads = listOf(FirmwareProtocolReadRequest(1, 0, "MCUboot image state")),
        persistentDeviceMutationExpected = false,
    )

    override suspend fun inspect(endpoint: EndpointCandidate): FirmwareImageStateInspection = try {
        awaitCancellation()
    } finally {
        released = true
    }
}

private class SlowReleaseFirmwareInspector(
    private val release: CompletableDeferred<Unit>,
) : FirmwareImageStateInspector {
    var calls = 0
        private set

    override val disclosure = FirmwareImageStateReadDisclosure(
        protocol = "mcumgr-smp",
        requestedAttMtu = 23,
        writesRequestCharacteristic = true,
        writesNotificationDescriptor = true,
        protocolReads = listOf(FirmwareProtocolReadRequest(1, 0, "MCUboot image state")),
        persistentDeviceMutationExpected = false,
    )

    override suspend fun inspect(endpoint: EndpointCandidate): FirmwareImageStateInspection {
        calls += 1
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { release.await() }
        }
    }
}
