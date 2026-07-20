package dev.gumi.devices.omicv1

import dev.gumi.edge.sdk.CapabilityBinding
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.OperationResult
import dev.gumi.edge.sdk.ble.BleCharacteristicTarget
import dev.gumi.edge.sdk.ble.BleGattCharacteristicProperty
import dev.gumi.edge.sdk.ble.BleSessionException
import dev.gumi.edge.sdk.ble.BleSubscription
import dev.gumi.edge.sdk.ble.BleTransportSession
import dev.gumi.edge.sdk.ble.BleWriteKind
import dev.gumi.edge.sdk.capability.audio.AudioCodec
import dev.gumi.edge.sdk.capability.audio.AudioFormat
import dev.gumi.edge.sdk.capability.audio.AudioFrame
import dev.gumi.edge.sdk.capability.audio.AudioInputDescriptor
import dev.gumi.edge.sdk.capability.audio.AudioInputHandle
import dev.gumi.edge.sdk.capability.audio.AudioInputV1
import dev.gumi.edge.sdk.capability.audio.AudioPayloadFraming
import dev.gumi.edge.sdk.capability.audio.AudioStream
import dev.gumi.edge.sdk.capability.audio.AudioStreamException
import dev.gumi.edge.sdk.capability.button.ButtonGestureDescriptor
import dev.gumi.edge.sdk.capability.button.ButtonGestureEvent
import dev.gumi.edge.sdk.capability.button.ButtonGestureHandle
import dev.gumi.edge.sdk.capability.button.ButtonGestureKind
import dev.gumi.edge.sdk.capability.button.ButtonGestureV1
import dev.gumi.edge.sdk.capability.haptic.HapticDescriptor
import dev.gumi.edge.sdk.capability.haptic.HapticHandle
import dev.gumi.edge.sdk.capability.haptic.HapticPatternId
import dev.gumi.edge.sdk.capability.haptic.HapticV1
import dev.gumi.edge.sdk.capability.power.PowerStatus
import dev.gumi.edge.sdk.capability.power.PowerStatusDescriptor
import dev.gumi.edge.sdk.capability.power.PowerStatusHandle
import dev.gumi.edge.sdk.capability.power.PowerStatusV1
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object OmiCv1Targets {
    val manufacturer = target(OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE, OmiCv1GattProfile.MANUFACTURER_NAME)
    val model = target(OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE, OmiCv1GattProfile.MODEL_NUMBER)
    val firmware = target(OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE, OmiCv1GattProfile.FIRMWARE_REVISION)
    val battery = target(OmiCv1GattProfile.BATTERY_SERVICE, OmiCv1GattProfile.BATTERY_LEVEL)
    val audio = target(OmiCv1Protocol.AUDIO_SERVICE_UUID, "19b10001-e8f2-537e-4f6c-d104768a1214")
    val audioCodec = target(OmiCv1Protocol.AUDIO_SERVICE_UUID, "19b10002-e8f2-537e-4f6c-d104768a1214")
    val button = target("23ba7924-0000-1000-7450-346eac492e92", "23ba7925-0000-1000-7450-346eac492e92")
    val haptic = target("cab1ab95-2ea5-4f4d-bb56-874b72cfc984", "cab1ab96-2ea5-4f4d-bb56-874b72cfc984")

    val requiredForStockLiveCapabilities = listOf(
        OmiGattRequirement(manufacturer, allOf = setOf(BleGattCharacteristicProperty.READ)),
        OmiGattRequirement(model, allOf = setOf(BleGattCharacteristicProperty.READ)),
        OmiGattRequirement(firmware, allOf = setOf(BleGattCharacteristicProperty.READ)),
        OmiGattRequirement(
            battery,
            allOf = setOf(BleGattCharacteristicProperty.READ),
            anyOf = setOf(BleGattCharacteristicProperty.NOTIFY, BleGattCharacteristicProperty.INDICATE),
        ),
        OmiGattRequirement(
            audio,
            anyOf = setOf(BleGattCharacteristicProperty.NOTIFY, BleGattCharacteristicProperty.INDICATE),
        ),
        OmiGattRequirement(audioCodec, allOf = setOf(BleGattCharacteristicProperty.READ)),
        OmiGattRequirement(
            button,
            anyOf = setOf(BleGattCharacteristicProperty.NOTIFY, BleGattCharacteristicProperty.INDICATE),
        ),
        OmiGattRequirement(haptic, allOf = setOf(BleGattCharacteristicProperty.WRITE)),
    )

    private fun target(service: String, characteristic: String) =
        BleCharacteristicTarget(service, characteristic)
}

internal data class OmiGattRequirement(
    val target: BleCharacteristicTarget,
    val allOf: Set<BleGattCharacteristicProperty> = emptySet(),
    val anyOf: Set<BleGattCharacteristicProperty> = emptySet(),
) {
    fun isSatisfiedBy(properties: Set<BleGattCharacteristicProperty>): Boolean =
        properties.containsAll(allOf) && (anyOf.isEmpty() || properties.any(anyOf::contains))
}

internal class OmiStockAudioInputHandle(
    private val transport: BleTransportSession,
) : AudioInputHandle {
    private val format = AudioFormat(
        codec = AudioCodec.OPUS,
        sampleRateHz = 16_000u,
        channels = 1u,
        payloadFraming = AudioPayloadFraming.RAW_OPUS_PACKET,
        frameDurationMillis = 20u,
    )
    override val descriptor = AudioInputDescriptor(
        formats = setOf(format),
        live = true,
        stored = false,
    )
    val binding = CapabilityBinding(AudioInputV1, descriptor, this)
    private val streams = mutableSetOf<OmiAudioStream>()
    private val lock = Mutex()

    override suspend fun open(format: AudioFormat): AudioStream {
        require(format == this.format) { "Omi stock audio format was not negotiated" }
        val negotiatedMtu = transport.link.mtu ?: throw audioStreamFailure(
            category = FailureCategory.UNAVAILABLE,
            code = "OMI_AUDIO_MTU_UNKNOWN",
            retryable = true,
        )
        if (negotiatedMtu < OMI_STOCK_AUDIO_MINIMUM_UNFRAGMENTED_ATT_MTU) {
            throw audioStreamFailure(
                category = FailureCategory.INCOMPATIBLE,
                code = "OMI_AUDIO_MTU_CANNOT_PROVE_PACKET_BOUNDARY",
                retryable = true,
            )
        }
        val stream = OmiAudioStream(
            format = format,
            subscription = transport.subscribe(OmiCv1Targets.audio),
            onClosed = { closed -> lock.withLock { streams.remove(closed) } },
        )
        lock.withLock { streams += stream }
        return stream
    }

    suspend fun closeAll() {
        lock.withLock { streams.toList() }.forEach { it.close() }
        lock.withLock { streams.clear() }
    }

    private class OmiAudioStream(
        override val format: AudioFormat,
        private val subscription: BleSubscription,
        private val onClosed: suspend (OmiAudioStream) -> Unit,
    ) : AudioStream {
        override val frames: Flow<AudioFrame> = flow {
            val decoder = OmiLiveAudioEnvelopeDecoder()
            subscription.notifications.collect { notification ->
                val decoded = decoder.decode(
                    notificationOrdinal = notification.ordinal,
                    bytes = notification.value.copyBytes(),
                )
                emit(
                    AudioFrame(
                        sequence = decoded.sequence,
                        payload = dev.gumi.edge.sdk.OpaqueBytes.copyOf(decoded.payload),
                        discontinuityBefore = decoded.discontinuityBefore,
                        receivedAtMonotonicMillis = notification.receivedAtMonotonicMillis,
                    ),
                )
            }
        }

        private val closeLock = Mutex()
        private var closed = false

        override suspend fun close() = closeLock.withLock {
            if (!closed) {
                closed = true
                withContext(NonCancellable) {
                    try {
                        subscription.close()
                    } finally {
                        onClosed(this@OmiAudioStream)
                    }
                }
            }
        }
    }
}

private fun audioStreamFailure(
    category: FailureCategory,
    code: String,
    retryable: Boolean,
) = AudioStreamException(
    ExpectedFailure(
        category = category,
        code = FailureCode(code),
        retryable = retryable,
    ),
)

internal class OmiStockButtonGestureHandle(
    private val transport: BleTransportSession,
    firmware: String,
) : ButtonGestureHandle {
    override val descriptor = ButtonGestureDescriptor(
        gestures = stockGesturesFor(firmware),
        configurable = false,
    )
    val binding = CapabilityBinding(ButtonGestureV1, descriptor, this)
    override val events: Flow<ButtonGestureEvent> = flow {
        val subscription = transport.subscribe(OmiCv1Targets.button)
        try {
            emitAll(subscription.notifications.map { notification ->
                ButtonGestureEvent(
                    ordinal = notification.ordinal,
                    gesture = decodeStockButton(notification.value.copyBytes()),
                    deviceTimeMillis = null,
                )
            })
        } finally {
            withContext(NonCancellable) { subscription.close() }
        }
    }
}

private fun stockGesturesFor(firmware: String): Set<ButtonGestureKind> = when (firmware) {
    "3.0.12" -> setOf(
        ButtonGestureKind.SINGLE_TAP,
        ButtonGestureKind.DOUBLE_TAP,
        ButtonGestureKind.HOLD,
        ButtonGestureKind.RELEASE,
    )

    // In v3.0.20 the long callback is unreachable and a three-second physical hold powers off.
    "3.0.20" -> setOf(
        ButtonGestureKind.SINGLE_TAP,
        ButtonGestureKind.DOUBLE_TAP,
        ButtonGestureKind.RELEASE,
    )

    else -> error("Gesture profile requested for unsupported stock firmware $firmware")
}

internal class OmiStockHapticHandle(
    private val transport: BleTransportSession,
) : HapticHandle {
    private val values = mapOf(
        HapticPatternId("stock-short") to 1,
        HapticPatternId("stock-medium") to 2,
        HapticPatternId("stock-long") to 3,
    )
    override val descriptor = HapticDescriptor(patterns = values.keys)
    val binding = CapabilityBinding(HapticV1, descriptor, this)
    private val terminal = linkedMapOf<CommandId, Pair<HapticPatternId, OperationResult<Unit>>>()
    private val lock = Mutex()

    override suspend fun play(
        commandId: CommandId,
        pattern: HapticPatternId,
    ): OperationResult<Unit> = lock.withLock {
        terminal[commandId]?.let { (priorPattern, prior) ->
            return@withLock if (priorPattern == pattern) prior else OperationResult.Failure(
                ExpectedFailure(
                    category = FailureCategory.REPLAYED,
                    code = FailureCode("HAPTIC_COMMAND_ID_CONFLICT"),
                    retryable = false,
                ),
            )
        }
        val value = values[pattern] ?: return@withLock OperationResult.Failure(
            ExpectedFailure(
                category = FailureCategory.INCOMPATIBLE,
                code = FailureCode("HAPTIC_PATTERN_UNSUPPORTED"),
                retryable = false,
            ),
        )
        val result = try {
            transport.write(
                OmiCv1Targets.haptic,
                dev.gumi.edge.sdk.OpaqueBytes.copyOf(byteArrayOf(value.toByte())),
                BleWriteKind.WITH_RESPONSE,
            )
            OperationResult.Success(Unit)
        } catch (error: BleSessionException) {
            OperationResult.Failure(
                ExpectedFailure(
                    category = FailureCategory.UNAVAILABLE,
                    code = FailureCode("HAPTIC_BLE_WRITE_FAILED"),
                    retryable = true,
                ),
            )
        }
        terminal[commandId] = pattern to result
        while (terminal.size > 128) terminal.remove(terminal.keys.first())
        result
    }
}

internal class OmiStockPowerStatusHandle(
    private val transport: BleTransportSession,
) : PowerStatusHandle {
    override val descriptor = PowerStatusDescriptor(
        reportsBatteryPercent = true,
        reportsCharging = false,
    )
    val binding = CapabilityBinding(PowerStatusV1, descriptor, this)
    override val updates: Flow<PowerStatus> = flow {
        val subscription = transport.subscribe(OmiCv1Targets.battery)
        try {
            emitAll(subscription.notifications.map { notification ->
                decodeBattery(notification.value.copyBytes(), notification.receivedAtMonotonicMillis)
            })
        } finally {
            withContext(NonCancellable) { subscription.close() }
        }
    }

    override suspend fun read(): PowerStatus = decodeBattery(
        transport.read(OmiCv1Targets.battery).copyBytes(),
        observedAt = null,
    )
}

private fun decodeStockButton(bytes: ByteArray): ButtonGestureKind {
    if (bytes.size < 4) return ButtonGestureKind.UNKNOWN
    val code = bytes[0].toUByte().toUInt() or
        (bytes[1].toUByte().toUInt() shl 8) or
        (bytes[2].toUByte().toUInt() shl 16) or
        (bytes[3].toUByte().toUInt() shl 24)
    return when (code) {
        1u -> ButtonGestureKind.SINGLE_TAP
        2u -> ButtonGestureKind.DOUBLE_TAP
        3u -> ButtonGestureKind.HOLD
        4u -> ButtonGestureKind.PRESS
        5u -> ButtonGestureKind.RELEASE
        else -> ButtonGestureKind.UNKNOWN
    }
}

private fun decodeBattery(
    bytes: ByteArray,
    observedAt: Long?,
): PowerStatus {
    val percent = bytes.firstOrNull()?.toUByte()?.toUInt()
    return PowerStatus(
        batteryPercent = percent?.takeIf { it <= 100u },
        charging = null,
        observedAtMonotonicMillis = observedAt,
    )
}
