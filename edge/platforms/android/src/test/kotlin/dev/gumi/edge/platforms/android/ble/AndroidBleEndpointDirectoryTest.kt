package dev.gumi.edge.platforms.android.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidBleEndpointDirectoryTest {
    @Test
    fun `process local observation comparison exposes equality only`() {
        assertEquals(
            AndroidBleObservationComparison.SAME,
            compareProcessLocalBleObservationTokens("opaque-token-a", "opaque-token-a"),
        )
        assertEquals(
            AndroidBleObservationComparison.CHANGED,
            compareProcessLocalBleObservationTokens("opaque-token-a", "opaque-token-b"),
        )
        assertEquals(
            AndroidBleObservationComparison.INCONCLUSIVE,
            compareProcessLocalBleObservationTokens(null, "opaque-token-b"),
        )
        assertEquals(
            AndroidBleObservationComparison.INCONCLUSIVE,
            compareProcessLocalBleObservationTokens("opaque-token-a", null),
        )
    }
}
