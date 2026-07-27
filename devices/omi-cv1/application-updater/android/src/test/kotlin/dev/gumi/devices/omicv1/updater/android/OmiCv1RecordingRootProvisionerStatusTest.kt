package dev.gumi.devices.omicv1.updater.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OmiCv1RecordingRootProvisionerStatusTest {
    @Test
    fun `fresh one-time MEXT write is accepted without secret evidence`() {
        val evidence = validate(
            byteArrayOf(
                0x01, 0x03, 0x3f, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x05, 0x00, 0x00, 0x00,
            ),
        )

        assertTrue(evidence.provisioningComplete)
        assertTrue(evidence.writeAttempted)
        assertTrue(evidence.mextPresent)
        assertTrue(evidence.derivationVerified)
        assertTrue(evidence.mutationAdmitted)
        assertEquals(5L, evidence.generation)
        assertEquals("01033f000000000005000000", evidence.rawHex)
    }

    @Test
    fun `already-present MEXT root is accepted without another write`() {
        val evidence = validate(
            byteArrayOf(
                0x01, 0x04, 0x3b, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x04, 0x00, 0x00, 0x00,
            ),
        )

        assertTrue(evidence.provisioningComplete)
        assertFalse(evidence.writeAttempted)
        assertTrue(evidence.mutationAdmitted)
    }

    @Test
    fun `transitional failed or overexposed provisioner state is rejected`() {
        assertRejected {
            validate(
                byteArrayOf(
                    0x01, 0x02, 0x07, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                    0x03, 0x00, 0x00, 0x00,
                ),
            )
        }
        assertRejected {
            validate(
                byteArrayOf(
                    0x01, 0x03, 0x1f, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                    0x05, 0x00, 0x00, 0x00,
                ),
            )
        }
        assertRejected {
            validate(
                byteArrayOf(
                    0x01, 0x05, 0x03, 0x00,
                    0xfb.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                    0x04, 0x00, 0x00, 0x00,
                ),
            )
        }
        assertRejected {
            OmiCv1RecordingRootProvisionerStatusProtocol.validate(
                statusBytes = successfulStatus,
                discoveredServiceUuids = baselineServices,
                provisionerCharacteristicUuids = setOf(
                    OmiCv1RecordingRootProvisionerStatusProtocol.STATUS_CHARACTERISTIC_UUID,
                    "47554d49-0010-4f4d-492d-435631000003",
                ),
                familyIdentityServiceHasCharacteristics = false,
            )
        }
    }

    private fun validate(bytes: ByteArray) =
        OmiCv1RecordingRootProvisionerStatusProtocol.validate(
            statusBytes = bytes,
            discoveredServiceUuids = baselineServices,
            provisionerCharacteristicUuids = setOf(
                OmiCv1RecordingRootProvisionerStatusProtocol.STATUS_CHARACTERISTIC_UUID,
            ),
            familyIdentityServiceHasCharacteristics = false,
        )

    private fun assertRejected(block: () -> Unit) {
        val error = assertFailsWith<OmiCv1ApplicationUpdateException>(block = block)
        assertEquals(
            OmiCv1ApplicationUpdateFailureCode.RECORDING_ROOT_PROVISIONER_EVIDENCE_REJECTED,
            error.code,
        )
    }

    private companion object {
        val successfulStatus = byteArrayOf(
            0x01, 0x03, 0x3f, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x05, 0x00, 0x00, 0x00,
        )
        val baselineServices = setOf(
            OmiCv1RecordingRootProvisionerStatusProtocol.SERVICE_UUID,
            OmiCv1RecoveryStatusProtocol.RECOVERY_SERVICE_UUID,
            OmiCv1RecordingRootProvisionerStatusProtocol.FAMILY_IDENTITY_SERVICE_UUID,
        )
    }
}
