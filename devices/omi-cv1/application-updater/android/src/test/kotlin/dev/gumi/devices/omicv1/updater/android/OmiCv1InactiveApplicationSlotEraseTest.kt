package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OmiCv1InactiveApplicationSlotEraseTest {
    @Test
    fun `planner binds exact endpoint active hash image and inactive slot`() {
        val endpoint = endpoint("reclaimer")
        val sourceHash = FirmwareImageHash("70".repeat(32))

        val first = OmiCv1InactiveApplicationSlotErasePlanner.prepare(
            endpoint,
            safeInspection(endpoint, sourceHash, populatedSecondary = true),
            sourceHash,
        )
        val second = OmiCv1InactiveApplicationSlotErasePlanner.prepare(
            endpoint,
            safeInspection(endpoint, sourceHash, populatedSecondary = true),
            sourceHash,
        )

        assertEquals(first.planId, second.planId)
        assertEquals(endpoint, first.endpoint)
        assertEquals(sourceHash, first.expectedActiveApplicationHash)
    }

    @Test
    fun `planner rejects pending secondary before erase`() {
        val endpoint = endpoint("reclaimer")
        val sourceHash = FirmwareImageHash("70".repeat(32))
        val unsafe = safeInspection(endpoint, sourceHash, populatedSecondary = true).copy(
            slots = safeInspection(endpoint, sourceHash, populatedSecondary = true).slots.map {
                if (it.slotNumber == 1) it.copy(pending = true) else it
            },
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1InactiveApplicationSlotErasePlanner.prepare(endpoint, unsafe, sourceHash)
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED, error.code)
    }

    @Test
    fun `executor erases once and proves active hash unchanged with secondary absent`() = runTest {
        val endpoint = endpoint("reclaimer")
        val sourceHash = FirmwareImageHash("70".repeat(32))
        val plan = OmiCv1InactiveApplicationSlotErasePlanner.prepare(
            endpoint,
            safeInspection(endpoint, sourceHash, populatedSecondary = true),
            sourceHash,
        )
        val session = FakeEraseSession(
            ArrayDeque(
                listOf(
                    safeInspection(endpoint, sourceHash, populatedSecondary = true),
                    safeInspection(endpoint, sourceHash, populatedSecondary = false),
                ),
            ),
        )
        val stages = mutableListOf<OmiCv1InactiveApplicationSlotEraseStage>()

        val validation = OmiCv1InactiveApplicationSlotEraseExecutor(
            OmiCv1ApplicationImage0UpdateSessionFactory { session },
            MonotonicMillisClock { 100 },
        ).execute(
            OmiCv1InactiveApplicationSlotEraseAuthorization(plan, plan.planId, 1_000),
            stages::add,
        )

        assertEquals(listOf("inspect", "erase", "inspect", "release"), session.calls)
        assertEquals(OmiCv1InactiveApplicationSlotEraseStage.entries.toList(), stages)
        assertEquals(sourceHash, validation.activeApplicationHash)
        assertTrue(validation.inactiveApplicationSlotAbsent)
    }

    @Test
    fun `post erase validation rejects any populated secondary`() = runTest {
        val endpoint = endpoint("reclaimer")
        val sourceHash = FirmwareImageHash("70".repeat(32))
        val initial = safeInspection(endpoint, sourceHash, populatedSecondary = true)
        val plan = OmiCv1InactiveApplicationSlotErasePlanner.prepare(
            endpoint,
            initial,
            sourceHash,
        )
        val session = FakeEraseSession(ArrayDeque(listOf(initial, initial)))

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1InactiveApplicationSlotEraseExecutor(
                OmiCv1ApplicationImage0UpdateSessionFactory { session },
                MonotonicMillisClock { 100 },
            ).execute(
                OmiCv1InactiveApplicationSlotEraseAuthorization(plan, plan.planId, 1_000),
            ) {}
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED, error.code)
        assertEquals(listOf("inspect", "erase", "inspect", "release"), session.calls)
    }

    private fun safeInspection(
        endpoint: EndpointCandidate,
        sourceHash: FirmwareImageHash,
        populatedSecondary: Boolean,
    ): FirmwareImageStateInspection = inspection(
        endpoint,
        FirmwareImageSlot(
            imageNumber = 0,
            slotNumber = 0,
            version = OmiCv1ApplicationUpdatePlanner.MCUMGR_COMPATIBILITY_WIRE_VERSION,
            hash = sourceHash,
            bootable = true,
            pending = false,
            confirmed = true,
            active = true,
            permanent = false,
            compressed = false,
        ),
        FirmwareImageSlot(
            imageNumber = 0,
            slotNumber = 1,
            version = if (populatedSecondary) "0.0.0" else null,
            hash = if (populatedSecondary) FirmwareImageHash("40".repeat(32)) else null,
            bootable = populatedSecondary,
            pending = false,
            confirmed = false,
            active = false,
            permanent = false,
            compressed = false,
        ),
    )

    private class FakeEraseSession(
        private val inspections: ArrayDeque<FirmwareImageStateInspection>,
    ) : OmiCv1ApplicationImage0UpdateSession {
        val calls = mutableListOf<String>()

        override suspend fun upload(
            imageBytes: ByteArray,
            mode: OmiCv1ApplicationUploadMode,
            onProgress: (Int, Int) -> Unit,
        ) {
            error("Upload is forbidden in inactive-slot erase")
        }

        override suspend fun inspect(): FirmwareImageStateInspection {
            calls += "inspect"
            return inspections.removeFirst()
        }

        override suspend fun eraseInactiveApplicationSlot() {
            calls += "erase"
        }

        override suspend fun confirm(mcubootImageHash: FirmwareImageHash) {
            error("Confirm is forbidden in inactive-slot erase")
        }

        override suspend fun requestReset(): Boolean {
            error("Reset is forbidden in inactive-slot erase")
        }

        override fun cancel() = Unit

        override fun release() {
            calls += "release"
        }
    }
}
