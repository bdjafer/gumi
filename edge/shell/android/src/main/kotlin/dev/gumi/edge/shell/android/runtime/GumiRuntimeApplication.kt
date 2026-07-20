package dev.gumi.edge.shell.android.runtime

import android.app.Application
import dev.gumi.edge.runtime.host.RuntimeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Android process composition root. Activities and diagnostics never own this RuntimeHost. */
class GumiRuntimeApplication : Application() {
    private val processJob = SupervisorJob()
    private val processScope = CoroutineScope(processJob + Dispatchers.Main.immediate)

    internal val foregroundBridge: AndroidRuntimeForegroundBridge by lazy {
        AndroidRuntimeForegroundBridge()
    }

    internal val runtimeOwner: AndroidRuntimeProcessOwner by lazy {
        val portableHost = RuntimeHost(
            parentScope = processScope,
            prerequisites = AndroidRuntimePrerequisitePort(
                applicationContext,
                UnavailableAndroidRuntimeAssociationEvidence,
            ),
            execution = foregroundBridge,
            recovery = UnavailableAndroidRuntimeRecoveryPort,
        )
        AndroidRuntimeProcessOwner(
            parentScope = processScope,
            host = PortableAndroidRuntimeHostController(portableHost),
            foreground = foregroundBridge,
        )
    }
}
