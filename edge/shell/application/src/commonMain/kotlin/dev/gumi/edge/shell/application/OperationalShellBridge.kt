package dev.gumi.edge.shell.application

import dev.gumi.edge.runtime.capture.CaptureState
import dev.gumi.edge.runtime.capture.CaptureTruth
import dev.gumi.edge.runtime.operational.OperationalBacklog
import dev.gumi.edge.runtime.operational.OperationalBacklogScope
import dev.gumi.edge.runtime.operational.OperationalLinkState
import dev.gumi.edge.runtime.operational.OperationalPowerRefreshPort
import dev.gumi.edge.runtime.operational.OperationalPowerRefreshRequest
import dev.gumi.edge.runtime.operational.OperationalPowerRefreshResult
import dev.gumi.edge.runtime.operational.OperationalRuntimeLifecycle
import dev.gumi.edge.runtime.operational.OperationalRuntimeOperation
import dev.gumi.edge.runtime.operational.OperationalRuntimeProjection
import dev.gumi.edge.runtime.operational.OperationalStorageState
import dev.gumi.edge.runtime.host.RuntimeHostOperation
import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import dev.gumi.edge.sdk.capability.power.PowerStatus as DevicePowerStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface OperationalShellPublishResult {
    data class Forwarded(
        val update: DeviceShellUpdate,
        val downstream: ShellUpdateResult,
    ) : OperationalShellPublishResult

    /** An exact re-delivery of the last accepted immutable runtime projection. */
    data object Duplicate : OperationalShellPublishResult

    /** No shell publication was emitted and no bridge ownership state was changed. */
    data class Rejected(val failure: ExpectedFailure) : OperationalShellPublishResult
}

/**
 * Host-neutral translation between the deliberately limited operational runtime and shell model.
 *
 * This bridge does not infer capture, update, cloud-sync, or device-maintenance capabilities. It is
 * bound to one provisioned identity and one process-local operational runtime lineage. Runtime
 * session generation becomes shell owner generation; runtime sequence is forwarded without
 * renumbering. A host may call [publish] from a StateFlow collector or an authenticated local RPC
 * adapter without changing these semantics.
 * Terminal shell commands use a bounded process-local replay ledger. Exact same-ID requests replay
 * their first terminal result; reusing an ID with changed envelope or owner facts is a conflict.
 *
 * Only [OperationalBacklogScope.DEVICE] is forwarded as per-device sync truth. A process-global Android
 * spool remains visible through its process projection but is represented as unavailable here until
 * durable capture metadata can attribute pending bytes to this exact device.
 */
class OperationalShellBridge(
    private val provisionedDeviceId: DeviceId,
    private val displayName: String,
    private val projections: ShellRuntimeProjectionPort,
    private val powerRefresh: OperationalPowerRefreshPort,
    private val receiptClock: ShellClock,
) : ShellCommandPort {
    init {
        require(displayName.isNotBlank()) { "Operational shell display name cannot be blank" }
        require(displayName == displayName.trim()) {
            "Operational shell display name cannot have surrounding whitespace"
        }
        require(displayName.none(Char::isISOControl)) {
            "Operational shell display name cannot contain control characters"
        }
    }

    private val mutex = Mutex()
    /** Serializes effects for this device without blocking projection publication or other devices. */
    private val commandMutex = Mutex()
    private var accepted: AcceptedProjection? = null
    private var lastReceiptAtEpochMillis = 0L
    private val terminalCommands = LinkedHashMap<CommandId, OperationalShellCommandRecord>()

    suspend fun publish(
        projection: OperationalRuntimeProjection,
    ): OperationalShellPublishResult = mutex.withLock {
        val prior = accepted
        validateProjection(projection, prior)?.let {
            return@withLock OperationalShellPublishResult.Rejected(it)
        }
        if (prior?.projection?.sequence == projection.sequence) {
            return@withLock OperationalShellPublishResult.Duplicate
        }

        val ownership = resolveOwnership(projection, prior)
        if (ownership is OwnershipResult.Rejected) {
            return@withLock OperationalShellPublishResult.Rejected(ownership.failure)
        }
        ownership as OwnershipResult.Accepted

        val receivedAt = monotonicReceiptTime()
        val powerAdvanced = prior != null &&
            ownership.generation == prior.ownerGeneration &&
            projection.powerObservationRevision > prior.projection.powerObservationRevision
        val newOwner = prior == null || ownership.generation != prior.ownerGeneration
        val previousReceipts = prior?.receipts ?: AxisReceiptTimes.at(receivedAt)
        val receipts = AxisReceiptTimes(
            capture = if (newOwner) receivedAt else previousReceipts.capture,
            link = if (newOwner || projection.link != prior.projection.link || powerAdvanced) {
                receivedAt
            } else {
                previousReceipts.link
            },
            maintenance = if (newOwner || projection.lifecycle != prior.projection.lifecycle) {
                receivedAt
            } else {
                previousReceipts.maintenance
            },
            update = if (newOwner) receivedAt else previousReceipts.update,
            sync = if (
                newOwner ||
                projection.storage != prior.projection.storage ||
                projection.deviceBacklogOrNull() != prior.projection.deviceBacklogOrNull()
            ) {
                receivedAt
            } else {
                previousReceipts.sync
            },
            power = if (newOwner || powerAdvanced) receivedAt else previousReceipts.power,
            storage = if (newOwner || projection.storage != prior.projection.storage) {
                receivedAt
            } else {
                previousReceipts.storage
            },
            fault = if (newOwner || projection.lastFailure != prior.projection.lastFailure) {
                receivedAt
            } else {
                previousReceipts.fault
            },
        )
        val update = DeviceShellUpdate(
            ownerGeneration = ownership.generation,
            sequence = projection.sequence,
            snapshot = projection.toShellSnapshot(
                receipts = receipts,
                connectionGeneration = ownership.generation.takeIf { it > 0uL },
            ),
        )

        val downstream = try {
            projections.publish(update)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock OperationalShellPublishResult.Rejected(
                bridgeFailure(
                    FailureCategory.INTERNAL,
                    "OPERATIONAL_SHELL_PUBLICATION_FAILED",
                    retryable = true,
                ),
            )
        }
        when (downstream) {
            ShellUpdateResult.Applied,
            ShellUpdateResult.Duplicate,
            -> {
                accepted = AcceptedProjection(
                    projection = projection,
                    ownerGeneration = ownership.generation,
                    lineageOwner = ownership.lineageOwner,
                    receipts = receipts,
                )
                OperationalShellPublishResult.Forwarded(update, downstream)
            }

            ShellUpdateResult.Stale -> OperationalShellPublishResult.Rejected(
                bridgeFailure(
                    FailureCategory.REPLAYED,
                    "OPERATIONAL_SHELL_DOWNSTREAM_STALE",
                    retryable = false,
                ),
            )

            is ShellUpdateResult.Conflict -> OperationalShellPublishResult.Rejected(
                downstream.failure,
            )
        }
    }

    override suspend fun submit(command: RoutedShellCommand): ShellCommandResult =
        commandMutex.withLock { submitSerialized(command) }

    private suspend fun submitSerialized(command: RoutedShellCommand): ShellCommandResult {
        val activeOwner = mutex.withLock {
            replayOrConflictLocked(command)?.let { return it }
            if (command.command.targetDeviceId != provisionedDeviceId) {
                return recordTerminalLocked(
                    command,
                    terminal(
                        command.command,
                        ShellTerminalOutcome.REJECTED,
                        FailureCategory.UNAUTHORIZED,
                        "OPERATIONAL_SHELL_FOREIGN_DEVICE_COMMAND",
                    ),
                )
            }
            when (command.command.intent) {
                ShellIntent.StartRecording,
                ShellIntent.StopRecording,
                is ShellIntent.StartVoiceTurn,
                ShellIntent.StopVoiceTurn,
                -> return recordTerminalLocked(
                    command,
                    terminal(
                        command.command,
                        ShellTerminalOutcome.REJECTED,
                        FailureCategory.INCOMPATIBLE,
                        "OPERATIONAL_STOCK_CAPTURE_CONTROL_UNAVAILABLE",
                    ),
                )

                ShellIntent.BeginPairing,
                is ShellIntent.PrepareUpdate,
                ShellIntent.RequestShutdown,
                is ShellIntent.ConfirmPhysicalAction,
                -> return recordTerminalLocked(
                    command,
                    terminal(
                        command.command,
                        ShellTerminalOutcome.REJECTED,
                        FailureCategory.INCOMPATIBLE,
                        "OPERATIONAL_SHELL_INTENT_UNSUPPORTED",
                    ),
                )

                ShellIntent.RepeatStatus -> Unit
            }

            val prior = accepted
                ?: return recordTerminalLocked(
                    command,
                    terminal(
                        command.command,
                        ShellTerminalOutcome.REJECTED,
                        FailureCategory.REJECTED_POLICY,
                        "OPERATIONAL_SHELL_RUNTIME_NOT_READY",
                    ),
                )
            if (command.expectedOwnerGeneration != prior.ownerGeneration) {
                return recordTerminalLocked(
                    command,
                    terminal(
                        command.command,
                        ShellTerminalOutcome.REJECTED,
                        if (command.expectedOwnerGeneration < prior.ownerGeneration) {
                            FailureCategory.REPLAYED
                        } else {
                            FailureCategory.UNAUTHORIZED
                        },
                        if (command.expectedOwnerGeneration < prior.ownerGeneration) {
                            "OPERATIONAL_SHELL_STALE_OWNER_COMMAND"
                        } else {
                            "OPERATIONAL_SHELL_FOREIGN_OWNER_COMMAND"
                        },
                    ),
                )
            }
            val ownerOperation = prior.projection.ownerOperation
            val sessionGeneration = prior.projection.sessionGeneration
            if (
                ownerOperation == null ||
                sessionGeneration == null ||
                prior.projection.lifecycle != OperationalRuntimeLifecycle.READY ||
                prior.projection.link != OperationalLinkState.CONNECTED
            ) {
                return recordTerminalLocked(
                    command,
                    terminal(
                        command.command,
                        ShellTerminalOutcome.REJECTED,
                        FailureCategory.REJECTED_POLICY,
                        "OPERATIONAL_SHELL_RUNTIME_NOT_READY",
                    ),
                )
            }
            OperationalRuntimeOperation(ownerOperation, sessionGeneration)
        }

        val result = refreshPower(command.command, activeOwner)
        return mutex.withLock {
            replayOrConflictLocked(command)?.let { return@withLock it }
            recordTerminalLocked(command, result)
        }
    }

    private suspend fun refreshPower(
        command: ShellCommand,
        owner: OperationalRuntimeOperation,
    ): ShellCommandResult.Terminal {
        val request = OperationalPowerRefreshRequest(
            commandId = command.id,
            correlationId = command.correlationId,
            expectedOwner = owner,
        )
        val result = try {
            powerRefresh.refreshPower(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return terminal(
                command,
                ShellTerminalOutcome.OUTCOME_UNKNOWN,
                FailureCategory.INTERNAL,
                "OPERATIONAL_POWER_REFRESH_OUTCOME_UNKNOWN",
            )
        }
        if (result.request != request) {
            return terminal(
                command,
                ShellTerminalOutcome.OUTCOME_UNKNOWN,
                FailureCategory.CORRUPT,
                "OPERATIONAL_POWER_REFRESH_RESULT_MISMATCH",
            )
        }
        val resultFailure = when (result) {
            is OperationalPowerRefreshResult.Completed -> null
            is OperationalPowerRefreshResult.Failed -> result.failure
            is OperationalPowerRefreshResult.OutcomeUnknown -> result.failure
        }
        if (resultFailure != null && resultFailure.correlationId != command.correlationId) {
            return terminal(
                command,
                ShellTerminalOutcome.OUTCOME_UNKNOWN,
                FailureCategory.CORRUPT,
                "OPERATIONAL_POWER_REFRESH_FAILURE_MISMATCH",
            )
        }
        return when (result) {
            is OperationalPowerRefreshResult.Completed -> ShellCommandResult.Terminal(
                commandId = command.id,
                outcome = ShellTerminalOutcome.COMPLETED,
                replayed = result.replayed,
            )

            is OperationalPowerRefreshResult.Failed -> ShellCommandResult.Terminal(
                commandId = command.id,
                outcome = ShellTerminalOutcome.REJECTED,
                failure = result.failure,
                replayed = result.replayed,
            )

            is OperationalPowerRefreshResult.OutcomeUnknown -> ShellCommandResult.Terminal(
                commandId = command.id,
                outcome = ShellTerminalOutcome.OUTCOME_UNKNOWN,
                failure = result.failure,
                replayed = result.replayed,
            )
        }
    }

    private fun validateProjection(
        projection: OperationalRuntimeProjection,
        prior: AcceptedProjection?,
    ): ExpectedFailure? {
        if (projection.deviceId != null && projection.deviceId != provisionedDeviceId) {
            return bridgeFailure(
                FailureCategory.UNAUTHORIZED,
                "OPERATIONAL_SHELL_FOREIGN_DEVICE_PROJECTION",
                retryable = false,
            )
        }
        if (prior == null) return null
        return when {
            projection.sequence < prior.projection.sequence -> bridgeFailure(
                FailureCategory.REPLAYED,
                "OPERATIONAL_SHELL_STALE_SEQUENCE",
                retryable = false,
            )

            projection.sequence == prior.projection.sequence && projection != prior.projection ->
                bridgeFailure(
                    FailureCategory.CORRUPT,
                    "OPERATIONAL_SHELL_SEQUENCE_CONFLICT",
                    retryable = false,
                )

            samePowerLineage(projection, prior) &&
                projection.powerObservationRevision <
                prior.projection.powerObservationRevision -> bridgeFailure(
                    FailureCategory.REPLAYED,
                    "OPERATIONAL_SHELL_STALE_POWER_REVISION",
                    retryable = false,
                )

            samePowerLineage(projection, prior) &&
                projection.powerObservationRevision ==
                prior.projection.powerObservationRevision &&
                projection.power != prior.projection.power -> bridgeFailure(
                    FailureCategory.CORRUPT,
                    "OPERATIONAL_SHELL_POWER_REVISION_CONFLICT",
                    retryable = false,
                )

            else -> null
        }
    }

    private fun samePowerLineage(
        projection: OperationalRuntimeProjection,
        prior: AcceptedProjection,
    ): Boolean = projection.sessionGeneration == null ||
        projection.sessionGeneration == prior.ownerGeneration

    private fun resolveOwnership(
        projection: OperationalRuntimeProjection,
        prior: AcceptedProjection?,
    ): OwnershipResult {
        val declaredOwner = projection.ownerOperation
        val declaredGeneration = projection.sessionGeneration
        if (declaredOwner == null || declaredGeneration == null) {
            return OwnershipResult.Accepted(
                generation = prior?.ownerGeneration ?: 0uL,
                lineageOwner = prior?.lineageOwner,
            )
        }
        if (prior == null) {
            return OwnershipResult.Accepted(declaredGeneration, declaredOwner)
        }
        return when {
            declaredGeneration < prior.ownerGeneration -> OwnershipResult.Rejected(
                bridgeFailure(
                    FailureCategory.REPLAYED,
                    "OPERATIONAL_SHELL_STALE_OWNER_PROJECTION",
                    retryable = false,
                ),
            )

            declaredGeneration == prior.ownerGeneration &&
                prior.lineageOwner != null && declaredOwner != prior.lineageOwner ->
                OwnershipResult.Rejected(
                    bridgeFailure(
                        FailureCategory.UNAUTHORIZED,
                        "OPERATIONAL_SHELL_FOREIGN_OWNER_PROJECTION",
                        retryable = false,
                    ),
                )

            declaredGeneration == prior.ownerGeneration &&
                prior.projection.ownerOperation == null -> OwnershipResult.Rejected(
                    bridgeFailure(
                        FailureCategory.REPLAYED,
                        "OPERATIONAL_SHELL_ENDED_OWNER_RESURRECTION",
                        retryable = false,
                    ),
                )

            declaredGeneration > prior.ownerGeneration &&
                prior.lineageOwner != null &&
                declaredOwner.generation <= prior.lineageOwner.generation ->
                OwnershipResult.Rejected(
                    bridgeFailure(
                        FailureCategory.UNAUTHORIZED,
                        "OPERATIONAL_SHELL_FOREIGN_OWNER_PROJECTION",
                        retryable = false,
                    ),
                )

            else -> OwnershipResult.Accepted(declaredGeneration, declaredOwner)
        }
    }

    private fun monotonicReceiptTime(): Long {
        val sampled = receiptClock.nowEpochMillis()
        require(sampled >= 0L) { "Operational shell receipt clock cannot be negative" }
        return maxOf(sampled, lastReceiptAtEpochMillis).also { lastReceiptAtEpochMillis = it }
    }

    private fun OperationalRuntimeProjection.toShellSnapshot(
        receipts: AxisReceiptTimes,
        connectionGeneration: ULong?,
    ): DeviceShellSnapshot {
        val linkFreshness = if (link == OperationalLinkState.UNKNOWN) {
            ObservationFreshness.UNAVAILABLE
        } else {
            ObservationFreshness.FRESH
        }
        val powerValue = power?.toShellPower()
            ?: PowerStatus(state = PowerState.UNKNOWN)
        val powerFreshness = when {
            power == null -> ObservationFreshness.UNAVAILABLE
            lifecycle == OperationalRuntimeLifecycle.READY &&
                link == OperationalLinkState.CONNECTED -> ObservationFreshness.FRESH

            else -> ObservationFreshness.STALE
        }
        val storageFreshness = when (storage) {
            OperationalStorageState.READY,
            OperationalStorageState.DEGRADED,
            -> ObservationFreshness.FRESH

            OperationalStorageState.CLOSED,
            OperationalStorageState.OPENING,
            -> ObservationFreshness.UNAVAILABLE
        }
        val storageStatus = StorageStatus(
            state = if (storage == OperationalStorageState.READY) {
                StorageState.HEALTHY
            } else {
                StorageState.UNKNOWN
            },
        )
        val attributedBacklog = deviceBacklogOrNull()
        val maintenance = when (lifecycle) {
            OperationalRuntimeLifecycle.DEGRADED -> MaintenanceState.RECOVERY_REQUIRED
            OperationalRuntimeLifecycle.STOPPING -> MaintenanceState.SHUTTING_DOWN
            else -> MaintenanceState.NORMAL
        }
        val maintenanceFreshness = when (lifecycle) {
            OperationalRuntimeLifecycle.READY,
            OperationalRuntimeLifecycle.DEGRADED,
            OperationalRuntimeLifecycle.STOPPING,
            -> ObservationFreshness.FRESH

            OperationalRuntimeLifecycle.NEW,
            OperationalRuntimeLifecycle.STARTING,
            OperationalRuntimeLifecycle.STOPPED,
            OperationalRuntimeLifecycle.CLOSED,
            -> ObservationFreshness.UNAVAILABLE
        }
        val fault = FaultStatus(
            severity = if (lastFailure == null) FaultSeverity.NONE else FaultSeverity.RECOVERABLE,
            failure = lastFailure,
        )
        return DeviceShellSnapshot(
            deviceId = provisionedDeviceId,
            displayName = displayName,
            capture = AxisObservation(
                value = CaptureState(truth = CaptureTruth.Unverified()),
                authority = ProjectionAuthority.EDGE_INFERRED,
                observedAtEpochMillis = receipts.capture,
                freshness = ObservationFreshness.UNAVAILABLE,
                connectionSessionGeneration = null,
            ),
            link = AxisObservation(
                value = link.toShellLink(),
                authority = ProjectionAuthority.EDGE_INFERRED,
                observedAtEpochMillis = receipts.link,
                freshness = linkFreshness,
                connectionSessionGeneration = connectionGeneration,
            ),
            maintenance = edgeObservation(
                maintenance,
                receipts.maintenance,
                maintenanceFreshness,
            ),
            update = edgeObservation(
                UpdateStatus(UpdateStage.IDLE),
                receipts.update,
                ObservationFreshness.UNAVAILABLE,
            ),
            sync = edgeObservation(
                SyncStatus(
                    state = SyncState.UNKNOWN,
                    backlog = BacklogStatus(
                        pendingItems = attributedBacklog?.pendingChunkCount ?: 0uL,
                        pendingBytes = attributedBacklog?.retainedPayloadBytes ?: 0uL,
                    ),
                ),
                receipts.sync,
                if (
                    storage == OperationalStorageState.READY &&
                    attributedBacklog != null
                ) {
                    ObservationFreshness.FRESH
                } else {
                    ObservationFreshness.UNAVAILABLE
                },
            ),
            power = AxisObservation(
                value = powerValue,
                authority = if (power == null) {
                    ProjectionAuthority.EDGE_INFERRED
                } else {
                    ProjectionAuthority.DEVICE_REPORTED
                },
                observedAtEpochMillis = receipts.power,
                freshness = powerFreshness,
                connectionSessionGeneration = connectionGeneration,
            ),
            storage = edgeObservation(
                storageStatus,
                receipts.storage,
                storageFreshness,
            ),
            fault = edgeObservation(
                fault,
                receipts.fault,
                ObservationFreshness.FRESH,
            ),
        )
    }

    private fun OperationalRuntimeProjection.deviceBacklogOrNull(): OperationalBacklog? =
        backlog.takeIf { backlogScope == OperationalBacklogScope.DEVICE }

    private fun <T> edgeObservation(
        value: T,
        observedAtEpochMillis: Long,
        freshness: ObservationFreshness,
    ): AxisObservation<T> = AxisObservation(
        value = value,
        authority = ProjectionAuthority.EDGE_INFERRED,
        observedAtEpochMillis = observedAtEpochMillis,
        freshness = freshness,
        connectionSessionGeneration = null,
    )

    private fun DevicePowerStatus.toShellPower(): PowerStatus = PowerStatus(
        state = PowerState.OPERATIONAL,
        batteryPercent = batteryPercent,
        level = PowerLevel.UNKNOWN,
        charging = charging,
    )

    private fun OperationalLinkState.toShellLink(): LinkState = when (this) {
        OperationalLinkState.UNKNOWN -> LinkState.DEGRADED
        OperationalLinkState.CONNECTING -> LinkState.CONNECTING
        OperationalLinkState.CONNECTED -> LinkState.READY
        OperationalLinkState.DISCONNECTED -> LinkState.DISCONNECTED
    }

    private fun terminal(
        command: ShellCommand,
        outcome: ShellTerminalOutcome,
        category: FailureCategory,
        code: String,
    ): ShellCommandResult.Terminal = ShellCommandResult.Terminal(
        commandId = command.id,
        outcome = outcome,
        failure = ExpectedFailure(
            category = category,
            code = FailureCode(code),
            retryable = false,
            correlationId = command.correlationId,
        ),
    )

    private fun replayOrConflictLocked(
        command: RoutedShellCommand,
    ): ShellCommandResult.Terminal? {
        val prior = terminalCommands[command.command.id] ?: return null
        return if (prior.command == command) {
            prior.result.copy(replayed = true)
        } else {
            terminal(
                command.command,
                ShellTerminalOutcome.REJECTED,
                FailureCategory.CORRUPT,
                "OPERATIONAL_SHELL_COMMAND_ID_CONFLICT",
            )
        }
    }

    private fun recordTerminalLocked(
        command: RoutedShellCommand,
        result: ShellCommandResult.Terminal,
    ): ShellCommandResult.Terminal {
        terminalCommands[command.command.id] = OperationalShellCommandRecord(command, result)
        while (terminalCommands.size > TERMINAL_COMMAND_LIMIT) {
            terminalCommands.remove(terminalCommands.keys.first())
        }
        return result
    }

    private fun bridgeFailure(
        category: FailureCategory,
        code: String,
        retryable: Boolean,
    ): ExpectedFailure = ExpectedFailure(
        category = category,
        code = FailureCode(code),
        retryable = retryable,
    )

    private companion object {
        const val TERMINAL_COMMAND_LIMIT: Int = 64
    }
}

private data class AcceptedProjection(
    val projection: OperationalRuntimeProjection,
    val ownerGeneration: ULong,
    val lineageOwner: RuntimeHostOperation?,
    val receipts: AxisReceiptTimes,
)

private data class OperationalShellCommandRecord(
    val command: RoutedShellCommand,
    val result: ShellCommandResult.Terminal,
)

private data class AxisReceiptTimes(
    val capture: Long,
    val link: Long,
    val maintenance: Long,
    val update: Long,
    val sync: Long,
    val power: Long,
    val storage: Long,
    val fault: Long,
) {
    companion object {
        fun at(epochMillis: Long): AxisReceiptTimes = AxisReceiptTimes(
            capture = epochMillis,
            link = epochMillis,
            maintenance = epochMillis,
            update = epochMillis,
            sync = epochMillis,
            power = epochMillis,
            storage = epochMillis,
            fault = epochMillis,
        )
    }
}

private sealed interface OwnershipResult {
    data class Accepted(
        val generation: ULong,
        val lineageOwner: RuntimeHostOperation?,
    ) : OwnershipResult

    data class Rejected(val failure: ExpectedFailure) : OwnershipResult
}
