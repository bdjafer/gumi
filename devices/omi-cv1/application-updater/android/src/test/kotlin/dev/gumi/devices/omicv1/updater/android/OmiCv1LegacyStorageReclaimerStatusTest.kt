package dev.gumi.devices.omicv1.updater.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OmiCv1LegacyStorageReclaimerStatusTest {
    @Test
    fun `exact reclaimed terminal evidence is accepted`() {
        val evidence = validate(
            status(
                phase = OmiCv1LegacyStorageReclaimerPhase.RECLAIMED,
                flags = OmiCv1LegacyStorageReclaimerStatusProtocol.RECLAIMED_FLAGS,
                targetSize = OmiCv1LegacyStorageReclaimerStatusProtocol.EXACT_TARGET_SIZE_BYTES,
                freeAfter = 500L * 1024L * 1024L,
            ),
        )

        assertTrue(evidence.reclaimSucceeded)
        assertTrue(evidence.recoveryEligible)
        assertTrue(evidence.deleteAttempted)
        assertTrue(evidence.targetAbsent)
        OmiCv1LegacyStorageReclaimerStatusProtocol.requireReclaimSucceeded(evidence)
    }

    @Test
    fun `already absent is successful only with minimum free space`() {
        val evidence = validate(
            status(
                phase = OmiCv1LegacyStorageReclaimerPhase.ALREADY_ABSENT,
                flags = OmiCv1LegacyStorageReclaimerStatusProtocol.ALREADY_ABSENT_FLAGS,
                freeBefore = 8L * 1024L * 1024L,
                freeAfter = 8L * 1024L * 1024L,
            ),
        )

        assertTrue(evidence.reclaimSucceeded)
        assertFalse(evidence.deleteAttempted)
        assertEquals(0L, evidence.targetSizeBytes)
    }

    @Test
    fun `refusal remains recoverable but never authorizes functional firmware`() {
        val evidence = validate(
            status(
                phase = OmiCv1LegacyStorageReclaimerPhase.REFUSED,
                flags = 0x10f,
                lastError = -1,
                targetSize = 123,
            ),
        )

        assertFalse(evidence.reclaimSucceeded)
        assertTrue(evidence.recoveryEligible)
        assertRejected {
            OmiCv1LegacyStorageReclaimerStatusProtocol.requireReclaimSucceeded(evidence)
        }
        OmiCv1LegacyStorageReclaimerStatusProtocol.requireRecoveryEligible(evidence)
    }

    @Test
    fun `wrong target size cannot masquerade as reclaimed`() {
        assertRejected {
            validate(
                status(
                    phase = OmiCv1LegacyStorageReclaimerPhase.RECLAIMED,
                    flags = OmiCv1LegacyStorageReclaimerStatusProtocol.RECLAIMED_FLAGS,
                    targetSize =
                        OmiCv1LegacyStorageReclaimerStatusProtocol.EXACT_TARGET_SIZE_BYTES - 1,
                    freeAfter = 500L * 1024L * 1024L,
                ),
            )
        }
    }

    @Test
    fun `mutation admission before terminal state is rejected`() {
        assertRejected {
            validate(
                status(
                    phase = OmiCv1LegacyStorageReclaimerPhase.INSPECTING,
                    flags = 0x103,
                ),
            )
        }
    }

    @Test
    fun `extra functional service or characteristic is rejected`() {
        assertRejected {
            OmiCv1LegacyStorageReclaimerStatusProtocol.validate(
                statusBytes = successfulStatus,
                discoveredServiceUuids = baselineServices +
                    OmiCv1FunctionalStatusProtocol.SERVICE_UUID,
                reclaimerCharacteristicUuids = setOf(
                    OmiCv1LegacyStorageReclaimerStatusProtocol.STATUS_CHARACTERISTIC_UUID,
                ),
                familyIdentityServiceHasCharacteristics = false,
            )
        }
        assertRejected {
            OmiCv1LegacyStorageReclaimerStatusProtocol.validate(
                statusBytes = successfulStatus,
                discoveredServiceUuids = baselineServices,
                reclaimerCharacteristicUuids = setOf(
                    OmiCv1LegacyStorageReclaimerStatusProtocol.STATUS_CHARACTERISTIC_UUID,
                    "47554d49-0011-4f4d-492d-435631000003",
                ),
                familyIdentityServiceHasCharacteristics = false,
            )
        }
    }

    private fun validate(bytes: ByteArray) =
        OmiCv1LegacyStorageReclaimerStatusProtocol.validate(
            statusBytes = bytes,
            discoveredServiceUuids = baselineServices,
            reclaimerCharacteristicUuids = setOf(
                OmiCv1LegacyStorageReclaimerStatusProtocol.STATUS_CHARACTERISTIC_UUID,
            ),
            familyIdentityServiceHasCharacteristics = false,
        )

    private fun status(
        phase: OmiCv1LegacyStorageReclaimerPhase,
        flags: Int,
        lastError: Int = 0,
        targetSize: Long = 0,
        freeBefore: Long = 0,
        freeAfter: Long = 0,
    ): ByteArray = ByteArray(OmiCv1LegacyStorageReclaimerStatusProtocol.STATUS_WIRE_SIZE).apply {
        this[0] = 1
        this[1] = phase.wireValue.toByte()
        putU16(2, flags)
        putU32(4, lastError)
        putU32(8, 5)
        putU64(12, targetSize)
        putU64(20, freeBefore)
        putU64(28, freeAfter)
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        for (index in 0 until 2) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Int) {
        for (index in 0 until 4) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun ByteArray.putU64(offset: Int, value: Long) {
        for (index in 0 until 8) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun assertRejected(block: () -> Unit) {
        val error = assertFailsWith<OmiCv1ApplicationUpdateException>(block = block)
        assertEquals(
            OmiCv1ApplicationUpdateFailureCode.LEGACY_STORAGE_RECLAIMER_EVIDENCE_REJECTED,
            error.code,
        )
    }

    private companion object {
        val baselineServices = setOf(
            OmiCv1LegacyStorageReclaimerStatusProtocol.SERVICE_UUID,
            OmiCv1RecoveryStatusProtocol.RECOVERY_SERVICE_UUID,
            OmiCv1LegacyStorageReclaimerStatusProtocol.FAMILY_IDENTITY_SERVICE_UUID,
        )

        val successfulStatus =
            ByteArray(OmiCv1LegacyStorageReclaimerStatusProtocol.STATUS_WIRE_SIZE).apply {
                this[0] = 1
                this[1] = OmiCv1LegacyStorageReclaimerPhase.RECLAIMED.wireValue.toByte()
                this[2] = 0xff.toByte()
                this[3] = 0x01
                val target =
                    OmiCv1LegacyStorageReclaimerStatusProtocol.EXACT_TARGET_SIZE_BYTES
                for (index in 0 until 8) {
                    this[12 + index] = (target ushr (index * 8)).toByte()
                }
                val free = 500L * 1024L * 1024L
                for (index in 0 until 8) {
                    this[28 + index] = (free ushr (index * 8)).toByte()
                }
            }
    }
}
