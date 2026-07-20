package dev.gumi.devices.omicv1.updater.android

import io.runtime.mcumgr.ble.exception.McuMgrDisconnectedException
import io.runtime.mcumgr.exception.McuMgrTimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class AndroidOmiCv1ApplicationImage0SessionTest {
    @Test
    fun `disconnect before reset response remains outcome-unknown`() {
        assertFalse(resetResponseObservedAfterError(McuMgrDisconnectedException()))
    }

    @Test
    fun `non-disconnect reset failure is not reclassified as a reboot`() {
        val timeout = McuMgrTimeoutException()

        val observed = assertFailsWith<McuMgrTimeoutException> {
            resetResponseObservedAfterError(timeout)
        }

        assertSame(timeout, observed)
    }
}
