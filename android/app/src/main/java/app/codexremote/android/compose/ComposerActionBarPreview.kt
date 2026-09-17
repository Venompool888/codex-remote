package app.codexremote.android.compose

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.R

@Composable
fun SampleComposerActionBarContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("1. Empty Draft (Collapsed, Can't Send):", fontSize = 12.sp)
        Box(modifier = Modifier.background(colorResource(R.color.app_composer))) {
            ComposerActionBar(
                state = ComposerActionBarUiState(
                    canSend = false,
                    isTurnRunning = false,
                    isAwaitingAttachments = false,
                    isExpanded = false,
                    modelLabel = "5.3 Thinking Extra High",
                    hasFastTier = false
                ),
                onPlusClick = {},
                onModelClick = {},
                onSendClick = {}
            )
        }

        Text("2. Expanded - Can Send:", fontSize = 12.sp)
        Box(modifier = Modifier.background(colorResource(R.color.app_composer))) {
            ComposerActionBar(
                state = ComposerActionBarUiState(
                    canSend = true,
                    isTurnRunning = false,
                    isAwaitingAttachments = false,
                    isExpanded = true,
                    modelLabel = "5.3 Thinking",
                    hasFastTier = true
                ),
                onPlusClick = {},
                onModelClick = {},
                onSendClick = {}
            )
        }

        Text("3. Awaiting Attachments (Blocked Send):", fontSize = 12.sp)
        Box(modifier = Modifier.background(colorResource(R.color.app_composer))) {
            ComposerActionBar(
                state = ComposerActionBarUiState(
                    canSend = false,
                    isTurnRunning = false,
                    isAwaitingAttachments = true,
                    isExpanded = true,
                    modelLabel = "5.3 Thinking",
                    sendButtonContentDescription = "Send unavailable until attachments are ready"
                ),
                onPlusClick = {},
                onModelClick = {},
                onSendClick = {}
            )
        }

        Text("4. Running Turn (Stop Action):", fontSize = 12.sp)
        Box(modifier = Modifier.background(colorResource(R.color.app_composer))) {
            ComposerActionBar(
                state = ComposerActionBarUiState(
                    canSend = true,
                    isTurnRunning = true,
                    isAwaitingAttachments = false,
                    isExpanded = true,
                    modelLabel = "5.3 Thinking Extra High",
                    sendButtonContentDescription = "Stop response"
                ),
                onPlusClick = {},
                onModelClick = {},
                onSendClick = {}
            )
        }

        Text("5. Long Model Name (Ellipsis truncation):", fontSize = 12.sp)
        Box(modifier = Modifier.background(colorResource(R.color.app_composer))) {
            ComposerActionBar(
                state = ComposerActionBarUiState(
                    canSend = true,
                    isTurnRunning = false,
                    isAwaitingAttachments = false,
                    isExpanded = true,
                    modelLabel = "Codex Pro 2026 Ultra High Extended Context Preview",
                    hasFastTier = true
                ),
                onPlusClick = {},
                onModelClick = {},
                onSendClick = {}
            )
        }
    }
}

@Preview(name = "Composer Action Bar - Light", showBackground = true, backgroundColor = 0xFFF4F4F4)
@Composable
fun ComposerActionBarPreviewLight() {
    Surface {
        SampleComposerActionBarContent()
    }
}

@Preview(
    name = "Composer Action Bar - Dark",
    showBackground = true,
    backgroundColor = 0xFF121212,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
fun ComposerActionBarPreviewDark() {
    Surface {
        SampleComposerActionBarContent()
    }
}
