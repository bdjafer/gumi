package dev.gumi.devices.omicv1.updater.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection

class OmiCv1FlashLabActivity : ComponentActivity() {
    private val controller: OmiCv1FlashLabController
        get() = (application as OmiCv1FlashLabApplication).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                OmiCv1FlashLabScreen(controller)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.refreshEnvironment(hasFlashLabBlePermissions())
    }
}

@Composable
private fun OmiCv1FlashLabScreen(controller: OmiCv1FlashLabController) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        controller.refreshEnvironment(context.hasFlashLabBlePermissions())
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Gumi · Omi CV1 Flash Lab", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Closed Omi CV1 firmware lab: exact application-image-0 transitions plus one " +
                    "official v3.0.7 → v3.0.12 dual-image normalization. No file picker, shell, " +
                    "network access, arbitrary artifact, test-boot, or generic retry exists here.",
                style = MaterialTheme.typography.bodyMedium,
            )

            StatusCard(state)
            PowerAndPermissionCard(state) {
                permissionLauncher.launch(requiredFlashLabBlePermissions())
            }

            if (state.phase !in setOf(
                OmiCv1FlashLabPhase.UPDATING,
                OmiCv1FlashLabPhase.VALIDATING_POST_REBOOT,
                OmiCv1FlashLabPhase.READING_ACTIVE_CAPTURE_SELFTEST,
                OmiCv1FlashLabPhase.READING_ACTIVE_FUNCTIONAL,
                OmiCv1FlashLabPhase.RECHECKING_RECOVERY,
                    OmiCv1FlashLabPhase.RECHECKING_FUNCTIONAL,
                    OmiCv1FlashLabPhase.RUNNING_CAPTURE_SELFTEST,
                    OmiCv1FlashLabPhase.AWAITING_CAPTURE_CONFIRMATION,
                    OmiCv1FlashLabPhase.VALIDATED,
                    OmiCv1FlashLabPhase.STOPPED_ON_FAILURE,
                )
            ) {
                SafetyChecklistCard(state.checklist, controller::updateChecklist)
            }

            state.error?.let { ErrorCard(it, state.phase == OmiCv1FlashLabPhase.STOPPED_ON_FAILURE) }
            state.devicePreflight?.let { evidence ->
                DevicePreflightEvidenceCard(evidence)
            }
            state.preflightInspection?.let { inspection ->
                ImageStateEvidenceCard(inspection)
            }

            when (state.phase) {
                OmiCv1FlashLabPhase.SAFETY_REVIEW -> ScanStartCard(state, controller::startScan)
                OmiCv1FlashLabPhase.SCANNING -> ScanningCard(
                    state,
                    controller::select,
                    controller::stopScan,
                )

                OmiCv1FlashLabPhase.DEVICE_SELECTED -> TransitionPreflightCard(
                    state,
                    controller::runPreflight,
                    controller::runStockNormalizationPreflight,
                    controller::runInactiveApplicationSlotErasePreflight,
                    controller::resumeActiveCaptureSelftest,
                    controller::resumeActiveFunctional,
                )

                OmiCv1FlashLabPhase.READING_PREFLIGHT -> BusyCard(
                    "Reading current MCUboot image state and allowlisted identity/battery " +
                        "characteristics. No persistent mutation is expected.",
                )

                OmiCv1FlashLabPhase.READY_TO_AUTHORIZE -> AuthorizationCard(
                    state,
                    controller::updateChecklist,
                    controller::authorizeAndExecute,
                )

                OmiCv1FlashLabPhase.UPDATING -> UpdateProgressCard(state)
                OmiCv1FlashLabPhase.AWAITING_POST_REBOOT_SCAN -> AwaitingRebootCard(
                    state,
                    controller::startScan,
                )

                OmiCv1FlashLabPhase.POST_REBOOT_DEVICE_SELECTED -> SelectedCard(
                    state,
                    "Validate fresh post-reboot image state",
                    controller::validatePostReboot,
                )

                OmiCv1FlashLabPhase.VALIDATING_POST_REBOOT -> BusyCard(
                    "Proving the exact target image and its mode-specific fail-closed GATT status.",
                )

                OmiCv1FlashLabPhase.READING_ACTIVE_CAPTURE_SELFTEST -> BusyCard(
                    "Read-only proof of the exact active self-test image, media-free topology, " +
                        "and safely re-armable microphone-off status.",
                )

                OmiCv1FlashLabPhase.READING_ACTIVE_FUNCTIONAL -> BusyCard(
                    "Read-only proof of the exact active functional image, recording-ready " +
                        "status, capabilities, and media topology.",
                )

                OmiCv1FlashLabPhase.RECHECKING_RECOVERY -> BusyCard(
                    "Repeating the read-only image-state, recovery status, and topology proof.",
                )

                OmiCv1FlashLabPhase.RECHECKING_FUNCTIONAL -> BusyCard(
                    "Repeating the read-only image-state, functional status, capabilities, and topology proof.",
                )

                OmiCv1FlashLabPhase.RUNNING_CAPTURE_SELFTEST -> BusyCard(
                    "Connecting and arming one 15-second capture-port lease. Do not press yet.",
                )

                OmiCv1FlashLabPhase.AWAITING_CAPTURE_CONFIRMATION -> BusyCard(
                    "ARMED — continuously hold the Omi button for two seconds. Keep only " +
                        "non-sensitive test sound nearby; red must appear before the three-second exercise.",
                )

                OmiCv1FlashLabPhase.VALIDATED -> ValidationCard(
                    state,
                    controller::beginNextTransition,
                    controller::recheckRecoveryStatus,
                    controller::recheckFunctionalStatus,
                    controller::runCaptureSelftest,
                )

                OmiCv1FlashLabPhase.STOPPED_ON_FAILURE -> Unit
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TransitionPreflightCard(
    state: OmiCv1FlashLabUiState,
    run: (OmiCv1ApplicationUpdateIntent) -> Unit,
    normalizeStock: () -> Unit,
    eraseInactiveApplicationSlot: () -> Unit,
    resumeCaptureSelftest: () -> Unit,
    resumeFunctional: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Selected exact Omi", style = MaterialTheme.typography.titleMedium)
            Text("${state.selected?.advertisedName ?: "Omi CV1"} · RSSI ${state.selected?.rssi ?: 0} dBm")
            Text(
                "Choose the intended target. A fresh image-state read rejects any transition that " +
                    "does not match the exact active application.",
            )
            Button(
                onClick = normalizeStock,
                enabled = state.readyForPreflight,
            ) {
                Text("Official stock v3.0.7 → v3.0.12 (app + network)")
            }
            Button(
                onClick = { run(OmiCv1ApplicationUpdateIntent.RECORDING_ROOT_PROVISIONER) },
                enabled = state.readyForPreflight,
            ) {
                Text("Recovery-only → provision recording root")
            }
            Button(
                onClick = { run(OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER) },
                enabled = state.readyForPreflight,
            ) {
                Text("Provisioner / functional v0006 → OTA-safe reclaimer v0002")
            }
            Button(
                onClick = { run(OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING) },
                enabled = state.readyForPreflight,
            ) {
                Text("Successful reclaimer v0002 → functional recording v0007")
            }
            OutlinedButton(
                onClick = eraseInactiveApplicationSlot,
                enabled = state.readyForPreflight,
            ) {
                Text("Successful reclaimer v0002 → erase inactive application slot 1")
            }
            OutlinedButton(
                onClick = { run(OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST) },
                enabled = state.readyForPreflight,
            ) {
                Text("Recovery-only → capture-port self-test")
            }
            OutlinedButton(
                onClick = { run(OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY) },
                enabled = state.readyForPreflight,
            ) {
                Text("Stock/self-test/provisioner/reclaimer v0002/functional → recovery-only")
            }
            OutlinedButton(
                onClick = resumeCaptureSelftest,
                enabled = state.readyForReadOnlyCapture,
            ) {
                Text("Read-only: resume active capture self-test")
            }
            OutlinedButton(
                onClick = resumeFunctional,
                enabled = state.readyForReadOnlyCapture,
            ) {
                Text("Read-only: resume active functional v0006 / v0007")
            }
            OutlinedButton(
                onClick = { run(OmiCv1ApplicationUpdateIntent.STOCK_RECOVERY) },
                enabled = state.readyForPreflight,
            ) {
                Text("Recovery-only → exact stock")
            }
        }
    }
}

@Composable
private fun DevicePreflightEvidenceCard(evidence: OmiCv1FlashLabDevicePreflightEvidence) {
    val batteryPercent = evidence.identity.batteryPercent
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Fresh read-only device evidence", style = MaterialTheme.typography.titleMedium)
            Text("Manufacturer: ${evidence.identity.manufacturer ?: "unavailable"}")
            Text("Model: ${evidence.identity.modelNumber ?: "unavailable"}")
            Text("Firmware: ${evidence.identity.firmwareRevision ?: "unavailable"}")
            Text("Hardware: ${evidence.identity.hardwareRevision ?: "unavailable"}")
            Text(
                "Device battery: " +
                    (batteryPercent?.let { "$it%" } ?: "unavailable"),
                color = if (
                    batteryPercent != null &&
                    batteryPercent >=
                    OmiCv1FlashLabDevicePreflightPolicy.LOW_OMI_BATTERY_WARNING_PERCENT
                ) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                "GATT topology: ${evidence.serviceCount} services · " +
                    "${evidence.characteristicCount} characteristics",
            )
            if (
                batteryPercent == null ||
                batteryPercent <
                OmiCv1FlashLabDevicePreflightPolicy.LOW_OMI_BATTERY_WARNING_PERCENT
            ) {
                Text(
                    "Low/unavailable Omi battery warning — flashing remains enabled.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ImageStateEvidenceCard(inspection: FirmwareImageStateInspection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fresh MCUboot image-state evidence", style = MaterialTheme.typography.titleMedium)
            Text("Protocol: ${inspection.protocol}")
            Text("Endpoint: ${inspection.endpoint.transport.name} · process-local selection")
            Text("Split status: ${inspection.splitStatus?.toString() ?: "not reported"}")
            inspection.slots
                .sortedWith(compareBy(FirmwareImageSlot::imageNumber, FirmwareImageSlot::slotNumber))
                .forEach { slot ->
                    Text(
                        buildString {
                            append("Image ${slot.imageNumber} · slot ${slot.slotNumber}\n")
                            append("Version: ${slot.version ?: "not reported"}\n")
                            append("Hash: ${slot.hash?.hex ?: "not reported"}\n")
                            append("Flags: ${slot.displayFlags()}")
                        },
                        fontFamily = FontFamily.Monospace,
                    )
                }
        }
    }
}

private fun FirmwareImageSlot.displayFlags(): String {
    val enabled = buildList {
        if (bootable) add("bootable")
        if (pending) add("pending")
        if (confirmed) add("confirmed")
        if (active) add("active")
        if (permanent) add("permanent")
        if (compressed) add("compressed")
    }
    return enabled.joinToString().ifEmpty { "none" }
}

@Composable
private fun StatusCard(state: OmiCv1FlashLabUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("State", style = MaterialTheme.typography.titleMedium)
            Text(state.phase.name)
            Text("Portable maintenance stage: ${state.portableMaintenanceStage.name}")
            Text(
                "Stock boot policy is overwrite-style. A failed application may require SWD/J-Link; " +
                    "there is no promised automatic rollback.",
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "The stock-compatible signing key is public upstream. Recovery-only is a logical " +
                    "safe application, not an immutable recovery root.",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PowerAndPermissionCard(state: OmiCv1FlashLabUiState, requestPermissions: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Host guard", style = MaterialTheme.typography.titleMedium)
            Text(
                "Phone battery: ${state.phonePower.percent?.let { "$it%" } ?: "unavailable"}" +
                    if (state.phonePower.charging) " · charging" else "",
            )
            Text("Firmware-update minimum: $MIN_PHONE_BATTERY_PERCENT%")
            Text("Read-only/self-test minimum: $MIN_PHONE_READ_ONLY_CAPTURE_PERCENT%")
            Text("Nearby Devices: ${if (state.permissionsGranted) "granted" else "not granted"}")
            if (!state.permissionsGranted) {
                Button(onClick = requestPermissions) { Text("Grant Nearby Devices") }
            }
        }
    }
}

@Composable
private fun SafetyChecklistCard(
    checklist: OmiCv1FlashLabChecklist,
    update: (OmiCv1FlashLabChecklist) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Physical preflight", style = MaterialTheme.typography.titleMedium)
            Text(
                "Flash Lab reads and displays the Omi battery automatically. A low or unavailable " +
                    "reading is a warning only and never blocks flashing.",
                style = MaterialTheme.typography.bodySmall,
            )
            CheckRow("Official Omi app is force-stopped", checklist.officialOmiAppStopped) {
                update(checklist.copy(officialOmiAppStopped = it))
            }
            CheckRow("Omi charger is connected now", checklist.chargerConnected) {
                update(checklist.copy(chargerConnected = it))
            }
            CheckRow("I accept the no-rollback / possible-SWD risk", checklist.noRollbackRiskAccepted) {
                update(checklist.copy(noRollbackRiskAccepted = it))
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun ScanStartCard(state: OmiCv1FlashLabUiState, start: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Discovery", style = MaterialTheme.typography.titleMedium)
            Text("Only connectable advertisements with an exact Omi service match are shown.")
            Button(onClick = start, enabled = state.permissionsGranted) { Text("Scan for Omi CV1") }
        }
    }
}

@Composable
private fun ScanningCard(
    state: OmiCv1FlashLabUiState,
    select: (OmiCv1FlashLabCandidate) -> Unit,
    stop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Scanning", style = MaterialTheme.typography.titleMedium)
            if (state.candidates.isEmpty()) Text("No exact Omi advertisement yet.")
            state.candidates.forEach { candidate ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(candidate.advertisedName, style = MaterialTheme.typography.titleSmall)
                        Text("RSSI ${candidate.rssi} dBm · connectable")
                        Button(onClick = { select(candidate) }) { Text("Select this Omi") }
                    }
                }
            }
            OutlinedButton(onClick = stop) { Text("Stop scan") }
        }
    }
}

@Composable
private fun SelectedCard(state: OmiCv1FlashLabUiState, label: String, action: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Selected exact Omi", style = MaterialTheme.typography.titleMedium)
            Text("${state.selected?.advertisedName ?: "Omi CV1"} · RSSI ${state.selected?.rssi ?: 0} dBm")
            if (
                state.pendingEndpoint != null &&
                state.selected?.endpoint != state.pendingEndpoint
            ) {
                Text(
                    "The BLE endpoint changed after reboot. This candidate remains untrusted until " +
                        "its exact expected MCUboot target and required recovery evidence pass.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = action, enabled = state.readyForPreflight || state.pendingEndpoint != null) {
                Text(label)
            }
        }
    }
}

@Composable
private fun BusyCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun AuthorizationCard(
    state: OmiCv1FlashLabUiState,
    updateChecklist: (OmiCv1FlashLabChecklist) -> Unit,
    execute: () -> Unit,
) {
    val review = state.review ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("One-shot owner authorization", style = MaterialTheme.typography.titleLarge)
            Text("Operation: ${review.operation.name}")
            review.intent?.let { Text("Intent: ${it.name}") }
            Text("Release: ${review.releaseId}")
            review.uploadMode?.let { Text("Transport: ${it.name}") }
            if (review.uploadMode == OmiCv1ApplicationUploadMode.INCOMPLETE_FLASH_BLOCK_RESCUE) {
                Text(
                    "Recovery rescue: stage the exact signed image as a deliberately incomplete " +
                        "transfer, prove its MCUboot slot hash, then confirm/reset. No generic retry.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (
                review.operation ==
                OmiCv1FlashLabOperation.OFFICIAL_STOCK_DUAL_IMAGE_NORMALIZATION
            ) {
                Text(
                    "DUAL-IMAGE NORMALIZATION: this uses Nordic's reviewed multi-image MCU Manager " +
                        "flow to upload and confirm Based Hardware's exact official v3.0.12 " +
                        "application and network-core images, then requests one reset. Application " +
                        "settings are not erased. nRF5340 overwrite boot has no promised rollback.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (
                review.operation ==
                OmiCv1FlashLabOperation.INACTIVE_APPLICATION_SLOT_ERASE
            ) {
                Text(
                    "BOUNDED STAGING-SLOT REPAIR: erase only inactive application image 0 slot 1. " +
                        "The confirmed active reclaimer in primary slot 0 must remain unchanged. " +
                        "This operation uploads no file, confirms no image, and requests no reset.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (review.intent == OmiCv1ApplicationUpdateIntent.RECORDING_ROOT_PROVISIONER) {
                Text(
                    "IRREVERSIBLE DEVICE PROVISIONING: after this image boots, it writes one random " +
                        "32-byte recording root into the nRF MEXT hardware key slot if that slot is " +
                        "empty. The value cannot be read back, exported, or undone. Validation proves " +
                        "only presence and a domain-separated derivation.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (review.intent == OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER) {
                Text(
                    "PERMANENT EXACT-FILE DELETION: after this image boots, it may unlink only " +
                        "/SD:/audio/a01.txt and only if it is a regular file of exactly " +
                        "505,118,720 bytes. It cannot format, recurse, accept another path, or read " +
                        "audio content. A wrong type or size is refused without deletion.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HashLine("Source application hash", review.sourceApplicationHash)
            Text("Target: ${review.targetIdentity}")
            review.targetFileSha256?.let { HashLine("Exact file SHA-256", it) }
            review.targetImageHash?.let { HashLine("Target MCUboot image hash", it) }
            Text("Application target image number: ${review.targetImageNumber}")
            review.targetSlotNumber?.let { Text("Target slot number: $it") }
            review.targetNetworkIdentity?.let { Text("Network target: $it") }
            review.targetNetworkFileSha256?.let {
                HashLine("Network exact file SHA-256", it)
            }
            review.targetNetworkImageHash?.let {
                HashLine("Network target MCUboot image hash", it)
            }
            Text("Network evidence: ${review.networkPolicy}")
            when (review.operation) {
                OmiCv1FlashLabOperation.APPLICATION_IMAGE_0 -> Text(
                    "This operation sends only the exact application target above through the " +
                        "explicit image-number-0 API.",
                )

                OmiCv1FlashLabOperation.INACTIVE_APPLICATION_SLOT_ERASE -> Text(
                    "This operation addresses only application slot 1. Fresh post-erase image " +
                        "state must prove the active source hash unchanged and slot 1 absent.",
                )

                OmiCv1FlashLabOperation.OFFICIAL_STOCK_DUAL_IMAGE_NORMALIZATION -> Text(
                    "This operation is the only route that can send image 1. Both files were " +
                        "extracted from the pinned official Omi v3.0.12 OTA archive and are " +
                        "re-inspected in memory before authorization.",
                )
            }
            CheckRow(
                if (review.intent == OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER) {
                    "I authorize this exact firmware once and permanent deletion of the one exact file"
                } else if (
                    review.operation ==
                    OmiCv1FlashLabOperation.INACTIVE_APPLICATION_SLOT_ERASE
                ) {
                    "I authorize one erase of inactive application image 0 slot 1"
                } else if (review.operation == OmiCv1FlashLabOperation.APPLICATION_IMAGE_0) {
                    "I authorize this exact file SHA-256 once"
                } else {
                    "I authorize both exact official file SHA-256 values once"
                },
                state.checklist.exactArtifactAuthorized,
            ) {
                updateChecklist(state.checklist.copy(exactArtifactAuthorized = it))
            }
            Button(
                onClick = execute,
                enabled = state.checklist.exactArtifactAuthorized,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(
                    when (review.operation) {
                        OmiCv1FlashLabOperation.APPLICATION_IMAGE_0 ->
                            "AUTHORIZE ONE IMAGE-0 UPDATE"

                        OmiCv1FlashLabOperation.INACTIVE_APPLICATION_SLOT_ERASE ->
                            "AUTHORIZE ONE INACTIVE-SLOT ERASE"

                        OmiCv1FlashLabOperation.OFFICIAL_STOCK_DUAL_IMAGE_NORMALIZATION ->
                            "AUTHORIZE OFFICIAL DUAL-IMAGE NORMALIZATION"
                    },
                )
            }
        }
    }
}

@Composable
private fun HashLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun UpdateProgressCard(state: OmiCv1FlashLabUiState) {
    val progress = state.progress
    val normalization = state.normalizationProgress
    val inactiveSlotEraseStage = state.inactiveSlotEraseStage
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Firmware transaction active", style = MaterialTheme.typography.titleLarge)
            Text(
                inactiveSlotEraseStage?.name ?:
                    normalization?.stage?.name ?:
                    progress?.stage?.name ?:
                    "PREPARING",
            )
            if (progress?.bytesSent != null && progress.totalBytes != null) {
                Text("${progress.bytesSent} / ${progress.totalBytes} bytes")
            }
            if (normalization?.bytesSent != null && normalization.totalBytes != null) {
                Text("${normalization.bytesSent} / ${normalization.totalBytes} bytes")
            }
            Text("Keep this screen visible. Do not move, unplug, close, or switch apps.")
        }
    }
}

@Composable
private fun AwaitingRebootCard(state: OmiCv1FlashLabUiState, scan: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Outcome not yet proven", style = MaterialTheme.typography.titleLarge)
            Text("Reset response observed: ${state.resetResponseObserved ?: false}")
            Text("A fresh post-reboot image-state read is mandatory before success.")
            Button(onClick = scan) { Text("Scan for the rebooted Omi") }
        }
    }
}

@Composable
private fun ValidationCard(
    state: OmiCv1FlashLabUiState,
    next: () -> Unit,
    recheck: () -> Unit,
    recheckFunctional: () -> Unit,
    runCaptureSelftest: () -> Unit,
) {
    val normalization = state.normalizationValidation
    if (normalization != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Official stock normalization proven", style = MaterialTheme.typography.titleLarge)
                HashLine("Active v3.0.12 application hash", normalization.applicationHash.hex)
                Text("Device Information firmware: ${normalization.firmwareRevision}")
                Text("Network image visible after reboot: ${normalization.networkImageObserved}")
                Text(
                    "The exact official v3.0.12 application is active and its read-only Device " +
                        "Information revision agrees. The network image was uploaded and confirmed " +
                        "by the multi-image transaction; this firmware may omit active image 1 from " +
                        "subsequent MCU Manager reads.",
                )
                OutlinedButton(onClick = next) {
                    Text("Open the separately reviewed stock → recovery-only transition")
                }
            }
        }
        return
    }
    val inactiveSlotErase = state.inactiveSlotEraseValidation
    if (inactiveSlotErase != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Inactive application slot erased", style = MaterialTheme.typography.titleLarge)
                HashLine("Unchanged active application hash", inactiveSlotErase.activeApplicationHash.hex)
                Text("Inactive application slot 1 absent: ${inactiveSlotErase.inactiveApplicationSlotAbsent}")
                Text(
                    "The staging slot is empty and the confirmed active reclaimer is unchanged. " +
                        "A functional v0007 update still requires a fresh preflight and separate " +
                        "exact-file authorization.",
                )
                OutlinedButton(onClick = next) {
                    Text("Open separately authorized functional v0007 transition")
                }
            }
        }
        return
    }
    val validation = state.validation ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Application transition proven", style = MaterialTheme.typography.titleLarge)
            HashLine("Active application hash", validation.applicationHash.hex)
            Text("Network image observed: ${validation.networkImageObserved}")
            if (state.completedIntent == OmiCv1ApplicationUpdateIntent.RECOVERY_ONLY) {
                val recovery = validation.recoveryStatus ?: return@Column
                Text("Recovery-only application and fail-closed runtime evidence are proven.")
                Text("Recovery status: ${recovery.rawHex}", fontFamily = FontFamily.Monospace)
                Text("Recovery transport ready: ${recovery.recoveryTransportReady}")
                Text("Microphone verified off: ${recovery.microphoneVerifiedOff}")
                Text("Capture permitted: ${recovery.capturePermitted}")
                Text("Stock-family identity service empty: ${recovery.stockIdentityServiceEmpty}")
                Text("Functional Omi services absent: ${recovery.functionalOmiServicesAbsent}")
                Text(
                    "You may leave recovery-only installed. Its BLE update path is intentionally " +
                        "unpaired at this development stage; keep the device in controlled proximity.",
                )
                Text(
                    "Unplug Omi, observe it for at least 10 minutes, then use the read-only recheck. " +
                        "The app rejects an early recheck.",
                )
                Button(onClick = recheck) {
                    Text("Recheck image + recovery evidence")
                }
                OutlinedButton(onClick = next) {
                    Text("Open a separately reviewed next transition")
                }
            } else if (state.completedIntent == OmiCv1ApplicationUpdateIntent.CAPTURE_PORT_SELFTEST) {
                val selftest = validation.captureSelftestStatus ?: return@Column
                Text("Capture-port diagnostic image and media-free GATT topology are proven.")
                Text("Attempt: ${selftest.attempt} · phase: ${selftest.phase.name}")
                Text("Failure: ${selftest.failure.name}")
                Text("PCM: ${selftest.pcmSamples} samples in ${selftest.pcmBlocks} blocks")
                Text("Opus packets counted/discarded: ${selftest.opusPackets}")
                Text("Discarded PCM samples: ${selftest.discardedSamples}")
                Text("Terminal codec error: ${selftest.terminalError}")
                Text("Microphone verified off: ${selftest.microphoneVerifiedOff}")
                Text("Privacy red currently asserted: ${selftest.privacyAsserted}")
                Text(
                    "Consecutive safe passes: ${state.captureSelftestConsecutivePasses} / " +
                        "$REQUIRED_CAPTURE_SELFTEST_PASSES",
                    color = if (
                        state.captureSelftestConsecutivePasses >= REQUIRED_CAPTURE_SELFTEST_PASSES
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.captureSelftestConsecutivePasses >= REQUIRED_CAPTURE_SELFTEST_PASSES) {
                    Text(
                        "Hardware lifecycle qualification passed. Restore recovery-only through a " +
                            "new preflight and separate exact-file authorization.",
                    )
                } else {
                    Button(onClick = runCaptureSelftest) {
                        Text("Arm one bounded self-test attempt")
                    }
                }
                OutlinedButton(onClick = next) {
                    Text("Prepare separately authorized recovery-only return")
                }
            } else if (
                state.completedIntent ==
                OmiCv1ApplicationUpdateIntent.RECORDING_ROOT_PROVISIONER
            ) {
                val provisioner =
                    validation.recordingRootProvisionerStatus ?: return@Column
                Text("Recording-root provisioning and its status-only GATT surface are proven.")
                Text("Phase: ${provisioner.phase.name}")
                Text("Recovery transport ready: ${provisioner.transportReady}")
                Text("Microphone verified off: ${provisioner.microphoneVerifiedOff}")
                Text("MEXT root present: ${provisioner.mextPresent}")
                Text("Domain-separated derivation verified: ${provisioner.derivationVerified}")
                Text("Write attempted this boot: ${provisioner.writeAttempted}")
                Text("Generation: ${provisioner.generation}")
                Text("Last errno: ${provisioner.lastError}")
                Text("Status: ${provisioner.rawHex}", fontFamily = FontFamily.Monospace)
                Text(
                    "No root, derivative, or digest was exposed. The bounded legacy-storage " +
                        "reclaimer is now eligible through a fresh preflight and a separate " +
                        "exact-file authorization.",
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(onClick = next) {
                    Text("Open separately authorized exact-file reclaimer transition")
                }
            } else if (
                state.completedIntent ==
                OmiCv1ApplicationUpdateIntent.LEGACY_STORAGE_RECLAIMER
            ) {
                val reclaimer =
                    validation.legacyStorageReclaimerStatus ?: return@Column
                Text("Exact reclaimer image and status-only GATT surface are proven.")
                Text("Phase: ${reclaimer.phase.name}")
                Text("Microphone verified off: ${reclaimer.microphoneVerifiedOff}")
                Text("Exact target observed: ${reclaimer.targetExact}")
                Text("Delete attempted: ${reclaimer.deleteAttempted}")
                Text("Target absent: ${reclaimer.targetAbsent}")
                Text("Target size: ${reclaimer.targetSizeBytes} bytes")
                Text("Free before: ${reclaimer.freeBytesBefore} bytes")
                Text("Free after: ${reclaimer.freeBytesAfter} bytes")
                Text("Generation: ${reclaimer.generation}")
                Text("Last errno: ${reclaimer.lastError}")
                Text("Status: ${reclaimer.rawHex}", fontFamily = FontFamily.Monospace)
                Text(
                    if (reclaimer.reclaimSucceeded) {
                        "SUCCESS — reclaimer v0002 kept OTA flash writable, the exact legacy file " +
                            "is absent, and at least 4 MiB is free. " +
                            "Functional v0007 is eligible through a fresh, separately authorized " +
                            "transition."
                    } else {
                        "NOT RECLAIMED — functional firmware remains ineligible. If follow-up " +
                            "mutation is admitted, use only the separately authorized recovery-only " +
                            "return."
                    },
                    color = if (reclaimer.reclaimSucceeded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Button(
                    onClick = next,
                    enabled = reclaimer.reclaimSucceeded || reclaimer.recoveryEligible,
                ) {
                    Text(
                        if (reclaimer.reclaimSucceeded) {
                            "Open separately authorized functional v0007 transition"
                        } else {
                            "Open separately authorized recovery-only return"
                        },
                    )
                }
            } else if (state.completedIntent == OmiCv1ApplicationUpdateIntent.FUNCTIONAL_RECORDING) {
                val functional = validation.functionalStatus ?: return@Column
                Text("Functional image and exact Gumi v1 GATT contract are proven.")
                val functionalSummary = when {
                    functional.capturingLocally ->
                        "RECORDING — microphone, privacy output, Opus, and durable store are active."

                    functional.recordingReady ->
                        "READY — local encrypted recording is operational and idle."

                    functional.faulted || !functional.operational ->
                        "FAIL-CLOSED — firmware is active, but recording is not ready. " +
                            "Use the separately authorized recovery-only return."

                    else ->
                        "TRANSITIONING — wait for a stable idle or recording state, then refresh."
                }
                Text(
                    functionalSummary,
                    color = if (
                        functional.recordingReady || functional.capturingLocally
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else if (functional.faulted || !functional.operational) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("Phase: ${functional.phase.name}")
                Text("Microphone: ${functional.microphone.name}")
                Text("Recording key: ${functional.key.name}")
                Text("Recording storage: ${functional.recordingStorage.name}")
                Text("Codec: ${functional.codec.name}")
                Text("Free bytes: ${functional.freeBytes}")
                Text("Last errno: ${functional.lastError}")
                Text("Status flags: 0x${functional.flags.toString(16).padStart(2, '0')}")
                Text("Status: ${functional.rawHex}", fontFamily = FontFamily.Monospace)
                if (functional.recordingReady || functional.capturingLocally) {
                    Text(
                        if (functional.capturingLocally) {
                            "Speak non-sensitive test audio, then double-tap to stop. Confirm red " +
                                "turns off before refreshing again."
                        } else {
                            "Qualification step: unplug from the charger, double-tap once to start, " +
                                "confirm continuous red, then refresh this status."
                        },
                    )
                }
                Button(onClick = recheckFunctional) {
                    Text("Refresh image + functional status")
                }
                Text(
                    "Recovery return: while capture is idle, hold the Omi button continuously for " +
                        "five seconds. After the updating indication appears, open a separately " +
                        "reviewed recovery-only transition; its preflight requires update-admitted " +
                        "and microphone-off evidence.",
                )
                OutlinedButton(onClick = next) {
                    Text("Prepare separately authorized recovery-only return")
                }
            } else {
                Text("Exact stock application recovery is proven. Stop this flash session.")
            }
        }
    }
}

private const val REQUIRED_CAPTURE_SELFTEST_PASSES = 3

@Composable
private fun ErrorCard(detail: String, terminal: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (terminal) "STOP — do not retry" else "Action required",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(detail)
            if (terminal) {
                Text("Preserve this state and review fresh image-state evidence before any next action.")
            }
        }
    }
}
