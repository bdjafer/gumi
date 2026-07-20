package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class OmiCv1ApplicationImage0UpdateExecutorTest {
    @Test
    fun `executes upload verify confirm verify reset and stops at pending validation`() = runTest {
        val prepared = preparedPlan()
        val session = FakeSession(
            inspections = ArrayDeque(
                listOf(
                    stableInspection(prepared.endpoint, prepared.release.source),
                    stagedInspection(prepared.endpoint, prepared.release),
                    stagedInspection(prepared.endpoint, prepared.release, confirmed = true),
                ),
            ),
        )
        val events = mutableListOf<OmiCv1ApplicationUpdateProgress>()
        val executor = OmiCv1ApplicationImage0UpdateExecutor(
            sessions = OmiCv1ApplicationImage0UpdateSessionFactory { session },
            clock = MonotonicMillisClock { 100 },
        )

        val result = executor.execute(authorization(prepared), events::add)

        assertEquals(
            listOf("inspect", "upload", "inspect", "confirm", "inspect", "reset", "release"),
            session.calls,
        )
        assertEquals(prepared.release.target.mcubootImageHash, result.expectedApplicationHash)
        assertEquals(prepared.release.source.networkHash, result.expectedNetworkHash)
        assertEquals(OmiCv1ApplicationUpdateStage.AWAITING_POST_REBOOT_VALIDATION, events.last().stage)
    }

    @Test
    fun `expired authorization opens no transport`() = runTest {
        val prepared = preparedPlan()
        var opened = false
        val executor = OmiCv1ApplicationImage0UpdateExecutor(
            sessions = OmiCv1ApplicationImage0UpdateSessionFactory {
                opened = true
                error("must not open")
            },
            clock = MonotonicMillisClock { 101 },
        )
        val expired = OmiCv1ApplicationUpdateAuthorization(prepared, prepared.planId, 100)

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            executor.execute(expired) {}
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.AUTHORIZATION_EXPIRED, error.code)
        assertTrue(!opened)
    }

    @Test
    fun `authorization is expired at its exact monotonic deadline`() = runTest {
        val prepared = preparedPlan()
        var opened = false
        val executor = OmiCv1ApplicationImage0UpdateExecutor(
            sessions = OmiCv1ApplicationImage0UpdateSessionFactory {
                opened = true
                error("must not open")
            },
            clock = MonotonicMillisClock { 100 },
        )
        val expired = OmiCv1ApplicationUpdateAuthorization(prepared, prepared.planId, 100)

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            executor.execute(expired) {}
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.AUTHORIZATION_EXPIRED, error.code)
        assertTrue(!opened)
    }

    @Test
    fun `authorization is one shot even after a completed attempt`() = runTest {
        val prepared = preparedPlan()
        val auth = authorization(prepared)
        fun session() = FakeSession(
            ArrayDeque(
                listOf(
                    stableInspection(prepared.endpoint, prepared.release.source),
                    stagedInspection(prepared.endpoint, prepared.release),
                    stagedInspection(prepared.endpoint, prepared.release, confirmed = true),
                ),
            ),
        )
        val executor = OmiCv1ApplicationImage0UpdateExecutor(
            sessions = OmiCv1ApplicationImage0UpdateSessionFactory { session() },
            clock = MonotonicMillisClock { 100 },
        )
        executor.execute(auth) {}

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            executor.execute(auth) {}
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.AUTHORIZATION_REUSED, error.code)
    }

    @Test
    fun `staged-state rejection never confirms or resets and always releases`() = runTest {
        val prepared = preparedPlan()
        val session = FakeSession(
            inspections = ArrayDeque(
                listOf(
                    stableInspection(prepared.endpoint, prepared.release.source),
                    stableInspection(prepared.endpoint, prepared.release.source),
                ),
            ),
        )
        val executor = OmiCv1ApplicationImage0UpdateExecutor(
            sessions = OmiCv1ApplicationImage0UpdateSessionFactory { session },
            clock = MonotonicMillisClock { 100 },
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            executor.execute(authorization(prepared)) {}
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.STAGED_STATE_REJECTED, error.code)
        assertEquals(listOf("inspect", "upload", "inspect", "release"), session.calls)
    }

    @Test
    fun `strict execution revalidates full source state before uploading`() = runTest {
        val prepared = preparedPlan()
        val networkUnobserved = networkUnobservedInspection(prepared.endpoint, prepared.release.source)
        val session = FakeSession(ArrayDeque(listOf(networkUnobserved)))
        val executor = OmiCv1ApplicationImage0UpdateExecutor(
            sessions = OmiCv1ApplicationImage0UpdateSessionFactory { session },
            clock = MonotonicMillisClock { 100 },
        )

        val error = assertFailsWith<OmiCv1ApplicationUpdateException> {
            executor.execute(authorization(prepared)) {}
        }

        assertEquals(OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED, error.code)
        assertEquals(listOf("inspect", "release"), session.calls)
    }

    @Test
    fun `owned-unit execution accepts network-unobserved state at every read`() = runTest {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest).copy(
            source = release(fixture.manifest).source.copy(
                networkEvidencePolicy =
                    OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
            ),
        )
        val prepared = OmiCv1ApplicationUpdatePlanner.prepare(
            endpoint,
            networkUnobservedInspection(endpoint, release.source),
            release,
            fixture.bytes,
        )
        val session = FakeSession(
            ArrayDeque(
                listOf(
                    networkUnobservedInspection(endpoint, release.source),
                    networkUnobservedStagedInspection(endpoint, release),
                    networkUnobservedStagedInspection(endpoint, release, confirmed = true),
                ),
            ),
        )
        val executor = OmiCv1ApplicationImage0UpdateExecutor(
            sessions = OmiCv1ApplicationImage0UpdateSessionFactory { session },
            clock = MonotonicMillisClock { 100 },
        )

        val result = executor.execute(authorization(prepared)) {}

        assertEquals(
            OmiCv1NetworkImageEvidencePolicy.ALLOW_COMPLETELY_UNOBSERVED,
            result.networkEvidencePolicy,
        )
        assertEquals(
            listOf("inspect", "upload", "inspect", "confirm", "inspect", "reset", "release"),
            session.calls,
        )
    }

    @Test
    fun `cancellation during upload cancels transport and never confirms or resets`() = runTest {
        val prepared = preparedPlan()
        val uploadStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val session = object : OmiCv1ApplicationImage0UpdateSession {
            val calls = mutableListOf<String>()

            override suspend fun upload(imageBytes: ByteArray, onProgress: (Int, Int) -> Unit) {
                calls += "upload"
                uploadStarted.complete(Unit)
                neverCompletes.await()
            }

            override suspend fun inspect(): FirmwareImageStateInspection {
                calls += "inspect"
                return stableInspection(prepared.endpoint, prepared.release.source)
            }

            override suspend fun confirm(mcubootImageHash: FirmwareImageHash) {
                calls += "confirm"
            }

            override suspend fun requestReset(): Boolean {
                calls += "reset"
                return true
            }

            override fun cancel() {
                calls += "cancel"
            }

            override fun release() {
                calls += "release"
            }
        }
        val executor = OmiCv1ApplicationImage0UpdateExecutor(
            sessions = OmiCv1ApplicationImage0UpdateSessionFactory { session },
            clock = MonotonicMillisClock { 100 },
        )
        val job = launch {
            executor.execute(authorization(prepared)) {}
        }

        uploadStarted.await()
        job.cancelAndJoin()

        assertEquals(listOf("inspect", "upload", "cancel", "release"), session.calls)
    }

    private fun preparedPlan(): OmiCv1PreparedApplicationUpdate {
        val fixture = McubootApplicationArtifactInspectorTest.mcubootFixture()
        val endpoint = endpoint()
        val release = release(fixture.manifest)
        return OmiCv1ApplicationUpdatePlanner.prepare(
            endpoint,
            stableInspection(endpoint, release.source),
            release,
            fixture.bytes,
        )
    }

    private fun authorization(plan: OmiCv1PreparedApplicationUpdate) =
        OmiCv1ApplicationUpdateAuthorization(plan, plan.planId, 1_000)

    private class FakeSession(
        private val inspections: ArrayDeque<FirmwareImageStateInspection>,
    ) : OmiCv1ApplicationImage0UpdateSession {
        val calls = mutableListOf<String>()

        override suspend fun upload(imageBytes: ByteArray, onProgress: (Int, Int) -> Unit) {
            calls += "upload"
            onProgress(imageBytes.size, imageBytes.size)
        }

        override suspend fun inspect(): FirmwareImageStateInspection {
            calls += "inspect"
            return inspections.removeFirst()
        }

        override suspend fun confirm(mcubootImageHash: FirmwareImageHash) {
            calls += "confirm"
        }

        override suspend fun requestReset(): Boolean {
            calls += "reset"
            return true
        }

        override fun cancel() {
            calls += "cancel"
        }

        override fun release() {
            calls += "release"
        }
    }
}
