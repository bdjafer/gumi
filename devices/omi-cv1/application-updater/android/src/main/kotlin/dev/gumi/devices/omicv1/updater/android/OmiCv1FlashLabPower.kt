package dev.gumi.devices.omicv1.updater.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

internal data class OmiCv1FlashLabPhonePower(
    val percent: Int?,
    val charging: Boolean,
) {
    val adequateForUpdate: Boolean get() = percent != null && percent >= MIN_PHONE_BATTERY_PERCENT
}

internal fun interface OmiCv1FlashLabPhonePowerSource {
    fun read(): OmiCv1FlashLabPhonePower
}

internal class AndroidOmiCv1FlashLabPhonePowerSource(context: Context) :
    OmiCv1FlashLabPhonePowerSource {
    private val applicationContext = context.applicationContext

    override fun read(): OmiCv1FlashLabPhonePower {
        val battery = applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return OmiCv1FlashLabPhonePower(percent, charging)
    }
}

internal const val MIN_PHONE_BATTERY_PERCENT = 80
