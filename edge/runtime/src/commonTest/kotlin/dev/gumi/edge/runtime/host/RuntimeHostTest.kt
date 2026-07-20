package dev.gumi.edge.runtime.host

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeHostTest {
    @Test
    fun `foreground execution is established before any durable rehydration work`() = runTest {
        val fixture = fixture()

        val result = fixture.host.start(start("ordered"))

        assertIs<RuntimeHostCommandOutcome.Started>(result.record.outcome)
        assertEquals(listOf("prerequisites", "enter-foreground", "rehydrate"), fixture.order)
        with(fixture.host.projection.value) {
            assertEquals(RuntimeHostAssociationState.ASSOCIATED, association)
            assertEquals(RuntimeHostPresenceState.PRESENT, presence)
            assertEquals(RuntimeHostPermissionState.GRANTED, permission)
            assertEquals(RuntimeHostExecutionState.FOREGROUND, execution)
            assertEquals(RuntimeHostTransportState.READY, transport)
            assertEquals(RuntimeHostRecoveryState.CLEAN, recovery)
            assertEquals(RuntimeHostRestartPolicy.AUTOMATIC_ALLOWED, restartPolicy)
        }
        fixture.host.stop(stop("ordered-cleanup", RuntimeHostStopOrigin.OWNER_SHUTDOWN))
        fixture.host.close()
    }

    @Test
    fun `matching ongoing disconnect removes transport readiness but preserves foreground truth`() =
        runTest {
            val fixture = fixture()
            fixture.host.start(start("ongoing-disconnect"))
            val owner = fixture.recovery.rehydrations.single()
            val disconnected = failure("TEST_OPERATIONAL_DISCONNECTED")

            fixture.recovery.events.emit(
                RuntimeHostRecoveryEvent.TransportDisconnected(owner, disconnected),
            )
            runCurrent()

            with(fixture.host.projection.value) {
                assertEquals(RuntimeHostExecutionState.FOREGROUND, execution)
                assertEquals(RuntimeHostTransportState.DISCONNECTED, transport)
                assertEquals(RuntimeHostRecoveryState.RECONCILIATION_REQUIRED, recovery)
                assertEquals(disconnected, lastFailure)
                assertEquals(0uL, staleCompletionCount)
            }
            val faulted = failure("TEST_OPERATIONAL_FAULT_AFTER_DISCONNECT")
            fixture.recovery.events.emit(RuntimeHostRecoveryEvent.Faulted(owner, faulted))
            runCurrent()
            with(fixture.host.projection.value) {
                assertEquals(RuntimeHostExecutionState.FOREGROUND, execution)
                assertEquals(RuntimeHostTransportState.DISCONNECTED, transport)
                assertEquals(RuntimeHostRecoveryState.FAULTED, recovery)
                assertEquals(faulted, lastFailure)
            }
            fixture.host.stop(stop("ongoing-disconnect", RuntimeHostStopOrigin.OWNER_SHUTDOWN))
            fixture.host.close()
        }

    @Test
    fun `disconnect racing rehydration completion cannot be overwritten by ready completion`() =
        runTest {
            val recoveryEvents = MutableSharedFlow<RuntimeHostRecoveryEvent>(extraBufferCapacity = 8)
            val disconnected = failure("TEST_RACING_OPERATIONAL_DISCONNECT")
            val fixture = fixture(
                recoveryEvents = recoveryEvents,
                rehydrateHandler = { operation ->
                    recoveryEvents.subscriptionCount.first { it > 0 }
                    recoveryEvents.emit(
                        RuntimeHostRecoveryEvent.TransportDisconnected(operation, disconnected),
                    )
                    yield()
                    RuntimeHostRehydrationResult.Rehydrated(
                        operation,
                        RuntimeHostTransportState.READY,
                    )
                },
            )

            val result = fixture.host.start(start("racing-recovery-event"))

            assertEquals(
                RuntimeHostRecoveryState.RECONCILIATION_REQUIRED,
                assertIs<RuntimeHostCommandOutcome.Started>(result.record.outcome).recovery,
            )
            with(fixture.host.projection.value) {
                assertEquals(RuntimeHostExecutionState.FOREGROUND, execution)
                assertEquals(RuntimeHostTransportState.DISCONNECTED, transport)
                assertEquals(RuntimeHostRecoveryState.RECONCILIATION_REQUIRED, recovery)
                assertEquals(disconnected, lastFailure)
            }
            fixture.host.stop(stop("racing-event-cleanup", RuntimeHostStopOrigin.OWNER_SHUTDOWN))
            fixture.host.close()
        }

    @Test
    fun `late prior-generation recovery event increments stale count only`() = runTest {
        val fixture = fixture()
        fixture.host.start(start("stale-event-first"))
        val firstOwner = fixture.recovery.rehydrations.single()
        fixture.host.stop(stop("stale-event-first", RuntimeHostStopOrigin.OWNER_SHUTDOWN))
        fixture.host.start(start("stale-event-second"))
        val before = fixture.host.projection.value

        fixture.recovery.events.emit(
            RuntimeHostRecoveryEvent.Faulted(
                firstOwner,
                failure("TEST_STALE_OPERATIONAL_FAULT"),
            ),
        )
        runCurrent()

        with(fixture.host.projection.value) {
            assertEquals(before.execution, execution)
            assertEquals(before.transport, transport)
            assertEquals(before.recovery, recovery)
            assertEquals(before.lastFailure, lastFailure)
            assertEquals(before.staleCompletionCount + 1uL, staleCompletionCount)
        }
        fixture.host.stop(stop("stale-event-second", RuntimeHostStopOrigin.OWNER_SHUTDOWN))
        fixture.host.close()
    }

    @Test
    fun `missing association and denied permission refuse start before foreground execution`() =
        runTest {
            val missing = fixture(
                prerequisiteHandler = { operation ->
                    RuntimeHostPrerequisiteResult.Observed(
                        operation,
                        allowedPrerequisites.copy(
                            association = RuntimeHostAssociationState.MISSING,
                        ),
                    )
                },
            )
            val missingResult = missing.host.start(start("association-missing"))
            assertEquals(
                "RUNTIME_HOST_ASSOCIATION_MISSING",
                assertIs<RuntimeHostCommandOutcome.Rejected>(missingResult.record.outcome)
                    .failure.code.value,
            )
            assertEquals(listOf("prerequisites"), missing.order)
            assertEquals(RuntimeHostExecutionState.START_DENIED, missing.host.projection.value.execution)
            missing.host.close()

            val denied = fixture(
                prerequisiteHandler = { operation ->
                    RuntimeHostPrerequisiteResult.Observed(
                        operation,
                        allowedPrerequisites.copy(
                            permission = RuntimeHostPermissionState.DENIED,
                        ),
                    )
                },
            )
            val deniedResult = denied.host.start(start("permission-denied"))
            assertEquals(
                "RUNTIME_HOST_PERMISSION_DENIED",
                assertIs<RuntimeHostCommandOutcome.Rejected>(deniedResult.record.outcome)
                    .failure.code.value,
            )
            assertEquals(listOf("prerequisites"), denied.order)
            assertEquals(RuntimeHostPermissionState.DENIED, denied.host.projection.value.permission)
            denied.host.close()
        }

    @Test
    fun `in-flight and terminal duplicate start identities replay one physical effect`() = runTest {
        val recoveryGate = CompletableDeferred<Unit>()
        val fixture = fixture(
            rehydrateHandler = { operation ->
                recoveryGate.await()
                RuntimeHostRehydrationResult.Rehydrated(
                    operation,
                    RuntimeHostTransportState.READY,
                )
            },
        )
        val request = start("duplicate")
        val first = async { fixture.host.start(request) }
        runCurrent()
        val duplicate = async { fixture.host.start(request) }
        runCurrent()

        assertEquals(1, fixture.execution.entered.size)
        assertEquals(1, fixture.recovery.rehydrations.size)
        assertFalse(first.isCompleted)
        assertFalse(duplicate.isCompleted)

        recoveryGate.complete(Unit)
        val firstResult = first.await()
        val duplicateResult = duplicate.await()
        assertFalse(firstResult.replayed)
        assertTrue(duplicateResult.replayed)
        assertIs<RuntimeHostCommandOutcome.Started>(firstResult.record.outcome)

        val terminalReplay = fixture.host.start(request)
        assertTrue(terminalReplay.replayed)
        assertEquals(1, fixture.execution.entered.size)
        assertEquals(1, fixture.recovery.rehydrations.size)

        val conflict = fixture.host.start(
            request.copy(correlationId = CorrelationId("correlation-conflicting-reuse")),
        )
        assertEquals(
            "RUNTIME_HOST_COMMAND_ID_CONFLICT",
            assertIs<RuntimeHostCommandOutcome.Rejected>(conflict.record.outcome).failure.code.value,
        )
        fixture.host.stop(stop("duplicate-cleanup", RuntimeHostStopOrigin.OWNER_SHUTDOWN))
        fixture.host.close()
    }

    @Test
    fun `in-flight and terminal duplicate stop identities run cleanup and release once`() = runTest {
        val cleanupGate = CompletableDeferred<Unit>()
        val fixture = fixture(
            cleanupHandler = { request ->
                cleanupGate.await()
                RuntimeHostCleanupResult.Cleaned(request.operation)
            },
        )
        fixture.host.start(start("duplicate-stop-start"))
        val request = stop("duplicate-stop", RuntimeHostStopOrigin.EXPLICIT_USER)

        val first = async { fixture.host.stop(request) }
        runCurrent()
        val duplicate = async { fixture.host.stop(request) }
        runCurrent()

        assertEquals(1, fixture.recovery.cleanups.size)
        assertTrue(fixture.execution.released.isEmpty())
        assertFalse(first.isCompleted)
        assertFalse(duplicate.isCompleted)

        cleanupGate.complete(Unit)
        val firstResult = first.await()
        val duplicateResult = duplicate.await()
        assertFalse(firstResult.replayed)
        assertTrue(duplicateResult.replayed)
        assertIs<RuntimeHostCommandOutcome.Stopped>(firstResult.record.outcome)
        assertEquals(1, fixture.recovery.cleanups.size)
        assertEquals(1, fixture.execution.released.size)

        val terminalReplay = fixture.host.stop(request)
        assertTrue(terminalReplay.replayed)
        assertEquals(1, fixture.recovery.cleanups.size)
        assertEquals(1, fixture.execution.released.size)
        fixture.host.close()
    }

    @Test
    fun `user stop suppresses automatic restart until a new explicit user start`() = runTest {
        val fixture = fixture()
        fixture.host.start(start("initial"))

        val stopped = fixture.host.stop(stop("user-stop", RuntimeHostStopOrigin.EXPLICIT_USER))

        assertIs<RuntimeHostCommandOutcome.Stopped>(stopped.record.outcome)
        assertEquals(RuntimeHostExecutionState.STOPPED, fixture.host.projection.value.execution)
        assertEquals(
            RuntimeHostRestartPolicy.USER_STOPPED,
            fixture.host.projection.value.restartPolicy,
        )
        val effectCountAfterStop = fixture.order.size

        val automatic = fixture.host.start(
            start("automatic", RuntimeHostStartOrigin.AUTOMATIC_RECOVERY),
        )
        assertEquals(
            "RUNTIME_HOST_USER_STOP_SUPPRESSES_AUTOSTART",
            assertIs<RuntimeHostCommandOutcome.Suppressed>(automatic.record.outcome).failure.code.value,
        )
        assertEquals(effectCountAfterStop, fixture.order.size)

        assertIs<RuntimeHostCommandOutcome.Started>(
            fixture.host.start(start("explicit-restart")).record.outcome,
        )
        assertEquals(
            RuntimeHostRestartPolicy.AUTOMATIC_ALLOWED,
            fixture.host.projection.value.restartPolicy,
        )
        fixture.host.stop(stop("explicit-cleanup", RuntimeHostStopOrigin.OWNER_SHUTDOWN))
        fixture.host.close()
    }

    @Test
    fun `stop racing foreground acquisition waits for cancellation settlement then releases once`() =
        runTest {
            val enterGate = CompletableDeferred<Unit>()
            val fixture = fixture(
                enterHandler = { operation ->
                    withContext(NonCancellable) {
                        enterGate.await()
                        RuntimeHostForegroundStartResult.Entered(operation)
                    }
                },
            )
            val starting = async { fixture.host.start(start("racing-start")) }
            runCurrent()
            assertEquals(1, fixture.execution.entered.size)

            val stopping = async {
                fixture.host.stop(stop("racing-stop", RuntimeHostStopOrigin.EXPLICIT_USER))
            }
            runCurrent()
            assertFalse(stopping.isCompleted)
            assertTrue(fixture.execution.released.isEmpty())

            enterGate.complete(Unit)
            val startResult = starting.await()
            val stopResult = stopping.await()

            assertIs<RuntimeHostCommandOutcome.Cancelled>(startResult.record.outcome)
            assertIs<RuntimeHostCommandOutcome.Stopped>(stopResult.record.outcome)
            assertTrue(fixture.recovery.rehydrations.isEmpty())
            assertEquals(1, fixture.execution.released.size)
            assertEquals(RuntimeHostExecutionState.STOPPED, fixture.host.projection.value.execution)
            assertEquals(
                RuntimeHostRestartPolicy.USER_STOPPED,
                fixture.host.projection.value.restartPolicy,
            )
            fixture.host.close()
        }

    @Test
    fun `stale foreground completion cannot establish execution and is conservatively released`() =
        runTest {
            val fixture = fixture(
                enterHandler = { operation ->
                    RuntimeHostForegroundStartResult.Entered(
                        operation.copy(generation = operation.generation + 1uL),
                    )
                },
            )

            val result = fixture.host.start(start("stale-completion"))

            assertEquals(
                "RUNTIME_HOST_STALE_FOREGROUND_COMPLETION",
                assertIs<RuntimeHostCommandOutcome.Failed>(result.record.outcome).failure.code.value,
            )
            assertEquals(1uL, fixture.host.projection.value.staleCompletionCount)
            assertTrue(fixture.recovery.rehydrations.isEmpty())
            assertEquals(1, fixture.execution.released.size)
            assertEquals(RuntimeHostExecutionState.STOPPED, fixture.host.projection.value.execution)
            fixture.host.close()
        }

    @Test
    fun `recovery outcome unknown triggers ordered cleanup and remains reconciliation-required`() =
        runTest {
            val unknown = failure("TEST_RECOVERY_OUTCOME_UNKNOWN")
            val fixture = fixture(
                rehydrateHandler = { operation ->
                    RuntimeHostRehydrationResult.OutcomeUnknown(operation, unknown)
                },
            )

            val result = fixture.host.start(start("unknown-recovery"))

            assertEquals(
                unknown,
                assertIs<RuntimeHostCommandOutcome.Failed>(result.record.outcome).failure,
            )
            assertEquals(
                listOf(
                    "prerequisites",
                    "enter-foreground",
                    "rehydrate",
                    "cleanup-recovery",
                    "leave-foreground",
                ),
                fixture.order,
            )
            assertEquals(RuntimeHostExecutionState.STOPPED, fixture.host.projection.value.execution)
            assertEquals(
                RuntimeHostRecoveryState.RECONCILIATION_REQUIRED,
                fixture.host.projection.value.recovery,
            )
            fixture.host.close()
        }

    @Test
    fun `cleanup failure cannot skip foreground release or claim clean recovery`() = runTest {
        val cleanupFailure = failure("TEST_RECOVERY_CLEANUP_FAILED")
        val fixture = fixture(
            cleanupHandler = { request ->
                RuntimeHostCleanupResult.Failed(request.operation, cleanupFailure)
            },
        )
        fixture.host.start(start("cleanup-failure-start"))

        val stopped = fixture.host.stop(
            stop("cleanup-failure-stop", RuntimeHostStopOrigin.EXPLICIT_USER),
        )

        assertEquals(
            cleanupFailure,
            assertIs<RuntimeHostCommandOutcome.Failed>(stopped.record.outcome).failure,
        )
        assertEquals(1, fixture.recovery.cleanups.size)
        assertEquals(1, fixture.execution.released.size)
        assertTrue(
            fixture.order.indexOf("cleanup-recovery") < fixture.order.indexOf("leave-foreground"),
        )
        assertEquals(RuntimeHostExecutionState.STOPPED, fixture.host.projection.value.execution)
        assertEquals(RuntimeHostRecoveryState.FAULTED, fixture.host.projection.value.recovery)
        assertEquals(
            RuntimeHostRestartPolicy.USER_STOPPED,
            fixture.host.projection.value.restartPolicy,
        )
        fixture.host.close()
    }

    @Test
    fun `foreground release outcome unknown preserves user stop but never claims stopped execution`() =
        runTest {
            val releaseUnknown = failure("TEST_FOREGROUND_RELEASE_UNKNOWN")
            val fixture = fixture(
                leaveHandler = { operation ->
                    RuntimeHostForegroundStopResult.OutcomeUnknown(operation, releaseUnknown)
                },
            )
            fixture.host.start(start("release-unknown-start"))

            val stopped = fixture.host.stop(
                stop("release-unknown-stop", RuntimeHostStopOrigin.EXPLICIT_USER),
            )

            assertEquals(
                releaseUnknown,
                assertIs<RuntimeHostCommandOutcome.Failed>(stopped.record.outcome).failure,
            )
            assertEquals(RuntimeHostExecutionState.OUTCOME_UNKNOWN, fixture.host.projection.value.execution)
            assertEquals(
                RuntimeHostRecoveryState.RECONCILIATION_REQUIRED,
                fixture.host.projection.value.recovery,
            )
            assertEquals(
                RuntimeHostRestartPolicy.USER_STOPPED,
                fixture.host.projection.value.restartPolicy,
            )
            val effects = fixture.order.size
            assertIs<RuntimeHostCommandOutcome.Suppressed>(
                fixture.host.start(
                    start("release-unknown-auto", RuntimeHostStartOrigin.AUTOMATIC_RECOVERY),
                ).record.outcome,
            )
            assertEquals(effects, fixture.order.size)
            fixture.host.close()
        }

    @Test
    fun `close atomically rejects later admission and settles the prior waiter`() = runTest {
        val enterGate = CompletableDeferred<Unit>()
        val fixture = fixture(
            enterHandler = { operation ->
                withContext(NonCancellable) {
                    enterGate.await()
                    RuntimeHostForegroundStartResult.Entered(operation)
                }
            },
        )
        val starting = async { fixture.host.start(start("close-race-active")) }
        runCurrent()
        assertEquals(1, fixture.execution.entered.size)

        val closing = async { fixture.host.close() }
        runCurrent()

        val rejected = fixture.host.start(start("close-race-rejected"))
        val rejection = assertIs<RuntimeHostCommandOutcome.Rejected>(rejected.record.outcome).failure
        assertEquals("RUNTIME_HOST_CLOSED", rejection.code.value)
        assertFalse(rejection.retryable)
        assertIs<RuntimeHostCommandOutcome.Cancelled>(starting.await().record.outcome)
        assertFalse(closing.isCompleted)
        assertEquals(RuntimeHostExecutionState.OUTCOME_UNKNOWN, fixture.host.projection.value.execution)

        enterGate.complete(Unit)
        closing.await()
        assertTrue(fixture.execution.released.isEmpty())
    }

    @Test
    fun `cancelled first close under mailbox pressure still publishes shutdown and converges`() =
        runTest {
            val fixture = fixture(mailboxCapacity = 1)
            val admitted = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.host.start(start("queued-before-close"))
            }
            val firstClose = async(start = CoroutineStart.UNDISPATCHED) { fixture.host.close() }
            assertFalse(firstClose.isCompleted)

            firstClose.cancel()
            runCurrent()
            fixture.host.close()

            assertTrue(firstClose.isCancelled)
            assertIs<RuntimeHostCommandOutcome.Cancelled>(admitted.await().record.outcome)
            assertEquals(RuntimeHostExecutionState.STOPPED, fixture.host.projection.value.execution)
            val rejected = fixture.host.stop(
                stop("after-cancelled-close", RuntimeHostStopOrigin.OWNER_SHUTDOWN),
            )
            assertEquals(
                "RUNTIME_HOST_CLOSED",
                assertIs<RuntimeHostCommandOutcome.Rejected>(rejected.record.outcome)
                    .failure.code.value,
            )
        }
}

private data class HostFixture(
    val order: MutableList<String>,
    val prerequisites: FakePrerequisitePort,
    val execution: FakeExecutionPort,
    val recovery: FakeRecoveryPort,
    val host: RuntimeHost,
)

private fun TestScope.fixture(
    prerequisiteHandler: suspend (RuntimeHostOperation) -> RuntimeHostPrerequisiteResult = {
        RuntimeHostPrerequisiteResult.Observed(it, allowedPrerequisites)
    },
    enterHandler: suspend (RuntimeHostOperation) -> RuntimeHostForegroundStartResult = {
        RuntimeHostForegroundStartResult.Entered(it)
    },
    leaveHandler: suspend (RuntimeHostOperation) -> RuntimeHostForegroundStopResult = {
        RuntimeHostForegroundStopResult.Released(it)
    },
    rehydrateHandler: suspend (RuntimeHostOperation) -> RuntimeHostRehydrationResult = {
        RuntimeHostRehydrationResult.Rehydrated(it, RuntimeHostTransportState.READY)
    },
    cleanupHandler: suspend (RuntimeHostCleanupRequest) -> RuntimeHostCleanupResult = {
        RuntimeHostCleanupResult.Cleaned(it.operation)
    },
    recoveryEvents: MutableSharedFlow<RuntimeHostRecoveryEvent> =
        MutableSharedFlow(extraBufferCapacity = 16),
    mailboxCapacity: Int = RuntimeHost.DEFAULT_MAILBOX_CAPACITY,
): HostFixture {
    val order = mutableListOf<String>()
    val prerequisitePort = FakePrerequisitePort(order, prerequisiteHandler)
    val executionPort = FakeExecutionPort(order, enterHandler, leaveHandler)
    val recoveryPort = FakeRecoveryPort(
        order,
        rehydrateHandler,
        cleanupHandler,
        recoveryEvents,
    )
    return HostFixture(
        order = order,
        prerequisites = prerequisitePort,
        execution = executionPort,
        recovery = recoveryPort,
        host = RuntimeHost(
            this,
            prerequisitePort,
            executionPort,
            recoveryPort,
            mailboxCapacity = mailboxCapacity,
        ),
    )
}

private class FakePrerequisitePort(
    private val order: MutableList<String>,
    private val handler: suspend (RuntimeHostOperation) -> RuntimeHostPrerequisiteResult,
) : RuntimeHostPrerequisitePort {
    val inspections = mutableListOf<RuntimeHostOperation>()

    override suspend fun inspect(operation: RuntimeHostOperation): RuntimeHostPrerequisiteResult {
        order += "prerequisites"
        inspections += operation
        return handler(operation)
    }
}

private class FakeExecutionPort(
    private val order: MutableList<String>,
    private val enterHandler: suspend (RuntimeHostOperation) -> RuntimeHostForegroundStartResult,
    private val leaveHandler: suspend (RuntimeHostOperation) -> RuntimeHostForegroundStopResult,
) : RuntimeHostExecutionPort {
    val entered = mutableListOf<RuntimeHostOperation>()
    val released = mutableListOf<RuntimeHostOperation>()

    override suspend fun enterForeground(
        operation: RuntimeHostOperation,
    ): RuntimeHostForegroundStartResult {
        order += "enter-foreground"
        entered += operation
        return enterHandler(operation)
    }

    override suspend fun leaveForeground(
        operation: RuntimeHostOperation,
    ): RuntimeHostForegroundStopResult {
        order += "leave-foreground"
        released += operation
        return leaveHandler(operation)
    }
}

private class FakeRecoveryPort(
    private val order: MutableList<String>,
    private val rehydrateHandler: suspend (RuntimeHostOperation) -> RuntimeHostRehydrationResult,
    private val cleanupHandler: suspend (RuntimeHostCleanupRequest) -> RuntimeHostCleanupResult,
    override val events: MutableSharedFlow<RuntimeHostRecoveryEvent>,
) : RuntimeHostRecoveryPort {
    val rehydrations = mutableListOf<RuntimeHostOperation>()
    val cleanups = mutableListOf<RuntimeHostCleanupRequest>()

    override suspend fun rehydrateAndReconcile(
        operation: RuntimeHostOperation,
    ): RuntimeHostRehydrationResult {
        order += "rehydrate"
        rehydrations += operation
        return rehydrateHandler(operation)
    }

    override suspend fun cleanup(request: RuntimeHostCleanupRequest): RuntimeHostCleanupResult {
        order += "cleanup-recovery"
        cleanups += request
        return cleanupHandler(request)
    }
}

private fun start(
    suffix: String,
    origin: RuntimeHostStartOrigin = RuntimeHostStartOrigin.EXPLICIT_USER,
): RuntimeHostRequest.Start = RuntimeHostRequest.Start(
    id = CommandId("command-start-$suffix"),
    correlationId = CorrelationId("correlation-start-$suffix"),
    origin = origin,
)

private fun stop(
    suffix: String,
    origin: RuntimeHostStopOrigin,
): RuntimeHostRequest.Stop = RuntimeHostRequest.Stop(
    id = CommandId("command-stop-$suffix"),
    correlationId = CorrelationId("correlation-stop-$suffix"),
    origin = origin,
)

private fun failure(code: String): ExpectedFailure = ExpectedFailure(
    category = FailureCategory.INTERNAL,
    code = FailureCode(code),
    retryable = false,
)

private val allowedPrerequisites = RuntimeHostPrerequisites(
    association = RuntimeHostAssociationState.ASSOCIATED,
    presence = RuntimeHostPresenceState.PRESENT,
    permission = RuntimeHostPermissionState.GRANTED,
)
