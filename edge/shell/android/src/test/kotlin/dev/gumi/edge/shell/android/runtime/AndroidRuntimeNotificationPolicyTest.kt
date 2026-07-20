package dev.gumi.edge.shell.android.runtime

import dev.gumi.edge.runtime.host.RuntimeHostExecutionState
import dev.gumi.edge.runtime.host.RuntimeHostProjection
import dev.gumi.edge.runtime.host.RuntimeHostTransportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidRuntimeNotificationPolicyTest {
    @Test
    fun `notification categories are exact and never infer capture or backlog truth`() {
        assertEquals(
            "Connection: checking | capture: unverified | backlog: unavailable",
            androidRuntimeNotificationText(AndroidRuntimeNotificationState.Starting),
        )
        assertEquals(
            "Connection: ready | capture: unverified | backlog: unavailable",
            androidRuntimeNotificationText(
                AndroidRuntimeNotificationState.Running(
                    RuntimeHostProjection(
                        execution = RuntimeHostExecutionState.FOREGROUND,
                        transport = RuntimeHostTransportState.READY,
                    ),
                ),
            ),
        )
        assertEquals(
            "Connection: outcome unknown | capture: unverified | backlog: unavailable",
            androidRuntimeNotificationText(
                AndroidRuntimeNotificationState.Running(
                    RuntimeHostProjection(
                        execution = RuntimeHostExecutionState.OUTCOME_UNKNOWN,
                        transport = RuntimeHostTransportState.READY,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `notification policy contains no identity media or credential surface`() {
        val text = androidRuntimeNotificationText(AndroidRuntimeNotificationState.Starting)
            .lowercase()
        listOf("omi", "transcript", "credential", "address", "audio", "recording").forEach {
            assertFalse(it in text)
        }
    }

    @Test
    fun `android twelve and newer request immediate foreground visibility`() {
        assertFalse(androidRuntimeRequestsImmediateVisibility(30))
        assertTrue(androidRuntimeRequestsImmediateVisibility(31))
        assertTrue(androidRuntimeRequestsImmediateVisibility(37))
    }
}
