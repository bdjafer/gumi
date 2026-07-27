package dev.gumi.devices.omicv1.updater.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OmiCv1FlashLabPowerTest {
    @Test
    fun `firmware update requires the exact qualified phone battery floor`() {
        assertFalse(OmiCv1FlashLabPhonePower(percent = 59, charging = true).adequateForUpdate)
        assertTrue(OmiCv1FlashLabPhonePower(percent = 60, charging = false).adequateForUpdate)
    }

    @Test
    fun `unknown phone battery is never adequate`() {
        val unknown = OmiCv1FlashLabPhonePower(percent = null, charging = true)

        assertFalse(unknown.adequateForUpdate)
        assertFalse(unknown.adequateForReadOnlyCapture)
    }
}
