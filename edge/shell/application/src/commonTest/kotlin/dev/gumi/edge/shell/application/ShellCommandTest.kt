package dev.gumi.edge.shell.application

import dev.gumi.edge.sdk.CommandId
import dev.gumi.edge.sdk.CorrelationId
import dev.gumi.edge.sdk.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShellCommandTest {
    @Test
    fun `semantic command envelope is independent of transport details`() {
        val command = ShellCommand(
            id = CommandId("command-voice"),
            correlationId = CorrelationId("correlation-voice"),
            targetDeviceId = DeviceId("device-pendant"),
            issuedAtEpochMillis = 10_000,
            intent = ShellIntent.StartVoiceTurn(
                VoiceTurnAdmission(
                    leaseId = "admission-1",
                    expiresAtEpochMillis = 20_000,
                ),
            ),
        )

        assertEquals(DeviceId("device-pendant"), command.targetDeviceId)
        assertEquals("admission-1", (command.intent as ShellIntent.StartVoiceTurn).admission.leaseId)
    }

    @Test
    fun `VoiceTurn cannot be issued with an expired admission lease`() {
        assertFailsWith<IllegalArgumentException> {
            ShellCommand(
                id = CommandId("command-expired"),
                correlationId = CorrelationId("correlation-expired"),
                targetDeviceId = DeviceId("device-pendant"),
                issuedAtEpochMillis = 20_000,
                intent = ShellIntent.StartVoiceTurn(
                    VoiceTurnAdmission(
                        leaseId = "admission-expired",
                        expiresAtEpochMillis = 20_000,
                    ),
                ),
            )
        }
    }

    @Test
    fun `provider credentials cannot be represented in a VoiceTurn admission reference`() {
        assertFailsWith<IllegalArgumentException> {
            VoiceTurnAdmission(
                leaseId = "  ",
                expiresAtEpochMillis = 20_000,
            )
        }
    }

    @Test
    fun `update and physical confirmation intents require opaque identities`() {
        assertFailsWith<IllegalArgumentException> { ShellIntent.PrepareUpdate("") }
        assertFailsWith<IllegalArgumentException> { ShellIntent.ConfirmPhysicalAction("\n") }
    }
}
