package dev.gumi.edge.platforms.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.OpaqueBytes
import dev.gumi.edge.sdk.TransportEvent
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.ble.BleBondState
import dev.gumi.edge.sdk.ble.BoundedSingleConsumerStream
import dev.gumi.edge.sdk.ble.BleCentral
import dev.gumi.edge.sdk.ble.BleCharacteristicTarget
import dev.gumi.edge.sdk.ble.BleConnectionOptions
import dev.gumi.edge.sdk.ble.BleGattAttributePermission
import dev.gumi.edge.sdk.ble.BleGattCharacteristic
import dev.gumi.edge.sdk.ble.BleGattCharacteristicProperty
import dev.gumi.edge.sdk.ble.BleGattDescriptor
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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.MtuRequest
import no.nordicsemi.android.ble.PhyRequest
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.exception.BluetoothDisabledException
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.InvalidRequestException
import no.nordicsemi.android.ble.exception.RequestFailedException
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.BondingObserver
import no.nordicsemi.android.ble.observer.ConnectionObserver

/**
 * Operational Android implementation of the host-neutral BLE central port.
 *
 * The endpoint directory is intentionally shared with [AndroidBleScanner]. This keeps stable BLE
 * addresses inside the Android process while allowing a redacted scan result to be connected.
 * Nordic's [BleManager] owns the only platform ATT queue for each returned session; Gumi adds a
 * lifecycle gate so operations cannot race close or continue after an unexpected disconnect.
 */
class AndroidBleCentral(
    context: Context,
    private val endpointDirectory: AndroidBleEndpointDirectory,
) : BleCentral {
    private val applicationContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override suspend fun connect(
        endpoint: EndpointCandidate,
        options: BleConnectionOptions,
    ): BleTransportSession {
        if (endpoint.transport != TransportKind.BLE) {
            throw BleSessionException(
                BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
                "Android BLE central requires a BLE endpoint",
            )
        }
        if (!applicationContext.hasBleConnectionPermission()) {
            throw BleSessionException(
                BleSessionFailureCode.PERMISSION_DENIED,
                "Android Nearby Devices permission is required",
            )
        }

        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            throw BleSessionException(
                BleSessionFailureCode.BLUETOOTH_UNAVAILABLE,
                "Bluetooth is unavailable or disabled",
            )
        }
        val device = endpointDirectory.resolve(endpoint.ephemeralId)
            ?: throw BleSessionException(
                BleSessionFailureCode.ENDPOINT_EXPIRED,
                "The ephemeral BLE endpoint expired; scan again",
            )

        val manager = OperationalBleManager(applicationContext)
        val session = AndroidBleTransportSession(
            endpoint = endpoint,
            device = device,
            manager = manager,
        )
        manager.listener = session

        try {
            session.connect(options)
            return session
        } catch (error: CancellationException) {
            session.closeAfterFailedConnect()
            throw error
        } catch (error: BleSessionException) {
            session.closeAfterFailedConnect()
            throw error
        } catch (error: Throwable) {
            session.closeAfterFailedConnect()
            throw error.toSessionException(
                defaultCode = BleSessionFailureCode.CONNECTION_FAILED,
                operation = "connection",
            )
        }
    }
}

private class AndroidBleTransportSession(
    override val endpoint: EndpointCandidate,
    private val device: BluetoothDevice,
    private val manager: OperationalBleManager,
) : BleTransportSession, OperationalBleManager.Listener {
    private val lifecycle = BleSessionLifecycle()
    private val attOwner = Mutex()
    private val subscriptionLock = Any()
    private val subscriptions = mutableMapOf<BleCharacteristicTarget, AndroidBleSubscription>()
    private val transportEventBus = BoundedSingleConsumerStream<TransportEvent>(EVENT_BUFFER_CAPACITY)
    private val bleEventBus = BoundedSingleConsumerStream<BleSessionEvent>(EVENT_BUFFER_CAPACITY)

    @Volatile
    private var linkSnapshot = BleLinkSnapshot(
        mtu = null,
        txPhy = null,
        rxPhy = null,
        bondState = device.toOperationalBondState(),
    )

    override val events: Flow<TransportEvent> = transportEventBus.flow
    override val bleEvents: Flow<BleSessionEvent> = bleEventBus.flow
    override val link: BleLinkSnapshot get() = linkSnapshot

    @SuppressLint("MissingPermission")
    suspend fun connect(options: BleConnectionOptions) {
        try {
            manager.connect(device)
                .useAutoConnect(false)
                .timeout(options.timeoutMillis)
                .suspend()

            val mtu = options.requestedMtu?.let { requested ->
                manager.requestOperationalMtu(requested, OPERATION_TIMEOUT_MILLIS)
            } ?: manager.currentMtu
            val phy = try {
                manager.readOperationalPhy(OPERATION_TIMEOUT_MILLIS)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }

            lifecycle.ensureOperational()
            updateLink(
                BleLinkSnapshot(
                    mtu = mtu,
                    txPhy = phy?.first?.toOperationalPhy(),
                    rxPhy = phy?.second?.toOperationalPhy(),
                    bondState = device.toOperationalBondState(),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw lifecycle.overrideFailureOrNull()
                ?: error.toSessionException(
                    defaultCode = BleSessionFailureCode.CONNECTION_FAILED,
                    operation = "connection",
                )
        }
    }

    override suspend fun discoverServices(): List<BleGattService> = runAtt("service discovery") {
        manager.services
    }

    override suspend fun read(target: BleCharacteristicTarget): OpaqueBytes = runAtt("read") {
        val characteristic = manager.find(target)
            ?: throw attributeMissing(target)
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            throw BleSessionException(
                BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
                "The requested characteristic is not readable",
            )
        }

        val value = manager.readOperationalCharacteristic(characteristic)
            .timeout(OPERATION_TIMEOUT_MILLIS)
            .suspend()
            .value ?: byteArrayOf()
        OpaqueBytes.copyOf(value)
    }

    override suspend fun write(
        target: BleCharacteristicTarget,
        value: OpaqueBytes,
        kind: BleWriteKind,
    ) = runAtt("write") {
        val characteristic = manager.find(target)
            ?: throw attributeMissing(target)
        val writeType = when (kind) {
            BleWriteKind.WITH_RESPONSE -> {
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
                    throw BleSessionException(
                        BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
                        "The requested characteristic does not support writes with response",
                    )
                }
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            BleWriteKind.WITHOUT_RESPONSE -> {
                if (
                    characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0
                ) {
                    throw BleSessionException(
                        BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
                        "The requested characteristic does not support writes without response",
                    )
                }
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
        }

        manager.writeOperationalCharacteristic(characteristic, value.copyBytes(), writeType)
            .timeout(OPERATION_TIMEOUT_MILLIS)
            .suspend()
        Unit
    }

    override suspend fun subscribe(target: BleCharacteristicTarget): BleSubscription =
        runAtt("subscription") {
            val characteristic = manager.find(target)
                ?: throw attributeMissing(target)
            val mode = when {
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                    ChangeMode.NOTIFICATION

                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                    ChangeMode.INDICATION

                else -> throw BleSessionException(
                    BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
                    "The requested characteristic does not support notifications or indications",
                )
            }

            val subscription = synchronized(subscriptionLock) {
                if (subscriptions.containsKey(target)) {
                    throw BleSessionException(
                        BleSessionFailureCode.OPERATION_FAILED,
                        "The requested characteristic already has an active subscription",
                    )
                }
                AndroidBleSubscription(
                    target = target,
                    owner = this,
                    capacity = NOTIFICATION_BUFFER_CAPACITY,
                    monotonicMillis = SystemClock::elapsedRealtime,
                ).also { subscriptions[target] = it }
            }

            manager.installChangeCallback(characteristic, mode) { bytes ->
                when (subscription.offer(bytes)) {
                    NotificationOfferResult.ACCEPTED,
                    // Android may deliver an already in-flight callback while the CCCD is being
                    // disabled. Intentional shutdown is not notification-buffer loss.
                    NotificationOfferResult.CLOSED,
                    -> Unit

                    NotificationOfferResult.BUFFER_FULL ->
                        emitBleEvent(BleSessionEvent.NotificationsDropped(target, 1u))
                }
            }
            try {
                manager.enableChanges(characteristic, mode)
                    .timeout(OPERATION_TIMEOUT_MILLIS)
                    .suspend()
                subscription
            } catch (error: Throwable) {
                synchronized(subscriptionLock) { subscriptions.remove(target, subscription) }
                manager.removeChangeCallback(characteristic, mode)
                subscription.finish()
                throw error
            }
        }

    suspend fun closeSubscription(subscription: AndroidBleSubscription) {
        var failure: Throwable? = null
        attOwner.withLock {
            val removed = synchronized(subscriptionLock) {
                subscriptions.remove(subscription.target, subscription)
            }
            if (!removed) return@withLock

            try {
                val characteristic = manager.find(subscription.target)
                if (characteristic != null) {
                    val mode = characteristic.changeModeOrNull()
                    if (mode != null) {
                        if (lifecycle.isOperational) {
                            try {
                                manager.disableChanges(characteristic, mode)
                                    .timeout(OPERATION_TIMEOUT_MILLIS)
                                    .suspend()
                            } catch (error: CancellationException) {
                                failure = error
                            } catch (error: Throwable) {
                                failure = lifecycle.overrideFailureOrNull()
                                    ?: error.toSessionException(
                                        defaultCode = BleSessionFailureCode.OPERATION_FAILED,
                                        operation = "subscription close",
                                    )
                            }
                        }
                        manager.removeChangeCallback(characteristic, mode)
                    }
                }
            } catch (error: Throwable) {
                if (failure == null) {
                    failure = lifecycle.overrideFailureOrNull()
                        ?: error.toSessionException(
                            defaultCode = BleSessionFailureCode.OPERATION_FAILED,
                            operation = "subscription cleanup",
                        )
                }
            } finally {
                subscription.finish()
            }
        }
        failure?.let { throw it }
    }

    override suspend fun close() {
        if (!lifecycle.close()) return

        manager.cancelPendingOperations()
        withContext(NonCancellable) {
            attOwner.withLock {
                val activeSubscriptions = synchronized(subscriptionLock) {
                    subscriptions.values.toList().also { subscriptions.clear() }
                }
                activeSubscriptions.forEach { subscription ->
                    manager.find(subscription.target)?.let { characteristic ->
                        characteristic.changeModeOrNull()?.let { mode ->
                            manager.removeChangeCallback(characteristic, mode)
                        }
                    }
                    subscription.finish()
                }

                runCatching {
                    if (manager.isConnected) {
                        manager.disconnect()
                            .timeout(DISCONNECT_TIMEOUT_MILLIS)
                            .suspend()
                    }
                }
                manager.close()
            }
        }

        bleEventBus.finishWith(BleSessionEvent.Closed)
        transportEventBus.finishWith(TransportEvent.Closed)
    }

    suspend fun closeAfterFailedConnect() {
        close()
    }

    override fun onDisconnected() {
        if (!lifecycle.disconnect()) return
        finishResourcesAfterTerminalLinkFailure()
        bleEventBus.finishWith(BleSessionEvent.Disconnected)
        transportEventBus.finishWith(
            TransportEvent.Fault(
                code = "BLE_DISCONNECTED",
                detail = "The BLE peripheral disconnected",
            ),
        )
    }

    override fun onBondStateChanged(bondState: BleBondState) {
        if (!lifecycle.isOperational) return
        updateLink(linkSnapshot.copy(bondState = bondState))
    }

    private suspend fun <T> runAtt(operation: String, block: suspend () -> T): T =
        attOwner.withLock {
            lifecycle.ensureOperational()
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: BleSessionException) {
                throw error
            } catch (error: SecurityException) {
                throw BleSessionException(
                    BleSessionFailureCode.PERMISSION_DENIED,
                    "Android rejected BLE connection permission during $operation",
                    error,
                )
            } catch (error: Throwable) {
                throw lifecycle.overrideFailureOrNull()
                    ?: error.toSessionException(
                        defaultCode = BleSessionFailureCode.OPERATION_FAILED,
                        operation = operation,
                    )
            }
        }

    private fun updateLink(next: BleLinkSnapshot) {
        linkSnapshot = next
        emitBleEvent(BleSessionEvent.LinkChanged(next))
    }

    private fun emitBleEvent(event: BleSessionEvent) {
        if (!bleEventBus.tryEmit(event)) terminateEventOverflow()
    }

    private fun emitTransportEvent(event: TransportEvent) {
        if (!transportEventBus.tryEmit(event)) terminateEventOverflow()
    }

    private fun terminateEventOverflow() {
        if (!lifecycle.failEventOverflow()) return
        finishResourcesAfterTerminalLinkFailure()
        bleEventBus.finishWith(
            BleSessionEvent.Fault(
                BleSessionFailureCode.EVENT_OVERFLOW,
                "BLE session event buffer overflowed",
            ),
        )
        transportEventBus.finishWith(
            TransportEvent.Fault(
                code = "BLE_EVENT_OVERFLOW",
                detail = "BLE transport event buffer overflowed",
            ),
        )
    }

    /** Callback-safe terminal cleanup; no asynchronous ATT request is started from an observer. */
    private fun finishResourcesAfterTerminalLinkFailure() {
        manager.cancelPendingOperations()
        val activeSubscriptions = synchronized(subscriptionLock) {
            subscriptions.values.toList().also { subscriptions.clear() }
        }
        activeSubscriptions.forEach { subscription ->
            manager.find(subscription.target)?.let { characteristic ->
                characteristic.changeModeOrNull()?.let { mode ->
                    manager.removeChangeCallback(characteristic, mode)
                }
            }
            subscription.finish()
        }
        manager.close()
    }

    private companion object {
        const val OPERATION_TIMEOUT_MILLIS = 10_000L
        const val DISCONNECT_TIMEOUT_MILLIS = 5_000L
        const val EVENT_BUFFER_CAPACITY = 32
        const val NOTIFICATION_BUFFER_CAPACITY = 64
    }
}

private class AndroidBleSubscription(
    override val target: BleCharacteristicTarget,
    private val owner: AndroidBleTransportSession,
    capacity: Int,
    monotonicMillis: () -> Long,
) : BleSubscription {
    private val lifecycleLock = Any()
    private var closeStarted = false
    private val closeCompleted = CompletableDeferred<Unit>()
    private val sink = BoundedNotificationSink(target, capacity, monotonicMillis)

    override val notifications: Flow<BleNotification> = sink.flow

    fun offer(bytes: ByteArray): NotificationOfferResult = sink.offer(bytes)

    override suspend fun close() {
        val ownsClose = synchronized(lifecycleLock) {
            if (closeStarted) false else {
                closeStarted = true
                sink.stopAccepting()
                true
            }
        }
        if (ownsClose) {
            try {
                withContext(NonCancellable) { owner.closeSubscription(this@AndroidBleSubscription) }
            } finally {
                finish()
            }
        } else {
            closeCompleted.await()
        }
    }

    fun finish() {
        synchronized(lifecycleLock) {
            closeStarted = true
            sink.stopAccepting()
        }
        sink.close()
        closeCompleted.complete(Unit)
    }
}

internal enum class NotificationOfferResult {
    ACCEPTED,
    BUFFER_FULL,
    CLOSED,
}

internal class BoundedNotificationSink(
    private val target: BleCharacteristicTarget,
    capacity: Int,
    private val monotonicMillis: () -> Long,
) {
    private val stream = BoundedSingleConsumerStream<BleNotification>(capacity)
    private val admissionLock = Any()
    private var accepting = true
    private var nextOrdinal = 1uL

    init {
        require(capacity > 0) { "Notification capacity must be positive" }
    }

    val flow: Flow<BleNotification> = stream.flow

    fun offer(bytes: ByteArray): NotificationOfferResult = synchronized(admissionLock) {
        if (!accepting) return@synchronized NotificationOfferResult.CLOSED

        val ordinal = nextOrdinal++
        if (
            stream.tryEmit(
                BleNotification(
                    target = target,
                    ordinal = ordinal,
                    receivedAtMonotonicMillis = monotonicMillis(),
                    value = OpaqueBytes.copyOf(bytes),
                ),
            )
        ) {
            NotificationOfferResult.ACCEPTED
        } else {
            NotificationOfferResult.BUFFER_FULL
        }
    }

    fun stopAccepting() {
        synchronized(admissionLock) { accepting = false }
    }

    fun close() {
        stopAccepting()
        stream.finish()
    }
}

internal class BleSessionLifecycle {
    private enum class State { OPERATIONAL, DISCONNECTED, EVENT_OVERFLOW, CLOSED }

    private val lock = Any()
    private var state = State.OPERATIONAL

    val isOperational: Boolean get() = synchronized(lock) { state == State.OPERATIONAL }

    fun disconnect(): Boolean = synchronized(lock) {
        if (state != State.OPERATIONAL) false else {
            state = State.DISCONNECTED
            true
        }
    }

    fun failEventOverflow(): Boolean = synchronized(lock) {
        if (state != State.OPERATIONAL) false else {
            state = State.EVENT_OVERFLOW
            true
        }
    }

    fun close(): Boolean = synchronized(lock) {
        if (state == State.CLOSED) false else {
            state = State.CLOSED
            true
        }
    }

    fun ensureOperational() {
        overrideFailureOrNull()?.let { throw it }
    }

    fun overrideFailureOrNull(): BleSessionException? = synchronized(lock) {
        when (state) {
            State.OPERATIONAL -> null
            State.DISCONNECTED -> BleSessionException(
                BleSessionFailureCode.DISCONNECTED,
                "The BLE peripheral disconnected",
            )

            State.EVENT_OVERFLOW -> BleSessionException(
                BleSessionFailureCode.EVENT_OVERFLOW,
                "The BLE session event buffer overflowed",
            )

            State.CLOSED -> BleSessionException(
                BleSessionFailureCode.CLOSED,
                "The BLE session is closed",
            )
        }
    }
}

private enum class ChangeMode { NOTIFICATION, INDICATION }

private class OperationalBleManager(context: Context) : BleManager(context) {
    interface Listener {
        fun onDisconnected()
        fun onBondStateChanged(bondState: BleBondState)
    }

    @Volatile
    var listener: Listener? = null

    @Volatile
    private var characteristics = emptyMap<BleCharacteristicTarget, BluetoothGattCharacteristic>()

    @Volatile
    var services: List<BleGattService> = emptyList()
        private set

    val currentMtu: Int get() = getMtu()

    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) = Unit
            override fun onDeviceConnected(device: BluetoothDevice) = Unit
            // The connect request itself publishes the typed connection failure to its caller.
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit

            override fun onDeviceReady(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) =
                listener?.onDisconnected() ?: Unit
        })
        setBondingObserver(object : BondingObserver {
            override fun onBondingRequired(device: BluetoothDevice) {
                listener?.onBondStateChanged(BleBondState.BONDING)
            }

            override fun onBonded(device: BluetoothDevice) {
                listener?.onBondStateChanged(BleBondState.BONDED)
            }

            override fun onBondingFailed(device: BluetoothDevice) {
                listener?.onBondStateChanged(BleBondState.NOT_BONDED)
            }
        })
    }

    override fun getMinLogPriority(): Int = Log.ASSERT
    override fun log(priority: Int, message: String) = Unit
    override fun initialize() = Unit

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val found = mutableMapOf<BleCharacteristicTarget, BluetoothGattCharacteristic>()
        services = gatt.services.map { service ->
            service.toOperationalSdkService { target, characteristic ->
                found[target] = characteristic
            }
        }
        characteristics = found
        return true
    }

    override fun onServicesInvalidated() {
        characteristics = emptyMap()
        services = emptyList()
    }

    fun find(target: BleCharacteristicTarget): BluetoothGattCharacteristic? = characteristics[target]

    fun readOperationalCharacteristic(characteristic: BluetoothGattCharacteristic) =
        readCharacteristic(characteristic)

    fun writeOperationalCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ) = writeCharacteristic(characteristic, value, writeType)

    suspend fun requestOperationalMtu(mtu: Int, timeoutMillis: Long): Int =
        awaitMtuRequest(requireNotNull(requestMtu(mtu)), timeoutMillis)

    suspend fun readOperationalPhy(timeoutMillis: Long): Pair<Int, Int> =
        awaitPhyRequest(requireNotNull(readPhy()), timeoutMillis)

    private suspend fun awaitMtuRequest(request: MtuRequest, timeoutMillis: Long): Int =
        try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancelQueue() }
                    request
                        .with { _, mtu -> continuation.resumeIfActive(mtu) }
                        .fail { _, status ->
                            continuation.failIfActive(RequestFailedException(request, status))
                        }
                        .invalid {
                            continuation.failIfActive(InvalidRequestException(request))
                        }
                        .enqueue()
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw BleSessionException(
                BleSessionFailureCode.TIMEOUT,
                "Android BLE MTU negotiation timed out",
                error,
            )
        }

    private suspend fun awaitPhyRequest(request: PhyRequest, timeoutMillis: Long): Pair<Int, Int> =
        try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancelQueue() }
                    request
                        .with { _, txPhy, rxPhy ->
                            continuation.resumeIfActive(txPhy to rxPhy)
                        }
                        .fail { _, status ->
                            continuation.failIfActive(RequestFailedException(request, status))
                        }
                        .invalid {
                            continuation.failIfActive(InvalidRequestException(request))
                        }
                        .enqueue()
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw BleSessionException(
                BleSessionFailureCode.TIMEOUT,
                "Android BLE PHY read timed out",
                error,
            )
        }

    fun installChangeCallback(
        characteristic: BluetoothGattCharacteristic,
        mode: ChangeMode,
        onValue: (ByteArray) -> Unit,
    ) {
        val callback = when (mode) {
            ChangeMode.NOTIFICATION -> setNotificationCallback(characteristic)
            ChangeMode.INDICATION -> setIndicationCallback(characteristic)
        }
        callback.with { _, data -> onValue(data.value ?: byteArrayOf()) }
    }

    fun enableChanges(characteristic: BluetoothGattCharacteristic, mode: ChangeMode) = when (mode) {
        ChangeMode.NOTIFICATION -> enableNotifications(characteristic)
        ChangeMode.INDICATION -> enableIndications(characteristic)
    }

    fun disableChanges(characteristic: BluetoothGattCharacteristic, mode: ChangeMode) = when (mode) {
        ChangeMode.NOTIFICATION -> disableNotifications(characteristic)
        ChangeMode.INDICATION -> disableIndications(characteristic)
    }

    fun removeChangeCallback(characteristic: BluetoothGattCharacteristic, mode: ChangeMode) {
        when (mode) {
            ChangeMode.NOTIFICATION -> removeNotificationCallback(characteristic)
            ChangeMode.INDICATION -> removeIndicationCallback(characteristic)
        }
    }

    fun cancelPendingOperations() {
        cancelQueue()
    }
}

private fun BluetoothGattService.toOperationalSdkService(
    observe: (BleCharacteristicTarget, BluetoothGattCharacteristic) -> Unit,
): BleGattService {
    val serviceUuid = uuid.toString().lowercase()
    return BleGattService(
        uuid = serviceUuid,
        primary = type == BluetoothGattService.SERVICE_TYPE_PRIMARY,
        characteristics = characteristics.map { characteristic ->
            val characteristicUuid = characteristic.uuid.toString().lowercase()
            val target = BleCharacteristicTarget(serviceUuid, characteristicUuid)
            observe(target, characteristic)
            BleGattCharacteristic(
                serviceUuid = serviceUuid,
                uuid = characteristicUuid,
                properties = characteristic.properties.toOperationalProperties(),
                permissions = characteristic.permissions.toOperationalPermissions(),
                descriptors = characteristic.descriptors.map { descriptor ->
                    BleGattDescriptor(
                        uuid = descriptor.uuid.toString().lowercase(),
                        permissions = descriptor.permissions.toOperationalPermissions(),
                    )
                },
            )
        },
    )
}

private fun Int.toOperationalProperties(): Set<BleGattCharacteristicProperty> {
    val flags = this
    return buildSet {
        addOperationalFlag(flags, BluetoothGattCharacteristic.PROPERTY_BROADCAST, BleGattCharacteristicProperty.BROADCAST)
        addOperationalFlag(flags, BluetoothGattCharacteristic.PROPERTY_READ, BleGattCharacteristicProperty.READ)
        addOperationalFlag(
            flags,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BleGattCharacteristicProperty.WRITE_WITHOUT_RESPONSE,
        )
        addOperationalFlag(flags, BluetoothGattCharacteristic.PROPERTY_WRITE, BleGattCharacteristicProperty.WRITE)
        addOperationalFlag(flags, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BleGattCharacteristicProperty.NOTIFY)
        addOperationalFlag(flags, BluetoothGattCharacteristic.PROPERTY_INDICATE, BleGattCharacteristicProperty.INDICATE)
        addOperationalFlag(
            flags,
            BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE,
            BleGattCharacteristicProperty.SIGNED_WRITE,
        )
        addOperationalFlag(
            flags,
            BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS,
            BleGattCharacteristicProperty.EXTENDED,
        )
    }
}

private fun Int.toOperationalPermissions(): Set<BleGattAttributePermission> {
    val flags = this
    return buildSet {
        addOperationalFlag(flags, BluetoothGattCharacteristic.PERMISSION_READ, BleGattAttributePermission.READ)
        addOperationalFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            BleGattAttributePermission.READ_ENCRYPTED,
        )
        addOperationalFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM,
            BleGattAttributePermission.READ_ENCRYPTED_MITM,
        )
        addOperationalFlag(flags, BluetoothGattCharacteristic.PERMISSION_WRITE, BleGattAttributePermission.WRITE)
        addOperationalFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            BleGattAttributePermission.WRITE_ENCRYPTED,
        )
        addOperationalFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM,
            BleGattAttributePermission.WRITE_ENCRYPTED_MITM,
        )
        addOperationalFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED,
            BleGattAttributePermission.WRITE_SIGNED,
        )
        addOperationalFlag(
            flags,
            BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM,
            BleGattAttributePermission.WRITE_SIGNED_MITM,
        )
    }
}

private fun <T> MutableSet<T>.addOperationalFlag(flags: Int, flag: Int, value: T) {
    if (flags and flag != 0) add(value)
}

private fun <T> CancellableContinuation<T>.resumeIfActive(value: T) {
    if (isActive) runCatching { resumeWith(Result.success(value)) }
}

private fun <T> CancellableContinuation<T>.failIfActive(error: Throwable) {
    if (isActive) runCatching { resumeWith(Result.failure(error)) }
}

private fun BluetoothGattCharacteristic.changeModeOrNull(): ChangeMode? = when {
    properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> ChangeMode.NOTIFICATION
    properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> ChangeMode.INDICATION
    else -> null
}

private fun attributeMissing(target: BleCharacteristicTarget) = BleSessionException(
    BleSessionFailureCode.ATTRIBUTE_MISSING,
    "The requested characteristic was not discovered (${target.serviceUuid}/${target.characteristicUuid})",
)

internal fun mapNordicFailureStatus(status: Int): BleSessionFailureCode = when (status) {
    FailCallback.REASON_TIMEOUT -> BleSessionFailureCode.TIMEOUT
    FailCallback.REASON_DEVICE_DISCONNECTED -> BleSessionFailureCode.DISCONNECTED
    FailCallback.REASON_BLUETOOTH_DISABLED -> BleSessionFailureCode.BLUETOOTH_UNAVAILABLE
    FailCallback.REASON_NULL_ATTRIBUTE -> BleSessionFailureCode.ATTRIBUTE_MISSING
    FailCallback.REASON_DEVICE_NOT_SUPPORTED,
    FailCallback.REASON_NOT_ENABLED,
    FailCallback.REASON_UNSUPPORTED_CONFIGURATION,
    -> BleSessionFailureCode.OPERATION_NOT_SUPPORTED

    else -> BleSessionFailureCode.OPERATION_FAILED
}

private fun Throwable.toSessionException(
    defaultCode: BleSessionFailureCode,
    operation: String,
): BleSessionException = when (this) {
    is BleSessionException -> this
    is SecurityException -> BleSessionException(
        BleSessionFailureCode.PERMISSION_DENIED,
        "Android rejected BLE permission during $operation",
        this,
    )

    is BluetoothDisabledException -> BleSessionException(
        BleSessionFailureCode.BLUETOOTH_UNAVAILABLE,
        "Bluetooth became unavailable during $operation",
        this,
    )

    is DeviceDisconnectedException -> BleSessionException(
        BleSessionFailureCode.DISCONNECTED,
        "The BLE peripheral disconnected during $operation",
        this,
    )

    is RequestFailedException -> BleSessionException(
        mapNordicFailureStatus(status),
        "Android BLE $operation failed with platform status $status",
        this,
    )

    is InvalidRequestException -> BleSessionException(
        BleSessionFailureCode.OPERATION_NOT_SUPPORTED,
        "Android rejected the BLE $operation request",
        this,
    )

    else -> BleSessionException(
        defaultCode,
        "Android BLE $operation failed (${this::class.simpleName})",
        this,
    )
}

private fun Int.toOperationalPhy(): BlePhy = when (this) {
    BluetoothDevice.PHY_LE_1M -> BlePhy.LE_1M
    BluetoothDevice.PHY_LE_2M -> BlePhy.LE_2M
    BluetoothDevice.PHY_LE_CODED -> BlePhy.LE_CODED
    else -> BlePhy.UNKNOWN
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.toOperationalBondState(): BleBondState = when (bondState) {
    BluetoothDevice.BOND_NONE -> BleBondState.NOT_BONDED
    BluetoothDevice.BOND_BONDING -> BleBondState.BONDING
    BluetoothDevice.BOND_BONDED -> BleBondState.BONDED
    else -> BleBondState.UNKNOWN
}

private fun Context.hasBleConnectionPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
