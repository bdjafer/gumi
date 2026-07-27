package dev.gumi.devices.omicv1.updater.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OmiCv1CaptureSelftestStatusTest {
    @Test
    fun `decodes exact safe idle baseline`() {
        val evidence = validate(status(phase = 0, flags = 0x82))

        assertEquals(OmiCv1CaptureSelftestPhase.IDLE, evidence.phase)
        assertTrue(evidence.microphoneVerifiedOff)
        assertTrue(evidence.recoveryTransportPresent)
        assertFalse(evidence.privacyAsserted)
        OmiCv1CaptureSelftestProtocol.requireSafeBaseline(evidence)
    }

    @Test
    fun `accepts exact safe pass with minimum lifecycle evidence`() {
        val evidence = validate(
            status(
                phase = 9,
                flags = 0xa2,
                attempt = 3,
                pcmBlocks = 101,
                pcmSamples = 32_000,
                opusPackets = 100,
            ),
        )

        assertTrue(evidence.passedSafely)
        assertEquals(3, evidence.attempt)
        OmiCv1CaptureSelftestProtocol.requireSafeBaseline(evidence)
    }

    @Test
    fun `rejects pass below evidence thresholds`() {
        assertRejected {
            validate(
                status(
                    phase = 9,
                    flags = 0xa2,
                    attempt = 1,
                    pcmSamples = 31_999,
                    opusPackets = 100,
                ),
            )
        }
    }

    @Test
    fun `rejects pass with discarded samples or terminal codec error`() {
        assertRejected {
            validate(
                status(
                    phase = 9,
                    flags = 0xa2,
                    attempt = 1,
                    pcmSamples = 32_000,
                    opusPackets = 100,
                    discardedSamples = 1,
                ),
            )
        }
        assertRejected {
            validate(
                status(
                    phase = 9,
                    flags = 0xa2,
                    attempt = 1,
                    pcmSamples = 32_000,
                    opusPackets = 100,
                    terminalError = -5,
                ),
            )
        }
    }

    @Test
    fun `accepts but never re-arms fail-closed microphone unknown`() {
        val evidence = validate(status(phase = 11, failure = 5, flags = 0xc5, attempt = 1))

        assertTrue(evidence.microphoneUnknown)
        assertTrue(evidence.privacyAsserted)
        assertFalse(evidence.microphoneVerifiedOff)
        assertTrue(evidence.codecOpen)
        assertRejected { OmiCv1CaptureSelftestProtocol.requireSafeBaseline(evidence) }
    }

    @Test
    fun `re-arms only failures whose hardware ports remain trustworthy`() {
        val expired = validate(status(phase = 10, failure = 1, flags = 0x82, attempt = 1))
        OmiCv1CaptureSelftestProtocol.requireSafeBaseline(expired)

        val privacyPortFailure = validate(status(phase = 10, failure = 2, flags = 0x82, attempt = 2))
        assertRejected { OmiCv1CaptureSelftestProtocol.requireSafeBaseline(privacyPortFailure) }
    }

    @Test
    fun `rejects phase flag disagreement`() {
        assertRejected { validate(status(phase = 1, flags = 0x82, leaseMillis = 15_000)) }
        assertRejected { validate(status(phase = 0, flags = 0x8a)) }
    }

    @Test
    fun `rejects impossible failure lease and quiescent combinations`() {
        assertRejected { validate(status(phase = 0, failure = 1, flags = 0x82)) }
        assertRejected { validate(status(phase = 10, failure = 0, flags = 0x82)) }
        assertRejected { validate(status(phase = 1, flags = 0x8a, leaseMillis = 0)) }
        assertRejected { validate(status(phase = 0, flags = 0x82, leaseMillis = 1)) }
        assertRejected { validate(status(phase = 0, flags = 0x83)) }
    }

    @Test
    fun `rejects missing transport and malformed status`() {
        assertRejected { validate(status(phase = 0, flags = 0x02)) }
        assertRejected { validate(ByteArray(31)) }
    }

    @Test
    fun `rejects stock functional services and non-empty identity`() {
        assertRejected(
            services = services + "30295780-4301-eabd-2904-2849adfeae43",
        ) { validate(status(phase = 0, flags = 0x82), services = it) }
        assertRejected {
            OmiCv1CaptureSelftestProtocol.validate(
                statusBytes = status(phase = 0, flags = 0x82),
                discoveredServiceUuids = services,
                stockIdentityServiceHasCharacteristics = true,
            )
        }
    }

    private fun validate(
        bytes: ByteArray,
        services: Set<String> = this.services,
    ) = OmiCv1CaptureSelftestProtocol.validate(
        statusBytes = bytes,
        discoveredServiceUuids = services,
        stockIdentityServiceHasCharacteristics = false,
    )

    private fun status(
        phase: Int,
        failure: Int = 0,
        flags: Int,
        attempt: Long = 0,
        pcmBlocks: Long = 0,
        pcmSamples: Long = 0,
        opusPackets: Long = 0,
        discardedSamples: Long = 0,
        terminalError: Int = 0,
        leaseMillis: Long = 0,
    ): ByteArray = ByteArray(OmiCv1CaptureSelftestProtocol.STATUS_WIRE_SIZE).also { bytes ->
        bytes[0] = 1
        bytes[1] = phase.toByte()
        bytes[2] = failure.toByte()
        bytes[3] = flags.toByte()
        bytes.putU32(4, attempt)
        bytes.putU32(8, pcmBlocks)
        bytes.putU32(12, pcmSamples)
        bytes.putU32(16, opusPackets)
        bytes.putU32(20, discardedSamples)
        bytes.putU32(24, terminalError.toLong() and 0xffff_ffffL)
        bytes.putU32(28, leaseMillis)
    }

    private fun ByteArray.putU32(offset: Int, value: Long) {
        for (index in 0 until 4) {
            this[offset + index] = (value ushr (index * 8)).toByte()
        }
    }

    private fun assertRejected(block: () -> Unit) {
        val error = assertFailsWith<OmiCv1ApplicationUpdateException> { block() }
        assertEquals(
            OmiCv1ApplicationUpdateFailureCode.CAPTURE_SELFTEST_EVIDENCE_REJECTED,
            error.code,
        )
    }

    private fun assertRejected(
        services: Set<String>,
        block: (Set<String>) -> Unit,
    ) = assertRejected { block(services) }

    private val services = setOf(
        OmiCv1CaptureSelftestProtocol.SERVICE_UUID,
        OmiCv1CaptureSelftestProtocol.STOCK_IDENTITY_SERVICE_UUID,
    )
}
