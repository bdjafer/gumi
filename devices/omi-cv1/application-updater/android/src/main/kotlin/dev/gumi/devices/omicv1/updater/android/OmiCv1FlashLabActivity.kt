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
                "Dedicated application-image-0 transport. No network image, generic file picker, " +
                    "multi-image package, erase, test, shell, settings, or automatic retry exists here.",
                style = MaterialTheme.typography.bodyMedium,
            )

            StatusCard(state)
            PowerAndPermissionCard(state) {
                permissionLauncher.launch(requiredFlashLabBlePermissions())
            }

            if (state.phase !in setOf(
                    OmiCv1FlashLabPhase.UPDATING,
                    OmiCv1FlashLabPhase.VALIDATING_POST_REBOOT,
                    OmiCv1FlashLabPhase.STOPPED_ON_FAILURE,
                )
            ) {
                SafetyChecklistCard(state.checklist, controller::updateChecklist)
            }

            state.error?.let { ErrorCard(it, state.phase == OmiCv1FlashLabPhase.STOPPED_ON_FAILURE) }

            when (state.phase) {
                OmiCv1FlashLabPhase.SAFETY_REVIEW -> ScanStartCard(state, controller::startScan)
                OmiCv1FlashLabPhase.SCANNING -> ScanningCard(
                    state,
                    controller::select,
                    controller::stopScan,
                )

                OmiCv1FlashLabPhase.DEVICE_SELECTED -> SelectedCard(
                    state,
                    "Run fresh disclosed preflight",
                    controller::runPreflight,
                )

                OmiCv1FlashLabPhase.READING_PREFLIGHT -> BusyCard(
                    "Reading current MCUboot image state. No persistent mutation is expected.",
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
                    "Proving the target application hash after reboot.",
                )

                OmiCv1FlashLabPhase.VALIDATED -> ValidationCard(
                    state,
                    controller::updateChecklist,
                    controller::beginNextTransition,
                )

                OmiCv1FlashLabPhase.STOPPED_ON_FAILURE -> Unit
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(state: OmiCv1FlashLabUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("State", style = MaterialTheme.typography.titleMedium)
            Text(state.phase.name)
            Text(
                "Stock boot policy is overwrite-style. A failed application may require SWD/J-Link; " +
                    "there is no promised automatic rollback.",
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
            Text("Required minimum: $MIN_PHONE_BATTERY_PERCENT%")
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
            CheckRow("Omi battery is at least 80%", checklist.omiBatteryAtLeast80) {
                update(checklist.copy(omiBatteryAtLeast80 = it))
            }
            CheckRow("Official Omi app is force-stopped", checklist.officialOmiAppStopped) {
                update(checklist.copy(officialOmiAppStopped = it))
            }
            CheckRow("Omi charger is connected or immediately available", checklist.chargerAvailable) {
                update(checklist.copy(chargerAvailable = it))
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
            Button(onClick = action, enabled = state.readyForPreflight || state.pendingValidation != null) {
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
            Text("Intent: ${review.intent.name}")
            Text("Release: ${review.releaseId}")
            HashLine("Source application hash", review.sourceApplicationHash)
            Text("Target: ${review.targetIdentity}")
            HashLine("Exact file SHA-256", review.targetFileSha256)
            HashLine("Target MCUboot image hash", review.targetImageHash)
            Text("Only target image number: ${review.targetImageNumber}")
            Text("Network evidence: ${review.networkPolicy}")
            Text(
                "The packaged APK contains two application images only. This operation sends only " +
                    "the target above through the explicit image-number-0 API.",
            )
            CheckRow(
                "I authorize this exact file SHA-256 once",
                state.checklist.exactArtifactAuthorized,
            ) {
                updateChecklist(state.checklist.copy(exactArtifactAuthorized = it))
            }
            Button(
                onClick = execute,
                enabled = state.checklist.exactArtifactAuthorized,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("AUTHORIZE ONE IMAGE-0 UPDATE")
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Firmware transaction active", style = MaterialTheme.typography.titleLarge)
            Text(progress?.stage?.name ?: "PREPARING")
            if (progress?.bytesSent != null && progress.totalBytes != null) {
                Text("${progress.bytesSent} / ${progress.totalBytes} bytes")
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
    updateChecklist: (OmiCv1FlashLabChecklist) -> Unit,
    next: () -> Unit,
) {
    val validation = state.validation ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Application transition proven", style = MaterialTheme.typography.titleLarge)
            HashLine("Active application hash", validation.applicationHash.hex)
            Text("Network image observed: ${validation.networkImageObserved}")
            if (state.completedIntent == OmiCv1ApplicationUpdateIntent.MINIMAL_CANARY) {
                Text(
                    "Before recovery, verify three magenta boot pulses, software revision " +
                        "gumi-canary-0001, GATT inventory, and the bounded audio witness in Gumi.",
                )
                CheckRow(
                    "I completed the canary indicator, GATT, and audio checks",
                    state.checklist.externalCanaryChecksComplete,
                ) {
                    updateChecklist(state.checklist.copy(externalCanaryChecksComplete = it))
                }
                Button(onClick = next, enabled = state.checklist.externalCanaryChecksComplete) {
                    Text("Begin separately reviewed stock recovery")
                }
            } else {
                Text("Exact stock application recovery is proven. Stop this flash session.")
            }
        }
    }
}

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
