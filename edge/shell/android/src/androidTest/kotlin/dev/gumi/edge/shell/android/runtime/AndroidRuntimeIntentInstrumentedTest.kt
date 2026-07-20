package dev.gumi.edge.shell.android.runtime

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gumi.edge.runtime.host.RuntimeHostRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRuntimeIntentInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun preparedCommandKeepsExactIdentityAcrossRetryWhileNewCommandsUseNewUuids() {
        val prepared = AndroidRuntimeServiceLauncher.prepareExplicitStart(context)
        val retryDecode = AndroidRuntimeServiceIntents.decode(prepared.intent)
        val secondRetryDecode = AndroidRuntimeServiceIntents.decode(prepared.intent)
        val another = AndroidRuntimeServiceLauncher.prepareExplicitStart(context)

        assertTrue(retryDecode is AndroidRuntimeCommandDecodeResult.Valid)
        assertTrue(secondRetryDecode is AndroidRuntimeCommandDecodeResult.Valid)
        retryDecode as AndroidRuntimeCommandDecodeResult.Valid
        secondRetryDecode as AndroidRuntimeCommandDecodeResult.Valid
        assertEquals(prepared.commandId, retryDecode.request.id)
        assertEquals(retryDecode.request, secondRetryDecode.request)
        assertNotEquals(prepared.commandId, another.commandId)
        assertNotEquals(prepared.correlationId, another.correlationId)
    }

    @Test
    fun wrongTypedIntentExtraFailsClosedInsteadOfEscapingServiceDecode() {
        val malformed = Intent(context, GumiRuntimeService::class.java)
            .setAction(AndroidRuntimeServiceContract.ACTION_START_EXPLICIT)
            .putExtra(AndroidRuntimeServiceContract.EXTRA_COMMAND_ID, 42)
            .putExtra(AndroidRuntimeServiceContract.EXTRA_CORRELATION_ID, "correlation-safe")

        assertTrue(
            AndroidRuntimeServiceIntents.decode(malformed) is
                AndroidRuntimeCommandDecodeResult.Invalid,
        )
    }

    @Test
    fun preparedStopDecodesOnlyAsExplicitUserStop() {
        val prepared = AndroidRuntimeServiceLauncher.prepareExplicitStop(context)
        val valid = AndroidRuntimeServiceIntents.decode(prepared.intent)

        assertTrue(valid is AndroidRuntimeCommandDecodeResult.Valid)
        valid as AndroidRuntimeCommandDecodeResult.Valid
        assertTrue(valid.request is RuntimeHostRequest.Stop)
        assertEquals(prepared.commandId, valid.request.id)
    }
}
