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
import dev.gumi.edge.platforms.android.ble.AndroidBleEndpointDirectory
import io.runtime.mcumgr.McuMgrCallback
import io.runtime.mcumgr.ble.McuMgrBleTransport
import io.runtime.mcumgr.ble.exception.McuMgrDisconnectedException
import io.runtime.mcumgr.exception.McuMgrErrorException
import io.runtime.mcumgr.exception.McuMgrException
import io.runtime.mcumgr.managers.DefaultManager
import io.runtime.mcumgr.managers.ImageManager
import io.runtime.mcumgr.response.McuMgrResponse
import io.runtime.mcumgr.response.dflt.McuMgrOsResponse
import io.runtime.mcumgr.response.img.McuMgrImageResponse
import io.runtime.mcumgr.response.img.McuMgrImageStateResponse
import io.runtime.mcumgr.response.img.McuMgrImageUploadResponse
import io.runtime.mcumgr.transfer.TransferController
import io.runtime.mcumgr.transfer.UploadCallback
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
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

    override suspend fun upload(
        imageBytes: ByteArray,
        mode: OmiCv1ApplicationUploadMode,
        onProgress: (Int, Int) -> Unit,
    ) {
        when (mode) {
            OmiCv1ApplicationUploadMode.STANDARD -> uploadStandard(imageBytes, onProgress)
            OmiCv1ApplicationUploadMode.INCOMPLETE_FLASH_BLOCK_RESCUE ->
                uploadIncompleteFlashBlockRescue(imageBytes, onProgress)
        }
    }

    private suspend fun uploadStandard(
        imageBytes: ByteArray,
        onProgress: (Int, Int) -> Unit,
    ) {
        val immutableBytes = imageBytes.copyOf()
        var maximumReportedBytes = 0
        suspendCancellableCoroutine { continuation ->
            val callback = object : UploadCallback {
                override fun onUploadProgressChanged(current: Int, total: Int, timestamp: Long) {
                    maximumReportedBytes = max(maximumReportedBytes, current)
                    if (continuation.isActive) onProgress(current, total)
                }

                override fun onUploadFailed(error: McuMgrException) {
                    activeUpload.set(null)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            image0UploadFailure(
                                error = error,
                                bytesReported = maximumReportedBytes,
                                totalBytes = immutableBytes.size,
                            ),
                        )
                    }
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

    private suspend fun uploadIncompleteFlashBlockRescue(
        imageBytes: ByteArray,
        onProgress: (Int, Int) -> Unit,
    ) {
        val rescue = OmiCv1IncompleteFlashBlockRescue.create(imageBytes)
        var offset = 0

        while (offset < rescue.stagedBytes.size) {
            val chunkSize = min(RESCUE_CHUNK_BYTES, rescue.stagedBytes.size - offset)
            val nextOffset = offset + chunkSize
            val payload = hashMapOf<String, Any>(
                "off" to offset,
                "data" to rescue.stagedBytes.copyOfRange(offset, nextOffset),
            )
            if (offset == 0) {
                payload["len"] = rescue.advertisedSizeBytes
                payload["sha"] = rescue.resumeShaPrefix
            }
            val response = awaitRescueUploadChunk(
                payload,
                if (offset == 0) RESCUE_FIRST_CHUNK_TIMEOUT_MILLIS
                else RESCUE_CHUNK_TIMEOUT_MILLIS,
            )
            requireSuccess(response)
            if (response.off != nextOffset) {
                throw OmiCv1ApplicationUpdateException(
                    OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED,
                    "Recovery rescue offset mismatch: expected $nextOffset, observed ${response.off}",
                )
            }
            offset = nextOffset
            onProgress(min(offset, imageBytes.size), imageBytes.size)
        }

        check(rescue.stagedBytes.size + 1 == rescue.advertisedSizeBytes) {
            "Recovery rescue must stop exactly one byte before MCUmgr completion"
        }
    }

    private suspend fun awaitRescueUploadChunk(
        payload: HashMap<String, Any>,
        timeoutMillis: Long,
    ): McuMgrImageUploadResponse = suspendCancellableCoroutine { continuation ->
        imageManager.send(
            MCUMGR_OP_WRITE,
            MCUMGR_IMAGE_UPLOAD_COMMAND,
            payload,
            timeoutMillis,
            McuMgrImageUploadResponse::class.java,
            object : McuMgrCallback<McuMgrImageUploadResponse> {
                override fun onResponse(response: McuMgrImageUploadResponse) {
                    if (continuation.isActive) continuation.resume(response)
                }

                override fun onError(error: McuMgrException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            },
        )
        continuation.invokeOnCancellation { release() }
    }

    override suspend fun inspect(): FirmwareImageStateInspection =
        awaitResponse<McuMgrImageStateResponse> { callback -> imageManager.list(callback) }
            .also(::requireSuccess)
            .toInspection(endpoint)

    override suspend fun eraseInactiveApplicationSlot() {
        try {
            awaitResponse<McuMgrImageResponse> { callback ->
                imageManager.erase(
                    OmiCv1ApplicationUpdatePlanner.SECONDARY_SLOT_NUMBER,
                    callback,
                )
            }.also(::requireSuccess)
        } catch (error: McuMgrErrorException) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED,
                "Inactive application slot erase failed (${error.mcuMgrDetail()})",
                error,
            )
        }
    }

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
        const val RESCUE_CHUNK_BYTES = 384
        const val RESCUE_FIRST_CHUNK_TIMEOUT_MILLIS = 40_000L
        const val RESCUE_CHUNK_TIMEOUT_MILLIS = 40_000L
        const val MCUMGR_OP_WRITE = 2
        const val MCUMGR_IMAGE_UPLOAD_COMMAND = 1
    }
}

internal fun image0UploadFailure(
    error: McuMgrException,
    bytesReported: Int,
    totalBytes: Int,
): OmiCv1ApplicationUpdateException {
    require(bytesReported >= 0)
    require(totalBytes > 0)
    require(bytesReported <= totalBytes)

    val detail = when (error) {
        is McuMgrErrorException -> {
            val groupCode = error.groupCode
            if (groupCode == null) {
                "generic=${error.code.name}/${error.code.value()}"
            } else {
                "generic=${error.code.name}/${error.code.value()}, " +
                    "group=${groupCode.group}/${groupCode.rc}"
            }
        }

        else -> {
            val safeMessage = error.message
                ?.replace(Regex("\\s+"), " ")
                ?.take(160)
                ?.takeIf(String::isNotBlank)
            if (safeMessage == null) {
                "exception=${error::class.simpleName ?: "McuMgrException"}"
            } else {
                "exception=${error::class.simpleName ?: "McuMgrException"}, detail=$safeMessage"
            }
        }
    }
    return OmiCv1ApplicationUpdateException(
        OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED,
        "MCU Manager image-0 upload failed at $bytesReported/$totalBytes bytes ($detail)",
        error,
    )
}

private fun McuMgrErrorException.mcuMgrDetail(): String {
    val groupCode = groupCode
    return if (groupCode == null) {
        "generic=${code.name}/${code.value()}"
    } else {
        "generic=${code.name}/${code.value()}, group=${groupCode.group}/${groupCode.rc}"
    }
}

internal class OmiCv1IncompleteFlashBlockRescue private constructor(
    val stagedBytes: ByteArray,
    val advertisedSizeBytes: Int,
    val resumeShaPrefix: ByteArray,
) {
    init {
        require(stagedBytes.isNotEmpty())
        require(stagedBytes.size % FLASH_IMG_BUFFER_BYTES == 0)
        require(advertisedSizeBytes == stagedBytes.size + 1)
        require(resumeShaPrefix.size == TRUNCATED_SHA256_BYTES)
    }

    companion object {
        const val FLASH_IMG_BUFFER_BYTES = 512
        const val TRUNCATED_SHA256_BYTES = 31
        private const val ERASED_FLASH_VALUE: Byte = -1

        fun create(imageBytes: ByteArray): OmiCv1IncompleteFlashBlockRescue {
            require(imageBytes.isNotEmpty()) { "Recovery rescue image must not be empty" }
            val alignmentBytes =
                (FLASH_IMG_BUFFER_BYTES - imageBytes.size % FLASH_IMG_BUFFER_BYTES) %
                    FLASH_IMG_BUFFER_BYTES
            val stagedBytes = imageBytes.copyOf(imageBytes.size + alignmentBytes)
            stagedBytes.fill(
                ERASED_FLASH_VALUE,
                fromIndex = imageBytes.size,
                toIndex = stagedBytes.size,
            )
            val resumeShaPrefix = MessageDigest
                .getInstance("SHA-256")
                .digest(imageBytes)
                .copyOf(TRUNCATED_SHA256_BYTES)
            return OmiCv1IncompleteFlashBlockRescue(
                stagedBytes = stagedBytes,
                advertisedSizeBytes = stagedBytes.size + 1,
                resumeShaPrefix = resumeShaPrefix,
            )
        }
    }
}

internal class AndroidOmiCv1ApplicationImage0SessionFactory(
    context: Context,
    private val directory: AndroidBleEndpointDirectory,
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

internal fun Context.hasMcuMgrConnectionPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
