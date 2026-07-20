package dev.gumi.edge.shell.android

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Private debug-only host for deterministic Compose instrumentation on a timed-out handset. */
class OwnerDisclosureTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }
}
