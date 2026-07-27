package dev.gumi.devices.omicv1.updater.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.gumi.edge.platforms.android.ble.AndroidBleEndpointDirectory
import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import io.runtime.mcumgr.McuMgrCallback
import io.runtime.mcumgr.ble.McuMgrBleTransport
import io.runtime.mcumgr.dfu.FirmwareUpgradeCallback
import io.runtime.mcumgr.dfu.FirmwareUpgradeController
import io.runtime.mcumgr.dfu.mcuboot.FirmwareUpgradeManager
import io.runtime.mcumgr.dfu.mcuboot.model.ImageSet
import io.runtime.mcumgr.dfu.mcuboot.model.TargetImage
import io.runtime.mcumgr.exception.McuMgrException
import io.runtime.mcumgr.managers.ImageManager
import io.runtime.mcumgr.response.img.McuMgrImageStateResponse
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@SuppressLint("MissingPermission")
internal class AndroidOmiCv1StockNormalizationSession(
    context: Context,
    private val endpoint: EndpointCandidate,
    device: BluetoothDevice,
) : OmiCv1StockNormalizationSession {
    private val applicationContext = context.applicationContext
    private val transport: McuMgrBleTransport
    private val imageManager: ImageManager
    private val activeUpgrade = AtomicReference<FirmwareUpgradeController?>()
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
            setInitialMtu(AndroidOmiCv1ApplicationImage0Session.UPDATE_ATT_MTU)
        }
        imageManager = ImageManager(transport)
    }

    override suspend fun inspect(): FirmwareImageStateInspection =
        suspendCancellableCoroutine { continuation ->
            imageManager.list(
                object : McuMgrCallback<McuMgrImageStateResponse> {
                    override fun onResponse(response: McuMgrImageStateResponse) {
                        if (!continuation.isActive) return
                        if (!response.isSuccess) {
                            continuation.resumeWithException(
                                OmiCv1ApplicationUpdateException(
                                    OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED,
                                    "MCU Manager rejected normalization image-state inspection " +
                                        "(${response.returnCode})",
                                ),
                            )
                        } else {
                            continuation.resume(response.toInspection(endpoint))
                        }
                    }

                    override fun onError(error: McuMgrException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                },
            )
            continuation.invokeOnCancellation { release() }
        }

    override suspend fun normalize(
        applicationBytes: ByteArray,
        networkBytes: ByteArray,
        onProgress: (OmiCv1StockNormalizationProgress) -> Unit,
    ) {
        val immutableApplication = applicationBytes.copyOf()
        val immutableNetwork = networkBytes.copyOf()
        suspendCancellableCoroutine { continuation ->
            val callback = object : FirmwareUpgradeCallback<FirmwareUpgradeManager.State> {
                override fun onUpgradeStarted(controller: FirmwareUpgradeController) {
                    if (continuation.isActive) {
                        activeUpgrade.set(controller)
                    } else {
                        controller.cancel()
                    }
                }

                override fun onStateChanged(
                    previous: FirmwareUpgradeManager.State,
                    current: FirmwareUpgradeManager.State,
                ) {
                    if (!continuation.isActive) return
                    if (current == FirmwareUpgradeManager.State.TEST) {
                        activeUpgrade.getAndSet(null)?.cancel()
                        continuation.resumeWithException(
                            OmiCv1ApplicationUpdateException(
                                OmiCv1ApplicationUpdateFailureCode.STOCK_NORMALIZATION_FAILED,
                                "Overwrite-only stock normalization unexpectedly entered " +
                                    "MCUboot test mode",
                            ),
                        )
                        return
                    }
                    current.toNormalizationStage()?.let { stage ->
                        onProgress(OmiCv1StockNormalizationProgress(stage))
                    }
                }

                override fun onUpgradeCompleted() {
                    activeUpgrade.set(null)
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onUpgradeFailed(
                    state: FirmwareUpgradeManager.State,
                    error: McuMgrException,
                ) {
                    activeUpgrade.set(null)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onUpgradeCanceled(state: FirmwareUpgradeManager.State) {
                    activeUpgrade.set(null)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            CancellationException("Stock normalization canceled"),
                        )
                    }
                }

                override fun onUploadProgressChanged(
                    bytesSent: Int,
                    imageSize: Int,
                    timestamp: Long,
                ) {
                    if (continuation.isActive) {
                        onProgress(
                            OmiCv1StockNormalizationProgress(
                                OmiCv1StockNormalizationStage.UPLOADING,
                                bytesSent,
                                imageSize,
                            ),
                        )
                    }
                }
            }
            val manager = FirmwareUpgradeManager(transport, callback).apply {
                setCallbackOnUiThread(false)
                setMode(FirmwareUpgradeManager.Mode.CONFIRM_ONLY)
            }
            val images = ImageSet()
                .add(TargetImage(OmiCv1StockNormalizationPlanner.APPLICATION_IMAGE_NUMBER, immutableApplication))
                .add(TargetImage(OmiCv1StockNormalizationPlanner.NETWORK_IMAGE_NUMBER, immutableNetwork))
            val settings = FirmwareUpgradeManager.Settings.Builder()
                .setEstimatedSwapTime(0)
                .setWindowCapacity(1)
                .setEraseAppSettings(false)
                .build()
            continuation.invokeOnCancellation { activeUpgrade.getAndSet(null)?.cancel() }
            if (continuation.isActive) {
                manager.start(images, settings)
            } else {
                activeUpgrade.getAndSet(null)?.cancel()
            }
        }
    }

    override fun cancel() {
        activeUpgrade.getAndSet(null)?.cancel()
    }

    override fun release() {
        if (released.compareAndSet(false, true)) {
            activeUpgrade.getAndSet(null)?.cancel()
            transport.release()
        }
    }
}

internal class AndroidOmiCv1StockNormalizationSessionFactory(
    context: Context,
    private val directory: AndroidBleEndpointDirectory,
) : OmiCv1StockNormalizationSessionFactory {
    private val applicationContext = context.applicationContext

    override fun open(endpoint: EndpointCandidate): OmiCv1StockNormalizationSession {
        val device = directory.resolve(endpoint) ?: throw OmiCv1ApplicationUpdateException(
            OmiCv1ApplicationUpdateFailureCode.ENDPOINT_EXPIRED,
            "The process-local Omi endpoint expired; stop and scan again",
        )
        return AndroidOmiCv1StockNormalizationSession(applicationContext, endpoint, device)
    }
}

private fun FirmwareUpgradeManager.State.toNormalizationStage():
    OmiCv1StockNormalizationStage? = when (this) {
    FirmwareUpgradeManager.State.NONE -> null
    FirmwareUpgradeManager.State.VALIDATE ->
        OmiCv1StockNormalizationStage.VALIDATING_REMOTE_STATE

    FirmwareUpgradeManager.State.UPLOAD -> OmiCv1StockNormalizationStage.UPLOADING
    FirmwareUpgradeManager.State.CONFIRM -> OmiCv1StockNormalizationStage.CONFIRMING
    FirmwareUpgradeManager.State.RESET -> OmiCv1StockNormalizationStage.REQUESTING_REBOOT
    FirmwareUpgradeManager.State.TEST -> null
}
