package dev.gumi.edge.runtime.realtime

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.OpaqueBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceTurnCoordinatorTest {
    @Test
    fun `admission is exactly replayable and atomically binds session lease and start command`() =
        runTest {
            val fixture = fixture()
            fixture.store.nextCommitFault =
                InMemoryVoiceTurnStateStore.CommitFault.AFTER_COMMIT_RESPONSE_LOST

            assertIs<VoiceTurnOperationResult.Duplicate>(
                fixture.coordinator.admit(fixture.admission),
            )
            assertIs<VoiceTurnOperationResult.Duplicate>(
                fixture.coordinator.admit(fixture.admission),
            )
            assertEquals(1, fixture.store.commitCount)

            val conflicting = fixture.admission.copy(
                identity = fixture.admission.identity.copy(
                    turnId = VoiceTurnId("turn-conflict"),
                    startCommandId = CommandId("start-conflict"),
                ),
            )
            val result = assertIs<VoiceTurnOperationResult.Rejected>(
                fixture.coordinator.admit(conflicting),
            )
            assertEquals("VOICE_TURN_SESSION_ALREADY_BOUND", result.failure.code.value)
            assertNull(fixture.store.state(VoiceTurnId("turn-conflict")))
        }

    @Test
    fun `frame bytes flush before ordered metadata and response loss reconciles exact provenance`() =
        runTest {
            val fixture = admittedFixture()
            fixture.store.nextCommitFault =
                InMemoryVoiceTurnStateStore.CommitFault.AFTER_COMMIT_RESPONSE_LOST
            val speech = "secret-speech-that-must-not-enter-control-state".encodeToByteArray()

            val result = fixture.coordinator.appendFrame(
                frame(0uL, speech, mark = 11L),
            )

            assertIs<VoiceTurnOperationResult.Duplicate>(result)
            val state = assertNotNull(fixture.store.state(TURN))
            assertEquals(1, fixture.frames.writeCount)
            assertEquals(1, state.frames.size)
            assertEquals(0uL, state.frames.single().descriptor.ordinal)
            assertEquals(speech.size.toULong(), state.retainedBytes)
            assertFalse(state.toString().contains("secret-speech"))
            assertEquals(
                "<redacted-durable-payload-ref>",
                state.frames.single().payloadRef.toString(),
            )
        }

    @Test
    fun `out of order oversized and exhausted frame buffers fail before durable writes`() = runTest {
        val fixture = admittedFixture(
            VoiceTurnBufferPolicy(
                maximumFrameBytes = 4u,
                maximumFrameCount = 2u,
                maximumRetainedBytes = 8uL,
                maximumRemoteGenerations = 2u,
            ),
        )

        assertCode(
            "VOICE_TURN_FRAME_OUT_OF_ORDER",
            fixture.coordinator.appendFrame(frame(1uL, byteArrayOf(1), 10L)),
        )
        assertCode(
            "VOICE_TURN_FRAME_SIZE_OUTSIDE_POLICY",
            fixture.coordinator.appendFrame(frame(0uL, ByteArray(5), 10L)),
        )
        assertEquals(0, fixture.frames.writeCount)

        assertIs<VoiceTurnOperationResult.Applied>(
            fixture.coordinator.appendFrame(frame(0uL, ByteArray(4) { 1 }, 10L)),
        )
        assertIs<VoiceTurnOperationResult.Applied>(
            fixture.coordinator.appendFrame(frame(1uL, ByteArray(4) { 2 }, 11L)),
        )
        assertCode(
            "VOICE_TURN_FRAME_ORDINAL_OUTSIDE_POLICY",
            fixture.coordinator.appendFrame(frame(2uL, byteArrayOf(3), 12L)),
        )
        assertEquals(2, fixture.frames.writeCount)
    }

    @Test
    fun `same frame identity with different bytes cannot replace durable provenance`() = runTest {
        val fixture = admittedFixture()
        assertIs<VoiceTurnOperationResult.Applied>(
            fixture.coordinator.appendFrame(frame(0uL, byteArrayOf(1, 2), 10L)),
        )

        val conflict = assertIs<VoiceTurnOperationResult.Unavailable>(
            fixture.coordinator.appendFrame(frame(0uL, byteArrayOf(9, 9), 10L)),
        )

        assertEquals("TEST_VOICE_TURN_FRAME_IDENTITY_CONFLICT", conflict.failure.code.value)
        val durable = assertNotNull(fixture.store.state(TURN)).frames.single()
        assertEquals(
            fixture.frames.run {
                assertIs<VoiceTurnFrameReadResult.Verified>(readAndVerify(durable)).payload
            },
            OpaqueBytes.copyOf(byteArrayOf(1, 2)),
        )
    }

    @Test
    fun `ambiguous connection is explicit and exact retry does not call remote twice`() = runTest {
        val fixture = admittedFixture()
        fixture.remote.connectHandler = { request ->
            VoiceTurnRemoteConnectResult.OutcomeUnknown(
                VoiceTurnOutcomeBoundary.REMOTE_TRANSPORT,
                expectedFailure(
                    "TEST_CONNECT_UNKNOWN",
                    retryable = true,
                    correlationId = request.identity.correlationId,
                ),
            )
        }

        val first = assertIs<VoiceTurnOperationResult.OutcomeUnknown>(
            fixture.coordinator.connect(TURN, ATTEMPT_1, mark(20L)),
        )
        assertEquals(VoiceTurnRemoteOperation.CONNECT, first.unknown.operation)
        assertEquals(1, fixture.remote.connectRequests.size)

        val replay = assertIs<VoiceTurnOperationResult.Duplicate>(
            fixture.coordinator.connect(TURN, ATTEMPT_1, mark(21L)),
        )
        assertEquals(VoiceTurnStatus.REMOTE_OUTCOME_UNKNOWN, replay.projection.status)
        assertEquals(1, fixture.remote.connectRequests.size)

        fixture.remote.connectHandler = ScriptedVoiceTurnRealtimePort().connectHandler
        val resumed = assertIs<VoiceTurnOperationResult.Applied>(
            fixture.coordinator.connect(TURN, ATTEMPT_2, mark(22L)),
        )
        assertEquals(2uL, resumed.projection.remote.generation)
        assertEquals(VoiceTurnStatus.REMOTE_READY, resumed.projection.status)
    }

    @Test
    fun `outcome unknown frame resumes with stable identity and remote acknowledgement prevents resend`() =
        runTest {
            val fixture = admittedFixture()
            fixture.coordinator.appendFrame(frame(0uL, byteArrayOf(1), 10L))
            fixture.coordinator.appendFrame(frame(1uL, byteArrayOf(2), 11L))
            fixture.coordinator.connect(TURN, ATTEMPT_1, mark(20L))
            assertIs<VoiceTurnOperationResult.Applied>(fixture.coordinator.sendNext(TURN))
            fixture.remote.frameHandler = { request, _ ->
                VoiceTurnRemoteFrameResult.OutcomeUnknown(
                    VoiceTurnOutcomeBoundary.PROVIDER,
                    expectedFailure(
                        "TEST_FRAME_UNKNOWN",
                        retryable = true,
                        correlationId = request.identity.correlationId,
                    ),
                )
            }

            val unknown = assertIs<VoiceTurnOperationResult.OutcomeUnknown>(
                fixture.coordinator.sendNext(TURN),
            )
            assertEquals(0uL, unknown.projection.remote.acknowledgedThrough)
            assertEquals(1uL, fixture.remote.frameRequests.last().descriptor.ordinal)

            fixture.remote.connectHandler = { request ->
                VoiceTurnRemoteConnectResult.Ready(
                    VoiceTurnRemoteSessionRef("resumed-session"),
                    VoiceTurnRemoteResumeAck(
                        request.identity.sessionId,
                        request.identity.turnId,
                        request.attempt.generation,
                        acceptedThroughOrdinal = 1uL,
                    ),
                )
            }
            val resumed = assertIs<VoiceTurnOperationResult.Applied>(
                fixture.coordinator.connect(TURN, ATTEMPT_2, mark(30L)),
            )
            assertEquals(1uL, resumed.projection.remote.acknowledgedThrough)
            val noResend = assertIs<VoiceTurnOperationResult.NoWork>(
                fixture.coordinator.sendNext(TURN),
            )
            assertEquals(VoiceTurnNoWorkReason.WAITING_FOR_LOCAL_FRAME, noResend.reason)
            assertEquals(listOf(0uL, 1uL), fixture.remote.frameRequests.map { it.descriptor.ordinal })
        }

    @Test
    fun `caller cancellation leaves exact frame attempt fenced until a new generation reconciles`() =
        runTest {
            val fixture = admittedFixture()
            fixture.coordinator.appendFrame(frame(0uL, byteArrayOf(1), 10L))
            fixture.coordinator.connect(TURN, ATTEMPT_1, mark(20L))
            val entered = CompletableDeferred<Unit>()
            fixture.remote.frameHandler = { _, _ ->
                entered.complete(Unit)
                CompletableDeferred<VoiceTurnRemoteFrameResult>().await()
            }
            val sending = async { fixture.coordinator.sendNext(TURN) }
            entered.await()

            sending.cancelAndJoin()
            val fenced = assertIs<VoiceTurnReadResult.Found>(fixture.coordinator.read(TURN))
            assertEquals(VoiceTurnStatus.FRAME_ATTEMPT_FENCED, fenced.projection.status)
            assertNull(fenced.projection.remote.acknowledgedThrough)

            fixture.remote.connectHandler = { request ->
                VoiceTurnRemoteConnectResult.Ready(
                    VoiceTurnRemoteSessionRef("resume-after-caller-loss"),
                    VoiceTurnRemoteResumeAck(
                        request.identity.sessionId,
                        request.identity.turnId,
                        request.attempt.generation,
                        acceptedThroughOrdinal = 0uL,
                    ),
                )
            }
            val reconciled = assertIs<VoiceTurnOperationResult.Applied>(
                fixture.coordinator.connect(TURN, ATTEMPT_2, mark(30L)),
            )
            assertEquals(0uL, reconciled.projection.remote.acknowledgedThrough)
        }

    @Test
    fun `local cancellation does not wait behind suspended send and fences its late acknowledgement`() =
        runTest {
            val fixture = admittedFixture()
            fixture.coordinator.appendFrame(frame(0uL, byteArrayOf(1), 10L))
            fixture.coordinator.connect(TURN, ATTEMPT_1, mark(20L))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.remote.frameHandler = { request, _ ->
                entered.complete(Unit)
                release.await()
                VoiceTurnRemoteFrameResult.Accepted(
                    VoiceTurnRemoteFrameAck(
                        request.identity.sessionId,
                        request.identity.turnId,
                        request.generation,
                        request.descriptor.ordinal,
                        request.contentDigest,
                    ),
                )
            }
            val sending = async { fixture.coordinator.sendNext(TURN) }
            entered.await()

            val cancelled = assertIs<VoiceTurnOperationResult.Applied>(
                fixture.coordinator.cancel(TURN, cancellation(30L)),
            )
            assertEquals(VoiceTurnStatus.CANCELLED, cancelled.projection.status)
            assertEquals(
                VoiceTurnCancellationRemoteDisposition.CONFIRMED,
                (cancelled.projection.terminal as VoiceTurnTerminalOutcome.Cancelled)
                    .remoteDisposition,
            )
            release.complete(Unit)
            assertIs<VoiceTurnOperationResult.Stale>(sending.await())
            assertNull(cancelled.projection.remote.acknowledgedThrough)
        }

    @Test
    fun `remote cancellation ambiguity is terminal and explicitly projected`() = runTest {
        val fixture = admittedFixture()
        fixture.coordinator.connect(TURN, ATTEMPT_1, mark(20L))
        fixture.remote.cancelHandler = { request ->
            VoiceTurnRemoteCancelResult.OutcomeUnknown(
                VoiceTurnOutcomeBoundary.PROVIDER,
                expectedFailure(
                    "TEST_CANCEL_UNKNOWN",
                    retryable = true,
                    correlationId = request.cancellation.correlationId,
                ),
            )
        }

        val result = assertIs<VoiceTurnOperationResult.OutcomeUnknown>(
            fixture.coordinator.cancel(TURN, cancellation(30L)),
        )

        assertEquals(VoiceTurnRemoteOperation.CANCEL, result.unknown.operation)
        assertEquals(VoiceTurnOutcomeBoundary.PROVIDER, result.unknown.boundary)
        assertEquals(VoiceTurnStatus.CANCELLED_REMOTE_OUTCOME_UNKNOWN, result.projection.status)
        assertEquals(CANCEL_CORRELATION, result.projection.terminal?.correlationId)
    }

    @Test
    fun `finish and exact provider result preserve terminal reference and admission correlation`() =
        runTest {
            val fixture = admittedFixture()
            fixture.coordinator.appendFrame(frame(0uL, byteArrayOf(1), 10L))
            fixture.coordinator.endInput(TURN, mark(15L))
            fixture.coordinator.connect(TURN, ATTEMPT_1, mark(20L))
            fixture.coordinator.sendNext(TURN)
            val awaiting = assertIs<VoiceTurnOperationResult.Applied>(
                fixture.coordinator.finishRemote(TURN),
            )
            assertEquals(VoiceTurnStatus.AWAITING_RESULT, awaiting.projection.status)
            val terminalResult = VoiceTurnRemoteTerminalResult(
                sessionId = SESSION,
                turnId = TURN,
                correlationId = START_CORRELATION,
                remoteResultId = VoiceTurnRemoteResultId("provider-result-1"),
                resultRef = VoiceTurnResultRef("encrypted-result/turn-1"),
                observedAt = mark(40L),
            )

            val completed = assertIs<VoiceTurnOperationResult.Applied>(
                fixture.coordinator.applyRemoteEvent(
                    TURN,
                    VoiceTurnRemoteEvent.TerminalResult(1uL, terminalResult),
                ),
            )

            assertEquals(VoiceTurnStatus.COMPLETED, completed.projection.status)
            assertEquals(START_CORRELATION, completed.projection.identity.correlationId)
            assertEquals(START_CORRELATION, completed.projection.terminal?.correlationId)
            assertEquals(
                "<redacted-voice-turn-result-ref>",
                (completed.projection.terminal as VoiceTurnTerminalOutcome.Completed)
                    .result.resultRef.toString(),
            )
            assertIs<VoiceTurnOperationResult.Duplicate>(
                fixture.coordinator.applyRemoteEvent(
                    TURN,
                    VoiceTurnRemoteEvent.TerminalResult(1uL, terminalResult),
                ),
            )
        }

    @Test
    fun `wrong provider correlation cannot become a terminal result`() = runTest {
        val fixture = awaitingResultFixture()
        val result = assertIs<VoiceTurnOperationResult.OutcomeUnknown>(
            fixture.coordinator.applyRemoteEvent(
                TURN,
                VoiceTurnRemoteEvent.TerminalResult(
                    generation = 1uL,
                    result = VoiceTurnRemoteTerminalResult(
                        SESSION,
                        TURN,
                        CorrelationId("foreign-correlation"),
                        VoiceTurnRemoteResultId("foreign-result"),
                        VoiceTurnResultRef("foreign-result-ref"),
                        mark(40L),
                    ),
                ),
            ),
        )

        assertEquals(VoiceTurnOutcomeBoundary.PROVIDER, result.unknown.boundary)
        assertEquals("VOICE_TURN_REMOTE_RESULT_IDENTITY_MISMATCH", result.unknown.failure.code.value)
        assertNull(result.projection.terminal)
    }

    @Test
    fun `stale generation callback cannot overwrite a newer resumed session`() = runTest {
        val fixture = admittedFixture()
        fixture.remote.connectHandler = { request ->
            VoiceTurnRemoteConnectResult.OutcomeUnknown(
                VoiceTurnOutcomeBoundary.REMOTE_TRANSPORT,
                expectedFailure(
                    "TEST_CONNECT_UNKNOWN",
                    true,
                    request.identity.correlationId,
                ),
            )
        }
        fixture.coordinator.connect(TURN, ATTEMPT_1, mark(20L))
        fixture.remote.connectHandler = ScriptedVoiceTurnRealtimePort().connectHandler
        fixture.coordinator.connect(TURN, ATTEMPT_2, mark(21L))

        val stale = assertIs<VoiceTurnOperationResult.Stale>(
            fixture.coordinator.applyRemoteEvent(
                TURN,
                VoiceTurnRemoteEvent.ProviderFailure(
                    generation = 1uL,
                    failure = expectedFailure("OLD_PROVIDER_FAILURE", false, START_CORRELATION),
                    terminal = true,
                    observedAt = mark(30L),
                ),
            ),
        )

        assertEquals(2uL, stale.projection.remote.generation)
        assertEquals(VoiceTurnStatus.REMOTE_READY, stale.projection.status)
        assertNull(stale.projection.terminal)
    }

    @Test
    fun `retryable provider failure preserves turn identity and admits a new generation`() = runTest {
        val fixture = admittedFixture()
        fixture.coordinator.connect(TURN, ATTEMPT_1, mark(20L))
        val failed = assertIs<VoiceTurnOperationResult.Applied>(
            fixture.coordinator.applyRemoteEvent(
                TURN,
                VoiceTurnRemoteEvent.ProviderFailure(
                    generation = 1uL,
                    failure = expectedFailure(
                        "PROVIDER_TEMPORARILY_UNAVAILABLE",
                        true,
                        START_CORRELATION,
                    ),
                    terminal = false,
                    observedAt = mark(21L),
                ),
            ),
        )
        assertEquals(VoiceTurnStatus.REMOTE_RETRYABLE_FAILURE, failed.projection.status)

        val retried = assertIs<VoiceTurnOperationResult.Applied>(
            fixture.coordinator.connect(TURN, ATTEMPT_2, mark(22L)),
        )
        assertEquals(TURN, retried.projection.identity.turnId)
        assertEquals(START_CORRELATION, retried.projection.identity.correlationId)
        assertEquals(2uL, retried.projection.remote.generation)
    }

    @Test
    fun `remote generations are bounded and old attempt IDs cannot be recycled`() = runTest {
        val fixture = admittedFixture(
            VoiceTurnBufferPolicy(maximumRemoteGenerations = 2u),
        )
        fixture.remote.connectHandler = { request ->
            VoiceTurnRemoteConnectResult.OutcomeUnknown(
                VoiceTurnOutcomeBoundary.REMOTE_TRANSPORT,
                expectedFailure("TEST_CONNECT_UNKNOWN", true, request.identity.correlationId),
            )
        }
        fixture.coordinator.connect(TURN, ATTEMPT_1, mark(20L))
        fixture.coordinator.connect(TURN, ATTEMPT_2, mark(21L))

        assertCode(
            "VOICE_TURN_REMOTE_ATTEMPT_ID_REPLAYED",
            fixture.coordinator.connect(TURN, ATTEMPT_1, mark(22L)),
        )
        assertCode(
            "VOICE_TURN_REMOTE_GENERATIONS_EXHAUSTED",
            fixture.coordinator.connect(
                TURN,
                VoiceTurnRemoteAttemptId("remote-attempt-3"),
                mark(23L),
            ),
        )
        assertEquals(2, fixture.remote.connectRequests.size)
    }

    private suspend fun awaitingResultFixture(): Fixture {
        val fixture = admittedFixture()
        fixture.coordinator.endInput(TURN, mark(15L))
        fixture.coordinator.connect(TURN, ATTEMPT_1, mark(20L))
        fixture.coordinator.finishRemote(TURN)
        return fixture
    }

    private suspend fun admittedFixture(
        policy: VoiceTurnBufferPolicy = VoiceTurnBufferPolicy(),
    ): Fixture = fixture(policy).also {
        assertIs<VoiceTurnOperationResult.Applied>(it.coordinator.admit(it.admission))
    }

    private fun fixture(
        policy: VoiceTurnBufferPolicy = VoiceTurnBufferPolicy(),
    ): Fixture {
        val store = InMemoryVoiceTurnStateStore()
        val frames = InMemoryVoiceTurnFrameStore()
        val remote = ScriptedVoiceTurnRealtimePort()
        return Fixture(
            store = store,
            frames = frames,
            remote = remote,
            coordinator = VoiceTurnCoordinator(
                stateStore = store,
                frameStore = frames,
                realtime = remote,
                clock = IncrementingVoiceTurnClock(),
            ),
            admission = VoiceTurnAdmission(
                identity = VoiceTurnIdentity(
                    sessionId = SESSION,
                    turnId = TURN,
                    deviceId = DeviceId("device-1"),
                    startCommandId = CommandId("start-voice-turn-1"),
                    correlationId = START_CORRELATION,
                    admissionLeaseId = VoiceTurnAdmissionLeaseId("admission-lease-1"),
                ),
                issuedAtEpochMillis = 1_000L,
                admissionExpiresAtEpochMillis = 3_000L,
                admittedAtEpochMillis = 1_001L,
                admittedAt = mark(1L),
                bufferPolicy = policy,
            ),
        )
    }

    private fun frame(
        ordinal: ULong,
        bytes: ByteArray,
        mark: Long,
    ): VoiceTurnFrameInput = VoiceTurnFrameInput(
        turnId = TURN,
        ordinal = ordinal,
        formatId = VoiceTurnFrameFormatId("opus-16khz-mono-20ms"),
        payload = OpaqueBytes.copyOf(bytes),
        receivedAt = mark(mark),
    )

    private fun cancellation(mark: Long): VoiceTurnCancellation = VoiceTurnCancellation(
        commandId = CommandId("stop-voice-turn-1"),
        correlationId = CANCEL_CORRELATION,
        requestedAt = mark(mark),
    )

    private fun mark(millis: Long): VoiceTurnClockMark = VoiceTurnClockMark("test-boot", millis)

    private fun expectedFailure(
        code: String,
        retryable: Boolean,
        correlationId: CorrelationId,
    ): ExpectedFailure = ExpectedFailure(
        category = FailureCategory.UNAVAILABLE,
        code = FailureCode(code),
        retryable = retryable,
        correlationId = correlationId,
    )

    private fun assertCode(code: String, result: VoiceTurnOperationResult) {
        val rejected = assertIs<VoiceTurnOperationResult.Rejected>(result)
        assertEquals(code, rejected.failure.code.value)
    }

    private data class Fixture(
        val store: InMemoryVoiceTurnStateStore,
        val frames: InMemoryVoiceTurnFrameStore,
        val remote: ScriptedVoiceTurnRealtimePort,
        val coordinator: VoiceTurnCoordinator,
        val admission: VoiceTurnAdmission,
    )

    private companion object {
        val SESSION = VoiceTurnSessionId("session-1")
        val TURN = VoiceTurnId("turn-1")
        val ATTEMPT_1 = VoiceTurnRemoteAttemptId("remote-attempt-1")
        val ATTEMPT_2 = VoiceTurnRemoteAttemptId("remote-attempt-2")
        val START_CORRELATION = CorrelationId("voice-turn-correlation-1")
        val CANCEL_CORRELATION = CorrelationId("voice-turn-cancel-correlation-1")
    }
}
