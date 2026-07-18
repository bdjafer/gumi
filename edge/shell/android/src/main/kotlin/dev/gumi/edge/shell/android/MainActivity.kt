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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.gumi.devices.omicv1.OmiCv1DriverProvider
import dev.gumi.devices.omicv1.OmiCv1StockV3012FirmwareOracle
import dev.gumi.devices.omicv1.OmiCv1GattEvidence
import dev.gumi.devices.omicv1.OmiCv1Protocol
import dev.gumi.devices.omicv1.OmiCv1StorageEvidence
import dev.gumi.edge.platforms.android.ble.AndroidBleEndpointDirectory
import dev.gumi.edge.platforms.android.ble.AndroidBleGattInspector
import dev.gumi.edge.platforms.android.ble.AndroidBleScanner
import dev.gumi.edge.platforms.android.ble.AndroidMcuMgrImageStateInspector
import dev.gumi.edge.runtime.DeviceDriverRegistry
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.firmware.FirmwareImageStateReadDisclosure
import dev.gumi.edge.shell.android.diagnostics.AndroidBleProbeController
import dev.gumi.edge.shell.android.diagnostics.AndroidFirmwareImageProbeController
import dev.gumi.edge.shell.android.diagnostics.AndroidGattProbeController
import dev.gumi.edge.shell.android.diagnostics.BleProbeDevice
import dev.gumi.edge.shell.android.diagnostics.BleProbeState
import dev.gumi.edge.shell.android.diagnostics.FirmwareImageProbeState
import dev.gumi.edge.shell.android.diagnostics.GattProbeState

class MainActivity : ComponentActivity() {
    private lateinit var bleProbeController: AndroidBleProbeController
    private lateinit var gattProbeController: AndroidGattProbeController
    private lateinit var firmwareImageProbeController: AndroidFirmwareImageProbeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val registry = DeviceDriverRegistry(listOf(OmiCv1DriverProvider()))
        val projection = androidDiagnosticProjection(registry)
        val endpointDirectory = AndroidBleEndpointDirectory()
        bleProbeController = AndroidBleProbeController(
            scanner = AndroidBleScanner(this, endpointDirectory),
            driverRegistry = registry,
        )
        gattProbeController = AndroidGattProbeController(
            inspector = AndroidBleGattInspector(this, endpointDirectory),
        )
        firmwareImageProbeController = AndroidFirmwareImageProbeController(
            inspector = AndroidMcuMgrImageStateInspector(this, endpointDirectory),
        )

        setContent {
            GumiApp(
                projection,
                bleProbeController,
                gattProbeController,
                firmwareImageProbeController,
            )
        }
    }

    override fun onStop() {
        bleProbeController.stop()
        gattProbeController.cancel()
        firmwareImageProbeController.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        bleProbeController.close()
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
        transport = TransportKind.SIMULATED,
        ephemeralId = "android:diagnostic:omi-cv1",
        advertisedServiceUuids = setOf(OmiCv1Protocol.AUDIO_SERVICE_UUID),
    )
    val selection = registry.select(endpoint)
    val descriptor = selection.provider.describe(endpoint)

    return AndroidDiagnosticProjection(
        status = "Portable runtime loaded",
        device = descriptor.model,
        driver = descriptor.driverId.value,
        protocol = descriptor.protocolVersion,
        capabilities = descriptor.capabilities.map { it.key.value },
    )
}

@Composable
private fun GumiApp(
    projection: AndroidDiagnosticProjection,
    bleController: AndroidBleProbeController,
    gattController: AndroidGattProbeController,
    firmwareController: AndroidFirmwareImageProbeController,
) {
    val context = LocalContext.current
    val scanState by bleController.state.collectAsState()
    val gattState by gattController.state.collectAsState()
    val firmwareState by firmwareController.state.collectAsState()
    var reviewedFirmwareEndpoint by remember { mutableStateOf<EndpointCandidate?>(null) }
    var permissionGranted by remember { mutableStateOf(context.hasBlePermissions()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionGranted = context.hasBlePermissions()
        if (permissionGranted) bleController.start()
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) bleController.start()
    }

    MaterialTheme {
        GumiDiagnosticScreen(
            projection = projection,
            scanState = scanState,
            gattState = gattState,
            firmwareState = firmwareState,
            firmwareDisclosure = firmwareController.disclosure,
            reviewedFirmwareEndpoint = reviewedFirmwareEndpoint,
            permissionGranted = permissionGranted,
            onRequestPermission = { permissionLauncher.launch(requiredBlePermissions()) },
            onStartScan = bleController::start,
            onStopScan = bleController::stop,
            onInspect = { device ->
                bleController.stop()
                gattController.inspect(device.endpoint)
            },
            onReviewFirmware = { device ->
                reviewedFirmwareEndpoint = device.endpoint
            },
            onInspectFirmware = { endpoint ->
                bleController.stop()
                firmwareController.inspect(endpoint)
            },
        )
    }
}

@Composable
private fun GumiDiagnosticScreen(
    projection: AndroidDiagnosticProjection,
    scanState: BleProbeState,
    gattState: GattProbeState,
    firmwareState: FirmwareImageProbeState,
    firmwareDisclosure: FirmwareImageStateReadDisclosure,
    reviewedFirmwareEndpoint: EndpointCandidate?,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onInspect: (BleProbeDevice) -> Unit,
    onReviewFirmware: (BleProbeDevice) -> Unit,
    onInspectFirmware: (EndpointCandidate) -> Unit,
) {
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
                    Button(onClick = onStartScan, enabled = !scanState.scanning) {
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

                if (scanState.devices.isEmpty()) {
                    Text(if (scanState.scanning) "Waiting for an Omi advertisement…" else "No Omi observed yet.")
                } else {
                    scanState.devices.forEach { device ->
                        BleProbeCard(
                            device = device,
                            inspecting = gattState.inspecting || firmwareState.inspecting,
                            onInspect = { onInspect(device) },
                            onReviewFirmware = { onReviewFirmware(device) },
                        )
                    }
                }

                GattProbePanel(gattState)
                FirmwareImageProbePanel(
                    state = firmwareState,
                    disclosure = firmwareDisclosure,
                    reviewedEndpoint = reviewedFirmwareEndpoint,
                    onInspect = onInspectFirmware,
                )
            }

            Text("Declared capabilities", style = MaterialTheme.typography.titleMedium)
            projection.capabilities.forEach { capability -> Text("• $capability") }
        }
    }
}

@Composable
private fun BleProbeCard(
    device: BleProbeDevice,
    inspecting: Boolean,
    onInspect: () -> Unit,
    onReviewFirmware: () -> Unit,
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
            Button(onClick = onInspect, enabled = !inspecting) {
                Text(if (inspecting) "Inspecting…" else "Inspect read-only GATT")
            }
            OutlinedButton(onClick = onReviewFirmware, enabled = !inspecting) {
                Text("Review MCU image-state read")
            }
        }
    }
}

@Composable
private fun FirmwareImageProbePanel(
    state: FirmwareImageProbeState,
    disclosure: FirmwareImageStateReadDisclosure,
    reviewedEndpoint: EndpointCandidate?,
    onInspect: (EndpointCandidate) -> Unit,
) {
    if (reviewedEndpoint == null && state.inspection == null && state.error == null) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("MCU image-state inspection", style = MaterialTheme.typography.titleMedium)
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

            if (state.inspecting) {
                Text("Reading MCU image state, then disconnecting…")
            } else if (reviewedEndpoint != null) {
                Button(onClick = { onInspect(reviewedEndpoint) }) {
                    Text("Run disclosed image-state read")
                }
            }

            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            state.inspection?.let { inspection ->
                val oracle = OmiCv1StockV3012FirmwareOracle.assess(inspection)
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
                    "Published ${oracle.releaseTag} oracle: ${oracle.status}",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (oracle.findings.isEmpty()) {
                    Text("• Both active image hashes match the official release artifact")
                } else {
                    oracle.findings.forEach { finding ->
                        Text(
                            "• ${finding.code}: image ${finding.imageNumber ?: "unknown"}, " +
                                "slot ${finding.slotNumber ?: "unknown"}" +
                                (finding.observed?.let { ", observed $it" } ?: ""),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GattProbePanel(state: GattProbeState) {
    if (state.inspecting) {
        Text("Connecting once, reading the allowlist, then disconnecting…")
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
            gattState = GattProbeState(),
            firmwareState = FirmwareImageProbeState(),
            firmwareDisclosure = AndroidMcuMgrImageStateInspector.READ_DISCLOSURE,
            reviewedFirmwareEndpoint = null,
            permissionGranted = true,
            onRequestPermission = {},
            onStartScan = {},
            onStopScan = {},
            onInspect = {},
            onReviewFirmware = {},
            onInspectFirmware = {},
        )
    }
}
