package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OmiCv1ApplicationUpdatePlannerTest {
    @Test
    fun `prepares an image-0 plan only from exact stable source state`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest)

        val plan = OmiCv1ApplicationUpdatePlanner.prepare(
            endpoint,
            stableInspection(endpoint, release.source),
            release,
            fixture.bytes,
        )

        assertEquals(release.target.mcubootImageHash, plan.artifactEvidence.mcubootImageHash)
        assertEquals(64, plan.planId.length)
    }

    @Test
    fun `also accepts the exact canonical zero-build header form`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest)
        val canonical = stableInspection(endpoint, release.source).copy(
            slots = stableInspection(endpoint, release.source).slots.map { slot ->
                if (slot.active) slot.copy(version = release.source.mcubootVersion) else slot
            },
        )

        val plan = OmiCv1ApplicationUpdatePlanner.prepare(endpoint, canonical, release, fixture.bytes)

        assertEquals(release.target.mcubootImageHash, plan.artifactEvidence.mcubootImageHash)
    }

    @Test
    fun `rejects image-state evidence from a different ephemeral endpoint`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val release = release(fixture.manifest)

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1ApplicationUpdatePlanner.prepare(
                endpoint("selected"),
                stableInspection(endpoint("other"), release.source),
                release,
                fixture.bytes,
            )
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.ENDPOINT_MISMATCH, error.code)
    }

    @Test
    fun `rejects an update target with the active application hash`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()

        assertFailsWith<IllegalArgumentException> {
            release(
                fixture.manifest,
                sourceApplicationHash = fixture.manifest.mcubootImageHash,
            )
        }
    }

    @Test
    fun `rejects a source state with the wrong active network image`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest)
        val wrongNetwork = stableInspection(endpoint, release.source).copy(
            slots = stableInspection(endpoint, release.source).slots.map { slot ->
                if (slot.imageNumber == 1 && slot.active) {
                    slot.copy(hash = FirmwareImageHash("44".repeat(32)))
                } else {
                    slot
                }
            },
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1ApplicationUpdatePlanner.prepare(endpoint, wrongNetwork, release, fixture.bytes)
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED, error.code)
    }

    @Test
    fun `strict releases reject an application-match network-unobserved state`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest)
        val networkUnobserved = networkUnobservedInspection(endpoint, release.source)

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1ApplicationUpdatePlanner.prepare(endpoint, networkUnobserved, release, fixture.bytes)
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED, error.code)
    }

    @Test
    fun `owned-unit policy accepts a completely unobserved network image`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest).copy(
            source = release(fixture.manifest).source.copy(
                networkEvidencePolicy =
                    OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
            ),
        )

        val plan = OmiCv1ApplicationUpdatePlanner.prepare(
            endpoint,
            networkUnobservedInspection(endpoint, release.source),
            release,
            fixture.bytes,
        )

        OmiCv1ApplicationUpdatePlanner.requireStagedState(
            networkUnobservedStagedInspection(endpoint, release),
            plan,
        )
        OmiCv1ApplicationUpdatePlanner.requireConfirmedState(
            networkUnobservedStagedInspection(endpoint, release, confirmed = true),
            plan,
        )
    }

    @Test
    fun `owned-unit policy still rejects a visible network mismatch`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest).copy(
            source = release(fixture.manifest).source.copy(
                networkEvidencePolicy =
                    OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
            ),
        )
        val visibleMismatch = inspection(
            endpoint,
            activeSlot(0, release.source.applicationHash),
            activeSlot(1, FirmwareImageHash("44".repeat(32))),
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1ApplicationUpdatePlanner.prepare(
                endpoint,
                visibleMismatch,
                release,
                fixture.bytes,
            )
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED, error.code)
    }

    @Test
    fun `accepts only the exact canonical or zero-build wire version`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest)
        val wrongVersion = stableInspection(endpoint, release.source).copy(
            slots = stableInspection(endpoint, release.source).slots.map { slot ->
                if (slot.active && slot.imageNumber == OmiCv1ApplicationUpdatePlanner.APPLICATION_IMAGE_NUMBER) {
                    slot.copy(version = "0.0.1")
                } else {
                    slot
                }
            },
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1ApplicationUpdatePlanner.prepare(endpoint, wrongVersion, release, fixture.bytes)
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED, error.code)
    }

    @Test
    fun `rejects a preflight with an already populated secondary slot`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest)
        val populated = stableInspection(endpoint, release.source).copy(
            slots = stableInspection(endpoint, release.source).slots.map { slot ->
                if (slot.imageNumber == 0 && !slot.active) {
                    slot.copy(
                        version = release.target.mcubootVersion,
                        hash = FirmwareImageHash("55".repeat(32)),
                        bootable = true,
                    )
                } else {
                    slot
                }
            },
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1ApplicationUpdatePlanner.prepare(endpoint, populated, release, fixture.bytes)
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED, error.code)
    }

    @Test
    fun `rejects a staged image selected for boot before explicit confirmation`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest)
        val plan = OmiCv1ApplicationUpdatePlanner.prepare(
            endpoint,
            stableInspection(endpoint, release.source),
            release,
            fixture.bytes,
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1ApplicationUpdatePlanner.requireStagedState(
                stagedInspection(endpoint, release, confirmed = true),
                plan,
            )
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED, error.code)
    }

    @Test
    fun `rejects network secondary-slot mutation after application upload`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest)
        val plan = OmiCv1ApplicationUpdatePlanner.prepare(
            endpoint,
            stableInspection(endpoint, release.source),
            release,
            fixture.bytes,
        )
        val unsafe = stagedInspection(endpoint, release).copy(
            slots = stagedInspection(endpoint, release).slots.map {
                if (it.imageNumber == 1 && !it.active) {
                    it.copy(
                        version = "0.0.0+0",
                        hash = FirmwareImageHash("33".repeat(32)),
                        bootable = true,
                    )
                } else {
                    it
                }
            },
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1ApplicationUpdatePlanner.requireStagedState(unsafe, plan)
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED, error.code)
    }

    @Test
    fun `post-reboot validation accepts exact target with network completely unobserved`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest).copy(
            source = release(fixture.manifest).source.copy(
                networkEvidencePolicy =
                    OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
            ),
        )
        val plan = OmiCv1ApplicationUpdatePlanner.prepare(
            endpoint,
            networkUnobservedInspection(endpoint, release.source),
            release,
            fixture.bytes,
        )
        val pending = OmiCv1ApplicationUpdatePendingValidation(
            planId = plan.planId,
            endpoint = endpoint,
            expectedApplicationHash = release.target.mcubootImageHash,
            expectedNetworkHash = release.source.networkHash,
            expectedMcubootVersion = release.target.mcubootVersion,
            networkEvidencePolicy = release.source.networkEvidencePolicy,
            resetResponseObserved = false,
        )
        val postReboot = inspection(
            endpoint,
            activeSlot(0, release.target.mcubootImageHash),
        )

        val validation = OmiCv1ApplicationUpdatePlanner.validatePostRebootState(postReboot, pending)

        assertEquals(release.target.mcubootImageHash, validation.applicationHash)
        assertEquals(false, validation.networkImageObserved)
    }

    @Test
    fun `post-reboot validation rejects the prior application still active`() {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest).copy(
            source = release(fixture.manifest).source.copy(
                networkEvidencePolicy =
                    OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
            ),
        )
        val plan = OmiCv1ApplicationUpdatePlanner.prepare(
            endpoint,
            networkUnobservedInspection(endpoint, release.source),
            release,
            fixture.bytes,
        )
        val pending = OmiCv1ApplicationUpdatePendingValidation(
            planId = plan.planId,
            endpoint = endpoint,
            expectedApplicationHash = release.target.mcubootImageHash,
            expectedNetworkHash = release.source.networkHash,
            expectedMcubootVersion = release.target.mcubootVersion,
            networkEvidencePolicy = release.source.networkEvidencePolicy,
            resetResponseObserved = true,
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            OmiCv1ApplicationUpdatePlanner.validatePostRebootState(
                networkUnobservedInspection(endpoint, release.source),
                pending,
            )
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED, error.code)
    }
}
