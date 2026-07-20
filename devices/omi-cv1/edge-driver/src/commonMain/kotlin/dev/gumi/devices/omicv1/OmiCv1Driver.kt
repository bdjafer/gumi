package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.CapabilitySet
import dev.gumi.edge.sdk.DeviceDescriptor
import dev.gumi.edge.sdk.DeviceDriverProvider
import dev.gumi.edge.sdk.DeviceOpenException
import dev.gumi.edge.sdk.DeviceSession
import dev.gumi.edge.sdk.DeviceSessionEvent
import dev.gumi.edge.sdk.DriverId
import dev.gumi.edge.sdk.DriverMatch
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.MatchConfidence
import dev.gumi.edge.sdk.NegotiatedDeviceSession
import dev.gumi.edge.sdk.OperationResult
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.TransportSession
import dev.gumi.edge.sdk.ble.BleCharacteristicTarget
import dev.gumi.edge.sdk.ble.BleSessionEvent
import dev.gumi.edge.sdk.ble.BleSessionException
import dev.gumi.edge.sdk.ble.BleTransportSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object OmiCv1Protocol {
    const val DRIVER_ID = "gumi.device.omi-cv1"
    const val AUDIO_SERVICE_UUID = "19b10000-e8f2-537e-4f6c-d104768a1214"
    const val OFFLINE_STORAGE_SERVICE_UUID = "30295780-4301-eabd-2904-2849adfeae43"
}

class OmiCv1DriverProvider : DeviceDriverProvider {
    override val id = DriverId(OmiCv1Protocol.DRIVER_ID)

    override fun match(candidate: EndpointCandidate): DriverMatch {
        if (candidate.transport != TransportKind.BLE) return DriverMatch.None

        val normalizedServices = candidate.advertisedServiceUuids.map { it.lowercase() }.toSet()
        val matchedService = when {
            OmiCv1Protocol.AUDIO_SERVICE_UUID in normalizedServices -> OmiCv1Protocol.AUDIO_SERVICE_UUID
            OmiCv1Protocol.OFFLINE_STORAGE_SERVICE_UUID in normalizedServices ->
                OmiCv1Protocol.OFFLINE_STORAGE_SERVICE_UUID
            else -> null
        }
        if (matchedService != null) {
            return DriverMatch(
                confidence = MatchConfidence.EXACT,
                evidence = setOf("advertised Omi service UUID $matchedService"),
            )
        }

        if (candidate.advertisedName?.lowercase()?.startsWith("omi") == true) {
            return DriverMatch(
                confidence = MatchConfidence.POSSIBLE,
                evidence = setOf("advertised name starts with omi"),
            )
        }

        return DriverMatch.None
    }

    /** Match-time metadata is intentionally capability-free until a real GATT negotiation succeeds. */
    override fun describe(candidate: EndpointCandidate): DeviceDescriptor {
        require(match(candidate).confidence != MatchConfidence.NONE) {
            "Cannot describe an endpoint that does not match Omi CV1"
        }
        return DeviceDescriptor(
            driverId = id,
            manufacturer = "Based Hardware",
            model = "Omi consumer v1",
            protocolVersion = "not-negotiated",
            capabilities = emptyList(),
        )
    }

    override suspend fun open(
        candidate: EndpointCandidate,
        transport: TransportSession,
    ): DeviceSession {
        require(transport.endpoint == candidate) { "Transport belongs to a different endpoint" }
        val ble = transport as? BleTransportSession ?: throw DeviceOpenException(
            ExpectedFailure(
                category = FailureCategory.INCOMPATIBLE,
                code = FailureCode("OMI_REQUIRES_BLE_SESSION"),
                retryable = false,
            ),
        )
        return try {
            openNegotiated(candidate, ble)
        } catch (error: DeviceOpenException) {
            runCatching { ble.close() }
            throw error
        } catch (error: BleSessionException) {
            runCatching { ble.close() }
            throw DeviceOpenException(error.asExpectedFailure(), cause = error)
        } catch (error: Throwable) {
            runCatching { ble.close() }
            throw error
        }
    }

    private suspend fun openNegotiated(
        candidate: EndpointCandidate,
        ble: BleTransportSession,
    ): OmiCv1Session {
        val services = ble.discoverServices()
        val characteristics = services.flatMap { service ->
            service.characteristics.map { characteristic ->
                BleCharacteristicTarget(service.uuid, characteristic.uuid) to characteristic
            }
        }.toMap()
        val required = OmiCv1Targets.requiredForStockLiveCapabilities
        val unsatisfied = required.filter { requirement ->
            val characteristic = characteristics[requirement.target]
            characteristic == null || !requirement.isSatisfiedBy(characteristic.properties)
        }
        if (unsatisfied.isNotEmpty()) {
            throw DeviceOpenException(
                ExpectedFailure(
                    category = FailureCategory.INCOMPATIBLE,
                    code = FailureCode("OMI_REQUIRED_GATT_OPERATIONS_MISSING"),
                    retryable = false,
                    redactedEvidence = mapOf("unsatisfiedCount" to unsatisfied.size.toString()),
                ),
            )
        }

        val manufacturer = ble.readText(OmiCv1Targets.manufacturer)
        val model = ble.readText(OmiCv1Targets.model)
        val firmware = ble.readText(OmiCv1Targets.firmware)
        if (firmware !in SUPPORTED_STOCK_FIRMWARE) {
            throw DeviceOpenException(
                ExpectedFailure(
                    category = FailureCategory.INCOMPATIBLE,
                    code = FailureCode("OMI_STOCK_FIRMWARE_UNSUPPORTED"),
                    retryable = false,
                    redactedEvidence = mapOf("firmware" to firmware),
                ),
            )
        }
        val codec = ble.read(OmiCv1Targets.audioCodec).copyBytes()
        if (!codec.contentEquals(byteArrayOf(OMI_STOCK_OPUS_CODEC_ID))) {
            throw DeviceOpenException(
                ExpectedFailure(
                    category = FailureCategory.INCOMPATIBLE,
                    code = FailureCode("OMI_AUDIO_CODEC_UNSUPPORTED"),
                    retryable = false,
                    redactedEvidence = mapOf("valueLength" to codec.size.toString()),
                ),
            )
        }

        val audio = OmiStockAudioInputHandle(ble)
        val button = OmiStockButtonGestureHandle(ble, firmware)
        val haptic = OmiStockHapticHandle(ble)
        val power = OmiStockPowerStatusHandle(ble)
        val bindings = listOf(audio.binding, button.binding, haptic.binding, power.binding)
        val descriptors = bindings.map { it.descriptor }
        val capabilities = when (val negotiated = CapabilitySet.negotiate(descriptors, bindings)) {
            is OperationResult.Success -> negotiated.value
            is OperationResult.Failure -> throw DeviceOpenException(negotiated.failure)
        }
        val descriptor = DeviceDescriptor(
            driverId = id,
            manufacturer = manufacturer,
            model = model,
            protocolVersion = "omi-stock/$firmware",
            capabilities = descriptors,
        )
        return OmiCv1Session(
            endpoint = candidate,
            descriptor = descriptor,
            capabilities = capabilities,
            transport = ble,
            audio = audio,
            button = button,
            power = power,
        )
    }
}

private class OmiCv1Session(
    override val endpoint: EndpointCandidate,
    override val descriptor: DeviceDescriptor,
    override val capabilities: CapabilitySet,
    private val transport: BleTransportSession,
    private val audio: OmiStockAudioInputHandle,
    private val button: OmiStockButtonGestureHandle,
    private val power: OmiStockPowerStatusHandle,
) : NegotiatedDeviceSession {
    override val deviceId = null
    override val events: Flow<DeviceSessionEvent> = transport.bleEvents.map { event ->
        when (event) {
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
    }

    private val closeLock = kotlinx.coroutines.sync.Mutex()
    private var closed = false

    override suspend fun close() = closeLock.withLock {
        if (!closed) {
            closed = true
            withContext(NonCancellable) {
                try {
                    audio.closeAll()
                } finally {
                    transport.close()
                }
            }
        }
    }
}

private suspend fun BleTransportSession.readText(target: BleCharacteristicTarget): String =
    read(target).copyBytes().decodeToString().trimEnd('\u0000').takeIf(String::isNotBlank)
        ?: throw DeviceOpenException(
            ExpectedFailure(
                category = FailureCategory.CORRUPT,
                code = FailureCode("OMI_DEVICE_INFO_EMPTY"),
                retryable = false,
            ),
        )

private fun BleSessionException.asExpectedFailure(): ExpectedFailure = ExpectedFailure(
    category = when (code) {
        dev.gumi.edge.sdk.ble.BleSessionFailureCode.PERMISSION_DENIED -> FailureCategory.PERMISSION
        dev.gumi.edge.sdk.ble.BleSessionFailureCode.TIMEOUT -> FailureCategory.TIMEOUT
        dev.gumi.edge.sdk.ble.BleSessionFailureCode.DISCONNECTED,
        dev.gumi.edge.sdk.ble.BleSessionFailureCode.CLOSED,
        -> FailureCategory.DISCONNECTED
        dev.gumi.edge.sdk.ble.BleSessionFailureCode.ATTRIBUTE_MISSING,
        dev.gumi.edge.sdk.ble.BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
        -> FailureCategory.INCOMPATIBLE
        dev.gumi.edge.sdk.ble.BleSessionFailureCode.EVENT_OVERFLOW -> FailureCategory.RESOURCE_EXHAUSTED
        else -> FailureCategory.UNAVAILABLE
    },
    code = FailureCode("OMI_BLE_${code.name}"),
    retryable = code !in setOf(
        dev.gumi.edge.sdk.ble.BleSessionFailureCode.ATTRIBUTE_MISSING,
        dev.gumi.edge.sdk.ble.BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
    ),
)

private val SUPPORTED_STOCK_FIRMWARE = setOf("3.0.12", "3.0.20")
private const val OMI_STOCK_OPUS_CODEC_ID: Byte = 21
