package dev.gumi.edge.shell.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.gumi.devices.omicv1.OmiCv1DriverProvider
import dev.gumi.devices.omicv1.OmiCv1FirmwareOracleStatus
import dev.gumi.devices.omicv1.OmiCv1KnownV3012FirmwareOracle
import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.devices.omicv1.OmiCv1GattEvidence
import dev.gumi.devices.omicv1.OmiCv1Protocol
import dev.gumi.devices.omicv1.OmiCv1StorageEvidence
import dev.gumi.edge.platforms.android.ble.AndroidBleEndpointDirectory
import dev.gumi.edge.platforms.android.ble.AndroidBleCentral
import dev.gumi.edge.platforms.android.ble.AndroidBleGattInspector
import dev.gumi.edge.platforms.android.ble.AndroidBleScanner
import dev.gumi.edge.platforms.android.ble.AndroidMcuMgrImageStateInspector
import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.runtime.host.RuntimeHostExecutionState
import dev.gumi.edge.runtime.host.RuntimeHostProjection
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.firmware.FirmwareImageStateReadDisclosure
import dev.gumi.edge.shell.android.diagnostics.AndroidAudioMetadataProbeController
import dev.gumi.edge.shell.android.diagnostics.AndroidBleAddressStabilityProbeController
import dev.gumi.edge.shell.android.diagnostics.AndroidBleProbeController
import dev.gumi.edge.shell.android.diagnostics.AndroidDriverNegotiationProbeController
import dev.gumi.edge.shell.android.diagnostics.AndroidFirmwareImageProbeController
import dev.gumi.edge.shell.android.diagnostics.AndroidGattProbeController
import dev.gumi.edge.shell.android.diagnostics.AudioMetadataProbeDisclosure
import dev.gumi.edge.shell.android.diagnostics.AudioMetadataProbeState
import dev.gumi.edge.shell.android.diagnostics.BleAddressStabilityProbeState
import dev.gumi.edge.shell.android.diagnostics.BleAddressStabilityVerdict
import dev.gumi.edge.shell.android.diagnostics.BleProbeDevice
import dev.gumi.edge.shell.android.diagnostics.BleProbeState
import dev.gumi.edge.shell.android.diagnostics.DiagnosticOperation
import dev.gumi.edge.shell.android.diagnostics.DiagnosticOperationGate
import dev.gumi.edge.shell.android.diagnostics.DiagnosticOperationGateState
import dev.gumi.edge.shell.android.diagnostics.DriverNegotiationProbeState
import dev.gumi.edge.shell.android.diagnostics.FirmwareImageProbeState
import dev.gumi.edge.shell.android.diagnostics.FirmwareImageStateAssessment
import dev.gumi.edge.shell.android.diagnostics.GattProbeState
import dev.gumi.edge.shell.android.diagnostics.SharedOpusPacketMetadataInspector
import dev.gumi.edge.shell.android.runtime.AndroidRuntimeOwnerProjection
import dev.gumi.edge.shell.android.runtime.AndroidRuntimePreparedCommand
import dev.gumi.edge.shell.android.runtime.AndroidRuntimeProcessOwner
import dev.gumi.edge.shell.android.runtime.AndroidRuntimePermissions
import dev.gumi.edge.shell.android.runtime.AndroidRuntimeServiceLaunchResult
import dev.gumi.edge.shell.android.runtime.AndroidRuntimeServiceLauncher
import dev.gumi.edge.shell.android.runtime.GumiRuntimeApplication

class MainActivity : ComponentActivity() {
    private lateinit var bleProbeController: AndroidBleProbeController
    private lateinit var audioMetadataProbeController: AndroidAudioMetadataProbeController
    private lateinit var driverNegotiationProbeController: AndroidDriverNegotiationProbeController
    private lateinit var gattProbeController: AndroidGattProbeController
    private lateinit var firmwareImageProbeController: AndroidFirmwareImageProbeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val registry = DeviceDriverRegistry(listOf(OmiCv1DriverProvider()))
        val projection = androidDiagnosticProjection(registry)
        val endpointDirectory = AndroidBleEndpointDirectory()
        val diagnosticOperationGate = androidProcessDiagnosticOperationGate
        bleProbeController = AndroidBleProbeController(
            scanner = AndroidBleScanner(this, endpointDirectory),
            driverRegistry = registry,
            addressStabilityController = AndroidBleAddressStabilityProbeController(
                endpointDirectory::compareProcessLocalObservations,
            ),
        )
        driverNegotiationProbeController = AndroidDriverNegotiationProbeController(
            central = AndroidBleCentral(this, endpointDirectory),
            driverRegistry = registry,
            operationGate = diagnosticOperationGate,
        )
        audioMetadataProbeController = AndroidAudioMetadataProbeController(
            central = AndroidBleCentral(this, endpointDirectory),
            driverRegistry = registry,
            packetInspector = SharedOpusPacketMetadataInspector,
            operationGate = diagnosticOperationGate,
        )
        gattProbeController = AndroidGattProbeController(
            inspector = AndroidBleGattInspector(this, endpointDirectory),
            operationGate = diagnosticOperationGate,
        )
        firmwareImageProbeController = AndroidFirmwareImageProbeController(
            inspector = AndroidMcuMgrImageStateInspector(this, endpointDirectory),
            assessment = omiCv1V3012FirmwareAssessment,
            operationGate = diagnosticOperationGate,
        )
        val runtimeOwner = (application as GumiRuntimeApplication).runtimeOwner

        setContent {
            GumiApp(
                projection,
                bleProbeController,
                audioMetadataProbeController,
                driverNegotiationProbeController,
                gattProbeController,
                firmwareImageProbeController,
                diagnosticOperationGate,
                runtimeOwner,
            )
        }
    }

    override fun onStop() {
        bleProbeController.stop()
        audioMetadataProbeController.cancel()
        driverNegotiationProbeController.cancel()
        gattProbeController.cancel()
        firmwareImageProbeController.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        bleProbeController.close()
        audioMetadataProbeController.close()
        driverNegotiationProbeController.close()
        gattProbeController.close()
        firmwareImageProbeController.close()
        super.onDestroy()
    }
}

data class AndroidDiagnosticProjection(
    val status: String,
    val device: String,
    val driver: String,
    val protocol: String,
    val capabilities: List<String>,
)

internal fun androidDiagnosticProjection(registry: DeviceDriverRegistry): AndroidDiagnosticProjection {
    val endpoint = EndpointCandidate(
        transport = TransportKind.BLE,
        ephemeralId = "android:diagnostic:omi-cv1",
        advertisedServiceUuids = setOf(OmiCv1Protocol.AUDIO_SERVICE_UUID),
    )
    val selection = registry.select(endpoint)
    val descriptor = selection.provider.describe(endpoint)

    return AndroidDiagnosticProjection(
        status = "Portable runtime loaded; capabilities require connection",
        device = descriptor.model,
        driver = descriptor.driverId.value,
        protocol = descriptor.protocolVersion,
        capabilities = descriptor.capabilities.map { it.key.value },
    )
}

internal val omiCv1V3012FirmwareAssessment = FirmwareImageStateAssessment { inspection ->
    OmiCv1KnownV3012FirmwareOracle.assess(inspection).status.name
}

/**
 * Process-scoped because an Activity replacement can be created while the prior instance is still
 * completing non-cancellable BLE cleanup. A replacement must observe and respect that lease.
 */
internal val androidProcessDiagnosticOperationGate = DiagnosticOperationGate()

/** Process-local review authority only; endpoint identifiers are never rendered, logged, or saved. */
internal data class ReviewedDiagnosticEndpoints(
    val firmware: EndpointCandidate? = null,
    val audio: EndpointCandidate? = null,
) {
    fun invalidateForNewScan(): ReviewedDiagnosticEndpoints = ReviewedDiagnosticEndpoints()
}

internal enum class DiagnosticDisclosureReview {
    FIRMWARE_IMAGE_STATE,
    AUDIO_METADATA,
}

internal fun DiagnosticOperationGateState.allowsDiagnosticStart(): Boolean = !busy

internal fun DiagnosticOperationGateState.allowsCancel(operation: DiagnosticOperation): Boolean =
    activeOperation == operation

internal data class BleAddressStabilityUiState(
    val baselineCaptured: Boolean,
    val captureBaselineEnabled: Boolean,
    val freshScanVerdict: BleAddressStabilityVerdict?,
)

internal fun bleAddressStabilityUiState(
    state: BleAddressStabilityProbeState,
    scanning: Boolean,
): BleAddressStabilityUiState = BleAddressStabilityUiState(
    baselineCaptured = state.baselineCaptured,
    captureBaselineEnabled = state.baselineCaptureEnabled && !scanning,
    freshScanVerdict = state.verdict,
)

/** Same-process gate only. The ephemeral ID is never rendered, logged, or persisted. */
internal fun firmwareQualifiesAudioEndpoint(
    state: FirmwareImageProbeState,
    endpoint: EndpointCandidate,
): Boolean {
    val successfulId = state.successfulEndpointEphemeralId ?: return false
    if (successfulId != endpoint.ephemeralId) return false
    val inspection = state.inspection ?: return false
    if (inspection.endpoint.ephemeralId != successfulId) return false
    val observedStatus = OmiCv1KnownV3012FirmwareOracle.assess(inspection).status.name
    if (state.assessmentStatus != observedStatus) return false
    return observedStatus in setOf(
        OmiCv1FirmwareOracleStatus.MATCHES_PUBLISHED_V3012.name,
        OmiCv1FirmwareOracleStatus.APPLICATION_MATCH_NETWORK_UNOBSERVED.name,
        OmiCv1FirmwareOracleStatus.MATCHES_GUMI_CANARY_0001.name,
        OmiCv1FirmwareOracleStatus.GUMI_CANARY_APPLICATION_MATCH_NETWORK_UNOBSERVED.name,
    )
}

internal fun audioProbeFailureGuidance(code: String): String? = when (code) {
    "AUDIO_SETUP_TIMEOUT" ->
        "The Omi did not answer during setup. On the owned stock CV1, charger insertion is the " +
            "only physically proven recovery from its off state. Wake it, run a fresh scan, and " +
            "repeat the image-state review before retrying. This timeout does not mean pairing " +
            "is required."

    else -> null
}

internal data class AndroidRuntimeControlUiState(
    val execution: String = "STOPPED",
    val transport: String = "DISCONNECTED",
    val recovery: String = "CLEAN",
    val restartPolicy: String = "AUTOMATIC_ALLOWED",
    val outstandingDeliveries: Int = 0,
    val lastFailureCode: String? = null,
    val lastPlatformFailureCode: String? = null,
    val launchStatus: String = "No operational command delivered",
    val permissionsGranted: Boolean = false,
    val retryAvailable: Boolean = false,
    val operationalBoundaryBusy: Boolean = false,
    val startEnabled: Boolean = true,
)

internal fun androidRuntimeControlUiState(
    owner: AndroidRuntimeOwnerProjection,
    host: RuntimeHostProjection,
    launchStatus: String,
    permissionsGranted: Boolean,
    retryAvailable: Boolean,
    diagnosticBusy: Boolean,
): AndroidRuntimeControlUiState {
    val operationalBusy = owner.outstandingDeliveries > 0 ||
        host.execution !in setOf(
            RuntimeHostExecutionState.STOPPED,
            RuntimeHostExecutionState.START_DENIED,
        )
    return AndroidRuntimeControlUiState(
        execution = host.execution.name,
        transport = host.transport.name,
        recovery = host.recovery.name,
        restartPolicy = host.restartPolicy.name,
        outstandingDeliveries = owner.outstandingDeliveries,
        lastFailureCode = owner.lastFailure?.code?.value ?: host.lastFailure?.code?.value,
        lastPlatformFailureCode = owner.lastPlatformFailure?.code?.value,
        launchStatus = launchStatus,
        permissionsGranted = permissionsGranted,
        retryAvailable = retryAvailable,
        operationalBoundaryBusy = operationalBusy,
        startEnabled = !operationalBusy && !diagnosticBusy,
    )
}

@Composable
private fun GumiApp(
    projection: AndroidDiagnosticProjection,
    bleController: AndroidBleProbeController,
    audioController: AndroidAudioMetadataProbeController,
    driverNegotiationController: AndroidDriverNegotiationProbeController,
    gattController: AndroidGattProbeController,
    firmwareController: AndroidFirmwareImageProbeController,
    diagnosticOperationGate: DiagnosticOperationGate,
    runtimeOwner: AndroidRuntimeProcessOwner,
) {
    val context = LocalContext.current
    val scanState by bleController.state.collectAsState()
    val addressStabilityState by bleController.addressStabilityState.collectAsState()
    val audioState by audioController.state.collectAsState()
    val driverNegotiationState by driverNegotiationController.state.collectAsState()
    val gattState by gattController.state.collectAsState()
    val firmwareState by firmwareController.state.collectAsState()
    val diagnosticGateState by diagnosticOperationGate.state.collectAsState()
    val runtimeOwnerProjection by runtimeOwner.projection.collectAsState()
    val runtimeHostProjection by runtimeOwner.hostProjection.collectAsState()
    var reviewedEndpoints by remember { mutableStateOf(ReviewedDiagnosticEndpoints()) }
    var activeDisclosureReview by remember {
        mutableStateOf<DiagnosticDisclosureReview?>(null)
    }
    var permissionGranted by remember { mutableStateOf(context.hasBlePermissions()) }
    var runtimePermissionsGranted by remember {
        mutableStateOf(AndroidRuntimePermissions.areGranted(context))
    }
    var retryableRuntimeCommand by remember {
        mutableStateOf<AndroidRuntimePreparedCommand?>(null)
    }
    var runtimeLaunchStatus by remember { mutableStateOf("No operational command delivered") }

    fun deliverRuntimeCommand(command: AndroidRuntimePreparedCommand) {
        when (val result = AndroidRuntimeServiceLauncher.deliver(context, command)) {
            is AndroidRuntimeServiceLaunchResult.Delivered -> {
                retryableRuntimeCommand = null
                runtimeLaunchStatus = "Command delivered; awaiting the runtime projection"
            }

            is AndroidRuntimeServiceLaunchResult.Rejected -> {
                retryableRuntimeCommand = command
                runtimeLaunchStatus = "Command rejected: ${result.failure.code.value}"
            }

            is AndroidRuntimeServiceLaunchResult.OutcomeUnknown -> {
                retryableRuntimeCommand = command
                runtimeLaunchStatus =
                    "Delivery outcome unknown: ${result.failure.code.value}; exact retry is available"
            }
        }
    }

    fun startFreshScan() {
        if (
            bleController.state.value.scanning ||
            !diagnosticOperationGate.state.value.allowsDiagnosticStart() ||
            runtimeOwner.hostProjection.value.execution !in setOf(
                RuntimeHostExecutionState.STOPPED,
                RuntimeHostExecutionState.START_DENIED,
            )
        ) return

        // A disclosure review and firmware qualification belong to the scan generation that
        // produced their actionable card. Android's endpoint directory is process-local and may
        // still resolve the same ID after start() clears the visible cards, so invalidate both
        // pieces of authority before opening the next generation.
        reviewedEndpoints = reviewedEndpoints.invalidateForNewScan()
        activeDisclosureReview = null
        firmwareController.invalidateForNewScan()
        bleController.start()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionGranted = context.hasBlePermissions()
        if (permissionGranted && diagnosticGateState.allowsDiagnosticStart()) {
            startFreshScan()
        }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionGranted = context.hasBlePermissions()
        runtimePermissionsGranted = AndroidRuntimePermissions.areGranted(context)
        val pending = retryableRuntimeCommand
        if (runtimePermissionsGranted && pending?.foreground == true) {
            deliverRuntimeCommand(pending)
        } else if (!runtimePermissionsGranted) {
            runtimeLaunchStatus = "Runtime permission denied; no operational service was started"
        }
    }

    MaterialTheme {
        GumiDiagnosticScreen(
            projection = projection,
            scanState = scanState,
            addressStability = bleAddressStabilityUiState(
                state = addressStabilityState,
                scanning = scanState.scanning,
            ),
            audioState = audioState,
            driverNegotiationState = driverNegotiationState,
            gattState = gattState,
            firmwareState = firmwareState,
            diagnosticGateState = diagnosticGateState,
            firmwareDisclosure = firmwareController.disclosure,
            audioDisclosure = audioController.disclosure,
            reviewedFirmwareEndpoint = reviewedEndpoints.firmware,
            reviewedAudioEndpoint = reviewedEndpoints.audio,
            activeDisclosureReview = activeDisclosureReview,
            permissionGranted = permissionGranted,
            onRequestPermission = { permissionLauncher.launch(requiredBlePermissions()) },
            onStartScan = ::startFreshScan,
            onStopScan = bleController::stop,
            onCaptureAddressStabilityBaseline = {
                bleController.captureAddressStabilityBaseline()
            },
            onInspect = { device ->
                bleController.stop()
                gattController.inspect(device.endpoint)
            },
            onNegotiateDriver = { device ->
                bleController.stop()
                driverNegotiationController.probe(device.endpoint)
            },
            onReviewFirmware = { device ->
                reviewedEndpoints = reviewedEndpoints.copy(firmware = device.endpoint)
                activeDisclosureReview = DiagnosticDisclosureReview.FIRMWARE_IMAGE_STATE
            },
            onReviewAudio = { device ->
                if (firmwareQualifiesAudioEndpoint(firmwareState, device.endpoint)) {
                    reviewedEndpoints = reviewedEndpoints.copy(audio = device.endpoint)
                    activeDisclosureReview = DiagnosticDisclosureReview.AUDIO_METADATA
                }
            },
            onInspectFirmware = { endpoint ->
                activeDisclosureReview = null
                bleController.stop()
                firmwareController.inspect(endpoint)
            },
            onProbeAudio = { endpoint ->
                if (firmwareQualifiesAudioEndpoint(firmwareState, endpoint)) {
                    activeDisclosureReview = null
                    bleController.stop()
                    audioController.probe(endpoint)
                }
            },
            onDismissDisclosure = {
                reviewedEndpoints = when (activeDisclosureReview) {
                    DiagnosticDisclosureReview.FIRMWARE_IMAGE_STATE ->
                        reviewedEndpoints.copy(firmware = null)

                    DiagnosticDisclosureReview.AUDIO_METADATA ->
                        reviewedEndpoints.copy(audio = null)

                    null -> reviewedEndpoints
                }
                activeDisclosureReview = null
            },
            onCancelAudio = audioController::cancel,
            onCancelDriverNegotiation = driverNegotiationController::cancel,
            onCancelGatt = gattController::cancel,
            onCancelFirmware = firmwareController::cancel,
            runtimeControl = androidRuntimeControlUiState(
                runtimeOwnerProjection,
                runtimeHostProjection,
                runtimeLaunchStatus,
                runtimePermissionsGranted,
                retryableRuntimeCommand != null,
                diagnosticGateState.busy,
            ),
            onRuntimeStart = {
                bleController.stop()
                val command = AndroidRuntimeServiceLauncher.prepareExplicitStart(context)
                retryableRuntimeCommand = command
                runtimePermissionsGranted = AndroidRuntimePermissions.areGranted(context)
                if (runtimePermissionsGranted) {
                    deliverRuntimeCommand(command)
                } else {
                    runtimePermissionLauncher.launch(AndroidRuntimePermissions.required())
                }
            },
            onRuntimeStop = {
                val command = AndroidRuntimeServiceLauncher.prepareExplicitStop(context)
                retryableRuntimeCommand = command
                deliverRuntimeCommand(command)
            },
            onRuntimeRetry = {
                retryableRuntimeCommand?.let(::deliverRuntimeCommand)
            },
        )
    }
}

@Composable
private fun GumiDiagnosticScreen(
    projection: AndroidDiagnosticProjection,
    scanState: BleProbeState,
    addressStability: BleAddressStabilityUiState,
    audioState: AudioMetadataProbeState,
    driverNegotiationState: DriverNegotiationProbeState,
    gattState: GattProbeState,
    firmwareState: FirmwareImageProbeState,
    diagnosticGateState: DiagnosticOperationGateState,
    firmwareDisclosure: FirmwareImageStateReadDisclosure,
    audioDisclosure: AudioMetadataProbeDisclosure,
    reviewedFirmwareEndpoint: EndpointCandidate?,
    reviewedAudioEndpoint: EndpointCandidate?,
    activeDisclosureReview: DiagnosticDisclosureReview?,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onCaptureAddressStabilityBaseline: () -> Unit,
    onInspect: (BleProbeDevice) -> Unit,
    onNegotiateDriver: (BleProbeDevice) -> Unit,
    onReviewFirmware: (BleProbeDevice) -> Unit,
    onReviewAudio: (BleProbeDevice) -> Unit,
    onInspectFirmware: (EndpointCandidate) -> Unit,
    onProbeAudio: (EndpointCandidate) -> Unit,
    onDismissDisclosure: () -> Unit,
    onCancelAudio: () -> Unit,
    onCancelDriverNegotiation: () -> Unit,
    onCancelGatt: () -> Unit,
    onCancelFirmware: () -> Unit,
    runtimeControl: AndroidRuntimeControlUiState = AndroidRuntimeControlUiState(),
    onRuntimeStart: () -> Unit = {},
    onRuntimeStop: () -> Unit = {},
    onRuntimeRetry: () -> Unit = {},
) {
    val diagnosticStartsEnabled = diagnosticGateState.allowsDiagnosticStart() &&
        !runtimeControl.operationalBoundaryBusy
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Gumi", style = MaterialTheme.typography.headlineLarge)
            Text(projection.status, style = MaterialTheme.typography.titleMedium)
            Text("Device contract: ${projection.device}")
            Text("Driver: ${projection.driver}")
            Text("Protocol witness: ${projection.protocol}")

            AndroidRuntimeControlPanel(
                state = runtimeControl,
                onStart = onRuntimeStart,
                onStop = onRuntimeStop,
                onRetry = onRuntimeRetry,
            )

            if (diagnosticGateState.busy) {
                Text(
                    if (diagnosticGateState.cancelling) {
                        "Finishing BLE transport cleanup; new diagnostics remain disabled."
                    } else {
                        "One diagnostic owns the BLE transport; other diagnostics remain disabled."
                    },
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Text("Read-only Omi discovery", style = MaterialTheme.typography.titleLarge)
            if (!permissionGranted) {
                Text("Nearby Devices permission is required to scan. No connection or write is performed.")
                Button(onClick = onRequestPermission) {
                    Text("Grant Nearby Devices")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onStartScan,
                        enabled = !scanState.scanning && diagnosticStartsEnabled,
                    ) {
                        Text(if (scanState.scanning) "Scanning…" else "Start scan")
                    }
                    OutlinedButton(onClick = onStopScan, enabled = scanState.scanning) {
                        Text("Stop")
                    }
                }

                scanState.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                }

                Text(
                    "Nearby BLE broadcasters: ${scanState.nearbyDeviceCount} " +
                        "(identities not retained)",
                )

                BleAddressStabilityPanel(
                    state = addressStability,
                    onCaptureBaseline = onCaptureAddressStabilityBaseline,
                )

                if (scanState.devices.isEmpty()) {
                    Text(if (scanState.scanning) "Waiting for an Omi advertisement…" else "No Omi observed yet.")
                } else {
                    scanState.devices.forEach { device ->
                        BleProbeCard(
                            device = device,
                            diagnosticBusy = !diagnosticStartsEnabled,
                            audioProbeUnlocked =
                                firmwareQualifiesAudioEndpoint(firmwareState, device.endpoint),
                            onNegotiateDriver = { onNegotiateDriver(device) },
                            onInspect = { onInspect(device) },
                            onReviewFirmware = { onReviewFirmware(device) },
                            onReviewAudio = { onReviewAudio(device) },
                        )
                    }
                }

                DriverNegotiationProbePanel(
                    state = driverNegotiationState,
                    cancelEnabled = diagnosticGateState.allowsCancel(
                        DiagnosticOperation.DRIVER_NEGOTIATION,
                    ),
                    onCancel = onCancelDriverNegotiation,
                )
                GattProbePanel(
                    state = gattState,
                    cancelEnabled = diagnosticGateState.allowsCancel(
                        DiagnosticOperation.GATT_INSPECTION,
                    ),
                    onCancel = onCancelGatt,
                )
                FirmwareImageProbePanel(
                    state = firmwareState,
                    cancelEnabled = diagnosticGateState.allowsCancel(
                        DiagnosticOperation.FIRMWARE_IMAGE_STATE,
                    ),
                    onCancel = onCancelFirmware,
                )
                AudioMetadataProbePanel(
                    state = audioState,
                    cancelEnabled = diagnosticGateState.allowsCancel(
                        DiagnosticOperation.AUDIO_METADATA,
                    ),
                    onCancel = onCancelAudio,
                )
            }

            Text("Declared capabilities", style = MaterialTheme.typography.titleMedium)
            projection.capabilities.forEach { capability -> Text("• $capability") }
        }
    }

    when (activeDisclosureReview) {
        DiagnosticDisclosureReview.FIRMWARE_IMAGE_STATE -> {
            reviewedFirmwareEndpoint?.let { endpoint ->
                FirmwareImageStateDisclosureSheet(
                    disclosure = firmwareDisclosure,
                    actionEnabled = diagnosticStartsEnabled,
                    onDismiss = onDismissDisclosure,
                    onRun = { onInspectFirmware(endpoint) },
                )
            }
        }

        DiagnosticDisclosureReview.AUDIO_METADATA -> {
            reviewedAudioEndpoint?.let { endpoint ->
                AudioMetadataDisclosureSheet(
                    disclosure = audioDisclosure,
                    actionEnabled = diagnosticStartsEnabled &&
                        firmwareQualifiesAudioEndpoint(firmwareState, endpoint),
                    onDismiss = onDismissDisclosure,
                    onRun = { onProbeAudio(endpoint) },
                )
            }
        }

        null -> Unit
    }
}

@Composable
private fun BleAddressStabilityPanel(
    state: BleAddressStabilityUiState,
    onCaptureBaseline: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Stock Omi BLE-address diagnostic", style = MaterialTheme.typography.titleMedium)
            Text(
                "This compares only Android's process-local scan-address mapping. It is never " +
                    "device identity, ownership, bonding, association, or capture authority.",
            )
            if (state.baselineCaptured) {
                Text("Baseline: held in memory until this Activity or process is replaced.")
            } else {
                Text("Baseline: not captured. First observe exactly one current Omi candidate.")
                OutlinedButton(
                    onClick = onCaptureBaseline,
                    enabled = state.captureBaselineEnabled,
                ) {
                    Text("Capture single-candidate baseline")
                }
            }
            state.freshScanVerdict?.let { verdict ->
                Text("Fresh-scan comparison: ${verdict.name}")
            }
            Text(
                "After baseline capture, each new scan stays INCONCLUSIVE until stopped. A closed " +
                    "generation with zero, multiple, stale, or unresolvable candidates remains " +
                    "INCONCLUSIVE. No address or endpoint reference is shown or saved.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AndroidRuntimeControlPanel(
    state: AndroidRuntimeControlUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Operational edge runtime", style = MaterialTheme.typography.titleLarge)
            Text(
                "Execution ${state.execution} • transport ${state.transport} • " +
                    "recovery ${state.recovery}",
            )
            Text(
                "Restart policy ${state.restartPolicy} • pending ${state.outstandingDeliveries}",
            )
            Text(state.launchStatus)
            if (!state.permissionsGranted) {
                Text(
                    "Start requests Nearby Devices and notification visibility first. " +
                        "No automatic start source is enabled.",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            state.lastFailureCode?.let { Text("Runtime failure: $it") }
            state.lastPlatformFailureCode?.let { Text("Platform failure: $it") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onStart, enabled = state.startEnabled) {
                    Text("Start runtime")
                }
                OutlinedButton(onClick = onStop) {
                    Text("Request stop")
                }
            }
            if (state.retryAvailable) {
                OutlinedButton(onClick = onRetry) {
                    Text("Retry exact command")
                }
            }
            Text(
                "This offline build still fails closed without companion association and durable " +
                    "runtime recovery; service presence is not recording proof.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BleProbeCard(
    device: BleProbeDevice,
    diagnosticBusy: Boolean,
    audioProbeUnlocked: Boolean,
    onNegotiateDriver: () -> Unit,
    onInspect: () -> Unit,
    onReviewFirmware: () -> Unit,
    onReviewAudio: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(device.advertisedName, style = MaterialTheme.typography.titleMedium)
            Text("RSSI ${device.rssi} dBm • connectable ${device.connectable ?: "unknown"}")
            Text("Driver ${device.matchedDriver ?: "unmatched"} (${device.matchConfidence ?: "none"})")
            Text("Advertised services:")
            device.serviceUuids.sorted().forEach { uuid -> Text("• $uuid") }
            if (device.serviceDataLengths.isNotEmpty()) {
                Text("Service-data lengths: ${device.serviceDataLengths}")
            }
            if (device.manufacturerDataLengths.isNotEmpty()) {
                Text("Manufacturer-data lengths: ${device.manufacturerDataLengths}")
            }
            Button(onClick = onNegotiateDriver, enabled = !diagnosticBusy) {
                Text(if (diagnosticBusy) "Probe in progress…" else "Connect + negotiate driver")
            }
            Button(onClick = onInspect, enabled = !diagnosticBusy) {
                Text(if (diagnosticBusy) "Inspecting…" else "Inspect read-only GATT")
            }
            OutlinedButton(onClick = onReviewFirmware, enabled = !diagnosticBusy) {
                Text("Review MCU image-state read")
            }
            OutlinedButton(
                onClick = onReviewAudio,
                enabled = !diagnosticBusy && audioProbeUnlocked,
            ) {
                Text("Review 10-second live-audio metadata probe")
            }
            if (!audioProbeUnlocked) {
                Text(
                    "Audio probe remains locked until the exact published v3.0.12 " +
                        "application image is verified.",
                )
            }
        }
    }
}

@Composable
private fun FirmwareImageStateDisclosureSheet(
    disclosure: FirmwareImageStateReadDisclosure,
    actionEnabled: Boolean,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
) {
    OwnerDisclosureSheet(
        title = "MCU image-state read",
        actionLabel = "Run disclosed image-state read",
        actionEnabled = actionEnabled,
        onDismiss = onDismiss,
        onAction = onRun,
    ) {
        Text(
            "This is device-state read-only, but it is not GATT-write-free. " +
                "The response protocol requires transient BLE writes.",
        )
        Text("Exact transient operations:", style = MaterialTheme.typography.titleSmall)
        Text("• Connect and discover the SMP service")
        Text("• Request ATT MTU ${disclosure.requestedAttMtu ?: "platform default"}")
        if (disclosure.writesNotificationDescriptor) {
            Text("• Write the SMP CCCD to enable response notifications")
        }
        disclosure.protocolReads.forEach { read ->
            Text(
                "• Send ${read.label} READ request " +
                    "(group ${read.groupId}, command ${read.commandId})",
            )
        }
        Text("• Disconnect and release the transport")
        Text(
            "Persistent device mutation expected: " +
                if (disclosure.persistentDeviceMutationExpected) "yes" else "no",
        )
        Text("Unavailable here: upload, test, confirm, reset, erase, files, settings, or shell.")
    }
}

@Composable
private fun AudioMetadataDisclosureSheet(
    disclosure: AudioMetadataProbeDisclosure,
    actionEnabled: Boolean,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
) {
    OwnerDisclosureSheet(
        title = "10-second live-audio metadata probe",
        actionLabel = "Run disclosed 10-second metadata probe",
        actionEnabled = actionEnabled,
        onDismiss = onDismiss,
        onAction = onRun,
    ) {
        Text(
            "Run only in a controlled quiet room with no sensitive speech, media, or " +
                "bystanders. The app observes transient audio packets but never renders, " +
                "persists, uploads, hashes, or logs their content.",
        )
        Text("Exact disclosed operations:", style = MaterialTheme.typography.titleSmall)
        disclosure.operations.forEach { operation -> Text("• $operation") }
        Text(
            "Hard bounds: exactly ${disclosure.durationMillis / 1_000} seconds, at most " +
                "${disclosure.maximumFrames} frames and " +
                "${disclosure.maximumPayloadBytes} transient payload bytes.",
        )
        Text(
            "Coverage gate: at least ${disclosure.minimumFrames} frames spanning at least " +
                "${disclosure.minimumReceiveSpanMillis} ms, with no receive gap above " +
                "${disclosure.maximumInterarrivalMillis} ms.",
        )
        Text("BLE setup: request ATT MTU ${disclosure.requestedAttMtu} before audio subscribe.")
        Text("Unavailable here:", style = MaterialTheme.typography.titleSmall)
        disclosure.unavailableOperations.forEach { operation -> Text("• $operation") }
        Text(
            "Qualification also fails on insufficient coverage, receive starvation, a sequence " +
                "gap, discontinuity, notification drop or overflow, disconnect, deadline, or " +
                "incomplete close.",
        )
    }
}

@Composable
private fun AudioMetadataProbePanel(
    state: AudioMetadataProbeState,
    cancelEnabled: Boolean,
    onCancel: () -> Unit,
) {
    if (state.result == null && !state.running) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Qualified Omi live-audio metadata probe", style = MaterialTheme.typography.titleMedium)
            Text(
                "Packet content is never rendered, persisted, uploaded, hashed, or logged.",
            )

            if (state.running) {
                Text(
                    if (state.cancelling) {
                        "Cancelling after stream, device, and transport cleanup…"
                    } else {
                        "Observing metadata in memory for 10 seconds, then closing every owner…"
                    },
                )
                OutlinedButton(onClick = onCancel, enabled = cancelEnabled) {
                    Text(if (state.cancelling) "Cancellation in progress…" else "Cancel audio probe")
                }
            }

            state.result?.let { result ->
                Text(
                    "Outcome: ${result.code}" +
                        (result.reasonCode?.let { " ($it)" } ?: ""),
                    color = if (result.qualified) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!result.qualified) {
                    audioProbeFailureGuidance(result.code)?.let { guidance ->
                        Text(guidance, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                result.facts?.let { facts ->
                    Text(
                        "Format: ${facts.format.codec} ${facts.format.sampleRateHz}Hz " +
                            "${facts.format.channels}ch • ${facts.format.payloadFraming}",
                    )
                    Text(
                        "BLE link: MTU ${facts.link?.mtu ?: "unknown"} • " +
                            "TX PHY ${facts.link?.txPhy ?: "unknown"} • " +
                            "RX PHY ${facts.link?.rxPhy ?: "unknown"} • " +
                            "bond ${facts.link?.bondState ?: "unknown"}",
                    )
                    Text(
                        "Frames ${facts.frameCount} • payload bytes ${facts.totalPayloadBytes} " +
                            "(min ${facts.minimumPayloadBytes ?: "n/a"}, " +
                            "max ${facts.maximumPayloadBytes ?: "n/a"})",
                    )
                    Text(
                        "Sequence ${facts.firstSequence ?: "n/a"}…" +
                            "${facts.lastSequence ?: "n/a"} • gaps ${facts.sequenceGapCount} • " +
                            "discontinuity flags ${facts.discontinuityFlagCount}",
                    )
                    Text(
                        "BLE receive interarrival ms: min " +
                            "${facts.minimumInterarrivalMillis ?: "n/a"}, max " +
                            "${facts.maximumInterarrivalMillis ?: "n/a"} • span " +
                            "${facts.receiveSpanMillis ?: "n/a"}",
                    )
                    if (facts.opusFrameDurationsUs.isNotEmpty()) {
                        Text(
                            "Opus TOC: configurations ${facts.opusTocConfigurations.sorted()} • " +
                                "encodedStereo ${facts.opusEncodedStereo.sorted()} • " +
                                "frame counts ${facts.opusFrameCounts.sorted()} • " +
                                "durations μs ${facts.opusFrameDurationsUs.sorted()} • " +
                                "decoded samples@48k ${facts.opusDecodedSamples48k.sorted()}",
                        )
                    }
                }
                Text("Result was published only after stream, device session, and transport close.")
            }
        }
    }
}

@Composable
private fun DriverNegotiationProbePanel(
    state: DriverNegotiationProbeState,
    cancelEnabled: Boolean,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Operational driver negotiation", style = MaterialTheme.typography.titleMedium)
            Text(
                "Runs only when you tap Connect + negotiate driver. It opens one BLE link, " +
                    "discovers GATT services, reads Manufacturer, Model, Firmware Revision, " +
                    "requires codec ID 21 (Opus), reads link metadata, then disconnects.",
            )
            Text(
                "It does not subscribe to button, battery, or audio streams; invoke haptics; " +
                    "or write device characteristics.",
            )
            Text(
                "Bench status: this read-only path has completed against the owned CV1. Each run " +
                    "still has to produce its own terminal result.",
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (state.connecting) {
                Text(
                    if (state.cancelling) {
                        "Cancelling after device and transport cleanup…"
                    } else {
                        "Connecting, negotiating, then disconnecting…"
                    },
                )
                OutlinedButton(onClick = onCancel, enabled = cancelEnabled) {
                    Text(
                        if (state.cancelling) {
                            "Cancellation in progress…"
                        } else {
                            "Cancel driver negotiation"
                        },
                    )
                }
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            state.projection?.let { projection ->
                Text("Manufacturer: ${projection.manufacturer}")
                Text("Model: ${projection.model}")
                Text("Driver: ${projection.driver}")
                Text("Protocol: ${projection.protocol}")
                Text(
                    "BLE link: MTU ${projection.link.mtu ?: "unknown"} • " +
                        "PHY ${projection.link.txPhy ?: "unknown"}/" +
                        "${projection.link.rxPhy ?: "unknown"} • " +
                        "bond ${projection.link.bondState}",
                )
                Text("Negotiated capability descriptors", style = MaterialTheme.typography.titleSmall)
                projection.capabilities.forEach { capability ->
                    Text(
                        "• ${capability.key} @ ${capability.version} " +
                            "(${if (capability.required) "required" else "optional"})",
                    )
                    capability.fields.forEach { (field, value) -> Text("  ↳ $field: $value") }
                    if (capability.attributeNames.isNotEmpty()) {
                        Text("  ↳ opaque attribute names: ${capability.attributeNames}")
                    }
                }
            }
        }
    }
}

@Composable
private fun FirmwareImageProbePanel(
    state: FirmwareImageProbeState,
    cancelEnabled: Boolean,
    onCancel: () -> Unit,
) {
    if (state.inspection == null && state.error == null && !state.inspecting) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("MCU image-state inspection", style = MaterialTheme.typography.titleMedium)

            if (state.inspecting) {
                Text(
                    if (state.cancelling) {
                        "Cancelling after MCU Manager transport cleanup…"
                    } else {
                        "Reading MCU image state, then disconnecting…"
                    },
                )
                OutlinedButton(onClick = onCancel, enabled = cancelEnabled) {
                    Text(
                        if (state.cancelling) {
                            "Cancellation in progress…"
                        } else {
                            "Cancel image-state read"
                        },
                    )
                }
            }

            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            state.inspection?.let { inspection ->
                val oracle = OmiCv1KnownV3012FirmwareOracle.assess(inspection)
                Text(
                    "${inspection.slots.size} slots • split status " +
                        "${inspection.splitStatus ?: "unavailable"}",
                    style = MaterialTheme.typography.titleSmall,
                )
                inspection.slots.forEach { slot ->
                    Text("Image ${slot.imageNumber} • slot ${slot.slotNumber} • ${slot.version ?: "unknown"}")
                    Text("  hash ${slot.hash?.hex ?: "unavailable"}")
                    Text(
                        "  bootable=${slot.bootable}, pending=${slot.pending}, " +
                            "confirmed=${slot.confirmed}, active=${slot.active}, " +
                            "permanent=${slot.permanent}, compressed=${slot.compressed}",
                    )
                }
                Text(
                    "Qualified ${oracle.releaseTag} oracle: " +
                        (state.assessmentStatus ?: "unavailable"),
                    style = MaterialTheme.typography.titleSmall,
                )
                when (oracle.status) {
                    OmiCv1FirmwareOracleStatus.MATCHES_PUBLISHED_V3012 ->
                        Text("• Both active image hashes match the official release artifact")

                    OmiCv1FirmwareOracleStatus.APPLICATION_MATCH_NETWORK_UNOBSERVED ->
                        Text(
                            "• The application image exactly matches v3.0.12; stock MCU Manager " +
                                "did not expose the network image. Read-only diagnostics may " +
                                "continue, but firmware mutation remains forbidden.",
                            color = MaterialTheme.colorScheme.tertiary,
                        )

                    OmiCv1FirmwareOracleStatus.MATCHES_GUMI_CANARY_0001 ->
                        Text("• Application and network hashes match qualified canary-0001")

                    OmiCv1FirmwareOracleStatus.GUMI_CANARY_APPLICATION_MATCH_NETWORK_UNOBSERVED ->
                        Text(
                            "• The application exactly matches canary-0001; MCU Manager did not " +
                                "expose the network image. Bounded GATT/audio diagnostics may continue.",
                            color = MaterialTheme.colorScheme.tertiary,
                        )

                    else -> Unit
                }
                if (oracle.findings.isNotEmpty()) {
                    oracle.findings.forEach { finding ->
                        Text(
                            "• ${finding.code}: image ${finding.imageNumber ?: "unknown"}, " +
                                "slot ${finding.slotNumber ?: "unknown"}" +
                                (finding.observed?.let { ", observed $it" } ?: ""),
                            color = if (
                                oracle.status ==
                                OmiCv1FirmwareOracleStatus.APPLICATION_MATCH_NETWORK_UNOBSERVED ||
                                oracle.status ==
                                OmiCv1FirmwareOracleStatus.GUMI_CANARY_APPLICATION_MATCH_NETWORK_UNOBSERVED
                            ) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GattProbePanel(
    state: GattProbeState,
    cancelEnabled: Boolean,
    onCancel: () -> Unit,
) {
    if (state.inspecting) {
        Text(
            if (state.cancelling) {
                "Cancelling after read-only GATT transport cleanup…"
            } else {
                "Connecting once, reading the allowlist, then disconnecting…"
            },
        )
        OutlinedButton(onClick = onCancel, enabled = cancelEnabled) {
            Text(if (state.cancelling) "Cancellation in progress…" else "Cancel GATT inspection")
        }
    }
    state.error?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error)
    }
    val inspection = state.inspection ?: return
    val evidence = state.evidence ?: return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Read-only GATT inventory", style = MaterialTheme.typography.titleMedium)
            Text(
                "${inspection.services.size} services • " +
                    "${inspection.services.sumOf { it.characteristics.size }} characteristics",
            )
            Text(
                "MTU ${inspection.link.mtu ?: "unknown"} • " +
                    "PHY ${inspection.link.txPhy ?: "unknown"}/${inspection.link.rxPhy ?: "unknown"} • " +
                    "bond ${inspection.link.bondState}",
            )
            GattEvidence(evidence)
            Text("Discovered service tree", style = MaterialTheme.typography.titleSmall)
            inspection.services.forEach { service ->
                Text("• ${service.uuid} (${if (service.primary) "primary" else "secondary"})")
                service.characteristics.forEach { characteristic ->
                    Text("  ↳ ${characteristic.uuid} ${characteristic.properties.sortedBy { it.name }}")
                }
            }
        }
    }
}

@Composable
private fun GattEvidence(evidence: OmiCv1GattEvidence) {
    Text("Manufacturer: ${evidence.manufacturer ?: "unavailable"}")
    Text("Model: ${evidence.modelNumber ?: "unavailable"}")
    Text("Firmware: ${evidence.firmwareRevision ?: "unavailable"}")
    Text("Hardware: ${evidence.hardwareRevision ?: "unavailable"}")
    Text("Software: ${evidence.softwareRevision ?: "unavailable"}")
    Text("Battery: ${evidence.batteryPercent?.let { "$it%" } ?: "unavailable"}")
    when (val storage = evidence.storage) {
        is OmiCv1StorageEvidence.LegacyV3012FileSizes -> Text(
            "Storage v3.0.12: file1=${storage.firstFileBytes} bytes, " +
                "file2=${storage.secondFileBytes} bytes",
        )

        is OmiCv1StorageEvidence.V3020Status -> Text(
            "Storage v3.0.20: used=${storage.usedBytes}, unread=${storage.unreadPackets}, " +
                "free=${storage.freeBytes}, rtcValid=${storage.rtcValid}",
        )

        is OmiCv1StorageEvidence.Unknown -> Text(
            "Storage status: unknown ${storage.payloadSize}-byte shape",
        )

        null -> Text("Storage status: unavailable")
    }
    evidence.storageRawHex?.let { rawHex -> Text("Storage raw hex: $rawHex") }
    if (evidence.readFailures.isNotEmpty()) {
        Text(
            "Allowlisted read failures: " +
                evidence.readFailures.joinToString { "${it.target.characteristicUuid} (${it.code})" },
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun Context.hasBlePermissions(): Boolean = requiredBlePermissions().all { permission ->
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

private fun requiredBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

@Preview(showBackground = true)
@Composable
private fun GumiDiagnosticPreview() {
    MaterialTheme {
        GumiDiagnosticScreen(
            projection = AndroidDiagnosticProjection(
                status = "Portable runtime loaded",
                device = "Omi consumer v1",
                driver = "gumi.device.omi-cv1",
                protocol = "stock-ring-v1 (3.0.20+)",
                capabilities = listOf("gumi.audio-input", "gumi.capture-control"),
            ),
            scanState = BleProbeState(
                scanning = true,
                devices = listOf(
                    BleProbeDevice(
                        endpoint = EndpointCandidate(
                            transport = TransportKind.BLE,
                            ephemeralId = "preview",
                            advertisedServiceUuids = setOf(OmiCv1Protocol.AUDIO_SERVICE_UUID),
                            advertisedName = "Omi",
                        ),
                        advertisedName = "Omi",
                        rssi = -54,
                        connectable = true,
                        serviceUuids = setOf(OmiCv1Protocol.AUDIO_SERVICE_UUID),
                        serviceDataLengths = emptyMap(),
                        manufacturerDataLengths = emptyMap(),
                        matchedDriver = "gumi.device.omi-cv1",
                        matchConfidence = MatchConfidence.EXACT,
                    ),
                ),
            ),
            addressStability = BleAddressStabilityUiState(
                baselineCaptured = false,
                captureBaselineEnabled = true,
                freshScanVerdict = null,
            ),
            audioState = AudioMetadataProbeState(),
            driverNegotiationState = DriverNegotiationProbeState(),
            gattState = GattProbeState(),
            firmwareState = FirmwareImageProbeState(),
            diagnosticGateState = DiagnosticOperationGateState(),
            firmwareDisclosure = AndroidMcuMgrImageStateInspector.READ_DISCLOSURE,
            audioDisclosure = AudioMetadataProbeDisclosure(
                durationMillis = 10_000,
                minimumFrames = 450,
                maximumFrames = 1_000,
                maximumPayloadBytes = 1_276_000,
                minimumReceiveSpanMillis = 8_500,
                maximumInterarrivalMillis = 250,
                requestedAttMtu = 512,
            ),
            reviewedFirmwareEndpoint = null,
            reviewedAudioEndpoint = null,
            activeDisclosureReview = null,
            permissionGranted = true,
            onRequestPermission = {},
            onStartScan = {},
            onStopScan = {},
            onCaptureAddressStabilityBaseline = {},
            onInspect = {},
            onNegotiateDriver = {},
            onReviewFirmware = {},
            onReviewAudio = {},
            onInspectFirmware = {},
            onProbeAudio = {},
            onDismissDisclosure = {},
            onCancelAudio = {},
            onCancelDriverNegotiation = {},
            onCancelGatt = {},
            onCancelFirmware = {},
        )
    }
}
