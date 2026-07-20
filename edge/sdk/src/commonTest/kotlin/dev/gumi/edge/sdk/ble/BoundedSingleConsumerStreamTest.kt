package dev.gumi.edge.sdk.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class BoundedSingleConsumerStreamTest {
    @Test
    fun `terminal evicts an older observation and cannot be replaced`() = runTest {
        val stream = BoundedSingleConsumerStream<String>(capacity = 1)

        assertTrue(stream.tryEmit("ordinary"))
        assertTrue(stream.finishWith("disconnected"))
        assertFalse(stream.finishWith("closed"))
        assertFalse(stream.tryEmit("late"))
        assertEquals(listOf("disconnected"), stream.flow.toList())
    }

    @Test
    fun `one lifetime collector owns the stream`() = runTest {
        val stream = BoundedSingleConsumerStream<String>(capacity = 1)
        stream.finishWith("closed")
        assertEquals(listOf("closed"), stream.flow.toList())

        val error = assertFailsWith<BleSessionException> { stream.flow.toList() }
        assertEquals(BleSessionFailureCode.OPERATION_FAILED, error.code)
    }
}
