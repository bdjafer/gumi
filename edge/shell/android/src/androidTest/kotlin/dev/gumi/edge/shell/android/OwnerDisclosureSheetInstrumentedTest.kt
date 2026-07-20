package dev.gumi.edge.shell.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnerDisclosureSheetInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<OwnerDisclosureTestActivity>()

    @Test
    fun longDisclosureScrollsWhileExplicitActionRemainsVisible() {
        val actionCount = AtomicInteger(0)
        compose.setContent {
            MaterialTheme {
                OwnerDisclosureSheet(
                    title = "Owner-reviewed operation",
                    actionLabel = "Run disclosed operation",
                    actionEnabled = true,
                    onDismiss = {},
                    onAction = { actionCount.incrementAndGet() },
                ) {
                    repeat(40) { index -> Text("Disclosure detail ${index + 1}") }
                }
            }
        }

        compose.onNodeWithTag(OWNER_DISCLOSURE_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OWNER_DISCLOSURE_ACTION_TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, actionCount.get())

        compose.onNodeWithTag(OWNER_DISCLOSURE_CONTENT_TAG)
            .performScrollToNode(hasText("Disclosure detail 40"))
    }
}
