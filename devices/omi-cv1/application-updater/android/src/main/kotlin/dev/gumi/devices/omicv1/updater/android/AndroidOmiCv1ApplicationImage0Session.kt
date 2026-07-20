package dev.gumi.devices.omicv1.updater.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import io.runtime.mcumgr.McuMgrCallback
import io.runtime.mcumgr.ble.McuMgrBleTransport
import io.runtime.mcumgr.ble.exception.McuMgrDisconnectedException
import io.runtime.mcumgr.exception.McuMgrException
import io.runtime.mcumgr.managers.DefaultManager
import io.runtime.mcumgr.managers.ImageManager
import io.runtime.mcumgr.response.McuMgrResponse
import io.runtime.mcumgr.response.dflt.McuMgrOsResponse
import io.runtime.mcumgr.response.img.McuMgrImageStateResponse
import io.runtime.mcumgr.transfer.TransferController
import io.runtime.mcumgr.transfer.UploadCallback
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The only reviewed mutating MCU Manager adapter. It is internal to an uncomposed Gradle module.
 * The caller must supply a device already bound to the prepared process-local endpoint.
 */
@SuppressLint("MissingPermission")
internal class AndroidOmiCv1ApplicationImage0Session(
    context: Context,
    private val endpoint: EndpointCandidate,
    device: BluetoothDevice,
) : OmiCv1ApplicationImage0UpdateSession {
    private val applicationContext = context.applicationContext
    private val transport: McuMgrBleTransport
    private val imageManager: ImageManager
    private val defaultManager: DefaultManager
    private val activeUpload = AtomicReference<TransferController?>()
    private val released = AtomicBoolean(false)

    init {
        if (!applicationContext.hasMcuMgrConnectionPermission()) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.PERMISSION_DENIED,
                "Android Nearby Devices permission is required",
            )
        }
        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.BLUETOOTH_UNAVAILABLE,
                "Bluetooth is unavailable or disabled",
            )
        }
        transport = McuMgrBleTransport(applicationContext, device).apply {
            setLoggingEnabled(false)
            setInitialMtu(UPDATE_ATT_MTU)
        }
        imageManager = ImageManager(transport)
        defaultManager = DefaultManager(transport)
    }

    override suspend fun upload(imageBytes: ByteArray, onProgress: (Int, Int) -> Unit) {
        val immutableBytes = imageBytes.copyOf()
        suspendCancellableCoroutine { continuation ->
            val callback = object : UploadCallback {
                override fun onUploadProgressChanged(current: Int, total: Int, timestamp: Long) {
                    if (continuation.isActive) onProgress(current, total)
                }

                override fun onUploadFailed(error: McuMgrException) {
                    activeUpload.set(null)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onUploadCanceled() {
                    activeUpload.set(null)
                    if (continuation.isActive) {
                        continuation.resumeWithException(CancellationException("Image-0 upload canceled"))
                    }
                }

                override fun onUploadCompleted() {
                    activeUpload.set(null)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            val controller = imageManager.imageUpload(
                immutableBytes,
                OmiCv1ApplicationUpdatePlanner.APPLICATION_IMAGE_NUMBER,
                callback,
            )
            activeUpload.set(controller)
            continuation.invokeOnCancellation { activeUpload.getAndSet(null)?.cancel() }
            if (!continuation.isActive) activeUpload.getAndSet(null)?.cancel()
        }
    }

    override suspend fun inspect(): FirmwareImageStateInspection =
        awaitResponse<McuMgrImageStateResponse> { callback -> imageManager.list(callback) }
            .also(::requireSuccess)
            .toInspection(endpoint)

    override suspend fun confirm(mcubootImageHash: FirmwareImageHash) {
        val response = awaitResponse<McuMgrImageStateResponse> { callback ->
            imageManager.confirm(mcubootImageHash.hex.hexToByteArray(), callback)
        }
        requireSuccess(response)
    }

    override suspend fun requestReset(): Boolean = suspendCancellableCoroutine { continuation ->
        defaultManager.reset(
            object : McuMgrCallback<McuMgrOsResponse> {
                override fun onResponse(response: McuMgrOsResponse) {
                    if (!continuation.isActive) return
                    try {
                        requireSuccess(response)
                        continuation.resume(true)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }

                override fun onError(error: McuMgrException) {
                    if (!continuation.isActive) return
                    try {
                        continuation.resume(resetResponseObservedAfterError(error))
                    } catch (failure: Exception) {
                        continuation.resumeWithException(failure)
                    }
                }
            },
        )
        continuation.invokeOnCancellation { release() }
    }

    override fun cancel() {
        activeUpload.getAndSet(null)?.cancel()
    }

    override fun release() {
        if (released.compareAndSet(false, true)) {
            activeUpload.getAndSet(null)?.cancel()
            transport.release()
        }
    }

    private suspend inline fun <reified R : McuMgrResponse> awaitResponse(
        crossinline start: (McuMgrCallback<R>) -> Unit,
    ): R = suspendCancellableCoroutine { continuation ->
        start(
            object : McuMgrCallback<R> {
                override fun onResponse(response: R) {
                    if (continuation.isActive) continuation.resume(response)
                }

                override fun onError(error: McuMgrException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            },
        )
        continuation.invokeOnCancellation { release() }
    }

    private fun requireSuccess(response: McuMgrResponse) {
        if (!response.isSuccess) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED,
                "MCU Manager rejected the image-0 operation (${response.returnCode})",
            )
        }
    }

    companion object {
        const val UPDATE_ATT_MTU = 498
    }
}

internal class AndroidOmiCv1ApplicationImage0SessionFactory(
    context: Context,
    private val directory: OmiCv1FlashLabEndpointDirectory,
) : OmiCv1ApplicationImage0UpdateSessionFactory {
    private val applicationContext = context.applicationContext

    override fun open(endpoint: EndpointCandidate): OmiCv1ApplicationImage0UpdateSession {
        val device = directory.resolve(endpoint) ?: throw OmiCv1ApplicationUpdateException(
            OmiCv1ApplicationUpdateFailureCode.ENDPOINT_EXPIRED,
            "The process-local Omi endpoint expired; stop and scan again",
        )
        return AndroidOmiCv1ApplicationImage0Session(applicationContext, endpoint, device)
    }
}

private fun McuMgrImageStateResponse.toInspection(endpoint: EndpointCandidate) =
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

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0 && matches(Regex("^[0-9a-f]+$"))) { "Expected lowercase even-length hex" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

/** A reset may disconnect before its response; only fresh post-reboot inspection can settle outcome. */
internal fun resetResponseObservedAfterError(error: McuMgrException): Boolean {
    if (error is McuMgrDisconnectedException) return false
    throw error
}

private fun Context.hasMcuMgrConnectionPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
