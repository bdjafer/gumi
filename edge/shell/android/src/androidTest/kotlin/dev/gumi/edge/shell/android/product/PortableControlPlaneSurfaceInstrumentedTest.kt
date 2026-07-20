package dev.gumi.edge.shell.android.product

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.gumi.edge.sdk.DeviceId
import dev.gumi.edge.shell.application.ShellControlAction
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class PortableControlPlaneSurfaceInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun collisionRiskIsAnAssertiveLiveRegion() {
        compose.setContent {
            PortableControlPlaneSurface(
                presentation = PortableControlPlaneFixtures.collisionRisk,
                onSelectDevice = {},
                onAction = { _, _ -> },
            )
        }

        compose.onNodeWithTag("fleet-workflow")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "More than one microphone may be active — stop or verify each device",
                ),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            )
    }

    @Test
    fun deviceFocusReturnsOnlyTheStablePresentationIdentity() {
        val selected = AtomicReference<DeviceId?>(null)
        compose.setContent {
            PortableControlPlaneSurface(
                presentation = PortableControlPlaneFixtures.allVerifiedOff,
                onSelectDevice = selected::set,
                onAction = { _, _ -> },
            )
        }

        compose.onNodeWithTag("managed-device-1").performClick()

        assertEquals(DeviceId("fixture-glasses"), selected.get())
    }

    @Test
    fun verifiedOffShowsEnabledStartAndAnExplicitDisabledStopReason() {
        val action = AtomicReference<ShellControlAction?>(null)
        compose.setContent {
            PortableControlPlaneSurface(
                presentation = PortableControlPlaneFixtures.allVerifiedOff,
                onSelectDevice = {},
                onAction = { _, value -> action.set(value) },
            )
        }

        scrollTo("action-start_recording")
        compose.onNodeWithTag("action-start_recording")
            .assertIsEnabled()
            .performClick()
        assertEquals(ShellControlAction.START_RECORDING, action.get())

        scrollTo("action-stop_capture")
        compose.onNodeWithTag("action-stop_capture")
            .assertIsNotEnabled()
        compose.onNodeWithTag("blocked-stop_capture")
            .assertTextContains("CAPTURE_ALREADY_VERIFIED_OFF", substring = true)
    }

    @Test
    fun activeRecordingAllowsOnlySameDeviceVoiceTurnOverlayAndSafetyStop() {
        compose.setContent {
            PortableControlPlaneSurface(
                presentation = PortableControlPlaneFixtures.activeRecording,
                onSelectDevice = {},
                onAction = { _, _ -> },
            )
        }

        scrollTo("action-start_recording")
        compose.onNodeWithTag("action-start_recording")
            .assertIsNotEnabled()
        scrollTo("action-stop_capture")
        compose.onNodeWithTag("action-stop_capture")
            .assertIsEnabled()
        scrollTo("action-start_voice_turn")
        compose.onNodeWithTag("action-start_voice_turn")
            .assertIsEnabled()
    }

    private fun scrollTo(testTag: String) {
        compose.onNodeWithTag("control-plane-scroll")
            .performScrollToNode(hasTestTag(testTag))
    }
}
