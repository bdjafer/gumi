package dev.gumi.edge.shell.application

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.ExpectedFailure
import dev.gumi.edge.sdk.FailureCategory
import dev.gumi.edge.sdk.FailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShellApplicationTest {
    private val commandId = CommandId("shell-command-1")
    private val failure = ExpectedFailure(
        category = FailureCategory.REJECTED_POLICY,
        code = FailureCode("CAPTURE_REFUSED"),
        retryable = true,
    )

    @Test
    fun `accepted receipt does not claim acquired state`() {
        val result: ShellCommandResult = ShellCommandResult.Accepted(commandId)
        assertEquals(commandId, result.commandId)
    }

    @Test
    fun `failed terminal outcomes require expected failure evidence`() {
        assertFailsWith<IllegalArgumentException> {
            ShellCommandResult.Terminal(commandId, ShellTerminalOutcome.REJECTED)
        }
        assertEquals(
            failure,
            ShellCommandResult.Terminal(
                commandId,
                ShellTerminalOutcome.REJECTED,
                failure,
            ).failure,
        )
    }

    @Test
    fun `successful terminal outcomes forbid contradictory failure evidence`() {
        assertFailsWith<IllegalArgumentException> {
            ShellCommandResult.Terminal(
                commandId,
                ShellTerminalOutcome.COMPLETED,
                failure,
            )
        }
    }
}
