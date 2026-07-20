package dev.gumi.edge.shell.android.runtime

import dev.gumi.edge.runtime.host.RuntimeHostExecutionState
import dev.gumi.edge.runtime.host.RuntimeHostForegroundStartResult
import dev.gumi.edge.runtime.host.RuntimeHostForegroundStopResult
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.host.RuntimeHostProjection
import dev.gumi.edge.runtime.host.RuntimeHostTransportState
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.FailureCategory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class AndroidRuntimeForegroundBridgeTest {
    @Test
    fun `prompt bootstrap is claimed by the exact operation and released once`() = runTest {
        val bridge = AndroidRuntimeForegroundBridge()
        val endpoint = FakeEndpoint()
        val operation = operation("exact")

        assertIs<AndroidPlatformForegroundStartResult.Entered>(
            bridge.bootstrap(operation.commandId, operation.correlationId, endpoint),
        )
        assertIs<RuntimeHostForegroundStartResult.Entered>(bridge.enterForeground(operation))
        assertIs<AndroidRuntimeForegroundRefreshResult.Refreshed>(
            bridge.refresh(
                RuntimeHostProjection(
                    execution = RuntimeHostExecutionState.FOREGROUND,
                    transport = RuntimeHostTransportState.READY,
                ),
                operation.correlationId,
                endpoint,
            ),
        )
        assertIs<RuntimeHostForegroundStopResult.Released>(bridge.leaveForeground(operation))

        assertEquals(2, endpoint.entries.size)
        assertEquals(1, endpoint.leaves.size)
        assertFalse(bridge.mayNeedService())
    }

    @Test
    fun `known bootstrap refusal is a definitive typed host denial`() = runTest {
        val denied = androidRuntimeFailure(
            FailureCategory.PERMISSION,
            "ANDROID_TEST_PERMISSION_DENIED",
            retryable = false,
        )
        val bridge = AndroidRuntimeForegroundBridge()
        val endpoint = FakeEndpoint(startResults = ArrayDeque(listOf(
            AndroidPlatformForegroundStartResult.Denied(denied),
        )))
        val operation = operation("denied")

        bridge.bootstrap(operation.commandId, operation.correlationId, endpoint)
        val result = assertIs<RuntimeHostForegroundStartResult.Denied>(
            bridge.enterForeground(operation),
        )

        assertEquals("ANDROID_TEST_PERMISSION_DENIED", result.failure.code.value)
        assertEquals(operation.correlationId, result.failure.correlationId)
        assertFalse(bridge.mayNeedService())
    }

    @Test
    fun `destroyed service endpoint makes a held release outcome unknown`() = runTest {
        val bridge = AndroidRuntimeForegroundBridge()
        val endpoint = FakeEndpoint()
        val operation = operation("destroyed")
        bridge.bootstrap(operation.commandId, operation.correlationId, endpoint)
        bridge.enterForeground(operation)

        bridge.detach(endpoint)
        val result = assertIs<RuntimeHostForegroundStopResult.OutcomeUnknown>(
            bridge.leaveForeground(operation),
        )

        assertEquals("ANDROID_FOREGROUND_ENDPOINT_LOST", result.failure.code.value)
        assertEquals(0, endpoint.leaves.size)
    }

    @Test
    fun `endpoint loss before portable claim invalidates the provisional success`() = runTest {
        val bridge = AndroidRuntimeForegroundBridge()
        val endpoint = FakeEndpoint()
        val operation = operation("destroyed-before-claim")
        bridge.bootstrap(operation.commandId, operation.correlationId, endpoint)

        bridge.detach(endpoint)
        val result = assertIs<RuntimeHostForegroundStartResult.OutcomeUnknown>(
            bridge.enterForeground(operation),
        )

        assertEquals("ANDROID_FOREGROUND_ENDPOINT_LOST", result.failure.code.value)
        assertEquals(operation.correlationId, result.failure.correlationId)
    }

    @Test
    fun `abandoning a duplicate bootstrap cannot release the claimed host lease`() = runTest {
        val bridge = AndroidRuntimeForegroundBridge()
        val endpoint = FakeEndpoint()
        val claimed = operation("claimed")
        val duplicate = operation("duplicate")
        bridge.bootstrap(claimed.commandId, claimed.correlationId, endpoint)
        bridge.enterForeground(claimed)

        bridge.bootstrap(duplicate.commandId, duplicate.correlationId, endpoint)
        assertNull(bridge.abandon(duplicate.commandId, duplicate.correlationId))
        assertEquals(0, endpoint.leaves.size)

        bridge.leaveForeground(claimed)
        assertEquals(1, endpoint.leaves.size)
    }

    @Test
    fun `unclaimed provisional foreground is removed on abandonment`() {
        val bridge = AndroidRuntimeForegroundBridge()
        val endpoint = FakeEndpoint()
        val operation = operation("abandon")
        bridge.bootstrap(operation.commandId, operation.correlationId, endpoint)

        assertIs<AndroidPlatformForegroundStopResult.Released>(
            bridge.abandon(operation.commandId, operation.correlationId),
        )
        assertEquals(1, endpoint.leaves.size)
        assertFalse(bridge.mayNeedService())
    }

    @Test
    fun `service replacement must freshly promote before adopting an existing host lease`() = runTest {
        val bridge = AndroidRuntimeForegroundBridge()
        val first = FakeEndpoint()
        val initial = operation("initial-endpoint")
        bridge.bootstrap(initial.commandId, initial.correlationId, first)
        bridge.enterForeground(initial)
        bridge.detach(first)

        val replacement = FakeEndpoint()
        val replay = operation("replacement-endpoint")
        bridge.bootstrap(replay.commandId, replay.correlationId, replacement)
        assertEquals(1, replacement.entries.size)
        assertNull(bridge.acknowledgeAlreadyForeground(replay.commandId))

        bridge.leaveForeground(replay)
        assertEquals(1, replacement.leaves.size)
    }

    @Test
    fun `refresh requires the exact endpoint that owns the host lease`() = runTest {
        val bridge = AndroidRuntimeForegroundBridge()
        val owner = FakeEndpoint()
        val stale = FakeEndpoint()
        val operation = operation("refresh-owner")
        bridge.bootstrap(operation.commandId, operation.correlationId, owner)
        bridge.enterForeground(operation)

        val unavailable = assertIs<AndroidRuntimeForegroundRefreshResult.Unavailable>(
            bridge.refresh(
                RuntimeHostProjection(execution = RuntimeHostExecutionState.FOREGROUND),
                operation.correlationId,
                stale,
            ),
        )

        assertEquals("ANDROID_FOREGROUND_REFRESH_ENDPOINT_STALE", unavailable.failure.code.value)
        assertEquals(1, owner.entries.size)
        assertEquals(0, stale.entries.size)
        bridge.leaveForeground(operation)
    }
}

internal class FakeEndpoint(
    override val token: Long = nextToken++,
    private val startResults: ArrayDeque<AndroidPlatformForegroundStartResult> = ArrayDeque(),
    private val stopResults: ArrayDeque<AndroidPlatformForegroundStopResult> = ArrayDeque(),
) : AndroidRuntimeServiceEndpoint {
    val entries = mutableListOf<AndroidRuntimeNotificationState>()
    val leaves = mutableListOf<CorrelationId>()
    val settlements = mutableListOf<Pair<Int, Boolean>>()

    override fun enterForeground(
        state: AndroidRuntimeNotificationState,
        correlationId: CorrelationId,
    ): AndroidPlatformForegroundStartResult {
        entries += state
        return startResults.removeFirstOrNull() ?: AndroidPlatformForegroundStartResult.Entered
    }

    override fun leaveForeground(
        correlationId: CorrelationId,
    ): AndroidPlatformForegroundStopResult {
        leaves += correlationId
        return stopResults.removeFirstOrNull() ?: AndroidPlatformForegroundStopResult.Released
    }

    override fun commandSettled(startId: Int, stopService: Boolean) {
        settlements += startId to stopService
    }

    companion object {
        private var nextToken = 1L
    }
}

internal fun operation(suffix: String): RuntimeHostOperation = RuntimeHostOperation(
    commandId = CommandId("command-$suffix"),
    correlationId = CorrelationId("correlation-$suffix"),
    generation = 1uL,
)
