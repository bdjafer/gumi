package dev.gumi.edge.sdk.ble

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.OpaqueBytes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

data class BleConnectionOptions(
    val timeoutMillis: Long = 15_000,
    val requestedMtu: Int? = null,
) {
    init {
        require(timeoutMillis in 1_000..60_000)
        require(requestedMtu == null || requestedMtu in 23..517)
    }
}

data class BleCharacteristicTarget(
    val serviceUuid: String,
    val characteristicUuid: String,
) {
    init {
        requireCanonicalBleUuid(serviceUuid)
        requireCanonicalBleUuid(characteristicUuid)
    }

    fun asReadTarget(): BleGattReadTarget = BleGattReadTarget(serviceUuid, characteristicUuid)
}

enum class BleWriteKind {
    WITH_RESPONSE,
    WITHOUT_RESPONSE,
}

enum class BleSessionFailureCode {
    ENDPOINT_EXPIRED,
    PERMISSION_DENIED,
    BLUETOOTH_UNAVAILABLE,
    CONNECTION_FAILED,
    DISCONNECTED,
    CLOSED,
    TIMEOUT,
    ATTRIBUTE_MISSING,
    OPERATION_NOT_SUPPORTED,
    OPERATION_FAILED,
    EVENT_OVERFLOW,
}

class BleSessionException(
    val code: BleSessionFailureCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

sealed interface BleSessionEvent {
    data class LinkChanged(val link: BleLinkSnapshot) : BleSessionEvent

    data class NotificationsDropped(
        val target: BleCharacteristicTarget,
        val count: UInt,
    ) : BleSessionEvent

    data class Fault(
        val code: BleSessionFailureCode,
        val detail: String,
    ) : BleSessionEvent

    data object Disconnected : BleSessionEvent
    data object Closed : BleSessionEvent
}

data class BleNotification(
    val target: BleCharacteristicTarget,
    /** Monotonic within this target subscription; a gap means at least one observation was dropped. */
    val ordinal: ULong,
    val receivedAtMonotonicMillis: Long,
    val value: OpaqueBytes,
) {
    init {
        require(receivedAtMonotonicMillis >= 0)
    }
}

interface BleSubscription {
    val target: BleCharacteristicTarget

    /**
     * A bounded, single-consumer stream. The runtime owns its sole collector and projects state to
     * any number of UI/API consumers; it must not fan multiple collectors directly onto this flow.
     */
    val notifications: Flow<BleNotification>

    /** Idempotently disables this subscription and releases its bounded buffer. */
    suspend fun close()
}

/**
 * Bounded channel-backed stream with one lifetime collector and first-terminal-wins semantics.
 *
 * BLE callbacks cannot suspend. Producers therefore get an explicit false result on overflow. A
 * terminal value evicts older buffered observations when necessary, is inserted exactly once, and
 * closes the stream immediately after it. This utility is shared by platform adapters and protocol
 * simulators so their lifecycle behavior cannot silently diverge.
 */
class BoundedSingleConsumerStream<T>(capacity: Int) {
    private val channel = Channel<T>(capacity)
    private val claimed = MutableStateFlow(false)
    private val terminated = MutableStateFlow(false)

    init {
        require(capacity > 0) { "Bounded stream capacity must be positive" }
    }

    val flow: Flow<T> = flow {
        if (!claimed.compareAndSet(expect = false, update = true)) {
            throw BleSessionException(
                BleSessionFailureCode.OPERATION_FAILED,
                "A bounded BLE stream permits one lifetime collector",
            )
        }
        emitAll(channel.receiveAsFlow())
    }

    fun tryEmit(value: T): Boolean = !terminated.value && channel.trySend(value).isSuccess

    /** Returns false when another terminal outcome already won. */
    fun finishWith(terminal: T): Boolean {
        if (!terminated.compareAndSet(expect = false, update = true)) return false
        while (true) {
            val result = channel.trySend(terminal)
            if (result.isSuccess || result.isClosed) break
            if (channel.tryReceive().isFailure) break
        }
        channel.close()
        return true
    }

    fun finish(): Boolean {
        if (!terminated.compareAndSet(expect = false, update = true)) return false
        channel.close()
        return true
    }
}

/**
 * One connected BLE GATT session. Implementations serialize all ATT operations behind one owner,
 * apply their own timeouts, bound every event buffer, and reject every operation after close.
 */
interface BleTransportSession : dev.gumi.edge.sdk.TransportSession {
    val link: BleLinkSnapshot

    /** Bounded, single-consumer lifecycle/diagnostic stream owned by the device runtime. */
    val bleEvents: Flow<BleSessionEvent>

    suspend fun discoverServices(): List<BleGattService>

    suspend fun read(target: BleCharacteristicTarget): OpaqueBytes

    suspend fun write(
        target: BleCharacteristicTarget,
        value: OpaqueBytes,
        kind: BleWriteKind = BleWriteKind.WITH_RESPONSE,
    )

    suspend fun subscribe(target: BleCharacteristicTarget): BleSubscription
}

interface BleCentral {
    /** Opens one cancellable connection; the returned session exclusively owns its ATT queue. */
    suspend fun connect(
        endpoint: EndpointCandidate,
        options: BleConnectionOptions = BleConnectionOptions(),
    ): BleTransportSession
}
