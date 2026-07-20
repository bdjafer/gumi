package dev.gumi.edge.shell.application

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Host epoch clock used only for projection freshness and human-readable observation age. */
fun interface ShellClock {
    fun nowEpochMillis(): Long
}

/**
 * One immutable publication from a concrete runtime owner.
 *
 * [ownerGeneration] changes whenever the process-level owner for a managed device is replaced.
 * [sequence] is monotonic only within that owner generation. Together they prevent a late callback
 * from an obsolete runtime from replacing newer shell truth.
 */
data class DeviceShellUpdate(
    val ownerGeneration: ULong,
    val sequence: Long,
    val snapshot: DeviceShellSnapshot,
) {
    init {
        require(sequence >= 0) { "Shell update sequence cannot be negative" }
    }
}

data class RoutedShellCommand(
    val command: ShellCommand,
    val expectedOwnerGeneration: ULong,
)

/**
 * Adapter boundary from the applicative shell into the concrete per-device runtime owner.
 *
 * Implementations must check [RoutedShellCommand.expectedOwnerGeneration] before admitting an
 * effect. Expected operational failures and outcome-unknown cases are returned as values; they are
 * not thrown. Repeating the same [CommandId] must converge through the runtime's idempotency ledger.
 */
fun interface ShellCommandPort {
    suspend fun submit(command: RoutedShellCommand): ShellCommandResult
}

/** Runtime-facing input side of the portable shell; usable in-process or behind authenticated RPC. */
interface ShellRuntimeProjectionPort {
    suspend fun publish(update: DeviceShellUpdate): ShellUpdateResult

    suspend fun forget(
        deviceId: DeviceId,
        expectedOwnerGeneration: ULong,
    ): ShellForgetResult

    suspend fun refresh(): FleetShellProjection
}

sealed interface ShellUpdateResult {
    data object Applied : ShellUpdateResult

    /** The exact same immutable publication was already applied. */
    data object Duplicate : ShellUpdateResult

    /** An older owner or sequence arrived after a newer publication. */
    data object Stale : ShellUpdateResult

    /** One version was reused for different content; neither value is silently preferred. */
    data class Conflict(val failure: ExpectedFailure) : ShellUpdateResult
}

sealed interface ShellForgetResult {
    data object Removed : ShellForgetResult

    data object NotManaged : ShellForgetResult

    /** A former owner cannot remove a device now represented by a newer owner. */
    data object StaleOwner : ShellForgetResult

    /** A future/unobserved owner generation is never accepted as removal authority. */
    data object OwnerMismatch : ShellForgetResult
}

/**
 * Portable multi-device control-plane application.
 *
 * State mutations take a short fleet mutex. Command effects run outside it, so one slow device cannot
 * block status/freshness updates or commands for another device. Removing a device means explicit
 * deprovisioning; a normal disconnect must instead publish a conservative disconnected snapshot.
 */
class DefaultShellApplication(
    private val commandPort: ShellCommandPort,
    private val clock: ShellClock,
    private val freshnessPolicy: ShellFreshnessPolicy = ShellFreshnessPolicy(),
) : ShellApplication, ShellRuntimeProjectionPort {
    private val stateMutex = Mutex()
    private val publications = linkedMapOf<DeviceId, DeviceShellUpdate>()
    private val mutableProjection = MutableStateFlow(
        FleetShellProjector.aggregate(emptyList()),
    )
    private var lastProjectedAtEpochMillis: Long = 0L

    override val projection: StateFlow<FleetShellProjection> = mutableProjection.asStateFlow()

    override suspend fun publish(update: DeviceShellUpdate): ShellUpdateResult = stateMutex.withLock {
        val existing = publications[update.snapshot.deviceId]
        when {
            existing == null -> apply(update)
            update.ownerGeneration < existing.ownerGeneration -> ShellUpdateResult.Stale
            update.ownerGeneration > existing.ownerGeneration -> apply(update)
            update.sequence < existing.sequence -> ShellUpdateResult.Stale
            update.sequence > existing.sequence -> apply(update)
            update == existing -> ShellUpdateResult.Duplicate
            else -> ShellUpdateResult.Conflict(
                ExpectedFailure(
                    category = FailureCategory.CORRUPT,
                    code = FailureCode("SHELL_UPDATE_VERSION_CONFLICT"),
                    retryable = false,
                ),
            )
        }
    }

    override suspend fun forget(
        deviceId: DeviceId,
        expectedOwnerGeneration: ULong,
    ): ShellForgetResult = stateMutex.withLock {
        val existing = publications[deviceId] ?: return@withLock ShellForgetResult.NotManaged
        when {
            expectedOwnerGeneration < existing.ownerGeneration -> ShellForgetResult.StaleOwner
            expectedOwnerGeneration > existing.ownerGeneration -> ShellForgetResult.OwnerMismatch
            else -> {
                publications.remove(deviceId)
                reproject()
                ShellForgetResult.Removed
            }
        }
    }

    /** Re-evaluates timestamp freshness even when no runtime publication has arrived. */
    override suspend fun refresh(): FleetShellProjection = stateMutex.withLock {
        reproject()
        mutableProjection.value
    }

    override suspend fun submit(command: ShellCommand): ShellCommandResult {
        val routed = stateMutex.withLock {
            val owner = publications[command.targetDeviceId]
                ?: return rejected(command, "SHELL_DEVICE_NOT_MANAGED")
            RoutedShellCommand(command, owner.ownerGeneration)
        }

        val result = try {
            commandPort.submit(routed)
        } catch (cancelled: CancellationException) {
            // The caller can retry the same CommandId to converge. Never invent a terminal result.
            throw cancelled
        } catch (_: Exception) {
            return outcomeUnknown(command, "SHELL_COMMAND_PORT_OUTCOME_UNKNOWN")
        }

        if (result.commandId != command.id) {
            return outcomeUnknown(command, "SHELL_COMMAND_RESULT_ID_MISMATCH")
        }
        if (result is ShellCommandResult.Accepted) {
            val ownerUnchanged = stateMutex.withLock {
                publications[command.targetDeviceId]?.ownerGeneration == routed.expectedOwnerGeneration
            }
            if (!ownerUnchanged) {
                return outcomeUnknown(command, "SHELL_OWNER_CHANGED_AFTER_ACCEPTANCE")
            }
        }
        return result
    }

    private fun apply(update: DeviceShellUpdate): ShellUpdateResult {
        publications[update.snapshot.deviceId] = update
        reproject()
        return ShellUpdateResult.Applied
    }

    private fun reproject() {
        val now = monotonicProjectionTime()
        val devices = publications.values.map { publication ->
            ShellProjector.project(publication.snapshot, now, freshnessPolicy)
        }
        mutableProjection.value = FleetShellProjector.aggregate(devices)
    }

    private fun monotonicProjectionTime(): Long {
        val sampled = clock.nowEpochMillis()
        require(sampled >= 0) { "Shell clock cannot be negative" }
        val monotonic = maxOf(sampled, lastProjectedAtEpochMillis)
        lastProjectedAtEpochMillis = monotonic
        return monotonic
    }

    private fun rejected(command: ShellCommand, code: String): ShellCommandResult =
        ShellCommandResult.Terminal(
            commandId = command.id,
            outcome = ShellTerminalOutcome.REJECTED,
            failure = ExpectedFailure(
                category = FailureCategory.REJECTED_POLICY,
                code = FailureCode(code),
                retryable = false,
                correlationId = command.correlationId,
            ),
        )

    private fun outcomeUnknown(command: ShellCommand, code: String): ShellCommandResult =
        ShellCommandResult.Terminal(
            commandId = command.id,
            outcome = ShellTerminalOutcome.OUTCOME_UNKNOWN,
            failure = ExpectedFailure(
                category = FailureCategory.INTERNAL,
                code = FailureCode(code),
                retryable = false,
                correlationId = command.correlationId,
            ),
        )
}
