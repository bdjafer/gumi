package dev.gumi.devices.omicv1.updater.android

import io.runtime.mcumgr.McuMgrErrorCode
import io.runtime.mcumgr.ble.exception.McuMgrDisconnectedException
import io.runtime.mcumgr.exception.McuMgrErrorException
import io.runtime.mcumgr.exception.McuMgrTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun `generic MCU Manager rejection preserves code and upload offset`() {
        val source = McuMgrErrorException(McuMgrErrorCode.ACCESS_DENIED)

        val failure = image0UploadFailure(source, bytesReported = 0, totalBytes = 221_592)

        assertEquals(OmiCv1ApplicationUpdateFailureCode.TRANSPORT_FAILED, failure.code)
        assertSame(source, failure.cause)
        assertTrue(failure.message.orEmpty().contains("0/221592 bytes"))
        assertTrue(failure.message.orEmpty().contains("generic=ACCESS_DENIED/11"))
    }

    @Test
    fun `group MCU Manager rejection preserves group and return code`() {
        val groupCode = io.runtime.mcumgr.response.HasReturnCode.GroupReturnCode().apply {
            group = 1
            rc = 7
        }
        val source = McuMgrErrorException(groupCode)

        val failure = image0UploadFailure(source, bytesReported = 384, totalBytes = 221_592)

        assertSame(source, failure.cause)
        assertTrue(failure.message.orEmpty().contains("384/221592 bytes"))
        assertTrue(failure.message.orEmpty().contains("group=1/7"))
    }
}
