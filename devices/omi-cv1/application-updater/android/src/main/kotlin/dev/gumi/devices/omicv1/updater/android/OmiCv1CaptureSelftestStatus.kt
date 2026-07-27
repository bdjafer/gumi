package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate

internal fun interface OmiCv1CaptureSelftestProbe {
    suspend fun inspect(endpoint: EndpointCandidate): OmiCv1CaptureSelftestEvidence
}

internal interface OmiCv1CaptureSelftestRunner {
    suspend fun run(
        endpoint: EndpointCandidate,
        onArmed: (OmiCv1CaptureSelftestEvidence) -> Unit,
    ): OmiCv1CaptureSelftestEvidence
}

internal enum class OmiCv1CaptureSelftestPhase(val wireValue: Int) {
    IDLE(0),
    ARMED(1),
    ASSERTING_PRIVACY(2),
    OPENING_CODEC(3),
    ACQUIRING_MICROPHONE(4),
    EXERCISING(5),
    RELEASING_MICROPHONE(6),
    CLOSING_CODEC(7),
    DEASSERTING_PRIVACY(8),
    PASSED(9),
    FAILED_SAFE(10),
    FAILED_MICROPHONE_UNKNOWN(11),
    ;

    val terminal: Boolean
        get() = this == PASSED || this == FAILED_SAFE || this == FAILED_MICROPHONE_UNKNOWN
}

internal enum class OmiCv1CaptureSelftestFailure(val wireValue: Int) {
    NONE(0),
    CONFIRMATION_EXPIRED(1),
    PRIVACY_ASSERT(2),
    CODEC_OPEN(3),
    MICROPHONE_ACQUIRE(4),
    MICROPHONE_RELEASE(5),
    CODEC_CLOSE(6),
    PRIVACY_DEASSERT(7),
    INSUFFICIENT_PCM(8),
    INSUFFICIENT_OPUS(9),
    CODEC_DROPPED_SAMPLES(10),
    CODEC_TERMINAL(11),
    ASYNC_PORT(12),
}

internal data class OmiCv1CaptureSelftestEvidence(
    val schemaVersion: Int,
    val phase: OmiCv1CaptureSelftestPhase,
    val failure: OmiCv1CaptureSelftestFailure,
    val flags: Int,
    val attempt: Long,
    val pcmBlocks: Long,
    val pcmSamples: Long,
    val opusPackets: Long,
    val discardedSamples: Long,
    val terminalError: Int,
    val leaseRemainingMillis: Long,
    val privacyAsserted: Boolean,
    val microphoneVerifiedOff: Boolean,
    val codecOpen: Boolean,
    val armed: Boolean,
    val exercising: Boolean,
    val passedFlag: Boolean,
    val microphoneUnknown: Boolean,
    val recoveryTransportPresent: Boolean,
    val stockIdentityServiceEmpty: Boolean,
    val functionalOmiServicesAbsent: Boolean,
    val rawHex: String,
) {
    val passedSafely: Boolean
        get() = phase == OmiCv1CaptureSelftestPhase.PASSED &&
            failure == OmiCv1CaptureSelftestFailure.NONE &&
            !privacyAsserted && microphoneVerifiedOff && !codecOpen &&
            pcmSamples >= OmiCv1CaptureSelftestProtocol.MINIMUM_PCM_SAMPLES &&
            opusPackets >= OmiCv1CaptureSelftestProtocol.MINIMUM_OPUS_PACKETS &&
            discardedSamples == 0L && terminalError == 0
}

internal object OmiCv1CaptureSelftestProtocol {
    const val SERVICE_UUID = "f80a6e60-3b3f-4e8a-93e4-5f5e2c527001"
    const val STATUS_CHARACTERISTIC_UUID = "f80a6e61-3b3f-4e8a-93e4-5f5e2c527001"
    const val ARM_CHARACTERISTIC_UUID = "f80a6e62-3b3f-4e8a-93e4-5f5e2c527001"
    const val STOCK_IDENTITY_SERVICE_UUID = "19b10000-e8f2-537e-4f6c-d104768a1214"
    const val STATUS_WIRE_SIZE = 32
    const val MINIMUM_PCM_SAMPLES = 32_000L
    const val MINIMUM_OPUS_PACKETS = 100L
    val ARM_VALUE: ByteArray get() = byteArrayOf(0x01)

    private val forbiddenFunctionalServiceUuids = setOf(
        "0000180f-0000-1000-8000-00805f9b34fb", // Battery
        "23ba7924-0000-1000-7450-346eac492e92",
        "cab1ab95-2ea5-4f4d-bb56-874b72cfc984",
        "19b10010-e8f2-537e-4f6c-d104768a1214", // Settings
        "19b10020-e8f2-537e-4f6c-d104768a1214", // Features
        "30295780-4301-eabd-2904-2849adfeae43", // Offline storage
    )

    fun validate(
        statusBytes: ByteArray,
        discoveredServiceUuids: Set<String>,
        stockIdentityServiceHasCharacteristics: Boolean,
    ): OmiCv1CaptureSelftestEvidence {
        val services = discoveredServiceUuids.mapTo(mutableSetOf()) { it.lowercase() }
        rejectUnless(SERVICE_UUID in services) { "Capture self-test service is absent" }
        rejectUnless(STOCK_IDENTITY_SERVICE_UUID in services) {
            "The empty Omi-family identity service is absent"
        }
        rejectUnless(!stockIdentityServiceHasCharacteristics) {
            "Omi-family identity service is not empty in capture self-test mode"
        }
        val unexpectedServices = services.intersect(forbiddenFunctionalServiceUuids)
        rejectUnless(unexpectedServices.isEmpty()) {
            "Functional Omi services are unexpectedly present: ${unexpectedServices.sorted().joinToString()}"
        }
        rejectUnless(statusBytes.size == STATUS_WIRE_SIZE) {
            "Capture self-test status must contain exactly $STATUS_WIRE_SIZE bytes"
        }

        val schema = statusBytes.u8(0)
        val phaseValue = statusBytes.u8(1)
        val failureValue = statusBytes.u8(2)
        val flags = statusBytes.u8(3)
        val phase = OmiCv1CaptureSelftestPhase.entries.singleOrNull {
            it.wireValue == phaseValue
        }
        val failure = OmiCv1CaptureSelftestFailure.entries.singleOrNull {
            it.wireValue == failureValue
        }
        rejectUnless(schema == 1) { "Unsupported capture self-test schema $schema" }
        rejectUnless(phase != null) { "Unknown capture self-test phase $phaseValue" }
        rejectUnless(failure != null) { "Unknown capture self-test failure $failureValue" }

        val evidence = OmiCv1CaptureSelftestEvidence(
            schemaVersion = schema,
            phase = requireNotNull(phase),
            failure = requireNotNull(failure),
            flags = flags,
            attempt = statusBytes.u32le(4),
            pcmBlocks = statusBytes.u32le(8),
            pcmSamples = statusBytes.u32le(12),
            opusPackets = statusBytes.u32le(16),
            discardedSamples = statusBytes.u32le(20),
            terminalError = statusBytes.i32le(24),
            leaseRemainingMillis = statusBytes.u32le(28),
            privacyAsserted = flags and PRIVACY_ASSERTED != 0,
            microphoneVerifiedOff = flags and MICROPHONE_VERIFIED_OFF != 0,
            codecOpen = flags and CODEC_OPEN != 0,
            armed = flags and ARMED != 0,
            exercising = flags and EXERCISING != 0,
            passedFlag = flags and PASSED != 0,
            microphoneUnknown = flags and MICROPHONE_UNKNOWN != 0,
            recoveryTransportPresent = flags and RECOVERY_TRANSPORT_PRESENT != 0,
            stockIdentityServiceEmpty = true,
            functionalOmiServicesAbsent = true,
            rawHex = statusBytes.joinToString("") {
                it.toUByte().toString(16).padStart(2, '0')
            },
        )
        validateInvariants(evidence)
        return evidence
    }

    fun requireSafeBaseline(evidence: OmiCv1CaptureSelftestEvidence) {
        rejectUnless(evidence.phase in safeBaselinePhases) {
            "Capture self-test is not in a re-armable terminal state (${evidence.phase})"
        }
        rejectUnless(
            !evidence.privacyAsserted && evidence.microphoneVerifiedOff && !evidence.codecOpen,
        ) {
            "Capture self-test baseline does not prove privacy-off, microphone-off, and codec-closed"
        }
        rejectUnless(!evidence.microphoneUnknown) {
            "Capture self-test baseline reports unknown microphone state"
        }
        if (evidence.phase == OmiCv1CaptureSelftestPhase.FAILED_SAFE) {
            rejectUnless(evidence.failure in rearmableSafeFailures) {
                "Capture self-test failure ${evidence.failure} is safe but not re-armable"
            }
        }
    }

    private fun validateInvariants(evidence: OmiCv1CaptureSelftestEvidence) {
        rejectUnless(evidence.recoveryTransportPresent) {
            "Capture self-test recovery transport is not present"
        }
        rejectUnless(
            evidence.phase !in noFailurePhases ||
                evidence.failure == OmiCv1CaptureSelftestFailure.NONE,
        ) {
            "Capture self-test ${evidence.phase} phase unexpectedly reports ${evidence.failure}"
        }
        rejectUnless(
            !evidence.phase.terminal ||
                evidence.phase == OmiCv1CaptureSelftestPhase.PASSED ||
                evidence.failure != OmiCv1CaptureSelftestFailure.NONE,
        ) {
            "Failed capture self-test terminal phase has no failure reason"
        }
        rejectUnless(evidence.armed == (evidence.phase == OmiCv1CaptureSelftestPhase.ARMED)) {
            "Capture self-test armed flag disagrees with phase"
        }
        rejectUnless(evidence.exercising == (evidence.phase == OmiCv1CaptureSelftestPhase.EXERCISING)) {
            "Capture self-test exercising flag disagrees with phase"
        }
        rejectUnless(evidence.passedFlag == (evidence.phase == OmiCv1CaptureSelftestPhase.PASSED)) {
            "Capture self-test passed flag disagrees with phase"
        }
        rejectUnless(
            evidence.microphoneUnknown ==
                (evidence.phase == OmiCv1CaptureSelftestPhase.FAILED_MICROPHONE_UNKNOWN),
        ) {
            "Capture self-test microphone-unknown flag disagrees with phase"
        }
        rejectUnless(
            (evidence.phase == OmiCv1CaptureSelftestPhase.ARMED) ==
                (evidence.leaseRemainingMillis > 0L),
        ) {
            "Capture self-test lease disagrees with armed phase"
        }
        if (evidence.phase in quiescentSuccessPhases) {
            rejectUnless(
                !evidence.privacyAsserted && evidence.microphoneVerifiedOff && !evidence.codecOpen,
            ) {
                "Capture self-test ${evidence.phase} phase lacks a safe quiescent lifecycle"
            }
        }
        if (evidence.phase == OmiCv1CaptureSelftestPhase.PASSED) {
            rejectUnless(evidence.passedSafely) {
                "Capture self-test pass lacks exact safe lifecycle evidence"
            }
        }
        if (evidence.phase == OmiCv1CaptureSelftestPhase.FAILED_MICROPHONE_UNKNOWN) {
            rejectUnless(
                evidence.privacyAsserted && !evidence.microphoneVerifiedOff,
            ) {
                "Unknown microphone state is not held fail-closed under red privacy output"
            }
        }
        if (evidence.phase.terminal) {
            rejectUnless(evidence.leaseRemainingMillis == 0L) {
                "Terminal capture self-test status retains an active lease"
            }
        }
    }

    private fun rejectUnless(condition: Boolean, message: () -> String) {
        if (!condition) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.CAPTURE_SELFTEST_EVIDENCE_REJECTED,
                message(),
            )
        }
    }

    private val safeBaselinePhases = setOf(
        OmiCv1CaptureSelftestPhase.IDLE,
        OmiCv1CaptureSelftestPhase.PASSED,
        OmiCv1CaptureSelftestPhase.FAILED_SAFE,
    )
    private val noFailurePhases = setOf(
        OmiCv1CaptureSelftestPhase.IDLE,
        OmiCv1CaptureSelftestPhase.ARMED,
        OmiCv1CaptureSelftestPhase.PASSED,
    )
    private val quiescentSuccessPhases = setOf(
        OmiCv1CaptureSelftestPhase.IDLE,
        OmiCv1CaptureSelftestPhase.ARMED,
        OmiCv1CaptureSelftestPhase.PASSED,
    )
    private val rearmableSafeFailures = setOf(
        OmiCv1CaptureSelftestFailure.CONFIRMATION_EXPIRED,
        OmiCv1CaptureSelftestFailure.MICROPHONE_ACQUIRE,
        OmiCv1CaptureSelftestFailure.INSUFFICIENT_PCM,
        OmiCv1CaptureSelftestFailure.INSUFFICIENT_OPUS,
        OmiCv1CaptureSelftestFailure.CODEC_DROPPED_SAMPLES,
        OmiCv1CaptureSelftestFailure.CODEC_TERMINAL,
    )
    private const val PRIVACY_ASSERTED = 1 shl 0
    private const val MICROPHONE_VERIFIED_OFF = 1 shl 1
    private const val CODEC_OPEN = 1 shl 2
    private const val ARMED = 1 shl 3
    private const val EXERCISING = 1 shl 4
    private const val PASSED = 1 shl 5
    private const val MICROPHONE_UNKNOWN = 1 shl 6
    private const val RECOVERY_TRANSPORT_PRESENT = 1 shl 7
}

private fun ByteArray.u8(offset: Int): Int = this[offset].toUByte().toInt()

private fun ByteArray.u32le(offset: Int): Long =
    u8(offset).toLong() or
        (u8(offset + 1).toLong() shl 8) or
        (u8(offset + 2).toLong() shl 16) or
        (u8(offset + 3).toLong() shl 24)

private fun ByteArray.i32le(offset: Int): Int = u32le(offset).toInt()
