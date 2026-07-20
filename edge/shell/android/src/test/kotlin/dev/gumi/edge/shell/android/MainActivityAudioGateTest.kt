package dev.gumi.edge.shell.android

import dev.gumi.devices.omicv1.OmiCv1FirmwareOracleStatus
import dev.gumi.devices.omicv1.OmiCv1GumiCanary0001FirmwareOracle
import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import dev.gumi.edge.shell.android.diagnostics.DiagnosticOperation
import dev.gumi.edge.shell.android.diagnostics.DiagnosticOperationGateState
import dev.gumi.edge.shell.android.diagnostics.FirmwareImageProbeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainActivityAudioGateTest {
    @Test
    fun `published image match unlocks only its process local endpoint`() {
        val matched = endpoint("ble:redacted-matched")
        val different = endpoint("ble:redacted-different")
        val state = FirmwareImageProbeState(
            inspection = matchingInspection(matched),
            assessmentStatus = OmiCv1FirmwareOracleStatus.MATCHES_PUBLISHED_V3012.name,
            successfulEndpointEphemeralId = matched.ephemeralId,
        )

        assertTrue(firmwareQualifiesAudioEndpoint(state, matched))
        assertFalse(firmwareQualifiesAudioEndpoint(state, different))
    }

    @Test
    fun `exact application match with unobserved network unlocks only bounded diagnostics`() {
        val matched = endpoint("ble:redacted-application-match")
        val inspection = applicationOnlyInspection(matched)
        val state = FirmwareImageProbeState(
            inspection = inspection,
            assessmentStatus =
                OmiCv1FirmwareOracleStatus.APPLICATION_MATCH_NETWORK_UNOBSERVED.name,
            successfulEndpointEphemeralId = matched.ephemeralId,
        )

        assertTrue(firmwareQualifiesAudioEndpoint(state, matched))
    }

    @Test
    fun `exact canary application with unobserved network unlocks bounded diagnostics`() {
        val matched = endpoint("ble:redacted-canary")
        val inspection = FirmwareImageStateInspection(
            endpoint = matched,
            protocol = "mcumgr-smp",
            slots = listOf(
                matchingSlot(
                    imageNumber = 0,
                    hash = OmiCv1GumiCanary0001FirmwareOracle.APPLICATION_IMAGE_HASH,
                    version = OmiCv1StockV3012FirmwareOracle.MCUMGR_WIRE_VERSION,
                ),
            ),
            splitStatus = 0,
        )
        val state = FirmwareImageProbeState(
            inspection = inspection,
            assessmentStatus =
                OmiCv1FirmwareOracleStatus.GUMI_CANARY_APPLICATION_MATCH_NETWORK_UNOBSERVED.name,
            successfulEndpointEphemeralId = matched.ephemeralId,
        )

        assertTrue(firmwareQualifiesAudioEndpoint(state, matched))
    }

    @Test
    fun `assessment status inconsistent with observed slots stays locked`() {
        val endpoint = endpoint("ble:redacted-inconsistent")

        assertFalse(
            firmwareQualifiesAudioEndpoint(
                FirmwareImageProbeState(
                    inspection = applicationOnlyInspection(endpoint),
                    assessmentStatus = OmiCv1FirmwareOracleStatus.MATCHES_PUBLISHED_V3012.name,
                    successfulEndpointEphemeralId = endpoint.ephemeralId,
                ),
                endpoint,
            ),
        )
    }

    @Test
    fun `new in progress firmware attempt cannot reuse stale prior success`() {
        val prior = endpoint("ble:redacted-prior")

        assertFalse(
            firmwareQualifiesAudioEndpoint(
                FirmwareImageProbeState(inspecting = true),
                prior,
            ),
        )
    }

    @Test
    fun `matching bytes without the allowlisted assessment status stay locked`() {
        val endpoint = endpoint("ble:redacted-unassessed")

        assertFalse(
            firmwareQualifiesAudioEndpoint(
                FirmwareImageProbeState(
                    inspection = matchingInspection(endpoint),
                    successfulEndpointEphemeralId = endpoint.ephemeralId,
                ),
                endpoint,
            ),
        )
    }

    @Test
    fun `generic firmware assessment exposes only the stable oracle status`() {
        val endpoint = endpoint("ble:must-not-appear-in-assessment")

        val status = omiCv1V3012FirmwareAssessment.assess(matchingInspection(endpoint))

        assertEquals(OmiCv1FirmwareOracleStatus.MATCHES_PUBLISHED_V3012.name, status)
        assertFalse(endpoint.ephemeralId in status)
    }

    @Test
    fun `shared busy gate blocks starts but keeps only active cancel available`() {
        val active = DiagnosticOperationGateState(
            activeOperation = DiagnosticOperation.DRIVER_NEGOTIATION,
            cancelling = true,
        )

        assertFalse(active.allowsDiagnosticStart())
        assertTrue(active.allowsCancel(DiagnosticOperation.DRIVER_NEGOTIATION))
        assertFalse(active.allowsCancel(DiagnosticOperation.AUDIO_METADATA))

        val idle = DiagnosticOperationGateState()
        assertTrue(idle.allowsDiagnosticStart())
        assertFalse(idle.allowsCancel(DiagnosticOperation.DRIVER_NEGOTIATION))
    }

    @Test
    fun `replacement activity observes the process scoped diagnostic lease`() {
        val priorActivityLease = requireNotNull(
            androidProcessDiagnosticOperationGate.tryAcquire(DiagnosticOperation.GATT_INSPECTION),
        )
        try {
            assertNull(
                androidProcessDiagnosticOperationGate.tryAcquire(
                    DiagnosticOperation.DRIVER_NEGOTIATION,
                ),
            )
        } finally {
            priorActivityLease.close()
        }
    }

    @Test
    fun `new scan generation atomically drops both reviewed endpoints`() {
        val endpoint = endpoint("ble:redacted-reviewed")
        val reviewed = ReviewedDiagnosticEndpoints(
            firmware = endpoint,
            audio = endpoint,
        )

        val nextGeneration = reviewed.invalidateForNewScan()

        assertNull(nextGeneration.firmware)
        assertNull(nextGeneration.audio)
    }

    @Test
    fun `audio setup timeout names the proven stock wake recovery without inferring pairing`() {
        val guidance = requireNotNull(audioProbeFailureGuidance("AUDIO_SETUP_TIMEOUT"))

        assertTrue("charger insertion" in guidance)
        assertTrue("fresh scan" in guidance)
        assertTrue("does not mean pairing is required" in guidance)
        assertNull(audioProbeFailureGuidance("AUDIO_SEQUENCE_GAP"))
    }
}

private fun matchingInspection(endpoint: EndpointCandidate) = FirmwareImageStateInspection(
    endpoint = endpoint,
    protocol = "mcumgr-smp",
    slots = listOf(
        matchingSlot(
            imageNumber = 0,
            hash = OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH,
        ),
        matchingSlot(
            imageNumber = 1,
            hash = OmiCv1StockV3012FirmwareOracle.NETWORK_IMAGE_HASH,
        ),
    ),
    splitStatus = null,
)

private fun applicationOnlyInspection(endpoint: EndpointCandidate) = FirmwareImageStateInspection(
    endpoint = endpoint,
    protocol = "mcumgr-smp",
    slots = listOf(
        matchingSlot(
            imageNumber = 0,
            hash = OmiCv1StockV3012FirmwareOracle.APPLICATION_IMAGE_HASH,
            version = OmiCv1StockV3012FirmwareOracle.MCUMGR_WIRE_VERSION,
        ),
    ),
    splitStatus = 0,
)

private fun matchingSlot(
    imageNumber: Int,
    hash: String,
    version: String = OmiCv1StockV3012FirmwareOracle.MCUBOOT_VERSION,
) = FirmwareImageSlot(
    imageNumber = imageNumber,
    slotNumber = 0,
    version = version,
    hash = FirmwareImageHash(hash),
    bootable = true,
    pending = false,
    confirmed = true,
    active = true,
    permanent = false,
    compressed = false,
)

private fun endpoint(id: String) = EndpointCandidate(
    transport = TransportKind.BLE,
    ephemeralId = id,
)
