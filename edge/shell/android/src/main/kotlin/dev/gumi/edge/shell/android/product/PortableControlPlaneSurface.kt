package dev.gumi.edge.shell.android.product

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.shell.application.AxisObservation
import dev.gumi.edge.shell.application.CapturePresentationKind
import dev.gumi.edge.shell.application.GumiDarkShellPalette
import dev.gumi.edge.shell.application.MaintenanceState
import dev.gumi.edge.shell.application.ObservationFreshness
import dev.gumi.edge.shell.application.PortableControlPlanePresentation
import dev.gumi.edge.shell.application.PowerStatus
import dev.gumi.edge.shell.application.ProjectionAuthority
import dev.gumi.edge.shell.application.ShellActionAvailability
import dev.gumi.edge.shell.application.ShellControlAction
import dev.gumi.edge.shell.application.ShellLiveAnnouncement
import dev.gumi.edge.shell.application.ShellProductDevicePresentation
import dev.gumi.edge.shell.application.ShellSemanticTone
import dev.gumi.edge.shell.application.ShellVisualProjector
import dev.gumi.edge.shell.application.StorageStatus
import dev.gumi.edge.shell.application.SyncState
import dev.gumi.edge.shell.application.SyncStatus
import dev.gumi.edge.shell.application.UpdateStage
import dev.gumi.edge.shell.application.UpdateStatus
import java.time.Instant

/**
 * Android product renderer for the host-neutral control-plane projection.
 *
 * The caller owns collection and effect composition. This surface neither constructs commands nor
 * interprets transport/device data; callbacks identify only the selected stable device and the
 * already-projected semantic action. The runtime must revalidate every callback before an effect.
 */
@Composable
fun PortableControlPlaneSurface(
    presentation: PortableControlPlanePresentation,
    onSelectDevice: (DeviceId) -> Unit,
    onAction: (DeviceId, ShellControlAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    GumiProductTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            PortableControlPlaneContent(
                presentation = presentation,
                onSelectDevice = onSelectDevice,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun PortableControlPlaneContent(
    presentation: PortableControlPlanePresentation,
    onSelectDevice: (DeviceId) -> Unit,
    onAction: (DeviceId, ShellControlAction) -> Unit,
) {
    val selectedDevice = presentation.devices.singleOrNull { it.selected }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("control-plane-scroll"),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            ProductHeader()
        }
        item {
            FleetWorkflowCard(presentation)
        }
        if (presentation.devices.isEmpty()) {
            item {
                EmptyFleetCard()
            }
        } else {
            item {
                SectionHeading(
                    title = "Managed devices",
                    detail = "Focus changes what is shown. It does not grant ownership or control authority.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            item {
                DeviceSelector(
                    devices = presentation.devices,
                    onSelectDevice = onSelectDevice,
                )
            }
        }
        selectedDevice?.let { device ->
            item {
                DeviceTruthPanel(device)
            }
            item {
                CaptureControls(
                    device = device,
                    onAction = onAction,
                )
            }
            item {
                DeviceWorkflowControls(
                    device = device,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun ProductHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Gumi",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "Physical-world control plane",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FleetWorkflowCard(presentation: PortableControlPlanePresentation) {
    val workflow = presentation.workflow
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .testTag("fleet-workflow")
            .semanticStatus(
                accessibilityLabel = workflow.label,
                announcement = workflow.liveAnnouncement,
            ),
        colors = CardDefaults.cardColors(
            containerColor = toneColor(workflow.tone).copy(alpha = 0.13f),
        ),
        border = BorderStroke(1.dp, toneColor(workflow.tone).copy(alpha = 0.58f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Fleet microphone safety",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            SemanticStatusLine(
                label = workflow.label,
                iconKey = workflow.iconKey,
                tone = workflow.tone,
            )
            Text(
                text = fleetScopeSummary(
                    deviceCount = presentation.devices.size,
                    activeCount = workflow.activeDeviceIds.size,
                    uncertainCount = workflow.uncertainDeviceIds.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (workflow.admissionReservation != null) {
                Text(
                    text = "One capture admission is reserved. This is a request boundary, not microphone evidence.",
                    style = MaterialTheme.typography.bodySmall,
                    color = toneColor(ShellSemanticTone.PRIVACY_UNCERTAIN),
                )
            }
        }
    }
}

@Composable
private fun EmptyFleetCard() {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .fillMaxWidth()
            .testTag("empty-fleet"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("No managed device", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add or restore a provisioned device before capture controls can be shown.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceSelector(
    devices: List<ShellProductDevicePresentation>,
    onSelectDevice: (DeviceId) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("managed-device-list"),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items = devices) { index, device ->
            val projection = device.control.device
            Card(
                modifier = Modifier
                    .widthIn(min = 216.dp, max = 280.dp)
                    .heightIn(min = 112.dp)
                    .testTag("managed-device-$index")
                    .semantics {
                        selected = device.selected
                        role = Role.Tab
                        stateDescription = if (device.selected) "Selected device" else "Not selected"
                    }
                    .clickable { onSelectDevice(projection.deviceId) },
                colors = CardDefaults.cardColors(
                    containerColor = if (device.selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
                border = BorderStroke(
                    width = if (device.selected) 2.dp else 1.dp,
                    color = if (device.selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = projection.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = device.attachment.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = toneColor(device.attachment.tone),
                    )
                    Text(
                        text = device.control.captureVisual.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = toneColor(device.control.captureVisual.tone),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (device.captureAdmissionReserved) {
                        Text(
                            text = "Capture request reserved",
                            style = MaterialTheme.typography.labelSmall,
                            color = toneColor(ShellSemanticTone.PRIVACY_UNCERTAIN),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceTruthPanel(device: ShellProductDevicePresentation) {
    val projection = device.control.device
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeading(
            title = projection.displayName,
            detail = "Independent current-state axes from the portable projection",
        )
        TruthCard(
            title = "Microphone",
            value = device.control.captureVisual.label,
            supporting = captureSupportingText(device),
            iconKey = device.control.captureVisual.iconKey,
            tone = device.control.captureVisual.tone,
            announcement = device.control.captureVisual.liveAnnouncement,
            testTag = "capture-truth",
        )
        TruthCard(
            title = "Physical privacy output",
            value = device.physicalOutput.label,
            supporting = "A reported semantic output only; device-specific light colors are not shell truth.",
            iconKey = device.physicalOutput.iconKey,
            tone = device.physicalOutput.tone,
            announcement = device.physicalOutput.liveAnnouncement,
            accessibilityLabel = device.physicalOutput.accessibilityLabel,
            testTag = "physical-output-truth",
        )
        TruthCard(
            title = "Connection",
            value = device.attachment.label,
            supporting = evidenceSummary(projection.link),
            iconKey = device.attachment.iconKey,
            tone = device.attachment.tone,
            announcement = attachmentAnnouncement(device.attachment.tone),
            testTag = "attachment-truth",
        )
        TruthCard(
            title = "Fault",
            value = device.fault.label,
            supporting = buildString {
                append(evidenceSummary(projection.fault))
                device.fault.failure?.let {
                    append(" • ")
                    append(it.code.value)
                    append(if (it.retryable) " • retryable" else " • not retryable")
                }
            },
            iconKey = device.fault.iconKey,
            tone = device.fault.tone,
            announcement = device.fault.liveAnnouncement,
            testTag = "fault-truth",
        )
        AxisGroup(device)
    }
}

@Composable
private fun AxisGroup(device: ShellProductDevicePresentation) {
    val projection = device.control.device
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            AxisRow(
                label = "Requested capture",
                value = requestedCaptureSummary(device),
                supporting = "A request is not evidence that hardware acquired or released the microphone.",
                testTag = "requested-capture",
            )
            AxisDivider()
            AxisRow(
                label = "Local storage",
                value = storageSummary(projection.storage),
                supporting = evidenceSummary(projection.storage),
                testTag = "storage-truth",
            )
            AxisDivider()
            AxisRow(
                label = "Transfer and backlog",
                value = syncSummary(projection.sync),
                supporting = evidenceSummary(projection.sync),
                testTag = "backlog-truth",
            )
            AxisDivider()
            AxisRow(
                label = "Power",
                value = powerSummary(projection.power),
                supporting = evidenceSummary(projection.power),
                testTag = "power-truth",
            )
            AxisDivider()
            AxisRow(
                label = "Maintenance",
                value = maintenanceSummary(projection.maintenance),
                supporting = evidenceSummary(projection.maintenance),
                testTag = "maintenance-truth",
            )
            AxisDivider()
            AxisRow(
                label = "Software update",
                value = updateSummary(projection.update),
                supporting = evidenceSummary(projection.update),
                testTag = "update-truth",
            )
        }
    }
}

@Composable
private fun CaptureControls(
    device: ShellProductDevicePresentation,
    onAction: (DeviceId, ShellControlAction) -> Unit,
) {
    val deviceId = device.control.device.deviceId
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeading(
            title = "Capture controls",
            detail = "Controls submit requests. Fresh device evidence remains the only capture truth.",
        )
        ProductActionButton(
            action = ShellControlAction.START_RECORDING,
            availability = device.control.actions.getValue(ShellControlAction.START_RECORDING),
            onClick = { onAction(deviceId, ShellControlAction.START_RECORDING) },
            primary = true,
        )
        ProductActionButton(
            action = ShellControlAction.STOP_CAPTURE,
            availability = device.control.actions.getValue(ShellControlAction.STOP_CAPTURE),
            onClick = { onAction(deviceId, ShellControlAction.STOP_CAPTURE) },
            primary = true,
        )
        ProductActionButton(
            action = ShellControlAction.START_VOICE_TURN,
            availability = device.control.actions.getValue(ShellControlAction.START_VOICE_TURN),
            onClick = { onAction(deviceId, ShellControlAction.START_VOICE_TURN) },
            primary = true,
        )
        ProductActionButton(
            action = ShellControlAction.STOP_VOICE_TURN,
            availability = device.control.actions.getValue(ShellControlAction.STOP_VOICE_TURN),
            onClick = { onAction(deviceId, ShellControlAction.STOP_VOICE_TURN) },
            primary = true,
        )
    }
}

@Composable
private fun DeviceWorkflowControls(
    device: ShellProductDevicePresentation,
    onAction: (DeviceId, ShellControlAction) -> Unit,
) {
    val deviceId = device.control.device.deviceId
    val actions = listOf(
        ShellControlAction.REPEAT_STATUS,
        ShellControlAction.BEGIN_PAIRING,
        ShellControlAction.PREPARE_UPDATE,
        ShellControlAction.CONFIRM_PHYSICAL_ACTION,
        ShellControlAction.REQUEST_SHUTDOWN,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeading(
            title = "Device workflows",
            detail = "The host adapter opens any required review or confirmation before dispatch.",
        )
        actions.forEach { action ->
            ProductActionButton(
                action = action,
                availability = device.control.actions.getValue(action),
                onClick = { onAction(deviceId, action) },
                primary = false,
            )
        }
    }
}

@Composable
private fun ProductActionButton(
    action: ShellControlAction,
    availability: ShellActionAvailability,
    onClick: () -> Unit,
    primary: Boolean,
) {
    val blockedReason = availability.blockedReasonCode?.let(::blockedReasonLabel)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val buttonModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("action-${action.name.lowercase()}")
            .semantics {
                contentDescription = actionLabel(action)
                stateDescription = if (availability.enabled) {
                    "Available. Runtime revalidation required."
                } else {
                    "Unavailable. ${requireNotNull(blockedReason)}"
                }
            }
        if (primary) {
            Button(
                onClick = onClick,
                enabled = availability.enabled,
                modifier = buttonModifier,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (action) {
                        ShellControlAction.STOP_CAPTURE,
                        ShellControlAction.STOP_VOICE_TURN,
                        -> toneColor(ShellSemanticTone.PRIVACY_UNCERTAIN)

                        else -> MaterialTheme.colorScheme.primary
                    },
                ),
            ) {
                Text(actionLabel(action))
            }
        } else {
            OutlinedButton(
                onClick = onClick,
                enabled = availability.enabled,
                modifier = buttonModifier,
            ) {
                Text(actionLabel(action))
            }
        }
        if (blockedReason != null) {
            Text(
                text = "Unavailable: $blockedReason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .testTag("blocked-${action.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun TruthCard(
    title: String,
    value: String,
    supporting: String,
    iconKey: String,
    tone: ShellSemanticTone,
    announcement: ShellLiveAnnouncement,
    testTag: String,
    accessibilityLabel: String = value,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semanticStatus(accessibilityLabel, announcement),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, toneColor(tone).copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatusGlyph(iconKey = iconKey, tone = tone)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = toneColor(tone),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SemanticStatusLine(
    label: String,
    iconKey: String,
    tone: ShellSemanticTone,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusGlyph(iconKey, tone)
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = toneColor(tone),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusGlyph(iconKey: String, tone: ShellSemanticTone) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(toneColor(tone).copy(alpha = 0.18f))
            .semantics { contentDescription = iconMeaning(iconKey) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = iconGlyph(iconKey),
            color = toneColor(tone),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun AxisRow(
    label: String,
    value: String,
    supporting: String,
    testTag: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AxisDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
}

@Composable
private fun SectionHeading(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GumiProductTheme(content: @Composable () -> Unit) {
    val palette = GumiDarkShellPalette
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = palette.information.toComposeColor(),
            onPrimary = palette.background.toComposeColor(),
            primaryContainer = palette.information.toComposeColor().copy(alpha = 0.18f),
            onPrimaryContainer = palette.content.toComposeColor(),
            background = palette.background.toComposeColor(),
            onBackground = palette.content.toComposeColor(),
            surface = palette.surface.toComposeColor(),
            onSurface = palette.content.toComposeColor(),
            onSurfaceVariant = palette.muted.toComposeColor(),
            outline = palette.muted.toComposeColor().copy(alpha = 0.48f),
            error = palette.privacyActive.toComposeColor(),
        ),
        content = content,
    )
}

private fun Modifier.semanticStatus(
    accessibilityLabel: String,
    announcement: ShellLiveAnnouncement,
): Modifier = semantics {
    stateDescription = accessibilityLabel
    when (announcement) {
        ShellLiveAnnouncement.NONE -> Unit
        ShellLiveAnnouncement.POLITE -> liveRegion = LiveRegionMode.Polite
        ShellLiveAnnouncement.ASSERTIVE -> liveRegion = LiveRegionMode.Assertive
    }
}

private fun toneColor(tone: ShellSemanticTone): Color =
    ShellVisualProjector.colorFor(tone).toComposeColor()

private fun dev.gumi.edge.shell.application.ShellArgb.toComposeColor(): Color =
    Color(value.toLong())

internal fun fleetScopeSummary(
    deviceCount: Int,
    activeCount: Int,
    uncertainCount: Int,
): String = buildString {
    append("Managed devices: ")
    append(deviceCount)
    append(" • confirmed active: ")
    append(activeCount)
    append(" • uncertain: ")
    append(uncertainCount)
}

internal fun <T> evidenceSummary(observation: AxisObservation<T>): String =
    "${freshnessLabel(observation.freshness)} • ${authorityLabel(observation.authority)}"

internal fun storageSummary(observation: AxisObservation<StorageStatus>): String =
    when (observation.freshness) {
        ObservationFreshness.UNAVAILABLE -> "Current storage status unavailable"
        ObservationFreshness.STALE -> "Storage report stale — current capacity unknown"
        ObservationFreshness.FRESH -> buildString {
            val availableBytes = observation.value.availableBytes
            val capacityBytes = observation.value.capacityBytes
            append(enumLabel(observation.value.state.name))
            when {
                availableBytes != null && capacityBytes != null -> {
                    append(" • ")
                    append(formatBytes(availableBytes))
                    append(" available of ")
                    append(formatBytes(capacityBytes))
                }

                availableBytes != null -> {
                    append(" • ")
                    append(formatBytes(availableBytes))
                    append(" available")
                }
            }
        }
    }

internal fun syncSummary(observation: AxisObservation<SyncStatus>): String =
    when (observation.freshness) {
        ObservationFreshness.UNAVAILABLE -> "Current transfer status and backlog unavailable"
        ObservationFreshness.STALE -> "Transfer report stale — current backlog unavailable"
        ObservationFreshness.FRESH -> buildString {
            append(syncStateLabel(observation.value.state))
            append(" • ")
            append(backlogSummary(observation.value))
        }
    }

internal fun powerSummary(observation: AxisObservation<PowerStatus>): String =
    when (observation.freshness) {
        ObservationFreshness.UNAVAILABLE -> "Current power status unavailable"
        ObservationFreshness.STALE -> "Power report stale — current level unknown"
        ObservationFreshness.FRESH -> buildString {
            append(enumLabel(observation.value.state.name))
            observation.value.batteryPercent?.let {
                append(" • ")
                append(it)
                append('%')
            }
            observation.value.charging?.let {
                append(if (it) " • charging" else " • not charging")
            }
            if (observation.value.batteryPercent == null) {
                append(" • ")
                append(enumLabel(observation.value.level.name))
                append(" level")
            }
        }
    }

internal fun maintenanceSummary(observation: AxisObservation<MaintenanceState>): String =
    when (observation.freshness) {
        ObservationFreshness.UNAVAILABLE -> "Current maintenance status unavailable"
        ObservationFreshness.STALE -> "Maintenance report stale — current state unknown"
        ObservationFreshness.FRESH -> enumLabel(observation.value.name)
    }

internal fun updateSummary(observation: AxisObservation<UpdateStatus>): String =
    when (observation.freshness) {
        ObservationFreshness.UNAVAILABLE -> "Current software-update status unavailable"
        ObservationFreshness.STALE -> "Software-update report stale — current stage unknown"
        ObservationFreshness.FRESH -> buildString {
            append(updateStageLabel(observation.value.stage))
            observation.value.progressPercent?.let {
                append(" • ")
                append(it)
                append('%')
            }
        }
    }

private fun captureSupportingText(device: ShellProductDevicePresentation): String = buildString {
    append(evidenceSummary(device.control.device.capture))
    append(" • ")
    append(
        when (device.control.device.capture.value.kind) {
            CapturePresentationKind.VERIFIED_OFF -> "Device-confirmed release"
            CapturePresentationKind.STARTING -> "Acquisition not yet confirmed"
            CapturePresentationKind.RECORDING -> "Recording acquisition confirmed"
            CapturePresentationKind.RECORDING_STARTING_VOICE_TURN ->
                "Recording remains active; voice-turn acquisition pending"

            CapturePresentationKind.VOICE_TURN -> "Voice-turn acquisition confirmed"
            CapturePresentationKind.RECORDING_WITH_VOICE_TURN ->
                "Recording and voice turn are both active"

            CapturePresentationKind.RECORDING_ENDING_VOICE_TURN ->
                "Recording remains active; voice-turn release pending"

            CapturePresentationKind.STOPPING -> "Release not yet confirmed"
            CapturePresentationKind.MAY_BE_RECORDING -> "Treat the microphone as active"
            CapturePresentationKind.UNKNOWN -> "Treat the microphone as active until reconciled"
        },
    )
}

private fun requestedCaptureSummary(device: ShellProductDevicePresentation): String {
    val capture = device.control.device.capture.value
    val requested = capture.requestedMode ?: return "No capture transition requested"
    return "Requested: ${enumLabel(requested.name)} — awaiting authoritative result"
}

private fun backlogSummary(status: SyncStatus): String {
    val backlog = status.backlog
    val pendingItems = backlog.pendingItems
    val pendingBytes = backlog.pendingBytes
    val amount = when {
        pendingItems != null && pendingBytes != null ->
            "$pendingItems queued item(s), ${formatBytes(pendingBytes)}"

        pendingItems != null -> "$pendingItems queued item(s); byte count unavailable"
        pendingBytes != null -> "item count unavailable; ${formatBytes(pendingBytes)} queued"
        else -> "backlog amount unavailable"
    }
    val oldest = backlog.oldestItemAtEpochMillis ?: return amount
    return "$amount; oldest queued item: ${Instant.ofEpochMilli(oldest)}"
}

private fun syncStateLabel(state: SyncState): String = when (state) {
    SyncState.CURRENT -> "Transfer current"
    SyncState.UPLOADING -> "Uploading durable backlog"
    SyncState.CLOUD_OFFLINE_SAVED_LOCALLY -> "Cloud offline — saved locally"
    SyncState.BLOCKED -> "Transfer blocked"
    SyncState.UNKNOWN -> "Transfer state unknown"
}

private fun updateStageLabel(stage: UpdateStage): String = when (stage) {
    UpdateStage.IDLE -> "No update active"
    else -> enumLabel(stage.name)
}

private fun freshnessLabel(freshness: ObservationFreshness): String = when (freshness) {
    ObservationFreshness.FRESH -> "Fresh observation"
    ObservationFreshness.STALE -> "Stale observation"
    ObservationFreshness.UNAVAILABLE -> "Observation unavailable"
}

private fun authorityLabel(authority: ProjectionAuthority): String = when (authority) {
    ProjectionAuthority.DEVICE_REPORTED -> "device reported"
    ProjectionAuthority.EDGE_INFERRED -> "edge inferred"
    ProjectionAuthority.CLOUD_REPORTED -> "cloud reported"
}

internal fun actionLabel(action: ShellControlAction): String = when (action) {
    ShellControlAction.REPEAT_STATUS -> "Repeat device status"
    ShellControlAction.START_RECORDING -> "Start recording"
    ShellControlAction.STOP_CAPTURE -> "Stop capture"
    ShellControlAction.START_VOICE_TURN -> "Start voice turn"
    ShellControlAction.STOP_VOICE_TURN -> "End voice turn"
    ShellControlAction.BEGIN_PAIRING -> "Begin pairing"
    ShellControlAction.PREPARE_UPDATE -> "Prepare software update"
    ShellControlAction.CONFIRM_PHYSICAL_ACTION -> "Confirm physical action"
    ShellControlAction.REQUEST_SHUTDOWN -> "Request safe shutdown"
}

internal fun blockedReasonLabel(code: String): String = when (code) {
    "CAPTURE_ADMISSION_RESERVED" -> "another capture request owns the fleet admission ($code)"
    "CAPTURE_ALREADY_VERIFIED_OFF" -> "the microphone is already device-confirmed off ($code)"
    "CAPTURE_TRUTH_NOT_ACTIONABLE" -> "capture truth is not fresh enough for this action ($code)"
    "CAPTURE_TRUTH_NOT_VERIFIED_OFF" -> "the microphone is not freshly device-confirmed off ($code)"
    "COMMAND_ALREADY_PENDING" -> "another command is awaiting a result ($code)"
    "DEVICE_LINK_NOT_READY" -> "the device link is not freshly ready ($code)"
    "FATAL_PRIVACY_FAULT" -> "a critical privacy fault blocks acquisition ($code)"
    "FLEET_CAPTURE_NOT_QUIESCENT" -> "another microphone is active or uncertain ($code)"
    "LOCAL_STORAGE_NOT_HEALTHY" -> "qualified local durable storage is unavailable ($code)"
    "MAINTENANCE_EXCLUDES_CAPTURE" -> "the maintenance state excludes capture ($code)"
    "MAINTENANCE_NOT_NORMAL" -> "the device is not in normal maintenance state ($code)"
    "NO_PHYSICAL_CONFIRMATION_PENDING" -> "no physical confirmation is pending ($code)"
    "PHYSICAL_OUTPUT_NOT_TRUSTWORTHY" -> "physical privacy output is failed or contradictory ($code)"
    "POWER_POLICY_NOT_SATISFIED" -> "the qualified power policy is not satisfied ($code)"
    "POWER_STATE_NOT_FRESH" -> "the current power state is unavailable ($code)"
    "SHUTDOWN_CURRENTLY_UNSAFE" -> "the current lifecycle state makes shutdown unsafe ($code)"
    "UPDATE_ALREADY_ACTIVE" -> "a software update is already active ($code)"
    "UPDATE_STATE_NOT_FRESH" -> "the current software-update state is unavailable ($code)"
    "VOICE_TURN_ALREADY_ACTIVE" -> "a voice turn is already active or transitioning ($code)"
    "VOICE_TURN_NOT_ACTIVE" -> "there is no active voice turn to end ($code)"
    else -> "the portable control plane blocked this action ($code)"
}

private fun attachmentAnnouncement(tone: ShellSemanticTone): ShellLiveAnnouncement = when (tone) {
    ShellSemanticTone.PRIVACY_UNCERTAIN -> ShellLiveAnnouncement.ASSERTIVE
    ShellSemanticTone.INFORMATION -> ShellLiveAnnouncement.POLITE
    else -> ShellLiveAnnouncement.NONE
}

private fun iconGlyph(iconKey: String): String = when (iconKey) {
    "microphones-off-verified", "microphone-off-verified", "fault-clear" -> "✓"
    "microphone-active", "physical-privacy-active" -> "●"
    "microphone-starting", "device-connecting", "device-authenticating" -> "…"
    "microphone-uncertain", "microphones-collision-risk", "privacy-fault-critical",
    "physical-output-contradiction", "physical-output-failed", "fault-warning",
    "fault-recoverable", "fault-unknown", "device-connection-degraded",
    "physical-privacy-unknown", -> "!"

    "device-connected" -> "↔"
    "device-disconnected" -> "×"
    "physical-output-inactive", "devices-none" -> "○"
    "physical-status-output" -> "i"
    "physical-output-unverified" -> "?"
    else -> "•"
}

private fun iconMeaning(iconKey: String): String = when (iconKey) {
    "microphones-off-verified", "microphone-off-verified" -> "Verified microphone off icon"
    "microphone-active" -> "Microphone active icon"
    "microphone-starting" -> "Microphone transition icon"
    "microphone-uncertain", "microphones-collision-risk" -> "Microphone warning icon"
    "device-connected" -> "Connected device icon"
    "device-connecting", "device-authenticating" -> "Connecting device icon"
    "device-disconnected" -> "Disconnected device icon"
    "device-connection-degraded" -> "Degraded connection icon"
    "physical-privacy-active" -> "Physical privacy output active icon"
    "physical-privacy-unknown" -> "Physical privacy output warning icon"
    "physical-output-inactive" -> "Physical output inactive icon"
    "physical-output-failed", "physical-output-contradiction" -> "Physical output fault icon"
    "physical-output-unverified" -> "Physical output unverified icon"
    "fault-clear" -> "No fault icon"
    "fault-warning", "fault-recoverable", "fault-unknown", "privacy-fault-critical" -> "Fault warning icon"
    "devices-none" -> "No managed device icon"
    else -> "Status icon"
}

private fun enumLabel(value: String): String = value
    .lowercase()
    .split('_')
    .joinToString(" ")
    .replaceFirstChar { it.titlecase() }

private fun formatBytes(bytes: ULong): String {
    val grouped = bytes
        .toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()
    return "$grouped bytes"
}
