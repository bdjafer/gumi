package dev.gumi.devices.omicv1.updater.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class OmiCv1RecoveryStatusTest {
    @Test
    fun `exact recovery safe-mode evidence is accepted`() {
        val evidence = OmiCv1RecoveryStatusProtocol.validate(
            statusBytes = byteArrayOf(0x01, 0x07, 0x01, 0x23),
            discoveredServiceUuids = baselineServices,
            stockIdentityServiceHasCharacteristics = false,
        )

        assertEquals("01070123", evidence.rawHex)
        assertTrue(evidence.recoveryTransportReady)
        assertTrue(evidence.microphoneVerifiedOff)
        assertFalse(evidence.capturePermitted)
        assertTrue(evidence.overwriteOnlyBootPolicyObserved)
        assertTrue(evidence.stockIdentityServiceEmpty)
        assertTrue(evidence.functionalOmiServicesAbsent)
    }

    @Test
    fun `capture admission or a functional service rejects recovery evidence`() {
        assertRejected {
            OmiCv1RecoveryStatusProtocol.validate(
                statusBytes = byteArrayOf(0x01, 0x07, 0x01, 0x33),
                discoveredServiceUuids = baselineServices,
                stockIdentityServiceHasCharacteristics = false,
            )
        }
        assertRejected {
            OmiCv1RecoveryStatusProtocol.validate(
                statusBytes = byteArrayOf(0x01, 0x07, 0x01, 0x23),
                discoveredServiceUuids = baselineServices + "19b10010-e8f2-537e-4f6c-d104768a1214",
                stockIdentityServiceHasCharacteristics = false,
            )
        }
        assertRejected {
            OmiCv1RecoveryStatusProtocol.validate(
                statusBytes = byteArrayOf(0x01, 0x07, 0x01, 0x23),
                discoveredServiceUuids = baselineServices,
                stockIdentityServiceHasCharacteristics = true,
            )
        }
    }

    @Test
    fun `missing identity topology or transitional supervisor state rejects evidence`() {
        assertRejected {
            OmiCv1RecoveryStatusProtocol.validate(
                statusBytes = byteArrayOf(0x01, 0x02, 0x01, 0x21),
                discoveredServiceUuids = baselineServices,
                stockIdentityServiceHasCharacteristics = false,
            )
        }
        assertRejected {
            OmiCv1RecoveryStatusProtocol.validate(
                statusBytes = byteArrayOf(0x01, 0x07, 0x01, 0x23),
                discoveredServiceUuids = setOf(OmiCv1RecoveryStatusProtocol.RECOVERY_SERVICE_UUID),
                stockIdentityServiceHasCharacteristics = false,
            )
        }
    }

    private fun assertRejected(block: () -> Unit) {
        val error = assertFailsWith<OmiCv1ApplicationUpdateException>(block = block)
        assertEquals(OmiCv1ApplicationUpdateFailureCode.RECOVERY_EVIDENCE_REJECTED, error.code)
    }

    private companion object {
        val baselineServices = setOf(
            OmiCv1RecoveryStatusProtocol.RECOVERY_SERVICE_UUID,
            OmiCv1RecoveryStatusProtocol.STOCK_IDENTITY_SERVICE_UUID,
            "8d53dc1d-1db7-4cd3-868b-8a527460aa84",
            "0000180a-0000-1000-8000-00805f9b34fb",
        )
    }
}
