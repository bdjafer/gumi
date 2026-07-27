package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.CapabilityBinding
import dev.gumi.edge.sdk.CapabilitySet
import dev.gumi.edge.sdk.DeviceDescriptor
import dev.gumi.edge.sdk.DeviceSessionEvent
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.NegotiatedDeviceSession
import dev.gumi.edge.sdk.OperationResult
import dev.gumi.edge.sdk.ble.BleCharacteristicTarget
import dev.gumi.edge.sdk.ble.BleGattCharacteristicProperty
import dev.gumi.edge.sdk.ble.BleSessionEvent
import dev.gumi.edge.sdk.ble.BleTransportSession
import dev.gumi.edge.sdk.capability.capture.CaptureStateDescriptor
import dev.gumi.edge.sdk.capability.capture.CaptureStateHandle
import dev.gumi.edge.sdk.capability.capture.CaptureStateV1
import dev.gumi.edge.sdk.capability.capture.DeviceCaptureState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object OmiCv1FunctionalTargets {
    val status = BleCharacteristicTarget(
        OmiCv1FunctionalGattV1.SERVICE_UUID,
        OmiCv1FunctionalGattV1.STATUS_CHARACTERISTIC_UUID,
    )
    val capabilities = BleCharacteristicTarget(
        OmiCv1FunctionalGattV1.SERVICE_UUID,
        OmiCv1FunctionalGattV1.CAPABILITIES_CHARACTERISTIC_UUID,
    )
    val software = BleCharacteristicTarget(
        OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
        OmiCv1GattProfile.SOFTWARE_REVISION,
    )

    val required = listOf(
        OmiGattRequirement(
            OmiCv1Targets.manufacturer,
            allOf = setOf(BleGattCharacteristicProperty.READ),
        ),
        OmiGattRequirement(
            OmiCv1Targets.model,
            allOf = setOf(BleGattCharacteristicProperty.READ),
        ),
        OmiGattRequirement(
            OmiCv1Targets.firmware,
            allOf = setOf(BleGattCharacteristicProperty.READ),
        ),
        OmiGattRequirement(
            software,
            allOf = setOf(BleGattCharacteristicProperty.READ),
        ),
        OmiGattRequirement(
            status,
            allOf = setOf(BleGattCharacteristicProperty.READ),
            anyOf = setOf(
                BleGattCharacteristicProperty.NOTIFY,
                BleGattCharacteristicProperty.INDICATE,
            ),
        ),
        OmiGattRequirement(
            capabilities,
            allOf = setOf(BleGattCharacteristicProperty.READ),
        ),
    )
}

internal class OmiFunctionalCaptureStateHandle(
    private val transport: BleTransportSession,
    private val decoder: (ByteArray) -> OmiCv1FunctionalStatusEvidence,
) : CaptureStateHandle {
    override val descriptor = CaptureStateDescriptor(
        localRecording = true,
        readOnly = true,
        liveMedia = false,
        mediaExport = false,
        semanticSignals = false,
    )
    val binding = CapabilityBinding(CaptureStateV1, descriptor, this)

    override suspend fun read(): DeviceCaptureState = decoder(
        transport.read(OmiCv1FunctionalTargets.status).copyBytes(),
    ).toDeviceCaptureState(observedAtMonotonicMillis = null)

    override val updates: Flow<DeviceCaptureState> = flow {
        val subscription = transport.subscribe(OmiCv1FunctionalTargets.status)
        try {
            subscription.notifications.collect { notification ->
                emit(
                    decoder(notification.value.copyBytes()).toDeviceCaptureState(
                        observedAtMonotonicMillis = notification.receivedAtMonotonicMillis,
                    ),
                )
            }
        } finally {
            withContext(NonCancellable) { subscription.close() }
        }
    }
}

internal class OmiCv1FunctionalSession(
    override val endpoint: EndpointCandidate,
    override val descriptor: DeviceDescriptor,
    override val capabilities: CapabilitySet,
    private val transport: BleTransportSession,
) : NegotiatedDeviceSession {
    override val deviceId = null
    override val events: Flow<DeviceSessionEvent> = transport.bleEvents.map(::mapOmiSessionEvent)

    private val closeLock = Mutex()
    private var closed = false

    override suspend fun close() = closeLock.withLock {
        if (!closed) {
            closed = true
            withContext(NonCancellable) { transport.close() }
        }
    }
}

internal fun buildFunctionalCapabilitySet(
    handle: OmiFunctionalCaptureStateHandle,
): CapabilitySet = when (
    val negotiated = CapabilitySet.negotiate(
        advertised = listOf(handle.descriptor),
        bindings = listOf(handle.binding),
    )
) {
    is OperationResult.Success -> negotiated.value
    is OperationResult.Failure -> error(
        "Internally inconsistent functional Omi capability binding: ${negotiated.failure.code}",
    )
}

internal fun mapOmiSessionEvent(event: BleSessionEvent): DeviceSessionEvent = when (event) {
    BleSessionEvent.Closed -> DeviceSessionEvent.Closed
    BleSessionEvent.Disconnected -> DeviceSessionEvent.Diagnostic(
        code = "BLE_DISCONNECTED",
        detail = "The Omi transport disconnected",
    )
    is BleSessionEvent.Fault -> DeviceSessionEvent.Diagnostic(event.code.name, event.detail)
    is BleSessionEvent.LinkChanged -> DeviceSessionEvent.Diagnostic(
        code = "BLE_LINK_CHANGED",
        detail = "The Omi BLE link parameters changed",
    )
    is BleSessionEvent.NotificationsDropped -> DeviceSessionEvent.Diagnostic(
        code = "BLE_NOTIFICATIONS_DROPPED",
        detail = "A bounded Omi notification stream overflowed (${event.count})",
    )
}

internal val SUPPORTED_FUNCTIONAL_SOFTWARE = setOf(
    "gumi-functional-recording-0001",
    "gumi-functional-recording-0002",
    "gumi-functional-recording-0003",
    "gumi-functional-recording-0004",
    "gumi-functional-recording-0005",
)
