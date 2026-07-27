package dev.gumi.devices.omicv1.updater.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OmiCv1FunctionalStatusTest {
    @Test
    fun `exact idle snapshot is recording ready`() {
        val evidence = validate(readyStatus())

        assertTrue(evidence.recordingReady)
        assertEquals(OmiCv1FunctionalCapturePhase.IDLE, evidence.phase)
        assertEquals(OmiCv1FunctionalKeyTruth.READY, evidence.key)
        assertEquals(8UL * 1024UL * 1024UL, evidence.freeBytes)
        assertEquals(1L, evidence.generation)
    }

    @Test
    fun `missing root key is accepted as authentic fail-closed diagnostics`() {
        val bytes = readyStatus()
        bytes[1] = OmiCv1FunctionalCapturePhase.BOOTING.wireValue.toByte()
        bytes[4] = OmiCv1FunctionalKeyTruth.ROOT_MISSING.wireValue.toByte()
        bytes[5] = OmiCv1FunctionalRecordingStorageTruth.UNINITIALIZED.wireValue.toByte()
        bytes[7] = 0x80.toByte()
        bytes.putI32(24, -19)

        val evidence = validate(bytes)

        assertFalse(evidence.recordingReady)
        assertEquals(OmiCv1FunctionalKeyTruth.ROOT_MISSING, evidence.key)
        assertTrue(evidence.faulted)
        assertEquals(-19, evidence.lastError)
    }

    @Test
    fun `reserved bytes capability drift and stock topology are rejected`() {
        val reserved = readyStatus().also { it[39] = 1 }
        assertFailsWith<OmiCv1ApplicationUpdateException> { validate(reserved) }

        assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1FunctionalStatusProtocol.validate(
                statusBytes = readyStatus(),
                capabilitiesBytes =
                    OmiCv1FunctionalStatusProtocol.EXPECTED_CAPABILITIES.also { it[8] = 7 },
                discoveredServiceUuids = services,
                familyIdentityServiceHasCharacteristics = false,
            )
        }

        assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1FunctionalStatusProtocol.validate(
                statusBytes = readyStatus(),
                capabilitiesBytes = OmiCv1FunctionalStatusProtocol.EXPECTED_CAPABILITIES,
                discoveredServiceUuids = services + "23ba7924-0000-1000-7450-346eac492e92",
                familyIdentityServiceHasCharacteristics = false,
            )
        }
    }

    @Test
    fun `audio permission without acquired microphone is rejected`() {
        val bytes = readyStatus()
        bytes[1] = OmiCv1FunctionalCapturePhase.BASE_ACTIVE.wireValue.toByte()
        bytes[7] = 0x63
        assertFailsWith<OmiCv1ApplicationUpdateException> { validate(bytes) }
    }

    @Test
    fun `functional recovery requires physically admitted idle microphone-off state`() {
        val notAdmitted = validate(readyStatus())
        assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1FunctionalStatusProtocol.requireRecoveryMaintenance(notAdmitted)
        }

        val admittedBytes = readyStatus().also { it[7] = 0x71 }
        val admitted = validate(admittedBytes)
        assertFalse(admitted.recordingReady)
        OmiCv1FunctionalStatusProtocol.requireRecoveryMaintenance(admitted)
    }

    @Test
    fun `update admission cannot coexist with active or uncertain capture`() {
        val active = readyStatus()
        active[1] = OmiCv1FunctionalCapturePhase.BASE_ACTIVE.wireValue.toByte()
        active[2] = OmiCv1FunctionalMicrophoneTruth.ACQUIRED.wireValue.toByte()
        active[5] = OmiCv1FunctionalRecordingStorageTruth.ACTIVE.wireValue.toByte()
        active[6] = OmiCv1FunctionalCodecTruth.ACTIVE.wireValue.toByte()
        active[7] = 0x7b
        active.putU64(8, 17)

        assertFailsWith<OmiCv1ApplicationUpdateException> { validate(active) }

        val uncertain = readyStatus()
        uncertain[2] = OmiCv1FunctionalMicrophoneTruth.UNKNOWN.wireValue.toByte()
        uncertain[7] = 0x71
        assertFailsWith<OmiCv1ApplicationUpdateException> { validate(uncertain) }
    }

    @Test
    fun `active local recording requires privacy microphone codec and durable-store agreement`() {
        val bytes = readyStatus()
        bytes[1] = OmiCv1FunctionalCapturePhase.BASE_ACTIVE.wireValue.toByte()
        bytes[2] = OmiCv1FunctionalMicrophoneTruth.ACQUIRED.wireValue.toByte()
        bytes[5] = OmiCv1FunctionalRecordingStorageTruth.ACTIVE.wireValue.toByte()
        bytes[6] = OmiCv1FunctionalCodecTruth.ACTIVE.wireValue.toByte()
        bytes[7] = 0x2b
        bytes.putU64(8, 7)

        assertTrue(validate(bytes).capturingLocally)

        bytes[7] = 0x23
        assertFailsWith<OmiCv1ApplicationUpdateException> { validate(bytes) }
    }

    private fun validate(bytes: ByteArray) = OmiCv1FunctionalStatusProtocol.validate(
        statusBytes = bytes,
        capabilitiesBytes = OmiCv1FunctionalStatusProtocol.EXPECTED_CAPABILITIES,
        discoveredServiceUuids = services,
        familyIdentityServiceHasCharacteristics = false,
    )

    private fun readyStatus(): ByteArray {
        val bytes = ByteArray(OmiCv1FunctionalStatusProtocol.STATUS_WIRE_SIZE)
        bytes[0] = 1
        bytes[1] = OmiCv1FunctionalCapturePhase.IDLE.wireValue.toByte()
        bytes[2] = OmiCv1FunctionalMicrophoneTruth.VERIFIED_OFF.wireValue.toByte()
        bytes[3] = OmiCv1FunctionalStorageState.HEALTHY.wireValue.toByte()
        bytes[4] = OmiCv1FunctionalKeyTruth.READY.wireValue.toByte()
        bytes[5] = OmiCv1FunctionalRecordingStorageTruth.READY.wireValue.toByte()
        bytes[6] = OmiCv1FunctionalCodecTruth.CLOSED.wireValue.toByte()
        bytes[7] = 0x61
        bytes.putU64(16, 8L * 1024L * 1024L)
        bytes.putI32(28, 1)
        return bytes
    }

    private fun ByteArray.putU64(offset: Int, value: Long) {
        for (index in 0 until 8) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun ByteArray.putI32(offset: Int, value: Int) {
        for (index in 0 until 4) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private companion object {
        val services = setOf(
            OmiCv1FunctionalStatusProtocol.SERVICE_UUID,
            OmiCv1FunctionalStatusProtocol.FAMILY_IDENTITY_SERVICE_UUID,
        )
    }
}
