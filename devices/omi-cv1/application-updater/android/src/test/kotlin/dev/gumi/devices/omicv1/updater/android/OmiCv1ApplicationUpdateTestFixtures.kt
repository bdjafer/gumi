package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection

internal fun endpoint(value: String = "ephemeral-omi") = EndpointCandidate(TransportKind.BLE, value)

internal fun release(
    manifest: OmiCv1ApplicationArtifactManifest,
    sourceApplicationHash: FirmwareImageHash = FirmwareImageHash("11".repeat(32)),
    sourceNetworkHash: FirmwareImageHash = FirmwareImageHash("22".repeat(32)),
    sourceManufacturer: String = OmiCv1V3012ApplicationUpdateCatalog.STOCK_MANUFACTURER,
) = OmiCv1ApplicationUpdateRelease(
    releaseId = "test-release",
    intent = OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY,
    source = OmiCv1ExpectedActiveImages(
        applicationHash = sourceApplicationHash,
        networkHash = sourceNetworkHash,
        mcubootVersion = "0.0.0+0",
    ),
    sourceManufacturer = sourceManufacturer,
    target = manifest,
)

internal fun stableInspection(
    endpoint: EndpointCandidate,
    expected: OmiCv1ExpectedActiveImages,
) = inspection(
    endpoint,
    activeSlot(0, expected.applicationHash),
    emptySlot(0),
    activeSlot(1, expected.networkHash),
    emptySlot(1),
)

internal fun networkUnobservedInspection(
    endpoint: EndpointCandidate,
    expected: OmiCv1ExpectedActiveImages,
) = inspection(
    endpoint,
    activeSlot(0, expected.applicationHash),
)

internal fun stagedInspection(
    endpoint: EndpointCandidate,
    release: OmiCv1ApplicationUpdateRelease,
    confirmed: Boolean = false,
) = inspection(
    endpoint,
    activeSlot(0, release.source.applicationHash),
    FirmwareImageSlot(
        imageNumber = 0,
        slotNumber = 1,
        version = OmiCv1ApplicationUpdatePlanner.MCUMGR_COMPATIBILITY_WIRE_VERSION,
        hash = release.target.mcubootImageHash,
        bootable = true,
        pending = confirmed,
        confirmed = confirmed,
        active = false,
        permanent = confirmed,
        compressed = false,
    ),
    activeSlot(1, release.source.networkHash),
    emptySlot(1),
)

internal fun networkUnobservedStagedInspection(
    endpoint: EndpointCandidate,
    release: OmiCv1ApplicationUpdateRelease,
    confirmed: Boolean = false,
) = stagedInspection(endpoint, release, confirmed).copy(
    slots = stagedInspection(endpoint, release, confirmed).slots.filter { it.imageNumber == 0 },
)

internal fun inspection(
    endpoint: EndpointCandidate,
    vararg slots: FirmwareImageSlot,
) = FirmwareImageStateInspection(
    endpoint = endpoint,
    protocol = "mcumgr-smp",
    slots = slots.toList(),
    splitStatus = 0,
)

internal fun activeSlot(imageNumber: Int, hash: FirmwareImageHash) = FirmwareImageSlot(
    imageNumber = imageNumber,
    slotNumber = 0,
    version = OmiCv1ApplicationUpdatePlanner.MCUMGR_COMPATIBILITY_WIRE_VERSION,
    hash = hash,
    bootable = true,
    pending = false,
    confirmed = true,
    active = true,
    permanent = true,
    compressed = false,
)

internal fun emptySlot(imageNumber: Int) = FirmwareImageSlot(
    imageNumber = imageNumber,
    slotNumber = 1,
    version = null,
    hash = null,
    bootable = false,
    pending = false,
    confirmed = false,
    active = false,
    permanent = false,
    compressed = false,
)
