package dev.gumi.devices.omicv1.updater.android

import android.app.Application
import android.os.SystemClock

class OmiCv1FlashLabApplication : Application() {
    internal lateinit var controller: OmiCv1FlashLabController
        private set

    override fun onCreate() {
        super.onCreate()
        val directory = OmiCv1FlashLabEndpointDirectory()
        controller = OmiCv1FlashLabController(
            scanner = AndroidOmiCv1FlashLabScanner(this, directory),
            sessions = AndroidOmiCv1ApplicationImage0SessionFactory(this, directory),
            artifacts = AndroidOmiCv1FlashLabArtifactSource(this),
            phonePower = AndroidOmiCv1FlashLabPhonePowerSource(this),
            clock = MonotonicMillisClock(SystemClock::elapsedRealtime),
        )
    }
}
