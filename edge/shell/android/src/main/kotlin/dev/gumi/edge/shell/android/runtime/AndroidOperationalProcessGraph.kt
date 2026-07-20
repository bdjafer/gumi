package dev.gumi.edge.shell.android.runtime

import dev.gumi.edge.runtime.host.RuntimeHost
import dev.gumi.edge.runtime.host.RuntimeHostPrerequisitePort
import dev.gumi.edge.runtime.operational.OperationalRuntimeNode
import dev.gumi.edge.runtime.operational.OperationalRuntimeRegistration
import dev.gumi.edge.runtime.operational.OperationalRuntimeRegistry
import dev.gumi.edge.runtime.operational.OperationalStoragePort
import dev.gumi.edge.runtime.operational.ProcessGlobalOperationalStorageOwner
import dev.gumi.edge.sdk.DeviceId
import kotlinx.coroutines.CoroutineScope

/** Creates one heterogeneous device runtime against the exact process-global storage owner supplied. */
internal data class AndroidOperationalRuntimeFactory(
    val deviceId: DeviceId,
    val create: (OperationalStoragePort) -> OperationalRuntimeNode,
)

/**
 * Scalable Android process graph: one RuntimeHost/foreground bridge, one physical spool owner, and one
 * DeviceId-keyed runtime registry. It creates no automatic start source and imports no device driver.
 */
internal data class AndroidOperationalProcessGraph(
    val owner: AndroidRuntimeProcessOwner,
    val registry: OperationalRuntimeRegistry,
    val storage: ProcessGlobalOperationalStorageOwner,
)

internal fun createAndroidOperationalProcessGraph(
    parentScope: CoroutineScope,
    prerequisites: RuntimeHostPrerequisitePort,
    foreground: AndroidRuntimeForegroundBridge,
    physicalStorage: OperationalStoragePort,
    runtimeFactories: Collection<AndroidOperationalRuntimeFactory>,
): AndroidOperationalProcessGraph {
    require(runtimeFactories.isNotEmpty()) {
        "Android operational process graph requires at least one provisioned runtime"
    }
    require(runtimeFactories.map { it.deviceId }.toSet().size == runtimeFactories.size) {
        "Android operational process graph requires unique device identities"
    }
    val processStorage = ProcessGlobalOperationalStorageOwner(physicalStorage)
    val registry = OperationalRuntimeRegistry(
        parentScope = parentScope,
        registrations = runtimeFactories.map { factory ->
            OperationalRuntimeRegistration(
                factory.deviceId,
                factory.create(processStorage),
            )
        },
    )
    val host = RuntimeHost(
        parentScope = parentScope,
        prerequisites = prerequisites,
        execution = foreground,
        recovery = registry,
    )
    val owner = AndroidRuntimeProcessOwner(
        parentScope = parentScope,
        host = PortableAndroidRuntimeHostController(host),
        foreground = foreground,
        processResources = AndroidOperationalProcessResources(registry, processStorage),
    )
    return AndroidOperationalProcessGraph(owner, registry, processStorage)
}
