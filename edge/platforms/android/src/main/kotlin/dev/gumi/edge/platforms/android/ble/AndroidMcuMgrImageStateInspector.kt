package dev.gumi.edge.platforms.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.TransportKind
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspectionException
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspectionFailureCode
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspector
import dev.gumi.edge.sdk.firmware.FirmwareImageStateReadDisclosure
import dev.gumi.edge.sdk.firmware.FirmwareProtocolReadRequest
import io.runtime.mcumgr.McuMgrCallback
import io.runtime.mcumgr.ble.McuMgrBleTransport
import io.runtime.mcumgr.exception.McuMgrException
import io.runtime.mcumgr.managers.ImageManager
import io.runtime.mcumgr.response.img.McuMgrImageStateResponse
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A deliberately narrow adapter around Nordic Device Manager. It creates an ImageManager only long
 * enough to issue list(), then releases the transport. No updater or other MCU Manager is exposed.
 */
class AndroidMcuMgrImageStateInspector(
    context: Context,
    private val endpointDirectory: AndroidBleEndpointDirectory,
) : FirmwareImageStateInspector {
    private val applicationContext = context.applicationContext

    override val disclosure: FirmwareImageStateReadDisclosure = READ_DISCLOSURE

    @SuppressLint("MissingPermission")
    override suspend fun inspect(endpoint: EndpointCandidate): FirmwareImageStateInspection {
        require(endpoint.transport == TransportKind.BLE) {
            "Android MCU Manager inspection requires a BLE endpoint"
        }
        if (!applicationContext.hasMcuMgrConnectionPermission()) {
            throw FirmwareImageStateInspectionException(
                FirmwareImageStateInspectionFailureCode.PERMISSION_DENIED,
                "Android Nearby Devices permission is required",
            )
        }
        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            throw FirmwareImageStateInspectionException(
                FirmwareImageStateInspectionFailureCode.BLUETOOTH_UNAVAILABLE,
                "Bluetooth is unavailable or disabled",
            )
        }
        val device = endpointDirectory.resolve(endpoint.ephemeralId)
            ?: throw FirmwareImageStateInspectionException(
                FirmwareImageStateInspectionFailureCode.ENDPOINT_EXPIRED,
                "The ephemeral BLE endpoint expired; scan again",
            )

        val transport = McuMgrBleTransport(applicationContext, device).apply {
            setLoggingEnabled(false)
            // Preserve the already observed baseline MTU. The library still emits an ATT MTU
            // request, but requests the default value rather than silently negotiating 498.
            setInitialMtu(BASELINE_ATT_MTU)
        }
        val releaseTransport = RunOnce(transport::release)
        return try {
            val response = ImageManager(transport).readImageState(releaseTransport::run)
            response.toInspection(endpoint)
        } catch (error: CancellationException) {
            throw error
        } catch (error: FirmwareImageStateInspectionException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw FirmwareImageStateInspectionException(
                FirmwareImageStateInspectionFailureCode.MALFORMED_RESPONSE,
                "MCU Manager returned malformed image state",
                error,
            )
        } catch (error: Exception) {
            throw FirmwareImageStateInspectionException(
                FirmwareImageStateInspectionFailureCode.TRANSPORT_FAILED,
                "MCU Manager image-state read failed (${error::class.simpleName})",
                error,
            )
        } finally {
            releaseTransport.run()
        }
    }

    companion object {
        const val BASELINE_ATT_MTU = 23

        val READ_DISCLOSURE = FirmwareImageStateReadDisclosure(
            protocol = "mcumgr-smp",
            requestedAttMtu = BASELINE_ATT_MTU,
            writesRequestCharacteristic = true,
            writesNotificationDescriptor = true,
            protocolReads = listOf(
                FirmwareProtocolReadRequest(
                    groupId = 0,
                    commandId = 6,
                    label = "MCU Manager parameters",
                ),
                FirmwareProtocolReadRequest(
                    groupId = 1,
                    commandId = 0,
                    label = "MCUboot image state",
                ),
            ),
            persistentDeviceMutationExpected = false,
        )
    }
}

private suspend fun ImageManager.readImageState(onCancellation: () -> Unit): McuMgrImageStateResponse =
    awaitMcuMgrImageState(
        start = { callback -> list(callback) },
        onCancellation = onCancellation,
    )

internal suspend fun awaitMcuMgrImageState(
    start: (McuMgrCallback<McuMgrImageStateResponse>) -> Unit,
    onCancellation: () -> Unit,
): McuMgrImageStateResponse = suspendCancellableCoroutine { continuation ->
    start(
        object : McuMgrCallback<McuMgrImageStateResponse> {
            override fun onResponse(response: McuMgrImageStateResponse) {
                if (continuation.isActive) continuation.resume(response)
            }

            override fun onError(error: McuMgrException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        },
    )
    continuation.invokeOnCancellation { onCancellation() }
}

internal fun McuMgrImageStateResponse.toInspection(endpoint: EndpointCandidate) =
    FirmwareImageStateInspection(
        endpoint = endpoint,
        protocol = "mcumgr-smp",
        slots = images.orEmpty().map { image ->
            FirmwareImageSlot(
                imageNumber = image.image,
                slotNumber = image.slot,
                version = image.version?.takeIf(String::isNotBlank),
                hash = image.hash?.takeIf(ByteArray::isNotEmpty)?.let(FirmwareImageHash::copyOf),
                bootable = image.bootable,
                pending = image.pending,
                confirmed = image.confirmed,
                active = image.active,
                permanent = image.permanent,
                compressed = image.compressed,
            )
        },
        splitStatus = splitStatus,
    )

private fun Context.hasMcuMgrConnectionPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

internal class RunOnce(private val action: () -> Unit) {
    private val ran = AtomicBoolean(false)

    fun run() {
        if (ran.compareAndSet(false, true)) action()
    }
}
