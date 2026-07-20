package dev.gumi.edge.shell.application

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.ExpectedFailure
import kotlinx.coroutines.flow.StateFlow

/**
 * Portable application control surface. Android may call it in-process; a Raspberry Pi host may
 * expose the same semantics through authenticated local RPC without changing runtime ownership.
 */
interface ShellApplication {
    val projection: StateFlow<FleetShellProjection>

    /** Returns command acceptance/terminal evidence; acquired truth arrives through [projection]. */
    suspend fun submit(command: ShellCommand): ShellCommandResult
}

sealed interface ShellCommandResult {
    val commandId: CommandId

    /** Serialized by the runtime, but not evidence that the requested hardware state is acquired. */
    data class Accepted(
        override val commandId: CommandId,
    ) : ShellCommandResult

    data class Terminal(
        override val commandId: CommandId,
        val outcome: ShellTerminalOutcome,
        val failure: ExpectedFailure? = null,
        val replayed: Boolean = false,
    ) : ShellCommandResult {
        init {
            val failed = outcome in FAILURE_OUTCOMES
            require(failed == (failure != null)) {
                "Refused, rejected, and failed commands require one expected failure; successful outcomes forbid it"
            }
        }
    }
}

enum class ShellTerminalOutcome {
    COMPLETED,
    NO_OP,
    REFUSED,
    REJECTED,
    FAILED,
    /** The command crossed an effect boundary but its terminal state cannot yet be established. */
    OUTCOME_UNKNOWN,
}

private val FAILURE_OUTCOMES = setOf(
    ShellTerminalOutcome.REFUSED,
    ShellTerminalOutcome.REJECTED,
    ShellTerminalOutcome.FAILED,
    ShellTerminalOutcome.OUTCOME_UNKNOWN,
)
