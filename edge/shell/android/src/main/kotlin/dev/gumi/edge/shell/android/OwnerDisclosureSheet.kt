package dev.gumi.edge.shell.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val OWNER_DISCLOSURE_SHEET_TAG = "owner-disclosure-sheet"
internal const val OWNER_DISCLOSURE_CONTENT_TAG = "owner-disclosure-content"
internal const val OWNER_DISCLOSURE_ACTION_TAG = "owner-disclosure-action"

/**
 * Reusable shell-level owner review surface. Long disclosures scroll independently while the
 * explicit action remains visible and cannot be confused with ordinary page content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OwnerDisclosureSheet(
    title: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onDismiss: () -> Unit,
    onAction: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maximumHeight = with(LocalDensity.current) { windowHeight.toDp() * 0.9f }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maximumHeight)
                .navigationBarsPadding()
                .testTag(OWNER_DISCLOSURE_SHEET_TAG),
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .testTag(OWNER_DISCLOSURE_CONTENT_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = onAction,
                    enabled = actionEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag(OWNER_DISCLOSURE_ACTION_TAG),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
