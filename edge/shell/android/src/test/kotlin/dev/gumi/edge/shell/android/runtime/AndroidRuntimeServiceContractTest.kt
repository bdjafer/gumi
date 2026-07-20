package dev.gumi.edge.shell.android.runtime

import dev.gumi.edge.runtime.host.RuntimeHostRequest
import dev.gumi.edge.runtime.host.RuntimeHostStartOrigin
import dev.gumi.edge.runtime.host.RuntimeHostStopOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidRuntimeServiceContractTest {
    @Test
    fun `every accepted action maps to one exact portable request origin`() {
        val cases = listOf(
            AndroidRuntimeServiceContract.ACTION_START_EXPLICIT to
                RuntimeHostStartOrigin.EXPLICIT_USER,
            AndroidRuntimeServiceContract.ACTION_START_AUTOMATIC_PRESENCE to
                RuntimeHostStartOrigin.AUTOMATIC_PRESENCE,
            AndroidRuntimeServiceContract.ACTION_START_AUTOMATIC_RECOVERY to
                RuntimeHostStartOrigin.AUTOMATIC_RECOVERY,
        )
        cases.forEachIndexed { index, (action, origin) ->
            val decoded = valid(action, index)
            assertEquals(origin, assertIs<RuntimeHostRequest.Start>(decoded.request).origin)
        }

        val stops = listOf(
            AndroidRuntimeServiceContract.ACTION_STOP_EXPLICIT to
                RuntimeHostStopOrigin.EXPLICIT_USER,
            AndroidRuntimeServiceContract.ACTION_STOP_PREREQUISITE_LOST to
                RuntimeHostStopOrigin.PREREQUISITE_LOST,
        )
        stops.forEachIndexed { index, (action, origin) ->
            val decoded = valid(action, index + cases.size)
            assertEquals(origin, assertIs<RuntimeHostRequest.Stop>(decoded.request).origin)
        }
    }

    @Test
    fun `unknown null and malformed deliveries fail closed with stable redacted codes`() {
        assertInvalid(null, "command", "correlation", "ANDROID_RUNTIME_INTENT_ACTION_INVALID")
        assertInvalid("unknown", "command", "correlation", "ANDROID_RUNTIME_INTENT_ACTION_INVALID")
        assertInvalid(
            AndroidRuntimeServiceContract.ACTION_START_EXPLICIT,
            " bad ",
            "correlation",
            "ANDROID_RUNTIME_COMMAND_ID_INVALID",
        )
        assertInvalid(
            AndroidRuntimeServiceContract.ACTION_STOP_EXPLICIT,
            "command",
            "",
            "ANDROID_RUNTIME_CORRELATION_ID_INVALID",
        )
    }

    @Test
    fun `platform launch refusals map to stable permission policy and unknown outcomes`() {
        assertEquals(
            "ANDROID_RUNTIME_SERVICE_LAUNCH_SECURITY_DENIED",
            assertIs<AndroidRuntimeServiceLaunchResult.Rejected>(
                mapAndroidRuntimeLaunchFailure(SecurityException(), foreground = true),
            ).failure.code.value,
        )
        assertEquals(
            "ANDROID_RUNTIME_FOREGROUND_START_NOT_ALLOWED",
            assertIs<AndroidRuntimeServiceLaunchResult.Rejected>(
                mapAndroidRuntimeLaunchFailure(IllegalStateException(), foreground = true),
            ).failure.code.value,
        )
        assertEquals(
            "ANDROID_RUNTIME_STOP_DELIVERY_NOT_ALLOWED",
            assertIs<AndroidRuntimeServiceLaunchResult.Rejected>(
                mapAndroidRuntimeLaunchFailure(IllegalStateException(), foreground = false),
            ).failure.code.value,
        )
        assertEquals(
            "ANDROID_RUNTIME_SERVICE_LAUNCH_OUTCOME_UNKNOWN",
            assertIs<AndroidRuntimeServiceLaunchResult.OutcomeUnknown>(
                mapAndroidRuntimeLaunchFailure(RuntimeException(), foreground = true),
            ).failure.code.value,
        )
    }

    private fun valid(action: String, index: Int): AndroidRuntimeCommandDecodeResult.Valid =
        assertIs(
            AndroidRuntimeServiceContract.decode(
                action,
                "command-$index",
                "correlation-$index",
            ),
        )

    private fun assertInvalid(
        action: String?,
        commandId: String?,
        correlationId: String?,
        code: String,
    ) {
        val invalid = assertIs<AndroidRuntimeCommandDecodeResult.Invalid>(
            AndroidRuntimeServiceContract.decode(action, commandId, correlationId),
        )
        assertEquals(code, invalid.failure.code.value)
        assertEquals(emptyMap(), invalid.failure.redactedEvidence)
    }
}
