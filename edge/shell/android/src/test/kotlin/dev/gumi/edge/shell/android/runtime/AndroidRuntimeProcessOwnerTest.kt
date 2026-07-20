package dev.gumi.edge.shell.android.runtime

import dev.gumi.edge.runtime.host.RuntimeHost
import dev.gumi.edge.runtime.host.RuntimeHostAssociationState
import dev.gumi.edge.runtime.host.RuntimeHostCleanupRequest
import dev.gumi.edge.runtime.host.RuntimeHostCleanupResult
import dev.gumi.edge.runtime.host.RuntimeHostCommandOutcome
import dev.gumi.edge.runtime.host.RuntimeHostCommandRecord
import dev.gumi.edge.runtime.host.RuntimeHostCommandResult
import dev.gumi.edge.runtime.host.RuntimeHostExecutionState
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.runtime.host.RuntimeHostPermissionState
import dev.gumi.edge.runtime.host.RuntimeHostPrerequisitePort
import dev.gumi.edge.runtime.host.RuntimeHostPrerequisiteResult
import dev.gumi.edge.runtime.host.RuntimeHostPrerequisites
import dev.gumi.edge.runtime.host.RuntimeHostPresenceState
import dev.gumi.edge.runtime.host.RuntimeHostProjection
import dev.gumi.edge.runtime.host.RuntimeHostRecoveryPort
import dev.gumi.edge.runtime.host.RuntimeHostRehydrationResult
import dev.gumi.edge.runtime.host.RuntimeHostRequest
import dev.gumi.edge.runtime.host.RuntimeHostRestartPolicy
import dev.gumi.edge.runtime.host.RuntimeHostStartOrigin
import dev.gumi.edge.runtime.host.RuntimeHostStopOrigin
import dev.gumi.edge.runtime.host.RuntimeHostTransportState
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.FailureCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidRuntimeProcessOwnerTest {
    @Test
    fun `start user stop suppression and explicit restart keep one process host`() = runTest {
        val fixture = fixture()
        val endpoint = FakeEndpoint()

        assertIs<AndroidRuntimeAdmissionResult.Accepted>(
            fixture.owner.submit(start("initial"), 1, endpoint),
        )
        advanceUntilIdle()
        assertEquals(RuntimeHostExecutionState.FOREGROUND, fixture.owner.hostProjection.value.execution)
        assertEquals(listOf(1 to false), endpoint.settlements)

        assertIs<AndroidRuntimeAdmissionResult.Accepted>(
            fixture.owner.submit(stop("user", RuntimeHostStopOrigin.EXPLICIT_USER), 2, endpoint),
        )
        advanceUntilIdle()
        assertEquals(RuntimeHostExecutionState.STOPPED, fixture.owner.hostProjection.value.execution)
        assertEquals(
            RuntimeHostRestartPolicy.USER_STOPPED,
            fixture.owner.hostProjection.value.restartPolicy,
        )
        assertEquals(1, endpoint.leaves.size)

        val entriesBeforeAutomatic = endpoint.entries.size
        val automatic = assertIs<AndroidRuntimeAdmissionResult.Rejected>(
            fixture.owner.submit(
                start("automatic", RuntimeHostStartOrigin.AUTOMATIC_RECOVERY),
                3,
                endpoint,
            ),
        )
        assertEquals(
            "ANDROID_RUNTIME_AUTOSTART_SUPPRESSED_BY_USER_STOP",
            automatic.failure.code.value,
        )
        assertEquals(entriesBeforeAutomatic, endpoint.entries.size)

        assertIs<AndroidRuntimeAdmissionResult.Accepted>(
            fixture.owner.submit(start("restart"), 4, endpoint),
        )
        advanceUntilIdle()
        assertEquals(RuntimeHostExecutionState.FOREGROUND, fixture.owner.hostProjection.value.execution)
        assertEquals(
            RuntimeHostRestartPolicy.AUTOMATIC_ALLOWED,
            fixture.owner.hostProjection.value.restartPolicy,
        )

        fixture.owner.shutdown()
        assertEquals(AndroidRuntimeOwnerLifecycle.CLOSED, fixture.owner.projection.value.lifecycle)
        assertEquals(2, endpoint.leaves.size)
    }

    @Test
    fun `definitive bootstrap denial is rejected synchronously without an outstanding delivery`() =
        runTest {
            val failure = androidRuntimeFailure(
                FailureCategory.PERMISSION,
                "ANDROID_TEST_BOOTSTRAP_DENIED",
                retryable = false,
            )
            val fixture = fixture()
            val endpoint = FakeEndpoint(
                startResults = ArrayDeque(
                    listOf(AndroidPlatformForegroundStartResult.Denied(failure)),
                ),
            )

            val rejected = assertIs<AndroidRuntimeAdmissionResult.Rejected>(
                fixture.owner.submit(start("bootstrap-denied"), 8, endpoint),
            )

            assertEquals("ANDROID_TEST_BOOTSTRAP_DENIED", rejected.failure.code.value)
            assertFalse(rejected.serviceStillNeeded)
            assertEquals(0, fixture.owner.projection.value.outstandingDeliveries)
            assertEquals(RuntimeHostExecutionState.STOPPED, fixture.owner.hostProjection.value.execution)
            fixture.owner.shutdown()
        }

    @Test
    fun `invalid service delivery is retained as redacted owner evidence`() = runTest {
        val fixture = fixture()
        val failure = androidRuntimeFailure(
            FailureCategory.REJECTED_POLICY,
            "ANDROID_TEST_INVALID_DELIVERY",
            retryable = false,
        )

        assertFalse(fixture.owner.recordInvalidDelivery(failure))
        assertEquals(
            "ANDROID_TEST_INVALID_DELIVERY",
            fixture.owner.projection.value.lastFailure?.code?.value,
        )
        fixture.owner.shutdown()
    }

    @Test
    fun `capacity rejects another start while prerequisite and user stops share one barrier`() =
        runTest {
            val recoveryEntered = CompletableDeferred<Unit>()
            val recoveryGate = CompletableDeferred<Unit>()
            val fixture = fixture(
                deliveryCapacity = 1,
                rehydrate = { operation ->
                    recoveryEntered.complete(Unit)
                    withContext(NonCancellable) { recoveryGate.await() }
                    RuntimeHostRehydrationResult.Rehydrated(
                        operation,
                        RuntimeHostTransportState.READY,
                    )
                },
            )
            val endpoint = FakeEndpoint()

            assertIs<AndroidRuntimeAdmissionResult.Accepted>(
                fixture.owner.submit(start("blocked"), 11, endpoint),
            )
            runCurrent()
            recoveryEntered.await()

            val rejected = assertIs<AndroidRuntimeAdmissionResult.Rejected>(
                fixture.owner.submit(start("overflow"), 12, endpoint),
            )
            assertEquals(
                "ANDROID_RUNTIME_DELIVERY_CAPACITY_EXHAUSTED",
                rejected.failure.code.value,
            )
            assertTrue(rejected.serviceStillNeeded)
            assertEquals(1, endpoint.entries.size)

            // Stops bypass start pressure. The later explicit stop must still establish USER_STOPPED.
            assertIs<AndroidRuntimeAdmissionResult.Accepted>(
                fixture.owner.submit(
                    stop("lost-prerequisite", RuntimeHostStopOrigin.PREREQUISITE_LOST),
                    13,
                    endpoint,
                ),
            )
            assertIs<AndroidRuntimeAdmissionResult.Accepted>(
                fixture.owner.submit(
                    stop("user-after-pressure", RuntimeHostStopOrigin.EXPLICIT_USER),
                    14,
                    endpoint,
                ),
            )
            runCurrent()
            assertFalse(endpoint.settlements.any { it.first in setOf(13, 14) })

            recoveryGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(RuntimeHostExecutionState.STOPPED, fixture.owner.hostProjection.value.execution)
            assertEquals(
                RuntimeHostRestartPolicy.USER_STOPPED,
                fixture.owner.hostProjection.value.restartPolicy,
            )
            assertEquals(1, endpoint.leaves.size)
            assertEquals(setOf(11, 13, 14), endpoint.settlements.map { it.first }.toSet())
            assertEquals(false, endpoint.settlements.single { it.first == 11 }.second)
            assertEquals(false, endpoint.settlements.single { it.first == 13 }.second)
            assertEquals(true, endpoint.settlements.single { it.first == 14 }.second)
            assertIs<RuntimeHostCommandOutcome.Stopped>(
                fixture.owner.projection.value.lastResult?.record?.outcome,
            )

            fixture.owner.shutdown()
        }

    @Test
    fun `graceful shutdown stops before forced close and survives caller cancellation`() = runTest {
        val order = mutableListOf<String>()
        val stopEntered = CompletableDeferred<Unit>()
        val stopGate = CompletableDeferred<Unit>()
        val controller = RecordingHostController(order, stopEntered, stopGate)
        val resources = RecordingProcessResources(order)
        val owner = AndroidRuntimeProcessOwner(
            parentScope = this,
            host = controller,
            foreground = AndroidRuntimeForegroundBridge(),
            processResources = resources,
        )

        val shuttingDown = launch { owner.shutdown() }
        runCurrent()
        stopEntered.await()
        shuttingDown.cancel()
        stopGate.complete(Unit)
        shuttingDown.join()
        advanceUntilIdle()

        assertEquals(listOf("stop:OWNER_SHUTDOWN", "resources", "close"), order)
        assertEquals(1, resources.closeCount)
        assertEquals(AndroidRuntimeOwnerLifecycle.CLOSED, owner.projection.value.lifecycle)
        val rejected = assertIs<AndroidRuntimeAdmissionResult.Rejected>(
            owner.submit(start("after-close"), 21, FakeEndpoint()),
        )
        assertEquals("ANDROID_RUNTIME_OWNER_CLOSED", rejected.failure.code.value)
    }

    @Test
    fun `process resource uncertainty is retained while forced host teardown still completes`() =
        runTest {
            val order = mutableListOf<String>()
            val stopEntered = CompletableDeferred<Unit>()
            val stopGate = CompletableDeferred<Unit>().also { it.complete(Unit) }
            val controller = RecordingHostController(order, stopEntered, stopGate)
            val resourceFailure = androidRuntimeFailure(
                FailureCategory.INTERNAL,
                "ANDROID_TEST_PROCESS_RESOURCE_OUTCOME_UNKNOWN",
                retryable = false,
            )
            val owner = AndroidRuntimeProcessOwner(
                parentScope = this,
                host = controller,
                foreground = AndroidRuntimeForegroundBridge(),
                processResources = AndroidRuntimeProcessResources {
                    order += "resources-unknown"
                    AndroidRuntimeProcessResourceCloseResult.OutcomeUnknown(resourceFailure)
                },
            )

            owner.shutdown()

            assertEquals(
                listOf("stop:OWNER_SHUTDOWN", "resources-unknown", "close"),
                order,
            )
            assertEquals(AndroidRuntimeOwnerLifecycle.CLOSED, owner.projection.value.lifecycle)
            assertEquals(
                "ANDROID_TEST_PROCESS_RESOURCE_OUTCOME_UNKNOWN",
                owner.projection.value.lastPlatformFailure?.code?.value,
            )
        }

    @Test
    fun `unexpected service destruction reconciles a held lease as outcome unknown`() = runTest {
        val fixture = fixture()
        val endpoint = FakeEndpoint()
        fixture.owner.submit(start("destroyed-service"), 31, endpoint)
        advanceUntilIdle()
        assertEquals(RuntimeHostExecutionState.FOREGROUND, fixture.owner.hostProjection.value.execution)

        fixture.owner.endpointDestroyed(endpoint)
        advanceUntilIdle()

        assertEquals(
            RuntimeHostExecutionState.OUTCOME_UNKNOWN,
            fixture.owner.hostProjection.value.execution,
        )
        assertEquals(0, endpoint.leaves.size)
        fixture.owner.shutdown()
    }

    @Test
    fun `service destruction before start dispatch invalidates its provisional lease`() = runTest {
        val fixture = fixture()
        val endpoint = FakeEndpoint()
        fixture.owner.submit(start("destroyed-before-dispatch"), 35, endpoint)

        fixture.owner.endpointDestroyed(endpoint)
        advanceUntilIdle()

        assertFalse(fixture.owner.hostProjection.value.execution == RuntimeHostExecutionState.FOREGROUND)
        assertEquals(
            "ANDROID_RUNTIME_SERVICE_ENDPOINT_DESTROYED",
            fixture.owner.projection.value.lastPlatformFailure?.code?.value,
        )
        fixture.owner.shutdown()
    }

    @Test
    fun `notification refresh failure triggers prerequisite stop before service settlement`() = runTest {
        val fixture = fixture()
        val notificationFailure = androidRuntimeFailure(
            FailureCategory.PERMISSION,
            "ANDROID_TEST_NOTIFICATION_DENIED",
            retryable = false,
        )
        val endpoint = FakeEndpoint(
            startResults = ArrayDeque(
                listOf(
                    AndroidPlatformForegroundStartResult.Entered,
                    AndroidPlatformForegroundStartResult.OutcomeUnknown(notificationFailure),
                ),
            ),
        )

        fixture.owner.submit(start("notification-refresh"), 41, endpoint)
        advanceUntilIdle()

        assertEquals(RuntimeHostExecutionState.STOPPED, fixture.owner.hostProjection.value.execution)
        assertEquals(
            "ANDROID_TEST_NOTIFICATION_DENIED",
            fixture.owner.projection.value.lastPlatformFailure?.code?.value,
        )
        assertEquals(1, endpoint.leaves.size)
        assertEquals(listOf(41 to true), endpoint.settlements)
        fixture.owner.shutdown()
    }

    @Test
    fun `failed foreground release keeps service attached and outcome unknown`() = runTest {
        val releaseFailure = androidRuntimeFailure(
            FailureCategory.INTERNAL,
            "ANDROID_TEST_RELEASE_UNKNOWN",
            retryable = false,
        )
        val fixture = fixture()
        val endpoint = FakeEndpoint(
            stopResults = ArrayDeque(
                listOf(AndroidPlatformForegroundStopResult.OutcomeUnknown(releaseFailure)),
            ),
        )
        fixture.owner.submit(start("release-unknown"), 45, endpoint)
        advanceUntilIdle()

        fixture.owner.submit(stop("release-unknown", RuntimeHostStopOrigin.EXPLICIT_USER), 46, endpoint)
        advanceUntilIdle()

        assertEquals(
            RuntimeHostExecutionState.OUTCOME_UNKNOWN,
            fixture.owner.hostProjection.value.execution,
        )
        assertEquals(false, endpoint.settlements.single { it.first == 46 }.second)
        assertTrue(fixture.owner.serviceStillNeeded())
        fixture.owner.shutdown()
    }

    @Test
    fun `coalesced stop settles the newest start id for every service endpoint`() = runTest {
        val fixture = fixture()
        val oldEndpoint = FakeEndpoint()
        val replacementEndpoint = FakeEndpoint()

        fixture.owner.submit(
            stop("old-endpoint", RuntimeHostStopOrigin.PREREQUISITE_LOST),
            99,
            oldEndpoint,
        )
        fixture.owner.submit(
            stop("replacement-endpoint", RuntimeHostStopOrigin.EXPLICIT_USER),
            1,
            replacementEndpoint,
        )
        advanceUntilIdle()

        assertEquals(listOf(99 to true), oldEndpoint.settlements)
        assertEquals(listOf(1 to true), replacementEndpoint.settlements)
        assertEquals(
            RuntimeHostRestartPolicy.USER_STOPPED,
            fixture.owner.hostProjection.value.restartPolicy,
        )
        fixture.owner.shutdown()
    }

    @Test
    fun `duplicate in flight start deliveries share one physical host transition`() = runTest {
        val recoveryEntered = CompletableDeferred<Unit>()
        val recoveryGate = CompletableDeferred<Unit>()
        var rehydrations = 0
        val fixture = fixture(
            rehydrate = { operation ->
                rehydrations += 1
                recoveryEntered.complete(Unit)
                recoveryGate.await()
                RuntimeHostRehydrationResult.Rehydrated(
                    operation,
                    RuntimeHostTransportState.READY,
                )
            },
        )
        val endpoint = FakeEndpoint()
        val request = start("duplicate-delivery")

        fixture.owner.submit(request, 51, endpoint)
        fixture.owner.submit(request, 52, endpoint)
        runCurrent()
        recoveryEntered.await()
        recoveryGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, rehydrations)
        assertEquals(RuntimeHostExecutionState.FOREGROUND, fixture.owner.hostProjection.value.execution)
        assertEquals(setOf(51, 52), endpoint.settlements.map { it.first }.toSet())
        assertTrue(endpoint.settlements.none { it.second })
        assertEquals(null, fixture.owner.projection.value.lastFailure)
        fixture.owner.shutdown()
        assertEquals(1, endpoint.leaves.size)
    }

    @Test
    fun `terminal start replay after stop cannot resurrect a stale foreground outcome`() = runTest {
        val fixture = fixture()
        val endpoint = FakeEndpoint()
        val original = start("terminal-replay")
        fixture.owner.submit(original, 61, endpoint)
        advanceUntilIdle()
        fixture.owner.submit(stop("terminal-replay", RuntimeHostStopOrigin.EXPLICIT_USER), 62, endpoint)
        advanceUntilIdle()

        fixture.owner.submit(original, 63, endpoint)
        advanceUntilIdle()

        assertEquals(RuntimeHostExecutionState.STOPPED, fixture.owner.hostProjection.value.execution)
        assertEquals(
            "ANDROID_RUNTIME_REPLAY_NOT_CURRENT_EXECUTION",
            fixture.owner.projection.value.lastPlatformFailure?.code?.value,
        )
        assertEquals(2, endpoint.leaves.size)
        assertEquals(true, endpoint.settlements.single { it.first == 63 }.second)
        fixture.owner.shutdown()
    }

    private fun TestScope.fixture(
        deliveryCapacity: Int = AndroidRuntimeProcessOwner.DEFAULT_DELIVERY_CAPACITY,
        prerequisites: suspend (RuntimeHostOperation) -> RuntimeHostPrerequisiteResult = { operation ->
            RuntimeHostPrerequisiteResult.Observed(operation, allowedPrerequisites)
        },
        rehydrate: suspend (RuntimeHostOperation) -> RuntimeHostRehydrationResult = { operation ->
            RuntimeHostRehydrationResult.Rehydrated(operation, RuntimeHostTransportState.READY)
        },
    ): OwnerFixture {
        val foreground = AndroidRuntimeForegroundBridge()
        val runtimeHost = RuntimeHost(
            parentScope = this,
            prerequisites = object : RuntimeHostPrerequisitePort {
                override suspend fun inspect(
                    operation: RuntimeHostOperation,
                ): RuntimeHostPrerequisiteResult = prerequisites(operation)
            },
            execution = foreground,
            recovery = object : RuntimeHostRecoveryPort {
                override suspend fun rehydrateAndReconcile(
                    operation: RuntimeHostOperation,
                ): RuntimeHostRehydrationResult = rehydrate(operation)

                override suspend fun cleanup(
                    request: RuntimeHostCleanupRequest,
                ): RuntimeHostCleanupResult = RuntimeHostCleanupResult.Cleaned(request.operation)
            },
        )
        val owner = AndroidRuntimeProcessOwner(
            parentScope = this,
            host = PortableAndroidRuntimeHostController(runtimeHost),
            foreground = foreground,
            deliveryCapacity = deliveryCapacity,
        )
        return OwnerFixture(owner)
    }
}

private data class OwnerFixture(val owner: AndroidRuntimeProcessOwner)

private class RecordingHostController(
    private val order: MutableList<String>,
    private val stopEntered: CompletableDeferred<Unit>,
    private val stopGate: CompletableDeferred<Unit>,
) : AndroidRuntimeHostController {
    private val mutableProjection = MutableStateFlow(
        RuntimeHostProjection(execution = RuntimeHostExecutionState.FOREGROUND),
    )
    override val projection: StateFlow<RuntimeHostProjection> = mutableProjection

    override suspend fun start(request: RuntimeHostRequest.Start): RuntimeHostCommandResult =
        error("start is not expected")

    override suspend fun stop(request: RuntimeHostRequest.Stop): RuntimeHostCommandResult {
        order += "stop:${request.origin.name}"
        stopEntered.complete(Unit)
        withContext(NonCancellable) { stopGate.await() }
        mutableProjection.value = mutableProjection.value.copy(
            execution = RuntimeHostExecutionState.STOPPED,
        )
        return RuntimeHostCommandResult(
            RuntimeHostCommandRecord(request, RuntimeHostCommandOutcome.Stopped),
            replayed = false,
        )
    }

    override suspend fun close() {
        order += "close"
    }
}

private class RecordingProcessResources(
    private val order: MutableList<String>,
) : AndroidRuntimeProcessResources {
    var closeCount = 0

    override suspend fun close(): AndroidRuntimeProcessResourceCloseResult {
        closeCount += 1
        order += "resources"
        return AndroidRuntimeProcessResourceCloseResult.Closed
    }
}

private fun start(
    suffix: String,
    origin: RuntimeHostStartOrigin = RuntimeHostStartOrigin.EXPLICIT_USER,
): RuntimeHostRequest.Start = RuntimeHostRequest.Start(
    CommandId("android-command-start-$suffix"),
    CorrelationId("android-correlation-start-$suffix"),
    origin,
)

private fun stop(
    suffix: String,
    origin: RuntimeHostStopOrigin,
): RuntimeHostRequest.Stop = RuntimeHostRequest.Stop(
    CommandId("android-command-stop-$suffix"),
    CorrelationId("android-correlation-stop-$suffix"),
    origin,
)

private val allowedPrerequisites = RuntimeHostPrerequisites(
    association = RuntimeHostAssociationState.ASSOCIATED,
    presence = RuntimeHostPresenceState.PRESENT,
    permission = RuntimeHostPermissionState.GRANTED,
)
