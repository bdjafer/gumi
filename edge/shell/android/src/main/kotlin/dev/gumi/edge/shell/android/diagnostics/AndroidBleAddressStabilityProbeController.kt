package dev.gumi.edge.shell.android.diagnostics

import dev.gumi.edge.platforms.android.ble.AndroidBleObservationComparison
import dev.gumi.edge.sdk.EndpointCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BleAddressStabilityVerdict {
    SAME,
    CHANGED,
    INCONCLUSIVE,
}

data class BleAddressStabilityProbeState(
    val baselineCaptured: Boolean = false,
    val baselineCaptureEnabled: Boolean = false,
    val verdict: BleAddressStabilityVerdict? = null,
)

/**
 * Activity-local, equality-only stock-address diagnostic. It never connects, writes, pairs, bonds,
 * logs, or persists an endpoint. A comparison is useful only as a release-planning observation and
 * is never evidence of device identity, ownership, or capture authority.
 */
class AndroidBleAddressStabilityProbeController(
    private val compare: (EndpointCandidate, EndpointCandidate) -> AndroidBleObservationComparison,
) {
    private data class Baseline(
        val endpoint: EndpointCandidate,
        val generation: Long,
    )

    private val mutableState = MutableStateFlow(BleAddressStabilityProbeState())
    private val currentCandidates = linkedMapOf<String, EndpointCandidate>()
    private var baseline: Baseline? = null
    private var activeGeneration: Long? = null
    private var generationScanning: Boolean = false
    private var lastGeneration: Long = 0L

    val state: StateFlow<BleAddressStabilityProbeState> = mutableState.asStateFlow()

    /** Opens a fresh scan generation and rejects all observations carrying an older generation. */
    fun beginGeneration(generation: Long) {
        require(generation > lastGeneration) { "Scan generations must increase monotonically" }
        lastGeneration = generation
        activeGeneration = generation
        generationScanning = true
        currentCandidates.clear()
        project()
    }

    /** Records one driver-matched Omi candidate for the active generation only. */
    fun observe(generation: Long, endpoint: EndpointCandidate) {
        if (generation != activeGeneration || !generationScanning) return
        currentCandidates[endpoint.ephemeralId] = endpoint
        project()
    }

    /** Closes the candidate set so a single-candidate baseline can be captured deterministically. */
    fun finishGeneration(generation: Long) {
        if (generation != activeGeneration) return
        generationScanning = false
        project()
    }

    /**
     * Captures exactly one current Omi candidate. A baseline cannot be replaced or cherry-picked;
     * Activity replacement (or [reset]) is required to begin a different diagnostic run.
     */
    fun captureBaseline(): Boolean {
        if (baseline != null || generationScanning || currentCandidates.size != 1) return false
        val generation = activeGeneration ?: return false
        baseline = Baseline(currentCandidates.values.single(), generation)
        project()
        return true
    }

    /** Drops the only retained endpoint reference when the Activity/controller is destroyed. */
    fun reset() {
        baseline = null
        activeGeneration = null
        generationScanning = false
        lastGeneration = 0L
        currentCandidates.clear()
        mutableState.value = BleAddressStabilityProbeState()
    }

    private fun project() {
        val savedBaseline = baseline
        val generation = activeGeneration
        val verdict = when {
            savedBaseline == null -> null
            generation == null || generation <= savedBaseline.generation -> null
            generationScanning -> BleAddressStabilityVerdict.INCONCLUSIVE
            currentCandidates.size != 1 -> BleAddressStabilityVerdict.INCONCLUSIVE
            else -> runCatching {
                compare(savedBaseline.endpoint, currentCandidates.values.single()).toVerdict()
            }.getOrDefault(BleAddressStabilityVerdict.INCONCLUSIVE)
        }
        mutableState.value = BleAddressStabilityProbeState(
            baselineCaptured = savedBaseline != null,
            baselineCaptureEnabled =
                savedBaseline == null && !generationScanning && currentCandidates.size == 1,
            verdict = verdict,
        )
    }
}

private fun AndroidBleObservationComparison.toVerdict(): BleAddressStabilityVerdict = when (this) {
    AndroidBleObservationComparison.SAME -> BleAddressStabilityVerdict.SAME
    AndroidBleObservationComparison.CHANGED -> BleAddressStabilityVerdict.CHANGED
    AndroidBleObservationComparison.INCONCLUSIVE -> BleAddressStabilityVerdict.INCONCLUSIVE
}
