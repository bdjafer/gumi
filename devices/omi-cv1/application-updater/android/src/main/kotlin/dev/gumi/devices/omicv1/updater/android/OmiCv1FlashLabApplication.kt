package dev.gumi.devices.omicv1.updater.android

import android.app.Application
import android.os.SystemClock
import dev.gumi.edge.platforms.android.ble.AndroidBleEndpointDirectory
import dev.gumi.edge.platforms.android.ble.AndroidBleGattInspector

class OmiCv1FlashLabApplication : Application() {
    internal lateinit var controller: OmiCv1FlashLabController
        private set

    override fun onCreate() {
        super.onCreate()
        val directory = AndroidBleEndpointDirectory()
        val captureSelftest = AndroidOmiCv1CaptureSelftestProbe(this, directory)
        controller = OmiCv1FlashLabController(
            scanner = AndroidOmiCv1FlashLabScanner(this, directory),
            sessions = AndroidOmiCv1ApplicationImage0SessionFactory(this, directory),
            devicePreflight =
                AndroidOmiCv1FlashLabDevicePreflightProbe(
                    AndroidBleGattInspector(this, directory),
                ),
            normalizationSessions =
                AndroidOmiCv1StockNormalizationSessionFactory(this, directory),
            normalizationArtifacts = AndroidOmiCv1StockNormalizationArtifactSource(this),
            recoveryStatus = AndroidOmiCv1RecoveryStatusProbe(this, directory),
            recordingRootProvisionerStatus =
                AndroidOmiCv1RecordingRootProvisionerStatusProbe(this, directory),
            legacyStorageReclaimerStatus =
                AndroidOmiCv1LegacyStorageReclaimerStatusProbe(this, directory),
            functionalStatus = AndroidOmiCv1FunctionalStatusProbe(this, directory),
            captureSelftest = captureSelftest,
            captureSelftestRunner = captureSelftest,
            artifacts = AndroidOmiCv1FlashLabArtifactSource(this),
            phonePower = AndroidOmiCv1FlashLabPhonePowerSource(this),
            clock = MonotonicMillisClock(SystemClock::elapsedRealtime),
        )
    }
}
