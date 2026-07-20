package dev.gumi.devices.omicv1.simulator

import dev.gumi.devices.omicv1.OmiCv1GattProfile
import dev.gumi.devices.omicv1.OmiCv1Protocol
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.OpaqueBytes
import dev.gumi.edge.sdk.TransportEvent
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.ble.BleBondState
import dev.gumi.edge.sdk.ble.BoundedSingleConsumerStream
import dev.gumi.edge.sdk.ble.BleCentral
import dev.gumi.edge.sdk.ble.BleCharacteristicTarget
import dev.gumi.edge.sdk.ble.BleConnectionOptions
import dev.gumi.edge.sdk.ble.BleGattCharacteristicProperty
import dev.gumi.edge.sdk.ble.BleGattService
import dev.gumi.edge.sdk.ble.BleLinkSnapshot
import dev.gumi.edge.sdk.ble.BleNotification
import dev.gumi.edge.sdk.ble.BlePhy
import dev.gumi.edge.sdk.ble.BleSessionEvent
import dev.gumi.edge.sdk.ble.BleSessionException
import dev.gumi.edge.sdk.ble.BleSessionFailureCode
import dev.gumi.edge.sdk.ble.BleSubscription
import dev.gumi.edge.sdk.ble.BleTransportSession
import dev.gumi.edge.sdk.ble.BleWriteKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SimulatorOperation {
    DISCOVER,
    READ,
    WRITE,
    SUBSCRIBE,
}

data class SimulatorPlannedFailure(
    val operation: SimulatorOperation,
    val target: BleCharacteristicTarget? = null,
    val code: BleSessionFailureCode,
    val detail: String = "planned simulator failure",
)

data class SimulatorWrite(
    val target: BleCharacteristicTarget,
    val kind: BleWriteKind,
    val value: OpaqueBytes,
)

/**
 * Deterministic in-memory CV1 v3.0.12 peripheral. It presents the same BLE central/session boundary as
 * a platform adapter; it is never selected through a simulator-specific transport kind.
 */
class OmiCv1Simulator(
    private val batteryPercent: UInt = 47u,
    private val firstStorageFileBytes: UInt = 505_118_720u,
    private val secondStorageFileBytes: UInt = 0u,
    private val gattServices: List<BleGattService> = OmiCv1V3012Profile.services,
    private val eventBufferCapacity: Int = 32,
    private val notificationBufferCapacity: Int = 16,
    private val maximumAttMtu: Int = 498,
) : BleCentral {
    val endpoint = EndpointCandidate(
        transport = TransportKind.BLE,
        ephemeralId = "ble:omi-cv1-v3.0.12-simulator",
        advertisedServiceUuids = setOf(
            OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
            OmiCv1Protocol.AUDIO_SERVICE_UUID,
        ),
        advertisedName = "Omi",
    )

    val writes: List<SimulatorWrite> get() = recordedWrites.toList()
    val operations: List<SimulatorOperation> get() = recordedOperations.toList()

    private val lock = Mutex()
    private val values = linkedMapOf<BleCharacteristicTarget, OpaqueBytes>()
    private val sessions = linkedSetOf<SimulatorSession>()
    private val failures = ArrayDeque<SimulatorPlannedFailure>()
    private val recordedWrites = mutableListOf<SimulatorWrite>()
    private val recordedOperations = mutableListOf<SimulatorOperation>()
    private val firmwareRevisionTarget = BleCharacteristicTarget(
        OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE,
        OmiCv1GattProfile.FIRMWARE_REVISION,
    )
    private var monotonicMillis = 0L

    init {
        require(batteryPercent <= 100u)
        require(eventBufferCapacity > 0)
        require(notificationBufferCapacity > 0)
        require(maximumAttMtu in 23..517)
        resetValues(batteryPercent, firstStorageFileBytes, secondStorageFileBytes)
    }

    override suspend fun connect(
        endpoint: EndpointCandidate,
        options: BleConnectionOptions,
    ): BleTransportSession = lock.withLock {
        if (endpoint != this.endpoint) {
            throw BleSessionException(
                BleSessionFailureCode.ENDPOINT_EXPIRED,
                "Simulator endpoint does not match the requested endpoint",
            )
        }
        if (sessions.isNotEmpty()) {
            throw BleSessionException(
                BleSessionFailureCode.CONNECTION_FAILED,
                "The CV1 v3.0.12 simulator permits one BLE connection",
            )
        }
        SimulatorSession(
            simulator = this,
            negotiatedAttMtu = minOf(options.requestedMtu ?: 23, maximumAttMtu),
        ).also(sessions::add)
    }

    suspend fun failNext(failure: SimulatorPlannedFailure) = lock.withLock {
        failures.addLast(failure)
    }

    suspend fun setReadValue(
        target: BleCharacteristicTarget,
        value: ByteArray,
    ) = lock.withLock {
        requireCharacteristic(target)
        require(target != firmwareRevisionTarget) {
            "The observed v3.0.12 simulator firmware identity is immutable; " +
                "use a complete version-specific profile instead of relabeling its GATT database"
        }
        values[target] = OpaqueBytes.copyOf(value)
    }

    suspend fun emit(
        target: BleCharacteristicTarget,
        value: ByteArray,
    ) = emitMany(target, listOf(value))

    /** Emits one stock Omi audio notification: u16-LE sequence, zero fragment index, Opus packet. */
    suspend fun emitAudioPacket(
        sequence: UInt,
        opusPacket: ByteArray,
    ) {
        require(sequence <= 0xffffu)
        require(opusPacket.isNotEmpty() && opusPacket.size <= 160)
        emit(
            BleCharacteristicTarget(
                OmiCv1Protocol.AUDIO_SERVICE_UUID,
                OmiCv1V3012Profile.AUDIO_CHARACTERISTIC,
            ),
            byteArrayOf(sequence.toByte(), (sequence shr 8).toByte(), 0) + opusPacket,
        )
    }

    suspend fun emitSplit(
        target: BleCharacteristicTarget,
        value: ByteArray,
        partSizes: List<Int>,
    ) {
        require(partSizes.isNotEmpty() && partSizes.all { it > 0 })
        require(partSizes.sum() == value.size)
        var offset = 0
        val parts = partSizes.map { size ->
            value.copyOfRange(offset, offset + size).also { offset += size }
        }
        emitMany(target, parts)
    }

    suspend fun emitDuplicate(
        target: BleCharacteristicTarget,
        value: ByteArray,
    ) = emitMany(target, listOf(value, value.copyOf()))

    suspend fun emitReordered(
        target: BleCharacteristicTarget,
        values: List<ByteArray>,
        order: List<Int>,
    ) {
        require(order.sorted() == values.indices.toList())
        emitMany(target, order.map { values[it] })
    }

    suspend fun disconnect() {
        val active = lock.withLock { sessions.toList() }
        active.forEach { it.disconnectFromPeripheral() }
        lock.withLock { sessions.removeAll(active.toSet()) }
    }

    /** A reboot closes every current session. The next connect starts from the stock default profile. */
    suspend fun reboot() {
        disconnect()
        lock.withLock {
            monotonicMillis = 0
            failures.clear()
            recordedWrites.clear()
            recordedOperations.clear()
            resetValues(batteryPercent, firstStorageFileBytes, secondStorageFileBytes)
        }
    }

    private suspend fun emitMany(
        target: BleCharacteristicTarget,
        payloads: List<ByteArray>,
    ) {
        val active = lock.withLock {
            requireNotifiable(target)
            sessions.toList()
        }
        payloads.forEach { payload ->
            val observedAt = lock.withLock {
                monotonicMillis += 1
                monotonicMillis
            }
            active.forEach { it.emitNotification(target, payload, observedAt) }
        }
    }

    private fun resetValues(
        batteryPercent: UInt,
        firstStorageFileBytes: UInt,
        secondStorageFileBytes: UInt,
    ) {
        values.clear()
        putText(OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE, OmiCv1GattProfile.MANUFACTURER_NAME, "Based Hardware")
        putText(OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE, OmiCv1GattProfile.MODEL_NUMBER, "Omi CV 1")
        putText(OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE, OmiCv1GattProfile.FIRMWARE_REVISION, "3.0.12")
        putText(OmiCv1GattProfile.DEVICE_INFORMATION_SERVICE, OmiCv1GattProfile.HARDWARE_REVISION, "5.0")
        putText(OmiCv1V3012Profile.GENERIC_ACCESS_SERVICE, OmiCv1V3012Profile.GENERIC_ACCESS_DEVICE_NAME, "Omi")
        put(OmiCv1GattProfile.BATTERY_SERVICE, OmiCv1GattProfile.BATTERY_LEVEL, byteArrayOf(batteryPercent.toByte()))
        put(
            OmiCv1GattProfile.STORAGE_SERVICE,
            OmiCv1GattProfile.STORAGE_STATUS,
            littleEndian(firstStorageFileBytes) + littleEndian(secondStorageFileBytes),
        )
        put(OmiCv1V3012Profile.BUTTON_SERVICE, OmiCv1V3012Profile.BUTTON_CHARACTERISTIC, ByteArray(8))
        put(OmiCv1Protocol.AUDIO_SERVICE_UUID, OmiCv1V3012Profile.AUDIO_CHARACTERISTIC, byteArrayOf())
        put(OmiCv1Protocol.AUDIO_SERVICE_UUID, OmiCv1V3012Profile.AUDIO_CODEC_CHARACTERISTIC, byteArrayOf(21))
        put(OmiCv1V3012Profile.SETTINGS_SERVICE, OmiCv1V3012Profile.LED_DIM_CHARACTERISTIC, byteArrayOf(0))
        put(OmiCv1V3012Profile.SETTINGS_SERVICE, OmiCv1V3012Profile.MIC_GAIN_CHARACTERISTIC, byteArrayOf(0))
        put(OmiCv1V3012Profile.FEATURES_SERVICE, OmiCv1V3012Profile.FEATURES_CHARACTERISTIC, byteArrayOf(0))
    }

    private fun putText(service: String, characteristic: String, value: String) =
        put(service, characteristic, value.encodeToByteArray())

    private fun put(service: String, characteristic: String, value: ByteArray) {
        values[BleCharacteristicTarget(service, characteristic)] = OpaqueBytes.copyOf(value)
    }

    private fun littleEndian(value: UInt) = ByteArray(4) { index ->
        (value shr (index * 8)).toByte()
    }

    private fun requireCharacteristic(target: BleCharacteristicTarget) =
        gattServices
            .flatMap(BleGattService::characteristics)
            .firstOrNull { it.serviceUuid == target.serviceUuid && it.uuid == target.characteristicUuid }
            ?: throw BleSessionException(
                BleSessionFailureCode.ATTRIBUTE_MISSING,
                "Characteristic is absent from the v3.0.12 profile",
            )

    private fun requireReadable(target: BleCharacteristicTarget) {
        val characteristic = requireCharacteristic(target)
        if (BleGattCharacteristicProperty.READ !in characteristic.properties) {
            throw BleSessionException(
                BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
                "Characteristic is not readable in the v3.0.12 profile",
            )
        }
    }

    private fun requireWritable(target: BleCharacteristicTarget, kind: BleWriteKind) {
        val characteristic = requireCharacteristic(target)
        val required = when (kind) {
            BleWriteKind.WITH_RESPONSE -> BleGattCharacteristicProperty.WRITE
            BleWriteKind.WITHOUT_RESPONSE -> BleGattCharacteristicProperty.WRITE_WITHOUT_RESPONSE
        }
        if (required !in characteristic.properties) {
            throw BleSessionException(
                BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
                "Characteristic does not support $kind in the v3.0.12 profile",
            )
        }
    }

    private fun requireNotifiable(target: BleCharacteristicTarget) {
        val properties = requireCharacteristic(target).properties
        if (
            BleGattCharacteristicProperty.NOTIFY !in properties &&
            BleGattCharacteristicProperty.INDICATE !in properties
        ) {
            throw BleSessionException(
                BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
                "Characteristic is not notifiable in the v3.0.12 profile",
            )
        }
    }

    private suspend fun beforeOperation(
        operation: SimulatorOperation,
        target: BleCharacteristicTarget? = null,
    ): SimulatorPlannedFailure? = lock.withLock {
        recordedOperations += operation
        val failure = failures.firstOrNull() ?: return@withLock null
        if (failure.operation != operation || failure.target != null && failure.target != target) {
            return@withLock null
        }
        failures.removeFirst()
    }

    private inner class SimulatorSession(
        private val simulator: OmiCv1Simulator,
        negotiatedAttMtu: Int,
    ) : BleTransportSession {
        override val endpoint: EndpointCandidate = simulator.endpoint
        override val link = BleLinkSnapshot(
            mtu = negotiatedAttMtu,
            txPhy = BlePhy.LE_2M,
            rxPhy = BlePhy.LE_2M,
            bondState = BleBondState.NOT_BONDED,
        )

        private val operationLock = Mutex()
        private val bleEventStream = BoundedSingleConsumerStream<BleSessionEvent>(eventBufferCapacity)
        private val transportEventStream = BoundedSingleConsumerStream<TransportEvent>(eventBufferCapacity)
        private val subscriptions = linkedMapOf<BleCharacteristicTarget, SimulatorSubscription>()
        private var state = SessionState.OPERATIONAL

        override val bleEvents: Flow<BleSessionEvent> = bleEventStream.flow
        override val events: Flow<TransportEvent> = transportEventStream.flow

        override suspend fun discoverServices(): List<BleGattService> = operationLock.withLock {
            ensureOperational()
            beforeOperation(SimulatorOperation.DISCOVER)?.throwFailure()
            gattServices
        }

        override suspend fun read(target: BleCharacteristicTarget): OpaqueBytes = operationLock.withLock {
            ensureOperational()
            beforeOperation(SimulatorOperation.READ, target)?.throwFailure()
            requireReadable(target)
            lock.withLock {
                values[target] ?: throw BleSessionException(
                    BleSessionFailureCode.OPERATION_FAILED,
                    "No deterministic read value is configured",
                )
            }
        }

        override suspend fun write(
            target: BleCharacteristicTarget,
            value: OpaqueBytes,
            kind: BleWriteKind,
        ) = operationLock.withLock {
            ensureOperational()
            beforeOperation(SimulatorOperation.WRITE, target)?.throwFailure()
            requireWritable(target, kind)
            lock.withLock {
                recordedWrites += SimulatorWrite(target, kind, OpaqueBytes.copyOf(value.copyBytes()))
                values[target] = OpaqueBytes.copyOf(value.copyBytes())
            }
        }

        override suspend fun subscribe(target: BleCharacteristicTarget): BleSubscription =
            operationLock.withLock {
                ensureOperational()
                beforeOperation(SimulatorOperation.SUBSCRIBE, target)?.throwFailure()
                requireNotifiable(target)
                if (subscriptions[target]?.isClosed == false) {
                    throw BleSessionException(
                        BleSessionFailureCode.OPERATION_FAILED,
                        "The requested characteristic already has an active subscription",
                    )
                }
                SimulatorSubscription(target).also { subscriptions[target] = it }
            }

        override suspend fun close() {
            val activeSubscriptions = operationLock.withLock {
                if (state == SessionState.CLOSED) return
                state = SessionState.CLOSED
                subscriptions.values.toList().also { subscriptions.clear() }
            }
            activeSubscriptions.forEach(SimulatorSubscription::finish)
            bleEventStream.finishWith(BleSessionEvent.Closed)
            transportEventStream.finishWith(TransportEvent.Closed)
            lock.withLock { sessions.remove(this) }
        }

        suspend fun disconnectFromPeripheral() {
            val activeSubscriptions = operationLock.withLock {
                if (state != SessionState.OPERATIONAL) return
                state = SessionState.DISCONNECTED
                subscriptions.values.toList().also { subscriptions.clear() }
            }
            activeSubscriptions.forEach(SimulatorSubscription::finish)
            bleEventStream.finishWith(BleSessionEvent.Disconnected)
            transportEventStream.finishWith(
                TransportEvent.Fault(BleSessionFailureCode.DISCONNECTED.name, "Peripheral disconnected"),
            )
        }

        suspend fun emitNotification(
            target: BleCharacteristicTarget,
            payload: ByteArray,
            atMillis: Long,
        ) {
            var terminalOverflow = false
            operationLock.withLock {
                if (state != SessionState.OPERATIONAL) return
                val subscription = subscriptions[target] ?: return
                if (!subscription.offer(payload, atMillis)) {
                    terminalOverflow = emitNonTerminal(BleSessionEvent.NotificationsDropped(target, 1u))
                }
            }
            if (terminalOverflow) lock.withLock { sessions.remove(this) }
        }

        private fun ensureOperational() {
            when (state) {
                SessionState.OPERATIONAL -> Unit
                SessionState.DISCONNECTED -> throw BleSessionException(
                    BleSessionFailureCode.DISCONNECTED,
                    "BLE peripheral disconnected",
                )
                SessionState.EVENT_OVERFLOW -> throw BleSessionException(
                    BleSessionFailureCode.EVENT_OVERFLOW,
                    "The bounded BLE session event stream overflowed",
                )
                SessionState.CLOSED -> throw BleSessionException(BleSessionFailureCode.CLOSED, "BLE session is closed")
            }
        }

        /** Called only while [operationLock] is held. */
        private fun emitNonTerminal(event: BleSessionEvent): Boolean {
            val transportEvent = event.asTransportEvent()
            if (bleEventStream.tryEmit(event) && transportEventStream.tryEmit(transportEvent)) return false

            state = SessionState.EVENT_OVERFLOW
            subscriptions.values.forEach(SimulatorSubscription::finish)
            subscriptions.clear()
            bleEventStream.finishWith(
                BleSessionEvent.Fault(
                    BleSessionFailureCode.EVENT_OVERFLOW,
                    "BLE session event buffer overflowed",
                ),
            )
            transportEventStream.finishWith(
                TransportEvent.Fault(BleSessionFailureCode.EVENT_OVERFLOW.name, "BLE event buffer overflowed"),
            )
            return true
        }

        private inner class SimulatorSubscription(
            override val target: BleCharacteristicTarget,
        ) : BleSubscription {
            private val stream = BoundedSingleConsumerStream<BleNotification>(notificationBufferCapacity)
            private var nextOrdinal = 1uL
            var isClosed: Boolean = false
                private set

            override val notifications: Flow<BleNotification> = stream.flow

            fun offer(payload: ByteArray, atMillis: Long): Boolean {
                val ordinal = nextOrdinal++
                return !isClosed && stream.tryEmit(
                    BleNotification(target, ordinal, atMillis, OpaqueBytes.copyOf(payload)),
                )
            }

            override suspend fun close() {
                withContext(NonCancellable) {
                    operationLock.withLock {
                        if (isClosed) return@withLock
                        finish()
                        subscriptions.remove(target, this@SimulatorSubscription)
                    }
                }
            }

            fun finish() {
                if (isClosed) return
                isClosed = true
                stream.finish()
            }
        }
    }

    private enum class SessionState { OPERATIONAL, DISCONNECTED, EVENT_OVERFLOW, CLOSED }

    private fun BleSessionEvent.asTransportEvent(): TransportEvent = when (this) {
        BleSessionEvent.Closed -> TransportEvent.Closed
        is BleSessionEvent.Fault -> TransportEvent.Fault(code.name, detail)
        BleSessionEvent.Disconnected -> TransportEvent.Fault(
            BleSessionFailureCode.DISCONNECTED.name,
            "Peripheral disconnected",
        )
        is BleSessionEvent.LinkChanged -> TransportEvent.Fault("LINK_CHANGED", "BLE link parameters changed")
        is BleSessionEvent.NotificationsDropped -> TransportEvent.Fault(
            BleSessionFailureCode.EVENT_OVERFLOW.name,
            "Notifications dropped: $count",
        )
    }

    private fun SimulatorPlannedFailure.throwFailure(): Nothing = throw BleSessionException(code, detail)
}
